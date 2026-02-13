package com.guess34.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskSession {
    private String id;
    private String playerName;
    private LendingEntry associatedLoan;
    private long startTime;
    private boolean isActive;
}