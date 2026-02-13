package com.guess34.lendingtracker.model;

import lombok.Data;

/**
 * StoredNotification - Represents a notification that is stored for a target player
 * These are shown when the player opens the plugin panel or as an overlay popup
 */
@Data
public class StoredNotification
{
	public enum NotificationType
	{
		BORROW_REQUEST,  // Someone wants to borrow from you
		LEND_OFFER,      // Someone is offering to lend to you
		GROUP_INVITE     // Someone is inviting you to join their group
	}

	private String id;                    // Unique notification ID
	private String groupId;               // Group this notification belongs to
	private String groupName;             // Group name for display
	private NotificationType type;        // Type of notification
	private String fromPlayer;            // Who sent the notification
	private String toPlayer;              // Who receives the notification
	private String itemName;              // Item involved
	private int itemId;                   // Item ID for icon
	private int quantity;                 // Quantity requested/offered
	private int durationDays;             // Duration in days
	private String message;               // Optional message
	private long timestamp;               // When notification was created
	private boolean read;                 // Whether notification has been read/acknowledged
	private long readTimestamp;           // When notification was read (for read receipts)

	/**
	 * Create a new notification
	 */
	public StoredNotification()
	{
		this.id = java.util.UUID.randomUUID().toString();
		this.timestamp = System.currentTimeMillis();
		this.read = false;
	}

	/**
	 * Static factory for borrow request notification
	 */
	public static StoredNotification createBorrowRequest(
		String groupId, String groupName, String requester, String lender,
		String itemName, int itemId, int quantity, int durationDays)
	{
		StoredNotification notification = new StoredNotification();
		notification.setType(NotificationType.BORROW_REQUEST);
		notification.setGroupId(groupId);
		notification.setGroupName(groupName);
		notification.setFromPlayer(requester);
		notification.setToPlayer(lender);
		notification.setItemName(itemName);
		notification.setItemId(itemId);
		notification.setQuantity(quantity);
		notification.setDurationDays(durationDays);
		return notification;
	}

	/**
	 * Static factory for lend offer notification
	 */
	public static StoredNotification createLendOffer(
		String groupId, String groupName, String lender, String requester,
		String itemName, int itemId, int quantity, int durationDays, String message)
	{
		StoredNotification notification = new StoredNotification();
		notification.setType(NotificationType.LEND_OFFER);
		notification.setGroupId(groupId);
		notification.setGroupName(groupName);
		notification.setFromPlayer(lender);
		notification.setToPlayer(requester);
		notification.setItemName(itemName);
		notification.setItemId(itemId);
		notification.setQuantity(quantity);
		notification.setDurationDays(durationDays);
		notification.setMessage(message);
		return notification;
	}

	/**
	 * ADDED: Static factory for item SET borrow request notification
	 */
	public static StoredNotification createSetBorrowRequest(
		String groupId, String groupName, String requester, String owner,
		String setName, long totalValue, int itemCount, int durationDays)
	{
		StoredNotification notification = new StoredNotification();
		notification.setType(NotificationType.BORROW_REQUEST);
		notification.setGroupId(groupId);
		notification.setGroupName(groupName);
		notification.setFromPlayer(requester);
		notification.setToPlayer(owner);
		// Use setName as itemName with [SET] prefix for display
		notification.setItemName("[SET] " + setName);
		notification.setItemId(0); // No single item ID for sets
		notification.setQuantity(itemCount); // Store item count in quantity field
		notification.setDurationDays(durationDays);
		// Store total value in message for display
		notification.setMessage("Total value: " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(totalValue) + " GP");
		return notification;
	}

	/**
	 * Get a display-friendly description of this notification
	 */
	public String getDisplayText()
	{
		if (type == NotificationType.BORROW_REQUEST)
		{
			return String.format("%s wants to borrow %s (x%d) for %d days",
				fromPlayer, itemName, quantity, durationDays);
		}
		else if (type == NotificationType.GROUP_INVITE)
		{
			return String.format("%s invited you to join '%s'",
				fromPlayer, groupName);
		}
		else
		{
			return String.format("%s is offering to lend you %s (x%d) for %d days",
				fromPlayer, itemName, quantity, durationDays);
		}
	}

	/**
	 * Static factory for group invite notification
	 */
	public static StoredNotification createGroupInvite(
		String groupId, String groupName, String inviter, String invitee, String inviteCode)
	{
		StoredNotification notification = new StoredNotification();
		notification.setType(NotificationType.GROUP_INVITE);
		notification.setGroupId(groupId);
		notification.setGroupName(groupName);
		notification.setFromPlayer(inviter);
		notification.setToPlayer(invitee);
		notification.setItemName(inviteCode); // Store invite code in itemName field
		notification.setItemId(0);
		notification.setQuantity(0);
		notification.setDurationDays(0);
		notification.setMessage("Use code: " + inviteCode);
		return notification;
	}

	/**
	 * Get the invite code (for GROUP_INVITE type)
	 */
	public String getInviteCode()
	{
		if (type == NotificationType.GROUP_INVITE)
		{
			return itemName; // Invite code stored in itemName
		}
		return null;
	}

	/**
	 * Mark as read and set read timestamp
	 */
	public void markAsRead()
	{
		this.read = true;
		this.readTimestamp = System.currentTimeMillis();
	}

	/**
	 * Check if read receipt is available (has been read)
	 */
	public boolean hasReadReceipt()
	{
		return read && readTimestamp > 0;
	}

	/**
	 * Serialize to string for storage
	 */
	public String serialize()
	{
		return String.format("%s|%s|%s|%s|%s|%s|%s|%d|%d|%d|%s|%d|%b|%d",
			id, groupId, groupName != null ? groupName : "",
			type.name(), fromPlayer, toPlayer, itemName,
			itemId, quantity, durationDays,
			message != null ? message.replace("|", "\\|") : "",
			timestamp, read, readTimestamp);
	}

	/**
	 * Deserialize from string
	 */
	public static StoredNotification deserialize(String data)
	{
		try
		{
			String[] parts = data.split("\\|", 14);
			if (parts.length < 12)
			{
				return null;
			}

			StoredNotification notification = new StoredNotification();
			notification.setId(parts[0]);
			notification.setGroupId(parts[1]);
			notification.setGroupName(parts[2].isEmpty() ? null : parts[2]);
			notification.setType(NotificationType.valueOf(parts[3]));
			notification.setFromPlayer(parts[4]);
			notification.setToPlayer(parts[5]);
			notification.setItemName(parts[6]);
			notification.setItemId(Integer.parseInt(parts[7]));
			notification.setQuantity(Integer.parseInt(parts[8]));
			notification.setDurationDays(Integer.parseInt(parts[9]));
			notification.setMessage(parts[10].isEmpty() ? null : parts[10].replace("\\|", "|"));
			notification.setTimestamp(Long.parseLong(parts[11]));
			if (parts.length > 12)
			{
				notification.setRead(Boolean.parseBoolean(parts[12]));
			}
			if (parts.length > 13)
			{
				notification.setReadTimestamp(Long.parseLong(parts[13]));
			}
			return notification;
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
