package net.runelite.client.plugins.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing notification messages between players
 * Stores messages locally so players can see acknowledgments and notifications
 */
@Slf4j
@Singleton
public class NotificationMessageService
{
	private static final String CONFIG_GROUP = "lendingtracker";
	private static final String CONFIG_KEY_MESSAGES = "notification_messages_";

	@Inject
	private ConfigManager configManager;

	private final Gson gson;
	private final Map<String, List<NotificationMessage>> playerMessages = new ConcurrentHashMap<>();

	public NotificationMessageService()
	{
		this.gson = new Gson();
	}

	/**
	 * Notification message data class
	 */
	public static class NotificationMessage
	{
		public String id;
		public String fromPlayer;
		public String toPlayer;
		public String message;
		public String itemName;
		public long timestamp;
		public boolean read;
		public NotificationMessageType type;
		public String groupId;      // For GROUP_INVITE: group ID
		public String groupName;    // For GROUP_INVITE: group name
		public String inviteCode;   // For GROUP_INVITE: single-use invite code

		public NotificationMessage(String fromPlayer, String toPlayer, String message, String itemName, NotificationMessageType type)
		{
			this.id = UUID.randomUUID().toString();
			this.fromPlayer = fromPlayer;
			this.toPlayer = toPlayer;
			this.message = message;
			this.itemName = itemName;
			this.timestamp = Instant.now().toEpochMilli();
			this.read = false;
			this.type = type;
		}

		/**
		 * Constructor for GROUP_INVITE type
		 */
		public NotificationMessage(String fromPlayer, String toPlayer, String groupId, String groupName, String inviteCode)
		{
			this.id = UUID.randomUUID().toString();
			this.fromPlayer = fromPlayer;
			this.toPlayer = toPlayer;
			this.groupId = groupId;
			this.groupName = groupName;
			this.inviteCode = inviteCode;
			this.message = "You've been invited to join '" + groupName + "'";
			this.itemName = groupName; // Use group name as item name for display
			this.timestamp = Instant.now().toEpochMilli();
			this.read = false;
			this.type = NotificationMessageType.GROUP_INVITE;
		}
	}

	public enum NotificationMessageType
	{
		REQUEST_ACKNOWLEDGED,  // Lender acknowledged borrow request
		REQUEST_CANCELLED,     // Requester cancelled their request
		ITEM_AVAILABLE,        // Item you requested is now available
		REQUEST_DECLINED,      // Lender declined your request
		GROUP_INVITE           // Invite to join a lending group
	}

	/**
	 * Send a notification message to a player
	 */
	public void sendMessage(String fromPlayer, String toPlayer, String message, String itemName, NotificationMessageType type)
	{
		NotificationMessage msg = new NotificationMessage(fromPlayer, toPlayer, message, itemName, type);

		// Get existing messages for this player
		List<NotificationMessage> messages = getMessagesForPlayer(toPlayer);
		messages.add(msg);

		// Store updated list
		playerMessages.put(toPlayer, messages);
		persist(toPlayer);

		log.info("Sent {} notification from {} to {} about {}", type, fromPlayer, toPlayer, itemName);
	}

	/**
	 * Send a group invite notification to a player
	 */
	public void sendGroupInvite(String fromPlayer, String toPlayer, String groupId, String groupName, String inviteCode)
	{
		NotificationMessage msg = new NotificationMessage(fromPlayer, toPlayer, groupId, groupName, inviteCode);

		// Get existing messages for this player
		List<NotificationMessage> messages = getMessagesForPlayer(toPlayer);
		messages.add(msg);

		// Store updated list
		playerMessages.put(toPlayer, messages);
		persist(toPlayer);

		log.info("Sent GROUP_INVITE notification from {} to {} for group '{}'", fromPlayer, toPlayer, groupName);
	}

	/**
	 * Get all messages for a player
	 */
	public List<NotificationMessage> getMessagesForPlayer(String playerName)
	{
		// Check cache first
		if (playerMessages.containsKey(playerName))
		{
			return new ArrayList<>(playerMessages.get(playerName));
		}

		// Load from config
		String key = CONFIG_KEY_MESSAGES + playerName;
		String json = configManager.getConfiguration(CONFIG_GROUP, key);

		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}

		try
		{
			Type listType = new TypeToken<ArrayList<NotificationMessage>>(){}.getType();
			List<NotificationMessage> messages = gson.fromJson(json, listType);
			playerMessages.put(playerName, messages);
			return new ArrayList<>(messages);
		}
		catch (Exception e)
		{
			log.error("Failed to load messages for player: {}", playerName, e);
			return new ArrayList<>();
		}
	}

	/**
	 * Get unread messages for a player
	 */
	public List<NotificationMessage> getUnreadMessages(String playerName)
	{
		List<NotificationMessage> messages = getMessagesForPlayer(playerName);
		List<NotificationMessage> unread = new ArrayList<>();

		for (NotificationMessage msg : messages)
		{
			if (!msg.read)
			{
				unread.add(msg);
			}
		}

		return unread;
	}

	/**
	 * Mark a message as read
	 */
	public void markAsRead(String playerName, String messageId)
	{
		List<NotificationMessage> messages = getMessagesForPlayer(playerName);

		for (NotificationMessage msg : messages)
		{
			if (msg.id.equals(messageId))
			{
				msg.read = true;
				break;
			}
		}

		playerMessages.put(playerName, messages);
		persist(playerName);
	}

	/**
	 * Mark all messages as read for a player
	 */
	public void markAllAsRead(String playerName)
	{
		List<NotificationMessage> messages = getMessagesForPlayer(playerName);

		for (NotificationMessage msg : messages)
		{
			msg.read = true;
		}

		playerMessages.put(playerName, messages);
		persist(playerName);
	}

	/**
	 * Delete old messages (older than 7 days)
	 */
	public void cleanupOldMessages(String playerName)
	{
		List<NotificationMessage> messages = getMessagesForPlayer(playerName);
		long cutoffTime = Instant.now().toEpochMilli() - (7 * 24 * 60 * 60 * 1000); // 7 days

		messages.removeIf(msg -> msg.timestamp < cutoffTime);

		playerMessages.put(playerName, messages);
		persist(playerName);
	}

	/**
	 * Delete a specific message by ID
	 */
	public void deleteMessage(String playerName, String messageId)
	{
		List<NotificationMessage> messages = getMessagesForPlayer(playerName);
		messages.removeIf(msg -> msg.id.equals(messageId));

		playerMessages.put(playerName, messages);
		persist(playerName);

		log.info("Deleted message {} for player {}", messageId, playerName);
	}

	/**
	 * Persist messages to config
	 */
	private void persist(String playerName)
	{
		List<NotificationMessage> messages = playerMessages.get(playerName);
		if (messages == null)
		{
			return;
		}

		String key = CONFIG_KEY_MESSAGES + playerName;
		String json = gson.toJson(messages);
		configManager.setConfiguration(CONFIG_GROUP, key, json);
	}

	/**
	 * Clear all messages for a player
	 */
	public void clearMessages(String playerName)
	{
		playerMessages.remove(playerName);
		String key = CONFIG_KEY_MESSAGES + playerName;
		configManager.unsetConfiguration(CONFIG_GROUP, key);
	}
}
