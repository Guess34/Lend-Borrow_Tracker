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
    // Machine-readable collateral: "itemId:qty,itemId:qty". Lets the guards treat
    // collateral the lender is holding like borrowed items (the lender is
    // responsible for it until the loan settles). collateralItems above is the
    // human-readable label only.
    private String collateralItemIds;
    private boolean agreedNoCollateral;

    // Group / party
    private String groupId;

    // Time fields (epoch millis)
    private long lendTime;
    private long dueTime;
    private long returnedAt;

    // Last time this row was mutated, epoch millis. Drives last-write-wins
    // reconciliation when the same entry arrives from another client via sync.
    private long updatedAt;

    // --- Running-tally return tracking ---
    // A loan settles piece by piece: the borrower owes the lent item(s) back, and
    // the lender owes the collateral back. Each completed trade decrements
    // whatever actually came home, on either side; the loan only closes when BOTH
    // sides reach zero. All three are wrappers so records saved before this
    // feature deserialize to null and the accessors below fall back to the old
    // all-or-nothing semantics — no migration, no broken saved data.
    private Integer lentOutstanding;         // qty of the lent item still with the borrower
    private String collateralOutstandingIds; // "itemId:qty,..." still held by the lender
    private Long collateralGpOutstanding;    // GP collateral still held by the lender

    // Lender chose a one-time loan: recorded and tracked like any loan, but the
    // item is never (re)listed on the marketplace when it comes home.
    private Boolean oneTime;

    // Misc
    private String notes;

    // Item set this listing belongs to, if any. Both live on the listing itself,
    // not in a separate set record: a set is simply whichever listings carry the
    // same setId, so lending one piece out delists only that piece and the rest
    // stay a set. The name rides along so peers need nothing else to draw it.
    // New fields, not a new status - clients that predate sets ignore them and
    // show the pieces as ordinary listings.
    private String setId;
    private String setName;

    // The BORROWER's client keeps this loan: the lender can't run the plugin (a
    // clan-mate on mobile, say) so theirs can't record it, track its returns or
    // post it. Exactly one client is ever the keeper - the lender's normally,
    // the borrower's for these - so nothing is counted twice. A field, not a
    // status; the merge treats it as a one-way latch because clients that predate
    // it drop it when they relay the row.
    private Boolean keptByBorrower;

    // How many of this listing the owner's client positively SAW on their own
    // back. Gear somebody is wearing can't be handed over, so the rest of the
    // group stops being shown it until they take it off.
    //
    // Only the owner's client ever writes this - nobody can see anyone else's
    // equipment. Absent means visible, and absent is NEVER repaired: unlike
    // keptByBorrower above, losing this flag only means a peer briefly sees gear
    // that is on someone's back, while re-asserting it would hide gear that is
    // actually free with no way for the owner to clear it. So there is no latch
    // here on purpose.
    private Integer wornQty;

    // How many of this listing the owner actually holds - bank, inventory and
    // worn gear - once their client has checked. The listing follows it, up as
    // well as down: list 1 and hold 5, and 5 are offered; sell 2 of 5, and 3 are.
    // Holding none hides it from the group without deleting it, and it comes back
    // the moment the item is seen again.
    //
    // Only the owner's client ever writes this; absent means "not checked", so the
    // number typed is shown. Kept across sessions - having sold something stays
    // true after logging out.
    private Integer heldQty;

    // When this loan's due date was last moved later; absent = never. Only the
    // client that keeps the loan writes it. Losing it on the way through an older
    // client only means this loan's overdue posts are keyed the way they always
    // were, so nothing needs to repair it.
    private Long dueExtendedAt;

    // Convenience

    public long getDueDate() { return this.dueTime; }
    public long getLendDate() { return this.lendTime; }
    public boolean isReturned() { return this.returnedAt > 0; }

    /** How many of this listing are on the owner's back right now. */
    public int wornQuantity() { return wornQty == null ? 0 : Math.max(0, Math.min(wornQty, shownQuantity())); }

    /** How many to show: what the owner holds once their bank has been checked, else what they typed. */
    public int shownQuantity() { return heldQty != null ? Math.max(0, heldQty) : quantity; }

    /** The value for the shown quantity - value is stored for the typed one. */
    public long shownValue() { return heldQty != null && quantity > 0 ? value * shownQuantity() / quantity : value; }

    /** The owner holds none of it, so the group doesn't see it. */
    public boolean heldNone() { return heldQty != null && heldQty <= 0; }

    /** How many could actually be handed over right now. */
    public int availableQuantity() { return Math.max(0, shownQuantity() - wornQuantity()); }

    // --- Null-safe outstanding accessors (legacy records fall back to the old
    // all-or-nothing model: everything outstanding while active, nothing after) ---

    /** Quantity of the lent item the borrower still holds. */
    public int outstandingLentQty() {
        if (lentOutstanding != null) return Math.max(0, lentOutstanding);
        return isReturned() ? 0 : Math.max(1, quantity);
    }

    /** Collateral items ("itemId:qty,...") the lender still holds. Empty = none. */
    public String outstandingCollateralIds() {
        if (collateralOutstandingIds != null) return collateralOutstandingIds;
        if (isReturned()) return "";
        return collateralItemIds != null ? collateralItemIds : "";
    }

    /** GP collateral the lender still holds. */
    public long outstandingCollateralGp() {
        if (collateralGpOutstanding != null) return Math.max(0, collateralGpOutstanding);
        if (isReturned()) return 0;
        return collateralValue != null && "GP".equals(collateralType) ? Math.max(0, collateralValue) : 0;
    }

    /**
     * Close out every running tally. Archiving sets returnedAt, but the
     * outstanding accessors only fall back to the returned-means-zero shortcut
     * when the field is NULL - so a partially-returned loan archived with 2 still
     * outstanding reported 2 forever, and anything asking "is this settled?" said
     * no for the rest of time. Call this wherever an entry is archived.
     */
    public void markSettled() {
        this.lentOutstanding = 0;
        this.collateralOutstandingIds = "";
        this.collateralGpOutstanding = 0L;
    }

    /** True when nothing is outstanding on EITHER side — item home AND collateral home. */
    public boolean isFullySettled() {
        return outstandingLentQty() == 0
            && outstandingCollateralIds().isEmpty()
            && outstandingCollateralGp() == 0;
    }

    public boolean isOneTimeLoan() { return Boolean.TRUE.equals(oneTime); }

    public boolean isInSet() { return setId != null && !setId.isEmpty(); }

    public boolean isKeptByBorrower() { return Boolean.TRUE.equals(keptByBorrower); }

    public boolean wasExtended() { return dueExtendedAt != null && dueExtendedAt > 0; }

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
        this.collateralItemIds = other.collateralItemIds;
        this.agreedNoCollateral = other.agreedNoCollateral;
        this.groupId = other.groupId;
        this.lendTime = other.lendTime;
        this.dueTime = other.dueTime;
        this.returnedAt = other.returnedAt;
        this.updatedAt = other.updatedAt;
        this.lentOutstanding = other.lentOutstanding;
        this.collateralOutstandingIds = other.collateralOutstandingIds;
        this.collateralGpOutstanding = other.collateralGpOutstanding;
        this.oneTime = other.oneTime;
        this.notes = other.notes;
        this.setId = other.setId;
        this.setName = other.setName;
        this.keptByBorrower = other.keptByBorrower;
        this.wornQty = other.wornQty;
        this.heldQty = other.heldQty;
        this.dueExtendedAt = other.dueExtendedAt;
    }

    public String getPlayerName() { return borrower != null ? borrower : lender; }
    public String getItemName() { return item; }
    public void setItemName(String name) { this.item = name; }
}
