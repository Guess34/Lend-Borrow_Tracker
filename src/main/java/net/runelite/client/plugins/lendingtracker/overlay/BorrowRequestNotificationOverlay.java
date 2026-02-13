package net.runelite.client.plugins.lendingtracker.overlay;

import net.runelite.api.Client;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.BorrowRequest;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Flashing notification overlay for borrow requests
 * Shows item name, requester name, and world number
 */
public class BorrowRequestNotificationOverlay extends Overlay
{
	private static final int NOTIFICATION_DISPLAY_TIME = 10000; // 10 seconds
	private static final int FLASH_INTERVAL = 500; // Flash every 500ms
	private static final Color NOTIFICATION_BACKGROUND = new Color(255, 140, 0, 220); // Orange
	private static final Color FLASH_BACKGROUND = new Color(255, 69, 0, 240); // Bright orange-red
	private static final Color TEXT_COLOR = Color.WHITE;
	private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);
	private static final Font DETAIL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);

	private final Client client;
	private final LendingTrackerPlugin plugin;
	private final List<BorrowRequestNotification> activeNotifications = new ArrayList<>();

	@Inject
	public BorrowRequestNotificationOverlay(Client client, LendingTrackerPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;

		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(OverlayPriority.HIGH);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		long currentTime = Instant.now().toEpochMilli();

		// Clean up expired notifications
		Iterator<BorrowRequestNotification> iterator = activeNotifications.iterator();
		while (iterator.hasNext())
		{
			BorrowRequestNotification notification = iterator.next();
			if (currentTime - notification.startTime > NOTIFICATION_DISPLAY_TIME)
			{
				iterator.remove();
			}
		}

		// Render active notifications
		int yOffset = 50; // Start below top of screen
		for (BorrowRequestNotification notification : activeNotifications)
		{
			renderNotification(graphics, notification, yOffset, currentTime);
			yOffset += notification.getHeight() + 10; // 10px spacing between notifications
		}

		return null;
	}

	private void renderNotification(Graphics2D graphics, BorrowRequestNotification notification, int yOffset, long currentTime)
	{
		long elapsed = currentTime - notification.startTime;

		// Determine if we should show flash color (alternating every FLASH_INTERVAL)
		boolean isFlashing = (elapsed / FLASH_INTERVAL) % 2 == 0;
		Color backgroundColor = isFlashing ? FLASH_BACKGROUND : NOTIFICATION_BACKGROUND;

		// Center horizontally
		int screenWidth = client.getCanvas().getWidth();
		int x = (screenWidth - notification.getWidth()) / 2;
		int y = yOffset;

		// Draw background with rounded corners
		graphics.setColor(backgroundColor);
		graphics.fillRoundRect(x, y, notification.getWidth(), notification.getHeight(), 10, 10);

		// Draw border
		graphics.setColor(backgroundColor.darker());
		graphics.setStroke(new BasicStroke(2));
		graphics.drawRoundRect(x, y, notification.getWidth(), notification.getHeight(), 10, 10);

		// Draw notification icon
		graphics.setColor(TEXT_COLOR);
		graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
		graphics.drawString("📦", x + 10, y + 25);

		// Draw title
		graphics.setFont(TITLE_FONT);
		graphics.setColor(TEXT_COLOR);
		graphics.drawString("Borrow Request!", x + 40, y + 22);

		// Draw details
		graphics.setFont(DETAIL_FONT);
		int detailY = y + 40;

		// Requester name
		graphics.drawString("From: " + notification.requesterName, x + 15, detailY);
		detailY += 16;

		// Item name (highlighted)
		graphics.setColor(Color.YELLOW);
		graphics.drawString("Item: " + notification.itemName, x + 15, detailY);
		graphics.setColor(TEXT_COLOR);
		detailY += 16;

		// World number (highlighted)
		graphics.setColor(Color.CYAN);
		graphics.drawString("World: " + notification.world, x + 15, detailY);
		graphics.setColor(TEXT_COLOR);
		detailY += 16;

		// Instruction
		graphics.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
		graphics.setColor(Color.LIGHT_GRAY);
		graphics.drawString("Click 'Wanted' tab to view and respond", x + 15, detailY);

		// ADDED: Draw dismiss button (X) in top-right corner
		int buttonSize = 20;
		int buttonX = x + notification.getWidth() - buttonSize - 5;
		int buttonY = y + 5;

		// Draw button background
		graphics.setColor(new Color(200, 0, 0, 180)); // Red with transparency
		graphics.fillRoundRect(buttonX, buttonY, buttonSize, buttonSize, 5, 5);

		// Draw X
		graphics.setColor(Color.WHITE);
		graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		graphics.drawString("✕", buttonX + 5, buttonY + 15);

		// Store button bounds for click detection
		notification.dismissBounds = new Rectangle(buttonX, buttonY, buttonSize, buttonSize);

		// Also store the main notification bounds for x/y position tracking
		notification.screenX = x;
		notification.screenY = y;
	}

	/**
	 * Show a new borrow request notification
	 */
	public void showBorrowRequest(BorrowRequest request)
	{
		// Check if this request is already displayed
		boolean alreadyShowing = activeNotifications.stream()
			.anyMatch(n -> n.requestId.equals(request.getId()));

		if (!alreadyShowing)
		{
			BorrowRequestNotification notification = new BorrowRequestNotification(
				request.getId(),
				request.getRequesterId(),
				request.getItemName(),
				request.getWorld()
			);
			activeNotifications.add(notification);
		}
	}

	/**
	 * Remove a specific notification
	 */
	public void removeNotification(String requestId)
	{
		activeNotifications.removeIf(n -> n.requestId.equals(requestId));
	}

	/**
	 * Clear all notifications
	 */
	public void clearAll()
	{
		activeNotifications.clear();
	}

	/**
	 * Handle mouse clicks on notifications
	 * Returns true if click was handled
	 */
	public boolean handleClick(Point clickPoint)
	{
		for (BorrowRequestNotification notification : activeNotifications)
		{
			if (notification.dismissBounds != null && notification.dismissBounds.contains(clickPoint))
			{
				// User clicked the dismiss button - notify plugin
				plugin.onNotificationDismissed(notification.requestId, notification.requesterName);
				activeNotifications.remove(notification);
				return true;
			}
		}
		return false;
	}

	/**
	 * Get all active notifications (for management panel)
	 */
	public List<BorrowRequest> getActiveNotifications()
	{
		// This would need the full request objects - for now return empty
		// We'll implement this with a proper lookup
		return new ArrayList<>();
	}

	/**
	 * Notification data class
	 */
	private static class BorrowRequestNotification
	{
		final String requestId;
		final String requesterName;
		final String itemName;
		final int world;
		final long startTime;

		// ADDED: For click detection
		Rectangle dismissBounds;
		int screenX;
		int screenY;

		public BorrowRequestNotification(String requestId, String requesterName, String itemName, int world)
		{
			this.requestId = requestId;
			this.requesterName = requesterName;
			this.itemName = itemName;
			this.world = world;
			this.startTime = Instant.now().toEpochMilli();
		}

		public int getWidth()
		{
			return 350; // Fixed width
		}

		public int getHeight()
		{
			return 100; // Fixed height for consistent display
		}
	}
}
