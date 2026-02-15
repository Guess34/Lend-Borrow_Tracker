package com.guess34.lendingtracker.services.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.model.ItemSet;
import com.guess34.lendingtracker.services.sync.GroupSyncService;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ItemSetManager - Manages item sets (bundled items for lending)
 * Allows users to create, edit, and lend/borrow item sets as a package
 */
@Slf4j
@Singleton
public class ItemSetManager {

    private static final String CONFIG_GROUP = "lendingtracker";
    private static final String CONFIG_KEY_PREFIX = "itemsets.";

    @Inject
    private ConfigManager configManager;

    @Inject
    private GroupSyncService groupSyncService;

    @Inject
    private Gson gson;

    // Group-specific item sets: groupId -> setId -> ItemSet
    private final Map<String, Map<String, ItemSet>> groupItemSets = new ConcurrentHashMap<>();

    /**
     * Initialize the manager
     */
    public void initialize() {
        log.info("ItemSetManager initialized");
    }

    /**
     * Load item sets for a specific group
     */
    public void loadGroupData(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            log.debug("Cannot load item sets - groupId is null or empty");
            return;
        }

        log.info("Loading item sets for group: {}", groupId);
        String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + groupId);

        if (json != null && !json.isEmpty()) {
            try {
                Type type = new TypeToken<List<ItemSet>>(){}.getType();
                List<ItemSet> sets = gson.fromJson(json, type);

                if (sets != null && !sets.isEmpty()) {
                    Map<String, ItemSet> setMap = new ConcurrentHashMap<>();
                    for (ItemSet set : sets) {
                        setMap.put(set.getId(), set);
                    }
                    groupItemSets.put(groupId, setMap);
                    log.info("Loaded {} item sets for group {}", sets.size(), groupId);
                }
            } catch (Exception e) {
                log.error("Failed to load item sets for group {}: {}", groupId, e.getMessage());
            }
        } else {
            log.debug("No item sets found for group: {}", groupId);
        }
    }

    /**
     * Save item sets for a specific group
     */
    private void saveGroupData(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            log.warn("Cannot save item sets - groupId is null or empty");
            return;
        }

        Map<String, ItemSet> sets = groupItemSets.get(groupId);
        List<ItemSet> setList = sets != null ? new ArrayList<>(sets.values()) : new ArrayList<>();

        String json = gson.toJson(setList);
        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + groupId, json);
        log.debug("Saved {} item sets for group {}", setList.size(), groupId);
    }

    /**
     * Create a new item set
     */
    public ItemSet createItemSet(String groupId, String name, String ownerName, String description) {
        if (groupId == null || name == null || ownerName == null) {
            throw new IllegalArgumentException("groupId, name, and ownerName are required");
        }

        ItemSet set = new ItemSet(name, ownerName, groupId);
        set.setDescription(description);

        groupItemSets.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
            .put(set.getId(), set);

        saveGroupData(groupId);
        log.info("Created item set '{}' (ID: {}) for owner {} in group {}", name, set.getId(), ownerName, groupId);

        // Publish sync event for real-time updates
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_SET_CREATED,
                set.getId(),
                set
            );
        }

        return set;
    }

    /**
     * Get an item set by ID
     */
    public ItemSet getItemSet(String groupId, String setId) {
        Map<String, ItemSet> sets = groupItemSets.get(groupId);
        return sets != null ? sets.get(setId) : null;
    }

    /**
     * Get all item sets for a group
     */
    public List<ItemSet> getItemSetsForGroup(String groupId) {
        Map<String, ItemSet> sets = groupItemSets.get(groupId);
        if (sets == null || sets.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(sets.values());
    }

    /**
     * Get available item sets for a group (not currently lent out)
     */
    public List<ItemSet> getAvailableItemSets(String groupId) {
        return getItemSetsForGroup(groupId).stream()
            .filter(ItemSet::isAvailable)
            .collect(Collectors.toList());
    }

    /**
     * Get item sets owned by a specific player
     */
    public List<ItemSet> getItemSetsByOwner(String groupId, String ownerName) {
        return getItemSetsForGroup(groupId).stream()
            .filter(set -> ownerName.equalsIgnoreCase(set.getOwnerName()))
            .collect(Collectors.toList());
    }

    /**
     * Get item sets currently borrowed by a player
     */
    public List<ItemSet> getItemSetsBorrowedBy(String groupId, String borrowerName) {
        return getItemSetsForGroup(groupId).stream()
            .filter(set -> borrowerName.equalsIgnoreCase(set.getCurrentBorrower()))
            .collect(Collectors.toList());
    }

    /**
     * Add an item to an existing set
     */
    public boolean addItemToSet(String groupId, String setId, int itemId, String itemName, int quantity, long value) {
        ItemSet set = getItemSet(groupId, setId);
        if (set == null) {
            log.warn("Cannot add item - set {} not found in group {}", setId, groupId);
            return false;
        }

        if (set.isLentOut()) {
            log.warn("Cannot modify set {} - it is currently lent out", setId);
            return false;
        }

        ItemSet.ItemSetEntry entry = new ItemSet.ItemSetEntry(itemId, itemName, quantity);
        entry.setValue(value);
        set.addItem(entry);

        saveGroupData(groupId);
        log.info("Added {} x {} to set '{}' in group {}", quantity, itemName, set.getName(), groupId);

        // FIXED: Publish sync event for item set update
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_SET_UPDATED,
                setId,
                set
            );
        }

        return true;
    }

    /**
     * Remove an item from a set
     */
    public boolean removeItemFromSet(String groupId, String setId, int itemId) {
        ItemSet set = getItemSet(groupId, setId);
        if (set == null) {
            log.warn("Cannot remove item - set {} not found in group {}", setId, groupId);
            return false;
        }

        if (set.isLentOut()) {
            log.warn("Cannot modify set {} - it is currently lent out", setId);
            return false;
        }

        boolean removed = set.removeItem(itemId);
        if (removed) {
            saveGroupData(groupId);
            log.info("Removed item {} from set '{}' in group {}", itemId, set.getName(), groupId);

            // FIXED: Publish sync event for item set update
            if (groupSyncService != null) {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.ITEM_SET_UPDATED,
                    setId,
                    set
                );
            }
        }

        return removed;
    }

    /**
     * Update item set details
     */
    public boolean updateItemSet(String groupId, String setId, String name, String description,
                                  Integer collateralValue, String collateralType, int defaultDurationDays) {
        ItemSet set = getItemSet(groupId, setId);
        if (set == null) {
            log.warn("Cannot update - set {} not found in group {}", setId, groupId);
            return false;
        }

        if (name != null && !name.isEmpty()) {
            set.setName(name);
        }
        if (description != null) {
            set.setDescription(description);
        }
        if (collateralValue != null) {
            set.setCollateralValue(collateralValue);
        }
        if (collateralType != null) {
            set.setCollateralType(collateralType);
        }
        if (defaultDurationDays > 0) {
            set.setDefaultDurationDays(defaultDurationDays);
        }
        set.setUpdatedAt(System.currentTimeMillis());

        saveGroupData(groupId);
        log.info("Updated item set '{}' in group {}", set.getName(), groupId);

        // Publish sync event for real-time updates
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_SET_UPDATED,
                set.getId(),
                set
            );
        }

        return true;
    }

    /**
     * Delete an item set
     */
    public boolean deleteItemSet(String groupId, String setId) {
        Map<String, ItemSet> sets = groupItemSets.get(groupId);
        if (sets == null) {
            return false;
        }

        ItemSet removed = sets.remove(setId);
        if (removed != null) {
            if (removed.isLentOut()) {
                log.warn("Deleted item set '{}' that was still lent to {}", removed.getName(), removed.getCurrentBorrower());
            }
            saveGroupData(groupId);
            log.info("Deleted item set '{}' from group {}", removed.getName(), groupId);

            // Publish sync event for real-time updates
            if (groupSyncService != null) {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.ITEM_SET_DELETED,
                    setId,
                    removed
                );
            }

            return true;
        }

        return false;
    }

    /**
     * Lend an item set to a borrower
     */
    public boolean lendItemSet(String groupId, String setId, String borrowerName, long dueTime) {
        ItemSet set = getItemSet(groupId, setId);
        if (set == null) {
            log.warn("Cannot lend - set {} not found in group {}", setId, groupId);
            return false;
        }

        if (set.isLentOut()) {
            log.warn("Cannot lend set '{}' - already lent to {}", set.getName(), set.getCurrentBorrower());
            return false;
        }

        set.lendTo(borrowerName, dueTime);
        saveGroupData(groupId);
        log.info("Lent item set '{}' ({} items, {} GP total) to {} in group {}",
            set.getName(), set.getItemCount(), set.getTotalValue(), borrowerName, groupId);

        // FIXED: Publish sync event for item set borrowed
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_SET_UPDATED,
                setId,
                set
            );
        }

        return true;
    }

    /**
     * Return an item set
     */
    public boolean returnItemSet(String groupId, String setId) {
        ItemSet set = getItemSet(groupId, setId);
        if (set == null) {
            log.warn("Cannot return - set {} not found in group {}", setId, groupId);
            return false;
        }

        if (!set.isLentOut()) {
            log.warn("Cannot return set '{}' - it is not currently lent out", set.getName());
            return false;
        }

        String previousBorrower = set.getCurrentBorrower();
        set.markReturned();
        saveGroupData(groupId);
        log.info("Item set '{}' returned by {} in group {}", set.getName(), previousBorrower, groupId);

        // FIXED: Publish sync event for item set returned
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_SET_UPDATED,
                setId,
                set
            );
        }

        return true;
    }

    /**
     * Get overdue item sets for a group
     */
    public List<ItemSet> getOverdueItemSets(String groupId) {
        return getItemSetsForGroup(groupId).stream()
            .filter(ItemSet::isOverdue)
            .collect(Collectors.toList());
    }

    /**
     * Get total value of all item sets in a group
     */
    public long getTotalValueForGroup(String groupId) {
        return getItemSetsForGroup(groupId).stream()
            .mapToLong(ItemSet::getTotalValue)
            .sum();
    }

    /**
     * Get total value of item sets currently lent out in a group
     */
    public long getTotalLentValueForGroup(String groupId) {
        return getItemSetsForGroup(groupId).stream()
            .filter(ItemSet::isLentOut)
            .mapToLong(ItemSet::getTotalValue)
            .sum();
    }

    /**
     * ADDED: Clear all item set data for a specific group
     * Used when deleting a group to remove all associated item sets
     */
    public void clearGroupData(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            log.debug("Cannot clear item set data - groupId is null or empty");
            return;
        }

        log.info("Clearing all item set data for group: {}", groupId);

        // Remove from in-memory map
        groupItemSets.remove(groupId);

        // Delete from config storage
        configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_PREFIX + groupId);

        log.info("Cleared all item sets for group: {}", groupId);

        // FIXED: Publish sync event for group data clear
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.ITEM_SET_DELETED,
                groupId,
                null
            );
        }
    }
}
