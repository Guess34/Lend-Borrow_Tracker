package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;

import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import net.runelite.client.ui.ColorScheme;

/**
 * Unified group service that handles:
 * 1. Account-specific group storage with JSON persistence (from GroupConfigStore)
 * 2. Group creation, invite code management, member management (from GroupManager)
 * 3. Real-time synchronization of group data across members (from GroupSyncService)
 *
 * IMPORTANT: Group data is stored PER-ACCOUNT to prevent bleeding between accounts.
 * Each account has its own list of groups they are a member of.
 *
 * Storage keys use format: "lendingtracker.{accountName}.groups"
 */
@Slf4j
@Singleton
public class GroupService
{
	// --- Config Key Constants ---
	private static final String CFG_GROUP = "lendingtracker";
	private static final String CFG_KEY_GROUPS_SUFFIX = ".groups";
	private static final String CFG_KEY_ACTIVE_SUFFIX = ".activeGroupId";

	// --- Invite Key Constants (shared, not per-account) ---
	private static final String INVITE_KEY_PREFIX = "invite.";

	// --- Sync Constants ---
	private static final String SYNC_KEY_PREFIX = "sync.";
	private static final String SYNC_EVENTS_SUFFIX = ".events";
	private static final String SYNC_GROUP_SUFFIX = ".group";
	private static final int MAX_SYNC_EVENTS = 100;
	private static final long SYNC_INTERVAL_MS = 5000;

	// --- Injected Dependencies ---
	@Inject private ConfigManager configManager;
	@Inject private Client client;
	@Inject private Gson gson;
	@Inject private DataService dataService;
	@Inject private RelaySyncService relaySyncService;
	@Inject private LendingTrackerConfig config;

	// --- Group State ---
	private final Map<String, LendingGroup> groups = new java.util.concurrent.ConcurrentHashMap<>();
	private String activeGroupId;
	private String currentAccountName = null;

	// --- Sync State ---
	private ScheduledExecutorService syncExecutor;
	// Volatile: written by the client thread (startSync/stopSync) but read by the
	// sync executor and the OkHttp ws-callback thread, which now act on it (the
	// catch-up target re-check and the publish gate).
	private volatile String currentSyncGroupId;
	private String currentSyncPlayerName;
	private long lastSyncTimestamp = 0;
	private Runnable onSyncCallback;
	// Told when a group disappears out from under the player, so the plugin
	// can say so. Without it the group just vanishes from the dropdown with
	// no explanation - true of a disband AND of being kicked.
	// How recent a removal has to be before it is worth announcing, and how
	// many to announce at once. A member returning after a week away merges
	// every kick that happened while they were gone in one go - without
	// these they would get one notification per kick, all at once, each
	// worded as though it had just happened.
	private static final long REMOVAL_NOTICE_WINDOW_MS = 10L * 60000L;
	private static final int MAX_REMOVAL_NOTICES = 3;
	// The cap above is per merge, but a staff clear-out arrives as one publish
	// per kick - twenty kicks, twenty merges, sixty notifications. This budget
	// spans merges: at most MAX_REMOVAL_NOTICES a minute, the rest rolled up.
	private static final long REMOVAL_NOTICE_BUDGET_MS = 60000L;
	private final Deque<Long> recentRemovalNotices = new ArrayDeque<>();
	private final Set<String> announcedRemovals = ConcurrentHashMap.newKeySet();
	private int pendingRemovalNotices;
	private long lastRemovalRollupAt;

	// Tells the user when a group they were in has gone, or who has left one.
	private java.util.function.Consumer<String> onGroupGone;

	public void setOnGroupGone(java.util.function.Consumer<String> callback)
	{
		this.onGroupGone = callback;
	}
	private java.util.function.Consumer<SyncEvent> onWildernessAlert;

	// The group whose stored snapshot we have reconciled with since connecting.
	// Publishing before that has happened is what makes a brief disconnect
	// destructive: the relay keeps ONE record per group and overwrites it with
	// whatever arrives, so uploading a view that predates changes made while we
	// were away rolls them back for every member, not just for us. Written from
	// the sync executor, read from the ws callback thread — hence volatile.
	private volatile String caughtUpGroupId;

	// Groups we have sent a disband tombstone for. The 5-minute heartbeat and any
	// user action keep pushing state from other threads; one of those landing
	// after the tombstone would overwrite the stored record with the live group,
	// and members who were offline would come back to a group everyone else has
	// lost. Kept for the rest of the session when the disband went out but could
	// not be confirmed - the group stays on this client so the owner can retry,
	// and it must stay silent until they do.
	private final Set<String> disbandingGroupIds = ConcurrentHashMap.newKeySet();

	// One catch-up retry chain at a time. pollForUpdates ticks every 5 seconds
	// and would otherwise start a fresh 6-attempt chain on each tick whenever we
	// aren't caught up — hundreds of overlapping blocking REST calls piling onto
	// the single sync thread during a relay outage. Ownership is a token, not a
	// boolean: a stale chain waking from a 90-second fetch may only release its
	// OWN claim, never one a newer chain holds.
	private final java.util.concurrent.atomic.AtomicLong catchUpOwner =
		new java.util.concurrent.atomic.AtomicLong(0);
	private final java.util.concurrent.atomic.AtomicLong catchUpTokens =
		new java.util.concurrent.atomic.AtomicLong(0);

	// Bumped every time the relay connection drops. A catch-up whose fetch began
	// on an older connection must not mark us reconciled: peers can have changed
	// the stored record during the outage, and its pre-drop read says nothing
	// about what is there now.
	private final java.util.concurrent.atomic.AtomicLong connectionEpoch =
		new java.util.concurrent.atomic.AtomicLong(0);

	// --- Relay-authoritative presence ---
	// The relay knows exactly which members hold an open websocket to the group's
	// room and broadcasts that list; a member is online iff they are in it, with no
	// friends-list relationship required. Keyed by lower-cased name -> world (0 if
	// unknown). Held as a single volatile reference to an immutable map and swapped
	// WHOLESALE, so the Swing EDT (which reads it while building the roster) always
	// sees a complete map — never a half-updated one mid clear()/putAll().
	private volatile Map<String, Integer> relayPresence = java.util.Collections.emptyMap();

	// --- Initialization & Account Lifecycle ---

	public void initialize()
	{
		groups.clear();
		activeGroupId = null;
		currentAccountName = null;
	}

	public void onAccountLogin(String accountName)
	{
		if (accountName == null || accountName.isEmpty())
		{
			log.warn("onAccountLogin called with null/empty accountName");
			return;
		}

		String normalizedName = accountName.toLowerCase().replace(" ", "_");

		if (normalizedName.equals(currentAccountName) && !groups.isEmpty())
		{
			return;
		}

		currentAccountName = normalizedName;
		groups.clear();
		activeGroupId = null;

		loadGroups();
		loadActiveGroup();
	}

	public boolean isLoggedIn()
	{
		try
		{
			if (client == null || client.getGameState() != GameState.LOGGED_IN)
			{
				return false;
			}
			if (client.getLocalPlayer() != null)
			{
				String name = client.getLocalPlayer().getName();
				return name != null && !name.isEmpty();
			}
		}
		catch (Exception e)
		{
			log.debug("Error checking login status", e);
		}
		return false;
	}

	public boolean hasCurrentAccount()
	{
		return currentAccountName != null && !currentAccountName.isEmpty();
	}

	// --- Active Group Management ---

	public LendingGroup getActiveGroup()
	{
		if (!isLoggedIn() || activeGroupId == null)
		{
			return null;
		}
		return groups.get(activeGroupId);
	}

	public LendingGroup getActiveGroupUnchecked()
	{
		return activeGroupId != null ? groups.get(activeGroupId) : null;
	}

	public String getCurrentGroupId()
	{
		return isLoggedIn() ? activeGroupId : null;
	}

	public String getCurrentGroupIdUnchecked()
	{
		return activeGroupId;
	}

	public void setCurrentGroupId(String id)
	{
		if (id != null && groups.containsKey(id))
		{
			activeGroupId = id;
			saveActiveGroup();
		}
	}

	// --- Group CRUD ---

	/**
	 * Create a new lending group with input validation.
	 * @return The group ID if successful, null if name is taken or inputs invalid
	 */
	public String createGroup(String name, String description, String ownerName)
	{
		if (name == null || name.trim().isEmpty())
		{
			throw new IllegalArgumentException("Group name cannot be empty");
		}
		if (ownerName == null || ownerName.trim().isEmpty())
		{
			throw new IllegalArgumentException("Owner name cannot be empty");
		}

		ensureCurrentAccount();

		if (isGroupNameTaken(name))
		{
			return null;
		}

		String id = UUID.randomUUID().toString().substring(0, 8);
		LendingGroup g = new LendingGroup(id, name, description);

		GroupMember owner = new GroupMember(ownerName, "owner");
		g.setFounderName(ownerName);
		g.setFounderUpdatedAt(System.currentTimeMillis());
		g.addMember(owner);
		touchRoster(g);

		groups.put(id, g);
		activeGroupId = id;
		saveActiveGroup();
		saveGroups();
		return id;
	}

	/** Outcome of a disband, so the UI can tell the user what actually happened. */
	public enum DisbandResult
	{
		/** Published, confirmed stored, and torn down here. */
		DONE,
		/**
		 * Sent, but we could not confirm the server stored it. Members who were
		 * online have already lost the group. We keep it here, silent, so the
		 * owner can press Delete again - tearing it down now would throw away the
		 * only key that can still finish the job for members who were offline.
		 */
		SENT_UNCONFIRMED,
		NOT_ALLOWED,       // not an owner or founder
		LOANS_OUTSTANDING, // something is still lent out somewhere in the group
		NOT_SYNCED,        // sync is on, but we are offline or not reconciled yet
		NOT_SENT,          // could not put the message on the wire; nothing changed
		LOCAL_ONLY         // sync is off and the group has other members - refused
	}

