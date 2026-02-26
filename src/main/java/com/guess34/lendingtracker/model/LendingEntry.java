package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Core lending data model.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LendingEntry {

    // Core identity
    private String id;

    // Players
    private String lender;
    private String borrower;

    // Item
    private String item;
    private int itemId;
    private int quantity;

    // Value / collateral
    private long value;
    private Integer collateralValue;
    private String collateralType;
    private String collateralItems;
    private boolean agreedNoCollateral;

    // Group / party
    private String groupId;

    // Time fields (epoch millis)
    private long lendTime;
    private long dueTime;
    private long returnedAt;

    // Misc
    private String notes;

    // Convenience

    public long getDueDate() { return this.dueTime; }
    public long getLendDate() { return this.lendTime; }
    public boolean isReturned() { return this.returnedAt > 0; }

    public boolean isOverdue() {
        return returnedAt == 0 && dueTime > 0 && System.currentTimeMillis() > dueTime;
    }

    public LendingEntry(LendingEntry other) {
        if (other == null) return;
        this.id = other.id;
        this.lender = other.lender;
        this.borrower = other.borrower;
        this.item = other.item;
        this.itemId = other.itemId;
        this.quantity = other.quantity;
        this.value = other.value;
        this.collateralValue = other.collateralValue;
        this.collateralType = other.collateralType;
        this.collateralItems = other.collateralItems;
        this.agreedNoCollateral = other.agreedNoCollateral;
        this.groupId = other.groupId;
        this.lendTime = other.lendTime;
        this.dueTime = other.dueTime;
        this.returnedAt = other.returnedAt;
        this.notes = other.notes;
    }

    public String getPlayerName() { return borrower != null ? borrower : lender; }
    public String getItemName() { return item; }
    public void setItemName(String name) { this.item = name; }
}
