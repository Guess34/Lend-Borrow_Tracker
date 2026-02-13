package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ItemSet - A collection of items that can be lent/borrowed together as a set.
 * Examples: Full armor sets, gear loadouts, boss gear packages, etc.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemSet {

    // Unique identifier for this set
    private String id;

    // Display name for the set (e.g., "Bandos Set", "Max Melee Gear")
    private String name;

    // Optional description
    private String description;

    // Owner who created this set
    private String ownerName;

    // Group this set belongs to
    private String groupId;

    // List of items in this set
    private List<ItemSetEntry> items = new ArrayList<>();

    // Total value of all items in the set (calculated)
    private long totalValue;

    // Collateral settings for the entire set
    private Integer collateralValue;
    private String collateralType; // "gp", "items", "none", "percentage"
    private int collateralPercentage; // If collateralType is "percentage"

    // Lending terms
    private int defaultDurationDays = 7; // Default loan duration
    private String notes;

    // Status
    private boolean available = true; // Is this set available for lending?
    private String currentBorrower; // If lent out, who has it?
    private long lentAt; // When was it lent?
    private long dueAt; // When is it due back?

    // Timestamps
    private long createdAt;
    private long updatedAt;

    /**
     * Create a new ItemSet with generated ID
     */
    public ItemSet(String name, String ownerName, String groupId) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.ownerName = ownerName;
        this.groupId = groupId;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.items = new ArrayList<>();
    }

    /**
     * Add an item to this set
     */
    public void addItem(ItemSetEntry item) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(item);
        recalculateTotalValue();
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Remove an item from this set
     */
    public boolean removeItem(int itemId) {
        if (items == null) return false;
        boolean removed = items.removeIf(item -> item.getItemId() == itemId);
        if (removed) {
            recalculateTotalValue();
            this.updatedAt = System.currentTimeMillis();
        }
        return removed;
    }

    /**
     * Recalculate total value of all items
     */
    public void recalculateTotalValue() {
        if (items == null || items.isEmpty()) {
            this.totalValue = 0;
            return;
        }
        this.totalValue = items.stream()
            .mapToLong(item -> item.getValue() * item.getQuantity())
            .sum();
    }

    /**
     * Get the number of unique items in this set
     */
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    /**
     * Get total quantity of all items
     */
    public int getTotalQuantity() {
        if (items == null) return 0;
        return items.stream().mapToInt(ItemSetEntry::getQuantity).sum();
    }

    /**
     * Check if this set is currently lent out
     */
    public boolean isLentOut() {
        return currentBorrower != null && !currentBorrower.isEmpty();
    }

    /**
     * Check if this set is overdue
     */
    public boolean isOverdue() {
        return isLentOut() && dueAt > 0 && System.currentTimeMillis() > dueAt;
    }

    /**
     * Mark this set as lent to a borrower
     */
    public void lendTo(String borrowerName, long dueTime) {
        this.currentBorrower = borrowerName;
        this.lentAt = System.currentTimeMillis();
        this.dueAt = dueTime;
        this.available = false;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * Mark this set as returned
     */
    public void markReturned() {
        this.currentBorrower = null;
        this.lentAt = 0;
        this.dueAt = 0;
        this.available = true;
        this.updatedAt = System.currentTimeMillis();
    }

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
