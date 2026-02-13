package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import com.guess34.lendingtracker.model.LendingEntry;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * HistoryCard - Single transaction card for history list
 * Phase 3: Clean list-based design replacing JTable
 */
@Slf4j
public class HistoryCard extends JPanel
{
	private static final Color CARD_BACKGROUND = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color RETURNED_COLOR = new Color(0, 200, 0);
	private static final Color OVERDUE_COLOR = new Color(255, 140, 0);
	private static final Color DEFAULTED_COLOR = new Color(200, 0, 0);
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd");

	public HistoryCard(LendingEntry entry, ItemManager itemManager)
	{
		setLayout(new BorderLayout(10, 0));
		setBackground(CARD_BACKGROUND);
		setBorder(new EmptyBorder(8, 10, 8, 10));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
		setPreferredSize(new Dimension(0, 60));

		// Left: Item Icon
		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(36, 36));

		// Load icon asynchronously
		SwingUtilities.invokeLater(() ->
		{
			BufferedImage itemImage = itemManager.getImage(entry.getItemId(), entry.getQuantity(), entry.getQuantity() > 1);
			if (itemImage != null)
			{
				// Scale to 36x36
				Image scaled = itemImage.getScaledInstance(36, 36, Image.SCALE_SMOOTH);
				iconLabel.setIcon(new ImageIcon(scaled));
			}
		});

		add(iconLabel, BorderLayout.WEST);

		// Center: Item Info
		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
		centerPanel.setBackground(CARD_BACKGROUND);

		JLabel itemNameLabel = new JLabel(entry.getItem());
		itemNameLabel.setFont(FontManager.getRunescapeBoldFont());
		itemNameLabel.setForeground(Color.WHITE);
		itemNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		String participantInfo = String.format("Lent to: %s", entry.getBorrower());
		JLabel participantLabel = new JLabel(participantInfo);
		participantLabel.setFont(FontManager.getRunescapeSmallFont());
		participantLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		participantLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		centerPanel.add(itemNameLabel);
		centerPanel.add(Box.createVerticalStrut(2));
		centerPanel.add(participantLabel);

		add(centerPanel, BorderLayout.CENTER);

		// Right: Date and Status
		JPanel rightPanel = new JPanel();
		rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
		rightPanel.setBackground(CARD_BACKGROUND);

		// Date label
		String dateStr = DATE_FORMAT.format(new Date(entry.getLendDate()));
		JLabel dateLabel = new JLabel(dateStr);
		dateLabel.setFont(FontManager.getRunescapeSmallFont());
		dateLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dateLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);

		// Status badge
		JLabel statusBadge = createStatusBadge(entry);
		statusBadge.setAlignmentX(Component.RIGHT_ALIGNMENT);

		rightPanel.add(dateLabel);
		rightPanel.add(Box.createVerticalStrut(4));
		rightPanel.add(statusBadge);

		add(rightPanel, BorderLayout.EAST);
	}

	/**
	 * Create status badge with appropriate color
	 */
	private JLabel createStatusBadge(LendingEntry entry)
	{
		String statusText;
		Color statusColor;

		if (entry.getReturnedAt() > 0)
		{
			statusText = "Returned";
			statusColor = RETURNED_COLOR;
		}
		else if (entry.isOverdue())
		{
			// Very overdue (> 30 days) = defaulted
			long daysSinceDue = (System.currentTimeMillis() - entry.getDueDate()) / (24 * 60 * 60 * 1000);
			if (daysSinceDue > 30)
			{
				statusText = "Defaulted";
				statusColor = DEFAULTED_COLOR;
			}
			else
			{
				statusText = "Overdue";
				statusColor = OVERDUE_COLOR;
			}
		}
		else
		{
			statusText = "Active";
			statusColor = ColorScheme.BRAND_ORANGE;
		}

		JLabel badge = new JLabel(statusText);
		badge.setFont(FontManager.getRunescapeSmallFont());
		badge.setForeground(Color.WHITE);
		badge.setBackground(statusColor);
		badge.setOpaque(true);
		badge.setBorder(new EmptyBorder(2, 8, 2, 8));
		badge.setHorizontalAlignment(SwingConstants.CENTER);

		return badge;
	}
}
