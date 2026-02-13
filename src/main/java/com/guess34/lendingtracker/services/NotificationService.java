package com.guess34.lendingtracker.services;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.StoredNotification;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NotificationService - Handles all notification types for the Lending Tracker plugin
 *
 * UPDATED: Now supports 3-party messaging system:
 * 1. Initiator sees confirmation in chat (private)
 * 2. Target gets stored notification (shown in panel + overlay)
 * 3. Group sees broadcast in game chat (if enabled in config)
 */
@Slf4j
@Singleton
public class NotificationService
{
	private static final String NOTIFICATION_PREFIX = "storedNotification.";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Notifier notifier;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private LendingTrackerConfig config;

	// Callback interface for notification changes
	private Runnable notificationChangeCallback;

	/**
	 * Set callback to be notified when notifications change
	 */
	public void setNotificationChangeCallback(Runnable callback)
	{
		this.notificationChangeCallback = callback;
	}

	/**
	 * Notify that notifications have changed
	 */
	private void notifyChange()
	{
		if (notificationChangeCallback != null)
		{
			notificationChangeCallback.run();
		}
	}

	// ==================== STORED NOTIFICATION METHODS ====================

	/**
	 * Store a notification for a target player
	 * This will be shown when the target player opens the plugin or as an overlay
	 */
	public void storeNotification(StoredNotification notification)
	{
		if (notification == null || notification.getToPlayer() == null)
		{
			log.debug("Cannot store null notification or notification without target player");
			return;
		}

		String key = NOTIFICATION_PREFIX + notification.getToPlayer().toLowerCase() + "." + notification.getId();
		configManager.setConfiguration("lendingtracker", key, notification.serialize());

		// FIXED: Always track notification ID so it persists across sessions
		trackNotificationId(notification.getToPlayer(), notification.getId());

		log.info("Stored notification for {}: {}", notification.getToPlayer(), notification.getDisplayText());
		notifyChange();
	}

	/**
	 * Get all notifications for a specific player
	 */
	public List<StoredNotification> getNotificationsForPlayer(String playerName)
	{
		if (playerName == null)
		{
			return new ArrayList<>();
		}

		List<StoredNotification> notifications = new ArrayList<>();
		String prefix = NOTIFICATION_PREFIX + playerName.toLowerCase() + ".";

		// Get all config keys starting with our prefix
		String allKeys = configManager.getConfiguration("lendingtracker", "allNotificationKeys." + playerName.toLowerCase());
		if (allKeys != null && !allKeys.isEmpty())
		{
			for (String id : allKeys.split(","))
			{
				String data = configManager.getConfiguration("lendingtracker", prefix + id);
				if (data != null)
				{
					StoredNotification notification = StoredNotification.deserialize(data);
					if (notification != null)
					{
						notifications.add(notification);
					}
				}
			}
		}
		else
		{
			// Fallback: scan all keys (less efficient)
			// Note: ConfigManager doesn't have a getAll method, so we track keys separately
		}

		// Sort by timestamp (newest first)
		notifications.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
		return notifications;
	}

	/**
	 * Get unread notification count for a player
	 */
	public int getUnreadCount(String playerName)
	{
		return (int) getNotificationsForPlayer(playerName).stream()
			.filter(n -> !n.isRead())
			.count();
	}

	/**
	 * Mark a notification as read (with timestamp for read receipt)
	 */
	public void markAsRead(String playerName, String notificationId)
	{
		if (playerName == null || notificationId == null)
		{
			return;
		}

		String key = NOTIFICATION_PREFIX + playerName.toLowerCase() + "." + notificationId;
		String data = configManager.getConfiguration("lendingtracker", key);
		if (data != null)
		{
			StoredNotification notification = StoredNotification.deserialize(data);
			if (notification != null)
			{
				// Use markAsRead() which sets both read flag and timestamp
				notification.markAsRead();
				configManager.setConfiguration("lendingtracker", key, notification.serialize());
				log.info("Marked notification {} as read at {}", notificationId, notification.getReadTimestamp());
				notifyChange();
			}
		}
	}

