package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.model.BorrowRequest;
import com.guess34.lendingtracker.model.BorrowRequest.BorrowRequestStatus;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.group.GroupConfigStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class BorrowRequestService {
    
    private static final String CONFIG_KEY_REQUESTS = "borrow_requests_";
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private GroupConfigStore groupConfigStore;
    
    @Inject
    private Client client;
    
    private final Gson gson;
    
    public BorrowRequestService() {
        this.gson = new Gson();
    }
    
    /**
     * Result of submitting a borrow request
     */
    public static class SubmitResult {
        public final boolean success;
        public final String errorMessage;
        public final BorrowRequest request;

        public SubmitResult(boolean success, String errorMessage, BorrowRequest request) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.request = request;
        }

        public static SubmitResult success(BorrowRequest request) {
            return new SubmitResult(true, null, request);
        }

        public static SubmitResult failure(String errorMessage) {
            return new SubmitResult(false, errorMessage, null);
        }
    }

    /**
     * Submit a new borrow request (compliant - stored locally, messages sent manually)
     */
    public SubmitResult submitBorrowRequest(BorrowRequest request) {
        if (!isValidRequest(request)) {
            if (request == null) {
                return SubmitResult.failure("Request cannot be null");
            }
            if (request.getRequesterId() == null || request.getRequesterId().isEmpty()) {
                return SubmitResult.failure("Requester ID is missing");
            }
            if (request.getGroupId() == null || request.getGroupId().isEmpty()) {
                return SubmitResult.failure("Group ID is missing. Please select a group.");
            }
            if (request.getItemName() == null || request.getItemName().isEmpty()) {
                return SubmitResult.failure("Item name is required");
            }
            if (request.getQuantity() <= 0) {
                return SubmitResult.failure("Quantity must be greater than 0");
            }
            return SubmitResult.failure("Invalid request: Missing required fields");
        }

        // Verify requester is in the group
        LendingGroup group = groupConfigStore.getGroup(request.getGroupId());
        if (group == null) {
            log.warn("Request submitted for non-existent group: {}", request.getGroupId());
            return SubmitResult.failure("Group does not exist. Please select a valid group.");
        }

        boolean requesterInGroup = group.getMembers().stream()
            .anyMatch(member -> member.getName().equals(request.getRequesterId()));

        if (!requesterInGroup) {
            log.warn("Borrow request submitted by member not in group");
            return SubmitResult.failure("You are not a member of this group. Join the group first.");
        }

        // Store request locally
        List<BorrowRequest> requests = getRequestsForGroup(request.getGroupId());

        // Remove any existing pending requests for the same item by the same user
        requests.removeIf(existing ->
            existing.getRequesterId().equals(request.getRequesterId()) &&
            existing.getItemName().equals(request.getItemName()) &&
            existing.isPending()
        );

        requests.add(request);
        saveRequestsForGroup(request.getGroupId(), requests);

        log.info("Borrow request submitted for {} by {} in group {}",
                request.getItemName(), request.getRequesterId(), request.getGroupId());

        return SubmitResult.success(request);
    }

    // ADDED: Convenience method to create and submit a borrow request
    public SubmitResult createRequest(String groupId, String requesterName, String ownerName,
                                     String itemName, int itemId, int quantity, int world)
    {
        BorrowRequest request = new BorrowRequest();
        request.setRequesterId(requesterName);
        request.setGroupId(groupId);
        request.setItemName(itemName);
        request.setItemId(itemId);
        request.setQuantity(quantity);
        request.setWorld(world);
        request.setMessage(""); // Optional message
        request.setCollateralOffered(""); // Optional collateral
        request.setCollateralValue(0);

        return submitBorrowRequest(request);
    }

    /**
     * Get all active requests for a group
     */
    public List<BorrowRequest> getActiveRequestsForGroup(String groupId) {
        return getRequestsForGroup(groupId).stream()
            .filter(request -> request.getStatus() == BorrowRequestStatus.PENDING && !request.isExpired())
            .sorted((a, b) -> Long.compare(b.getRequestDate(), a.getRequestDate()))
            .collect(Collectors.toList());
    }
    
    /**
     * Get all requests for a specific member
     */
    public List<BorrowRequest> getRequestsByMember(String memberId, String groupId) {
        return getRequestsForGroup(groupId).stream()
            .filter(request -> request.getRequesterId().equals(memberId))
            .sorted((a, b) -> Long.compare(b.getRequestDate(), a.getRequestDate()))
            .collect(Collectors.toList());
    }
    
    /**
     * Accept a borrow request
     */
    public boolean acceptRequest(String requestId, String responderId, String responseMessage) {
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<BorrowRequest> requests = getRequestsForGroup(group.getId());
            
            Optional<BorrowRequest> requestOpt = requests.stream()
                .filter(req -> req.getId().equals(requestId) && req.isPending())
                .findFirst();
                
            if (requestOpt.isPresent()) {
                BorrowRequest request = requestOpt.get();
                
                // Verify responder is in the group
                boolean responderInGroup = group.getMembers().stream()
                    .anyMatch(member -> member.getName().equals(responderId));
                    
                if (!responderInGroup) {
                    return false;
                }
                
                request.accept(responderId, responseMessage);
                saveRequestsForGroup(group.getId(), requests);
                
                log.info("Borrow request {} accepted by {}", requestId, responderId);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Decline a borrow request
     */
    public boolean declineRequest(String requestId, String responderId, String responseMessage) {
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<BorrowRequest> requests = getRequestsForGroup(group.getId());
            
            Optional<BorrowRequest> requestOpt = requests.stream()
                .filter(req -> req.getId().equals(requestId) && req.isPending())
                .findFirst();
                
            if (requestOpt.isPresent()) {
                BorrowRequest request = requestOpt.get();
                
                // Verify responder is in the group
                boolean responderInGroup = group.getMembers().stream()
                    .anyMatch(member -> member.getName().equals(responderId));
                    
                if (!responderInGroup) {
                    return false;
                }
                
                request.decline(responderId, responseMessage);
                saveRequestsForGroup(group.getId(), requests);
                
                log.info("Borrow request {} declined by {}", requestId, responderId);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Mark request as completed
     */
    public boolean completeRequest(String requestId) {
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<BorrowRequest> requests = getRequestsForGroup(group.getId());
            
            Optional<BorrowRequest> requestOpt = requests.stream()
                .filter(req -> req.getId().equals(requestId) && 
                              req.getStatus() == BorrowRequestStatus.ACCEPTED)
                .findFirst();
                
            if (requestOpt.isPresent()) {
                BorrowRequest request = requestOpt.get();
                request.complete();
                saveRequestsForGroup(group.getId(), requests);
                
                log.info("Borrow request {} marked as completed", requestId);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Cancel a request (only by the requester)
     */
    public boolean cancelRequest(String requestId, String requesterId) {
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<BorrowRequest> requests = getRequestsForGroup(group.getId());
            
            requests.removeIf(request -> 
                request.getId().equals(requestId) && 
                request.getRequesterId().equals(requesterId) &&
                request.isPending()
            );
            
            saveRequestsForGroup(group.getId(), requests);
            log.info("Borrow request {} cancelled by {}", requestId, requesterId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Clean up expired requests
     */
    public void cleanupExpiredRequests() {
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<BorrowRequest> requests = getRequestsForGroup(group.getId());
            
            List<BorrowRequest> activeRequests = requests.stream()
                .filter(request -> {
                    if (request.isExpired() && request.getStatus() == BorrowRequestStatus.PENDING) {
                        request.setStatus(BorrowRequestStatus.EXPIRED);
                    }
                    // Keep requests for 7 days after expiration for history
                    return (Instant.now().toEpochMilli() - request.getRequestDate()) < (7 * 24 * 60 * 60 * 1000);
                })
                .collect(Collectors.toList());
            
            if (activeRequests.size() != requests.size()) {
                saveRequestsForGroup(group.getId(), activeRequests);
            }
        }
    }
    
    /**
     * Get formatted message for a borrow request (for manual sending)
     */
    public String formatRequestMessage(BorrowRequest request) {
        StringBuilder message = new StringBuilder();
        message.append("BORROW REQUEST\n");
        message.append("Item: ").append(request.getItemName());
        if (request.getQuantity() > 1) {
            message.append(" x").append(request.getQuantity());
        }
        message.append("\n");
        
        if (request.getMessage() != null && !request.getMessage().isEmpty()) {
            message.append("Message: ").append(request.getMessage()).append("\n");
        }
        
        if (request.getCollateralOffered() != null && !request.getCollateralOffered().isEmpty()) {
            message.append("Collateral: ").append(request.getCollateralOffered());
            if (request.getCollateralValue() > 0) {
                message.append(" (").append(request.getCollateralValue()).append(" GP)");
            }
            message.append("\n");
        }
        
        message.append("Expires: ").append(request.getTimeRemaining());
        message.append("\nRequest ID: ").append(request.getId().substring(0, 8));
        
        return message.toString();
    }
    
    /**
     * Get formatted response message
     */
    public String formatResponseMessage(BorrowRequest request, boolean accepted, String customMessage) {
        StringBuilder message = new StringBuilder();
        if (accepted) {
            message.append("REQUEST ACCEPTED\n");
        } else {
            message.append("REQUEST DECLINED\n");
        }
        
        message.append("Item: ").append(request.getItemName());
        if (request.getQuantity() > 1) {
            message.append(" x").append(request.getQuantity());
        }
        message.append("\n");
        
        if (customMessage != null && !customMessage.isEmpty()) {
            message.append("Message: ").append(customMessage).append("\n");
        }
        
        message.append("Request ID: ").append(request.getId().substring(0, 8));
        
        return message.toString();
    }
    
    /**
     * Get summary statistics
     */
    public RequestStatistics getStatistics(String groupId) {
        List<BorrowRequest> allRequests = getRequestsForGroup(groupId);
        
        RequestStatistics stats = new RequestStatistics();
        stats.totalRequests = allRequests.size();
        stats.pendingRequests = (int) allRequests.stream()
            .filter(req -> req.getStatus() == BorrowRequestStatus.PENDING && !req.isExpired())
            .count();
        stats.acceptedRequests = (int) allRequests.stream()
            .filter(req -> req.getStatus() == BorrowRequestStatus.ACCEPTED)
            .count();
        stats.completedRequests = (int) allRequests.stream()
            .filter(req -> req.getStatus() == BorrowRequestStatus.COMPLETED)
            .count();
        
        return stats;
    }
    
    /**
     * Private helper methods
     */
    private List<BorrowRequest> getRequestsForGroup(String groupId) {
        String configKey = CONFIG_KEY_REQUESTS + groupId;
        String requestsJson = configManager.getConfiguration("lendingtracker", configKey);
        
        if (requestsJson == null || requestsJson.isEmpty()) {
            return new ArrayList<>();
        }
        
        try {
            Type listType = new TypeToken<List<BorrowRequest>>(){}.getType();
            List<BorrowRequest> requests = gson.fromJson(requestsJson, listType);
            return requests != null ? requests : new ArrayList<>();
        } catch (Exception e) {
            log.error("Failed to parse borrow requests for group {}", groupId, e);
            return new ArrayList<>();
        }
    }
    
    private void saveRequestsForGroup(String groupId, List<BorrowRequest> requests) {
        String configKey = CONFIG_KEY_REQUESTS + groupId;
        String requestsJson = gson.toJson(requests);
        configManager.setConfiguration("lendingtracker", configKey, requestsJson);
    }
    
    private boolean isValidRequest(BorrowRequest request) {
        return request != null &&
               request.getRequesterId() != null && !request.getRequesterId().isEmpty() &&
               request.getGroupId() != null && !request.getGroupId().isEmpty() &&
               request.getItemName() != null && !request.getItemName().isEmpty() &&
               request.getQuantity() > 0;
    }
    
    /**
     * Request statistics class
     */
    public static class RequestStatistics {
        public int totalRequests;
        public int pendingRequests;
        public int acceptedRequests;
        public int completedRequests;
        
        public double getAcceptanceRate() {
            if (totalRequests == 0) return 0.0;
            return (double) (acceptedRequests + completedRequests) / totalRequests * 100;
        }
    }
}