package net.runelite.client.plugins.lendingtracker.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerReputation {
    private String playerName;
    private int successfulReturns;
    private int lateReturns;
    private int failedReturns;
    private int defaultCount;
    private long totalValueBorrowed;
    private long totalValueReturned;
    private long lastInteraction;
    private String notes;
    
    public PlayerReputation(String playerName) {
        this.playerName = playerName;
        this.successfulReturns = 0;
        this.lateReturns = 0;
        this.failedReturns = 0;
        this.defaultCount = 0;
        this.totalValueBorrowed = 0;
        this.totalValueReturned = 0;
        this.lastInteraction = System.currentTimeMillis();
        this.notes = "";
    }
    
    public void incrementSuccessful() {
        successfulReturns++;
    }
    
    public void incrementLate() {
        lateReturns++;
    }
    
    public void incrementFailed() {
        failedReturns++;
    }
    
    public void incrementDefaultCount() {
        defaultCount++;
    }
    
    public int getFailureRate() {
        int total = successfulReturns + lateReturns + failedReturns;
        if (total == 0) return 0;
        return (failedReturns * 100) / total;
    }
    
    public int getTrustScore() {
        int score = 100;
        score -= failedReturns * 20;
        score -= lateReturns * 10;
        score -= defaultCount * 30;
        score += successfulReturns * 5;
        return Math.max(0, Math.min(100, score));
    }
}