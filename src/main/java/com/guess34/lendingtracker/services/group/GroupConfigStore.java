package com.guess34.lendingtracker.services.group;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.model.LendingGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import com.guess34.lendingtracker.services.sync.GroupSyncService;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Account-specific group store with JSON persistence.
 *
 * IMPORTANT: Group data is stored PER-ACCOUNT to prevent bleeding between accounts.
 * Each account has its own list of groups they are a member of.
 *
 * Storage keys use format: "lendingtracker.{accountName}.groups"
 * This ensures Account A's groups don't appear for Account B.
 *
 * SYNC ARCHITECTURE (No backend server):
 * - Groups are LOCAL to each user's RuneLite installation
 * - When User A invites User B via right-click, User A generates a single-use code
 * - User B must enter this code on their end via "Join Group" to add the group locally
 * - The invite code contains the group ID which allows both users to share marketplace data
 * - Marketplace data is synced via shared group ID (stored in config)
 */
@Slf4j
@Singleton
public class GroupConfigStore {

    private static final String CFG_GROUP = "lendingtracker";
    private static final String CFG_KEY_GROUPS_SUFFIX = ".groups";
    private static final String CFG_KEY_ACTIVE_SUFFIX = ".activeGroupId";
    // Global group registry - stores group metadata that can be looked up by code
    private static final String CFG_KEY_GLOBAL_REGISTRY = "lendingtracker.globalGroupRegistry";

    @Inject private ConfigManager configManager;
    @Inject private Client client;
    @Inject private Gson gson;
    @Inject private GroupSyncService groupSyncService;

    private final Map<String, LendingGroup> groups = new LinkedHashMap<>();
    private String activeGroupId;
    private String currentAccountName = null;

    // FIXED: Removed manual constructor - using only @Inject dependency injection

    /**
     * FIXED: Initialize should NEVER load any data.
     * Data is ONLY loaded when a user explicitly logs in via onAccountLogin().
     * This prevents showing cached data on startup before login.
     */
    public void initialize() {
        // FIXED: Always clear all cached data on initialize - NEVER load anything
        groups.clear();
        activeGroupId = null;
        currentAccountName = null;

        log.info("GroupConfigStore initialized - all data cleared, waiting for login");
        // NOTE: Data will only be loaded when onAccountLogin() is called after LOGGED_IN event
    }

    /**
     * Update current account name from client.
     * Called on initialize and when account changes.
     */
    private void updateCurrentAccount() {
        String previousAccount = currentAccountName;
        currentAccountName = null;

        if (client != null && client.getLocalPlayer() != null) {
            String name = client.getLocalPlayer().getName();
            if (name != null && !name.isEmpty()) {
                currentAccountName = name.toLowerCase().replace(" ", "_");
            }
        }

        // If account changed, reload groups for new account
        if (previousAccount != null && !previousAccount.equals(currentAccountName)) {
            log.info("Account changed from {} to {}, reloading groups", previousAccount, currentAccountName);
            groups.clear();
            activeGroupId = null;
            loadGroups();
            loadActiveGroup();
        }
    }

    /**
     * Called when player logs in - reload data for this account
     * FIXED: Always reload data on login, even if same account (re-login scenario)
     * FIXED: Added idempotent check to avoid duplicate loads within same session
     */
    public void onAccountLogin(String accountName) {
        if (accountName == null || accountName.isEmpty()) {
            log.warn("onAccountLogin called with null/empty accountName, ignoring");
            return;
        }

        String normalizedName = accountName.toLowerCase().replace(" ", "_");

        // FIXED: Check if we're already loaded for this account to avoid duplicate loads
        // within the same session (e.g., both GameStateChanged and RuneScapeProfileChanged fire)
        if (normalizedName.equals(currentAccountName) && !groups.isEmpty()) {
            log.info("Account {} already loaded with {} groups, skipping duplicate load",
                normalizedName, groups.size());
            return;
        }

        log.info("=== ACCOUNT LOGIN START === Account: {} (normalized: {})", accountName, normalizedName);
        log.info("Previous state - currentAccountName: {}, groups count: {}, activeGroupId: {}",
            currentAccountName, groups.size(), activeGroupId);

        // FIXED: Always set account name and reload, even if same account
        // This handles the case where user logs out and logs back in
        currentAccountName = normalizedName;
        groups.clear();
        activeGroupId = null;

        log.info("State cleared - now loading from config...");
        loadGroups();
        loadActiveGroup();

        log.info("=== ACCOUNT LOGIN COMPLETE === Loaded {} groups for account {}, active group: {}",
            groups.size(), currentAccountName, activeGroupId);

        // List all loaded groups
        for (LendingGroup g : groups.values()) {
            log.info("  - Group: '{}' (id: {})", g.getName(), g.getId());
        }

    }

