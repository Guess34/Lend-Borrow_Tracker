package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LendingGroup {
    private String id;
    private String name;
    private String description;
    private String inviteCode; // Single-use group code (one per person)
    private String clanCode; // Multi-use clan code
    private boolean clanCodeEnabled = true;
    private Set<String> usedGroupCodes = new HashSet<>(); // Track who used group codes
    private int clanCodeUseCount = 0; // Track clan code usage
    // Who used the multi-use group code (lower-cased names). Unlike the raw count,
    // a name set union-merges cleanly across machines, so the owner sees joins that
    // happened on other clients. The count is kept in step for display.
    private Set<String> clanCodeUsedBy = new HashSet<>();
    // Kick tombstones: lower-cased name -> when they were removed (epoch millis).
    // Roster sync only ever ADDS members (so a stale peer can't erase a fresh
    // join); these tombstones are how a kick propagates without reopening that
    // hole. A member is dropped when a tombstone is NEWER than their joinedAt;
    // re-joining after a kick gets a fresh joinedAt and survives.
    private Map<String, Long> removedMembers = new HashMap<>();
    // CopyOnWriteArrayList: the roster is read on the EDT while sync threads may
    // add members, so reads must never throw ConcurrentModificationException.
    private List<GroupMember> members = new CopyOnWriteArrayList<>();

    // Version stamp for the roster, epoch millis. A relayed group state only
    // replaces the local roster when its stamp is newer, so a member with a
    // stale roster can't erase someone who just joined on another client.
    private long membersUpdatedAt;

    // ADDED: Shared secret for HMAC-SHA256 message signing on relay sync
    private String syncSecret;

    // Permission settings - which roles can kick members
    // Default: co-owner, admin, mod can all kick (true)
    private boolean coOwnerCanKick = true;
    private boolean adminCanKick = true;
    private boolean modCanKick = true;

    // Permission settings - which roles can generate invite codes
    // Default: co-owner and admin can generate (true), mod cannot (false)
    private boolean coOwnerCanInvite = true;
    private boolean adminCanInvite = true;
    private boolean modCanInvite = false;

    public LendingGroup(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
        // FIXED: No auto-generated codes - codes are generated on demand from group settings
        this.inviteCode = null;
        this.clanCode = null;
        this.clanCodeEnabled = false;
        this.usedGroupCodes = new HashSet<>();
        this.clanCodeUseCount = 0;
        this.members = new CopyOnWriteArrayList<>();
        // ADDED: Generate sync secret for HMAC message signing
        this.syncSecret = generateSyncSecret();
        // Default kick permissions - all staff can kick
        this.coOwnerCanKick = true;
        this.adminCanKick = true;
        this.modCanKick = true;
        // Default invite permissions - co-owner and admin can invite, mod cannot
        this.coOwnerCanInvite = true;
        this.adminCanInvite = true;
        this.modCanInvite = false;
    }

    // Generate formatted code like "ABC-123-XYZ"
    private String generateFormattedCode() {
        String uuid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return uuid.substring(0, 3) + "-" + uuid.substring(3, 6) + "-" + uuid.substring(6, 9);
    }

    /**
     * Generate a new single-use invite code.
     * This code will be voided after one person uses it.
     */
    public String generateSingleUseCode() {
        this.inviteCode = generateFormattedCode();
        // Clear previous code usage since this is a new code
        this.usedGroupCodes.clear();
        return this.inviteCode;
    }

    /**
     * Mark invite code as used by a player.
     * Single-use codes become void after use - the code is cleared.
     */
    public void markGroupCodeUsed(String playerName) {
        usedGroupCodes.add(playerName.toLowerCase());
        // FIXED: Single-use code - void the code after use
        this.inviteCode = null;
    }

    /**
     * Check if an invite code is currently active (not voided)
     */
    public boolean hasActiveInviteCode() {
        return this.inviteCode != null && !this.inviteCode.isEmpty();
    }

    public void addMember(GroupMember member) {
        if (!hasMember(member.getName())) {
            members.add(member);
        }
        // A (re)join always clears any old kick tombstone for this name, so a
        // legitimately re-invited member isn't immediately re-removed by sync.
        clearRemoval(member.getName());
    }

    public void removeMember(String memberName) {
        members.removeIf(m -> m.getName().equalsIgnoreCase(memberName));
    }

    /** Record a kick tombstone so the removal propagates across machines. */
    public void recordRemoval(String memberName) {
        if (memberName == null || memberName.isEmpty()) return;
        if (removedMembers == null) removedMembers = new HashMap<>();
        removedMembers.put(memberName.toLowerCase(), System.currentTimeMillis());
    }

    /** Drop the kick tombstone for a name (member re-joined). */
    public void clearRemoval(String memberName) {
        if (memberName == null || removedMembers == null) return;
        removedMembers.remove(memberName.toLowerCase());
    }

    /** Null-safe view of the kick tombstones (name may predate this field in saved data). */
    public Map<String, Long> getRemovedMembersSafe() {
        return removedMembers != null ? removedMembers : Collections.emptyMap();
    }

    /**
     * Record that a player used the multi-use group code. Tracked as a name set so
     * it survives cross-machine merges; the display count follows the set size
     * (same person re-joining doesn't double-count).
     */
    public void recordClanCodeUse(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        if (clanCodeUsedBy == null) clanCodeUsedBy = new HashSet<>();
        clanCodeUsedBy.add(playerName.toLowerCase());
        clanCodeUseCount = Math.max(clanCodeUseCount, clanCodeUsedBy.size());
    }

    /** Null-safe view of who used the group code. */
    public Set<String> getClanCodeUsedBySafe() {
        return clanCodeUsedBy != null ? clanCodeUsedBy : Collections.emptySet();
    }

    public boolean hasMember(String memberName) {
        return members.stream()
            .anyMatch(m -> m.getName().equalsIgnoreCase(memberName));
    }

    // ADDED: Generate a cryptographically random sync secret (32 bytes, hex-encoded)
    private static String generateSyncSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Ensure this group has a sync secret.
     * Call this on groups loaded from config that were created before HMAC was added.
     */
    public void ensureSyncSecret() {
        if (syncSecret == null || syncSecret.isEmpty()) {
            syncSecret = generateSyncSecret();
        }
    }
}