	/**
	 * Disband a group for EVERYONE, not just for us.
	 *
	 * The tombstone - an empty roster with every member recorded as removed, and no
	 * listing or loan data - is an ORDINARY SIGNED MESSAGE. The relay is not asked
	 * to do anything privileged and is not trusted to decide who may disband what;
	 * it cannot, as it holds no group key. Only a holder of that key can produce a
	 * tombstone other clients will accept, so knowing a group id is not enough to
	 * destroy somebody else's group.
	 *
	 * THERE IS NO ABORT, and the code is shaped around that. The relay forwards a
	 * state message to everyone in the room the instant it arrives, before and
	 * regardless of whether it stores it - so the moment the message is on the wire
	 * every online member has already dropped the group. Publishing IS the
	 * irreversible act. What follows can only tell us how completely it took
	 * effect, never undo it. We tear down here only once the server confirms it
	 * holds the tombstone; until then this client keeps the group and its key,
	 * silent, because it is the only thing left that can finish the disband for
	 * members who were offline. Pressing Delete again simply re-sends it.
	 *
	 * Blocking - callers must run it off the EDT.
	 */
	public DisbandResult disbandGroup(String groupId, String requester)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null || requester == null) return DisbandResult.NOT_ALLOWED;
		if (!isOwner(groupId, requester) && !hasFounderPower(groupId, requester))
		{
			return DisbandResult.NOT_ALLOWED;
		}
		// Nobody's loan disappears into a disband. This asks about the WHOLE group,
		// not just the owner: disbanding destroys everyone's record of who owes what.
		// It can only see what THIS client knows. A loan only a member's client
		// holds survives anyway: dropping a group never deletes loan records, and
		// the dashboard keeps showing that member their open loans from it.
		if (dataService != null && !dataService.getAllUnsettled(groupId).isEmpty())
		{
			return DisbandResult.LOANS_OUTSTANDING;
		}

		boolean syncOn = relaySyncService != null && config != null && config.enableRelaySync();
		boolean alone = group.getMembers() == null || group.getMembers().size() <= 1;
		if (!syncOn)
		{
			// Deleting locally would destroy our copy of the group key, and with it
			// the only means of ever disbanding it properly - leaving the group
			// stranded on the server with every other member still holding it.
			if (!alone) return DisbandResult.LOCAL_ONLY;
			// A group only we are in has nothing to tell anyone. Safe to just drop.
			deleteGroup(groupId);
			return DisbandResult.DONE;
		}

		// Must be reconciled first, or the tombstone would be built from a view we
		// already know is incomplete.
		if (!canPublishRemoval(groupId)) return DisbandResult.NOT_SYNCED;

		// DEEP copy. A shallow one shares the live member list and tombstone map, so
		// clearing the roster below would empty the real group before the network
		// call - and a send that never left would leave a live group with no members
		// and everyone tombstoned.
		LendingGroup tombstone = gson.fromJson(gson.toJson(group), LendingGroup.class);
		long now = System.currentTimeMillis();
		long latestJoin = 0L;
		if (tombstone.getMembers() != null)
		{
			for (GroupMember m : tombstone.getMembers())
			{
				if (m != null) { latestJoin = Math.max(latestJoin, m.getJoinedAt()); }
			}
			// A tombstone only removes a member when it is NEWER than their joinedAt,
			// and those two stamps come from different machines' clocks. Anyone whose
			// clock ran ahead of ours would otherwise survive the disband entirely.
			long stamp = Math.max(now, latestJoin + 1);
			for (GroupMember m : tombstone.getMembers())
			{
				if (m != null && m.getName() != null) { tombstone.recordRemovalAt(m.getName(), stamp); }
			}
			tombstone.getMembers().clear();
		}
		tombstone.setMembersUpdatedAt(now);
		tombstone.setInviteCode(null);
		tombstone.setInviteCodeGeneratedAt(0);
		tombstone.setClanCode(null);
		tombstone.setClanCodeEnabled(false);
		if (tombstone.getClanCodeUsedBy() != null) { tombstone.getClanCodeUsedBy().clear(); }
		tombstone.touchCodeState();
		tombstone.setDisbandedAt(now);
		tombstone.setDisbandedBy(requester);
		// The stored record outlives the group; don't leave the webhook in it.
		tombstone.setWebhookSealed("");
		tombstone.setWebhookUpdatedAt(now);

		// Silence every other publish for this group BEFORE the tombstone goes, so
		// no heartbeat can land behind it and overwrite the stored record.
		disbandingGroupIds.add(groupId);
		RelaySyncService.PublishOutcome sent =
			relaySyncService.publishTombstoneBlocking(groupId, relayGroupJson(tombstone), requester);
		if (sent == RelaySyncService.PublishOutcome.NOT_SENT)
		{
			// Never reached the wire, so nothing was broadcast and nothing changed.
			disbandingGroupIds.remove(groupId);
			return DisbandResult.NOT_SENT;
		}

		// On the wire: online members have dropped the group, so retire the codes
		// either way - nobody should be joining it now. A joiner who slips in
		// before this lands reads the tombstone on catch-up and drops it too
		// (forgetGroupIfRemovedReturnsGone treats disbandedAt as removal for all).
		revokeGroupCodes(group);

		if (sent != RelaySyncService.PublishOutcome.CONFIRMED)
		{
			// Can't tell whether the server kept it. Keep the group, and its key,
			// so a retry can finish the job; tearing down here would leave members
			// who were offline holding a group nobody can ever disband.
			return DisbandResult.SENT_UNCONFIRMED;
		}

		// Clearing our own roster first makes deleteGroup skip its leave-and-publish
		// step: the tombstone said far more than a MEMBER_LEFT would, and that step
		// is gated on still being reconciled - which can lapse during the network
		// call and would otherwise abandon the teardown halfway.
		if (group.getMembers() != null) { group.getMembers().clear(); }
		deleteGroup(groupId);
		disbandingGroupIds.remove(groupId);
		return DisbandResult.DONE;
	}

	/**
	 * The row a joiner adds for themselves. Invite records carry no roster, so the
	 * joiner cannot tell whether they are already in the group - a staff member
	 * setting up a second machine joins exactly like a stranger. joinedAt is now,
	 * so a return outranks an old removal; the role is stamped as old as possible,
	 * so this guess never outranks a role anyone actually set. Stamped "now", it
	 * demoted every existing staff member who joined from a new machine.
	 */
	private static GroupMember joinerRow(String playerName)
	{
		GroupMember row = new GroupMember(playerName, "member");
		row.setRoleUpdatedAt(1L);
		return row;
	}

	/** Kill every code that could still be used to re-adopt this group. */
	private void revokeGroupCodes(LendingGroup group)
	{
		for (String code : new String[] { group.getClanCode(), group.getInviteCode() })
		{
			if (code == null || code.isEmpty()) continue;
			configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + code);
			if (relaySyncService != null) { relaySyncService.consumeInviteCode(code); }
		}
	}

	/**
	 * Delete our copy of a group. Returns false when the departure could not be
	 * published - deleting silently would leave us a live member (and possibly the
	 * founder) of a group we no longer hold, with no local copy to fix it from.
	 */
	public boolean deleteGroup(String id)
	{
		LendingGroup doomed = groups.get(id);
		String self = currentSyncPlayerName != null ? currentSyncPlayerName : currentAccountName;
		if (doomed != null && self != null && doomed.hasMember(self))
		{
			if (!canPublishRemoval(id)) return false;
			dataService.removeItemsForLender(id, self);
			dataService.removeRequestsInvolving(id, self);
			doomed.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(self));
			doomed.recordRemoval(self);
			touchRoster(doomed);
			publishEvent(SyncEventType.MEMBER_LEFT, id + ":" + self, null);
		}
		// Stop syncing FIRST. Clearing the group while still caught up lets a later
		// push publish an empty snapshot, which overwrites the stored catch-up record
		// on the relay and wipes the group for every remaining member.
		// Capture before stopSync nulls it - the replacement group needs it.
		String me = currentSyncPlayerName != null ? currentSyncPlayerName : currentAccountName;
		// Clear the DATA here, after sync has stopped - not in the caller. Doing it
		// first published an empty snapshot (clearGroupData fires ITEM_REMOVED, which
		// pushes state while still caught up), overwriting the stored record and
		// wiping the group for every remaining member.
		if (id != null && id.equals(currentSyncGroupId))
		{
			stopSync();
		}
		if (dataService != null)
		{
			dataService.clearGroupData(id);
			dataService.clearItemSetData(id);
		}
		groups.remove(id);
		String replacement = null;
		if (Objects.equals(activeGroupId, id))
		{
			activeGroupId = groups.isEmpty() ? null : groups.keySet().iterator().next();
			replacement = activeGroupId;
			saveActiveGroup();
		}
		saveGroups();
		// Same omission the leave path had: without this the client sits on a group
		// that is selected but neither loaded nor syncing, until the user relogs.
		if (replacement != null && me != null)
		{
			dataService.loadGroupData(replacement);
			startSync(replacement, me);
		}
		return true;
	}

	public LendingGroup getGroup(String id)
	{
		return groups.get(id);
	}

	public Collection<LendingGroup> getAllGroups()
	{
		return Collections.unmodifiableCollection(groups.values());
	}

	public String getGroupNameById(String id)
	{
		LendingGroup group = groups.get(id);
		return group != null ? group.getName() : null;
	}

	private LendingGroup getGroupByName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return null;
		}
		return groups.values().stream()
			.filter(g -> name.equals(g.getName()))
			.findFirst()
			.orElse(null);
	}

	/** @return "success", "not_member", or "not_found" */
	public String switchToGroup(String groupName, String playerName)
	{
		LendingGroup group = getGroupByName(groupName);
		if (group == null)
		{
			return "not_found";
		}

		if (group.getMembers() != null)
		{
			boolean isMember = group.getMembers().stream()
				.anyMatch(m -> m.getName().equalsIgnoreCase(playerName));
			if (isMember)
			{
				setCurrentGroupId(group.getId());
				return "success";
			}
		}

		return "not_member";
	}

	// --- Members & Roles ---

	/**
	 * Advance the roster version stamp. Call on any real change to the members
	 * list so {@link #handleRelayState} adopts it over peers' older rosters.
	 */
	private void touchRoster(LendingGroup g)
	{
		if (g != null)
		{
			g.setMembersUpdatedAt(System.currentTimeMillis());
		}
	}

	/**
	 * Wrap a group's member list in a CopyOnWriteArrayList before it enters the
	 * shared {@code groups} map. Gson deserializes members as a plain ArrayList,
	 * which is not safe against the concurrent roster reads/writes sync performs.
	 */
	private LendingGroup ensureCowMembers(LendingGroup g)
	{
		if (g != null)
		{
			g.setMembers(new java.util.concurrent.CopyOnWriteArrayList<>(
				g.getMembers() != null ? g.getMembers() : new ArrayList<>()));
		}
		return g;
	}

	/**
	 * Union-merge a remote roster into the local group. Members present remotely
	 * but not locally are added; when the remote roster is at least as new as the
	 * local one, role and permission changes are adopted. Members are never
	 * removed here — kicks propagate through the normal removeMember path, not by
	 * letting a stale peer's roster overwrite ours.
	 *
	 * Builds a fresh member list and swaps it in atomically so a reader iterating
	 * the roster on another thread never sees a torn list.
	 */
	private void mergeRoster(LendingGroup local, LendingGroup remote)
	{
		List<GroupMember> merged = new java.util.concurrent.CopyOnWriteArrayList<>(
			local.getMembers() != null ? local.getMembers() : new ArrayList<>());
		boolean remoteIsNewer = remote.getMembersUpdatedAt() >= local.getMembersUpdatedAt();

		// Union the kick tombstones first (newest removal time wins per name) so
		// the member merge below can test against the combined set. This is how a
		// kick propagates: rosters only ever ADD members, so without tombstones a
		// peer with a stale roster would resurrect anyone we kicked.
		// Snapshot first, so we can tell which kicks are NEW to us and announce
		// only those. The tombstones persist, so diffing is what stops the same
		// removal being announced again on every later merge.
		java.util.Set<String> knownRemovals =
			new java.util.HashSet<>(local.getRemovedMembersSafe().keySet());
		Map<String, Long> tombstones = new HashMap<>(local.getRemovedMembersSafe());
		for (Map.Entry<String, Long> t : remote.getRemovedMembersSafe().entrySet())
		{
			tombstones.merge(t.getKey(), t.getValue(), Math::max);
		}

		// Tell the group who just lost their place. Skipped entirely for a
		// disband, which tombstones EVERY member at once - announcing that
		// would fire once per person instead of the single message the
		// departing client already shows.
		if (onGroupGone != null && remote.getDisbandedAt() == 0)
		{
			String me = currentSyncPlayerName != null ? currentSyncPlayerName : currentAccountName;
			long now = System.currentTimeMillis();
			long announceAfter = now - REMOVAL_NOTICE_WINDOW_MS;
			List<String> notices = new ArrayList<>();
			for (String key : tombstones.keySet())
			{
				if (knownRemovals.contains(key)) continue;
				if (me != null && key.equals(nameKey(me))) continue;   // our own exit is announced separately
				// Only for someone we actually had on the roster - otherwise a
				// peer's old tombstones would announce strangers on first sync.
				final String k = key;
				GroupMember gone = merged.stream()
					.filter(m -> m.getName() != null && k.equals(nameKey(m.getName())))
					.findFirst().orElse(null);
				if (gone == null) continue;
				// Apply the SAME test the removal itself uses. A tombstone a re-join has
				// already outranked leaves the member on the roster - announcing it would
				// tell the group somebody was kicked who is standing right there, and
				// would do it again every time a peer with the stale copy syncs.
				Long removedAt = tombstones.get(key);
				if (removedAt == null || removedAt <= gone.getJoinedAt()) continue;
				if (removedAt < announceAfter) continue;   // history, not news
				// Once per removal, however many peers relay it to us.
				if (!announcedRemovals.add(local.getId() + ":" + key + ":" + removedAt)) continue;
				// "No longer in" rather than "removed": leaving voluntarily
				// leaves the same tombstone as a kick, and we can't tell them apart.
				notices.add(gone.getName() + " is no longer in "
					+ (local.getName() != null ? "'" + local.getName() + "'" : "the group") + ".");
			}
			synchronized (recentRemovalNotices)
			{
				while (!recentRemovalNotices.isEmpty()
					&& recentRemovalNotices.peekFirst() < now - REMOVAL_NOTICE_BUDGET_MS)
				{
					recentRemovalNotices.pollFirst();
				}
				for (String notice : notices)
				{
					if (recentRemovalNotices.size() >= MAX_REMOVAL_NOTICES)
					{
						pendingRemovalNotices++;
						continue;
					}
					recentRemovalNotices.addLast(now);
					onGroupGone.accept(notice);
				}
				if (pendingRemovalNotices > 0 && now - lastRemovalRollupAt >= REMOVAL_NOTICE_BUDGET_MS)
				{
					onGroupGone.accept("...and " + pendingRemovalNotices + " other member"
						+ (pendingRemovalNotices == 1 ? " is" : "s are") + " no longer in the group.");
					pendingRemovalNotices = 0;
					lastRemovalRollupAt = now;
				}
			}
		}

		if (remote.getMembers() != null)
		{
			for (GroupMember rm : remote.getMembers())
			{
				// Don't adopt a member who was kicked after they joined — that's a
				// stale roster echoing someone we removed. (A re-join carries a
				// fresh joinedAt newer than the tombstone, so it survives.)
				Long removedAt = tombstones.get(nameKey(rm.getName()));
				if (removedAt != null && removedAt > rm.getJoinedAt())
				{
					continue;
				}

				GroupMember existing = merged.stream()
					.filter(m -> m.getName().equalsIgnoreCase(rm.getName()))
					.findFirst().orElse(null);
				if (existing == null)
				{
					merged.add(rm);
				}
				// Strict > only. Treating a remote 0 as "unversioned, defer to the roster
				// stamp" let any peer holding a legacy row overwrite a REAL stamp with 0,
				// zeroing the whole group within one sync round and switching per-row
				// versioning back off. loadGroups now backfills legacy rows to 1, so 0
				// never appears here and any genuine change (now()) outranks them. Cost: a
				// role change made on a not-yet-updated client does not reach updated
				// clients during rollout - the safe direction, since it can never REVERT.
				else if (rm.getRole() != null && rm.getRoleUpdatedAt() > existing.getRoleUpdatedAt())
				{
					// Per-row versioning, NOT the whole-roster stamp: a demoted owner who
					// has not seen the demotion republishes their old role, and under the
					// shared stamp that restored it for everyone. Their stale row now
					// loses to the change it never saw.
					existing.setRole(rm.getRole());
					existing.setRoleUpdatedAt(Math.max(rm.getRoleUpdatedAt(), existing.getRoleUpdatedAt()));
				}
				if (existing != null && rm.getDiscordUpdatedAt() > existing.getDiscordUpdatedAt())
				{
					existing.setDiscordSealed(rm.getDiscordSealed());
					existing.setDiscordUpdatedAt(rm.getDiscordUpdatedAt());
				}
			}
		}

		// Apply tombstones to what we already had: this is the receiving side of a
		// kick performed on another machine.
		merged.removeIf(m ->
		{
			Long removedAt = tombstones.get(nameKey(m.getName()));
			return removedAt != null && removedAt > m.getJoinedAt();
		});

		// Prune tombstones that a re-join has outdated, so the map can't grow
		// stale entries forever.
		for (GroupMember m : merged)
		{
			Long removedAt = tombstones.get(nameKey(m.getName()));
			if (removedAt != null && m.getJoinedAt() >= removedAt)
			{
				tombstones.remove(nameKey(m.getName()));
			}
		}
		local.setRemovedMembers(tombstones);

		// Founder carries its OWN stamp. Riding membersUpdatedAt was wrong: that is
		// bumped by every roster action, so a peer who only toggled a permission would
		// out-stamp a founder transfer and restore the previous founder permanently.
		// Ties break on the name so two clients cannot ping-pong forever.
		long remoteFounderAt = remote.getFounderUpdatedAt();
		long localFounderAt = local.getFounderUpdatedAt();
		boolean takeRemoteFounder;
		// Never adopt a founder who is not actually in the roster: isFounder requires
		// membership, so taking an orphan name strands the group with nobody able to
		// demote an owner and no way to recover.
		if (remote.getFounderName() == null
			|| !isMemberIn(merged, remote.getFounderName())) takeRemoteFounder = false;
		else if (remoteFounderAt > localFounderAt) takeRemoteFounder = true;
		else if (remoteFounderAt < localFounderAt) takeRemoteFounder = false;
		else if (local.getFounderName() == null) takeRemoteFounder = true;
		else
		{
			// Equal stamps means both sides guessed via the backfill. Prefer whoever
			// the MERGED roster actually shows as an owner - deciding by name could
			// hand permanent, undemotable authority to someone who never held the role
			// (two clients disagreeing about the owner backfill different names).
			boolean remoteIsOwner = isOwnerIn(merged, remote.getFounderName());
			boolean localIsOwner = isOwnerIn(merged, local.getFounderName());
			takeRemoteFounder = (remoteIsOwner != localIsOwner)
				? remoteIsOwner
				: remote.getFounderName().compareToIgnoreCase(local.getFounderName()) > 0;
		}
		if (takeRemoteFounder)
		{
			local.setFounderName(remote.getFounderName());
			local.setFounderUpdatedAt(remoteFounderAt);
		}
		// Adopt the disband stamp. Without this it only ever existed on the
		// disbanding client's own throwaway copy, so every OTHER member read
		// 0 and was told they had been individually kicked - the exact
		// confusion the message was added to prevent.
		if (remote.getDisbandedAt() > local.getDisbandedAt())
		{
			local.setDisbandedAt(remote.getDisbandedAt());
			local.setDisbandedBy(remote.getDisbandedBy());
		}
		// The group's Discord webhook rides its own stamp, newest wins. A client
		// that predates it relays stamp 0, which never clears it. Exact-tie breaks
		// on the value so both sides settle on the same one.
		long remoteHookAt = remote.getWebhookUpdatedAt();
		long localHookAt = local.getWebhookUpdatedAt();
		if (remoteHookAt > localHookAt
			|| (remoteHookAt == localHookAt && remoteHookAt > 0
				&& String.valueOf(remote.getWebhookSealed()).compareTo(String.valueOf(local.getWebhookSealed())) > 0))
		{
			local.setWebhookSealed(remote.getWebhookSealed());
			local.setWebhookUpdatedAt(remoteHookAt);
			local.setWebhookSetBy(remote.getWebhookSetBy());
		}
		long remoteScopeAt = remote.getLoanScopeUpdatedAt();
		long localScopeAt = local.getLoanScopeUpdatedAt();
		if (remoteScopeAt > localScopeAt
			|| (remoteScopeAt == localScopeAt && remoteScopeAt > 0
				&& String.valueOf(remote.getLoanScope()).compareTo(String.valueOf(local.getLoanScope())) > 0))
		{
			local.setLoanScope(remote.getLoanScope());
			local.setLoanScopeUpdatedAt(remoteScopeAt);
		}
		local.setWebhookTestedFor(Math.max(local.getWebhookTestedFor(), remote.getWebhookTestedFor()));
		long remoteClanAt = remote.getLinkedClanUpdatedAt();
		long localClanAt = local.getLinkedClanUpdatedAt();
		if (remoteClanAt > localClanAt
			|| (remoteClanAt == localClanAt && remoteClanAt > 0
				&& String.valueOf(remote.getLinkedClan()).compareTo(String.valueOf(local.getLinkedClan())) > 0))
		{
			local.setLinkedClan(remote.getLinkedClan());
			local.setLinkedClanUpdatedAt(remoteClanAt);
			local.setLinkedClanSetBy(remote.getLinkedClanSetBy());
		}
		local.setMembers(merged);
		local.setMembersUpdatedAt(Math.max(local.getMembersUpdatedAt(), remote.getMembersUpdatedAt()));

		// Union who used the multi-use group code so the owner's "(N used)" counter
		// reflects joins that happened on other machines. Names union cleanly;
		// keep the displayed int in step (never lower it).
		Set<String> usedBy = new HashSet<>(local.getClanCodeUsedBySafe());
		usedBy.addAll(remote.getClanCodeUsedBySafe());
		local.setClanCodeUsedBy(usedBy);
		local.setClanCodeUseCount(Math.max(
			Math.max(local.getClanCodeUseCount(), remote.getClanCodeUseCount()),
			usedBy.size()));

		// Code state is GROUP data: every staff member must see the same single-use
		// code, group code, and open/closed status. Adopt the remote code state
		// wholesale when it's newer — a joiner consuming a code or a staff member
		// rotating/toggling one then propagates to everyone with the panel open.
		// On an exact-millisecond tie (two staff acting at once), break it
		// deterministically by the code-state key so BOTH sides pick the same
		// winner instead of each keeping its own code forever.
		long remoteCodeStamp = remote.getCodeStateUpdatedAt();
		long localCodeStamp = local.getCodeStateUpdatedAt();
		boolean adoptCodeState = remoteCodeStamp > localCodeStamp
			|| (remoteCodeStamp == localCodeStamp && remoteCodeStamp > 0
				&& codeStateKey(remote).compareTo(codeStateKey(local)) > 0);
		if (adoptCodeState)
		{
			String oldInvite = local.getInviteCode();
			String oldClan = local.getClanCode();
			boolean oldClanEnabled = local.isClanCodeEnabled();

			local.setInviteCode(remote.getInviteCode());
			local.setInviteCodeGeneratedAt(remote.getInviteCodeGeneratedAt());
			local.setInviteCodeUsedByName(remote.getInviteCodeUsedByName());
			local.setClanCode(remote.getClanCode());
			local.setClanCodeEnabled(remote.isClanCodeEnabled());
			local.setCodeStateUpdatedAt(remote.getCodeStateUpdatedAt());

			// Retire stale same-machine lookup keys: a code consumed or closed on
			// ANOTHER machine must stop working for alts on this one too.
			if (oldInvite != null && !oldInvite.equals(local.getInviteCode()))
			{
				configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + oldInvite);
			}
			if (oldClan != null && oldClanEnabled
				&& (!local.isClanCodeEnabled() || !oldClan.equals(local.getClanCode())))
			{
				configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + oldClan);
			}
			// And mirror an OPEN group code / active invite for same-machine joins
			// here, like the machine that opened it does.
			if (local.isClanCodeEnabled() && local.getClanCode() != null && !local.getClanCode().isEmpty())
			{
				configManager.setConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + local.getClanCode(), gson.toJson(local));
			}
			if (local.hasActiveInviteCode())
			{
				configManager.setConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + local.getInviteCode(), gson.toJson(local));
			}
		}

		if (remoteIsNewer)
		{
			local.setCoOwnerCanKick(remote.isCoOwnerCanKick());
			local.setAdminCanKick(remote.isAdminCanKick());
			local.setModCanKick(remote.isModCanKick());
			local.setCoOwnerCanInvite(remote.isCoOwnerCanInvite());
			local.setAdminCanInvite(remote.isAdminCanInvite());
			local.setModCanInvite(remote.isModCanInvite());
		}
	}

	/** Stable key for a group's code state — used only to break exact-timestamp ties. */
	private static String codeStateKey(LendingGroup g)
	{
		return (g.getInviteCode() == null ? "" : g.getInviteCode())
			+ "|" + (g.getClanCode() == null ? "" : g.getClanCode())
			+ "|" + g.isClanCodeEnabled();
	}

	public void addMember(String groupId, String name, String role)
	{
		LendingGroup g = groups.get(groupId);
		if (g == null) return;
		if (g.getMembers() == null) g.setMembers(new ArrayList<>());
		boolean exists = g.getMembers().stream().anyMatch(m -> m.getName().equalsIgnoreCase(name));
		if (!exists)
		{
			// Model addMember also clears any kick tombstone, so a re-invited
			// member isn't immediately re-removed by sync.
			g.addMember(new GroupMember(name, role));
			touchRoster(g);
			saveGroups();
			publishEvent(SyncEventType.MEMBER_JOINED, groupId + ":" + name, null);
		}
	}

	/**
	 * Restore a whole group from a LOCAL BACKUP file, preserving its identity.
	 * Recreating via createGroup minted a NEW random id, which forked the group
	 * from its sync room, lost the syncSecret and kick tombstones, and orphaned
	 * the per-group data still keyed by the original id. Restoring the object
	 * as-is keeps the same room, secret, roster, and tombstones. No-op if a group
	 * with this id already exists locally.
	 */
	public void restoreGroupFromBackup(LendingGroup backupGroup)
	{
		if (backupGroup == null || backupGroup.getId() == null) return;
		if (groups.containsKey(backupGroup.getId())) return;

		// Same backfill loadGroups does: a pre-HMAC backup has no syncSecret, and
		// without one this group would sync unsigned for the rest of the session.
		backupGroup.ensureSyncSecret();
		groups.put(backupGroup.getId(), ensureCowMembers(backupGroup));
		saveGroups();
	}

	/**
	 * Re-add a member from a LOCAL BACKUP file (not a live join). Unlike
	 * {@link #addMember}, this must NOT mint a fresh joinedAt or clear kick
	 * tombstones: the backup predates whatever happened while we were offline,
	 * so a member kicked in the meantime has a tombstone NEWER than their
	 * backed-up joinedAt and must stay removed — otherwise a stale backup would
	 * resurrect them (and, worse, push the resurrection to the whole group).
	 */
	public void restoreMemberFromBackup(String groupId, GroupMember backupMember)
	{
		if (backupMember == null || backupMember.getName() == null) return;
		LendingGroup g = groups.get(groupId);
		if (g == null) return;
		if (g.hasMember(backupMember.getName())) return;

		Long removedAt = g.getRemovedMembersSafe().get(backupMember.getName().toLowerCase());
		if (removedAt != null && removedAt > backupMember.getJoinedAt())
		{
			// Kicked after this backup was taken — the tombstone wins.
			return;
		}

		if (g.getMembers() == null) g.setMembers(new java.util.concurrent.CopyOnWriteArrayList<>());
		// Keep the ORIGINAL joinedAt (0 for pre-update backups) so a tombstone
		// learned later via sync still outranks this restore.
		g.getMembers().add(backupMember);
		touchRoster(g);
		saveGroups();
		publishEvent(SyncEventType.MEMBER_JOINED, groupId + ":" + backupMember.getName(), null);
	}

	/**
	 * True when a removal published right now would actually reach the relay.
	 *
	 * Leaving and kicking both write a tombstone and then rely on pushStateToRelay
	 * to carry it. That push is gated on having reconciled first, so during a cold
	 * start or a reconnect the tombstone is silently dropped - and the leaver then
	 * deletes the group locally, leaving no copy to republish from. They would be
	 * gone on their own machine and still a member everywhere else, permanently.
	 * Callers check this and refuse rather than lose the removal.
	 */
	public boolean canPublishRemoval(String groupId)
	{
		if (relaySyncService == null || !config.enableRelaySync()) return true;  // local-only setup
		if (!relaySyncService.isConnected()) return false;
		return groupId != null && groupId.equals(caughtUpGroupId);
	}

	public void removeMember(String groupId, String name)
	{
		LendingGroup g = groups.get(groupId);
		if (g == null || g.getMembers() == null) return;
		g.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(name));
		// Tombstone the removal so it propagates: without it the union-only roster
		// merge would let any peer with a stale roster re-add the member.
		g.recordRemoval(name);
		touchRoster(g);
		saveGroups();
		// Publish BEFORE forgetting the group locally: dropping it first would clear
		// currentSyncGroupId and leave publishEvent with nothing to send, so the
		// tombstone would never reach the relay and peers would keep showing us as
		// a member forever.
		publishEvent(SyncEventType.MEMBER_LEFT, groupId + ":" + name, null);
		forgetGroupIfRemoved(groupId, name);
	}

	/**
	 * Drop a group from local state once the named player is tombstoned out of it
	 * and that player is us. Without this the group stayed in the dropdown after
	 * leaving (and after being kicked from another machine), still selectable and
	 * still syncing a group we are no longer in.
	 *
	 * Deliberately gated on the TOMBSTONE rather than on mere absence from the
	 * roster: a snapshot that simply predates our join would otherwise delete the
	 * group out from under us. Same test the roster merge uses.
	 */
	private void forgetGroupIfRemoved(String groupId, String name)
	{
		String me = currentSyncPlayerName != null ? currentSyncPlayerName : currentAccountName;
		if (me == null || name == null || !me.equalsIgnoreCase(name)) return;
		forgetGroupIfRemovedReturnsGone(groupId);
	}

	/**
	 * True when an incoming group copy carries a tombstone removing US that we
	 * haven't out-joined. Used to refuse re-adopting a group we left or were
	 * kicked from. Mirrors the test in mergeRoster so both agree on what "removed"
	 * means; a genuine re-join carries a fresh joinedAt and is not blocked.
	 */
	private boolean wasRemovedFrom(LendingGroup remote)
	{
		String me = currentSyncPlayerName != null ? currentSyncPlayerName : currentAccountName;
		if (remote == null) return false;
		if (remote.getDisbandedAt() > 0) return true;   // gone for everyone
		if (me == null) return false;
		Long removedAt = remote.getRemovedMembersSafe().get(nameKey(me));
		if (removedAt == null) return false;
		long myJoinedAt = 0L;
		if (remote.getMembers() != null)
		{
			for (GroupMember m : remote.getMembers())
			{
				if (m.getName() != null && m.getName().equalsIgnoreCase(me))
				{
					myJoinedAt = m.getJoinedAt();
					break;
				}
			}
		}
		return removedAt > myJoinedAt;
	}

	/**
	 * Same check keyed on the local player rather than a named one, for the paths
	 * where a removal arrives from a peer. Returns true when the group was
	 * dropped, so callers can stop touching state that no longer exists.
	 */
	private boolean forgetGroupIfRemovedReturnsGone(String groupId)
	{
		if (groupId == null) return false;
		String me = currentSyncPlayerName != null ? currentSyncPlayerName : currentAccountName;
		if (me == null) return false;

		LendingGroup g = groups.get(groupId);
		if (g == null) return false;
		// A disband is a removal for everyone, including anyone who joined after
		// the owner's last look at the roster and so has no tombstone of their own.
		if (g.getDisbandedAt() <= 0)
		{
			Long removedAt = g.getRemovedMembersSafe().get(nameKey(me));
			if (removedAt == null) return false;
			if (g.hasMember(me)) return false;   // a re-join outdated the tombstone
		}

		// Only the GROUP goes. Its loan records stay: an open loan is still owed
		// whoever removed us, so it stays on the dashboard (under "other groups")
		// and keeps its overdue reminders, which are scoped to the loans we are
		// a party to. Deleting them here would also delete the records of any
		// other account on this machine that is still in the group.
		if (groupId.equals(currentSyncGroupId))
		{
			stopSync();
		}
		if (onGroupGone != null)
		{
			String who = g.getDisbandedBy();
			String label = g.getName() != null ? g.getName() : "a lending group";
			// Neutral on purpose: this also fires when we left from another
			// machine, and "you were removed" reads as a kick.
			onGroupGone.accept(g.getDisbandedAt() > 0
				? "'" + label + "' was disbanded" + (who != null ? " by " + who : "") + "."
				: "You are no longer in '" + label + "'.");
		}
		groups.remove(groupId);
		String replacement = null;
		if (Objects.equals(activeGroupId, groupId))
		{
			activeGroupId = groups.isEmpty() ? null : groups.keySet().iterator().next();
			replacement = activeGroupId;
			saveActiveGroup();
		}
		saveGroups();
		// Without this the client sits on a group that is selected but neither loaded
		// nor syncing: no updates in or out for ANY group, and an empty-looking panel,
		// until the user happens to relog.
		if (replacement != null && me != null)
		{
			dataService.loadGroupData(replacement);
			startSync(replacement, me);
		}
		log.debug("Forgot group {} - no longer a member", groupId);
		return true;
	}

	public boolean removeMemberFromGroup(String groupId, String requesterName, String targetName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		if (!canKick(groupId, requesterName, targetName))
		{
			log.warn("{} doesn't have permission to kick {}", requesterName, targetName);
			return false;
		}

		boolean removed = group.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(targetName));
		if (removed)
		{
			// Tombstone so the kick sticks across machines (see removeMember).
			group.recordRemoval(targetName);
			touchRoster(group);
			saveGroups();
			publishEvent(SyncEventType.MEMBER_LEFT, groupId + ":" + targetName, null);
		}
		return removed;
	}

	public boolean setMemberRole(String groupId, String requesterName, String targetName, String newRole)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		if (!canChangeRole(groupId, requesterName, targetName)) return false;

		boolean promotingToOwner = "owner".equalsIgnoreCase(newRole);
		boolean targetIsOwner = "owner".equalsIgnoreCase(getMemberRole(groupId, targetName));

		if (promotingToOwner)
		{
			// Only an owner makes another owner, and never past the ceiling.
			if (!isOwner(groupId, requesterName) && !hasFounderPower(groupId, requesterName)) return false;
			if (countOwners(group) >= MAX_OWNERS) return false;
		}
		else if (targetIsOwner)
		{
			// Demoting an owner is the founder's privilege alone, and the founder
			// is not demotable - otherwise owners could strip each other and the
			// anti-flooding guarantee disappears.
			if (!hasFounderPower(groupId, requesterName)) return false;
			if (isFounder(groupId, targetName)) return false;
		}
		if ("co-owner".equalsIgnoreCase(newRole) && !isOwner(groupId, requesterName)
			&& !hasFounderPower(groupId, requesterName)) return false;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(targetName))
			{
				member.setRole(newRole.toLowerCase());
				member.setRoleUpdatedAt(System.currentTimeMillis());
				touchRoster(group);
				saveGroups();
				publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
				return true;
			}
		}

		return false;
	}

	public boolean transferOwnership(String groupId, String currentOwnerName, String newOwnerName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;
		if (!isOwner(groupId, currentOwnerName) && !hasFounderPower(groupId, currentOwnerName)) return false;
		if (currentOwnerName.equalsIgnoreCase(newOwnerName)) return false;

		GroupMember currentOwnerMember = null;
		GroupMember newOwnerMember = null;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(currentOwnerName)) currentOwnerMember = member;
			if (member.getName().equalsIgnoreCase(newOwnerName)) newOwnerMember = member;
		}

		if (currentOwnerMember == null || newOwnerMember == null) return false;

		// Only the caller's own ownership moves. Any other owners keep theirs.
		long now = System.currentTimeMillis();
		newOwnerMember.setRole("owner");
		newOwnerMember.setRoleUpdatedAt(now);
		currentOwnerMember.setRole("co-owner");
		currentOwnerMember.setRoleUpdatedAt(now);
		// Founder status deliberately does NOT move here. A founder who hands over
		// the owner role keeps their authority at any rank, right down to plain
		// member; only transferFounder() gives it away.

		touchRoster(group);
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	public String getMemberRole(String groupId, String playerName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return null;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(playerName))
			{
				return member.getRole();
			}
		}
		return null;
	}

	// --- Role Hierarchy & Permissions ---

	public static String[] getAvailableRoles()
	{
		// "owner" is assignable now that a group may have up to MAX_OWNERS of them;
		// setMemberRole enforces who may grant it and the ceiling.
		return new String[] {"owner", "co-owner", "admin", "mod", "member"};
	}

	public static int getRoleRank(String role)
	{
		if (role == null) return 1;
		switch (role.toLowerCase())
		{
			case "owner": return 5;
			case "co-owner": return 4;
			case "admin": return 3;
			case "mod": return 2;
			default: return 1;
		}
	}

	public static String formatRoleName(String role)
	{
		if (role == null || role.isEmpty()) return "Member";
		return Arrays.stream(role.split("-"))
			.map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
			.collect(Collectors.joining("-"));
	}

	public static Color getRoleBackgroundColor(String role)
	{
		switch (role.toLowerCase())
		{
			case "owner": return new Color(255, 215, 0);
			case "co-owner": return new Color(192, 192, 192);
			case "admin": return ColorScheme.BRAND_ORANGE;
			case "mod": return new Color(100, 149, 237);
			default: return ColorScheme.MEDIUM_GRAY_COLOR;
		}
	}

	public static Color getRoleForegroundColor(String role)
	{
		switch (role.toLowerCase())
		{
			case "owner": case "co-owner": return Color.BLACK;
			default: return Color.WHITE;
		}
	}

	/** Hard ceiling on owners. Co-owner and below are uncapped. */
	public static final int MAX_OWNERS = 5;

	/** How many members currently hold the owner role. */
	public int countOwners(LendingGroup group)
	{
		if (group == null || group.getMembers() == null) return 0;
		int n = 0;
		for (GroupMember m : group.getMembers())
		{
			if ("owner".equalsIgnoreCase(m.getRole())) n++;
		}
		return n;
	}

	/**
	 * The founder is the only member who may demote an owner, and may not be
	 * demoted themselves. Groups created before the field existed are backfilled
	 * on load to their sole owner.
	 */
	/**
	 * RuneScape display names use spaces, but many stored forms use underscores;
	 * a tombstone written under one and looked up under the other silently misses.
	 */
	/** Does this roster show the named player as an owner? */
	private static boolean isMemberIn(List<GroupMember> roster, String name)
	{
		if (roster == null || name == null) return false;
		for (GroupMember m : roster)
		{
			if (name.equalsIgnoreCase(m.getName())) return true;
		}
		return false;
	}

	private static boolean isOwnerIn(List<GroupMember> roster, String name)
	{
		if (roster == null || name == null) return false;
		for (GroupMember m : roster)
		{
			if (name.equalsIgnoreCase(m.getName())) return "owner".equalsIgnoreCase(m.getRole());
		}
		return false;
	}

	private static String nameKey(String s)
	{
		return s == null ? null : s.toLowerCase().replace('_', ' ').trim();
	}

	public boolean isFounder(String groupId, String playerName)
	{
		LendingGroup g = groups.get(groupId);
		if (g == null || playerName == null) return false;
		if (!playerName.equalsIgnoreCase(g.getFounderName())) return false;
		// A founder who is no longer in the group holds nothing. Without this a
		// kicked founder would keep full authority over a group they had left.
		return g.hasMember(playerName);
	}

	public boolean isOwner(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "owner");
	}

	public boolean isAdmin(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "owner") ||
			hasRole(groupId, playerName, "admin") ||
			hasRole(groupId, playerName, "moderator");
	}

	public boolean isCoOwner(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "co-owner");
	}

	public boolean isMod(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "mod");
	}

	public boolean canKick(String groupId, String kickerName, String targetName)
	{
		if (groupId == null || kickerName == null || targetName == null) return false;
		if (kickerName.equalsIgnoreCase(targetName)) return false;
		// The founder cannot be removed by anyone. Kicking them would destroy the
		// only authority able to demote an owner - exactly the takeover the founder
		// role exists to prevent. Removal is the R4 vote, deliberately not built yet.
		if (isFounder(groupId, targetName)) return false;
		// Founder authority is otherwise rank-independent (see hasFounderPower).
		if (hasFounderPower(groupId, kickerName)) return true;

		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		String kickerRole = getMemberRole(groupId, kickerName);
		String targetRole = getMemberRole(groupId, targetName);
		if (kickerRole == null || targetRole == null) return false;

		int kickerRank = getRoleRank(kickerRole);
		int targetRank = getRoleRank(targetRole);

		if (kickerRank < 2 || kickerRank <= targetRank) return false;
		if (kickerRank == 5) return true;

		switch (kickerRole.toLowerCase())
		{
			case "co-owner": return group.isCoOwnerCanKick();
			case "admin": return group.isAdminCanKick();
			case "mod": return group.isModCanKick();
			default: return false;
		}
	}

	public enum WebhookResult { SAVED, REMOVED, NOT_ALLOWED, NOT_A_WEBHOOK, NO_GROUP }

	/**
	 * Set or remove (blank url) the group's Discord webhook. Co-owners and up, or
	 * the founder. Stored sealed with the group key and synced to every member,
	 * so each loan posts to the channel of the group it was made in and nowhere else.
	 */
	public WebhookResult setGroupWebhook(String groupId, String requester, String url)
	{
		LendingGroup group = groupId != null ? groups.get(groupId) : null;
		if (group == null || requester == null) return WebhookResult.NO_GROUP;
		boolean founder = hasFounderPower(groupId, requester);
		String role = getMemberRole(groupId, requester);
		if (!founder && (role == null || getRoleRank(role) < 4)) return WebhookResult.NOT_ALLOWED;
		// Never mint a key here: a group without one is not in sync with anybody,
		// and a new key would seal the link so no other member could open it.
		if (group.getSyncSecret() == null || group.getSyncSecret().isEmpty()) return WebhookResult.NO_GROUP;

		String clean = url == null ? "" : url.trim();
		String sealed = "";
		if (!clean.isEmpty())
		{
			if (!DiscordWebhook.isDiscordWebhook(clean)) return WebhookResult.NOT_A_WEBHOOK;
			sealed = WebhookSeal.seal(clean, group.getSyncSecret());
			if (sealed == null) return WebhookResult.NOT_A_WEBHOOK;
		}
		synchronized (group)
		{
			group.setWebhookSealed(sealed);
			group.setWebhookUpdatedAt(System.currentTimeMillis());
			group.setWebhookSetBy(requester);
		}
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return clean.isEmpty() ? WebhookResult.REMOVED : WebhookResult.SAVED;
	}

	/**
	 * Link the group to an in-game clan by its exact name, or unlink it (blank).
	 * Same people as the webhook: co-owners and up, or the founder.
	 */
	public boolean setLinkedClan(String groupId, String requester, String clan)
	{
		LendingGroup group = groupId != null ? groups.get(groupId) : null;
		if (group == null || requester == null) return false;
		boolean founder = hasFounderPower(groupId, requester);
		String role = getMemberRole(groupId, requester);
		if (!founder && (role == null || getRoleRank(role) < 4)) return false;
		String clean = clan == null ? "" : clan.trim();
		if (clean.length() > 40) clean = clean.substring(0, 40);
		synchronized (group)
		{
			group.setLinkedClan(clean);
			group.setLinkedClanUpdatedAt(System.currentTimeMillis());
			group.setLinkedClanSetBy(requester);
		}
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	/** Whose loans a group tracks and posts to its Discord. */
	public enum LoanScope
	{
		CLAN("Clan members only"),
		GROUP("Group members only"),
		ANYONE("Anyone");

		private final String label;

		LoanScope(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	/**
	 * The scope in force. CLAN with no clan linked can't be checked, so it falls
	 * back to GROUP - the narrower of the two that can.
	 */
	public LoanScope getLoanScope(String groupId)
	{
		LendingGroup group = groupId != null ? groups.get(groupId) : null;
		if (group == null) return LoanScope.ANYONE;
		boolean clanLinked = getLinkedClan(groupId) != null;
		LoanScope scope;
		try
		{
			scope = group.getLoanScope() != null ? LoanScope.valueOf(group.getLoanScope())
				: clanLinked ? LoanScope.CLAN : LoanScope.ANYONE;
		}
		catch (IllegalArgumentException e)
		{
			scope = LoanScope.GROUP;   // unknown value from a newer client: stay narrow
		}
		return scope == LoanScope.CLAN && !clanLinked ? LoanScope.GROUP : scope;
	}

	/** Co-owners and up, or the founder. */
	public boolean setLoanScope(String groupId, String requester, LoanScope scope)
	{
		LendingGroup group = groupId != null ? groups.get(groupId) : null;
		if (group == null || requester == null || scope == null) return false;
		boolean founder = hasFounderPower(groupId, requester);
		String role = getMemberRole(groupId, requester);
		if (!founder && (role == null || getRoleRank(role) < 4)) return false;
		synchronized (group)
		{
			group.setLoanScope(scope.name());
			group.setLoanScopeUpdatedAt(System.currentTimeMillis());
		}
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	/** The in-game clan this group is linked to, or null. */
	public String getLinkedClan(String groupId)
	{
		LendingGroup group = groupId != null ? groups.get(groupId) : null;
		String clan = group != null ? group.getLinkedClan() : null;
		return clan == null || clan.trim().isEmpty() ? null : clan.trim();
	}

	/**
	 * Put this player's Discord user ID on their own row in every group they are
	 * in, or take it off (null). Sealed with each group's key. Cheap to call
	 * often: a group whose row already says the same thing is left alone.
	 */
	public void setMyDiscordId(String playerName, String discordId)
	{
		if (playerName == null) return;
		boolean changed = false;
		boolean changedActive = false;
		for (LendingGroup g : groups.values())
		{
			String secret = g.getSyncSecret();
			if (secret == null || secret.isEmpty() || g.getMembers() == null) continue;
			GroupMember mine = null;
			for (GroupMember m : g.getMembers())
			{
				if (m != null && m.getName() != null && m.getName().equalsIgnoreCase(playerName))
				{
					mine = m;
					break;
				}
			}
			if (mine == null) continue;
			String sealed = mine.getDiscordSealed();
			String current = sealed == null || sealed.isEmpty() ? null : WebhookSeal.open(sealed, secret);
			if (Objects.equals(current, discordId)) continue;
			String next = discordId == null ? "" : WebhookSeal.seal(discordId, secret);
			if (next == null) continue;
			synchronized (g)
			{
				mine.setDiscordSealed(next);
				mine.setDiscordUpdatedAt(System.currentTimeMillis());
			}
			changed = true;
			changedActive |= g.getId().equals(currentSyncGroupId);
		}
		if (changed) saveGroups();
		if (changedActive) publishEvent(SyncEventType.SETTINGS_CHANGED, currentSyncGroupId, null);
	}

	/** A member's Discord user ID in this group, if they chose to be tagged; else null. */
	public String getMemberDiscordId(String groupId, String playerName)
	{
		LendingGroup g = groupId != null ? groups.get(groupId) : null;
		if (g == null || playerName == null || g.getMembers() == null) return null;
		for (GroupMember m : g.getMembers())
		{
			if (m != null && m.getName() != null && m.getName().equalsIgnoreCase(playerName))
			{
				String sealed = m.getDiscordSealed();
				String id = sealed == null || sealed.isEmpty() ? null : WebhookSeal.open(sealed, g.getSyncSecret());
				return id != null && id.matches("\\d{17,20}") ? id : null;
			}
		}
		return null;
	}

	/** Has the group's current webhook already had its "connected" test? */
	public boolean isWebhookTested(String groupId)
	{
		LendingGroup g = groupId != null ? groups.get(groupId) : null;
		return g != null && g.getWebhookUpdatedAt() > 0 && g.getWebhookTestedFor() == g.getWebhookUpdatedAt();
	}

	/** Lock the Test button for everyone until the webhook changes. */
	public void markWebhookTested(String groupId)
	{
		LendingGroup g = groupId != null ? groups.get(groupId) : null;
		if (g == null) return;
		synchronized (g)
		{
			g.setWebhookTestedFor(g.getWebhookUpdatedAt());
		}
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
	}

	/** The group's Discord webhook, or null when none is set or it can't be opened here. */
	public String getGroupWebhook(String groupId)
	{
		LendingGroup group = groupId != null ? groups.get(groupId) : null;
		if (group == null || group.getWebhookSealed() == null || group.getWebhookSealed().isEmpty()) return null;
		String url = WebhookSeal.open(group.getWebhookSealed(), group.getSyncSecret());
		return DiscordWebhook.isDiscordWebhook(url) ? url : null;
	}

	public boolean setKickPermission(String groupId, String requesterName, String role, boolean value)
	{
		return setPermission(groupId, requesterName, role, value, "kick");
	}

	public boolean setInvitePermission(String groupId, String requesterName, String role, boolean value)
	{
		return setPermission(groupId, requesterName, role, value, "invite");
	}

	private boolean setPermission(String groupId, String requesterName, String role, boolean value, String permType)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		// The founder holds full control whatever rank they display as, and the
		// settings panel already offers them these controls.
		boolean founder = hasFounderPower(groupId, requesterName);
		String requesterRole = getMemberRole(groupId, requesterName);
		if (!founder && (requesterRole == null || getRoleRank(requesterRole) < 4)) return false;

		boolean isKick = "kick".equals(permType);
		switch (role.toLowerCase())
		{
			case "co-owner":
				if (!founder && getRoleRank(requesterRole) < 5) return false;
				if (isKick) group.setCoOwnerCanKick(value); else group.setCoOwnerCanInvite(value);
				break;
			case "admin":
				if (isKick) group.setAdminCanKick(value); else group.setAdminCanInvite(value);
				break;
			case "mod":
				if (isKick) group.setModCanKick(value); else group.setModCanInvite(value);
				break;
			default:
				return false;
		}
		// Peers only adopt permission flags when the roster stamp is newer — without
		// bumping it, this change could be silently discarded by any peer whose
		// stamp is already ahead.
		touchRoster(group);
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	public boolean canGenerateInviteCode(String groupId, String playerName)
	{
		if (groupId == null || playerName == null) return false;

		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		// Founder authority is rank-independent (see hasFounderPower).
		if (hasFounderPower(groupId, playerName)) return true;

		String role = getMemberRole(groupId, playerName);
		if (role == null) return false;

		int rank = getRoleRank(role);
		if (rank == 5) return true;

		switch (role.toLowerCase())
		{
			case "co-owner": return group.isCoOwnerCanInvite();
			case "admin": return group.isAdminCanInvite();
			case "mod": return group.isModCanInvite();
			default: return false;
		}
	}

	/**
	 * The founder keeps full authority regardless of the role they currently hold -
	 * owner, co-owner or plain member. Their rank is not shown anywhere in the UI,
	 * so treat this as a deliberate hidden capability rather than a visible rank.
	 * Note founderName travels in the synced group payload, so it is not a secret
	 * from anyone reading relay data directly.
	 */
	public boolean hasFounderPower(String groupId, String playerName)
	{
		return isFounder(groupId, playerName);
	}

	/**
	 * Hand the founder role to another member. The only way to give it up, and
	 * what a founder must do before they can leave the group.
	 */
	public boolean transferFounder(String groupId, String currentFounderName, String newFounderName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;
		if (!isFounder(groupId, currentFounderName)) return false;
		if (currentFounderName == null || currentFounderName.equalsIgnoreCase(newFounderName)) return false;
		if (!group.hasMember(newFounderName)) return false;

		group.setFounderName(newFounderName);
		group.setFounderUpdatedAt(System.currentTimeMillis());
		touchRoster(group);
		saveGroups();
		log.info("Founder of group {} transferred", groupId);
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	public boolean canChangeRole(String groupId, String changerName, String targetName)
	{
		if (groupId == null || changerName == null || targetName == null) return false;
		if (changerName.equalsIgnoreCase(targetName)) return false;
		// The founder outranks everyone whatever their displayed role.
		if (hasFounderPower(groupId, changerName)) return true;

		String changerRole = getMemberRole(groupId, changerName);
		String targetRole = getMemberRole(groupId, targetName);
		if (changerRole == null) return false;

		int changerRank = getRoleRank(changerRole);
		int targetRank = getRoleRank(targetRole);

		if (changerRank < 4) return false;
		if (changerRank == 5) return true;
		return targetRank < 4;
	}

	// --- Invite Codes ---

	/**
	 * Status of a join-by-code attempt so the UI can show an accurate message instead of
	 * always reporting "invalid or expired".
	 */
	public enum JoinStatus { JOINED, INVALID_CODE, SERVER_UNREACHABLE, SYNC_DISABLED }

	public static final class JoinResult
	{
		public final JoinStatus status;
		public final String groupId;

		private JoinResult(JoinStatus status, String groupId)
		{
			this.status = status;
			this.groupId = groupId;
		}

		public static JoinResult joined(String groupId) { return new JoinResult(JoinStatus.JOINED, groupId); }
		public static JoinResult invalid() { return new JoinResult(JoinStatus.INVALID_CODE, null); }
		public static JoinResult unreachable() { return new JoinResult(JoinStatus.SERVER_UNREACHABLE, null); }
		public static JoinResult syncDisabled() { return new JoinResult(JoinStatus.SYNC_DISABLED, null); }
	}

	/**
	 * Join a group using an invite code.
	 * Resolution order: local groups -> shared per-account config key -> relay server.
	 * @return a {@link JoinResult}; JOINED carries the group ID.
	 */
	public JoinResult useInviteCode(String code, String playerName)
	{
		if (code == null || code.trim().isEmpty() || playerName == null || playerName.trim().isEmpty())
		{
			return JoinResult.invalid();
		}

		String trimmedCode = code.trim().toUpperCase();

		// Check local groups first (handles self-join or already-synced groups)
		for (LendingGroup group : groups.values())
		{
			if (group.hasActiveInviteCode() && trimmedCode.equalsIgnoreCase(group.getInviteCode()))
			{
				if (!group.hasMember(playerName))
				{
					group.addMember(new GroupMember(playerName, "member"));
					touchRoster(group);
				}

				group.markGroupCodeUsed(playerName);
				setCurrentGroupId(group.getId());
				saveGroups();
				return JoinResult.joined(group.getId());
			}

			if (group.isClanCodeEnabled() && trimmedCode.equalsIgnoreCase(group.getClanCode()))
			{
				if (!group.hasMember(playerName))
				{
					group.addMember(new GroupMember(playerName, "member"));
					touchRoster(group);
				}

				group.recordClanCodeUse(playerName);
				setCurrentGroupId(group.getId());
				saveGroups();
				return JoinResult.joined(group.getId());
			}
		}

		// Check shared invite key (for codes generated by other accounts on this machine)
		String sharedJson = configManager.getConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + trimmedCode);
		if (sharedJson != null && !sharedJson.isEmpty())
		{
			try
			{
				LendingGroup sharedGroup = gson.fromJson(sharedJson, LendingGroup.class);
				if (sharedGroup != null && sharedGroup.getId() != null)
				{
					// Multi-use group code? It stays valid for the next joiner.
					boolean multiUse = sharedGroup.isClanCodeEnabled()
						&& trimmedCode.equalsIgnoreCase(sharedGroup.getClanCode());

					// Add joining player as member
					if (!sharedGroup.hasMember(playerName))
					{
						sharedGroup.addMember(joinerRow(playerName));
						touchRoster(sharedGroup);
					}

					if (multiUse)
					{
						sharedGroup.recordClanCodeUse(playerName);
					}
					else
					{
						// Void the single-use code
						sharedGroup.markGroupCodeUsed(playerName);
					}

					// Store in this player's local groups
					groups.put(sharedGroup.getId(), ensureCowMembers(sharedGroup));
					setCurrentGroupId(sharedGroup.getId());
					saveGroups();

					// Publish updated group state so the creator sees the new member
					publishGroupState(sharedGroup.getId());

					// Single-use codes are consumed; a multi-use code stays for others
					if (!multiUse)
					{
						configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + trimmedCode);
					}

					// Notify via sync events
					SyncEvent joinEvent = new SyncEvent();
					joinEvent.setType(SyncEventType.MEMBER_JOINED);
					joinEvent.setTimestamp(System.currentTimeMillis());
					joinEvent.setPublisher(playerName);
					String prevSyncGroup = currentSyncGroupId;
					String prevSyncPlayer = currentSyncPlayerName;
					currentSyncGroupId = sharedGroup.getId();
					currentSyncPlayerName = playerName;
					addEventToQueue(joinEvent);
					currentSyncGroupId = prevSyncGroup;
					currentSyncPlayerName = prevSyncPlayer;

					return JoinResult.joined(sharedGroup.getId());
				}
			}
			catch (Exception e)
			{
				log.error("Failed to parse shared invite code data", e);
			}
		}

		// Cross-machine lookup requires Cloud Sync (opt-in - it submits the player's IP to the
		// relay). Same-machine joins are already handled above, so only gate the relay path.
		if (!config.enableRelaySync())
		{
			return JoinResult.syncDisabled();
		}

		// Check relay server for cross-machine invite codes
		if (relaySyncService != null)
		{
			try
			{
				RelaySyncService.InviteLookupResult lookup = relaySyncService.lookupInvite(trimmedCode);

				if (lookup.status == RelaySyncService.InviteStatus.UNREACHABLE)
				{
					// Server didn't answer (likely Render cold-start) - tell the user to retry
					return JoinResult.unreachable();
				}

				if (lookup.status == RelaySyncService.InviteStatus.FOUND && lookup.groupJson != null)
				{
					LendingGroup relayGroup = gson.fromJson(lookup.groupJson, LendingGroup.class);
					if (relayGroup != null && relayGroup.getId() != null)
					{
						// Multi-use group code? It stays on the relay for the next joiner.
						boolean multiUse = relayGroup.isClanCodeEnabled()
							&& trimmedCode.equalsIgnoreCase(relayGroup.getClanCode());

						if (!relayGroup.hasMember(playerName))
						{
							relayGroup.addMember(joinerRow(playerName));
							touchRoster(relayGroup);
						}
						if (multiUse)
						{
							relayGroup.recordClanCodeUse(playerName);
						}
						else
						{
							relayGroup.markGroupCodeUsed(playerName);
						}

						groups.put(relayGroup.getId(), ensureCowMembers(relayGroup));
						setCurrentGroupId(relayGroup.getId());
						saveGroups();

						// Single-use codes are consumed; a multi-use code stays for others
						if (!multiUse)
						{
							relaySyncService.consumeInviteCode(trimmedCode);
						}

						// Publish group state and member joined event via relay
						publishGroupState(relayGroup.getId());
						publishEvent(SyncEventType.MEMBER_JOINED, relayGroup.getId() + ":" + playerName, null);

						return JoinResult.joined(relayGroup.getId());
					}
				}
			}
			catch (Exception e)
			{
				log.error("Failed to check relay for invite code", e);
				return JoinResult.unreachable();
			}
		}

		return JoinResult.invalid();
	}

	/**
	 * Result of generating an invite code, so the UI can tell the owner whether the code is
	 * actually live on the relay (shareable cross-machine) or only valid on this computer.
	 */
	public static final class InviteCodeResult
	{
		public final String code;
		public final boolean syncEnabled;
		public final boolean publishedToRelay;

		public InviteCodeResult(String code, boolean syncEnabled, boolean publishedToRelay)
		{
			this.code = code;
			this.syncEnabled = syncEnabled;
			this.publishedToRelay = publishedToRelay;
		}
	}

	/**
	 * Generate a single-use invite code and, if Cloud Sync is on, publish it to the relay and
	 * CONFIRM it landed (retrying through a cold-start) before the owner shares it.
	 * Does a blocking network call - callers must run it off the EDT.
	 */
	public InviteCodeResult generateAndPublishInviteCode(String groupId)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null || disbandingGroupIds.contains(groupId))
		{
			return null;
		}

		// Retire the previous code's same-machine lookup key — otherwise the old
		// (rotated-away) code would keep working for alts on this computer.
		String previousCode = group.getInviteCode();
		String code = group.generateSingleUseCode();
		if (previousCode != null && !previousCode.isEmpty() && !previousCode.equals(code))
		{
			configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + previousCode);
		}
		saveGroups();
		// FULL copy for the local shared key: with sync off this is the only
		// way another account on this machine ever sees the roster, and it
		// never leaves the machine. The relay gets the trimmed one below.
		String groupJson = gson.toJson(group);
		// Store in shared config so other accounts on the same machine can look up this code
		configManager.setConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + code, groupJson);

		boolean syncEnabled = config.enableRelaySync();
		boolean published = false;
		if (syncEnabled && relaySyncService != null)
		{
			// Confirm the code actually reached the relay before the owner hands it out
			published = relaySyncService.publishInviteBlocking(code, groupId, inviteGroupJson(group));
		}

		// Code state is shared group data — push it live so every staff member's
		// panel shows the same active code immediately.
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return new InviteCodeResult(code, syncEnabled, published);
	}

	// --- Multi-use Group Code (open/close joins) ---
	//
	// Unlike the single-use invite code, the group code can be used by ANY number
	// of joiners while it is OPEN. Closing joins keeps the code but removes it
	// from the relay and shared config, so it stops working until reopened. The
	// permission gate is the same one that governs single-use codes.

	/** Result of opening a group code: the code, or an error the UI can show. */
	public static final class GroupCodeResult
	{
		public final String code;       // null on failure
		public final String error;      // null on success
		public final boolean syncEnabled;
		public final boolean publishedToRelay;

		GroupCodeResult(String code, String error, boolean syncEnabled, boolean publishedToRelay)
		{
			this.code = code;
			this.error = error;
			this.syncEnabled = syncEnabled;
			this.publishedToRelay = publishedToRelay;
		}
	}

	/**
	 * Open joins on the group code. Pass a custom code to set one, or null to
	 * reuse the existing code (generating a fresh one if none exists yet).
	 * Blocking network call — run off the EDT.
	 */
	public GroupCodeResult openGroupCode(String groupId, String requesterName, String customCode)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null)
		{
			return new GroupCodeResult(null, "Group not found.", false, false);
		}
		if (!canGenerateInviteCode(groupId, requesterName))
		{
			return new GroupCodeResult(null, "You don't have permission to manage invite codes.", false, false);
		}
		if (disbandingGroupIds.contains(groupId))
		{
			return new GroupCodeResult(null, "This group is being disbanded.", false, false);
		}

		String code;
		if (customCode != null)
		{
			code = normalizeCustomCode(customCode);
			if (code == null)
			{
				return new GroupCodeResult(null,
					"Codes must be 6-20 characters: letters, numbers and dashes only.", false, false);
			}
		}
		else if (group.getClanCode() != null && !group.getClanCode().isEmpty())
		{
			code = group.getClanCode(); // reopen with the kept code
		}
		else
		{
			code = UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 9);
			code = code.substring(0, 3) + "-" + code.substring(3, 6) + "-" + code.substring(6, 9);
		}

		// Kill the code we are replacing. Rotating the clan code after a kick is
		// how staff shut that door, so leaving the old one alive on the relay
		// until its 24h expiry defeats the point - closing joins already
		// revoked properly, changing the code did not.
		String previousClanCode = group.getClanCode();
		if (previousClanCode != null && !previousClanCode.isEmpty()
			&& !previousClanCode.equalsIgnoreCase(code))
		{
			configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + previousClanCode);
			if (relaySyncService != null)
			{
				relaySyncService.consumeInviteCode(previousClanCode);
			}
		}
		group.setClanCode(code);
		group.setClanCodeEnabled(true);
		group.touchCodeState();
		saveGroups();

		// FULL copy for the local shared key: with sync off this is the only
		// way another account on this machine ever sees the roster, and it
		// never leaves the machine. The relay gets the trimmed one below.
		String groupJson = gson.toJson(group);
		// Same-machine joiners look the code up in shared config
		configManager.setConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + code, groupJson);

		boolean syncEnabled = config.enableRelaySync();
		boolean published = false;
		if (syncEnabled && relaySyncService != null)
		{
			published = relaySyncService.publishInviteBlocking(code, groupId, inviteGroupJson(group));
		}

		// Shared group data — every staff member's panel must show the code is OPEN.
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return new GroupCodeResult(code, null, syncEnabled, published);
	}

	/**
	 * Close joins: the code stops working everywhere but is KEPT on the group, so
	 * it can be reopened later. Returns an error string, or null on success.
	 */
	public String closeGroupCode(String groupId, String requesterName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null)
		{
			return "Group not found.";
		}
		if (!canGenerateInviteCode(groupId, requesterName))
		{
			return "You don't have permission to manage invite codes.";
		}

		group.setClanCodeEnabled(false);
		group.touchCodeState();
		saveGroups();

		if (group.getClanCode() != null)
		{
			configManager.unsetConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + group.getClanCode());
			if (relaySyncService != null)
			{
				relaySyncService.consumeInviteCode(group.getClanCode()); // removes it from the relay
			}
		}

		// Shared group data — every staff member's panel must show joins CLOSED.
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return null;
	}

	/**
	 * Re-publish an OPEN group code so it doesn't age out of the relay (stored
	 * codes expire after 24h). Called from the plugin's periodic sync task; only
	 * members with invite permission refresh it.
	 */
	public void refreshGroupCodePresence()
	{
		if (!config.enableRelaySync() || relaySyncService == null)
		{
			return;
		}
		LendingGroup group = getActiveGroupUnchecked();
		if (group == null || !group.isClanCodeEnabled()
			|| group.getClanCode() == null || group.getClanCode().isEmpty())
		{
			return;
		}
		String me = currentSyncPlayerName;
		if (me == null || !canGenerateInviteCode(group.getId(), me))
		{
			return;
		}
		// Only refresh from a client that has reconciled with the relay. An
		// un-caught-up peer still holding a rotated-away code would republish
		// it with a fresh 24h life, undoing the revoke that staff rely on
		// after a kick - and undoing closing joins too.
		if (!canPublishRemoval(group.getId())) return;
		// A disband we are still finishing revoked this code; keeping it alive
		// would let people join a group that is on its way out.
		if (disbandingGroupIds.contains(group.getId())) return;
		relaySyncService.publishInviteCode(group.getClanCode(), group.getId(), inviteGroupJson(group));
	}

	/** Uppercase and validate a custom code: 6-20 chars, A-Z 0-9 and dashes. */
	private static String normalizeCustomCode(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String code = raw.trim().toUpperCase().replace(' ', '-');
		return code.matches("[A-Z0-9-]{6,20}") ? code : null;
	}

	/**
	 * @deprecated Use {@link #generateAndPublishInviteCode(String)}, which confirms the code
	 * reached the relay. This variant published fire-and-forget and could silently fail.
	 */
	@Deprecated
	public String generateSingleUseInviteCode(String groupId)
	{
		InviteCodeResult result = generateAndPublishInviteCode(groupId);
		return result != null ? result.code : null;
	}

	// --- Real-Time Sync ---

	// False from the plugin's shutDown until its next startUp. A disband holds a
	// network call open for seconds, and if the plugin is turned off meanwhile the
	// teardown that follows would start syncing a replacement group - reopening a
	// socket that nothing will ever close.
	private volatile boolean active = true;

	public void setActive(boolean active)
	{
		this.active = active;
	}

	public void startSync(String groupId, String playerName)
	{
		if (!active) return;
		if (groupId == null || playerName == null)
		{
			return;
		}

		// Already syncing this exact group+player? Both GameStateChanged(LOGGED_IN)
		// and RuneScapeProfileChanged fire the login flow, so every login (and every
		// world hop) calls startSync twice. A full teardown/reconnect here caused a
		// presence flap for peers, an all-offline roster flash locally, and a
		// needless re-fetch. Deliberately NOT gated on isConnected(): at login the
		// second call usually lands while the first call's websocket handshake is
		// still in flight, and it must be absorbed too — connect() below no-ops
		// when a socket is connected OR connecting, and joinRoom just refreshes the
		// room (sent now if connected, else by onOpen when the handshake finishes).
		if (groupId.equals(currentSyncGroupId)
			&& playerName.equalsIgnoreCase(currentSyncPlayerName)
			&& syncExecutor != null && !syncExecutor.isShutdown()
			&& relaySyncService != null)
		{
			LendingGroup sameGroup = groups.get(groupId);
			// Real reconnect only if sync was fully torn down (e.g. the Cloud Sync
			// toggle was flipped off and back on without a group change).
			boolean wasDisconnected = !relaySyncService.isConnected();
			relaySyncService.connect();
			relaySyncService.joinRoom(groupId, playerName,
				sameGroup != null ? sameGroup.getSyncSecret() : null);
			// Resuming from a dead connection means we may have missed peers'
			// changes — pull the stored snapshot once. (Skipped when connected,
			// so a world hop doesn't hit the relay's REST endpoint every time;
			// a duplicate fetch from the login double-call is idempotent.)
			if (wasDisconnected)
			{
				scheduleCatchUpFetch(groupId);
			}
			// Push our current state too: this re-sync may follow a local change
			// this path wouldn't otherwise broadcast (e.g. redeeming a single-use
			// code for a group we were already syncing — staff must see "USED by X").
			// No-ops until connected; harmless when nothing changed (peers dedup an
			// identical snapshot by content hash).
			announcePresence();
			return;
		}

		stopSync();

		this.currentSyncGroupId = groupId;
		this.currentSyncPlayerName = playerName;
		// Start from 0 so the first poll also applies events published before this
		// session began — previously anything from before login was silently
		// skipped, so same-machine accounts needed a relog to see each other's items.
		this.lastSyncTimestamp = 0;

		// Load this group's data from local config into memory BEFORE the catch-up
		// fetch runs. The catch-up preserves the local player's own rows, but only
		// ones already in memory — without this, offline additions that live only in
		// config would be absent and get overwritten by the relay snapshot.
		dataService.loadGroupData(groupId);

		syncExecutor = Executors.newSingleThreadScheduledExecutor();
		syncExecutor.scheduleAtFixedRate(this::pollForUpdates, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

		// CHANGED: Connect to relay server with HMAC sync secret
		if (relaySyncService != null)
		{
			relaySyncService.connect();
			LendingGroup group = groups.get(groupId);
			String syncSecret = group != null ? group.getSyncSecret() : null;
			relaySyncService.joinRoom(groupId, playerName, syncSecret);

			// Pull the authoritative catch-up snapshot off the caller's thread
			// (blocking REST call). Retries with backoff so a Render cold-start
			// (30-60s wake) or a transient failure doesn't mean "no catch-up until
			// relog" — offline deletions/returns would otherwise never arrive.
			scheduleCatchUpFetch(groupId);
		}
	}

	// Catch-up retry backoff: immediate, then 10s/20s/40s/80s/160s — spans ~5min,
	// enough to ride out a Render free-tier cold start.
	private static final long[] CATCH_UP_RETRY_DELAYS_MS = { 0, 10_000, 20_000, 40_000, 80_000, 160_000 };

	/** Start a catch-up chain for the group, unless one is already running. */
	private void scheduleCatchUpFetch(String groupId)
	{
		long token = catchUpTokens.incrementAndGet();
		if (!catchUpOwner.compareAndSet(0L, token))
		{
			// A chain is already in flight; it (or the 5-second poll rescue after
			// it releases) will get us caught up. Starting another would stack
			// blocking fetches on the single sync thread.
			return;
		}
		scheduleCatchUpAttempt(groupId, 0, 0, token, connectionEpoch.get());
	}

	private void scheduleCatchUpAttempt(String groupId, int attempt, long delayMs, long token, long epoch)
	{
		ScheduledExecutorService exec = syncExecutor;
		if (exec == null || exec.isShutdown())
		{
			catchUpOwner.compareAndSet(token, 0L);
			return;
		}
		try
		{
			exec.schedule(() ->
			{
				// The finally releases our claim on every exit — including an
				// unexpected throw, which would otherwise wedge the flag and leave
				// the publish gate closed for the rest of the session. The one path
				// that must NOT release is a scheduled retry: the chain lives on.
				boolean chainContinues = false;
				try
				{
					// The sync target may have changed while we waited (group switch),
					// or the connection may have cycled — a chain sleeping through a
					// backoff wakes up obsolete. Check before spending a fetch.
					if (!groupId.equals(currentSyncGroupId) || epoch != connectionEpoch.get()) return;
					boolean done = relaySyncService.fetchStateSnapshot(groupId);
					// Re-check AFTER the fetch too: it blocks for up to 90s (Render
					// cold start) — ample time for a group switch, a stopSync, or a
					// connection drop. A stale task must not mark the old group caught
					// up, and a fetch that started before a drop must not vouch for
					// the record after the reconnect: peers may have changed it during
					// the outage.
					if (exec != syncExecutor || !groupId.equals(currentSyncGroupId)
						|| epoch != connectionEpoch.get())
					{
						return;
					}
					if (done)
					{
						// Reconciled with the stored record — or the relay definitively
						// has nothing usable for this group (no record, or one whose
						// signature we reject). Both make publishing safe: overwriting a
						// record we refused to APPLY isn't a rollback, it replaces
						// unusable data with our signed state.
						caughtUpGroupId = groupId;
						// The check above and this write aren't atomic against a drop on
						// the ws thread. Re-read the epoch and take the marker back if it
						// moved, so a snapshot read before the drop can't vouch for the
						// record after it.
						if (epoch != connectionEpoch.get())
						{
							caughtUpGroupId = null;
							return;
						}
						// Now push our own view, which the gate suppressed until this
						// point. This is what carries changes made while we were offline
						// up to the relay, and it replaces the unconditional push that
						// used to run on reconnect before we knew what we were
						// overwriting.
						pushStateToRelay(groupId);
					}
					else if (attempt + 1 < CATCH_UP_RETRY_DELAYS_MS.length)
					{
						chainContinues = true;
						scheduleCatchUpAttempt(groupId, attempt + 1,
							CATCH_UP_RETRY_DELAYS_MS[attempt + 1], token, epoch);
					}
					else
					{
						// Still not reconciled. We stay silent rather than publish
						// blind — pollForUpdates starts a fresh chain, so a relay that
						// comes back later heals without needing a relog.
						log.warn("Catch-up fetch for group {} failed after {} attempts; "
							+ "not publishing until reconciled", groupId, attempt + 1);
					}
				}
				finally
				{
					if (!chainContinues)
					{
						catchUpOwner.compareAndSet(token, 0L);
					}
				}
			}, delayMs, TimeUnit.MILLISECONDS);
		}
		catch (java.util.concurrent.RejectedExecutionException ignored)
		{
			// stopSync shut the executor down — nothing to catch up on anymore.
			catchUpOwner.compareAndSet(token, 0L);
		}
	}

	public void stopSync()
	{
		if (syncExecutor != null && !syncExecutor.isShutdown())
		{
			// shutdownNow() only — never wait here. stopSync runs from the plugin's
			// shutDown(), and blocking there stalls the client's whole plugin
			// teardown. A catch-up fetch can hold a socket read for up to 90s, so
			// awaiting it was a real stall, not a theoretical one. Tasks that
			// survive the interrupt are harmless: each re-checks the sync target,
			// the connection epoch and the executor identity before acting.
			syncExecutor.shutdownNow();
		}
		// Disconnect relay
		if (relaySyncService != null)
		{
			relaySyncService.disconnect();
		}

		currentSyncGroupId = null;
		currentSyncPlayerName = null;
		caughtUpGroupId = null;
		// Any pending chain died with the executor; a held claim would block the
		// next session's first catch-up. A stale task that later wakes can only
		// CAS its own token, so force-clearing here is safe.
		catchUpOwner.set(0L);
	}

	/**
	 * Called the moment the relay websocket drops. The caught-up marker must die
	 * HERE, not on reconnect: publishes already queued on the sync executor would
	 * otherwise race the reconnect callback and slip through the gate with
	 * pre-outage state. Bumping the epoch invalidates any catch-up fetch that
	 * started on the old connection — what it read says nothing about what peers
	 * stored during the outage.
	 */
	public void onRelayDisconnected()
	{
		caughtUpGroupId = null;
		connectionEpoch.incrementAndGet();
	}

	/**
	 * Called when the relay websocket (re)connects. A drop can span any amount of
	 * time — Render idles the free tier out routinely — and members may have
	 * changed things meanwhile, so we re-read the stored record before publishing
	 * anything. The catch-up push replaces the straight announce that used to run
	 * here. If a chain from before the drop still holds the claim, this no-ops —
	 * that chain dies on its epoch check and the 5-second poll rescue restarts.
	 */
	public void onRelayConnected()
	{
		final String groupId = currentSyncGroupId;
		if (groupId == null) return;
		caughtUpGroupId = null;
		// Bump on connect as well as disconnect: a fetch whose store-read happened
		// BEFORE we joined the room missed anything peers pushed in between, so it
		// must not vouch for the record either. Chains started below capture the
		// new epoch and are unaffected.
		connectionEpoch.incrementAndGet();
		// Hand the claim to the fresh chain. A chain from the old connection may be
		// asleep in a backoff of up to 160s, and waiting for it to wake and notice
		// would hold every publish back that whole time. It can't corrupt anything
		// on waking: the epoch check kills it, and its release is a compare-and-set
		// on its own token, which no longer owns the claim.
		catchUpOwner.set(0L);
		scheduleCatchUpFetch(groupId);
	}

	public void setOnWildernessAlert(java.util.function.Consumer<SyncEvent> callback)
	{
		this.onWildernessAlert = callback;
	}

	public void setOnSyncCallback(Runnable callback)
	{
		this.onSyncCallback = callback;
	}

	/**
	 * Handle a sync event received from the relay server (cross-machine).
	 * Processes the event and triggers UI refresh via callback.
	 */
	public void handleRelayEvent(SyncEvent event)
	{
		processEvent(event);
		if (onSyncCallback != null)
		{
			onSyncCallback.run();
		}
	}

	/**
	 * Apply an authoritative presence snapshot from the relay: the exact set of
	 * members currently connected to the room (lower-cased name -> world). Replaces
	 * the previous set wholesale, so a member who logged off drops out immediately.
	 * Returns true if the online set actually changed (so the caller can skip a
	 * needless roster repaint on an identical snapshot).
	 */
	public boolean handlePresence(java.util.Map<String, Integer> present)
	{
		java.util.Map<String, Integer> next = (present == null || present.isEmpty())
			? java.util.Collections.emptyMap()
			: java.util.Collections.unmodifiableMap(new java.util.HashMap<>(present));
		if (relayPresence.equals(next))
		{
			return false;
		}
		relayPresence = next; // single volatile reference swap — readers see old or new, never torn
		return true;
	}

	/**
	 * Drop all presence when our own connection goes down — we can't vouch for
	 * anyone. Returns true if anything was actually cleared.
	 */
	public boolean clearPresence()
	{
		if (relayPresence.isEmpty())
		{
			return false;
		}
		relayPresence = java.util.Collections.emptyMap();
		return true;
	}

	/**
	 * Members the relay reports as online right now, lower-cased name -> world
	 * (0 = world unknown). The roster shows everyone here with a green dot.
	 */
	public java.util.Map<String, Integer> getOnlineMembers()
	{
		return relayPresence; // already an immutable snapshot reference
	}

	/**
	 * Announce our current group state to the relay immediately. Fired the moment
	 * the websocket (re)connects so a freshly joined member — and everyone already
	 * in the room — propagate their rosters to each other without waiting for the
	 * periodic 5-minute push. Also flushes changes made while briefly disconnected.
	 *
	 * Serializing the full group+data snapshot is CPU work, and this can be called
	 * from the OkHttp ws-callback thread (onConnected) — hand it to the sync
	 * executor when one is running so snapshot building never delays inbound
	 * message delivery.
	 */
	public void announcePresence()
	{
		final String groupId = currentSyncGroupId;
		if (groupId == null)
		{
			return;
		}

		ScheduledExecutorService exec = syncExecutor;
		if (exec != null && !exec.isShutdown())
		{
			try
			{
				exec.execute(() -> pushStateToRelay(groupId));
				return;
			}
			catch (java.util.concurrent.RejectedExecutionException ignored)
			{
				// Executor shut down between the check and submit — fall through.
			}
		}
		pushStateToRelay(groupId);
	}

	public void publishEvent(SyncEventType type, String dataId, Object data)
	{
		if (currentSyncGroupId == null || currentSyncPlayerName == null)
		{
			return;
		}

		SyncEvent event = new SyncEvent();
		event.setType(type);
		event.setTimestamp(System.currentTimeMillis());
		event.setPublisher(currentSyncPlayerName);
		event.setDataId(dataId);

		addEventToQueue(event);

		// Send via relay for cross-machine sync
		if (relaySyncService != null && relaySyncService.isConnected())
		{
			relaySyncService.sendEvent(currentSyncGroupId, event);

			// Push full state to relay so offline members can catch up later
			pushStateToRelay(currentSyncGroupId);
		}

		// Publish full group state for member/settings changes so other accounts can sync
		if (type == SyncEventType.MEMBER_JOINED || type == SyncEventType.MEMBER_LEFT ||
			type == SyncEventType.SETTINGS_CHANGED)
		{
			publishGroupState(currentSyncGroupId);
		}
	}

	/**
	 * Write the full group state to a shared config key so other accounts can read it.
	 */
	private void publishGroupState(String groupId)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return;

		String key = SYNC_KEY_PREFIX + groupId + SYNC_GROUP_SUFFIX;
		configManager.setConfiguration(CFG_GROUP, key, gson.toJson(group));
	}

	/**
	 * The group as it should appear in an INVITE payload.
	 *
	 * GET /api/invite/:code has no authentication - knowing the code is the only
	 * credential - and an open clan code is republished every few minutes to keep
	 * it alive. So whatever goes in here is readable by anyone who has ever seen
	 * the code, indefinitely. It used to be the entire group: every member's name
	 * and role, the kick tombstones, who had used the code.
	 *
	 * A joiner needs none of that. They get the real roster from their first sync.
	 * The group's settings and permission flags do stay - they say nothing about
	 * who is in it, and a joiner needs them before that first sync lands.
	 * What they genuinely cannot start without is the signing key, so that stays -
	 * which does mean the code is as powerful as the key. Fixing THAT needs a
	 * different join handshake, not a smaller payload.
	 */
	private String inviteGroupJson(LendingGroup group)
	{
		com.google.gson.JsonObject o = gson.toJsonTree(group).getAsJsonObject();
		o.remove("members");
		o.remove("removedMembers");
		o.remove("clanCodeUsedBy");
		o.remove("usedGroupCodes");
		o.remove("inviteCodeUsedByName");
		return gson.toJson(o);
	}

	/**
	 * The group as it should appear on the RELAY: everything except the signing key.
	 *
	 * That key is what proves a message came from a member, and the stored state is
	 * readable by anyone who knows the group id - so publishing it there handed the
	 * key to anyone who asked for it. It still travels in the INVITE payload, which
	 * is how a joiner is supposed to receive it.
	 *
	 * Used only for what the relay STORES - the state push and the disband
	 * tombstone - and it must stay that way. Do not reach for it on the invite paths: useInviteCode deserialises that payload
	 * straight into the live group, and a group that arrives without a secret has a
	 * brand new one minted for it by the backfill in loadGroups - a DIFFERENT key
	 * from everyone else's. Every signature then fails, and the group quietly splits
	 * in two with nothing shown to the user.
	 *
	 * Serialises a copy; the live group keeps its secret.
	 */
	private String relayGroupJson(LendingGroup group)
	{
		com.google.gson.JsonObject o = gson.toJsonTree(group).getAsJsonObject();
		o.remove("syncSecret");
		return gson.toJson(o);
	}

	/**
	 * Push full group + data state to relay server for offline catch-up.
	 * Called whenever data changes so the relay always has the latest snapshot.
	 */
	private void pushStateToRelay(String groupId)
	{
		if (relaySyncService == null || !relaySyncService.isConnected()) return;

		// Never publish a group we haven't reconciled with since connecting. Every
		// publish path funnels through here — user actions, the 5-minute heartbeat,
		// and the reconnect announce — so this one check is what stops a stale local
		// copy from overwriting the shared record for everyone.
		if (!groupId.equals(caughtUpGroupId)) return;
		if (disbandingGroupIds.contains(groupId)) return;

		LendingGroup group = groups.get(groupId);
		if (group == null) return;

		String groupJson = relayGroupJson(group);
		String dataJson = dataService.getGroupDataSnapshot(groupId);
		relaySyncService.publishState(groupId, groupJson, dataJson, currentSyncPlayerName);
	}

	/**
	 * Handle state received from the relay — either the authoritative catch-up
	 * snapshot the joining client fetched over REST (publisher == null), or a live
	 * broadcast pushed when another member's data changed (publisher != null).
	 *
	 * @param publisher player who pushed this state, or null for authoritative
	 *                  catch-up — the data merge treats a non-null publisher as
	 *                  authoritative for their own rows only.
	 */
	public void handleRelayState(String groupJson, String dataJson, String publisher)
	{
		if (groupJson == null) return;

		try
		{
			LendingGroup remoteGroup = gson.fromJson(groupJson, LendingGroup.class);
			if (remoteGroup == null || remoteGroup.getId() == null) return;

			String groupId = remoteGroup.getId();

			// An authoritative catch-up (null publisher) must only ever apply to the
			// group we're currently syncing — a fetch that was in flight when the
			// user switched or left a group must not write that group's data back.
			// (Live broadcasts are already scoped: the ws socket is per-room.)
			if (publisher == null && !groupId.equals(currentSyncGroupId))
			{
				return;
			}

			// Union-merge the roster: add members present remotely but not locally,
			// and adopt role/permission changes when the remote roster is newer.
			// We never DROP a member on sync — a wholesale replace let a peer with a
			// stale roster erase someone who had just joined on another client.
			LendingGroup localGroup = groups.get(groupId);
			if (localGroup != null)
			{
				// mergeRoster runs on BOTH the ws thread (here) and the sync-executor
				// thread (loadSharedGroupState); lock the group so their field writes
				// can't interleave into a torn code/roster state.
				synchronized (localGroup)
				{
					mergeRoster(localGroup, remoteGroup);
				}
				saveGroups();
				// A kick performed on another machine arrives as a tombstone in this
				// merge. If it names us, stop syncing a group we're no longer in and
				// take it out of the dropdown.
				if (forgetGroupIfRemovedReturnsGone(groupId))
				{
					// Refresh before bailing, or the panel keeps showing the group we were
					// just removed from until something else redraws it.
					if (onSyncCallback != null) onSyncCallback.run();
					return;
				}
			}

			// Reconcile data (marketplace, loans, requests). Pass this player's name
			// so catch-up preserves their own rows.
			if (dataJson != null && !dataJson.isEmpty())
			{
				dataService.loadGroupDataFromSnapshot(groupId, dataJson, publisher, currentSyncPlayerName);
			}

			// Refresh UI
			if (onSyncCallback != null)
			{
				onSyncCallback.run();
			}
		}
		catch (Exception e)
		{
			log.error("Failed to handle relay state: {}", e.getMessage(), e);
		}
	}

	public void syncAllEntries(String groupId, List<LendingEntry> entries)
	{
		if (entries == null || entries.isEmpty()) return;
		String previousGroupId = currentSyncGroupId;
		currentSyncGroupId = groupId;
		// One consolidated event + state push. Publishing per entry would
		// broadcast a full state snapshot for every active loan every time
		// the 5-minute periodic sync runs.
		publishEvent(SyncEventType.ITEM_UPDATED, null, null);
		currentSyncGroupId = previousGroupId;
	}

	// --- Sync Queue Management ---

	private void addEventToQueue(SyncEvent event)
	{
		String key = SYNC_KEY_PREFIX + currentSyncGroupId + SYNC_EVENTS_SUFFIX;
		List<SyncEvent> events = loadEventsFromQueue();
		events.add(event);

		while (events.size() > MAX_SYNC_EVENTS)
		{
			events.remove(0);
		}

		configManager.setConfiguration(CFG_GROUP, key, gson.toJson(events));
	}

	private List<SyncEvent> loadEventsFromQueue()
	{
		if (currentSyncGroupId == null)
		{
			return new ArrayList<>();
		}

		String key = SYNC_KEY_PREFIX + currentSyncGroupId + SYNC_EVENTS_SUFFIX;
		String json = configManager.getConfiguration(CFG_GROUP, key);

		if (json != null && !json.isEmpty())
		{
			try
			{
				Type type = new TypeToken<List<SyncEvent>>(){}.getType();
				List<SyncEvent> events = gson.fromJson(json, type);
				return events != null ? new ArrayList<>(events) : new ArrayList<>();
			}
			catch (Exception e)
			{
				log.error("Failed to load sync events: {}", e.getMessage());
			}
		}

		return new ArrayList<>();
	}

	private void pollForUpdates()
	{
		if (currentSyncGroupId == null) return;

		// If catch-up hasn't succeeded FOR THIS GROUP, the publish gate is holding
		// our data back. Retry so a relay that was down at login (or exhausted its
		// backoff) heals on its own instead of staying silent until the next relog.
		// Compared against the current group, not just null: a marker left behind
		// by a previous group would otherwise block the rescue while the gate
		// blocks every publish — permanently silent. The in-flight flag inside
		// scheduleCatchUpFetch keeps this 5-second tick from stacking chains.
		if (!currentSyncGroupId.equals(caughtUpGroupId)
			&& relaySyncService != null && relaySyncService.isConnected())
		{
			scheduleCatchUpFetch(currentSyncGroupId);
		}

		try
		{
			List<SyncEvent> events = loadEventsFromQueue();
			List<SyncEvent> newEvents = new ArrayList<>();

			for (SyncEvent event : events)
			{
				if (event.getTimestamp() > lastSyncTimestamp &&
					!currentSyncPlayerName.equalsIgnoreCase(event.getPublisher()))
				{
					newEvents.add(event);
				}
			}

			if (!newEvents.isEmpty())
			{
				for (SyncEvent event : newEvents)
				{
					processEvent(event);
				}
				lastSyncTimestamp = System.currentTimeMillis();
				if (onSyncCallback != null)
				{
					onSyncCallback.run();
				}
			}
		}
		catch (Exception e)
		{
			log.error("Error polling for sync updates: {}", e.getMessage());
		}
	}

	private void processEvent(SyncEvent event)
	{
		try
		{
			switch (event.getType())
			{
				case ITEM_RETURNED:
					// Reload FIRST, then apply the return. The other order re-read the
					// pre-return rows out of local config immediately after deleting
					// them, restoring the loan this event exists to close.
					if (currentSyncGroupId != null)
					{
						dataService.loadGroupData(currentSyncGroupId);
					}
					// Apply the return directly by entry id — cross-machine, our own
					// config doesn't contain the change, so reloading isn't enough
					if (event.getDataId() != null)
					{
						dataService.applyReturnedFromSync(event.getDataId());
					}
					break;
				case ITEM_ADDED:
				case ITEM_REMOVED:
				case ITEM_UPDATED:
				case ITEM_SET_DELETED:
				case REQUEST_CREATED:
				case REQUEST_UPDATED:
					if (currentSyncGroupId != null)
					{
						dataService.loadGroupData(currentSyncGroupId);
					}
					break;
				case MEMBER_JOINED:
				case MEMBER_LEFT:
				case SETTINGS_CHANGED:
					if (currentSyncGroupId != null)
					{
						loadSharedGroupState(currentSyncGroupId);
					}
					break;
				case WILDERNESS_ALERT:
				case WILDERNESS_ALERT_COLLATERAL:
					// Surface to the plugin, which decides whether the local player is
					// the affected party (lender or borrower) and whether to notify
					if (onWildernessAlert != null && event.getDataId() != null)
					{
						onWildernessAlert.accept(event);
					}
					break;
				default:
					log.warn("Unknown sync event type: {}", event.getType());
			}
		}
		catch (Exception e)
		{
			log.error("Error processing sync event {}: {}", event.getType(), e.getMessage());
		}
	}

	/**
	 * Load group state from the shared sync key and merge into local groups.
	 * Preserves the local player's membership while updating members/settings from remote.
	 */
	private void loadSharedGroupState(String groupId)
	{
		String key = SYNC_KEY_PREFIX + groupId + SYNC_GROUP_SUFFIX;
		String json = configManager.getConfiguration(CFG_GROUP, key);
		if (json == null || json.isEmpty()) return;

		try
		{
			LendingGroup remoteGroup = gson.fromJson(json, LendingGroup.class);
			if (remoteGroup == null || remoteGroup.getId() == null) return;

			LendingGroup localGroup = groups.get(groupId);
			if (localGroup != null)
			{
				// Same union-merge path as relay state: never drop a member, and
				// keep the roster in a thread-safe (COW) list. Locked on the group
				// so it can't interleave with the ws-thread merge (see handleRelayState).
				synchronized (localGroup)
				{
					mergeRoster(localGroup, remoteGroup);
				}
			}
			else
			{
				// Group doesn't exist locally yet — add it, UNLESS we were removed
				// from it. Without this check, leaving a group put it straight back
				// on the next sync: we drop it locally, this path finds it missing,
				// and re-adopts the shared copy — dropdown entry and all.
				if (wasRemovedFrom(remoteGroup))
				{
					return;
				}
				groups.put(remoteGroup.getId(), ensureCowMembers(remoteGroup));
			}
			saveGroups();
			// Same merge as the relay path, so a kick arriving here must drop the group
			// too - otherwise being removed while THIS path handles the event leaves it
			// sitting in the dropdown.
			forgetGroupIfRemovedReturnsGone(groupId);
		}
		catch (Exception e)
		{
			log.error("Failed to load shared group state for {}", groupId, e);
		}
	}

	// --- Persistence (Account-Specific) ---

	private String getGroupsKey()
	{
		return (currentAccountName != null && !currentAccountName.isEmpty())
			? currentAccountName + CFG_KEY_GROUPS_SUFFIX
			: null;
	}

	private String getActiveGroupKey()
	{
		return (currentAccountName != null && !currentAccountName.isEmpty())
			? currentAccountName + CFG_KEY_ACTIVE_SUFFIX
			: null;
	}

	private void loadGroups()
	{
		String key = getGroupsKey();
		if (key == null) return;

		String json = configManager.getConfiguration(CFG_GROUP, key);
		if (json != null && !json.isEmpty())
		{
			try
			{
				Type type = new TypeToken<List<LendingGroup>>(){}.getType();
				List<LendingGroup> list = gson.fromJson(json, type);
				groups.clear();
				if (list != null)
				{
					boolean needsSave = false;
					for (LendingGroup g : list)
					{
						// A malformed saved group (e.g. null id) must not abort the whole
						// loop — groups.clear() already ran, so bailing here would make
						// every OTHER group vanish from the UI until a config repair.
						if (g == null || g.getId() == null)
						{
							log.warn("Skipping malformed saved group (missing id)");
							continue;
						}
						// Legacy rows deserialize roleUpdatedAt to 0. Stamp them once so 0
						// stops existing here - it is otherwise a value two rows tie on, and a
						// peer republishing 0 could erase a real version.
						if (g.getMembers() != null)
						{
							for (GroupMember m : g.getMembers())
							{
								if (m.getRoleUpdatedAt() == 0) { m.setRoleUpdatedAt(1L); needsSave = true; }
							}
						}
						// ADDED: Ensure existing groups have a sync secret (backwards compat)
						if (g.getSyncSecret() == null || g.getSyncSecret().isEmpty())
						{
							g.ensureSyncSecret();
							needsSave = true;
						}
						// Groups made before founders existed: the sole owner becomes the
						// founder. Skipped when the roster already somehow has several, so
						// we never crown one arbitrarily.
						// Also re-derive when founderName names somebody who is no longer in
						// the roster: isFounder requires membership, so an orphaned name means
						// NOBODY holds founder authority and nothing can ever restore it -
						// transferFounder itself requires being the founder.
						// Re-derive when founderName is absent OR names a non-member. An
						// orphaned name means NOBODY holds founder authority and nothing can
						// restore it, since transferFounder itself requires being the founder.
						boolean founderMissing = g.getFounderName() != null
							&& !g.hasMember(g.getFounderName());
						if ((g.getFounderName() == null || founderMissing) && g.getMembers() != null)
						{
							// Earliest-joined owner, so multi-owner groups recover too and every
							// client picks the same one.
							GroupMember pick = null;
							for (GroupMember m : g.getMembers())
							{
								if (!"owner".equalsIgnoreCase(m.getRole()) || m.getName() == null) continue;
								if (pick == null || m.getJoinedAt() < pick.getJoinedAt()
									|| (m.getJoinedAt() == pick.getJoinedAt()
										&& m.getName().compareToIgnoreCase(pick.getName()) < 0))
								{
									pick = m;
								}
							}
							if (pick != null)
							{
								g.setFounderName(pick.getName());
								// A first backfill is a guess and must lose to any real transfer,
								// so it stamps 1. REPAIRING an orphan is a genuine correction and
								// must BEAT the peers still holding the orphan record - stamp now.
								g.setFounderUpdatedAt(founderMissing ? System.currentTimeMillis() : 1L);
								needsSave = true;
							}
						}
						// Gson deserializes members as a plain ArrayList; wrap it so
						// concurrent roster reads/writes are CME-safe like new groups.
						g.setMembers(new java.util.concurrent.CopyOnWriteArrayList<>(
							g.getMembers() != null ? g.getMembers() : new ArrayList<>()));
						groups.put(g.getId(), g);
					}
					// Save back if any groups needed a secret generated
					if (needsSave)
					{
						saveGroups();
					}
				}

			}
			catch (Exception e)
			{
				log.error("Failed to load groups from {}", key, e);
			}
		}
	}

	private void saveGroups()
	{
		ensureCurrentAccount();

		String key = getGroupsKey();
		if (key == null) return;

		String json = gson.toJson(new ArrayList<>(groups.values()));
		configManager.setConfiguration(CFG_GROUP, key, json);
	}

	private void loadActiveGroup()
	{
		String key = getActiveGroupKey();
		if (key == null) return;

		activeGroupId = configManager.getConfiguration(CFG_GROUP, key);
		if (activeGroupId != null && !groups.containsKey(activeGroupId))
		{
			activeGroupId = null;
		}
	}

	private void saveActiveGroup()
	{
		String key = getActiveGroupKey();
		if (key == null) return;
		configManager.setConfiguration(CFG_GROUP, key, activeGroupId);
	}

	private void ensureCurrentAccount()
	{
		if (currentAccountName == null && isLoggedIn())
		{
			try
			{
				String playerName = client.getLocalPlayer().getName();
				if (playerName != null && !playerName.isEmpty())
				{
					currentAccountName = playerName.toLowerCase().replace(" ", "_");
					loadGroups();
					loadActiveGroup();
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to auto-set currentAccountName", e);
			}
		}
	}

	// --- Helpers ---

	private boolean hasRole(String groupId, String playerName, String role)
	{
		if (groupId == null || playerName == null) return false;
		LendingGroup g = groups.get(groupId);
		if (g == null || g.getMembers() == null) return false;
		return g.getMembers().stream()
			.anyMatch(m -> m.getName().equalsIgnoreCase(playerName) &&
				role.equalsIgnoreCase(m.getRole()));
	}

	private boolean isGroupNameTaken(String name)
	{
		if (name == null || name.trim().isEmpty()) return false;
		String lowerName = name.trim().toLowerCase();
		return groups.values().stream()
			.anyMatch(g -> g.getName().toLowerCase().equals(lowerName));
	}

	// --- Sync Event Types and Data Class ---

	public enum SyncEventType
	{
		ITEM_ADDED,
		ITEM_REMOVED,
		ITEM_UPDATED,
		ITEM_RETURNED,
		MEMBER_JOINED,
		MEMBER_LEFT,
		SETTINGS_CHANGED,
		ITEM_SET_DELETED,
		REQUEST_CREATED,
		REQUEST_UPDATED,
		// Borrower has been in the wilderness 45+ seconds carrying a borrowed item;
		// dataId = the loan entry id, publisher = the borrower. Real-time alarm for
		// the lender — not persisted in snapshots.
		WILDERNESS_ALERT,
		// The LENDER has been in the wilderness 45+ seconds carrying the item
		// collateral they hold for a loan; dataId = the loan entry id, publisher =
		// the lender. Real-time alarm for the borrower, whose collateral is at risk.
		WILDERNESS_ALERT_COLLATERAL
	}

	public static class SyncEvent
	{
		private SyncEventType type;
		private long timestamp;
		private String publisher;
		// Id of the entry/request the event refers to, so receivers can apply
		// targeted changes (e.g. mark a specific loan returned). It IS included in
		// the HMAC payload (see RelaySyncService.buildSignaturePayload) because it
		// drives destructive mutations and must not be tamperable.
		private String dataId;

		public SyncEventType getType() { return type; }
		public void setType(SyncEventType type) { this.type = type; }
		public long getTimestamp() { return timestamp; }
		public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
		public String getPublisher() { return publisher; }
		public void setPublisher(String publisher) { this.publisher = publisher; }
		public String getDataId() { return dataId; }
		public void setDataId(String dataId) { this.dataId = dataId; }
	}
}
