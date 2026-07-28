package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

import com.guess34.lendingtracker.model.ItemSet;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingRequest;

import lombok.extern.slf4j.Slf4j;

/**
 * DataService - Unified data store for all lending tracker data.
 *
 * Consolidates the former Recorder, LendingManager, MarketplaceManager,
 * StorageService, and ItemSetManager into a single service.
 *
 * Handles: marketplace offerings, active loans, history, item sets,
 * and all persistence via ConfigManager.
 */
@Singleton
@Slf4j
public class DataService
{
	// Config keys (backward-compatible with old Recorder/StorageService)
	private static final String CONFIG_GROUP = "lendingtracker";
	private static final String KEY_PREFIX = "lendingtracker.recorder.";
	private static final String ENTRIES_KEY = "lendingtracker.entries";
	private static final String HISTORY_KEY = "lendingtracker.history";
	private static final String ITEMSETS_KEY_PREFIX = "itemsets.";

	// Multi-group data storage (marketplace / lent / borrowed)
	private final Map<String, Map<String, List<LendingEntry>>> groupLent = new ConcurrentHashMap<>();
	private final Map<String, Map<String, List<LendingEntry>>> groupBorrowed = new ConcurrentHashMap<>();
	private final Map<String, Map<String, List<LendingEntry>>> groupAvailable = new ConcurrentHashMap<>();

	// Global entries storage (all entries regardless of group)
	private final Map<String, LendingEntry> allEntries = new ConcurrentHashMap<>();
	private final List<LendingEntry> historyEntries = new CopyOnWriteArrayList<>();

	// Direct lending requests (borrow requests / lend offers): groupId -> requests
	private final Map<String, List<LendingRequest>> groupRequests = new ConcurrentHashMap<>();

	// Item sets: groupId -> setId -> ItemSet
	private final Map<String, Map<String, ItemSet>> groupItemSets = new ConcurrentHashMap<>();

	// Injected dependencies
	@Inject private ConfigManager configManager;
	@Inject private Gson gson;
	@Inject private GroupService groupService;

	private boolean initialized = false;

	public DataService()
	{
		// For @Inject usage
	}

	// Initialization

	public void initialize()
	{
		if (initialized)
		{
			return;
		}

		loadEntries();
		initialized = true;
	}

	/**
	 * Flatten a group's nested map (owner -> entries) into a single list.
	 * Returns an empty list if the group has no data.
	 */
	private List<LendingEntry> flattenGroupData(Map<String, Map<String, List<LendingEntry>>> store, String groupId)
	{
		Map<String, List<LendingEntry>> groupData = store.get(groupId);
		if (groupData == null)
		{
			return new ArrayList<>();
		}
		return groupData.values().stream()
			.flatMap(List::stream)
			.collect(Collectors.toList());
	}

	// Marketplace / Available items

	public void addAvailable(String groupId, String owner, LendingEntry entry)
	{
		entry.setGroupId(groupId);
		entry.setLender(owner);
		entry.setUpdatedAt(System.currentTimeMillis());

		List<LendingEntry> ownerList = groupAvailable
			.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>());

		boolean alreadyExists = ownerList.stream()
			.anyMatch(e -> e.getItemId() == entry.getItemId());

		if (alreadyExists)
		{
			for (LendingEntry existing : ownerList)
			{
				if (existing.getItemId() == entry.getItemId())
				{
					existing.setQuantity(existing.getQuantity() + entry.getQuantity());
					break;
				}
			}
		}
		else
		{
			ownerList.add(new LendingEntry(entry));
		}

		persist(groupId, "available");