    /**
     * Called when player logs out - save data but keep groups AND active group in memory
     * FIXED: Groups AND activeGroupId are now preserved in memory after logout
     * User can still see their groups but cannot interact with them until logged back in
     */
    public void onAccountLogout() {
        log.info("Account logout detected - currentAccountName: {}, groups count: {}, activeGroup: {}",
            currentAccountName, groups.size(), activeGroupId);

        // FIXED: Save groups BEFORE clearing currentAccountName
        // The save methods need currentAccountName to generate the config key
        if (currentAccountName != null) {
            if (!groups.isEmpty()) {
                log.info("Saving {} groups for account {} before logout", groups.size(), currentAccountName);
                saveGroups();
                saveActiveGroup();
            } else {
                log.warn("No groups to save for account {} - groups map is empty!", currentAccountName);
            }
        } else {
            log.warn("Cannot save groups - currentAccountName is null!");
        }

        // CHANGED: Don't clear groups OR activeGroupId - keep them in memory for:
        // 1. Dropdown display (groups)
        // 2. Re-selection on next login (activeGroupId)
        // Only clear the account name to prevent modifications
        // groups.clear(); // REMOVED - keep groups visible in dropdown
        // activeGroupId = null; // REMOVED - keep active group for next login
        currentAccountName = null;
        log.info("Cleared account after logout (groups: {}, activeGroup: {} preserved)", groups.size(), activeGroupId);
    }

    /**
     * Get the config key for the current account's groups
     * FIXED: Returns null if no account is logged in (prevents loading "default" data)
     * Key format: accountname.groups (the CFG_GROUP "lendingtracker" is the config group prefix)
     */
    private String getGroupsKey() {
        if (currentAccountName == null || currentAccountName.isEmpty()) {
            // FIXED: Don't return a default key - return null to prevent loading data
            return null;
        }
        // FIXED: Don't duplicate "lendingtracker" - it's already the config group
        return currentAccountName + CFG_KEY_GROUPS_SUFFIX;
    }

    /**
     * Get the config key for the current account's active group
     * FIXED: Returns null if no account is logged in
     * Key format: accountname.activeGroupId
     */
    private String getActiveGroupKey() {
        if (currentAccountName == null || currentAccountName.isEmpty()) {
            // FIXED: Don't return a default key - return null to prevent loading data
            return null;
        }
        // FIXED: Don't duplicate "lendingtracker" - it's already the config group
        return currentAccountName + CFG_KEY_ACTIVE_SUFFIX;
    }

    /**
     * FIXED: Check if user is currently logged in
     * Must check BOTH GameState AND local player name to be sure
     */
    public boolean isLoggedIn() {
        if (client == null) {
            return false;
        }

        // FIXED: Also check GameState - must be LOGGED_IN
        try {
            if (client.getGameState() != GameState.LOGGED_IN) {
                return false;
            }
        } catch (Exception e) {
            log.debug("Error checking game state", e);
            return false;
        }
        try {
            if (client.getLocalPlayer() != null) {
                String name = client.getLocalPlayer().getName();
                return name != null && !name.isEmpty();
            }
        } catch (Exception e) {
            log.debug("Error checking login status", e);
        }
        return false;
    }

    /**
     * FIXED: Returns null if not logged in, to prevent showing cached data
     */
    public LendingGroup getActiveGroup() {
        // FIXED: Don't return any group if user is not logged in
        if (!isLoggedIn()) {
            return null;
        }
        if (activeGroupId == null) {
            return null;
        }
        return groups.get(activeGroupId);
    }

    /**
     * ADDED: Get active group without login check - for UI display during transitions
     * Use this when you've already verified login status separately
     */
    public LendingGroup getActiveGroupUnchecked() {
        if (activeGroupId == null) {
            return null;
        }
        return groups.get(activeGroupId);
    }

    /**
     * ADDED: Check if we have an active group ID set (even if not logged in)
     * Useful for determining if a group was selected before logout
     */
    public boolean hasActiveGroupId() {
        return activeGroupId != null && groups.containsKey(activeGroupId);
    }

    /**
     * ADDED: Check if currentAccountName is set (indicates onAccountLogin was called)
     */
    public boolean hasCurrentAccount() {
        return currentAccountName != null && !currentAccountName.isEmpty();
    }
    
    public void appointAdmin(String groupId, String playerName) {
        LendingGroup group = groups.get(groupId);
        if (group != null) {
            // Find the member and make them admin
            for (GroupMember member : group.getMembers()) {
                if (member.getName().equalsIgnoreCase(playerName)) {
                    member.setRole("admin");
                    saveGroups();
                    log.info("Appointed {} as admin in group {}", playerName, groupId);
                    break;
                }
            }
        }
    }

    /**
     * Set a member's role. Only owners and co-owners can change roles.
     * Uses canChangeRole() for permission checks.
     * @param groupId The group ID
     * @param requesterName The player requesting the change
     * @param targetName The player whose role is being changed
     * @param newRole The new role (co-owner, admin, mod, member)
     * @return true if successful, false otherwise
     */
    public boolean setMemberRole(String groupId, String requesterName, String targetName, String newRole) {
        LendingGroup group = groups.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return false;
        }

        // Use the new canChangeRole permission check
        if (!canChangeRole(groupId, requesterName, targetName)) {
            log.warn("{} cannot change {}'s role in group {}", requesterName, targetName, groupId);
            return false;
        }

        // Validate new role - cannot promote to owner
        if ("owner".equalsIgnoreCase(newRole)) {
            log.warn("Cannot promote to owner role");
            return false;
        }

        // Co-owners cannot promote someone to co-owner
        if ("co-owner".equalsIgnoreCase(newRole) && !isOwner(groupId, requesterName)) {
            log.warn("Only owners can promote to co-owner");
            return false;
        }

