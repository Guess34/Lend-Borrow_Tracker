package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

import com.guess34.lendingtracker.model.ItemSet;
import com.guess34.lendingtracker.model.LendingEntry;

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
	private final List<LendingEntry> historyEntries = Collections.synchronizedList(new ArrayList<>());

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

		List<LendingEntry> ownerList = groupAvailable
			.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(owner, k -> new ArrayList<>());

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
			.computeIfAbsent(owner, k -> new ArrayList<>());

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
			List<LendingEntry> removed = groupData.remove(lenderName);
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
			.computeIfAbsent(borrower, k -> new ArrayList<>());

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
			.computeIfAbsent(lender, k -> new ArrayList<>());

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

		allEntries.put(entry.getId(), entry);

		groupLent.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(lender, k -> Collections.synchronizedList(new ArrayList<>()))
			.add(entry);

		groupBorrowed.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(borrower, k -> Collections.synchronizedList(new ArrayList<>()))
			.add(entry);

		saveEntries();
		persist(groupId, "lent");
		persist(groupId, "borrowed");
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

	public List<LendingEntry> getEntriesForPlayer(String playerName)
	{
		if (playerName == null)
		{
			return new ArrayList<>();
		}

		return allEntries.values().stream()
			.filter(entry -> playerName.equalsIgnoreCase(entry.getPlayerName()) ||
				playerName.equalsIgnoreCase(entry.getBorrower()) ||
				playerName.equalsIgnoreCase(entry.getLender()))
			.collect(Collectors.toList());
	}

	public List<LendingEntry> getOverdueEntries()
	{
		long currentTime = Instant.now().toEpochMilli();
		return allEntries.values().stream()
			.filter(entry -> !entry.isReturned() && entry.getDueDate() > 0 && entry.getDueDate() < currentTime)
			.collect(Collectors.toList());
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
			historyEntries.add(new LendingEntry(entry));
			if (returned)
			{
				allEntries.remove(entryId);
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

	// Data retention / cleanup

	public int deleteOldReturnedEntries(long cutoffTime)
	{
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
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("lent", groupLent.getOrDefault(groupId, new HashMap<>()));
		snapshot.put("borrowed", groupBorrowed.getOrDefault(groupId, new HashMap<>()));
		snapshot.put("available", groupAvailable.getOrDefault(groupId, new HashMap<>()));
		String json = gson.toJson(snapshot);
		configManager.setConfiguration(CONFIG_GROUP, KEY_PREFIX + groupId, json);
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
				Map<String, Map<String, List<Map<String, Object>>>> snapshot = gson.fromJson(json, Map.class);

				loadGroupEntries(snapshot.get("available"), groupId, groupAvailable);
				loadGroupEntries(snapshot.get("lent"), groupId, groupLent);
				loadGroupEntries(snapshot.get("borrowed"), groupId, groupBorrowed);
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
		if (rawData == null || rawData.isEmpty())
		{
			return;
		}

		Map<String, List<LendingEntry>> entryMap = new ConcurrentHashMap<>();
		for (Map.Entry<String, List<Map<String, Object>>> ownerEntry : rawData.entrySet())
		{
			List<LendingEntry> entries = new ArrayList<>();
			for (Map<String, Object> entryData : ownerEntry.getValue())
			{
				String entryJson = gson.toJson(entryData);
				LendingEntry entry = gson.fromJson(entryJson, LendingEntry.class);
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
