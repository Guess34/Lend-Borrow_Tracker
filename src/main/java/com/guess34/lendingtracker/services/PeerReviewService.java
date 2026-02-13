package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.model.PeerReview;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.group.GroupConfigStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class PeerReviewService {
    
    private static final String CONFIG_KEY_REVIEWS = "peer_reviews_";
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private GroupConfigStore groupConfigStore;
    
    private final Gson gson;
    
    public PeerReviewService() {
        this.gson = new Gson();
    }
    
    /**
     * Submit a new peer review (compliant - stores locally only)
     */
    public boolean submitReview(PeerReview review) {
        if (!review.isValid()) {
            log.warn("Invalid review submitted: {}", review);
            return false;
        }
        
        // Check if reviewer is in the same group as reviewed member
        LendingGroup group = groupConfigStore.getGroup(review.getGroupId());
        if (group == null) {
            log.warn("Review submitted for non-existent group: {}", review.getGroupId());
            return false;
        }
        
        boolean reviewerInGroup = group.getMembers().stream()
            .anyMatch(member -> member.getName().equals(review.getReviewerId()));
        boolean reviewedInGroup = group.getMembers().stream()
            .anyMatch(member -> member.getName().equals(review.getReviewedMemberId()));
            
        if (!reviewerInGroup || !reviewedInGroup) {
            log.warn("Review submitted by or for member not in group");
            return false;
        }
        
        // Prevent self-reviews
        if (review.getReviewerId().equals(review.getReviewedMemberId())) {
            log.warn("Attempted self-review blocked");
            return false;
        }
        
        // Store review locally
        List<PeerReview> reviews = getReviewsForGroup(review.getGroupId());
        reviews.add(review);
        saveReviewsForGroup(review.getGroupId(), reviews);
        
        log.info("Peer review submitted for member {} in group {}", 
                review.getReviewedMemberId(), review.getGroupId());
        return true;
    }
    
    /**
     * Get all reviews for a specific member
     */
    public List<PeerReview> getReviewsForMember(String memberId, String groupId) {
        return getReviewsForGroup(groupId).stream()
            .filter(review -> review.getReviewedMemberId().equals(memberId))
            .sorted((a, b) -> Long.compare(b.getReviewDate(), a.getReviewDate()))
            .collect(Collectors.toList());
    }
    
    /**
     * Get all reviews for a group
     */
    public List<PeerReview> getReviewsForGroup(String groupId) {
        String configKey = CONFIG_KEY_REVIEWS + groupId;
        String reviewsJson = configManager.getConfiguration("lendingtracker", configKey);
        
        if (reviewsJson == null || reviewsJson.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            Type listType = new TypeToken<List<PeerReview>>(){}.getType();
            List<PeerReview> reviews = gson.fromJson(reviewsJson, listType);
            return reviews != null ? reviews : new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to parse reviews for group {}", groupId, e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Calculate average rating for a member
     */
    public double getAverageRating(String memberId, String groupId) {
        List<PeerReview> reviews = getReviewsForMember(memberId, groupId);
        if (reviews.isEmpty()) {
            return 0.0;
        }
        
        return reviews.stream()
            .mapToInt(PeerReview::getRating)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Get rating distribution for a member
     */
    public Map<Integer, Integer> getRatingDistribution(String memberId, String groupId) {
        Map<Integer, Integer> distribution = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            distribution.put(i, 0);
        }
        
        List<PeerReview> reviews = getReviewsForMember(memberId, groupId);
        for (PeerReview review : reviews) {
            distribution.merge(review.getRating(), 1, Integer::sum);
        }
        
        return distribution;
    }
    
    /**
     * Get member reputation summary
     */
    public MemberReputation getMemberReputation(String memberId, String groupId) {
        List<PeerReview> reviews = getReviewsForMember(memberId, groupId);
        
        MemberReputation reputation = new MemberReputation();
        reputation.memberId = memberId;
        reputation.groupId = groupId;
        reputation.totalReviews = reviews.size();
        reputation.averageRating = getAverageRating(memberId, groupId);
        reputation.ratingDistribution = getRatingDistribution(memberId, groupId);
        reputation.recentReviews = reviews.stream()
            .limit(5)
            .collect(Collectors.toList());
        
        // Calculate lending vs borrowing performance
        Map<String, Double> typeRatings = reviews.stream()
            .collect(Collectors.groupingBy(
                PeerReview::getTransactionType,
                Collectors.averagingInt(PeerReview::getRating)
            ));
        
        reputation.lendingRating = typeRatings.getOrDefault("lending", 0.0);
        reputation.borrowingRating = typeRatings.getOrDefault("borrowing", 0.0);
        
        return reputation;
    }
    
    /**
     * Get top-rated members in a group
     */
    public List<MemberReputation> getTopRatedMembers(String groupId, int limit) {
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null || group.getMembers() == null) {
            return new ArrayList<>();
        }
        
        return group.getMembers().stream()
            .map(member -> getMemberReputation(member.getName(), groupId))
            .filter(rep -> rep.totalReviews > 0)
            .sorted((a, b) -> Double.compare(b.averageRating, a.averageRating))
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * Check if a member can review another member
     * (Must be in same group, cannot self-review, must have had a transaction)
     */
    public boolean canReviewMember(String reviewerId, String reviewedMemberId, String groupId) {
        if (reviewerId.equals(reviewedMemberId)) {
            return false; // No self-reviews
        }
        
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) {
            return false;
        }
        
        boolean reviewerInGroup = group.getMembers().stream()
            .anyMatch(member -> member.getName().equals(reviewerId));
        boolean reviewedInGroup = group.getMembers().stream()
            .anyMatch(member -> member.getName().equals(reviewedMemberId));
            
        return reviewerInGroup && reviewedInGroup;
    }
    
    /**
     * Delete a review (only by the reviewer)
     */
    public boolean deleteReview(String reviewId, String reviewerId) {
        // Find which group this review belongs to
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<PeerReview> reviews = getReviewsForGroup(group.getId());
            
            Optional<PeerReview> reviewToDelete = reviews.stream()
                .filter(review -> review.getId().equals(reviewId) && 
                                 review.getReviewerId().equals(reviewerId))
                .findFirst();
                
            if (reviewToDelete.isPresent()) {
                reviews.remove(reviewToDelete.get());
                saveReviewsForGroup(group.getId(), reviews);
                log.info("Review {} deleted by {}", reviewId, reviewerId);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Save reviews for a group to config
     */
    private void saveReviewsForGroup(String groupId, List<PeerReview> reviews) {
        String configKey = CONFIG_KEY_REVIEWS + groupId;
        String reviewsJson = gson.toJson(reviews);
        configManager.setConfiguration("lendingtracker", configKey, reviewsJson);
    }
    
    /**
     * Export reviews for backup
     */
    public Map<String, List<PeerReview>> exportAllReviews() {
        Map<String, List<PeerReview>> allReviews = new HashMap<>();
        
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<PeerReview> groupReviews = getReviewsForGroup(group.getId());
            if (!groupReviews.isEmpty()) {
                allReviews.put(group.getId(), groupReviews);
            }
        }
        
        return allReviews;
    }
    
    /**
     * Import reviews from backup
     */
    public void importReviews(Map<String, List<PeerReview>> reviewsData) {
        for (Map.Entry<String, List<PeerReview>> entry : reviewsData.entrySet()) {
            String groupId = entry.getKey();
            List<PeerReview> reviews = entry.getValue();
            
            // Merge with existing reviews (avoid duplicates)
            List<PeerReview> existingReviews = getReviewsForGroup(groupId);
            Set<String> existingIds = existingReviews.stream()
                .map(PeerReview::getId)
                .collect(Collectors.toSet());
            
            List<PeerReview> newReviews = reviews.stream()
                .filter(review -> !existingIds.contains(review.getId()))
                .collect(Collectors.toList());
            
            if (!newReviews.isEmpty()) {
                existingReviews.addAll(newReviews);
                saveReviewsForGroup(groupId, existingReviews);
                log.info("Imported {} new reviews for group {}", newReviews.size(), groupId);
            }
        }
    }
    
    /**
     * Member reputation summary class
     */
    public static class MemberReputation {
        public String memberId;
        public String groupId;
        public int totalReviews;
        public double averageRating;
        public double lendingRating;
        public double borrowingRating;
        public Map<Integer, Integer> ratingDistribution;
        public List<PeerReview> recentReviews;
        
        public String getDisplayRating() {
            if (totalReviews == 0) {
                return "No reviews";
            }
            return String.format("%.1f/5.0 (%d reviews)", averageRating, totalReviews);
        }
        
        public String getStarDisplay() {
            if (totalReviews == 0) {
                return "☆☆☆☆☆";
            }
            int fullStars = (int) Math.round(averageRating);
            return "★".repeat(fullStars) + "☆".repeat(5 - fullStars);
        }
    }
}