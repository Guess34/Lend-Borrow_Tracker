package com.guess34.lendingtracker.services.core;

import lombok.extern.slf4j.Slf4j;
import com.guess34.lendingtracker.model.LendingEntry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * LendingManager - Loan lifecycle management
 * Handles lending, returning, two-party confirmation, history
 */
@Slf4j
@Singleton
public class LendingManager
{
	@Inject
	private StorageService storageService;

	// In-memory cache
	private final Map<String, LendingEntry> allEntries = new ConcurrentHashMap<>(); // entryId -> entry
	private final List<LendingEntry> history = Collections.synchronizedList(new ArrayList<>());

	// Group-specific data: groupId -> playerName -> List<LendingEntry>
	private final Map<String, Map<String, List<LendingEntry>>> groupLent = new ConcurrentHashMap<>();
	private final Map<String, Map<String, List<LendingEntry>>> groupBorrowed = new ConcurrentHashMap<>();

	/**
	 * Initialize - load data from storage
	 */
	public void initialize()
	{
		log.info("Initializing LendingManager...");

		// Load entries
		Map<String, LendingEntry> loadedEntries = storageService.loadEntries();
		allEntries.putAll(loadedEntries);
		log.info("Loaded {} active entries", allEntries.size());

		// Load history
		List<LendingEntry> loadedHistory = storageService.loadHistory();
		history.addAll(loadedHistory);
		log.info("Loaded {} history entries", history.size());

		// Rebuild group maps from entries
		rebuildGroupMaps();
	}

	/**
	 * Rebuild group maps from allEntries
	 */
	private void rebuildGroupMaps()
	{
		groupLent.clear();
		groupBorrowed.clear();

		for (LendingEntry entry : allEntries.values())
		{
			if (entry.isReturned())
			{
				continue; // Skip returned entries
			}

			String groupId = entry.getGroupId();
			if (groupId == null)
			{
				continue;
			}

			// Add to lent map
			groupLent.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(entry.getLender(), k -> Collections.synchronizedList(new ArrayList<>()))
				.add(entry);

			// Add to borrowed map
			groupBorrowed.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
				.computeIfAbsent(entry.getBorrower(), k -> Collections.synchronizedList(new ArrayList<>()))
				.add(entry);
		}

		log.debug("Rebuilt group maps - {} groups in lent, {} groups in borrowed",
			groupLent.size(), groupBorrowed.size());
	}

	/**
	 * Add a new loan
	 */
	public void addLoan(String groupId, String lender, String borrower, LendingEntry entry, long dueTime)
	{
		if (groupId == null || lender == null || borrower == null || entry == null)
		{
			throw new IllegalArgumentException("Invalid loan parameters");
		}

		// Set entry details
		entry.setGroupId(groupId);
		entry.setLender(lender);
		entry.setBorrower(borrower);
		entry.setLendTime(System.currentTimeMillis());
		entry.setDueTime(dueTime);
		entry.setReturnedAt(0); // 0 = not returned (isReturned() checks returnedAt > 0)

		// Add to allEntries
		allEntries.put(entry.getId(), entry);

		// Add to group maps
		groupLent.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(lender, k -> Collections.synchronizedList(new ArrayList<>()))
			.add(entry);

		groupBorrowed.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(borrower, k -> Collections.synchronizedList(new ArrayList<>()))
			.add(entry);

		// Save to storage
		save();

		log.info("Added loan: {} lent {} to {} in group {}",
			lender, entry.getItemName(), borrower, groupId);
	}