		if (groupService != null)
		{
			groupService.publishEvent(
				alreadyExists ? GroupService.SyncEventType.ITEM_UPDATED : GroupService.SyncEventType.ITEM_ADDED,
				entry.getId() != null ? entry.getId() : String.valueOf(entry.getItemId()),
				entry
			);
		}
	}

	/**
	 * Restore an available item from sync data - does NOT add to quantity if item already exists.
	 * Used by LocalDataSyncService to prevent quantity duplication on login.
	 */
	public void restoreAvailable(String groupId, String owner, LendingEntry entry)
	{
		entry.setGroupId(groupId);
		entry.setLender(owner);

		List<LendingEntry> ownerList = groupAvailable
			.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>());

		boolean alreadyExists = ownerList.stream()
			.anyMatch(e ->
				(entry.getId() != null && entry.getId().equals(e.getId())) ||
				(e.getItemId() == entry.getItemId() && Objects.equals(e.getLender(), entry.getLender()))
			);

		if (alreadyExists)
		{
			return;
		}

		ownerList.add(new LendingEntry(entry));
		persist(groupId, "available");
	}

	public void removeAvailable(String groupId, String owner, String itemName, int itemId)
	{
		Map<String, List<LendingEntry>> groupData = groupAvailable.get(groupId);
		if (groupData != null)
		{
			List<LendingEntry> ownerItems = groupData.get(owner);
			if (ownerItems != null)
			{
				ownerItems.removeIf(entry ->
					entry.getItem().equals(itemName) && entry.getItemId() == itemId
				);
				persist(groupId, "available");

				if (groupService != null)
				{
					groupService.publishEvent(
						GroupService.SyncEventType.ITEM_REMOVED,
						itemName + "_" + itemId,
						null
					);
				}
			}
		}
	}

	public void updateAvailable(String groupId, String owner, String itemName, int itemId, LendingEntry updatedEntry)
	{
		Map<String, List<LendingEntry>> groupData = groupAvailable.get(groupId);
		if (groupData != null)
		{
			List<LendingEntry> ownerItems = groupData.get(owner);
			if (ownerItems != null)
			{
				for (int i = 0; i < ownerItems.size(); i++)
				{
					LendingEntry entry = ownerItems.get(i);
					if (entry.getItem().equals(itemName) && entry.getItemId() == itemId)
					{
						updatedEntry.setGroupId(groupId);
						updatedEntry.setLender(owner);
						updatedEntry.setUpdatedAt(System.currentTimeMillis());
						ownerItems.set(i, new LendingEntry(updatedEntry));
						persist(groupId, "available");

						if (groupService != null)
						{
							groupService.publishEvent(
								GroupService.SyncEventType.ITEM_UPDATED,
								updatedEntry.getId() != null ? updatedEntry.getId() : String.valueOf(itemId),
								updatedEntry
							);
						}
						break;
					}
				}
			}
		}
	}

	public List<LendingEntry> getAvailable(String groupId)
	{
		return flattenGroupData(groupAvailable, groupId);
	}

	public void addToAvailableList(LendingEntry entry, String groupId)
	{
		addAvailable(groupId, entry.getLender(), entry);
	}

	/** Add a marketplace offering (MarketplaceManager API). */
	public void addOffering(String groupId, String ownerName, LendingEntry entry)
	{
		if (groupId == null || ownerName == null || entry == null)
		{
			throw new IllegalArgumentException("Invalid offering parameters");
		}
		addAvailable(groupId, ownerName, entry);
	}

	/** Remove a marketplace offering (MarketplaceManager API). */
	public void removeOffering(String groupId, String ownerName, String itemName, int itemId)
	{
		if (groupId == null || ownerName == null)
		{
			throw new IllegalArgumentException("Invalid removal parameters");
		}
		removeAvailable(groupId, ownerName, itemName, itemId);
	}

	public List<LendingEntry> getOfferingsByOwner(String groupId, String ownerName)
	{
		List<LendingEntry> all = getAvailable(groupId);
		if (all == null || ownerName == null)
		{
			return Collections.emptyList();
		}
		return all.stream()
			.filter(e -> ownerName.equalsIgnoreCase(e.getLender()))
			.collect(Collectors.toList());
	}

	/** Remove all items for a specific lender (used when a member is kicked). */
	public void removeItemsForLender(String groupId, String lenderName)
	{
		if (groupId == null || lenderName == null)
		{
			return;
		}

		Map<String, List<LendingEntry>> groupData = groupAvailable.get(groupId);
		if (groupData != null)
		{
			// Names are keyed however the publishing client happened to capitalise
			// them, so an exact-case remove left a kicked member's listings behind.
			// Everything else in this file compares names with equalsIgnoreCase.
			List<LendingEntry> removed = null;
			for (String key : new ArrayList<>(groupData.keySet()))
			{
				if (key != null && key.equalsIgnoreCase(lenderName))
				{
					List<LendingEntry> hit = groupData.remove(key);
					if (hit != null)
					{
						if (removed == null)
						{
							removed = new ArrayList<>();
						}
						removed.addAll(hit);
					}
				}
			}
			if (removed != null && !removed.isEmpty())
			{
				persist(groupId, "available");
				if (groupService != null)
				{
					groupService.publishEvent(
						GroupService.SyncEventType.ITEM_REMOVED,
						groupId + ":" + lenderName,
						null
					);
				}
			}
		}
	}

	// Lending / Borrowing

	/**
	 * Restore a borrowed entry from sync data - does NOT add if entry already exists.
	 */
	public void restoreBorrowed(String groupId, String borrower, LendingEntry entry)
	{
		entry.setGroupId(groupId);
		entry.setBorrower(borrower);

		List<LendingEntry> borrowerList = groupBorrowed
			.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(borrower, k -> new CopyOnWriteArrayList<>());

		boolean alreadyExists = borrowerList.stream()
			.anyMatch(e ->
				(entry.getId() != null && entry.getId().equals(e.getId())) ||
				(e.getItemId() == entry.getItemId() && Objects.equals(e.getBorrower(), entry.getBorrower()))
			);

		if (alreadyExists)
		{
			return;
		}

		borrowerList.add(new LendingEntry(entry));
		persist(groupId, "borrowed");
	}

	/**
	 * Restore a lent entry from sync data - does NOT add if entry already exists.
	 */
	public void restoreLent(String groupId, String lender, String borrower, LendingEntry entry, long dueTime)
	{
		entry.setGroupId(groupId);
		entry.setLender(lender);
		entry.setBorrower(borrower);
		entry.setDueTime(dueTime);

		List<LendingEntry> lenderList = groupLent
			.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(lender, k -> new CopyOnWriteArrayList<>());

		boolean alreadyExists = lenderList.stream()
			.anyMatch(e ->
				(entry.getId() != null && entry.getId().equals(e.getId())) ||
				(e.getItemId() == entry.getItemId() &&
				 Objects.equals(e.getLender(), entry.getLender()) &&
				 Objects.equals(e.getBorrower(), entry.getBorrower()))
			);

		if (alreadyExists)
		{
			return;
		}

		lenderList.add(new LendingEntry(entry));
		persist(groupId, "lent");
	}

	/** Add a new loan (LendingManager API). */
	public void addLoan(String groupId, String lender, String borrower, LendingEntry entry, long dueTime)
	{
		if (groupId == null || lender == null || borrower == null || entry == null)
		{
			throw new IllegalArgumentException("Invalid loan parameters");
		}

		entry.setGroupId(groupId);
		entry.setLender(lender);
		entry.setBorrower(borrower);
		entry.setLendTime(System.currentTimeMillis());
		entry.setDueTime(dueTime);
		entry.setReturnedAt(0);
		entry.setUpdatedAt(System.currentTimeMillis());

		allEntries.put(entry.getId(), entry);

		groupLent.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(lender, k -> new CopyOnWriteArrayList<>())
			.add(entry);

		groupBorrowed.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(borrower, k -> new CopyOnWriteArrayList<>())
			.add(entry);

		saveEntries();
		// persist() writes the whole group snapshot regardless of category, so a
		// single call covers both the lent and borrowed changes.
		persist(groupId, "loan");

		if (groupService != null)
		{
			groupService.publishEvent(
				GroupService.SyncEventType.ITEM_ADDED,
				entry.getId(),
				entry
			);
		}
	}

	// Direct lending requests (borrow requests / lend offers)

	/** Add a new request and sync it to the group. */
	public void addRequest(String groupId, LendingRequest request)
	{
		if (groupId == null || request == null || request.getId() == null)
		{
			return;
		}

		request.setGroupId(groupId);
		List<LendingRequest> requests = groupRequests
			.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>());
		requests.removeIf(r -> request.getId().equals(r.getId()));
		requests.add(request);

		persist(groupId, "requests");

		if (groupService != null)
		{
			groupService.publishEvent(
				GroupService.SyncEventType.REQUEST_CREATED,
				request.getId(),
				request
			);
		}
	}

	/** Update a request's status (accept/decline/cancel) and sync the change. */
	public void updateRequestStatus(String groupId, String requestId, String status)
	{
		List<LendingRequest> requests = groupRequests.get(groupId);
		if (requests == null || requestId == null)
		{
			return;
		}

		for (LendingRequest r : requests)
		{
			if (requestId.equals(r.getId()))
			{
				r.setStatus(status);
				r.setUpdatedAt(System.currentTimeMillis());
				persist(groupId, "requests");

				if (groupService != null)
				{
					groupService.publishEvent(
						GroupService.SyncEventType.REQUEST_UPDATED,
						requestId,
						r
					);
				}
				return;
			}
		}
	}

	/** All requests for a group (any status). */
	public List<LendingRequest> getRequests(String groupId)
	{
		List<LendingRequest> requests = groupRequests.get(groupId);
		if (requests == null)
		{
			return new ArrayList<>();
		}
		synchronized (requests)
		{
			return new ArrayList<>(requests);
		}
	}

	/** Pending requests addressed TO the given player. */
	public List<LendingRequest> getPendingRequestsFor(String groupId, String playerName)
	{
		if (playerName == null)
		{
			return new ArrayList<>();
		}
		return getRequests(groupId).stream()
			.filter(r -> r.isPending() && playerName.equalsIgnoreCase(r.getTo()))
			.collect(Collectors.toList());
	}

	/** Requests created BY the given player. */
	public List<LendingRequest> getRequestsFrom(String groupId, String playerName)
	{
		if (playerName == null)
		{
			return new ArrayList<>();
		}
		return getRequests(groupId).stream()
			.filter(r -> playerName.equalsIgnoreCase(r.getFrom()))
			.collect(Collectors.toList());
	}

	/**
	 * Drop resolved (non-pending) requests last updated before the cutoff across
	 * all groups, so the synced snapshot doesn't grow without bound. Returns the
	 * number removed.
	 */
	public int pruneResolvedRequests(long cutoffTime)
	{
		int removed = 0;
		for (Map.Entry<String, List<LendingRequest>> entry : groupRequests.entrySet())
		{
			List<LendingRequest> list = entry.getValue();
			synchronized (list)
			{
				int before = list.size();
				list.removeIf(r -> !r.isPending() && r.getUpdatedAt() < cutoffTime);
				int delta = before - list.size();
				if (delta > 0)
				{
					removed += delta;
					persist(entry.getKey(), "requests");
				}
			}
		}
		return removed;
	}

	// Getters for lent/borrowed/available lists

	public List<LendingEntry> getLent(String groupId)
	{
		return flattenGroupData(groupLent, groupId);
	}

	public List<LendingEntry> getBorrowed(String groupId)
	{
		return flattenGroupData(groupBorrowed, groupId);
	}

	// Global entries (allEntries map)

	public void addEntry(LendingEntry entry)
	{
		if (entry == null || entry.getId() == null)
		{
			return;
		}

		entry.setUpdatedAt(System.currentTimeMillis());
		allEntries.put(entry.getId(), new LendingEntry(entry));
		saveEntries();
		if (groupService != null && entry.getGroupId() != null)
		{
			groupService.publishEvent(
				GroupService.SyncEventType.ITEM_ADDED,
				entry.getId(),
				entry
			);
		}
	}

	/**
	 * Restore an entry from a LOCAL BACKUP file. Unlike {@link #addEntry} this
	 * PRESERVES the entry's original updatedAt and publishes nothing: a backup is
	 * a point-in-time copy, and stamping it "now" would let stale return tallies
	 * win last-write-wins against the whole group's fresher state (e.g. a partial
	 * return silently rolling back to fully-outstanding everywhere).
	 */
	public void restoreEntry(LendingEntry entry)
	{
		if (entry == null || entry.getId() == null)
		{
			return;
		}
		allEntries.put(entry.getId(), new LendingEntry(entry));
		saveEntries();
	}

	public List<LendingEntry> getActiveEntries()
	{
		return allEntries.values().stream()
			.filter(entry -> !entry.isReturned())
			.collect(Collectors.toList());
	}

	public List<LendingEntry> getHistoryEntries()
	{
		return new ArrayList<>(historyEntries);
	}

	public int removeOldHistoryEntries(long olderThanMs)
	{
		int sizeBefore = historyEntries.size();
		historyEntries.removeIf(entry ->
			entry.getReturnedAt() > 0 && entry.getReturnedAt() < olderThanMs
		);
		int removed = sizeBefore - historyEntries.size();
		if (removed > 0)
		{
			saveEntries();
		}
		return removed;
	}

	public List<LendingEntry> getOverdueEntries()
	{
		long currentTime = Instant.now().toEpochMilli();
		return allEntries.values().stream()
			.filter(entry -> !entry.isReturned() && entry.getDueDate() > 0 && entry.getDueDate() < currentTime)
			.collect(Collectors.toList());
	}

	/**
	 * Persist and sync a PARTIAL return: the entry's outstanding tallies changed
	 * but the loan isn't fully settled yet, so it stays active. Bumps updatedAt
	 * (last-write-wins) and pushes the change live so every member's panel shows
	 * the new "still to return" state immediately.
	 */
	public void updateEntryProgress(LendingEntry entry)
	{
		if (entry == null || entry.getId() == null)
		{
			return;
		}
		entry.setUpdatedAt(System.currentTimeMillis());
		allEntries.put(entry.getId(), entry);
		saveEntries();
		if (entry.getGroupId() != null)
		{
			persist(entry.getGroupId(), "loan");
		}
		if (groupService != null && entry.getGroupId() != null)
		{
			groupService.publishEvent(
				GroupService.SyncEventType.ITEM_UPDATED,
				entry.getId(),
				entry
			);
		}
	}

	public void completeEntry(String entryId, boolean returned)
	{
		LendingEntry entry = allEntries.get(entryId);
		if (entry != null)
		{
			if (returned)
			{
				entry.setReturnedAt(Instant.now().toEpochMilli());
			}
			entry.setUpdatedAt(System.currentTimeMillis());
			historyEntries.add(new LendingEntry(entry));
			if (returned)
			{
				allEntries.remove(entryId);
				// Drop it from the group lists too, the way forgiveLoan and the staff
				// removal path already do. Left behind, the row kept being published
				// as an active loan, and after a restart it no longer shared an object
				// with the history copy — so its returnedAt stayed 0 and no retention
				// sweep could ever reap it.
				removeEntryFromCategory(groupLent, entryId);
				removeEntryFromCategory(groupBorrowed, entryId);
				// The group lists live in the per-group snapshot key, which only
				// persist() writes — saveEntries() covers just entries/history. Skip
				// it and the next loadGroupData resurrects the row from stale config.
				if (entry.getGroupId() != null)
				{
					persist(entry.getGroupId(), "returned");
				}
			}
			saveEntries();
			if (groupService != null && entry.getGroupId() != null)
			{
				groupService.publishEvent(
					GroupService.SyncEventType.ITEM_RETURNED,
					entryId,
					entry
				);
			}
		}
	}

	/**
	 * Remove an active loan and archive it to history as "forgiven by the lender".
	 * The lender is the creditor, so dropping their own claim harms no one — this
	 * is only offered for loans with NO collateral (a collateralised loan is a real
	 * exchange that could be disputed, so it needs the two-party / staff paths).
	 *
	 * Propagates like a return (the lender is authoritative for their own loans, so
	 * peers archive it and it won't be resurrected). Returns false if not found.
	 */
	public boolean forgiveLoan(String entryId, String actingLender)
	{
		LendingEntry entry = allEntries.get(entryId);
		if (entry == null)
		{
			return false;
		}
		// Only the lender may forgive, and only when no collateral is held
		if (actingLender == null || !actingLender.equalsIgnoreCase(entry.getLender()) || hasCollateral(entry))
		{
			return false;
		}

		long now = System.currentTimeMillis();
		entry.setReturnedAt(now); // close it out for history/overdue purposes
		entry.setUpdatedAt(now);
		String stamp = "[Forgiven by lender " + actingLender + "]";
		entry.setNotes(entry.getNotes() == null || entry.getNotes().isEmpty()
			? stamp : entry.getNotes() + " " + stamp);

		historyEntries.add(new LendingEntry(entry));
		allEntries.remove(entryId);
		removeEntryFromCategory(groupLent, entryId);
		removeEntryFromCategory(groupBorrowed, entryId);
		// The group lists only reach config via persist() — saveEntries() covers
		// just entries/history. Without it the next loadGroupData resurrects the
		// removed row from the stale per-group snapshot.
		if (entry.getGroupId() != null)
		{
			persist(entry.getGroupId(), "returned");
		}
		saveEntries();

		if (groupService != null && entry.getGroupId() != null)
		{
			groupService.publishEvent(GroupService.SyncEventType.ITEM_RETURNED, entryId, entry);
		}
		return true;
	}

	/**
	 * True if the lender STILL HOLDS collateral against this loan. Uses the
	 * running tally: once every deposit went back to the borrower, forgiving the
	 * loan strands nothing — the lender is only dropping their own claim.
	 */
	public static boolean hasCollateral(LendingEntry entry)
	{
		return entry.outstandingCollateralGp() > 0
			|| !entry.outstandingCollateralIds().isEmpty();
	}

	/**
	 * Remove an active loan because its removal request was APPROVED (by the
	 * counterparty, or by uninvolved staff). Archives to history with an audit
	 * stamp naming the approval, so a removal is never silent. Propagates like a
	 * return. The caller enforces WHO may trigger this; returns false if the loan
	 * is already gone (idempotent).
	 */
	public boolean removeLoanApproved(String entryId, String auditStamp)
	{
		LendingEntry entry = allEntries.get(entryId);
		if (entry == null)
		{
			return false;
		}

		long now = System.currentTimeMillis();
		entry.setReturnedAt(now);
		entry.setUpdatedAt(now);
		entry.setNotes(entry.getNotes() == null || entry.getNotes().isEmpty()
			? auditStamp : entry.getNotes() + " " + auditStamp);

		historyEntries.add(new LendingEntry(entry));
		allEntries.remove(entryId);
		removeEntryFromCategory(groupLent, entryId);
		removeEntryFromCategory(groupBorrowed, entryId);
		// The group lists only reach config via persist() — saveEntries() covers
		// just entries/history. Without it the next loadGroupData resurrects the
		// removed row from the stale per-group snapshot.
		if (entry.getGroupId() != null)
		{
			persist(entry.getGroupId(), "returned");
		}
		saveEntries();

		if (groupService != null && entry.getGroupId() != null)
		{
			groupService.publishEvent(GroupService.SyncEventType.ITEM_RETURNED, entryId, entry);
		}
		return true;
	}

	/** Look up an active loan by id, or null. */
	public LendingEntry getActiveEntry(String entryId)
	{
		return entryId != null ? allEntries.get(entryId) : null;
	}

	/** Is there already an unresolved removal request for this loan? */
	public boolean hasPendingRemovalFor(String groupId, String entryId)
	{
		return getRequests(groupId).stream()
			.anyMatch(r -> r.isRemoval() && r.isPending() && entryId != null && entryId.equals(r.getEntryId()));
	}

	/** Was a MUTUAL removal request for this loan already declined (escalation grounds)? */
	public boolean hasDeclinedMutualRemovalFor(String groupId, String entryId)
	{
		return getRequests(groupId).stream()
			.anyMatch(r -> LendingRequest.TYPE_REMOVAL_MUTUAL.equals(r.getType())
				&& LendingRequest.STATUS_DECLINED.equals(r.getStatus())
				&& entryId != null && entryId.equals(r.getEntryId()));
	}

	/**
	 * Pending staff-review removals this viewer may adjudicate: viewer must be an
	 * owner or co-owner of the group, must not be the requester, and must not be a
	 * party (lender/borrower) to the loan — nobody clears their own loan.
	 */
	public List<LendingRequest> getPendingStaffRemovalsFor(String groupId, String viewer,
		com.guess34.lendingtracker.model.LendingGroup group)
	{
		List<LendingRequest> result = new ArrayList<>();
		if (viewer == null || group == null || groupService == null)
		{
			return result;
		}
		boolean isStaff = groupService.isOwner(groupId, viewer) || groupService.isCoOwner(groupId, viewer);
		if (!isStaff)
		{
			return result;
		}
		for (LendingRequest r : getRequests(groupId))
		{
			if (!r.isStaffRemoval() || !r.isPending() || viewer.equalsIgnoreCase(r.getFrom()))
			{
				continue;
			}
			LendingEntry entry = getActiveEntry(r.getEntryId());
			if (entry != null
				&& (viewer.equalsIgnoreCase(entry.getLender()) || viewer.equalsIgnoreCase(entry.getBorrower())))
			{
				continue; // party to the loan — conflict of interest
			}
			result.add(r);
		}
		return result;
	}

	// Data retention / cleanup

	public int deleteOldReturnedEntries(long requestedCutoff)
	{
		// The "this loan came back" tombstone is derived from history on every
		// publish, so pruning history below the tombstone window silently shortens
		// it — and it is the PUBLISHER's retention setting that decides, so one
		// member on a short setting would break catch-up for everyone else. Never
		// delete history a snapshot might still need to name.
		final long cutoffTime = Math.min(requestedCutoff, System.currentTimeMillis() - RETURNED_TOMBSTONE_MS);

		int deletedCount = 0;

		for (Map.Entry<String, Map<String, List<LendingEntry>>> groupEntry : groupLent.entrySet())
		{
			for (Map.Entry<String, List<LendingEntry>> accountEntry : groupEntry.getValue().entrySet())
			{
				List<LendingEntry> entries = accountEntry.getValue();
				int sizeBefore = entries.size();
				entries.removeIf(e -> e.getReturnedAt() > 0 && e.getReturnedAt() < cutoffTime);
				deletedCount += sizeBefore - entries.size();
			}
		}

		for (Map.Entry<String, Map<String, List<LendingEntry>>> groupEntry : groupBorrowed.entrySet())
		{
			for (Map.Entry<String, List<LendingEntry>> accountEntry : groupEntry.getValue().entrySet())
			{
				List<LendingEntry> entries = accountEntry.getValue();
				int sizeBefore = entries.size();
				entries.removeIf(e -> e.getReturnedAt() > 0 && e.getReturnedAt() < cutoffTime);
				deletedCount += sizeBefore - entries.size();
			}
		}

		int histSizeBefore = historyEntries.size();
		historyEntries.removeIf(entry ->
			entry.isReturned() && entry.getReturnedAt() < cutoffTime
		);
		deletedCount += histSizeBefore - historyEntries.size();

		if (deletedCount > 0)
		{
			for (String groupId : groupLent.keySet())
			{
				persist(groupId, "lent");
			}
			for (String groupId : groupBorrowed.keySet())
			{
				persist(groupId, "borrowed");
			}
			saveEntries();
		}

		return deletedCount;
	}

	public void clearGroupData(String groupId)
	{
		if (groupId == null || groupId.isEmpty())
		{
			return;
		}

		groupLent.remove(groupId);
		groupBorrowed.remove(groupId);
		groupAvailable.remove(groupId);
		configManager.unsetConfiguration(CONFIG_GROUP, KEY_PREFIX + groupId);
		if (groupService != null)
		{
			groupService.publishEvent(
				GroupService.SyncEventType.ITEM_REMOVED,
				groupId,
				null
			);
		}
	}

	// Item Sets (minimal -- load and clear only)

	private void loadItemSetsForGroup(String groupId)
	{
		if (groupId == null || groupId.isEmpty())
		{
			return;
		}

		String json = configManager.getConfiguration(CONFIG_GROUP, ITEMSETS_KEY_PREFIX + groupId);
		if (json != null && !json.isEmpty())
		{
			try
			{
				Type type = new TypeToken<List<ItemSet>>(){}.getType();
				List<ItemSet> sets = gson.fromJson(json, type);
				if (sets != null && !sets.isEmpty())
				{
					Map<String, ItemSet> setMap = new ConcurrentHashMap<>();
					for (ItemSet set : sets)
					{
						setMap.put(set.getId(), set);
					}
					groupItemSets.put(groupId, setMap);
				}
			}
			catch (Exception e)
			{
				log.error("Failed to load item sets for group {}: {}", groupId, e.getMessage());
			}
		}
	}

	public void clearItemSetData(String groupId)
	{
		if (groupId == null || groupId.isEmpty())
		{
			return;
		}

		groupItemSets.remove(groupId);
		configManager.unsetConfiguration(CONFIG_GROUP, ITEMSETS_KEY_PREFIX + groupId);

		if (groupService != null)
		{
			groupService.publishEvent(
				GroupService.SyncEventType.ITEM_SET_DELETED,
				groupId,
				null
			);
		}
	}

	// Persistence -- group data (lent/borrowed/available per group)

	private void persist(String groupId, String kind)
	{
		configManager.setConfiguration(CONFIG_GROUP, KEY_PREFIX + groupId, buildGroupSnapshotJson(groupId));
	}

	/**
	 * Build the full snapshot for a group: marketplace categories, direct requests,
	 * and active loan entries for the group. One format shared by local config
	 * persistence and relay state sync, so both paths carry the same data.
	 */
	// How long a returned-loan tombstone stays in the snapshot so members who were
	// offline at return time still learn the loan was returned when they catch up.
	private static final long RETURNED_TOMBSTONE_MS = 30L * 86400000L;

	private String buildGroupSnapshotJson(String groupId)
	{
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("lent", groupLent.getOrDefault(groupId, new HashMap<>()));
		snapshot.put("borrowed", groupBorrowed.getOrDefault(groupId, new HashMap<>()));
		snapshot.put("available", groupAvailable.getOrDefault(groupId, new HashMap<>()));
		snapshot.put("requests", getRequests(groupId));
		// Active loans live in the global allEntries map, which never crossed
		// machines before — include this group's entries so loans sync too.
		snapshot.put("entries", allEntries.values().stream()
			.filter(e -> groupId.equals(e.getGroupId()))
			.collect(Collectors.toList()));
		// Tombstones of recently-returned loans. A return removes the entry from
		// allEntries, so without this a member offline at return time would never
		// see it leave their Active Loans on catch-up.
		long cutoff = System.currentTimeMillis() - RETURNED_TOMBSTONE_MS;
		List<String> returnedIds = new ArrayList<>();
		for (LendingEntry h : historyEntries)
		{
			if (groupId.equals(h.getGroupId()) && h.getReturnedAt() > cutoff && h.getId() != null)
			{
				returnedIds.add(h.getId());
			}
		}
		snapshot.put("returnedIds", returnedIds);
		return gson.toJson(snapshot);
	}

	/**
	 * Get the full data snapshot for a group as a JSON string.
	 * Used for relay state sync so offline members can catch up.
	 */
	public String getGroupDataSnapshot(String groupId)
	{
		if (groupId == null) return null;
		return buildGroupSnapshotJson(groupId);
	}

	/**
	 * Apply a group snapshot received over the relay.
	 *
	 * Two very different cases, distinguished by {@code publisher}:
	 *
	 * <b>Catch-up (publisher == null)</b> — the authoritative stored snapshot the
	 * joining client fetched over REST. Adopt it wholesale so deletions made while
	 * this client was offline take effect (no resurrection).
	 *
	 * <b>Live (publisher != null)</b> — a single member's push. That member is
	 * authoritative ONLY for their own rows, so we replace just their owner-key in
	 * each category and leave everyone else untouched. This avoids both the
	 * resurrection bug (a stale peer can't re-add another owner's deleted rows) and
	 * data loss (concurrent edits by different members never clobber each other).
	 * Loans and requests, which aren't cleanly owned by one member, reconcile by a
	 * per-record last-write-wins on {@code updatedAt}.
	 *
	 * All writes replace list/map references rather than mutating live lists, so a
	 * reader iterating on another thread never sees a torn collection.
	 */
	public void loadGroupDataFromSnapshot(String groupId, String snapshotJson)
	{
		loadGroupDataFromSnapshot(groupId, snapshotJson, null, null);
	}

	/**
	 * @param publisher non-null for a live push (authoritative for their own rows
	 *                  only); null for an authoritative catch-up snapshot.
	 * @param selfOwner this client's player name, whose own rows are preserved on
	 *                  catch-up so offline additions aren't clobbered.
	 */
	public void loadGroupDataFromSnapshot(String groupId, String snapshotJson, String publisher, String selfOwner)
	{
		if (groupId == null || snapshotJson == null || snapshotJson.isEmpty()) return;
		try
		{
			@SuppressWarnings("unchecked")
			Map<String, Object> snapshot = gson.fromJson(snapshotJson, Map.class);

			if (publisher == null)
			{
				// Authoritative catch-up: adopt the stored marketplace wholesale so
				// deletions made while offline take effect, but keep the local
				// player's own rows (they're authoritative for their own listings).
				// Loans and requests merge by last-write-wins instead of a wholesale
				// replace, so a snapshot from a member with an incomplete view can't
				// drop live records.
				loadGroupEntries(getCategory(snapshot, "available"), groupId, groupAvailable, selfOwner);
				loadGroupEntries(getCategory(snapshot, "lent"), groupId, groupLent, selfOwner);
				loadGroupEntries(getCategory(snapshot, "borrowed"), groupId, groupBorrowed, selfOwner);
				mergeRequests(groupId, snapshot.get("requests"));
				mergeActiveEntries(groupId, snapshot.get("entries"));
			}
			else
			{
				// Live update: publisher is authoritative only for their own rows.
				applyPublisherRows(getCategory(snapshot, "available"), groupId, groupAvailable, publisher);
				applyPublisherRows(getCategory(snapshot, "lent"), groupId, groupLent, publisher);
				// "borrowed" is deliberately NOT applied here. Unlike available/lent,
				// that map is keyed by BORROWER, not by the publisher — so the
				// publisher-authoritative replace applyPublisherRows does would delete
				// rows other lenders filed under the publisher's borrower key. Borrowed
				// rows still arrive via the catch-up merge, which handles keying
				// correctly; live borrowed sync needs its own merge rule.
				mergeRequests(groupId, snapshot.get("requests"));
				mergeActiveEntries(groupId, snapshot.get("entries"));
			}

			// Apply returned-loan tombstones (both paths) so returns propagate to
			// members who were offline when the loan came back.
			applyReturnedTombstones(snapshot.get("returnedIds"));

			// Persist the reconciled state locally so it survives a restart.
			persist(groupId, "sync");
			log.debug("Applied group snapshot for group {} (publisher: {})", groupId,
				publisher != null ? publisher : "catch-up");
		}
		catch (Exception e)
		{
			log.error("Failed to load group data from snapshot: {}", e.getMessage(), e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, List<Map<String, Object>>> getCategory(Map<String, Object> snapshot, String key)
	{
		Object raw = snapshot.get(key);
		return raw instanceof Map ? (Map<String, List<Map<String, Object>>>) raw : null;
	}

	/**
	 * Live merge for one category: replace ONLY the publisher's own owner-key with
	 * their rows from the snapshot; leave every other owner untouched. The
	 * publisher's key is replaced (not merged) so their own deletions propagate.
	 */
	private void applyPublisherRows(Map<String, List<Map<String, Object>>> rawData, String groupId,
		Map<String, Map<String, List<LendingEntry>>> target, String publisher)
	{
		if (rawData == null || publisher == null) return;

		Map<String, List<LendingEntry>> ownerMap = target
			.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());

		// Find the publisher's rows in the snapshot (owner keys are player names)
		String matchedKey = null;
		List<Map<String, Object>> rows = null;
		for (Map.Entry<String, List<Map<String, Object>>> e : rawData.entrySet())
		{
			if (e.getKey().equalsIgnoreCase(publisher))
			{
				matchedKey = e.getKey();
				rows = e.getValue();
				break;
			}
		}

		// Drop any stale entries filed under the publisher's key(s) first, so a
		// removal (they now have no rows) propagates.
		for (String owner : new ArrayList<>(ownerMap.keySet()))
		{
			if (owner.equalsIgnoreCase(publisher))
			{
				ownerMap.remove(owner);
			}
		}

		if (rows == null || rows.isEmpty())
		{
			return;
		}

		List<LendingEntry> fresh = new CopyOnWriteArrayList<>();
		for (Map<String, Object> row : rows)
		{
			LendingEntry entry = gson.fromJson(gson.toJson(row), LendingEntry.class);
			if (entry != null)
			{
				fresh.add(entry);
			}
		}
		ownerMap.put(matchedKey, fresh);
	}

	/** Merge direct requests from a remote snapshot (union by id, newest update wins). */
	private void mergeRequests(String groupId, Object rawRequests)
	{
		if (!(rawRequests instanceof List))
		{
			return;
		}

		List<LendingRequest> localRequests = groupRequests
			.computeIfAbsent(groupId, k -> new CopyOnWriteArrayList<>());

		for (Object raw : (List<?>) rawRequests)
		{
			LendingRequest remote = gson.fromJson(gson.toJson(raw), LendingRequest.class);
			if (remote == null || remote.getId() == null)
			{
				continue;
			}

			synchronized (localRequests)
			{
				LendingRequest local = localRequests.stream()
					.filter(r -> remote.getId().equals(r.getId()))
					.findFirst().orElse(null);
				if (local == null)
				{
					localRequests.add(remote);
				}
				else if (remote.getUpdatedAt() > local.getUpdatedAt()
					|| (remote.getUpdatedAt() == local.getUpdatedAt() && local.isPending() && !remote.isPending()))
				{
					local.setStatus(remote.getStatus());
					local.setUpdatedAt(remote.getUpdatedAt());
				}
			}
		}
	}

	/** Live merge of loan entries: last-write-wins by updatedAt, archive returns. */
	private void mergeActiveEntries(String groupId, Object rawEntries)
	{
		if (!(rawEntries instanceof List))
		{
			return;
		}

		boolean changed = false;
		for (Object raw : (List<?>) rawEntries)
		{
			LendingEntry remote = gson.fromJson(gson.toJson(raw), LendingEntry.class);
			if (remote == null || remote.getId() == null)
			{
				continue;
			}
			if (applyRemoteEntry(remote))
			{
				changed = true;
			}
		}

		if (changed)
		{
			saveEntries();
		}
	}

	/**
	 * Apply one remote loan entry to allEntries. Returns true if anything changed.
	 * A returned entry is archived (not kept active); an active entry is upserted
	 * when it is newer than the local copy, and never resurrected if this client
	 * has already archived it.
	 */
	private boolean applyRemoteEntry(LendingEntry remote)
	{
		boolean alreadyInHistory;
		synchronized (historyEntries)
		{
			alreadyInHistory = historyEntries.stream()
				.anyMatch(h -> remote.getId().equals(h.getId()));
		}

		if (remote.isReturned())
		{
			boolean changed = false;
			if (!alreadyInHistory)
			{
				historyEntries.add(remote);
				changed = true;
			}
			if (allEntries.remove(remote.getId()) != null)
			{
				changed = true;
			}
			return changed;
		}

		if (alreadyInHistory)
		{
			return false; // returned locally already — don't resurrect
		}

		LendingEntry local = allEntries.get(remote.getId());
		if (local == null || remote.getUpdatedAt() > local.getUpdatedAt())
		{
			allEntries.put(remote.getId(), remote);
			return true;
		}
		return false;
	}

	/**
	 * Mark a loan returned because a sync event from another client said so.
	 * Same effect as {@link #completeEntry} but does NOT publish a sync event,
	 * so two clients can't bounce the same return back and forth.
	 */
	public void applyReturnedFromSync(String entryId)
	{
		// Grab the group before archiving removes the entry — the group snapshot
		// needs the same persist the local return paths do, or the next
		// loadGroupData resurrects the lent/borrowed rows from stale config and
		// we republish the returned loan as active.
		LendingEntry entry = allEntries.get(entryId);
		if (archiveReturnedById(entryId))
		{
			if (entry != null && entry.getGroupId() != null)
			{
				persist(entry.getGroupId(), "returned");
			}
			saveEntries();
		}
	}

	/**
	 * Apply a batch of returned-loan tombstones from a snapshot: archive each id
	 * that's still active locally so returns reach members who were offline.
	 */
	private void applyReturnedTombstones(Object rawIds)
	{
		if (!(rawIds instanceof List))
		{
			return;
		}
		boolean changed = false;
		for (Object raw : (List<?>) rawIds)
		{
			if (raw != null && archiveReturnedById(raw.toString()))
			{
				changed = true;
			}
		}
		if (changed)
		{
			saveEntries();
		}
	}

	/**
	 * Archive a loan by id: move it from the active map to history and drop it
	 * from the lent/borrowed maps. Idempotent — a no-op if already archived or
	 * unknown. Returns true if anything changed. Caller persists.
	 */
	private boolean archiveReturnedById(String entryId)
	{
		if (entryId == null)
		{
			return false;
		}

		LendingEntry entry = allEntries.remove(entryId);
		removeEntryFromCategory(groupLent, entryId);
		removeEntryFromCategory(groupBorrowed, entryId);

		if (entry == null)
		{
			return false; // wasn't active here; nothing to archive
		}

		boolean inHistory = historyEntries.stream().anyMatch(h -> entryId.equals(h.getId()));
		if (!inHistory)
		{
			if (!entry.isReturned())
			{
				entry.setReturnedAt(Instant.now().toEpochMilli());
			}
			historyEntries.add(new LendingEntry(entry));
		}
		return true;
	}

	/** Remove an entry by id from every owner list in a category map. */
	private void removeEntryFromCategory(Map<String, Map<String, List<LendingEntry>>> category, String entryId)
	{
		for (Map<String, List<LendingEntry>> ownerMap : category.values())
		{
			for (List<LendingEntry> list : ownerMap.values())
			{
				list.removeIf(e -> entryId.equals(e.getId()));
			}
		}
	}

	/**
	 * Load group-specific data from storage.
	 * Called when switching groups to restore previously saved items.
	 * Also loads item sets for the group.
	 */
	public void loadGroupData(String groupId)
	{
		if (groupId == null || groupId.isEmpty())
		{
			return;
		}

		String json = configManager.getConfiguration(CONFIG_GROUP, KEY_PREFIX + groupId);
		if (json != null && !json.isEmpty())
		{
			try
			{
				@SuppressWarnings("unchecked")
				Map<String, Object> snapshot = gson.fromJson(json, Map.class);

				loadGroupEntries(getCategory(snapshot, "available"), groupId, groupAvailable);
				loadGroupEntries(getCategory(snapshot, "lent"), groupId, groupLent);
				loadGroupEntries(getCategory(snapshot, "borrowed"), groupId, groupBorrowed);
				mergeRequests(groupId, snapshot.get("requests"));
				mergeActiveEntries(groupId, snapshot.get("entries"));
			}
			catch (Exception e)
			{
				log.error("Failed to load group data for {}: {}", groupId, e.getMessage(), e);
			}
		}

		loadItemSetsForGroup(groupId);
	}

	/**
	 * Helper to deserialize a category (available/lent/borrowed) from a raw map snapshot.
	 */
	private void loadGroupEntries(Map<String, List<Map<String, Object>>> rawData, String groupId,
		Map<String, Map<String, List<LendingEntry>>> target)
	{
		loadGroupEntries(rawData, groupId, target, null);
	}

	/**
	 * Wholesale-load one category from a snapshot, atomically swapping in a fresh
	 * owner-map so readers on other threads never see a torn collection.
	 *
	 * When {@code selfOwner} is set (authoritative catch-up), that owner's local
	 * rows are preserved rather than overwritten — the local player is always
	 * authoritative for their own listings, so a stale relay snapshot can't drop
	 * items they added while disconnected.
	 */
	private void loadGroupEntries(Map<String, List<Map<String, Object>>> rawData, String groupId,
		Map<String, Map<String, List<LendingEntry>>> target, String selfOwner)
	{
		// A null category means the snapshot didn't carry this section at all (old
		// or foreign format); preserve local data rather than wiping it. An EMPTY
		// map is different — it means "no rows", so deletions still propagate.
		if (rawData == null)
		{
			return;
		}

		Map<String, List<LendingEntry>> existing = target.get(groupId);

		Map<String, List<LendingEntry>> entryMap = new ConcurrentHashMap<>();
		{
			for (Map.Entry<String, List<Map<String, Object>>> ownerEntry : rawData.entrySet())
			{
				if (selfOwner != null && ownerEntry.getKey().equalsIgnoreCase(selfOwner))
				{
					continue; // preserve local self rows below
				}
				List<LendingEntry> entries = new CopyOnWriteArrayList<>();
				for (Map<String, Object> entryData : ownerEntry.getValue())
				{
					LendingEntry entry = gson.fromJson(gson.toJson(entryData), LendingEntry.class);
					if (entry != null)
					{
						entries.add(entry);
					}
				}
				if (!entries.isEmpty())
				{
					entryMap.put(ownerEntry.getKey(), entries);
				}
			}
		}

		// Carry over the local player's own rows untouched
		if (selfOwner != null && existing != null)
		{
			for (Map.Entry<String, List<LendingEntry>> e : existing.entrySet())
			{
				if (e.getKey().equalsIgnoreCase(selfOwner))
				{
					entryMap.put(e.getKey(), e.getValue());
				}
			}
		}

		target.put(groupId, entryMap);
	}

	// Persistence -- global entries (allEntries + history)

	private void loadEntries()
	{
		try
		{
			String entriesJson = configManager.getConfiguration(CONFIG_GROUP, ENTRIES_KEY);
			if (entriesJson != null)
			{
				Type type = new TypeToken<Map<String, LendingEntry>>(){}.getType();
				Map<String, LendingEntry> loaded = gson.fromJson(entriesJson, type);
				if (loaded != null)
				{
					allEntries.putAll(loaded);
				}
			}

			String historyJson = configManager.getConfiguration(CONFIG_GROUP, HISTORY_KEY);
			if (historyJson != null)
			{
				Type type = new TypeToken<List<LendingEntry>>(){}.getType();
				List<LendingEntry> loaded = gson.fromJson(historyJson, type);
				if (loaded != null)
				{
					historyEntries.addAll(loaded);
				}
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load entries", e);
		}
	}

	private void saveEntries()
	{
		try
		{
			configManager.setConfiguration(CONFIG_GROUP, ENTRIES_KEY, gson.toJson(allEntries));
			configManager.setConfiguration(CONFIG_GROUP, HISTORY_KEY, gson.toJson(historyEntries));
		}
		catch (Exception e)
		{
			log.error("Failed to save entries", e);
		}
	}
}
