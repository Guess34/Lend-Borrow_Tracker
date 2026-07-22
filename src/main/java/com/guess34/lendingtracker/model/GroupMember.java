package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMember {
    private String name;
    private String role; // owner, admin, moderator, member

    // When this member (last) joined, epoch millis. Used against the group's
    // removed-member tombstones: a kick recorded AFTER this time removes the
    // member everywhere; a re-join after the kick gets a fresh joinedAt and
    // survives. Members saved before this field existed deserialize to 0, so
    // any tombstone wins for them (correct - they joined before any kick).
    private long joinedAt;

    public GroupMember(String name, String role) {
        this.name = name;
        this.role = role;
        this.joinedAt = System.currentTimeMillis();
    }
}
