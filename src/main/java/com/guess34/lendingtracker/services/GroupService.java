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
	private java.util.function.Consumer<SyncEvent> onWildernessAlert;

	// The group whose stored snapshot we have reconciled with since connecting.
	// Publishing before that has happened is what makes a brief disconnect
	// destructive: the relay keeps ONE record per group and overwrites it with
	// whatever arrives, so uploading a view that predates changes made while we
	// were away rolls them back for every member, not just for us. Written from
	// the sync executor, read from the ws callback thread — hence volatile.
	private volatile String caughtUpGroupId;

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
		g.addMember(owner);
		touchRoster(g);

		groups.put(id, g);
		activeGroupId = id;
		saveActiveGroup();
		saveGroups();
		return id;
	}

	public void deleteGroup(String id)
	{
		groups.remove(id);
		if (Objects.equals(activeGroupId, id))
		{
			activeGroupId = groups.isEmpty() ? null : groups.keySet().iterator().next();
			saveActiveGroup();
		}
		saveGroups();
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
		Map<String, Long> tombstones = new HashMap<>(local.getRemovedMembersSafe());
		for (Map.Entry<String, Long> t : remote.getRemovedMembersSafe().entrySet())
		{
			tombstones.merge(t.getKey(), t.getValue(), Math::max);
		}

		if (remote.getMembers() != null)
		{
			for (GroupMember rm : remote.getMembers())
			{
				// Don't adopt a member who was kicked after they joined — that's a
				// stale roster echoing someone we removed. (A re-join carries a
				// fresh joinedAt newer than the tombstone, so it survives.)
				Long removedAt = tombstones.get(rm.getName().toLowerCase());
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
				else if (remoteIsNewer && rm.getRole() != null)
				{
					existing.setRole(rm.getRole());
				}
			}
		}

		// Apply tombstones to what we already had: this is the receiving side of a
		// kick performed on another machine.
		merged.removeIf(m ->
		{
			Long removedAt = tombstones.get(m.getName().toLowerCase());
			return removedAt != null && removedAt > m.getJoinedAt();
		});

		// Prune tombstones that a re-join has outdated, so the map can't grow
		// stale entries forever.
		for (GroupMember m : merged)
		{
			Long removedAt = tombstones.get(m.getName().toLowerCase());
			if (removedAt != null && m.getJoinedAt() >= removedAt)
			{
				tombstones.remove(m.getName().toLowerCase());
			}
		}
		local.setRemovedMembers(tombstones);

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
		publishEvent(SyncEventType.MEMBER_LEFT, groupId + ":" + name, null);
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
		if ("owner".equalsIgnoreCase(newRole)) return false;
		if ("co-owner".equalsIgnoreCase(newRole) && !isOwner(groupId, requesterName)) return false;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(targetName))
			{
				member.setRole(newRole.toLowerCase());
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
		if (!isOwner(groupId, currentOwnerName)) return false;
		if (currentOwnerName.equalsIgnoreCase(newOwnerName)) return false;

		GroupMember currentOwnerMember = null;
		GroupMember newOwnerMember = null;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(currentOwnerName)) currentOwnerMember = member;
			if (member.getName().equalsIgnoreCase(newOwnerName)) newOwnerMember = member;
		}

		if (currentOwnerMember == null || newOwnerMember == null) return false;

		newOwnerMember.setRole("owner");
		currentOwnerMember.setRole("co-owner");

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
		return new String[] {"co-owner", "admin", "mod", "member"};
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

		String requesterRole = getMemberRole(groupId, requesterName);
		if (requesterRole == null || getRoleRank(requesterRole) < 4) return false;

		boolean isKick = "kick".equals(permType);
		switch (role.toLowerCase())
		{
			case "co-owner":
				if (getRoleRank(requesterRole) < 5) return false;
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

	public boolean canChangeRole(String groupId, String changerName, String targetName)
	{
		if (groupId == null || changerName == null || targetName == null) return false;
		if (changerName.equalsIgnoreCase(targetName)) return false;

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
						sharedGroup.addMember(new GroupMember(playerName, "member"));
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
							relayGroup.addMember(new GroupMember(playerName, "member"));
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
		if (group == null)
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
		String groupJson = gson.toJson(group);
		// Store in shared config so other accounts on the same machine can look up this code
		configManager.setConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + code, groupJson);

		boolean syncEnabled = config.enableRelaySync();
		boolean published = false;
		if (syncEnabled && relaySyncService != null)
		{
			// Confirm the code actually reached the relay before the owner hands it out
			published = relaySyncService.publishInviteBlocking(code, groupId, groupJson);
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

		group.setClanCode(code);
		group.setClanCodeEnabled(true);
		group.touchCodeState();
		saveGroups();

		String groupJson = gson.toJson(group);
		// Same-machine joiners look the code up in shared config
		configManager.setConfiguration(CFG_GROUP, INVITE_KEY_PREFIX + code, groupJson);

		boolean syncEnabled = config.enableRelaySync();
		boolean published = false;
		if (syncEnabled && relaySyncService != null)
		{
			published = relaySyncService.publishInviteBlocking(code, groupId, groupJson);
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
		relaySyncService.publishInviteCode(group.getClanCode(), group.getId(), gson.toJson(group));
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

	public void startSync(String groupId, String playerName)
	{
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
			syncExecutor.shutdown();
			try
			{
				if (!syncExecutor.awaitTermination(1, TimeUnit.SECONDS))
				{
					syncExecutor.shutdownNow();
				}
			}
			catch (InterruptedException e)
			{
				syncExecutor.shutdownNow();
			}
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

		LendingGroup group = groups.get(groupId);
		if (group == null) return;

		String groupJson = gson.toJson(group);
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
				// Group doesn't exist locally yet — add it
				groups.put(remoteGroup.getId(), ensureCowMembers(remoteGroup));
			}
			saveGroups();
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
						// ADDED: Ensure existing groups have a sync secret (backwards compat)
						if (g.getSyncSecret() == null || g.getSyncSecret().isEmpty())
						{
							g.ensureSyncSecret();
							needsSave = true;
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
