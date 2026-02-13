package net.runelite.client.plugins.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.model.StoredNotification;
import net.runelite.client.plugins.lendingtracker.services.NotificationService;
import net.runelite.client.plugins.lendingtracker.services.core.LendingManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * HistoryPanel - Transaction log with notifications section
 * UPDATED: Now includes notifications display at the top
 */
@Slf4j
public class HistoryPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final LendingManager lendingManager;
	private final ItemManager itemManager;

	private final JLabel totalHistoryLabel;
	private final JPanel historyListPanel;

	// Notifications section
	private final JPanel notificationsPanel;
	private final JLabel notificationsLabel;

	// ADDED: View toggle and containers for switching between notifications/history
	private final JPanel notificationsView;
	private final JPanel historyView;
	private final JToggleButton notifToggle;
	private final JToggleButton historyToggle;

	public HistoryPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.lendingManager = plugin.getLendingManager();
		this.itemManager = plugin.getItemManager();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// === VIEW TOGGLE BAR ===
		// ADDED: Toggle buttons to switch between Notifications and History views
		JPanel toggleBar = new JPanel(new GridLayout(1, 2, 0, 0));
		toggleBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		toggleBar.setBorder(new EmptyBorder(5, 5, 5, 5));
		toggleBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		notifToggle = new JToggleButton("Notifications");
		notifToggle.setFont(FontManager.getRunescapeSmallFont());
		notifToggle.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		notifToggle.setForeground(Color.WHITE);
		notifToggle.setFocusPainted(false);

		historyToggle = new JToggleButton("History");
		historyToggle.setFont(FontManager.getRunescapeSmallFont());
		historyToggle.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		historyToggle.setForeground(Color.WHITE);
		historyToggle.setFocusPainted(false);

		ButtonGroup viewGroup = new ButtonGroup();
		viewGroup.add(notifToggle);
		viewGroup.add(historyToggle);

		// Default to history view
		historyToggle.setSelected(true);

		notifToggle.addActionListener(e -> switchView(true));
		historyToggle.addActionListener(e -> switchView(false));

		toggleBar.add(notifToggle);
		toggleBar.add(historyToggle);

		add(toggleBar, BorderLayout.NORTH);

		// === CARD PANEL: holds both views, switches between them ===
		JPanel cardPanel = new JPanel(new CardLayout());
		cardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// === NOTIFICATIONS VIEW ===
		notificationsView = new JPanel(new BorderLayout());
		notificationsView.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel notificationsHeader = new JPanel(new BorderLayout());
		notificationsHeader.setBackground(new Color(255, 140, 0));
		notificationsHeader.setBorder(new EmptyBorder(8, 10, 8, 10));

		notificationsLabel = new JLabel("Notifications (0)");
		notificationsLabel.setFont(FontManager.getRunescapeBoldFont());
		notificationsLabel.setForeground(Color.WHITE);
		notificationsHeader.add(notificationsLabel, BorderLayout.CENTER);

		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
		buttonRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonRow.setBorder(new EmptyBorder(5, 5, 5, 5));

		JButton markAllReadBtn = new JButton("Mark All Read");
		markAllReadBtn.setFont(FontManager.getRunescapeSmallFont());
		markAllReadBtn.setBackground(new Color(0, 120, 0));
		markAllReadBtn.setForeground(Color.WHITE);
		markAllReadBtn.setFocusPainted(false);
		markAllReadBtn.addActionListener(e -> markAllNotificationsRead());
		buttonRow.add(markAllReadBtn);

		JButton clearAllBtn = new JButton("Clear All");
		clearAllBtn.setFont(FontManager.getRunescapeSmallFont());
		clearAllBtn.setBackground(new Color(150, 50, 50));
		clearAllBtn.setForeground(Color.WHITE);
		clearAllBtn.setFocusPainted(false);
		clearAllBtn.addActionListener(e -> clearAllNotifications());
		buttonRow.add(clearAllBtn);

		notificationsPanel = new JPanel();
		notificationsPanel.setLayout(new BoxLayout(notificationsPanel, BoxLayout.Y_AXIS));
		notificationsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		notificationsPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		JPanel notifTopPanel = new JPanel();
		notifTopPanel.setLayout(new BoxLayout(notifTopPanel, BoxLayout.Y_AXIS));
		notifTopPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		notifTopPanel.add(notificationsHeader);
		notifTopPanel.add(buttonRow);

		notificationsView.add(notifTopPanel, BorderLayout.NORTH);

		JScrollPane notifScrollPane = new JScrollPane(notificationsPanel);
		notifScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		notifScrollPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		notifScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		notifScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		notificationsView.add(notifScrollPane, BorderLayout.CENTER);

		// === HISTORY VIEW ===
		historyView = new JPanel(new BorderLayout());
		historyView.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel historyHeader = new JPanel(new BorderLayout());
		historyHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		historyHeader.setBorder(new EmptyBorder(10, 10, 10, 10));

		totalHistoryLabel = new JLabel("Total History: 0 entries");
		totalHistoryLabel.setFont(FontManager.getRunescapeBoldFont());
		totalHistoryLabel.setForeground(Color.WHITE);
		totalHistoryLabel.setHorizontalAlignment(SwingConstants.CENTER);
		historyHeader.add(totalHistoryLabel, BorderLayout.CENTER);

		historyView.add(historyHeader, BorderLayout.NORTH);

		historyListPanel = new JPanel();
		historyListPanel.setLayout(new BoxLayout(historyListPanel, BoxLayout.Y_AXIS));
		historyListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane historyScrollPane = new JScrollPane(historyListPanel);
		historyScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		historyScrollPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		historyScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		historyScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		historyView.add(historyScrollPane, BorderLayout.CENTER);

		// Footer with clear old entries button
		historyView.add(createFooter(), BorderLayout.SOUTH);

		// Add both views to card panel
		cardPanel.add(historyView, "history");
		cardPanel.add(notificationsView, "notifications");

		add(cardPanel, BorderLayout.CENTER);

		// Default: show history view
		switchView(false);
	}

	/**
	 * ADDED: Switch between notifications and history views
	 */
	private void switchView(boolean showNotifications)
	{
		Container parent = historyView.getParent();
		if (parent != null && parent.getLayout() instanceof CardLayout)
		{
			CardLayout cl = (CardLayout) parent.getLayout();
			cl.show(parent, showNotifications ? "notifications" : "history");
		}
	}

	/**
	 * Create footer with controls
	 */
	private JPanel createFooter()
	{
		JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER));
		footer.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		footer.setBorder(new EmptyBorder(10, 10, 10, 10));

		JButton clearOldButton = new JButton("Clear Old Entries");
		clearOldButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		clearOldButton.setForeground(Color.WHITE);
		clearOldButton.setFocusPainted(false);
		clearOldButton.addActionListener(e -> clearOldEntries());

		footer.add(clearOldButton);

		return footer;
	}

	/**
	 * Refresh the history and notifications with latest data
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// ADDED: Check login status first
			boolean isLoggedIn = false;
			String currentPlayer = null;
			try
			{
				if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
				{
					currentPlayer = plugin.getClient().getLocalPlayer().getName();
					if (currentPlayer != null && !currentPlayer.isEmpty())
					{
						isLoggedIn = true;
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Could not check login status", e);
			}

			// Refresh notifications section
			refreshNotifications(isLoggedIn, currentPlayer);

			// Clear and rebuild history cards
			historyListPanel.removeAll();

			// If not logged in, show login message
			if (!isLoggedIn)
			{
				totalHistoryLabel.setText("Total History: 0 entries");

				JPanel emptyPanel = new JPanel(new BorderLayout());
				emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

				JLabel emptyLabel = new JLabel("<html><center><b style='color: #ff9900;'>Not Logged In</b><br><br>Please log in to your<br>OSRS account to view<br>transaction history.</center></html>");
				emptyLabel.setFont(FontManager.getRunescapeFont());
				emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);

				emptyPanel.add(emptyLabel, BorderLayout.CENTER);
				historyListPanel.add(emptyPanel);

				historyListPanel.revalidate();
				historyListPanel.repaint();
				return;
			}

			// Get history entries (with null safety) - only if logged in
			List<LendingEntry> historyEntries = lendingManager.getHistoryEntries();
			if (historyEntries == null)
			{
				historyEntries = java.util.Collections.emptyList();
			}

			// Update header label
			totalHistoryLabel.setText("Total History: " + historyEntries.size() + " entries");

			if (historyEntries.isEmpty())
			{
				// Show empty state
				JPanel emptyPanel = new JPanel(new BorderLayout());
				emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

				JLabel emptyLabel = new JLabel("<html><center><b>No History Yet</b><br><br>Completed lending transactions<br>will appear here</center></html>");
				emptyLabel.setFont(FontManager.getRunescapeFont());
				emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);

				emptyPanel.add(emptyLabel, BorderLayout.CENTER);
				historyListPanel.add(emptyPanel);
			}
			else
			{
				// Sort entries by date (most recent first)
				historyEntries.sort((e1, e2) -> Long.compare(e2.getLendDate(), e1.getLendDate()));

				// Create history cards
				for (LendingEntry entry : historyEntries)
				{
					HistoryCard card = new HistoryCard(entry, itemManager);
					historyListPanel.add(card);
					historyListPanel.add(Box.createVerticalStrut(2));
				}
			}

			historyListPanel.revalidate();
			historyListPanel.repaint();
		});
	}

	/**
	 * ADDED: Refresh the notifications section
	 */
	private void refreshNotifications(boolean isLoggedIn, String currentPlayer)
	{
		notificationsPanel.removeAll();

		if (!isLoggedIn || currentPlayer == null)
		{
			notificationsLabel.setText("Notifications (0)");
			JLabel noNotifLabel = new JLabel("Log in to see notifications");
			noNotifLabel.setFont(FontManager.getRunescapeSmallFont());
			noNotifLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noNotifLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
			notificationsPanel.add(noNotifLabel);
			notificationsPanel.revalidate();
			notificationsPanel.repaint();
			return;
		}

		NotificationService notificationService = plugin.getNotificationService();
		if (notificationService == null)
		{
			notificationsLabel.setText("Notifications (0)");
			return;
		}

		List<StoredNotification> notifications = notificationService.getNotificationsForPlayer(currentPlayer);
		int unreadCount = (int) notifications.stream().filter(n -> !n.isRead()).count();

		notificationsLabel.setText("Notifications (" + unreadCount + " unread)");

		if (notifications.isEmpty())
		{
			JLabel noNotifLabel = new JLabel("No notifications");
			noNotifLabel.setFont(FontManager.getRunescapeSmallFont());
			noNotifLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noNotifLabel.setBorder(new EmptyBorder(10, 10, 10, 10));
			notificationsPanel.add(noNotifLabel);
		}
		else
		{
			for (StoredNotification notification : notifications)
			{
				JPanel notifCard = createNotificationCard(notification, currentPlayer);
				notificationsPanel.add(notifCard);
				notificationsPanel.add(Box.createVerticalStrut(2));
			}
		}

		notificationsPanel.revalidate();
		notificationsPanel.repaint();
	}

	/**
	 * ADDED: Create a notification card for display
	 */
	private JPanel createNotificationCard(StoredNotification notification, String currentPlayer)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(notification.isRead() ? ColorScheme.DARK_GRAY_COLOR : new Color(60, 50, 30));
		card.setBorder(new EmptyBorder(8, 10, 8, 10));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

		// Left: Unread indicator
		JLabel indicator = new JLabel(notification.isRead() ? "" : "\u2022");
		indicator.setFont(new Font("Arial", Font.BOLD, 20));
		indicator.setForeground(new Color(255, 140, 0));
		card.add(indicator, BorderLayout.WEST);

		// Center: Notification content
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(card.getBackground());

		// Type and time
		String typeText = notification.getType() == StoredNotification.NotificationType.BORROW_REQUEST
			? "Borrow Request"
			: "Lend Offer";
		SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm");
		String timeText = sdf.format(new Date(notification.getTimestamp()));

		JLabel typeLabel = new JLabel(typeText + " - " + timeText);
		typeLabel.setFont(FontManager.getRunescapeSmallFont());
		typeLabel.setForeground(notification.isRead() ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);
		contentPanel.add(typeLabel);

		// Message
		JLabel msgLabel = new JLabel("<html>" + notification.getDisplayText() + "</html>");
		msgLabel.setFont(FontManager.getRunescapeSmallFont());
		msgLabel.setForeground(notification.isRead() ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);
		contentPanel.add(msgLabel);

		// Group name
		JLabel groupLabel = new JLabel("Group: " + (notification.getGroupName() != null ? notification.getGroupName() : "Unknown"));
		groupLabel.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 10));
		groupLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		contentPanel.add(groupLabel);

		card.add(contentPanel, BorderLayout.CENTER);

		// Right: Action buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		buttonPanel.setBackground(card.getBackground());

		if (!notification.isRead())
		{
			JButton readBtn = new JButton("\u2713"); // Checkmark
			readBtn.setFont(new Font("Arial", Font.PLAIN, 12));
			readBtn.setToolTipText("Mark as read");
			readBtn.setBackground(new Color(0, 150, 0));
			readBtn.setForeground(Color.WHITE);
			readBtn.setFocusPainted(false);
			readBtn.setBorderPainted(false);
			readBtn.setPreferredSize(new Dimension(30, 25));
			readBtn.addActionListener(e -> {
				plugin.getNotificationService().markAsRead(currentPlayer, notification.getId());
				refresh();
			});
			buttonPanel.add(readBtn);
		}

		JButton deleteBtn = new JButton("\u2715"); // X mark
		deleteBtn.setFont(new Font("Arial", Font.PLAIN, 12));
		deleteBtn.setToolTipText("Delete notification");
		deleteBtn.setBackground(new Color(150, 0, 0));
		deleteBtn.setForeground(Color.WHITE);
		deleteBtn.setFocusPainted(false);
		deleteBtn.setBorderPainted(false);
		deleteBtn.setPreferredSize(new Dimension(30, 25));
		deleteBtn.addActionListener(e -> {
			plugin.getNotificationService().deleteNotification(currentPlayer, notification.getId());
			refresh();
		});
		buttonPanel.add(deleteBtn);

		card.add(buttonPanel, BorderLayout.EAST);

		return card;
	}

	/**
	 * ADDED: Mark all notifications as read
	 */
	private void markAllNotificationsRead()
	{
		String currentPlayer = plugin.getCurrentPlayerName();
		if (currentPlayer != null && plugin.getNotificationService() != null)
		{
			plugin.getNotificationService().markAllAsRead(currentPlayer);
			refresh();
		}
	}

	/**
	 * ADDED: Clear all notifications
	 */
	private void clearAllNotifications()
	{
		String currentPlayer = plugin.getCurrentPlayerName();
		if (currentPlayer != null && plugin.getNotificationService() != null)
		{
			int confirm = JOptionPane.showConfirmDialog(
				this,
				"Are you sure you want to clear all notifications?",
				"Clear Notifications",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE
			);

			if (confirm == JOptionPane.YES_OPTION)
			{
				plugin.getNotificationService().clearAllNotifications(currentPlayer);
				refresh();
			}
		}
	}

	/**
	 * Clear old history entries
	 */
	private void clearOldEntries()
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"Are you sure you want to clear old history entries?\nThis will remove all completed transactions older than 30 days.",
			"Clear History",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);

		if (confirm == JOptionPane.YES_OPTION)
		{
			try
			{
				long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);

				// FIXED: Actually remove old entries from both LendingManager and Recorder
				int removedFromManager = lendingManager.removeOldHistoryEntries(thirtyDaysAgo);
				int removedFromRecorder = plugin.getRecorder().removeOldHistoryEntries(thirtyDaysAgo);
				int removedCount = removedFromManager + removedFromRecorder;

				log.info("Cleared {} old history entries ({} from manager, {} from recorder)",
					removedCount, removedFromManager, removedFromRecorder);
				refresh();

				JOptionPane.showMessageDialog(
					this,
					String.format("Cleared %d old entries (older than 30 days)", removedCount),
					"Success",
					JOptionPane.INFORMATION_MESSAGE
				);
			}
			catch (Exception e)
			{
				log.error("Failed to clear old entries", e);
				JOptionPane.showMessageDialog(
					this,
					"Failed to clear old entries: " + e.getMessage(),
					"Error",
					JOptionPane.ERROR_MESSAGE
				);
			}
		}
	}
}
