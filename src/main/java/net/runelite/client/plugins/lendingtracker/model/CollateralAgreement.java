package net.runelite.client.plugins.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollateralAgreement {
    private LendingEntry associatedLoan;
    private String collateralDescription;
    private long agreementTime;
    private boolean isReturned;
    private boolean isActive;
}