	/**
	 * Mark all notifications as read for a player
	 */
	public void markAllAsRead(String playerName)
	{
		for (StoredNotification notification : getNotificationsForPlayer(playerName))
		{
			markAsRead(playerName, notification.getId());
		}
	}

	/**
	 * Clear all notifications for a player (for testing/reset)
	 */
	public void clearAllNotifications(String playerName)
	{
		if (playerName == null)
		{
			return;
		}

		List<StoredNotification> notifications = getNotificationsForPlayer(playerName);
		for (StoredNotification notification : notifications)
		{
			String key = NOTIFICATION_PREFIX + playerName.toLowerCase() + "." + notification.getId();
			configManager.unsetConfiguration("lendingtracker", key);
		}

		// Clear tracking key
		String trackingKey = "allNotificationKeys." + playerName.toLowerCase();
		configManager.unsetConfiguration("lendingtracker", trackingKey);

		log.info("Cleared all {} notifications for player: {}", notifications.size(), playerName);
		notifyChange();
	}

	/**
	 * Delete a notification
	 */
	public void deleteNotification(String playerName, String notificationId)
	{
		if (playerName == null || notificationId == null)
		{
			return;
		}

		String key = NOTIFICATION_PREFIX + playerName.toLowerCase() + "." + notificationId;
		configManager.unsetConfiguration("lendingtracker", key);

		// Update tracking key
		String trackingKey = "allNotificationKeys." + playerName.toLowerCase();
		String allKeys = configManager.getConfiguration("lendingtracker", trackingKey);
		if (allKeys != null)
		{
			String newKeys = java.util.Arrays.stream(allKeys.split(","))
				.filter(k -> !k.equals(notificationId))
				.collect(Collectors.joining(","));
			if (newKeys.isEmpty())
			{
				configManager.unsetConfiguration("lendingtracker", trackingKey);
			}
			else
			{
				configManager.setConfiguration("lendingtracker", trackingKey, newKeys);
			}
		}

		log.info("Deleted notification {} for {}", notificationId, playerName);
		notifyChange();
	}

	/**
	 * Store a borrow request notification for the lender
	 * Note: storeNotification now handles tracking, so just delegate
	 */
	public void storeBorrowRequestNotification(StoredNotification notification)
	{
		storeNotification(notification);
		// trackNotificationId is now called by storeNotification()
	}

	/**
	 * Store a lend offer notification for the requester
	 * Note: storeNotification now handles tracking, so just delegate
	 */
	public void storeLendOfferNotification(StoredNotification notification)
	{
		storeNotification(notification);
		// trackNotificationId is now called by storeNotification()
	}

	/**
	 * Track notification ID for a player (for efficient retrieval)
	 * FIXED: Now checks for duplicates to avoid double-tracking
	 */
	private void trackNotificationId(String playerName, String notificationId)
	{
		String trackingKey = "allNotificationKeys." + playerName.toLowerCase();
		String allKeys = configManager.getConfiguration("lendingtracker", trackingKey);
		if (allKeys == null || allKeys.isEmpty())
		{
			allKeys = notificationId;
		}
		else
		{
			// FIXED: Check if already tracked to avoid duplicates
			if (!allKeys.contains(notificationId))
			{
				allKeys = allKeys + "," + notificationId;
			}
			else
			{
				return; // Already tracked
			}
		}
		configManager.setConfiguration("lendingtracker", trackingKey, allKeys);
	}

	// ==================== GROUP BROADCAST METHODS ====================

	/**
	 * Broadcast a message to all online group members via game chat
	 * Uses FRIENDSCHAT message type to mimic clan-style messaging
	 */
	public void broadcastToGroup(String groupName, String message)
	{
		if (!config.enableGroupAnnouncements())
		{
			log.debug("Group announcements disabled in config, skipping broadcast");
			return;
		}

		String fullMessage = String.format("[%s] %s", groupName, message);

		clientThread.invokeLater(() -> {
			// Add as a friendschat-style message so it looks like a group announcement
			client.addChatMessage(
				ChatMessageType.FRIENDSCHAT,
				"Lending",
				fullMessage,
				groupName
			);
		});

		log.info("Broadcast to group {}: {}", groupName, message);
	}

