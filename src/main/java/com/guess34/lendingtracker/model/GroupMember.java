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

}
