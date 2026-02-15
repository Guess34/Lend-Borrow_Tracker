package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.util.QuantityFormatter;

import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class DiscordWebhookService {
    
    private static final String CONFIG_KEY_WEBHOOK_URL = "discord_webhook_url_";
    private static final String CONFIG_KEY_WEBHOOK_ENABLED = "discord_webhook_enabled_";
    private static final String CONFIG_KEY_WEBHOOK_EVENTS = "discord_webhook_events_";
    
    @Inject
    private ConfigManager configManager;

    @Inject
    private GroupConfigStore groupConfigStore;

    @Inject
    private LendingTrackerConfig config;

    @Inject
    private WebhookRateLimiter rateLimiter;

    @Inject
    private WebhookAuditLogger auditLogger;

    @Inject
    private Client client;

    @Inject
    private OnlineStatusService onlineStatusService;

    @Inject
    private OkHttpClient httpClient;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private Gson gson;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    
    /**
     * Set Discord webhook URL for a specific group.
     * Each group has its own webhook URL stored separately.
     */
    public void setWebhookUrl(String groupId, String webhookUrl) {
        if (groupId == null) return;
        configManager.setConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_URL + groupId, webhookUrl);
        log.info("Webhook URL {} for group {}", webhookUrl != null && !webhookUrl.isEmpty() ? "set" : "cleared", groupId);
    }

    /**
     * Get Discord webhook URL for a specific group.
     * Returns null if no webhook is configured for this group.
     */
    public String getWebhookUrl(String groupId) {
        if (groupId == null) return null;
        return configManager.getConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_URL + groupId);
    }

    /**
     * Clear webhook URL for a group.
     * Called when user leaves or is kicked from a group.
     */
    public void clearWebhookUrl(String groupId) {
        if (groupId == null) return;
        configManager.unsetConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_URL + groupId);
        configManager.unsetConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_ENABLED + groupId);
        configManager.unsetConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_EVENTS + groupId);
        log.info("Cleared all webhook settings for group {}", groupId);
    }

    /**
     * Enable/disable webhook notifications for a group
     */
    public void setWebhookEnabled(String groupId, boolean enabled) {
        if (groupId == null) return;
        configManager.setConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_ENABLED + groupId, String.valueOf(enabled));
    }

    /**
     * Check if webhooks are enabled - now uses config setting
     */
    public boolean isWebhookEnabled(String groupId) {
        // CHANGED: Use main config toggle
        return config.enableDiscordWebhooks();
    }

    /**
     * Set which events trigger webhooks for a group (legacy)
     */
    public void setWebhookEvents(String groupId, String events) {
        // Legacy method - events are now set via config checkboxes
        configManager.setConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_EVENTS + groupId, events);
    }

    /**
     * Get webhook events configuration - now uses config settings
     */
    public String getWebhookEvents(String groupId) {
        // CHANGED: Build events string from consolidated config checkboxes
        StringBuilder events = new StringBuilder();
        if (config.webhookTransactionEvents()) {
            events.append("lending,returning,");
        }
        if (config.webhookAlertEvents()) {
            events.append("overdue,risk,");
        }
        if (config.webhookActivityEvents()) {
            events.append("member_join,member_leave,borrow_request,");
        }
        return events.toString();
    }

    /**
     * Check if a specific event type is enabled in config
     */
    // CHANGED: Consolidated webhook event checks to match simplified config
    public boolean isEventEnabled(String eventType) {
        switch (eventType) {
            case "lending":
            case "returning":
                return config.webhookTransactionEvents();
            case "overdue":
            case "risk":
                return config.webhookAlertEvents();
            case "member_join":
            case "member_leave":
            case "borrow_request":
                return config.webhookActivityEvents();
            default:
                return false;
        }
    }
    
    /**
     * Send lending notification to Discord
     */
    public void sendLendingNotification(String groupId, LendingEntry entry) {
        if (!shouldSendEvent(groupId, "lending")) {
            return;
        }

        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) return;

        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0x00FF00) // Green
            .setTitle("📦 Item Lent")
            .addField("Group", group.getName(), true)
            .addField("Lender", entry.getLender(), true)
            .addField("Borrower", entry.getBorrower(), true)
            .addField("Item", entry.getItem() + " x" + entry.getQuantity(), false)
            .addField("Value", QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP", true)
            .addField("Due Date", formatDueDate(entry.getDueTime()), true)
            .setTimestamp(Instant.now());

        sendWebhook(groupId, entry.getLender(), "lending", entry.getId(), embed);
    }
    
    /**
     * Send return notification to Discord
     */
    public void sendReturnNotification(String groupId, LendingEntry entry) {
        if (!shouldSendEvent(groupId, "returning")) {
            return;
        }
        
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) return;
        
        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0x0099FF) // Blue
            .setTitle("✅ Item Returned")
            .addField("Group", group.getName(), true)
            .addField("Borrower", entry.getBorrower(), true)
            .addField("Lender", entry.getLender(), true)
            .addField("Item", entry.getItem() + " x" + entry.getQuantity(), false)
            .addField("Value", QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP", true)
            .addField("Loan Duration", calculateLoanDuration(entry), true)
            .setTimestamp(Instant.now());

        sendWebhook(groupId, entry.getBorrower(), "returning", entry.getId(), embed);
    }
    
    /**
     * Send overdue notification to Discord
     */
    public void sendOverdueNotification(String groupId, LendingEntry entry, int daysOverdue) {
        if (!shouldSendEvent(groupId, "overdue")) {
            return;
        }
        
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) return;
        
        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0xFF9900) // Orange
            .setTitle("⚠️ Overdue Loan Alert")
            .addField("Group", group.getName(), true)
            .addField("Borrower", entry.getBorrower(), true)
            .addField("Lender", entry.getLender(), true)
            .addField("Item", entry.getItem() + " x" + entry.getQuantity(), false)
            .addField("Value", QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP", true)
            .addField("Days Overdue", String.valueOf(daysOverdue), true)
            .addField("Due Date", formatDueDate(entry.getDueTime()), true)
            .setTimestamp(Instant.now());

        sendWebhook(groupId, entry.getLender(), "overdue", entry.getId(), embed);
    }
    
    /**
     * Send risk warning to Discord
     */
    public void sendRiskWarning(String groupId, String playerName, String riskType, String description) {
        if (!shouldSendEvent(groupId, "risk")) {
            return;
        }
        
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) return;
        
        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0xFF0000) // Red
            .setTitle("🚨 Risk Alert")
            .addField("Group", group.getName(), true)
            .addField("Player", playerName, true)
            .addField("Risk Type", riskType.replace("_", " "), true)
            .addField("Description", description, false)
            .setTimestamp(Instant.now());

        sendWebhook(groupId, playerName, "risk", riskType + ":" + playerName, embed);
    }
    
    /**
     * Send member join notification to Discord
     */
    public void sendMemberJoinNotification(String groupId, String memberName, String invitedBy) {
        if (!shouldSendEvent(groupId, "member_join")) {
            return;
        }
        
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) return;
        
        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0x00CC00) // Green
            .setTitle("👋 New Member Joined")
            .addField("Group", group.getName(), true)
            .addField("New Member", memberName, true)
            .addField("Invited By", invitedBy, true)
            .addField("Total Members", String.valueOf(group.getMembers().size()), true)
            .setTimestamp(Instant.now());

        sendWebhook(groupId, memberName, "member_join", memberName, embed);
    }
    
    /**
     * Send member leave notification to Discord
     */
    public void sendMemberLeaveNotification(String groupId, String memberName, String reason) {
        if (!shouldSendEvent(groupId, "member_leave")) {
            return;
        }
        
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) return;
        
        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0xFF6600) // Orange
            .setTitle("👋 Member Left")
            .addField("Group", group.getName(), true)
            .addField("Member", memberName, true)
            .addField("Reason", reason, true)
            .addField("Remaining Members", String.valueOf(group.getMembers().size()), true)
            .setTimestamp(Instant.now());

        sendWebhook(groupId, memberName, "member_leave", memberName, embed);
    }
    
    /**
     * Test webhook connection
     */
    public CompletableFuture<Boolean> testWebhook(String groupId) {
        LendingGroup group = groupConfigStore.getGroup(groupId);
        String groupName = group != null ? group.getName() : "Unknown Group";

        DiscordEmbed embed = new DiscordEmbed()
            .setColor(0x0099FF)
            .setTitle("🔧 Webhook Test")
            .addField("Group", groupName, true)
            .addField("Status", "Connection successful!", false)
            .setTimestamp(Instant.now());

        return sendWebhookAsync(groupId, embed);
    }

    /**
     * Send a test message directly to a webhook URL (for testing before saving)
     */
    public void sendTestMessage(String webhookUrl, String groupName) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("Cannot send test message: no webhook URL provided");
            return;
        }

        executor.submit(() -> {
            try {
                DiscordEmbed embed = new DiscordEmbed()
                    .setColor(0x0099FF)
                    .setTitle("🔧 Webhook Test")
                    .addField("Group", groupName != null ? groupName : "Unknown Group", true)
                    .addField("Status", "Connection successful!", false)
                    .addField("Plugin", "LendingTracker", true)
                    .setTimestamp(Instant.now());

                DiscordWebhook webhook = new DiscordWebhook()
                    .addEmbed(embed);

                String jsonPayload = gson.toJson(webhook);

                RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, jsonPayload);
                Request request = new Request.Builder()
                    .url(webhookUrl)
                    .post(body)
                    .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log.info("Discord webhook test successful for group: {}", groupName);
                    } else {
                        log.warn("Discord webhook test failed with status {}: {}",
                            response.code(), response.body() != null ? response.body().string() : "");
                    }
                }
            } catch (Exception e) {
                log.error("Failed to send Discord webhook test message", e);
            }
        });
    }
    
    /**
     * Check if a specific event type should trigger webhook
     */
    private boolean shouldSendEvent(String groupId, String eventType) {
        // Check if webhooks are enabled globally
        if (!config.enableDiscordWebhooks()) {
            return false;
        }

        // Check if webhook URL is configured
        String webhookUrl = getWebhookUrl(groupId);
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return false;
        }

        // Check if this specific event type is enabled
        if (!isEventEnabled(eventType)) {
            return false;
        }

        // Check if current user has permission to send webhooks (prevents duplicates)
        if (!hasWebhookPermission(groupId)) {
            log.debug("User does not have webhook permission for group {}, skipping to prevent duplicates", groupId);
            return false;
        }

        return true;
    }

    /**
     * Check if the current user has permission to send webhooks based on their role.
     * This prevents duplicate webhooks when multiple group members have webhooks enabled.
     */
    private boolean hasWebhookPermission(String groupId) {
        LendingTrackerConfig.WebhookSenderRole requiredRole = config.webhookRequireRole();

        // If set to ANYONE, always allow (may cause duplicates)
        if (requiredRole == LendingTrackerConfig.WebhookSenderRole.ANYONE) {
            return true;
        }

        // Get current player name
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            log.debug("Cannot determine local player for webhook permission check");
            return false;
        }
        String currentPlayer = localPlayer.getName();
        if (currentPlayer == null || currentPlayer.isEmpty()) {
            return false;
        }

        // Verify group exists
        LendingGroup group = groupConfigStore.getGroup(groupId);
        if (group == null) {
            return false;
        }

        // Get current user's role
        String userRole = groupConfigStore.getMemberRole(groupId, currentPlayer);
        if (userRole == null) {
            log.debug("User {} has no role in group {}", currentPlayer, groupId);
            return false;
        }

        int userRank = GroupConfigStore.getRoleRank(userRole);

        // Handle HIGHEST_ONLINE - only send if you're the highest-ranked online member
        if (requiredRole == LendingTrackerConfig.WebhookSenderRole.HIGHEST_ONLINE) {
            return isHighestRankedOnline(groupId, currentPlayer, userRank, group);
        }

        // Check against required role for static role options
        switch (requiredRole) {
            case OWNER_ONLY:
                // Only owner can send (rank 4+)
                return userRank >= 4 || groupConfigStore.isOwner(groupId, currentPlayer);

            case ADMIN:
                // Admin+ can send (rank 3+)
                return userRank >= 3 || groupConfigStore.isAdmin(groupId, currentPlayer);

            case MODERATOR:
                // Moderator+ can send (rank 2+)
                return userRank >= 2;

            case MEMBER:
                // Any member can send (rank 1+)
                return userRank >= 1;

            default:
                return false;
        }
    }

    /**
     * Check if the current player is the highest-ranked online member.
     * This ensures only ONE person sends the webhook, preventing duplicates.
     *
     * Algorithm:
     * 1. Check if any higher-ranked member is online - if so, don't send
     * 2. If same rank, use alphabetical ordering to break ties deterministically
     * 3. This way all clients agree on who should send without communication
     */
    private boolean isHighestRankedOnline(String groupId, String currentPlayer, int currentRank, LendingGroup group) {
        if (group.getMembers() == null) {
            return true; // No members list, allow sending
        }

        for (com.guess34.lendingtracker.model.GroupMember member : group.getMembers()) {
            String memberName = member.getName();
            if (memberName == null || memberName.equalsIgnoreCase(currentPlayer)) {
                continue; // Skip self
            }

            // Check if this member is online
            if (!onlineStatusService.isPlayerOnline(memberName)) {
                continue; // Skip offline members
            }

            int memberRank = GroupConfigStore.getRoleRank(member.getRole());

            // If someone with higher rank is online, they should send instead
            if (memberRank > currentRank) {
                log.debug("Higher ranked member {} (rank {}) is online, skipping webhook", memberName, memberRank);
                return false;
            }

            // If same rank, use alphabetical order (first alphabetically wins)
            if (memberRank == currentRank) {
                if (memberName.compareToIgnoreCase(currentPlayer) < 0) {
                    log.debug("Same ranked member {} comes first alphabetically, skipping webhook", memberName);
                    return false;
                }
            }
        }

        // No higher-ranked or alphabetically-prior same-ranked online member found
        log.debug("Player {} is highest ranked online, will send webhook", currentPlayer);
        return true;
    }
    
    /**
     * Send webhook with rate limiting.
     * Adds footer showing who sent the webhook for audit purposes.
     */
    private void sendWebhook(String groupId, String rsn, String eventType, String eventData, DiscordEmbed embed) {
        // Check if Discord webhooks are enabled in config
        if (!config.enableDiscordWebhooks()) {
            log.debug("Discord webhooks disabled in config, skipping notification");
            return;
        }

        // Check rate limit BEFORE submitting to executor
        WebhookRateLimiter.RateLimitResult rateLimitResult = rateLimiter.checkLimit(groupId, rsn, eventType, eventData);
        if (!rateLimitResult.isAllowed()) {
            log.warn("Webhook rate limited for {} in group {}: {}", rsn, groupId, rateLimitResult.getReason());
            return;
        }

        // Add footer showing who sent this webhook (for audit purposes)
        String senderName = getCurrentPlayerName();
        if (senderName != null) {
            embed.setFooter("Sent by: " + senderName);
        }

        executor.submit(() -> {
            try {
                boolean success = sendWebhookSync(groupId, embed);
                // Log to audit trail
                auditLogger.logAttempt(groupId, rsn, eventType, success, success ? null : "Webhook send failed", eventData);
            } catch (Exception e) {
                log.error("Failed to send Discord webhook for group {}", groupId, e);
                auditLogger.logAttempt(groupId, rsn, eventType, false, e.getMessage(), eventData);
            }
        });
    }

    /**
     * Get the current player's name
     */
    private String getCurrentPlayerName() {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer != null) {
            return localPlayer.getName();
        }
        return null;
    }
    
    /**
     * Send webhook asynchronously and return result
     */
    private CompletableFuture<Boolean> sendWebhookAsync(String groupId, DiscordEmbed embed) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendWebhookSync(groupId, embed);
            } catch (Exception e) {
                log.error("Failed to send Discord webhook for group {}", groupId, e);
                return false;
            }
        }, executor);
    }
    
    /**
     * Send webhook synchronously
     */
    private boolean sendWebhookSync(String groupId, DiscordEmbed embed) throws IOException {
        String webhookUrl = getWebhookUrl(groupId);
        if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
            return false;
        }

        DiscordWebhook webhook = new DiscordWebhook()
            .addEmbed(embed);

        String jsonPayload = gson.toJson(webhook);

        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, jsonPayload);
        Request request = new Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.debug("Discord webhook sent successfully for group {}", groupId);
                return true;
            } else {
                log.warn("Discord webhook failed for group {} with status {}: {}",
                    groupId, response.code(), response.body() != null ? response.body().string() : "");
                return false;
            }
        }
    }
    
    /**
     * Format due date for display
     */
    private String formatDueDate(long dueTime) {
        if (dueTime <= 0) {
            return "No due date";
        }
        return "<t:" + (dueTime / 1000) + ":R>"; // Discord timestamp format
    }
    
    /**
     * Calculate loan duration
     */
    private String calculateLoanDuration(LendingEntry entry) {
        long lendTime = entry.getLendTime();
        long returnTime = Instant.now().toEpochMilli();
        long durationHours = (returnTime - lendTime) / (1000 * 60 * 60);
        
        if (durationHours < 24) {
            return durationHours + " hours";
        } else {
            long days = durationHours / 24;
            return days + " days";
        }
    }
    
    /**
     * Shutdown - no-op since we use RuneLite's shared executor and OkHttpClient
     */
    public void shutdown() {
    }
    
    /**
     * Discord webhook payload structure
     */
    private static class DiscordWebhook {
        private java.util.List<DiscordEmbed> embeds = new java.util.ArrayList<>();
        
        public DiscordWebhook addEmbed(DiscordEmbed embed) {
            embeds.add(embed);
            return this;
        }
    }
    
    /**
     * Discord embed structure
     */
    private static class DiscordEmbed {
        private String title;
        private String description;
        private int color;
        private String timestamp;
        private java.util.List<DiscordField> fields = new java.util.ArrayList<>();
        private DiscordFooter footer;

        public DiscordEmbed setTitle(String title) {
            this.title = title;
            return this;
        }

        public DiscordEmbed setDescription(String description) {
            this.description = description;
            return this;
        }

        public DiscordEmbed setColor(int color) {
            this.color = color;
            return this;
        }

        public DiscordEmbed setTimestamp(Instant timestamp) {
            this.timestamp = timestamp.toString();
            return this;
        }

        public DiscordEmbed addField(String name, String value, boolean inline) {
            fields.add(new DiscordField(name, value, inline));
            return this;
        }

        public DiscordEmbed setFooter(String text) {
            this.footer = new DiscordFooter(text);
            return this;
        }
    }

    /**
     * Discord footer structure
     */
    private static class DiscordFooter {
        private final String text;

        public DiscordFooter(String text) {
            this.text = text;
        }
    }
    
    /**
     * Check if webhook security is enabled for a group
     * @param groupId The group ID
     * @return true if security is enabled (always returns false for now - security is always optional)
     */
    public boolean isSecurityEnabled(String groupId) {
        // Webhook security is optional - tokens can be used but aren't required
        // Return false to indicate security is not enforced
        return false;
    }

    /**
     * Enable or disable webhook security for a group
     * @param groupId The group ID
     * @param enabled Whether to enable security
     */
    public void setSecurityEnabled(String groupId, boolean enabled) {
        // Webhook security is optional and controlled via token existence
        // No action needed here - security is managed by WebhookTokenService
        log.debug("Webhook security toggle requested for group {}: {}", groupId, enabled);
    }

    /**
     * Discord field structure
     */
    private static class DiscordField {
        private final String name;
        private final String value;
        private final boolean inline;

        public DiscordField(String name, String value, boolean inline) {
            this.name = name;
            this.value = value;
            this.inline = inline;
        }
    }
}