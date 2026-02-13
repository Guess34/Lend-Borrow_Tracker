package net.runelite.client.plugins.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LendingGroup {
    private String id;
    private String name;
    private String description;
    private long createdDate;
    private String inviteCode; // Single-use group code (one per person)
    private String clanCode; // Multi-use clan code
    private boolean clanCodeEnabled = true;
    private Set<String> usedGroupCodes = new HashSet<>(); // Track who used group codes
    private int clanCodeUseCount = 0; // Track clan code usage
    private List<GroupMember> members = new ArrayList<>();
    private List<PendingInvite> pendingInvites = new ArrayList<>();
    private Map<String, Object> settings = new HashMap<>();
    private List<String> notes = new ArrayList<>();
    private Map<String, Long> stats = new HashMap<>();

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
        this.createdDate = System.currentTimeMillis();
        // FIXED: No auto-generated codes - codes are generated on demand from group settings
        this.inviteCode = null;
        this.clanCode = null;
        this.clanCodeEnabled = false;
        this.usedGroupCodes = new HashSet<>();
        this.clanCodeUseCount = 0;
        this.members = new ArrayList<>();
        this.pendingInvites = new ArrayList<>();
        this.settings = new HashMap<>();
        this.notes = new ArrayList<>();
        this.stats = new HashMap<>();
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

    public void regenerateGroupCode() {
        this.inviteCode = generateFormattedCode();
        // Clear usedGroupCodes since this is a new code
        this.usedGroupCodes.clear();
    }

    public void regenerateClanCode() {
        this.clanCode = generateFormattedCode();
        this.clanCodeUseCount = 0; // Reset counter for new code
    }

    public boolean hasUsedGroupCode(String playerName) {
        return usedGroupCodes.contains(playerName.toLowerCase());
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
    }
    
    public void removeMember(String memberName) {
        members.removeIf(m -> m.getName().equalsIgnoreCase(memberName));
    }
    
    public boolean hasMember(String memberName) {
        return members.stream()
            .anyMatch(m -> m.getName().equalsIgnoreCase(memberName));
    }
    
    public GroupMember getMember(String memberName) {
        return members.stream()
            .filter(m -> m.getName().equalsIgnoreCase(memberName))
            .findFirst()
            .orElse(null);
    }
    
    public void addNote(String note) {
        notes.add(note);
        // Keep only last 100 notes
        if (notes.size() > 100) {
            notes.remove(0);
        }
    }
    
    public void updateStat(String key, long value) {
        stats.put(key, value);
    }
}