package net.runelite.client.plugins.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.model.WarningLogEntry;
import net.runelite.client.plugins.lendingtracker.services.sync.GroupSyncService;
import net.runelite.client.RuneLite;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class Recorder
{
    // Multi-group data storage
    private final Map<String, Map<String, List<LendingEntry>>> groupLent = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<LendingEntry>>> groupBorrowed = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<LendingEntry>>> groupAvailable = new ConcurrentHashMap<>();
    
    // Global entries storage (all entries regardless of group)
    private final Map<String, LendingEntry> allEntries = new ConcurrentHashMap<>();
    private final List<LendingEntry> historyEntries = new ArrayList<>();

    @Inject private Client client;
    @Inject private ItemManager itemManager;
    @Inject private ConfigManager configManager;
    @Inject private Gson gson;
    @Inject private GroupSyncService groupSyncService;

    // FIXED: Removed manual constructor - using only @Inject dependency injection
    
    // Default constructor for injection
    public Recorder() {
        // For @Inject usage
    }

    // Example save/load (JSON) keys
    private static final String KEY_PREFIX = "lendingtracker.recorder.";
    private static final String ENTRIES_KEY = "lendingtracker.entries";
    private static final String HISTORY_KEY = "lendingtracker.history";
    
    private boolean initialized = false;

    public void addAvailable(String groupId, String owner, LendingEntry entry)
    {
        entry.setGroupId(groupId);
        entry.setLender(owner);

        // FIXED: Check for duplicates before adding
        List<LendingEntry> ownerList = groupAvailable
            .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(owner, k -> new ArrayList<>());

        // Check if item already exists (same itemId from same owner)
        boolean alreadyExists = ownerList.stream()
            .anyMatch(e -> e.getItemId() == entry.getItemId());

        if (alreadyExists)
        {
            log.info("Item {} already in marketplace for owner {}, updating quantity instead", entry.getItem(), owner);
            // Update existing entry's quantity instead of adding duplicate
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

        // Publish sync event for real-time updates to other group members
        if (groupSyncService != null)
        {
            groupSyncService.publishEvent(
                alreadyExists ? GroupSyncService.SyncEventType.ITEM_UPDATED : GroupSyncService.SyncEventType.ITEM_ADDED,
                entry.getId() != null ? entry.getId() : String.valueOf(entry.getItemId()),
                entry
            );
        }
    }

    /**
     * ADDED: Restore an available item from sync data - does NOT add to quantity if item already exists
     * This is used by LocalDataSyncService to prevent quantity duplication on login
     */
    public void restoreAvailable(String groupId, String owner, LendingEntry entry)
    {
        entry.setGroupId(groupId);
        entry.setLender(owner);

        List<LendingEntry> ownerList = groupAvailable
            .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(owner, k -> new ArrayList<>());

        // Check if item already exists by entry ID first, then by itemId
        boolean alreadyExists = ownerList.stream()
            .anyMatch(e ->
                (entry.getId() != null && entry.getId().equals(e.getId())) ||
                (e.getItemId() == entry.getItemId() && Objects.equals(e.getLender(), entry.getLender()))
            );

        if (alreadyExists)
        {
            // FIXED: Do NOT add to quantity - just skip this entry
            log.debug("Skipping restore of {} - already exists for owner {}", entry.getItem(), owner);
            return;
        }

        // Only add if truly new
        ownerList.add(new LendingEntry(entry));
        persist(groupId, "available");
        log.debug("Restored available item: {} for owner {}", entry.getItem(), owner);
    }

    // ADDED: Remove an item from the marketplace
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

                // Publish sync event for real-time updates
                if (groupSyncService != null)
                {
                    groupSyncService.publishEvent(
                        GroupSyncService.SyncEventType.ITEM_REMOVED,
                        itemName + "_" + itemId,
                        null
                    );
                }
            }
        }
    }

    // ADDED: Update an existing marketplace item
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

                        // Publish sync event for real-time updates
                        if (groupSyncService != null)
                        {
                            groupSyncService.publishEvent(
                                GroupSyncService.SyncEventType.ITEM_UPDATED,
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

    public void markLent(String groupId, String lender, String borrower, LendingEntry entry, long dueTime)
    {
        entry.setGroupId(groupId);
        entry.setLender(lender);
        entry.setBorrower(borrower);
        entry.setDueTime(dueTime);
        entry.setLendTime(System.currentTimeMillis());
        groupLent
            .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(lender, k -> new ArrayList<>())
            .add(new LendingEntry(entry));
        persist(groupId, "lent");

        // Publish sync event for real-time updates
        if (groupSyncService != null)
        {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_BORROWED,
                entry.getId() != null ? entry.getId() : String.valueOf(entry.getItemId()),
                entry
            );
        }
    }

    public void markBorrowed(String groupId, String borrower, LendingEntry entry)
    {
        entry.setGroupId(groupId);
        entry.setBorrower(borrower);
        groupBorrowed
            .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(borrower, k -> new ArrayList<>())
            .add(new LendingEntry(entry));
        persist(groupId, "borrowed");

        // Publish sync event for real-time updates
        if (groupSyncService != null)
        {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_BORROWED,
                entry.getId() != null ? entry.getId() : String.valueOf(entry.getItemId()),
                entry
            );
        }
    }

    /**
     * ADDED: Restore a borrowed entry from sync data - does NOT add if entry already exists
     * This is used by LocalDataSyncService to prevent duplication on login
     */
    public void restoreBorrowed(String groupId, String borrower, LendingEntry entry)
    {
        entry.setGroupId(groupId);
        entry.setBorrower(borrower);

        List<LendingEntry> borrowerList = groupBorrowed
            .computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(borrower, k -> new ArrayList<>());

        // Check if entry already exists by ID or by item+borrower combo
        boolean alreadyExists = borrowerList.stream()
            .anyMatch(e ->
                (entry.getId() != null && entry.getId().equals(e.getId())) ||
                (e.getItemId() == entry.getItemId() && Objects.equals(e.getBorrower(), entry.getBorrower()))
            );

        if (alreadyExists)
        {
            log.debug("Skipping restore of borrowed {} - already exists for borrower {}", entry.getItem(), borrower);
            return;
        }

        borrowerList.add(new LendingEntry(entry));
        persist(groupId, "borrowed");
        log.debug("Restored borrowed item: {} for borrower {}", entry.getItem(), borrower);
    }

    /**
     * ADDED: Restore a lent entry from sync data - does NOT add if entry already exists
     * This is used by LocalDataSyncService to prevent duplication on login
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

        // Check if entry already exists by ID or by item+lender+borrower combo
        boolean alreadyExists = lenderList.stream()
            .anyMatch(e ->
                (entry.getId() != null && entry.getId().equals(e.getId())) ||
                (e.getItemId() == entry.getItemId() &&
                 Objects.equals(e.getLender(), entry.getLender()) &&
                 Objects.equals(e.getBorrower(), entry.getBorrower()))
            );

        if (alreadyExists)
        {
            log.debug("Skipping restore of lent {} - already exists for lender {}", entry.getItem(), lender);
            return;
        }

        lenderList.add(new LendingEntry(entry));
        persist(groupId, "lent");
        log.debug("Restored lent item: {} from {} to {}", entry.getItem(), lender, borrower);
    }

    public void markReturned(String groupId, String account, String name, String item, long returnTime)
    {
        Map<String, List<LendingEntry>> accountMap = groupBorrowed.getOrDefault(groupId, Collections.emptyMap());
        for (String acc : accountMap.keySet())
        {
            List<LendingEntry> entries = accountMap.get(acc);
            if (entries == null) continue;
            for (LendingEntry e : entries)
            {
                if (name.equals(e.getLender()) && item.equals(e.getItem()) && e.getReturnedAt() == 0)
                {
                    e.setReturnedAt(returnTime);
                    break;
                }
            }
        }
        persist(groupId, "borrowed");
        // FIXED: Publish sync event for item return
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_RETURNED,
                groupId + ":" + name + ":" + item,
                null
            );
        }
    }

    /**
     * Confirm return from one party. Both lender and borrower must confirm before the item is fully returned.
     * @param groupId The group ID
     * @param lenderName The lender's name
     * @param borrowerName The borrower's name
     * @param item The item name
     * @param isLender True if the lender is confirming, false if borrower is confirming
     * @return True if both parties have now confirmed (item fully returned), false if waiting for other party
     */
    public boolean confirmReturn(String groupId, String lenderName, String borrowerName, String item, boolean isLender)
    {
        // Find the entry in both lent and borrowed maps
        LendingEntry foundEntry = null;

        // Check borrowed map
        Map<String, List<LendingEntry>> borrowedMap = groupBorrowed.getOrDefault(groupId, Collections.emptyMap());
        for (List<LendingEntry> entries : borrowedMap.values())
        {
            if (entries == null) continue;
            for (LendingEntry e : entries)
            {
                if (lenderName.equals(e.getLender()) && borrowerName.equals(e.getBorrower())
                    && item.equals(e.getItem()) && e.getReturnedAt() == 0)
                {
                    foundEntry = e;
                    break;
                }
            }
            if (foundEntry != null) break;
        }

        // Also check lent map
        Map<String, List<LendingEntry>> lentMap = groupLent.getOrDefault(groupId, Collections.emptyMap());
        for (List<LendingEntry> entries : lentMap.values())
        {
            if (entries == null) continue;
            for (LendingEntry e : entries)
            {
                if (lenderName.equals(e.getLender()) && borrowerName.equals(e.getBorrower())
                    && item.equals(e.getItem()) && e.getReturnedAt() == 0)
                {
                    // Update the same entry reference if not already found
                    if (foundEntry == null) {
                        foundEntry = e;
                    } else {
                        // Sync confirmation status between both maps
                        e.setLenderConfirmedReturn(foundEntry.isLenderConfirmedReturn());
                        e.setBorrowerConfirmedReturn(foundEntry.isBorrowerConfirmedReturn());
                    }
                    break;
                }
            }
            if (foundEntry != null) break;
        }

        if (foundEntry == null)
        {
            log.warn("Entry not found for confirmation: {} -> {} ({})", lenderName, borrowerName, item);
            return false;
        }

        // Mark confirmation from the appropriate party
        if (isLender) {
            foundEntry.setLenderConfirmedReturn(true);
        } else {
            foundEntry.setBorrowerConfirmedReturn(true);
        }

        // Check if both parties have confirmed
        boolean fullyConfirmed = foundEntry.isBothPartiesConfirmedReturn();

        if (fullyConfirmed)
        {
            // Both confirmed - mark as returned
            long returnTime = System.currentTimeMillis();
            foundEntry.setReturnedAt(returnTime);
            log.debug("Item fully returned (both confirmed): {} from {} to {}", item, lenderName, borrowerName);

            // Publish sync event for real-time updates when fully returned
            if (groupSyncService != null)
            {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.ITEM_RETURNED,
                    foundEntry.getId() != null ? foundEntry.getId() : item,
                    foundEntry
                );
            }
        }
        else
        {
            log.debug("Partial return confirmation for {}: lender={}, borrower={}",
                item, foundEntry.isLenderConfirmedReturn(), foundEntry.isBorrowerConfirmedReturn());
        }

        // Persist both maps
        persist(groupId, "borrowed");
        persist(groupId, "lent");

        return fullyConfirmed;
    }


    private void persist(String groupId, String kind)
    {
        // Persist a summary JSON for the group; you can expand this to include all maps if you want.
        Map<String, Object> snapshot = new LinkedHashMap<>();
        // FIXED: Use new HashMap() instead of Collections.emptyMap() to avoid Gson reflection issues in Java 11+
        snapshot.put("lent", groupLent.getOrDefault(groupId, new HashMap<>()));
        snapshot.put("borrowed", groupBorrowed.getOrDefault(groupId, new HashMap<>()));
        snapshot.put("available", groupAvailable.getOrDefault(groupId, new HashMap<>()));
        String json = gson.toJson(snapshot);
        configManager.setConfiguration("lendingtracker", KEY_PREFIX + groupId, json);
        log.debug("Persisted {} data for group {}", kind, groupId);
    }

    /**
     * ADDED: Load group-specific data from storage
     * Called when switching groups to restore previously saved items
     */
    public void loadGroupData(String groupId)
    {
        if (groupId == null || groupId.isEmpty())
        {
            log.debug("Cannot load group data - groupId is null or empty");
            return;
        }

        log.info("Loading recorder data for group: {}", groupId);
        String json = configManager.getConfiguration("lendingtracker", KEY_PREFIX + groupId);

        if (json != null && !json.isEmpty())
        {
            try
            {
                @SuppressWarnings("unchecked")
                Map<String, Map<String, List<Map<String, Object>>>> snapshot = gson.fromJson(json, Map.class);

                // Load available items
                Map<String, List<Map<String, Object>>> availableData = snapshot.get("available");
                if (availableData != null && !availableData.isEmpty())
                {
                    Map<String, List<LendingEntry>> availableMap = new ConcurrentHashMap<>();
                    for (Map.Entry<String, List<Map<String, Object>>> ownerEntry : availableData.entrySet())
                    {
                        String owner = ownerEntry.getKey();
                        List<LendingEntry> entries = new ArrayList<>();
                        for (Map<String, Object> entryData : ownerEntry.getValue())
                        {
                            // Convert map to LendingEntry
                            String entryJson = gson.toJson(entryData);
                            LendingEntry entry = gson.fromJson(entryJson, LendingEntry.class);
                            if (entry != null)
                            {
                                entries.add(entry);
                            }
                        }
                        if (!entries.isEmpty())
                        {
                            availableMap.put(owner, entries);
                        }
                    }
                    groupAvailable.put(groupId, availableMap);
                    int totalItems = availableMap.values().stream().mapToInt(List::size).sum();
                    log.info("Loaded {} available items for group {} ({} owners)", totalItems, groupId, availableMap.size());
                }

                // Load lent items
                Map<String, List<Map<String, Object>>> lentData = snapshot.get("lent");
                if (lentData != null && !lentData.isEmpty())
                {
                    Map<String, List<LendingEntry>> lentMap = new ConcurrentHashMap<>();
                    for (Map.Entry<String, List<Map<String, Object>>> entry : lentData.entrySet())
                    {
                        List<LendingEntry> entries = new ArrayList<>();
                        for (Map<String, Object> entryData : entry.getValue())
                        {
                            String entryJson = gson.toJson(entryData);
                            LendingEntry e = gson.fromJson(entryJson, LendingEntry.class);
                            if (e != null) entries.add(e);
                        }
                        if (!entries.isEmpty()) lentMap.put(entry.getKey(), entries);
                    }
                    groupLent.put(groupId, lentMap);
                    log.debug("Loaded lent items for group {}", groupId);
                }

                // Load borrowed items
                Map<String, List<Map<String, Object>>> borrowedData = snapshot.get("borrowed");
                if (borrowedData != null && !borrowedData.isEmpty())
                {
                    Map<String, List<LendingEntry>> borrowedMap = new ConcurrentHashMap<>();
                    for (Map.Entry<String, List<Map<String, Object>>> entry : borrowedData.entrySet())
                    {
                        List<LendingEntry> entries = new ArrayList<>();
                        for (Map<String, Object> entryData : entry.getValue())
                        {
                            String entryJson = gson.toJson(entryData);
                            LendingEntry e = gson.fromJson(entryJson, LendingEntry.class);
                            if (e != null) entries.add(e);
                        }
                        if (!entries.isEmpty()) borrowedMap.put(entry.getKey(), entries);
                    }
                    groupBorrowed.put(groupId, borrowedMap);
                    log.debug("Loaded borrowed items for group {}", groupId);
                }
            }
            catch (Exception e)
            {
                log.error("Failed to load group data for {}: {}", groupId, e.getMessage(), e);
            }
        }
        else
        {
            log.info("No stored data found for group: {}", groupId);
        }
    }

    // -------- Methods required by LendingTrackerPlugin --------
    
    public void initialize() {
        if (initialized) {
            return;
        }
        
        loadEntries();
        initialized = true;
        log.debug("Recorder initialized");
    }
    
    public void addEntry(LendingEntry entry) {
        if (entry == null || entry.getId() == null) {
            return;
        }
        
        allEntries.put(entry.getId(), new LendingEntry(entry));
        saveEntries();
        // FIXED: Publish sync event for new entry
        if (groupSyncService != null && entry.getGroupId() != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_ADDED,
                entry.getId(),
                entry
            );
        }
        log.debug("Added entry: {}", entry.getId());
    }
    
    public List<LendingEntry> getActiveEntries() {
        return allEntries.values().stream()
            .filter(entry -> !entry.isReturned())
            .collect(Collectors.toList());
    }
    
    public List<LendingEntry> getHistoryEntries() {
        return new ArrayList<>(historyEntries);
    }

    /**
     * ADDED: Remove history entries older than the specified timestamp
     * @return number of entries removed
     */
    public int removeOldHistoryEntries(long olderThanMs) {
        int removed = 0;
        java.util.Iterator<LendingEntry> it = historyEntries.iterator();
        while (it.hasNext()) {
            LendingEntry entry = it.next();
            if (entry.getReturnedAt() > 0 && entry.getReturnedAt() < olderThanMs) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            saveEntries();
        }
        return removed;
    }

    public List<LendingEntry> getEntriesForPlayer(String playerName) {
        if (playerName == null) {
            return new ArrayList<>();
        }
        
        return allEntries.values().stream()
            .filter(entry -> playerName.equalsIgnoreCase(entry.getPlayerName()) || 
                           playerName.equalsIgnoreCase(entry.getBorrower()) ||
                           playerName.equalsIgnoreCase(entry.getLender()))
            .collect(Collectors.toList());
    }
    
    public List<LendingEntry> getOverdueEntries() {
        long currentTime = Instant.now().toEpochMilli();
        return allEntries.values().stream()
            .filter(entry -> !entry.isReturned() && entry.getDueDate() > 0 && entry.getDueDate() < currentTime)
            .collect(Collectors.toList());
    }
    
    public void completeEntry(String entryId, boolean returned) {
        LendingEntry entry = allEntries.get(entryId);
        if (entry != null) {
            if (returned) {
                entry.setReturnedAt(Instant.now().toEpochMilli());
            }
            // Move to history
            historyEntries.add(new LendingEntry(entry));
            if (returned) {
                allEntries.remove(entryId);
            }
            saveEntries();
            // FIXED: Publish sync event for completed entry
            if (groupSyncService != null && entry.getGroupId() != null) {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.ITEM_RETURNED,
                    entryId,
                    entry
                );
            }
            log.debug("Completed entry: {} (returned: {})", entryId, returned);
        }
    }
    
    public void removeEntry(String entryId) {
        LendingEntry removed = allEntries.remove(entryId);
        if (removed != null) {
            saveEntries();
            log.debug("Removed entry: {}", entryId);
            // FIXED: Publish sync event for removed entry
            if (groupSyncService != null && removed.getGroupId() != null) {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.ITEM_REMOVED,
                    entryId,
                    removed
                );
            }
        }
    }
    
    public long getTotalValueLent() {
        return getActiveEntries().stream()
            .mapToLong(LendingEntry::getValue)
            .sum();
    }
    
    public boolean exportData() {
        try {
            File exportFile = new File(RuneLite.RUNELITE_DIR, "lending_tracker_export.json");
            Map<String, Object> exportData = new HashMap<>();
            exportData.put("active", getActiveEntries());
            exportData.put("history", getHistoryEntries());
            exportData.put("timestamp", Instant.now().toEpochMilli());
            
            try (FileWriter writer = new FileWriter(exportFile)) {
                gson.toJson(exportData, writer);
            }
            
            log.info("Data exported to: {}", exportFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("Failed to export data", e);
            return false;
        }
    }
    
    // Additional methods needed by the panel and services
    
    public void addToAvailableList(LendingEntry entry, String groupId) {
        addAvailable(groupId, entry.getLender(), entry);
    }
    
    public void markAsBorrowed(String groupId, String playerName, LendingEntry entry) {
        markBorrowed(groupId, playerName, entry);
    }
    
    public void markAsLent(String groupId, String lender, String borrower, LendingEntry entry, long dueTime) {
        markLent(groupId, lender, borrower, entry, dueTime);
    }
    
    
    public List<LendingEntry> getLent(String groupId) {
        Map<String, List<LendingEntry>> groupData = groupLent.get(groupId);
        if (groupData == null) {
            return new ArrayList<>();
        }
        return groupData.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }
    
    public List<LendingEntry> getBorrowed(String groupId) {
        Map<String, List<LendingEntry>> groupData = groupBorrowed.get(groupId);
        if (groupData == null) {
            return new ArrayList<>();
        }
        return groupData.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }
    
    public List<LendingEntry> getAvailable(String groupId) {
        Map<String, List<LendingEntry>> groupData = groupAvailable.get(groupId);
        if (groupData == null) {
            return new ArrayList<>();
        }
        return groupData.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }
    
    
    public long getTotalValueLentToPlayer(String playerName) {
        if (playerName == null) {
            return 0;
        }
        return getEntriesForPlayer(playerName).stream()
            .filter(entry -> !entry.isReturned())
            .mapToLong(LendingEntry::getValue)
            .sum();
    }
    
    public void exportData(File file, String format, boolean includeHistory, String groupId) {
        // Legacy method signature - delegates to simpler version
        exportData();
    }
    
    public void recordReturn(String groupId, String playerName, String itemName) {
        markReturned(groupId, "", playerName, itemName, Instant.now().toEpochMilli());
    }
    
    public void clearOldWarnings(int days) {
        // Stub implementation - warning system not fully implemented
        log.debug("Clearing warnings older than {} days", days);
    }
    
    public void exportWarningLog(File file, String format) {
        // Stub implementation - warning system not fully implemented
        log.debug("Exporting warning log to file: {}", file.getName());
    }
    
    public List<String> getAccounts() {
        // Return unique account names from all groups
        Set<String> accounts = new HashSet<>();
        
        // Add current logged-in player if available
        if (client != null && client.getLocalPlayer() != null) {
            accounts.add(client.getLocalPlayer().getName());
        }
        
        // Add accounts from lending data
        for (LendingEntry entry : allEntries.values()) {
            if (entry.getLender() != null && !entry.getLender().isEmpty()) {
                accounts.add(entry.getLender());
            }
            if (entry.getBorrower() != null && !entry.getBorrower().isEmpty()) {
                accounts.add(entry.getBorrower());
            }
        }
        
        // Add from group data as well
        for (Map<String, List<LendingEntry>> groupData : groupLent.values()) {
            accounts.addAll(groupData.keySet());
        }
        for (Map<String, List<LendingEntry>> groupData : groupBorrowed.values()) {
            accounts.addAll(groupData.keySet());
        }
        for (Map<String, List<LendingEntry>> groupData : groupAvailable.values()) {
            accounts.addAll(groupData.keySet());
        }
        
        // If no accounts found, add a default
        if (accounts.isEmpty()) {
            accounts.add("Default Account");
        }
        
        return new ArrayList<>(accounts);
    }
    
    public List<LendingEntry> getAvailableList(String groupId) {
        return getAvailable(groupId);
    }
    
    public List<WarningLogEntry> getWarningLog(String groupId) {
        // Stub implementation - warning system not fully implemented
        return new ArrayList<>();
    }
    
    public List<LendingEntry> getHistory(String groupId) {
        // Return historical entries for the group
        return getHistoryEntries().stream()
            .filter(entry -> groupId.equals(entry.getGroupId()))
            .collect(Collectors.toList());
    }
    
    
    public boolean importData(String jsonData) {
        try {
            Type type = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> importData = gson.fromJson(jsonData, type);
            
            if (importData.containsKey("active")) {
                Type listType = new TypeToken<List<LendingEntry>>(){}.getType();
                List<LendingEntry> activeEntries = gson.fromJson(
                    gson.toJson(importData.get("active")), listType);
                
                for (LendingEntry entry : activeEntries) {
                    addEntry(entry);
                }
            }
            
            if (importData.containsKey("history")) {
                Type listType = new TypeToken<List<LendingEntry>>(){}.getType();
                List<LendingEntry> histEntries = gson.fromJson(
                    gson.toJson(importData.get("history")), listType);
                
                historyEntries.addAll(histEntries);
            }
            
            saveEntries();
            log.info("Data imported successfully");
            return true;
        } catch (Exception e) {
            log.error("Failed to import data", e);
            return false;
        }
    }
    
    private void loadEntries() {
        try {
            String entriesJson = configManager.getConfiguration("lendingtracker", ENTRIES_KEY);
            if (entriesJson != null) {
                Type type = new TypeToken<Map<String, LendingEntry>>(){}.getType();
                Map<String, LendingEntry> loaded = gson.fromJson(entriesJson, type);
                if (loaded != null) {
                    allEntries.putAll(loaded);
                }
            }
            
            String historyJson = configManager.getConfiguration("lendingtracker", HISTORY_KEY);
            if (historyJson != null) {
                Type type = new TypeToken<List<LendingEntry>>(){}.getType();
                List<LendingEntry> loaded = gson.fromJson(historyJson, type);
                if (loaded != null) {
                    historyEntries.addAll(loaded);
                }
            }
        } catch (Exception e) {
            log.error("Failed to load entries", e);
        }
    }
    
    private void saveEntries() {
        try {
            configManager.setConfiguration("lendingtracker", ENTRIES_KEY, gson.toJson(allEntries));
            configManager.setConfiguration("lendingtracker", HISTORY_KEY, gson.toJson(historyEntries));
        } catch (Exception e) {
            log.error("Failed to save entries", e);
        }
    }

    // -------- Utility used by panel for rendering collateral in your previous build --------
    public static String formatCollateral(LendingEntry entry)
    {
        if (entry == null) return "None";
        Integer v = entry.getCollateralValue();
        if (v != null && v > 0) {
            return v + " GP";
        } else if (entry.getCollateralItems() != null && !entry.getCollateralItems().isEmpty()) {
            return "Items: " + entry.getCollateralItems();
        } else if (entry.isAgreedNoCollateral()) {
            return "None (Agreed)";
        }
        return "None";
    }

    /**
     * IMPLEMENTED: dataRetentionDays - Delete old returned entries
     * @param cutoffTime Timestamp before which entries should be deleted
     * @return Number of entries deleted
     */
    public int deleteOldReturnedEntries(long cutoffTime)
    {
        int deletedCount = 0;

        // Clean lent entries
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

        // Clean borrowed entries
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

        // Persist changes
        if (deletedCount > 0) {
            for (String groupId : groupLent.keySet()) {
                persist(groupId, "lent");
            }
            for (String groupId : groupBorrowed.keySet()) {
                persist(groupId, "borrowed");
            }
        }

        return deletedCount;
    }

    /**
     * Extend the due date for a loan by adding additional days.
     * Updates both lender and borrower records and persists the change.
     *
     * @param groupId Group ID containing the loan
     * @param lender Lender's player name
     * @param borrower Borrower's player name
     * @param item Item name
     * @param additionalDays Number of days to add to due date
     */
    public void extendLoan(String groupId, String lender, String borrower, String item, int additionalDays)
    {
        if (groupId == null || lender == null || borrower == null || item == null)
        {
            log.warn("Cannot extend loan: null parameter provided");
            return;
        }

        // Find entry in lent map
        Map<String, List<LendingEntry>> lentAccounts = groupLent.get(groupId);
        LendingEntry lentEntry = null;
        if (lentAccounts != null)
        {
            List<LendingEntry> lentList = lentAccounts.get(lender);
            if (lentList != null)
            {
                lentEntry = lentList.stream()
                    .filter(e -> borrower.equals(e.getBorrower()) && item.equals(e.getItem()))
                    .findFirst()
                    .orElse(null);
            }
        }

        // Find entry in borrowed map
        Map<String, List<LendingEntry>> borrowedAccounts = groupBorrowed.get(groupId);
        LendingEntry borrowedEntry = null;
        if (borrowedAccounts != null)
        {
            List<LendingEntry> borrowedList = borrowedAccounts.get(borrower);
            if (borrowedList != null)
            {
                borrowedEntry = borrowedList.stream()
                    .filter(e -> lender.equals(e.getLender()) && item.equals(e.getItem()))
                    .findFirst()
                    .orElse(null);
            }
        }

        if (lentEntry == null && borrowedEntry == null)
        {
            log.warn("Cannot extend loan: entry not found for {} from {} to {}", item, lender, borrower);
            return;
        }

        // Calculate new due date
        long additionalMillis = additionalDays * 24L * 60L * 60L * 1000L;

        // Update lent entry
        if (lentEntry != null)
        {
            long currentDue = lentEntry.getDueDate();
            long newDue = currentDue + additionalMillis;
            lentEntry.setDueTime(newDue);
            log.info("Extended loan (lent): {} from {} to {} by {} days (new due: {})",
                item, lender, borrower, additionalDays, new Date(newDue));
        }

        // Update borrowed entry
        if (borrowedEntry != null)
        {
            long currentDue = borrowedEntry.getDueDate();
            long newDue = currentDue + additionalMillis;
            borrowedEntry.setDueTime(newDue);
            log.info("Extended loan (borrowed): {} from {} to {} by {} days (new due: {})",
                item, lender, borrower, additionalDays, new Date(newDue));
        }

        // Persist changes
        persist(groupId, "lent");
        persist(groupId, "borrowed");
        // FIXED: Publish sync event for loan extension
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_UPDATED,
                groupId + ":" + lender + ":" + item,
                null
            );
        }
    }

    /**
     * ADDED: Clear all data for a specific group and delete from storage
     * Used when deleting a group to remove all associated lending data
     */
    public void clearGroupData(String groupId)
    {
        if (groupId == null || groupId.isEmpty())
        {
            log.debug("Cannot clear group data - groupId is null or empty");
            return;
        }

        log.info("Clearing all recorder data for group: {}", groupId);

        // Remove from in-memory maps
        groupLent.remove(groupId);
        groupBorrowed.remove(groupId);
        groupAvailable.remove(groupId);

        // Delete from config storage
        configManager.unsetConfiguration("lendingtracker", KEY_PREFIX + groupId);

        log.info("Cleared all lending data for group: {}", groupId);
        // FIXED: Publish sync event for group data clear
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_REMOVED,
                groupId,
                null
            );
        }
    }

    /**
     * ADDED: Remove all items for a specific lender in a group
     * Used when a member is kicked from the group
     */
    public void removeItemsForLender(String groupId, String lenderName)
    {
        if (groupId == null || lenderName == null)
        {
            log.debug("Cannot remove items - groupId or lenderName is null");
            return;
        }

        Map<String, List<LendingEntry>> groupData = groupAvailable.get(groupId);
        if (groupData != null)
        {
            List<LendingEntry> removed = groupData.remove(lenderName);
            if (removed != null && !removed.isEmpty())
            {
                persist(groupId, "available");
                log.info("Removed {} items for lender: {} from group: {}", removed.size(), lenderName, groupId);
                // FIXED: Publish sync event for removed lender items
                if (groupSyncService != null) {
                    groupSyncService.publishEvent(
                        GroupSyncService.SyncEventType.ITEM_REMOVED,
                        groupId + ":" + lenderName,
                        null
                    );
                }
            }
        }
    }

    /**
     * Log a return request from the borrower to the lender.
     * This creates a record that the borrower wants to return an item.
     *
     * @param groupId Group ID containing the loan
     * @param lender Lender's player name
     * @param borrower Borrower's player name
     * @param item Item name
     * @param message Optional message from borrower
     */
    public void logReturnRequest(String groupId, String lender, String borrower, String item, String message)
    {
        if (groupId == null || lender == null || borrower == null || item == null)
        {
            log.warn("Cannot log return request: null parameter provided");
            return;
        }

        log.info("Return request logged: {} from {} to {} in group {} - Message: '{}'",
            item, borrower, lender, groupId, message != null ? message : "none");

        // Note: This is logged for informational purposes. In a full implementation,
        // this could be stored in a separate returnRequests map and displayed in UI.
        // For now, the log entry provides audit trail of return requests.
    }
}