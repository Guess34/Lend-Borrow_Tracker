package net.runelite.client.plugins.lendingtracker.model;

import lombok.Data;
import java.time.Instant;

@Data
public class PeerReview {
    private String id;
    private String reviewerId;
    private String reviewedMemberId;
    private String groupId;
    private int rating; // 1-5 stars
    private String comment;
    private String transactionType; // "lending" or "borrowing"
    private String itemInvolved;
    private long reviewDate;
    private boolean anonymous;
    
    // Constructors
    public PeerReview() {
        this.id = java.util.UUID.randomUUID().toString();
        this.reviewDate = Instant.now().toEpochMilli();
    }
    
    public PeerReview(String reviewerId, String reviewedMemberId, String groupId, 
                     int rating, String comment, String transactionType, String itemInvolved) {
        this();
        this.reviewerId = reviewerId;
        this.reviewedMemberId = reviewedMemberId;
        this.groupId = groupId;
        this.rating = Math.max(1, Math.min(5, rating)); // Clamp between 1-5
        this.comment = comment;
        this.transactionType = transactionType;
        this.itemInvolved = itemInvolved;
        this.anonymous = false;
    }
    
    // Validation
    public boolean isValid() {
        return reviewerId != null && !reviewerId.isEmpty() &&
               reviewedMemberId != null && !reviewedMemberId.isEmpty() &&
               groupId != null && !groupId.isEmpty() &&
               rating >= 1 && rating <= 5 &&
               (transactionType != null && (transactionType.equals("lending") || transactionType.equals("borrowing")));
    }
    
    // Display helpers
    public String getStarRating() {
        return "★".repeat(rating) + "☆".repeat(5 - rating);
    }
    
    public String getFormattedDate() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(reviewDate));
    }
    
    public String getDisplayReviewerId() {
        return anonymous ? "Anonymous" : reviewerId;
    }
}