	/**
	 * Confirm return (two-party confirmation system)
	 * Returns true if both parties have confirmed (loan is now complete)
	 */
	public boolean confirmReturn(String groupId, String lenderName, String borrowerName, String itemName, boolean isLender)
	{
		if (groupId == null || lenderName == null || borrowerName == null || itemName == null)
		{
			throw new IllegalArgumentException("Invalid confirmation parameters");
		}

		// Find the entry
		LendingEntry entry = findEntry(groupId, lenderName, borrowerName, itemName);
		if (entry == null)
		{
			log.warn("No active loan found for confirmation: {} -> {} item: {}",
				lenderName, borrowerName, itemName);
			return false;
		}

		// Mark confirmation
		if (isLender)
		{
			entry.setLenderConfirmedReturn(true);
			log.debug("Lender {} confirmed return of {}", lenderName, itemName);
		}
		else
		{
			entry.setBorrowerConfirmedReturn(true);
			log.debug("Borrower {} confirmed return of {}", borrowerName, itemName);
		}

		// Check if both confirmed
		if (entry.isBothPartiesConfirmedReturn())
		{
			entry.setReturnedAt(System.currentTimeMillis()); // Mark as returned with timestamp

			// Move to history
			moveToHistory(entry.getId(), true);

			log.info("Loan completed (both parties confirmed): {} returned {} to {}",
				borrowerName, itemName, lenderName);
			return true;
		}

		// Save partial confirmation
		save();
		return false;
	}

	/**
	 * Find an entry by group, lender, borrower, and item name
	 */
	private LendingEntry findEntry(String groupId, String lenderName, String borrowerName, String itemName)
	{
		Map<String, List<LendingEntry>> groupBorrowedMap = groupBorrowed.get(groupId);
		if (groupBorrowedMap == null)
		{
			return null;
		}

		List<LendingEntry> borrowedList = groupBorrowedMap.get(borrowerName);
		if (borrowedList == null)
		{
			return null;
		}

		return borrowedList.stream()
			.filter(e -> e.getLender().equalsIgnoreCase(lenderName)
				&& e.getItemName().equalsIgnoreCase(itemName)
				&& !e.isReturned())
			.findFirst()
			.orElse(null);
	}

	/**
	 * Move entry to history
	 */
	public void moveToHistory(String entryId, boolean returned)
	{
		LendingEntry entry = allEntries.get(entryId);
		if (entry == null)
		{
			log.warn("Cannot move to history - entry not found: {}", entryId);
			return;
		}

		if (returned)
		{
			entry.setReturnedAt(System.currentTimeMillis()); // Mark as returned with timestamp
		}
		else
		{
			entry.setReturnedAt(0); // Not returned (defaulted/cancelled)
		}

		// Add to history
		history.add(entry);

		// Remove from active entries
		allEntries.remove(entryId);

		// Remove from group maps
		removeFromGroupMaps(entry);

		// Save changes
		save();

		log.info("Moved entry to history: {} (returned: {})", entryId, returned);
	}

	/**
	 * Remove entry from group maps
	 */
	private void removeFromGroupMaps(LendingEntry entry)
	{
		String groupId = entry.getGroupId();
		if (groupId == null)
		{
			return;
		}

		// Remove from lent
		Map<String, List<LendingEntry>> lentMap = groupLent.get(groupId);
		if (lentMap != null)
		{
			List<LendingEntry> lentList = lentMap.get(entry.getLender());
			if (lentList != null)
			{
				lentList.removeIf(e -> e.getId().equals(entry.getId()));
			}
		}

		// Remove from borrowed
		Map<String, List<LendingEntry>> borrowedMap = groupBorrowed.get(groupId);
		if (borrowedMap != null)
		{
			List<LendingEntry> borrowedList = borrowedMap.get(entry.getBorrower());
			if (borrowedList != null)
			{
				borrowedList.removeIf(e -> e.getId().equals(entry.getId()));
			}
		}
	}

	/**
	 * Get all active entries
	 */
	public List<LendingEntry> getActiveEntries()
	{
		return new ArrayList<>(allEntries.values());
	}

	/**
	 * Get all history entries
	 */
	public List<LendingEntry> getHistoryEntries()
	{
		return new ArrayList<>(history);
	}

	/**
	 * ADDED: Remove history entries older than the specified timestamp
	 * @return number of entries removed
	 */
	public int removeOldHistoryEntries(long olderThanMs)
	{
		int removed = 0;
		java.util.Iterator<LendingEntry> it = history.iterator();
		while (it.hasNext())
		{
			LendingEntry entry = it.next();
			if (entry.getReturnedAt() > 0 && entry.getReturnedAt() < olderThanMs)
			{
				it.remove();
				removed++;
			}
		}
		if (removed > 0)
		{
			save();
		}
		return removed;
	}

