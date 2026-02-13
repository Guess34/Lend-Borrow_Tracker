package net.runelite.client.plugins.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.model.GroupMember;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class LocalDataSyncService {
    
    private static final String BACKUP_DIR = System.getProperty("user.home") + File.separator + ".runelite" + File.separator + "lending-tracker-backups";
    private static final String SYNC_DATA_FILE = "lending-data-sync.json";
    private static final String BACKUP_FILE_PREFIX = "lending-backup-";
    private static final int MAX_BACKUP_FILES = 10;
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private GroupConfigStore groupConfigStore;
    
    @Inject
    private Recorder recorder;
    
    @Inject
    private ScheduledExecutorService executor;
    
    private final Gson gson;
    private Path backupDirectory;
    private Path syncDataFile;
    
    // FIXED: Removed manual constructor - using only @Inject dependency injection
    public LocalDataSyncService() {
        this.gson = new Gson();
    }
    
    public void initialize() {
        try {
            // Create backup directory
            backupDirectory = Paths.get(BACKUP_DIR);
            if (!Files.exists(backupDirectory)) {
                Files.createDirectories(backupDirectory);
                log.info("Created backup directory: {}", backupDirectory);
            }

            syncDataFile = backupDirectory.resolve(SYNC_DATA_FILE);

            // FIXED: Do NOT load sync data on startup - only on explicit login
            // Data will be loaded via onAccountLogin() when user logs in
            // loadSyncData();  // Removed - don't load until logged in

            // Schedule periodic backups every 5 minutes (only saves if logged in)
            schedulePeriodicBackup();

            log.info("LocalDataSyncService initialized - data will load on login");

        } catch (IOException e) {
            log.error("Failed to initialize LocalDataSyncService", e);
        }
    }

    /**
     * ADDED: Called when user logs in - now safe to load sync data
     */
    public void onAccountLogin() {
        log.info("Loading sync data for logged-in user");
        loadSyncData();
    }
    
    /**
     * Create a complete backup of all lending data
     */
    public void createBackup() {
        try {
            BackupData backupData = new BackupData();
            backupData.timestamp = Instant.now().toEpochMilli();
            backupData.groups = new ArrayList<>(groupConfigStore.getAllGroups());
            backupData.activeEntries = recorder.getActiveEntries();
            backupData.historyEntries = recorder.getHistoryEntries();
            backupData.availableEntries = new HashMap<>();
            backupData.borrowedEntries = new HashMap<>();
            backupData.lentEntries = new HashMap<>();
            
            // Collect per-group data
            for (LendingGroup group : backupData.groups) {
                String groupId = group.getId();
                backupData.availableEntries.put(groupId, recorder.getAvailable(groupId));
                backupData.borrowedEntries.put(groupId, recorder.getBorrowed(groupId));
                backupData.lentEntries.put(groupId, recorder.getLent(groupId));
            }
            
            String backupJson = gson.toJson(backupData);
            
            // Write to timestamped backup file
            String backupFileName = BACKUP_FILE_PREFIX + Instant.now().toEpochMilli() + ".json";
            Path backupFile = backupDirectory.resolve(backupFileName);
            Files.write(backupFile, backupJson.getBytes());
            
            // Also update the sync data file for cross-session persistence
            Files.write(syncDataFile, backupJson.getBytes());
            
            // Clean up old backup files
            cleanupOldBackups();
            
            log.debug("Created backup: {}", backupFile);
            
        } catch (IOException e) {
            log.error("Failed to create backup", e);
        }
    }
    
    /**
     * Load and sync data from previous sessions
     */
    private void loadSyncData() {
        if (!Files.exists(syncDataFile)) {
            log.debug("No sync data file found, starting fresh");
            return;
        }
        
        try {
            String syncJson = new String(Files.readAllBytes(syncDataFile));
            BackupData syncData = gson.fromJson(syncJson, BackupData.class);
            
            if (syncData != null) {
                // Sync group data - merge with existing groups
                syncGroups(syncData.groups);
                
                // Sync lending entries - merge new entries and update existing ones
                syncLendingEntries(syncData);
                
                log.info("Successfully loaded and synced data from previous session");
            }
            
        } catch (IOException e) {
            log.error("Failed to load sync data", e);
        }
    }
    
    /**
     * Sync groups - merge new groups and update existing ones
     */
    private void syncGroups(List<LendingGroup> syncedGroups) {
        if (syncedGroups == null) return;
        
        for (LendingGroup syncedGroup : syncedGroups) {
            LendingGroup existingGroup = groupConfigStore.getGroup(syncedGroup.getId());
            
            if (existingGroup == null) {
            } else {
                // New group - recreate it using createGroup
                // Get owner from synced group members
                String ownerName = syncedGroup.getMembers().stream()
                    .filter(m -> "owner".equalsIgnoreCase(m.getRole()))
                    .map(GroupMember::getName)
                    .findFirst()
                    .orElse("Unknown");
                String groupId = groupConfigStore.createGroup(syncedGroup.getName(), syncedGroup.getDescription(), ownerName);
                // Add remaining members to the new group
                if (syncedGroup.getMembers() != null) {
                    for (var member : syncedGroup.getMembers()) {
                        if (!"owner".equalsIgnoreCase(member.getRole())) {
                            groupConfigStore.addMember(groupId, member.getName(), member.getRole());
                        }
                    }
                }
                log.debug("Imported new group: {}", syncedGroup.getName());
                // Existing group - check for new members
                syncGroupMembers(existingGroup, syncedGroup);
            }
        }
    }
    
    /**
     * Sync group members - add new members without removing existing ones
     */
    private void syncGroupMembers(LendingGroup existing, LendingGroup synced) {
        if (synced.getMembers() == null) return;
        
        Set<String> existingMemberNames = new HashSet<>();
        if (existing.getMembers() != null) {
            existing.getMembers().forEach(member -> existingMemberNames.add(member.getName()));
        }
        
        boolean membersAdded = false;
        for (var syncedMember : synced.getMembers()) {
            if (!existingMemberNames.contains(syncedMember.getName())) {
                groupConfigStore.addMember(existing.getId(), syncedMember.getName(), syncedMember.getRole());
                membersAdded = true;
                log.debug("Added new member {} to group {}", syncedMember.getName(), existing.getName());
            }
        }
        
        // saveGroup is not needed as addMember already saves
    }
    
    /**
     * Sync lending entries - merge new entries
     */
    private void syncLendingEntries(BackupData syncData) {
        // Get current entry IDs to avoid duplicates
        Set<String> existingEntryIds = new HashSet<>();
        recorder.getActiveEntries().forEach(entry -> existingEntryIds.add(entry.getId()));
        recorder.getHistoryEntries().forEach(entry -> existingEntryIds.add(entry.getId()));

        // FIXED: Also include available, borrowed, and lent entry IDs to prevent duplication
        // This was causing quantity to increase on every login because available entries
        // were not in existingEntryIds and got re-added via addToAvailableList
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            String groupId = group.getId();
            recorder.getAvailable(groupId).forEach(entry -> {
                if (entry.getId() != null) existingEntryIds.add(entry.getId());
            });
            recorder.getBorrowed(groupId).forEach(entry -> {
                if (entry.getId() != null) existingEntryIds.add(entry.getId());
            });
            recorder.getLent(groupId).forEach(entry -> {
                if (entry.getId() != null) existingEntryIds.add(entry.getId());
            });
        }
        
        // Sync active entries
        if (syncData.activeEntries != null) {
            for (LendingEntry entry : syncData.activeEntries) {
                if (!existingEntryIds.contains(entry.getId())) {
                    recorder.addEntry(entry);
                    log.debug("Synced active entry: {} for {}", entry.getItem(), entry.getBorrower());
                }
            }
        }
        
        // Sync history entries - add them directly to history list
        if (syncData.historyEntries != null) {
            for (LendingEntry entry : syncData.historyEntries) {
                if (!existingEntryIds.contains(entry.getId())) {
                    // Use completeEntry to add to history
                    recorder.addEntry(entry);
                    recorder.completeEntry(entry.getId(), true);
                    log.debug("Synced history entry: {} for {}", entry.getItem(), entry.getBorrower());
                }
            }
        }
        
        // Sync per-group entries
        syncPerGroupEntries(syncData, existingEntryIds);
    }
    
    /**
     * Sync per-group available, borrowed, and lent entries
     * FIXED: Uses restoreAvailable instead of addToAvailableList to prevent quantity duplication
     */
    private void syncPerGroupEntries(BackupData syncData, Set<String> existingEntryIds) {
        // Sync available entries - use restore method that doesn't add quantities
        if (syncData.availableEntries != null) {
            for (Map.Entry<String, List<LendingEntry>> groupEntry : syncData.availableEntries.entrySet()) {
                String groupId = groupEntry.getKey();
                for (LendingEntry entry : groupEntry.getValue()) {
                    if (!existingEntryIds.contains(entry.getId())) {
                        // FIXED: Use restoreAvailable instead of addToAvailableList
                        // This prevents quantity from being added on every login
                        recorder.restoreAvailable(groupId, entry.getLender(), entry);
                    }
                }
            }
        }
        
        // Sync borrowed entries - use restore method that doesn't duplicate
        if (syncData.borrowedEntries != null) {
            for (Map.Entry<String, List<LendingEntry>> groupEntry : syncData.borrowedEntries.entrySet()) {
                String groupId = groupEntry.getKey();
                for (LendingEntry entry : groupEntry.getValue()) {
                    if (!existingEntryIds.contains(entry.getId())) {
                        // FIXED: Use restoreBorrowed instead of markAsBorrowed
                        recorder.restoreBorrowed(groupId, entry.getBorrower(), entry);
                    }
                }
            }
        }

        // Sync lent entries - use restore method that doesn't duplicate
        if (syncData.lentEntries != null) {
            for (Map.Entry<String, List<LendingEntry>> groupEntry : syncData.lentEntries.entrySet()) {
                String groupId = groupEntry.getKey();
                for (LendingEntry entry : groupEntry.getValue()) {
                    if (!existingEntryIds.contains(entry.getId())) {
                        // FIXED: Use restoreLent instead of markAsLent
                        recorder.restoreLent(groupId, entry.getLender(), entry.getBorrower(), entry, entry.getDueTime());
                    }
                }
            }
        }
    }
    
    /**
     * Export all data to a portable backup file
     */
    public File exportPortableBackup() throws IOException {
        BackupData backupData = new BackupData();
        backupData.timestamp = Instant.now().toEpochMilli();
        backupData.groups = new ArrayList<>(groupConfigStore.getAllGroups());
        backupData.activeEntries = recorder.getActiveEntries();
        backupData.historyEntries = recorder.getHistoryEntries();
        backupData.availableEntries = new HashMap<>();
        backupData.borrowedEntries = new HashMap<>();
        backupData.lentEntries = new HashMap<>();
        
        // Collect per-group data
        for (LendingGroup group : backupData.groups) {
            String groupId = group.getId();
            backupData.availableEntries.put(groupId, recorder.getAvailable(groupId));
            backupData.borrowedEntries.put(groupId, recorder.getBorrowed(groupId));
            backupData.lentEntries.put(groupId, recorder.getLent(groupId));
        }
        
        String backupJson = gson.toJson(backupData);
        
        String exportFileName = "lending-tracker-export-" + Instant.now().toEpochMilli() + ".json";
        Path exportFile = backupDirectory.resolve(exportFileName);
        Files.write(exportFile, backupJson.getBytes());
        
        return exportFile.toFile();
    }
    
    /**
     * Import data from a portable backup file
     */
    public boolean importPortableBackup(File backupFile) {
        try {
            String backupJson = new String(Files.readAllBytes(backupFile.toPath()));
            BackupData importData = gson.fromJson(backupJson, BackupData.class);
            
            if (importData != null) {
                // Import groups
                syncGroups(importData.groups);
                
                // Import entries
                syncLendingEntries(importData);
                
                // Create a backup after successful import
                createBackup();
                
                log.info("Successfully imported data from backup file: {}", backupFile.getName());
                return true;
            }
            
        } catch (IOException e) {
            log.error("Failed to import backup file", e);
        }
        
        return false;
    }
    
    /**
     * Get list of available backup files
     */
    public List<File> getAvailableBackups() {
        List<File> backups = new ArrayList<>();
        
        try {
            Files.list(backupDirectory)
                .filter(path -> path.getFileName().toString().startsWith(BACKUP_FILE_PREFIX))
                .map(Path::toFile)
                .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified())) // Most recent first
                .forEach(backups::add);
                
        } catch (IOException e) {
            log.error("Failed to list backup files", e);
        }
        
        return backups;
    }
    
    /**
     * Restore from a specific backup file
     */
    public boolean restoreFromBackup(File backupFile) {
        return importPortableBackup(backupFile);
    }
    
    /**
     * Schedule periodic automatic backups
     */
    private void schedulePeriodicBackup() {
        executor.scheduleAtFixedRate(() -> {
            try {
                createBackup();
            } catch (Exception e) {
                log.error("Failed to create scheduled backup", e);
            }
        }, 5, 5, TimeUnit.MINUTES); // Backup every 5 minutes
    }
    
    /**
     * Clean up old backup files, keeping only the most recent MAX_BACKUP_FILES
     */
    private void cleanupOldBackups() {
        try {
            List<Path> backupFiles = Files.list(backupDirectory)
                .filter(path -> path.getFileName().toString().startsWith(BACKUP_FILE_PREFIX))
                .sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .collect(Collectors.toList());
            
            // Delete old backup files
            if (backupFiles.size() > MAX_BACKUP_FILES) {
                for (int i = MAX_BACKUP_FILES; i < backupFiles.size(); i++) {
                    Files.deleteIfExists(backupFiles.get(i));
                    log.debug("Deleted old backup file: {}", backupFiles.get(i));
                }
            }
            
        } catch (IOException e) {
            log.error("Failed to cleanup old backups", e);
        }
    }
    
    /**
     * Get backup directory for user access
     */
    public Path getBackupDirectory() {
        return backupDirectory;
    }
    
    /**
     * Data structure for backup/sync operations
     */
    private static class BackupData {
        long timestamp;
        List<LendingGroup> groups;
        List<LendingEntry> activeEntries;
        List<LendingEntry> historyEntries;
        Map<String, List<LendingEntry>> availableEntries;
        Map<String, List<LendingEntry>> borrowedEntries;
        Map<String, List<LendingEntry>> lentEntries;
    }
    
    public void shutdown() {
        // Final backup before shutdown
        createBackup();
    }
}