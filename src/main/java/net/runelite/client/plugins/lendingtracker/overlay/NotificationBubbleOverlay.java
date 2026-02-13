package net.runelite.client.plugins.lendingtracker.overlay;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.lendingtracker.LendingTrackerConfig;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.services.NotificationService;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.PanelComponent;

import javax.inject.Inject;
import java.awt.*;

/**
 * NotificationBubbleOverlay - Shows notification count bubble near minimap
 *
 * Displays a small circular badge with unread notification count.
 * User can drag this overlay to reposition it.
 * Click opens the lending panel.
 */
@Slf4j
public class NotificationBubbleOverlay extends Overlay
{
	private static final int BUBBLE_SIZE = 26;
	private static final int TOTAL_WIDTH = 50;
	private static final int TOTAL_HEIGHT = 45;
	private static final Color BUBBLE_COLOR = new Color(255, 69, 0);  // Orange-red
	private static final Color BUBBLE_BORDER = new Color(180, 40, 0);
	private static final Color TEXT_COLOR = Color.WHITE;
	private static final Font COUNT_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 14);

	private final Client client;
	private final LendingTrackerPlugin plugin;
	private final LendingTrackerConfig config;

	private int unreadCount = 0;

	@Inject
	public NotificationBubbleOverlay(Client client, LendingTrackerPlugin plugin, LendingTrackerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;

		setPosition(OverlayPosition.TOP_RIGHT);
		setPriority(OverlayPriority.HIGH);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(true);
	}

	/**
	 * Update the unread count
	 */
	public void setUnreadCount(int count)
	{
		this.unreadCount = count;
	}

	/**
	 * Get current unread count
	 */
	public int getUnreadCount()
	{
		return unreadCount;
	}

	/**
	 * Refresh notification count from service
	 */
	public void refreshCount()
	{
		NotificationService notificationService = plugin.getNotificationService();
		if (notificationService != null)
		{
			String currentPlayer = plugin.getCurrentPlayerName();
			if (currentPlayer != null)
			{
				unreadCount = notificationService.getUnreadCount(currentPlayer);
			}
			else
			{
				unreadCount = 0;
			}
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Don't render if disabled in config
		if (!config.showNotificationOverlay())
		{
			return null;
		}

		// Don't render if no notifications
		if (unreadCount <= 0)
		{
			return null;
		}

		// Draw everything relative to 0,0 - the overlay system handles positioning
		int centerX = TOTAL_WIDTH / 2;
		int bubbleY = 5;

		// Draw shadow
		graphics.setColor(new Color(0, 0, 0, 100));
		graphics.fillOval(centerX - BUBBLE_SIZE / 2 + 2, bubbleY + 2, BUBBLE_SIZE, BUBBLE_SIZE);

		// Draw bubble
		graphics.setColor(BUBBLE_COLOR);
		graphics.fillOval(centerX - BUBBLE_SIZE / 2, bubbleY, BUBBLE_SIZE, BUBBLE_SIZE);

		// Draw border
		graphics.setColor(BUBBLE_BORDER);
		graphics.setStroke(new BasicStroke(2));
		graphics.drawOval(centerX - BUBBLE_SIZE / 2, bubbleY, BUBBLE_SIZE, BUBBLE_SIZE);

		// Draw count text centered in bubble
		graphics.setColor(TEXT_COLOR);
		graphics.setFont(COUNT_FONT);
		String countText = unreadCount > 99 ? "99+" : String.valueOf(unreadCount);
		FontMetrics fm = graphics.getFontMetrics();
		int textX = centerX - fm.stringWidth(countText) / 2;
		int textY = bubbleY + (BUBBLE_SIZE + fm.getAscent() - fm.getDescent()) / 2;
		graphics.drawString(countText, textX, textY);

		// Draw "Lending" label below the bubble
		graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		graphics.setColor(Color.ORANGE);
		String label = "Lending";
		fm = graphics.getFontMetrics();
		int labelX = centerX - fm.stringWidth(label) / 2;
		graphics.drawString(label, labelX, bubbleY + BUBBLE_SIZE + 12);

		return new Dimension(TOTAL_WIDTH, TOTAL_HEIGHT);
	}
}