	/**
	 * Get entries for a specific player (as lender or borrower)
	 */
	public List<LendingEntry> getEntriesForPlayer(String playerName)
	{
		return allEntries.values().stream()
			.filter(e -> e.getLender().equalsIgnoreCase(playerName)
				|| e.getBorrower().equalsIgnoreCase(playerName))
			.collect(Collectors.toList());
	}

	/**
	 * Get entries lent by a player
	 */
	public List<LendingEntry> getEntriesLentBy(String groupId, String lenderName)
	{
		Map<String, List<LendingEntry>> lentMap = groupLent.get(groupId);
		if (lentMap == null)
		{
			return new ArrayList<>();
		}

		List<LendingEntry> lentList = lentMap.get(lenderName);
		return lentList != null ? new ArrayList<>(lentList) : new ArrayList<>();
	}

	/**
	 * Get entries borrowed by a player
	 */
	public List<LendingEntry> getEntriesBorrowedBy(String groupId, String borrowerName)
	{
		Map<String, List<LendingEntry>> borrowedMap = groupBorrowed.get(groupId);
		if (borrowedMap == null)
		{
			return new ArrayList<>();
		}

		List<LendingEntry> borrowedList = borrowedMap.get(borrowerName);
		return borrowedList != null ? new ArrayList<>(borrowedList) : new ArrayList<>();
	}

	/**
	 * Get total value lent to a player
	 */
	public long getTotalValueLentToPlayer(String playerName)
	{
		return allEntries.values().stream()
			.filter(e -> e.getBorrower().equalsIgnoreCase(playerName) && !e.isReturned())
			.mapToLong(LendingEntry::getValue)
			.sum();
	}

	/**
	 * Get overdue entries
	 */
	public List<LendingEntry> getOverdueEntries()
	{
		long now = System.currentTimeMillis();
		return allEntries.values().stream()
			.filter(e -> !e.isReturned() && e.getDueTime() > 0 && e.getDueTime() < now)
			.collect(Collectors.toList());
	}

	/**
	 * Extend loan due date
	 */
	public void extendLoan(String groupId, String lender, String borrower, String itemName, int additionalDays)
	{
		LendingEntry entry = findEntry(groupId, lender, borrower, itemName);
		if (entry == null)
		{
			throw new IllegalArgumentException("Loan not found");
		}

		long extension = additionalDays * 24L * 60L * 60L * 1000L; // days to milliseconds
		entry.setDueTime(entry.getDueTime() + extension);

		save();

		log.info("Extended loan by {} days: {} -> {} item: {}",
			additionalDays, lender, borrower, itemName);
	}

	/**
	 * Delete old returned entries from history
	 */
	public int deleteOldReturnedEntries(long cutoffTime)
	{
		int removed = 0;
		Iterator<LendingEntry> iterator = history.iterator();

		while (iterator.hasNext())
		{
			LendingEntry entry = iterator.next();
			if (entry.isReturned() && entry.getReturnedAt() < cutoffTime)
			{
				iterator.remove();
				removed++;
			}
		}

		if (removed > 0)
		{
			save();
			log.info("Deleted {} old returned entries from history", removed);
		}

		return removed;
	}

	/**
	 * Clear all data
	 */
	public void clearAll()
	{
		allEntries.clear();
		history.clear();
		groupLent.clear();
		groupBorrowed.clear();

		storageService.clearEntries();
		storageService.clearHistory();

		log.info("Cleared all lending data");
	}

	/**
	 * Save current state to storage
	 */
	private void save()
	{
		// Save entries
		storageService.saveEntries(allEntries);

		// Save history
		storageService.saveHistory(history);

		// Save group-specific data
		for (String groupId : groupLent.keySet())
		{
			Map<String, List<LendingEntry>> lent = groupLent.get(groupId);
			Map<String, List<LendingEntry>> borrowed = groupBorrowed.get(groupId);

			storageService.saveGroupData(groupId, lent, borrowed, new ConcurrentHashMap<>());
		}
	}
}
