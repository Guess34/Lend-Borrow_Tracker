package net.runelite.client.plugins.lendingtracker.services;

import net.runelite.client.plugins.lendingtracker.model.CollateralAgreement;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class CollateralManager {
    private final Map<String, CollateralAgreement> activeAgreements = new ConcurrentHashMap<>();
    private final Map<String, List<CollateralAgreement>> historyAgreements = new ConcurrentHashMap<>();
    private final Map<String, Long> collateralValues = new ConcurrentHashMap<>();
    
    @Inject
    private Recorder recorder;
    
    public void recordCollateral(CollateralAgreement agreement) {
        if (agreement == null || agreement.getAssociatedLoan() == null) {
            return;
        }
        
        String key = generateKey(agreement.getAssociatedLoan());
        activeAgreements.put(key, agreement);
        
        // Track total collateral value held
        String lender = agreement.getAssociatedLoan().getLender();
        if (lender != null && agreement.getAssociatedLoan().getCollateralValue() > 0) {
            collateralValues.merge(lender, 
                (long) agreement.getAssociatedLoan().getCollateralValue(), 
                Long::sum);
        }
    }
    
    public void releaseCollateral(String borrower, String item) {
        String key = borrower + "_" + item;
        CollateralAgreement agreement = activeAgreements.remove(key);
        
        if (agreement != null) {
            agreement.setReturned(true);
            agreement.setActive(false);
            
            // Add to history
            String lender = agreement.getAssociatedLoan().getLender();
            historyAgreements.computeIfAbsent(lender, k -> new ArrayList<>())
                .add(agreement);
            
            // Update collateral value tracking
            if (lender != null && agreement.getAssociatedLoan().getCollateralValue() > 0) {
                long currentValue = collateralValues.getOrDefault(lender, 0L);
                long newValue = Math.max(0, currentValue - agreement.getAssociatedLoan().getCollateralValue());
                collateralValues.put(lender, newValue);
            }
        }
    }
    
    public CollateralAgreement getActiveAgreement(String borrower, String item) {
        return activeAgreements.get(borrower + "_" + item);
    }
    
    public Collection<CollateralAgreement> getAllActiveAgreements() {
        return new ArrayList<>(activeAgreements.values());
    }
    
    public List<CollateralAgreement> getHistory(String lender) {
        return new ArrayList<>(historyAgreements.getOrDefault(lender, new ArrayList<>()));
    }
    
    public long getTotalCollateralHeld(String lender) {
        return collateralValues.getOrDefault(lender, 0L);
    }
    
    public boolean hasCollateral(String borrower, String item) {
        return activeAgreements.containsKey(borrower + "_" + item);
    }
    
    public void clearAll() {
        activeAgreements.clear();
        historyAgreements.clear();
        collateralValues.clear();
    }
    
    private String generateKey(LendingEntry entry) {
        return entry.getBorrower() + "_" + entry.getItem();
    }
}