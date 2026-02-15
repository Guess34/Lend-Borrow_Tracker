package com.guess34.lendingtracker.model;

/**
 * Risk log entry - tracks security events within lending groups
 */
public class RiskLogEntry {
    private long timestamp;
    private String reporter;
    private String affectedPlayer;
    private String eventType;
    private String description;

    public RiskLogEntry(long timestamp, String reporter, String affectedPlayer, String eventType, String description) {
        this.timestamp = timestamp;
        this.reporter = reporter;
        this.affectedPlayer = affectedPlayer;
        this.eventType = eventType;
        this.description = description;
    }

    public long getTimestamp() { return timestamp; }
    public String getReporter() { return reporter; }
    public String getAffectedPlayer() { return affectedPlayer; }
    public String getEventType() { return eventType; }
    public String getDescription() { return description; }
}
