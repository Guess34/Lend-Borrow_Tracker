package net.runelite.client.plugins.lendingtracker.services;

import net.runelite.client.plugins.lendingtracker.model.PlayerReputation;
import net.runelite.client.plugins.lendingtracker.model.RiskLevel;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class RiskAnalyzer {
    private final Map<String, PlayerReputation> reputations = new ConcurrentHashMap<>();
    private final Map<String, Integer> riskScores = new ConcurrentHashMap<>();
    private final Map<String, String> riskReasons = new ConcurrentHashMap<>();
    private final Map<String, Long> defaultAmounts = new ConcurrentHashMap<>();
    
    @Inject
    private Recorder recorder;
    
    public int analyzePlayer(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return RiskLevel.HIGH.getValue();
        }
        
        String nameLower = playerName.toLowerCase();
        int trustScore = 0; // Positive points = good standing
        StringBuilder reason = new StringBuilder();
        
        // Check reputation
        PlayerReputation rep = reputations.get(nameLower);
        if (rep != null) {
            int totalTransactions = rep.getSuccessfulReturns() + rep.getLateReturns() + rep.getFailedReturns();
            int successRate = totalTransactions > 0 ? (rep.getSuccessfulReturns() * 100) / totalTransactions : 0;
            
            if (successRate >= 95 && totalTransactions >= 5) {
                trustScore += 3;
                reason.append("Excellent reputation (").append(successRate).append("% success rate). ");
            } else if (successRate >= 80 && totalTransactions >= 3) {
                trustScore += 2;
                reason.append("Good reputation (").append(successRate).append("% success rate). ");
            } else if (successRate >= 60 && totalTransactions >= 2) {
                trustScore += 1;
                reason.append("Fair reputation (").append(successRate).append("% success rate). ");
            } else if (totalTransactions > 0) {
                // Poor reputation - no trust bonus
                reason.append("Poor reputation (").append(successRate).append("% success rate). ");
            }
            
            // Penalize defaults heavily
            if (rep.getDefaultCount() > 0) {
                trustScore -= (rep.getDefaultCount() * 2); // Heavy penalty
                reason.append("Previous defaults: ").append(rep.getDefaultCount()).append(". ");
            }
        } else {
            // New player - neutral (no bonus, no penalty)
            reason.append("New player (no lending history). ");
        }
        
        // Penalize high outstanding amounts (risk of losing too much)
        long outstandingValue = recorder.getTotalValueLentToPlayer(playerName);
        if (outstandingValue > 10000000) { // 10M
            trustScore -= 2;
            reason.append("Very high outstanding debt (").append(formatValue(outstandingValue)).append("). ");
        } else if (outstandingValue > 5000000) { // 5M
            trustScore -= 1;
            reason.append("High outstanding debt (").append(formatValue(outstandingValue)).append("). ");
        }
        
        // Penalize overdue items
        long overdueCount = recorder.getEntriesForPlayer(playerName).stream()
            .filter(entry -> entry.isOverdue() && !entry.isReturned())
            .count();
        if (overdueCount > 0) {
            trustScore -= overdueCount;
            reason.append("Currently overdue items: ").append(overdueCount).append(". ");
        }
        
        // Convert trust score to risk level (higher trust = lower risk)
        int riskLevel;
        if (trustScore >= 3) {
            riskLevel = RiskLevel.NONE.getValue();
        } else if (trustScore >= 1) {
            riskLevel = RiskLevel.LOW.getValue();
        } else if (trustScore >= -1) {
            riskLevel = RiskLevel.MEDIUM.getValue();
        } else if (trustScore >= -3) {
            riskLevel = RiskLevel.HIGH.getValue();
        } else {
            riskLevel = RiskLevel.CRITICAL.getValue();
        }
        
        // Store results
        riskScores.put(nameLower, riskLevel);
        riskReasons.put(nameLower, reason.toString());
        
        return riskLevel;
    }
    
    private String formatValue(long value) {
        if (value >= 1000000) {
            return (value / 1000000) + "M gp";
        } else if (value >= 1000) {
            return (value / 1000) + "K gp";
        }
        return value + " gp";
    }
    
    public String getRiskReason(String playerName) {
        return riskReasons.getOrDefault(playerName.toLowerCase(), "No specific risks identified");
    }
    
    public void recordReturn(String playerName, boolean onTime) {
        if (playerName == null) return;
        
        String nameLower = playerName.toLowerCase();
        PlayerReputation rep = reputations.computeIfAbsent(nameLower, 
            k -> new PlayerReputation(playerName));
        
        if (onTime) {
            rep.incrementSuccessful();
        } else {
            rep.incrementLate();
        }
        
        rep.setLastInteraction(System.currentTimeMillis());
    }
    
    public void recordDefault(String playerName, long amount) {
        if (playerName == null) return;
        
        String nameLower = playerName.toLowerCase();
        PlayerReputation rep = reputations.computeIfAbsent(nameLower, 
            k -> new PlayerReputation(playerName));
        
        rep.incrementDefaultCount();
        rep.incrementFailed();
        rep.setLastInteraction(System.currentTimeMillis());
        
        defaultAmounts.merge(nameLower, amount, Long::sum);
    }
    
    public PlayerReputation getReputation(String playerName) {
        if (playerName == null) return null;
        return reputations.get(playerName.toLowerCase());
    }
    
    public void clearHistory() {
        reputations.clear();
        riskScores.clear();
        riskReasons.clear();
        defaultAmounts.clear();
    }
}