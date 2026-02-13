package net.runelite.client.plugins.lendingtracker.services;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.lendingtracker.model.GroupMember;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Singleton
public class OnlineStatusService {
    
    @Inject
    private Client client;
    
    @Inject
    private GroupConfigStore groupConfigStore;
    
    // Track online status - compliant as it uses existing game state
    private final Map<String, OnlineStatus> playerStatus = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenTimes = new ConcurrentHashMap<>();
    
    /**
     * Update online status based on visible players (RuneLite compliant)
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        if (client.getLocalPlayer() == null) {
            return;
        }
        
        long currentTime = Instant.now().toEpochMilli();
        Set<String> currentlyVisible = new HashSet<>();
        
        // Check all visible players (RuneLite standard functionality)
        for (Player player : client.getPlayers()) {
            if (player != null && player.getName() != null) {
                String playerName = player.getName();
                currentlyVisible.add(playerName);
                
                // Update status if this player is in any of our groups
                if (isPlayerInAnyGroup(playerName)) {
                    OnlineStatus status = playerStatus.getOrDefault(playerName, new OnlineStatus());
                    status.isOnline = true;
                    status.lastSeen = currentTime;
                    status.world = client.getWorld();
                    
                    playerStatus.put(playerName, status);
                    lastSeenTimes.put(playerName, currentTime);
                }
            }
        }
        
        // Mark players as offline if not seen recently (but keep recent offline status)
        for (String playerName : new HashSet<>(playerStatus.keySet())) {
            if (!currentlyVisible.contains(playerName)) {
                OnlineStatus status = playerStatus.get(playerName);
                if (status != null && status.isOnline) {
                    // Mark as offline if not seen for 5+ seconds
                    if (currentTime - status.lastSeen > 5000) {
                        status.isOnline = false;
                        playerStatus.put(playerName, status);
                    }
                }
            }
        }
        
        // Clean up very old entries (older than 1 hour)
        playerStatus.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().lastSeen > 3600000);
        lastSeenTimes.entrySet().removeIf(entry ->
            currentTime - entry.getValue() > 3600000);
    }
    
    /**
     * Get online status for a player
     */
    public OnlineStatus getPlayerStatus(String playerName) {
        OnlineStatus status = playerStatus.get(playerName);
        if (status == null) {
            status = new OnlineStatus();
            status.isOnline = false;
            status.lastSeen = 0;
            status.world = -1;
        }
        return status;
    }
    
    /**
     * Check if player is currently online (visible in game world)
     */
    public boolean isPlayerOnline(String playerName) {
        OnlineStatus status = playerStatus.get(playerName);
        return status != null && status.isOnline;
    }
    
    /**
     * Get last seen time for a player
     */
    public long getLastSeenTime(String playerName) {
        return lastSeenTimes.getOrDefault(playerName, 0L);
    }
    
    /**
     * Get formatted last seen string
     */
    public String getLastSeenFormatted(String playerName) {
        long lastSeen = getLastSeenTime(playerName);
        if (lastSeen == 0) {
            return "Never seen";
        }
        
        long timeDiff = Instant.now().toEpochMilli() - lastSeen;
        
        if (timeDiff < 60000) { // Less than 1 minute
            return "Just now";
        } else if (timeDiff < 3600000) { // Less than 1 hour
            return (timeDiff / 60000) + " minutes ago";
        } else if (timeDiff < 86400000) { // Less than 1 day
            return (timeDiff / 3600000) + " hours ago";
        } else {
            return (timeDiff / 86400000) + " days ago";
        }
    }
    
    /**
     * Get status indicator string
     */
    public String getStatusIndicator(String playerName) {
        if (isPlayerOnline(playerName)) {
            OnlineStatus status = getPlayerStatus(playerName);
            return "🟢 Online (W" + status.world + ")";
        } else {
            long lastSeen = getLastSeenTime(playerName);
            if (lastSeen == 0) {
                return "⚫ Unknown";
            } else {
                long timeDiff = Instant.now().toEpochMilli() - lastSeen;
                if (timeDiff < 300000) { // Less than 5 minutes
                    return "🟡 Recently online";
                } else {
                    return "🔴 Offline";
                }
            }
        }
    }
    
    /**
     * Get online status for all group members
     */
    public Map<String, OnlineStatus> getGroupMemberStatuses(String groupId) {
        Map<String, OnlineStatus> statuses = new HashMap<>();
        
        var group = groupConfigStore.getGroup(groupId);
        if (group != null && group.getMembers() != null) {
            for (GroupMember member : group.getMembers()) {
                statuses.put(member.getName(), getPlayerStatus(member.getName()));
            }
        }
        
        return statuses;
    }
    
    /**
     * Get count of online members in a group
     */
    public int getOnlineMemberCount(String groupId) {
        return (int) getGroupMemberStatuses(groupId).values().stream()
            .filter(status -> status.isOnline)
            .count();
    }
    
    /**
     * Check if a player is in any of our groups
     */
    private boolean isPlayerInAnyGroup(String playerName) {
        return groupConfigStore.getAllGroups().stream()
            .filter(group -> group.getMembers() != null)
            .flatMap(group -> group.getMembers().stream())
            .anyMatch(member -> member.getName().equals(playerName));
    }

    /**
     * ADDED: Manually set a player as online (for testing purposes)
     * This allows test/fake players to appear online without being in the game world
     */
    public void setPlayerOnline(String playerName, boolean online, int world) {
        OnlineStatus status = playerStatus.getOrDefault(playerName, new OnlineStatus());
        status.isOnline = online;
        status.lastSeen = Instant.now().toEpochMilli();
        status.world = world;
        playerStatus.put(playerName, status);
        lastSeenTimes.put(playerName, status.lastSeen);
        log.debug("Manually set player {} online status to {} (world {})", playerName, online, world);
    }

    /**
     * Online status data class
     */
    public static class OnlineStatus {
        public boolean isOnline = false;
        public long lastSeen = 0;
        public int world = -1;
        public long sessionStart = 0;
        
        public OnlineStatus() {
            this.sessionStart = Instant.now().toEpochMilli();
        }
        
        public String getFormattedLastSeen() {
            if (lastSeen == 0) {
                return "Never";
            }
            
            long timeDiff = Instant.now().toEpochMilli() - lastSeen;
            
            if (timeDiff < 60000) {
                return "Just now";
            } else if (timeDiff < 3600000) {
                return (timeDiff / 60000) + "m ago";
            } else if (timeDiff < 86400000) {
                return (timeDiff / 3600000) + "h ago";
            } else {
                return (timeDiff / 86400000) + "d ago";
            }
        }
        
        public String getStatusColor() {
            if (isOnline) {
                return "#00FF00"; // Green
            } else if (lastSeen > 0 && (Instant.now().toEpochMilli() - lastSeen) < 300000) {
                return "#FFFF00"; // Yellow - recently online
            } else {
                return "#FF0000"; // Red - offline
            }
        }
    }
}