        // Find and update member
        for (GroupMember member : group.getMembers()) {
            if (member.getName().equalsIgnoreCase(targetName)) {
                String oldRole = member.getRole();
                member.setRole(newRole.toLowerCase());
                saveGroups();
                // FIXED: Publish sync event for role change
                if (groupSyncService != null) {
                    groupSyncService.publishEvent(
                        GroupSyncService.SyncEventType.SETTINGS_CHANGED,
                        groupId,
                        null
                    );
                }
                log.info("Changed {}'s role from {} to {} in group {}", targetName, oldRole, newRole, groupId);
                return true;
            }
        }

        log.warn("Member {} not found in group {}", targetName, groupId);
        return false;
    }

    /**
     * Transfer ownership of a group from current owner to another member.
     * The current owner becomes a co-owner after transfer.
     * Only the current owner can call this method.
     * @param groupId The group ID
     * @param currentOwnerName The current owner requesting the transfer
     * @param newOwnerName The member who will become the new owner
     * @return true if successful, false otherwise
     */
    public boolean transferOwnership(String groupId, String currentOwnerName, String newOwnerName) {
        LendingGroup group = groups.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return false;
        }

        // Verify the requester is the current owner
        if (!isOwner(groupId, currentOwnerName)) {
            log.warn("{} is not the owner of group {} - cannot transfer ownership", currentOwnerName, groupId);
            return false;
        }

        // Find current owner and new owner members
        GroupMember currentOwnerMember = null;
        GroupMember newOwnerMember = null;

        for (GroupMember member : group.getMembers()) {
            if (member.getName().equalsIgnoreCase(currentOwnerName)) {
                currentOwnerMember = member;
            }
            if (member.getName().equalsIgnoreCase(newOwnerName)) {
                newOwnerMember = member;
            }
        }

        if (currentOwnerMember == null) {
            log.warn("Current owner {} not found in group {}", currentOwnerName, groupId);
            return false;
        }

        if (newOwnerMember == null) {
            log.warn("New owner {} not found in group {}", newOwnerName, groupId);
            return false;
        }

        // Cannot transfer to self
        if (currentOwnerName.equalsIgnoreCase(newOwnerName)) {
            log.warn("Cannot transfer ownership to yourself");
            return false;
        }

        // Perform the transfer
        String previousNewOwnerRole = newOwnerMember.getRole();
        newOwnerMember.setRole("owner");
        currentOwnerMember.setRole("co-owner");

        saveGroups();
        // FIXED: Publish sync event for ownership transfer
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.SETTINGS_CHANGED,
                groupId,
                null
            );
        }
        log.info("Transferred ownership of group '{}' from {} to {} (previous role: {})",
            group.getName(), currentOwnerName, newOwnerName, previousNewOwnerRole);

        return true;
    }

    /**
     * Force set a member's role (bypasses permission checks).
     * USE WITH CAUTION - intended for testing and special cases only.
     * @param groupId The group ID
     * @param targetName The player whose role is being changed
     * @param newRole The new role (including owner)
     * @return true if successful, false otherwise
     */
    public boolean forceSetRole(String groupId, String targetName, String newRole) {
        LendingGroup group = groups.get(groupId);
        if (group == null) {
            log.warn("Group not found: {}", groupId);
            return false;
        }

        // If setting someone to owner, first demote current owner
        if ("owner".equalsIgnoreCase(newRole)) {
            for (GroupMember member : group.getMembers()) {
                if ("owner".equalsIgnoreCase(member.getRole())) {
                    member.setRole("co-owner");
                    log.info("Demoted previous owner {} to co-owner", member.getName());
                    break;
                }
            }
        }

        // Find and update target member
        for (GroupMember member : group.getMembers()) {
            if (member.getName().equalsIgnoreCase(targetName)) {
                String oldRole = member.getRole();
                member.setRole(newRole.toLowerCase());
                saveGroups();
                log.info("Force changed {}'s role from {} to {} in group {}", targetName, oldRole, newRole, groupId);
                return true;
            }
        }

        log.warn("Member {} not found in group {}", targetName, groupId);
        return false;
    }

    /**
     * Get a member's current role
     */
    public String getMemberRole(String groupId, String playerName) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return null;

        for (GroupMember member : group.getMembers()) {
            if (member.getName().equalsIgnoreCase(playerName)) {
                return member.getRole();
            }
        }
        return null;
    }

    /**
     * Remove a member from the group using role-based hierarchy.
     * Uses canKick() to check permissions.
     * Hierarchy: owner > co-owner > admin > mod > member
     */
    public boolean removeMemberFromGroup(String groupId, String requesterName, String targetName) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        // Use the new canKick permission check
        if (!canKick(groupId, requesterName, targetName)) {
            log.warn("{} doesn't have permission to kick {}", requesterName, targetName);
            return false;
        }

        // Remove the member
        boolean removed = group.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(targetName));
        if (removed) {
            saveGroups();
            // FIXED: Publish sync event for member removal
            if (groupSyncService != null) {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.MEMBER_LEFT,
                    groupId + ":" + targetName,
                    null
                );
            }
            log.info("{} removed {} from group {}", requesterName, targetName, groupId);
        }
        return removed;
    }

    /**
     * Get all available roles (excluding owner which cannot be assigned)
     * Hierarchy: owner > co-owner > admin > mod > member
     */
    public static String[] getAvailableRoles() {
        return new String[] {"co-owner", "admin", "mod", "member"};
    }

    /**
     * Get role rank (higher = more permissions)
     * owner=5, co-owner=4, admin=3, mod=2, member=1
     */
    public static int getRoleRank(String role) {
        if (role == null) return 1;
        switch (role.toLowerCase()) {
            case "owner": return 5;
            case "co-owner": return 4;
            case "admin": return 3;
            case "mod": return 2;
            default: return 1; // member
        }
    }

    /**
     * ADDED: Format a role name for display (capitalizes words, handles hyphenated roles).
     * Shared utility used by RosterPanel, SettingsPanel, etc.
     */
    public static String formatRoleName(String role)
    {
        if (role == null || role.isEmpty()) return "Member";

        String[] parts = role.toLowerCase().split("-");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            if (i > 0) result.append("-");
            if (!parts[i].isEmpty())
            {
                result.append(Character.toUpperCase(parts[i].charAt(0)));
                if (parts[i].length() > 1)
                {
                    result.append(parts[i].substring(1));
                }
            }
        }
        return result.toString();
    }

    /**
     * Check if a player can kick another player based on role hierarchy AND group settings.
     * Staff can only kick members with lower rank, and only if their role has kick enabled.
     * Owners can always kick anyone except themselves.
     * Co-owners, admins, and mods need group permission to kick.
     */
    public boolean canKick(String groupId, String kickerName, String targetName) {
        if (groupId == null || kickerName == null || targetName == null) return false;

        // Cannot kick yourself
        if (kickerName.equalsIgnoreCase(targetName)) return false;

        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        String kickerRole = getMemberRole(groupId, kickerName);
        String targetRole = getMemberRole(groupId, targetName);

        if (kickerRole == null || targetRole == null) return false;

        int kickerRank = getRoleRank(kickerRole);
        int targetRank = getRoleRank(targetRole);

        // Must be at least mod (rank 2) to kick anyone
        if (kickerRank < 2) return false;

        // Can only kick someone with strictly lower rank
        if (kickerRank <= targetRank) return false;

        // Owner can always kick (rank 5)
        if (kickerRank == 5) return true;

        // Check group permission settings for other roles
        switch (kickerRole.toLowerCase()) {
            case "co-owner":
                return group.isCoOwnerCanKick();
            case "admin":
                return group.isAdminCanKick();
            case "mod":
                return group.isModCanKick();
            default:
                return false;
        }
    }

    /**
     * Set kick permission for a role. Only owner/co-owner can change.
     */
    public boolean setKickPermission(String groupId, String requesterName, String role, boolean canKick) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        // Only owner and co-owner can change permissions
        String requesterRole = getMemberRole(groupId, requesterName);
        if (requesterRole == null) return false;
        int requesterRank = getRoleRank(requesterRole);
        if (requesterRank < 4) return false; // Must be co-owner or owner

        switch (role.toLowerCase()) {
            case "co-owner":
                // Only owner can change co-owner permissions
                if (requesterRank < 5) return false;
                group.setCoOwnerCanKick(canKick);
                break;
            case "admin":
                group.setAdminCanKick(canKick);
                break;
            case "mod":
                group.setModCanKick(canKick);
                break;
            default:
                return false;
        }
        saveGroups();
        // FIXED: Publish sync event for permission change
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.SETTINGS_CHANGED,
                groupId,
                null
            );
        }
        log.info("{} set {} kick permission to {} for group {}", requesterName, role, canKick, groupId);
        return true;
    }

    /**
     * Get kick permission for a role
     */
    public boolean getKickPermission(String groupId, String role) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        switch (role.toLowerCase()) {
            case "co-owner":
                return group.isCoOwnerCanKick();
            case "admin":
                return group.isAdminCanKick();
            case "mod":
                return group.isModCanKick();
            case "owner":
                return true; // Owner can always kick
            default:
                return false;
        }
    }

    /**
     * ADDED: Check if a player can generate invite codes for a group.
     * Owner can always generate. Others depend on group permission settings.
     */
    public boolean canGenerateInviteCode(String groupId, String playerName) {
        if (groupId == null || playerName == null) return false;

        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        String role = getMemberRole(groupId, playerName);
        if (role == null) return false;

        int rank = getRoleRank(role);

        // Owner can always generate (rank 5)
        if (rank == 5) return true;

        // Check group permission settings for other roles
        switch (role.toLowerCase()) {
            case "co-owner":
                return group.isCoOwnerCanInvite();
            case "admin":
                return group.isAdminCanInvite();
            case "mod":
                return group.isModCanInvite();
            default:
                return false;
        }
    }

    /**
     * ADDED: Set invite code generation permission for a role. Only owner/co-owner can change.
     */
    public boolean setInvitePermission(String groupId, String requesterName, String role, boolean canInvite) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        // Only owner and co-owner can change permissions
        String requesterRole = getMemberRole(groupId, requesterName);
        if (requesterRole == null) return false;
        int requesterRank = getRoleRank(requesterRole);
        if (requesterRank < 4) return false; // Must be co-owner or owner

        switch (role.toLowerCase()) {
            case "co-owner":
                // Only owner can change co-owner permissions
                if (requesterRank < 5) return false;
                group.setCoOwnerCanInvite(canInvite);
                break;
            case "admin":
                group.setAdminCanInvite(canInvite);
                break;
            case "mod":
                group.setModCanInvite(canInvite);
                break;
            default:
                return false;
        }
        saveGroups();
        // FIXED: Publish sync event for permission change
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.SETTINGS_CHANGED,
                groupId,
                null
            );
        }
        log.info("{} set {} invite permission to {} for group {}", requesterName, role, canInvite, groupId);
        return true;
    }

    /**
     * ADDED: Get invite code generation permission for a role
     */
    public boolean getInvitePermission(String groupId, String role) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        switch (role.toLowerCase()) {
            case "co-owner":
                return group.isCoOwnerCanInvite();
            case "admin":
                return group.isAdminCanInvite();
            case "mod":
                return group.isModCanInvite();
            case "owner":
                return true; // Owner can always generate
            default:
                return false;
        }
    }

    /**
     * Check if a player can change another player's role.
     * Only owners and co-owners can change roles.
     * Co-owners cannot change owner or other co-owner roles.
     */
    public boolean canChangeRole(String groupId, String changerName, String targetName) {
        if (groupId == null || changerName == null || targetName == null) return false;

        // Cannot change your own role
        if (changerName.equalsIgnoreCase(targetName)) return false;

        String changerRole = getMemberRole(groupId, changerName);
        String targetRole = getMemberRole(groupId, targetName);

        if (changerRole == null) return false;

        int changerRank = getRoleRank(changerRole);
        int targetRank = getRoleRank(targetRole);

        // Only owner (5) and co-owner (4) can change roles
        if (changerRank < 4) return false;

        // Owner can change anyone except themselves
        if (changerRank == 5) return true;

        // Co-owner can only change roles of admin (3) and below
        return targetRank < 4;
    }

    /**
     * Check if player is co-owner
     */
    public boolean isCoOwner(String groupId, String playerName) {
        return hasRole(groupId, playerName, "co-owner");
    }

    /**
     * Check if player is mod
     */
    public boolean isMod(String groupId, String playerName) {
        return hasRole(groupId, playerName, "mod");
    }

    /**
     * Check if player has staff privileges (mod or higher)
     */
    public boolean isStaff(String groupId, String playerName) {
        String role = getMemberRole(groupId, playerName);
        return role != null && getRoleRank(role) >= 2;
    }

    public List<String> getAllGroupNames() {
        return groups.values().stream()
            .map(LendingGroup::getName)
            .collect(Collectors.toList());
    }
    
    public String getGroupIdByName(String name) {
        return groups.entrySet().stream()
            .filter(entry -> name.equals(entry.getValue().getName()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }
    
    public String getGroupNameById(String id) {
        LendingGroup group = groups.get(id);
        return group != null ? group.getName() : null;
    }
    
    /**
     * FIXED: Returns null if not logged in, to prevent showing cached data
     */
    public String getCurrentGroupId() {
        // FIXED: Don't return group ID if user is not logged in
        if (!isLoggedIn()) {
            return null;
        }
        return activeGroupId;
    }

    /**
     * ADDED: Get current group ID without login check - for operations during transitions
     * Use this when you've already verified login status separately
     */
    public String getCurrentGroupIdUnchecked() {
        return activeGroupId;
    }

    // ------------------ Persistence (Account-Specific) ------------------

    private void loadGroups() {
        String key = getGroupsKey();
        // FIXED: Don't load if no key (not logged in)
        if (key == null) {
            log.info("Not loading groups - no account logged in (key is null)");
            return;
        }

        log.info("Loading groups from config group '{}' with key '{}'", CFG_GROUP, key);
        String json = configManager.getConfiguration(CFG_GROUP, key);
        log.info("Loaded JSON from config: {}", json != null ? (json.length() > 100 ? json.substring(0, 100) + "..." : json) : "null");

        if (json != null && !json.isEmpty()) {
            try {
                Type type = new TypeToken<List<LendingGroup>>(){}.getType();
                List<LendingGroup> list = gson.fromJson(json, type);
                groups.clear();
                if (list != null) {
                    for (LendingGroup g : list) {
                        groups.put(g.getId(), g);
                        log.info("Loaded group: {} ({})", g.getName(), g.getId());
                    }
                }
                log.info("Loaded {} groups total for account {}", groups.size(), currentAccountName);
            } catch (Exception e) {
                log.error("Failed to load groups from {}", key, e);
            }
        } else {
            log.info("No groups found in config for key '{}'", key);
        }
    }

    private void saveGroups() {
        // FIXED: If currentAccountName is null but we're logged in, set it now
        // This handles the race condition where UI detects login before onAccountLogin() is called
        if (currentAccountName == null && isLoggedIn()) {
            try {
                String playerName = client.getLocalPlayer().getName();
                if (playerName != null && !playerName.isEmpty()) {
                    currentAccountName = playerName.toLowerCase().replace(" ", "_");
                    log.info("Auto-set currentAccountName to '{}' during saveGroups()", currentAccountName);
                    // Also load any existing groups for this account
                    loadGroups();
                    loadActiveGroup();
                }
            } catch (Exception e) {
                log.warn("Failed to auto-set currentAccountName", e);
            }
        }

        String key = getGroupsKey();
        // FIXED: Don't save if no key (not logged in)
        if (key == null) {
            log.info("Not saving groups - no account logged in (key is null)");
            return;
        }

        List<LendingGroup> list = new ArrayList<>(groups.values());
        String json = gson.toJson(list);
        log.info("Saving {} groups to config group '{}' with key '{}': {}",
            list.size(), CFG_GROUP, key, json.length() > 100 ? json.substring(0, 100) + "..." : json);
        configManager.setConfiguration(CFG_GROUP, key, json);
        log.info("Save complete for key '{}'", key);

        // Also save to global registry for code lookup
        saveToGlobalRegistry();
    }

    private void loadActiveGroup() {
        String key = getActiveGroupKey();
        // FIXED: Don't load if no key (not logged in)
        if (key == null) {
            log.debug("Not loading active group - no account logged in");
            return;
        }

        activeGroupId = configManager.getConfiguration(CFG_GROUP, key);
        if (activeGroupId != null && !groups.containsKey(activeGroupId)) {
            activeGroupId = null;
        }
        log.debug("Loaded active group: {} from key: {}", activeGroupId, key);
    }

    private void saveActiveGroup() {
        String key = getActiveGroupKey();
        // FIXED: Don't save if no key (not logged in)
        if (key == null) {
            log.debug("Not saving active group - no account logged in");
            return;
        }

        configManager.setConfiguration(CFG_GROUP, key, activeGroupId);
        log.debug("Saved active group: {} to key: {}", activeGroupId, key);
    }

    /**
     * Save group metadata to global registry for code-based lookup.
     * This allows other users to find group info when they enter an invite code.
     */
    private void saveToGlobalRegistry() {
        // Build registry of group ID -> basic info (for code lookup)
        Map<String, Map<String, String>> registry = new HashMap<>();

        for (LendingGroup group : groups.values()) {
            if (group.getInviteCode() != null || group.getClanCode() != null) {
                Map<String, String> info = new HashMap<>();
                info.put("name", group.getName());
                info.put("id", group.getId());
                if (group.getInviteCode() != null) {
                    info.put("inviteCode", group.getInviteCode());
                }
                if (group.getClanCode() != null && group.isClanCodeEnabled()) {
                    info.put("clanCode", group.getClanCode());
                }
                registry.put(group.getId(), info);
            }
        }

        String json = gson.toJson(registry);
        configManager.setConfiguration(CFG_GROUP, CFG_KEY_GLOBAL_REGISTRY, json);
    }

    // ------------------ CRUD ------------------

    public LendingGroup getGroup(String id) {
        return groups.get(id);
    }

    public Collection<LendingGroup> getAllGroups() {
        // CHANGED: Return groups even when logged out (for display in dropdown)
        // Groups are preserved in memory after logout
        return Collections.unmodifiableCollection(groups.values());
    }

    /**
     * Get all groups only if user is logged in (for operations that require login)
     */
    public Collection<LendingGroup> getAllGroupsIfLoggedIn() {
        if (currentAccountName == null || currentAccountName.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableCollection(groups.values());
    }

    /**
     * Find a group by exact name (case-sensitive match)
     * Used for joining groups by name
     * @param name The exact group name to search for
     * @return The LendingGroup if found, null otherwise
     */
    public LendingGroup getGroupByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return groups.values().stream()
            .filter(g -> name.equals(g.getName()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Check if player is already a member of a group by name
     * @param groupName The exact group name
     * @param playerName The player name to check
     * @return true if already a member, false otherwise
     */
    public boolean isPlayerInGroup(String groupName, String playerName) {
        LendingGroup group = getGroupByName(groupName);
        if (group == null || group.getMembers() == null) {
            return false;
        }
        return group.getMembers().stream()
            .anyMatch(m -> m.getName().equalsIgnoreCase(playerName));
    }

    /**
     * Switch to a group the player is already a member of
     * Does NOT add them if they're not a member - use invite codes for that
     * @param groupName The exact name of the group to switch to
     * @param playerName The player requesting to switch
     * @return "success" if switched, "not_member" if not a member, "not_found" if group doesn't exist
     */
    public String switchToGroup(String groupName, String playerName) {
        LendingGroup group = getGroupByName(groupName);
        if (group == null) {
            log.info("switchToGroup: Group '{}' not found (case-sensitive)", groupName);
            return "not_found";
        }

        // Check if already a member - must be member to switch to group
        if (group.getMembers() != null) {
            boolean isMember = group.getMembers().stream()
                .anyMatch(m -> m.getName().equalsIgnoreCase(playerName));
            if (isMember) {
                log.info("switchToGroup: {} switching to group '{}'", playerName, groupName);
                setCurrentGroupId(group.getId());
                return "success";
            }
        }

        // Not a member - cannot join without invite code
        log.info("switchToGroup: {} is not a member of '{}' - invite code required", playerName, groupName);
        return "not_member";
    }

    /**
     * Get all groups the player is a member of
     * @param playerName The player name to check
     * @return List of groups the player belongs to
     */
    /**
     * FIXED: Returns empty list if not logged in
     */
    public List<LendingGroup> getGroupsForPlayer(String playerName) {
        // FIXED: Don't return any groups if user is not logged in
        if (!isLoggedIn()) {
            return new ArrayList<>();
        }
        if (playerName == null || playerName.isEmpty()) {
            return new ArrayList<>();
        }
        return groups.values().stream()
            .filter(g -> g.getMembers() != null && g.getMembers().stream()
                .anyMatch(m -> m.getName().equalsIgnoreCase(playerName)))
            .collect(Collectors.toList());
    }

    public String createGroup(String name, String description, String ownerName) {
        // FIXED: Ensure currentAccountName is set before creating a group
        // This handles the race condition where UI allows group creation before onAccountLogin()
        if (currentAccountName == null && isLoggedIn()) {
            try {
                String playerName = client.getLocalPlayer().getName();
                if (playerName != null && !playerName.isEmpty()) {
                    currentAccountName = playerName.toLowerCase().replace(" ", "_");
                    log.info("Auto-set currentAccountName to '{}' during createGroup()", currentAccountName);
                    // Load any existing groups for this account first
                    loadGroups();
                    loadActiveGroup();
                }
            } catch (Exception e) {
                log.warn("Failed to auto-set currentAccountName in createGroup", e);
            }
        }

        // Validate unique name
        if (isGroupNameTaken(name)) {
            return null; // Name already taken
        }

        String id = UUID.randomUUID().toString().substring(0, 8);
        LendingGroup g = new LendingGroup(id, name, description);

        // Add owner as first member
        GroupMember owner = new GroupMember(ownerName, "owner");
        g.addMember(owner);

        groups.put(id, g);

        // FIXED: Always set newly created group as active (auto-join)
        activeGroupId = id;
        saveActiveGroup();

        saveGroups();
        return id;
    }

    public void deleteGroup(String id) {
        groups.remove(id);
        if (Objects.equals(activeGroupId, id)) {
            activeGroupId = groups.isEmpty() ? null : groups.keySet().iterator().next();
            saveActiveGroup();
        }
        saveGroups();
    }

    // ------------------ Members & Roles ------------------

    public void addMember(String groupId, String name, String role) {
        LendingGroup g = groups.get(groupId);
        if (g == null) return;
        if (g.getMembers() == null) g.setMembers(new ArrayList<>());
        boolean exists = g.getMembers().stream().anyMatch(m -> m.getName().equalsIgnoreCase(name));
        if (!exists) {
            g.getMembers().add(new GroupMember(name, role));
            saveGroups();
            // FIXED: Publish sync event for new member
            if (groupSyncService != null) {
                groupSyncService.publishEvent(
                    GroupSyncService.SyncEventType.MEMBER_JOINED,
                    groupId + ":" + name,
                    null
                );
            }
        }
    }

    public void removeMember(String groupId, String name) {
        LendingGroup g = groups.get(groupId);
        if (g == null || g.getMembers() == null) return;
        g.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(name));
        saveGroups();
        // FIXED: Publish sync event for member removal
        if (groupSyncService != null) {
            groupSyncService.publishEvent(
                GroupSyncService.SyncEventType.MEMBER_LEFT,
                groupId + ":" + name,
                null
            );
        }
    }

    // ------------------ Compatibility wrappers used by the panel ------------------

    /** Panel expects this exact name. */
    public void setCurrentGroupId(String id) {
        if (id != null && groups.containsKey(id)) {
            activeGroupId = id;
            saveActiveGroup();
        }
    }

    /** Panel expects this exact signature. */
    public boolean isOwner(String groupId, String playerName) {
        return hasRole(groupId, playerName, "owner");
    }

    /** Panel expects this exact signature. */
    public boolean isAdmin(String groupId, String playerName) {
        // Treat owner/admin/moderator as admin privileges
        return hasRole(groupId, playerName, "owner") ||
               hasRole(groupId, playerName, "admin") ||
               hasRole(groupId, playerName, "moderator");
    }

    /** Panel expects this exact signature. */
    public List<String> getGroupMembers(String groupId) {
        LendingGroup g = groups.get(groupId);
        if (g == null || g.getMembers() == null) return Collections.emptyList();
        return g.getMembers().stream().map(GroupMember::getName).collect(Collectors.toList());
    }

    public List<String> searchGroups(String searchTerm) {
        String q = searchTerm == null ? "" : searchTerm.toLowerCase();
        return groups.values().stream()
                .filter(g -> (g.getName() != null && g.getName().toLowerCase().contains(q)) ||
                             (g.getDescription() != null && g.getDescription().toLowerCase().contains(q)))
                .map(LendingGroup::getId)
                .collect(Collectors.toList());
    }

    // ------------------ Helpers ------------------
    private boolean hasRole(String groupId, String playerName, String role) {
        if (groupId == null || playerName == null) return false;
        LendingGroup g = groups.get(groupId);
        if (g == null || g.getMembers() == null) return false;
        return g.getMembers().stream()
                .anyMatch(m -> m.getName().equalsIgnoreCase(playerName) &&
                               role.equalsIgnoreCase(m.getRole()));
    }

    // Check if group name already exists (case-insensitive)
    public boolean isGroupNameTaken(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String lowerName = name.trim().toLowerCase();
        return groups.values().stream()
                .anyMatch(g -> g.getName().toLowerCase().equals(lowerName));
    }

    // Find group by code (either group code or clan code)
    public LendingGroup findGroupByCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String trimmedCode = code.trim();
        
        for (LendingGroup group : groups.values()) {
            // Check group code
            if (trimmedCode.equals(group.getInviteCode())) {
                return group;
            }
            // Check clan code (if enabled)
            if (group.isClanCodeEnabled() && trimmedCode.equals(group.getClanCode())) {
                return group;
            }
        }
        return null;
    }

    // Validate and join group with code
    public boolean joinGroupWithCode(String code, String playerName) {
        LendingGroup group = findGroupByCode(code);
        if (group == null) return false;
        
        boolean isGroupCode = code.equals(group.getInviteCode());
        boolean isClanCode = group.isClanCodeEnabled() && code.equals(group.getClanCode());
        
        if (!isGroupCode && !isClanCode) return false;
        
        // Check if player already used group code
        if (isGroupCode && group.hasUsedGroupCode(playerName)) {
            return false; // Already used single-use code
        }
        
        // Create pending invite
        com.guess34.lendingtracker.model.PendingInvite invite = 
            new com.guess34.lendingtracker.model.PendingInvite(
                playerName, 
                code, 
                isGroupCode ? "group" : "clan"
            );
        
        group.getPendingInvites().add(invite);
        
        // Mark code as used if group code
        if (isGroupCode) {
            group.markGroupCodeUsed(playerName);
        }
        
        saveGroups();
        return true;
    }

    // Set custom code for group
    public void setCustomGroupCode(String groupId, String customCode) {
        LendingGroup group = groups.get(groupId);
        if (group != null) {
            group.setInviteCode(customCode);
            saveGroups();
        }
    }

    // Set custom clan code
    public void setCustomClanCode(String groupId, String customCode) {
        LendingGroup group = groups.get(groupId);
        if (group != null) {
            group.setClanCode(customCode);
            group.setClanCodeUseCount(0);
            saveGroups();
        }
    }

    /**
     * Generate a new single-use invite code for a group.
     * The code becomes void after one person uses it.
     * @return The generated code, or null if group not found
     */
    public String generateSingleUseInviteCode(String groupId) {
        LendingGroup group = groups.get(groupId);
        if (group != null) {
            String code = group.generateSingleUseCode();
            saveGroups();
            return code;
        }
        return null;
    }

    /**
     * Check if group has an active (non-voided) invite code
     */
    public boolean hasActiveInviteCode(String groupId) {
        LendingGroup group = groups.get(groupId);
        return group != null && group.hasActiveInviteCode();
    }

    /**
     * Use an invite code to join a group directly
     * FIXED: This method actually adds the player as a member (unlike joinGroupWithCode which creates pending invite)
     * @param code The invite code (single-use or clan code)
     * @param playerName The player joining
     * @return The group ID if successful, null if code invalid/expired/already used
     */
    public String useInviteCode(String code, String playerName) {
        if (code == null || code.trim().isEmpty() || playerName == null || playerName.trim().isEmpty()) {
            return null;
        }

        String trimmedCode = code.trim();

        // Search all groups for this code
        for (LendingGroup group : groups.values()) {
            // Check if code matches the single-use invite code
            if (group.hasActiveInviteCode() && trimmedCode.equals(group.getInviteCode())) {
                // Valid single-use code found!
                // Add player as member
                if (!group.hasMember(playerName)) {
                    GroupMember newMember = new GroupMember(playerName, "member");
                    group.addMember(newMember);
                }

                // Mark code as used (this voids/clears the code)
                group.markGroupCodeUsed(playerName);

                // Set as active group
                setCurrentGroupId(group.getId());
                saveGroups();

                log.info("useInviteCode: {} joined group '{}' using single-use invite code", playerName, group.getName());
                return group.getId();
            }

            // Check if code matches clan code (if enabled - multi-use)
            if (group.isClanCodeEnabled() && trimmedCode.equals(group.getClanCode())) {
                // Add player as member
                if (!group.hasMember(playerName)) {
                    GroupMember newMember = new GroupMember(playerName, "member");
                    group.addMember(newMember);
                }

                // Increment clan code usage count
                group.setClanCodeUseCount(group.getClanCodeUseCount() + 1);

                // Set as active group
                setCurrentGroupId(group.getId());
                saveGroups();

                log.info("useInviteCode: {} joined group '{}' using clan code", playerName, group.getName());
                return group.getId();
            }
        }

        log.info("useInviteCode: Invalid or expired code '{}' for player {}", trimmedCode, playerName);
        return null;
    }

    /**
     * Add a member directly (staff invite without code)
     * Only owners/admins can use this
     */
    public boolean inviteMemberDirectly(String groupId, String inviterName, String targetName) {
        LendingGroup group = groups.get(groupId);
        if (group == null) return false;

        // Check if inviter is owner or admin
        if (!isOwner(groupId, inviterName) && !isAdmin(groupId, inviterName)) {
            return false; // Only staff can direct invite
        }

        // Check if target is already a member
        if (group.hasMember(targetName)) {
            return false; // Already a member
        }

        // Add as regular member
        GroupMember newMember = new GroupMember(targetName, "member");
        group.addMember(newMember);
        saveGroups();
        return true;
    }

    // ==================== WEBHOOK URL METHODS ====================

    /**
     * Get the Discord webhook URL for a group
     */
    public String getGroupWebhookUrl(String groupId) {
        if (groupId == null) return null;
        String key = "group_webhook_" + groupId;
        return configManager.getConfiguration(CFG_GROUP, key);
    }

    /**
     * Set the Discord webhook URL for a group
     */
    public void setGroupWebhookUrl(String groupId, String webhookUrl) {
        if (groupId == null) return;
        String key = "group_webhook_" + groupId;
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            configManager.unsetConfiguration(CFG_GROUP, key);
        } else {
            configManager.setConfiguration(CFG_GROUP, key, webhookUrl);
        }
        log.info("Set webhook URL for group {}: {}", groupId, webhookUrl != null ? "[URL set]" : "[cleared]");
    }
}
