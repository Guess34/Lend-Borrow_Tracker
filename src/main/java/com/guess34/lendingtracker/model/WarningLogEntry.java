package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WarningLogEntry {
    private long timestamp;
    private String type;
    private String player;
    private String item;
    private String action;
    private boolean agreed;
    private String groupId;
    private String notes;
    
    public WarningLogEntry(long timestamp, String type, String player, 
                           String item, String action, boolean agreed, String groupId) {
        this.timestamp = timestamp;
        this.type = type;
        this.player = player;
        this.item = item;
        this.action = action;
        this.agreed = agreed;
        this.groupId = groupId;
        this.notes = "";
    }
}
