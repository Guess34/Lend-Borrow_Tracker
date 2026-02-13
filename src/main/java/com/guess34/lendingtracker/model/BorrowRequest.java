package com.guess34.lendingtracker.model;

import lombok.Data;
import java.time.Instant;

@Data
public class BorrowRequest {
    private String id;
    private String requesterId;
    private String groupId;
    private String itemName;
    private int itemId;
    private int quantity;
    private int world; // ADDED: World number where requester is located
    private String message;
    private String collateralOffered;
    private long collateralValue;
    private long requestDate;
    private long expirationDate;
    private BorrowRequestStatus status;
    
    // Response tracking
    private String responderId; // Who responded to the request
    private String responseMessage;
    private long responseDate;
    
    public BorrowRequest() {
        this.id = java.util.UUID.randomUUID().toString();
        this.requestDate = Instant.now().toEpochMilli();
        this.expirationDate = this.requestDate + (24 * 60 * 60 * 1000); // Expires in 24 hours
        this.status = BorrowRequestStatus.PENDING;
    }
    
    public BorrowRequest(String requesterId, String groupId, String itemName, int itemId, 
                        int quantity, String message, String collateralOffered, long collateralValue) {
        this();
        this.requesterId = requesterId;
        this.groupId = groupId;
        this.itemName = itemName;
        this.itemId = itemId;
        this.quantity = quantity;
        this.message = message;
        this.collateralOffered = collateralOffered;
        this.collateralValue = collateralValue;
    }
    
    public boolean isExpired() {
        return Instant.now().toEpochMilli() > expirationDate;
    }
    
    public boolean isPending() {
        return status == BorrowRequestStatus.PENDING && !isExpired();
    }
    
    public String getFormattedRequestDate() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(requestDate));
    }
    
    public String getFormattedExpirationDate() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(expirationDate));
    }
    
    public String getTimeRemaining() {
        if (isExpired()) {
            return "Expired";
        }
        
        long timeLeft = expirationDate - Instant.now().toEpochMilli();
        long hours = timeLeft / (60 * 60 * 1000);
        long minutes = (timeLeft % (60 * 60 * 1000)) / (60 * 1000);
        
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
    
    public void accept(String responderId, String responseMessage) {
        this.status = BorrowRequestStatus.ACCEPTED;
        this.responderId = responderId;
        this.responseMessage = responseMessage;
        this.responseDate = Instant.now().toEpochMilli();
    }
    
    public void decline(String responderId, String responseMessage) {
        this.status = BorrowRequestStatus.DECLINED;
        this.responderId = responderId;
        this.responseMessage = responseMessage;
        this.responseDate = Instant.now().toEpochMilli();
    }
    
    public void complete() {
        this.status = BorrowRequestStatus.COMPLETED;
    }
    
    public enum BorrowRequestStatus {
        PENDING,
        ACCEPTED,
        DECLINED,
        COMPLETED,
        EXPIRED
    }
}