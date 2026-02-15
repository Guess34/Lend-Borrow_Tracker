package com.guess34.lendingtracker.services.sync;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.ItemSet;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.services.Recorder;
import com.guess34.lendingtracker.services.core.MarketplaceManager;
import com.guess34.lendingtracker.services.core.ItemSetManager;
import com.guess34.lendingtracker.services.group.GroupConfigStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;

/**
 * GroupSyncService - Handles real-time synchronization of group data across members.
 *
 * Sync Strategy:
 * 1. Each change generates a SyncEvent with timestamp and data
 * 2. Events are stored in a shared config key that all group members can read
 * 3. Periodic polling checks for new events from other members
 * 4. When new events are found, local data is updated accordingly
 *
 * This uses RuneLite's config system as a simple pub/sub mechanism.
 * Each group has a "sync queue" in config that members write to and read from.
 */
@Slf4j
@Singleton
public class GroupSyncService {

    private static final String CONFIG_GROUP = "lendingtracker";
    private static final String SYNC_KEY_PREFIX = "sync.";
    private static final String SYNC_EVENTS_SUFFIX = ".events";
    private static final String SYNC_TIMESTAMP_SUFFIX = ".lastSync";

    // Maximum number of sync events to keep in the queue
    private static final int MAX_SYNC_EVENTS = 100;

    // Sync interval in milliseconds
    private static final long SYNC_INTERVAL_MS = 5000; // 5 seconds

    @Inject
    private ConfigManager configManager;

    @Inject
    private Recorder recorder;

    @Inject
    private MarketplaceManager marketplaceManager;

    @Inject
    private ItemSetManager itemSetManager;

    @Inject
    private GroupConfigStore groupConfigStore;

    @Inject
    private Gson gson;
    private ScheduledExecutorService syncExecutor;
    private String currentGroupId;
    private String currentPlayerName;
    private long lastSyncTimestamp = 0;

    // Callback for UI refresh
    private Runnable onSyncCallback;

    /**
     * Initialize the sync service
     */
    public void initialize() {
        log.info("GroupSyncService initialized");
    }

