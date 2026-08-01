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

    // When this member's ROLE last changed, epoch millis. Roles used to ride the
    // single whole-roster stamp, so a demoted owner whose client had not seen the
    // demotion would republish their old role and the newer roster stamp would
    // restore it - undoing the demotion for everyone. Versioning each row means a
    // stale republish loses to the change it never saw. 0 on members saved before
    // this existed, so any explicit role change beats them.
    private long roleUpdatedAt;

    public GroupMember(String name, String role) {
        this.name = name;
        this.role = role;
        this.joinedAt = System.currentTimeMillis();
        this.roleUpdatedAt = System.currentTimeMillis();
    }
}
