package net.runelite.client.plugins.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMember {
    private String name;
    private String role; // owner, admin, moderator, member
    private long joinedDate;
    private boolean active;
    
    public GroupMember(String name, String role) {
        this.name = name;
        this.role = role;
        this.joinedDate = System.currentTimeMillis();
        this.active = true;
    }
}