    /**
     * Start syncing for a specific group
     */
    public void startSync(String groupId, String playerName) {
        if (groupId == null || playerName == null) {
            log.warn("Cannot start sync - groupId or playerName is null");
            return;
        }

        // Stop any existing sync
        stopSync();

        this.currentGroupId = groupId;
        this.currentPlayerName = playerName;
        this.lastSyncTimestamp = System.currentTimeMillis();

        // Start periodic sync
        syncExecutor = Executors.newSingleThreadScheduledExecutor();
        syncExecutor.scheduleAtFixedRate(this::pollForUpdates, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("Started sync for group {} as player {}", groupId, playerName);
    }

    /**
     * Stop syncing
     */
    public void stopSync() {
        if (syncExecutor != null && !syncExecutor.isShutdown()) {
            syncExecutor.shutdown();
            try {
                if (!syncExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    syncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                syncExecutor.shutdownNow();
            }
        }
        currentGroupId = null;
        currentPlayerName = null;
        log.info("Stopped sync");
    }

    /**
     * Set callback for when sync updates are received
     */
    public void setOnSyncCallback(Runnable callback) {
        this.onSyncCallback = callback;
    }

    /**
     * Publish an event to the sync queue
     */
    public void publishEvent(SyncEventType type, String dataId, Object data) {
        if (currentGroupId == null || currentPlayerName == null) {
            log.debug("Cannot publish event - not syncing");
            return;
        }

        SyncEvent event = new SyncEvent();
        event.setType(type);
        event.setDataId(dataId);
        event.setData(data);
        event.setTimestamp(System.currentTimeMillis());
        event.setPublisher(currentPlayerName);
        event.setGroupId(currentGroupId);

        // Add to sync queue
        addEventToQueue(event);

        log.info("Published sync event: {} for {} by {}", type, dataId, currentPlayerName);
    }

    /**
     * Sync a single lending entry with the group
     * ADDED: Convenience method used by LendingTrackerPlugin
     */
    public void syncLending(String groupId, LendingEntry entry) {
        if (entry == null) {
            return;
        }
        String previousGroupId = currentGroupId;
        currentGroupId = groupId;
        publishEvent(SyncEventType.ITEM_ADDED, entry.getId(), entry);
        currentGroupId = previousGroupId;
    }

    /**
     * Sync all lending entries with the group
     * ADDED: Convenience method used by LendingTrackerPlugin
     */
    public void syncAllEntries(String groupId, List<LendingEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        String previousGroupId = currentGroupId;
        currentGroupId = groupId;
        for (LendingEntry entry : entries) {
            publishEvent(SyncEventType.ITEM_UPDATED, entry.getId(), entry);
        }
        currentGroupId = previousGroupId;
    }

    /**
     * Add an event to the group's sync queue
     */
    private void addEventToQueue(SyncEvent event) {
        String key = SYNC_KEY_PREFIX + currentGroupId + SYNC_EVENTS_SUFFIX;
        List<SyncEvent> events = loadEventsFromQueue();

        // Add new event
        events.add(event);

        // Trim to max size (keep newest)
        while (events.size() > MAX_SYNC_EVENTS) {
            events.remove(0);
        }

        // Save back
        String json = gson.toJson(events);
        configManager.setConfiguration(CONFIG_GROUP, key, json);
    }

    /**
     * Load events from the sync queue
     */
    private List<SyncEvent> loadEventsFromQueue() {
        if (currentGroupId == null) {
            return new ArrayList<>();
        }

        String key = SYNC_KEY_PREFIX + currentGroupId + SYNC_EVENTS_SUFFIX;
        String json = configManager.getConfiguration(CONFIG_GROUP, key);

        if (json != null && !json.isEmpty()) {
            try {
                Type type = new TypeToken<List<SyncEvent>>(){}.getType();
                List<SyncEvent> events = gson.fromJson(json, type);
                return events != null ? new ArrayList<>(events) : new ArrayList<>();
            } catch (Exception e) {
                log.error("Failed to load sync events: {}", e.getMessage());
            }
        }

        return new ArrayList<>();
    }

    /**
     * Poll for updates from other group members
     */
    private void pollForUpdates() {
        if (currentGroupId == null) {
            return;
        }

        try {
            List<SyncEvent> events = loadEventsFromQueue();
            List<SyncEvent> newEvents = new ArrayList<>();

            // Find events newer than our last sync and not from us
            for (SyncEvent event : events) {
                if (event.getTimestamp() > lastSyncTimestamp &&
                    !currentPlayerName.equalsIgnoreCase(event.getPublisher())) {
                    newEvents.add(event);
                }
            }

            if (!newEvents.isEmpty()) {
                log.info("Found {} new sync events", newEvents.size());

                // Process new events
                for (SyncEvent event : newEvents) {
                    processEvent(event);
                }

                // Update last sync timestamp
                lastSyncTimestamp = System.currentTimeMillis();

                // Notify UI to refresh
                if (onSyncCallback != null) {
                    onSyncCallback.run();
                }
            }
        } catch (Exception e) {
            log.error("Error polling for sync updates: {}", e.getMessage());
        }
    }

    /**
     * Process a sync event from another member
     */
    private void processEvent(SyncEvent event) {
        log.info("Processing sync event: {} from {}", event.getType(), event.getPublisher());

        try {
            switch (event.getType()) {
                case ITEM_ADDED:
                    handleItemAdded(event);
                    break;
                case ITEM_REMOVED:
                    handleItemRemoved(event);
                    break;
                case ITEM_UPDATED:
                    handleItemUpdated(event);
                    break;
                case ITEM_BORROWED:
                    handleItemBorrowed(event);
                    break;
                case ITEM_RETURNED:
                    handleItemReturned(event);
                    break;
                case MEMBER_JOINED:
                    handleMemberJoined(event);
                    break;
                case MEMBER_LEFT:
                    handleMemberLeft(event);
                    break;
                case SETTINGS_CHANGED:
                    handleSettingsChanged(event);
                    break;
                case ITEM_SET_CREATED:
                case ITEM_SET_UPDATED:
                case ITEM_SET_DELETED:
                    handleItemSetEvent(event);
                    break;
                default:
                    log.warn("Unknown sync event type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Error processing sync event {}: {}", event.getType(), e.getMessage());
        }
    }

    private void handleItemAdded(SyncEvent event) {
        // Reload marketplace data for the group
        if (currentGroupId != null) {
            recorder.loadGroupData(currentGroupId);
            marketplaceManager.loadGroupData(currentGroupId);
        }
    }

    private void handleItemRemoved(SyncEvent event) {
        // Reload marketplace data
        if (currentGroupId != null) {
            recorder.loadGroupData(currentGroupId);
            marketplaceManager.loadGroupData(currentGroupId);
        }
    }

    private void handleItemUpdated(SyncEvent event) {
        // Reload marketplace data
        if (currentGroupId != null) {
            recorder.loadGroupData(currentGroupId);
            marketplaceManager.loadGroupData(currentGroupId);
        }
    }

    private void handleItemBorrowed(SyncEvent event) {
        // Reload data
        if (currentGroupId != null) {
            recorder.loadGroupData(currentGroupId);
            marketplaceManager.loadGroupData(currentGroupId);
        }
    }

    private void handleItemReturned(SyncEvent event) {
        // Reload data
        if (currentGroupId != null) {
            recorder.loadGroupData(currentGroupId);
            marketplaceManager.loadGroupData(currentGroupId);
        }
    }

    private void handleMemberJoined(SyncEvent event) {
        // Reload group data
        log.info("Member {} joined group {}", event.getPublisher(), currentGroupId);
    }

    private void handleMemberLeft(SyncEvent event) {
        // Reload group data
        log.info("Member {} left group {}", event.getPublisher(), currentGroupId);
    }

    private void handleSettingsChanged(SyncEvent event) {
        // Reload group settings
        log.info("Group settings changed by {}", event.getPublisher());
    }

    private void handleItemSetEvent(SyncEvent event) {
        // Reload item sets
        if (currentGroupId != null) {
            itemSetManager.loadGroupData(currentGroupId);
        }
    }

    /**
     * Types of sync events
     */
    public enum SyncEventType {
        ITEM_ADDED,
        ITEM_REMOVED,
        ITEM_UPDATED,
        ITEM_BORROWED,
        ITEM_RETURNED,
        MEMBER_JOINED,
        MEMBER_LEFT,
        SETTINGS_CHANGED,
        ITEM_SET_CREATED,
        ITEM_SET_UPDATED,
        ITEM_SET_DELETED
    }

    /**
     * Sync event data class
     */
    public static class SyncEvent {
        private SyncEventType type;
        private String dataId;
        private Object data;
        private long timestamp;
        private String publisher;
        private String groupId;

        // Getters and setters
        public SyncEventType getType() { return type; }
        public void setType(SyncEventType type) { this.type = type; }
        public String getDataId() { return dataId; }
        public void setDataId(String dataId) { this.dataId = dataId; }
        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        public String getPublisher() { return publisher; }
        public void setPublisher(String publisher) { this.publisher = publisher; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
    }
}
