package com.guess34.lendingtracker.model;

public enum RiskLevel {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);
    
    private final int value;
    
    RiskLevel(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
    
    public static RiskLevel fromValue(int value) {
        for (RiskLevel level : values()) {
            if (level.value == value) {
                return level;
            }
        }
        return NONE;
    }
}