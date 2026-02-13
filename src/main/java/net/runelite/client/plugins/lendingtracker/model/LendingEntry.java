package net.runelite.client.plugins.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backward-compatible LendingEntry model.
 * Exposes the getters/setters used across panel & services.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LendingEntry {

    // Core identity
    private String partyMember;
    private String id;

    // Players
    private String lender;              // lender name
    private String borrower;            // borrower name

    // Item
    private String item;                // item display/name
    private int itemId;
    private int quantity;

    // Value / collateral
    private long value;                 // optional item value
    private Integer collateralValue;    // nullable (GP)
    private String collateralType;      // "gp", "items", "none", etc.
    private String collateralItems;     // CSV or text list of items
    private boolean agreedNoCollateral; // explicit no-collateral agreement

    // Group / party
    private String groupId;             // active group id

    // Time fields (epoch millis)
    private long lendTime;              // when lent
    private long dueTime;               // when due
    private long returnedAt;            // when returned (0 = not returned)

    // Two-party return confirmation
    private boolean lenderConfirmedReturn;   // lender confirmed return
    private boolean borrowerConfirmedReturn; // borrower confirmed return

    // Misc
    private String notes;

    // ---------------- Convenience / compatibility ----------------

    /** Legacy alias: some callers set due time by minutes from now. */
    public void setDueTime(int minutesFromNow) {
        this.dueTime = System.currentTimeMillis() + minutesFromNow * 60_000L;
    }
    public long getDueDate() {
        return this.dueTime; // epoch millis
    }

    /** Legacy alias used by panel/overlays */
    public long getLendDate() {
        return this.lendTime; // epoch millis
    }

    /** Legacy flag used by panel/plugin */
    public boolean isReturned() {
        return this.returnedAt > 0;
    }

    /** Party integration aliases used by panel/party listener */
    public String getPartyMember() {
        return this.partyMember;
    }

    public void setPartyMember(String partyMember) {
        this.partyMember = partyMember;
    }

    /** Overload so callers can set absolute epoch due-time without int cast */
    public void setDueTime(long epochMillis) {
        this.dueTime = epochMillis;
    }
    
    /** Legacy helper used by the panel for highlighting late loans. */
    public boolean isOverdue() {
        return returnedAt == 0 && dueTime > 0 && System.currentTimeMillis() > dueTime;
    }

    /** Check if both parties have confirmed return */
    public boolean isBothPartiesConfirmedReturn() {
        return lenderConfirmedReturn && borrowerConfirmedReturn;
    }

    /** Check if pending confirmation from other party */
    public boolean isPendingReturnConfirmation() {
        return (lenderConfirmedReturn || borrowerConfirmedReturn) && !isBothPartiesConfirmedReturn();
    }

    /** Copy-constructor used by Recorder. */
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
        this.lenderConfirmedReturn = other.lenderConfirmedReturn;
        this.borrowerConfirmedReturn = other.borrowerConfirmedReturn;
        this.notes = other.notes;
    }

    // ---- Optional legacy aliases to ease transitions (safe no-ops) ----
    public String getPlayerName() { return borrower != null ? borrower : lender; }
    public void setPlayerName(String name) { this.borrower = name; }

    public String getItemName() { return item; }
    public void setItemName(String name) { this.item = name; }
}