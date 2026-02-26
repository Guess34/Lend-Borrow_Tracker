package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * ItemSet - A collection of items that can be lent/borrowed together as a set.
 * Examples: Full armor sets, gear loadouts, boss gear packages, etc.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemSet {

    private String id;
    private String name;
    private String description;
    private String groupId;
    private List<ItemSetEntry> items = new ArrayList<>();

    /**
     * Inner class representing a single item in the set
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemSetEntry {
        private int itemId;
        private String itemName;
        private int quantity;
        private long value; // Per-item value

        public ItemSetEntry(int itemId, String itemName, int quantity) {
            this.itemId = itemId;
            this.itemName = itemName;
            this.quantity = quantity;
            this.value = 0;
        }
    }
}