	/**
	 * Broadcast a borrow request to the group
	 */
	public void broadcastBorrowRequest(String groupName, String requester, String lender, String itemName, int quantity, int durationDays)
	{
		String message = String.format("%s requested to borrow %s (x%d) from %s for %d days",
			requester, itemName, quantity, lender, durationDays);
		broadcastToGroup(groupName, message);
	}

	/**
	 * Broadcast a lend offer to the group
	 */
	public void broadcastLendOffer(String groupName, String lender, String requester, String itemName, int quantity, String durationDisplay)
	{
		String message = String.format("%s offered to lend %s (x%d) to %s for %s",
			lender, itemName, quantity, requester, durationDisplay);
		broadcastToGroup(groupName, message);
	}

	// ==================== EXISTING NOTIFICATION METHODS ====================

	public void sendLendingNotification(LendingEntry entry, String message)
	{
		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	public void sendReturnNotification(LendingEntry entry)
	{
		String itemName = entry.getItemName();
		String playerName = entry.getPlayerName();
		long value = entry.getValue();

		String message = String.format("%s has returned your %s (Value: %s gp)",
			playerName,
			itemName,
			QuantityFormatter.quantityToStackSize(value));

		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	public void sendOverdueNotification(LendingEntry entry)
	{
		String itemName = entry.getItemName();
		String playerName = entry.getPlayerName();
		long overdueDays = ChronoUnit.DAYS.between(
			Instant.ofEpochMilli(entry.getDueDate()),
			Instant.now()
		);

		String message = String.format("OVERDUE: %s still has your %s (%d days overdue)",
			playerName,
			itemName,
			overdueDays);

		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	public void sendRiskWarning(String playerName, int riskLevel, String reason)
	{
		String message = String.format("WARNING: %s has risk level %d - %s",
			playerName,
			riskLevel,
			reason);

		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	public void sendCollateralNotification(String playerName, String collateralInfo)
	{
		String message = String.format("Collateral received from %s: %s",
			playerName,
			collateralInfo);

		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	public void sendGroupNotification(String groupName, String message)
	{
		String fullMessage = String.format("[%s] %s", groupName, message);

		if (config.enableNotifications())
		{
			notifier.notify(fullMessage);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(fullMessage);
		}
	}

	public void sendPartyUpdate(String partyMember, String action, LendingEntry entry)
	{
		String message = String.format("Party: %s %s %s to/from %s",
			partyMember,
			action,
			entry.getItemName(),
			entry.getPlayerName());

		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	public void sendDailyReport(int activeLoans, long totalValue, int overdueCount)
	{
		String message = String.format("Daily Report: %d active loans worth %s gp (%d overdue)",
			activeLoans,
			QuantityFormatter.quantityToStackSize(totalValue),
			overdueCount);

		if (config.enableNotifications())
		{
			notifier.notify(message);
		}

		if (config.enableChatNotifications())
		{
			sendChatMessage(message);
		}
	}

	private void sendChatMessage(String message)
	{
		String formattedMessage = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Lending Tracker] ")
			.append(ChatColorType.NORMAL)
			.append(message)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(formattedMessage)
			.build());
	}

	public void sendTestNotification()
	{
		sendChatMessage("Lending Tracker notifications are enabled!");
		if (config.enableNotifications())
		{
			notifier.notify("Lending Tracker notifications are enabled!");
		}
	}

	public void sendClanNotification(String message)
	{
		String formattedMessage = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Lending] ")
			.append(ChatColorType.NORMAL)
			.append(message)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CLAN_MESSAGE)
			.runeLiteFormattedMessage(formattedMessage)
			.build());
	}
}
