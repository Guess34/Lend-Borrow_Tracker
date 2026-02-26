package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.services.DataService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * HistoryPanel - Transaction history log for completed lending transactions
 * CHANGED: Removed notifications view (NotificationService deleted). History is now the sole view.
 */
@Slf4j
public class HistoryPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final DataService dataService;
	private final ItemManager itemManager;

	private final JLabel totalHistoryLabel;
	private final JPanel historyListPanel;

	public HistoryPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.dataService = plugin.getDataService();
		this.itemManager = plugin.getItemManager();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// === HISTORY HEADER ===
		JPanel historyHeader = new JPanel(new BorderLayout());
		historyHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		historyHeader.setBorder(new EmptyBorder(10, 10, 10, 10));

		totalHistoryLabel = new JLabel("Total History: 0 entries");
		totalHistoryLabel.setFont(FontManager.getRunescapeBoldFont());
		totalHistoryLabel.setForeground(Color.WHITE);
		totalHistoryLabel.setHorizontalAlignment(SwingConstants.CENTER);
		historyHeader.add(totalHistoryLabel, BorderLayout.CENTER);

		add(historyHeader, BorderLayout.NORTH);

		// === HISTORY LIST ===
		historyListPanel = new JPanel();
		historyListPanel.setLayout(new BoxLayout(historyListPanel, BoxLayout.Y_AXIS));
		historyListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane historyScrollPane = new JScrollPane(historyListPanel);
		historyScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		historyScrollPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		historyScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		historyScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		add(historyScrollPane, BorderLayout.CENTER);

		// Footer with clear old entries button
		add(createFooter(), BorderLayout.SOUTH);
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
	 * Refresh the history with latest data
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// Check login status first
			boolean isLoggedIn = false;
			try
			{
				if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
				{
					String currentPlayer = plugin.getClient().getLocalPlayer().getName();
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
			List<LendingEntry> historyEntries = dataService.getHistoryEntries();
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

				// Remove old entries from DataService (unified store)
				int removedCount = dataService.removeOldHistoryEntries(thirtyDaysAgo);

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
