package net.runelite.client.plugins.lendingtracker.ui;

// UNUSED: This class is not instantiated by the active UI (LendingPanel).
// It was designed for the old LendingTrackerPanel. Kept for potential future use.

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.services.core.MarketplaceManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * PlayerCard - Collapsible card showing a player's marketplace offerings
 * Phase 3: Flipping Utilities style with item icons
 */
@Slf4j
public class PlayerCard extends JPanel
{
	private static final Color CARD_BACKGROUND = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color CARD_HOVER = ColorScheme.DARKER_GRAY_HOVER_COLOR;
	private static final Color ONLINE_COLOR = new Color(0, 200, 0);
	private static final Color OFFLINE_COLOR = new Color(200, 0, 0);

	private final String playerName;
	private final List<LendingEntry> offerings;
	private final boolean isOnline;
	private final ItemManager itemManager;
	private final Runnable onRequestCallback;
	private final String currentUserName; // ADDED: To check if user owns items
	private final MarketplaceManager marketplaceManager; // ADDED: For edit/delete
	private final String groupId; // ADDED: Group context
	private final Runnable onRefreshCallback; // ADDED: Refresh callback

	private boolean expanded = false;

	private final JPanel headerPanel;
	private final JPanel bodyPanel;
	private JLabel statusDot; // Not final - we set it in createHeader
	private final JLabel offerCountLabel;
	private final JPanel itemListPanel;

	public PlayerCard(String playerName, List<LendingEntry> offerings, boolean isOnline,
		ItemManager itemManager, Runnable onRequestCallback,
		String currentUserName, MarketplaceManager marketplaceManager, String groupId, Runnable onRefreshCallback)
	{
		this.playerName = playerName;
		this.offerings = offerings;
		this.isOnline = isOnline;
		this.itemManager = itemManager;
		this.onRequestCallback = onRequestCallback;
		this.currentUserName = currentUserName;
		this.marketplaceManager = marketplaceManager;
		this.groupId = groupId;
		this.onRefreshCallback = onRefreshCallback;

		setLayout(new BorderLayout());
		setBackground(CARD_BACKGROUND);
		setBorder(BorderFactory.createCompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1, true),
			new EmptyBorder(8, 10, 8, 10)
		));

		// Create header (also initializes statusDot)
		headerPanel = createHeader();
		add(headerPanel, BorderLayout.NORTH);

		// Create body (initially collapsed)
		bodyPanel = new JPanel(new BorderLayout());
		bodyPanel.setBackground(CARD_BACKGROUND);

		offerCountLabel = new JLabel(String.format("Offering: %d item%s",
			offerings.size(), offerings.size() == 1 ? "" : "s"));
		offerCountLabel.setFont(FontManager.getRunescapeSmallFont());
		offerCountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		offerCountLabel.setBorder(new EmptyBorder(5, 0, 0, 0));

		bodyPanel.add(offerCountLabel, BorderLayout.NORTH);

		// Create item list panel (hidden initially)
		itemListPanel = new JPanel();
		itemListPanel.setLayout(new BoxLayout(itemListPanel, BoxLayout.Y_AXIS));
		itemListPanel.setBackground(CARD_BACKGROUND);
		itemListPanel.setVisible(false);
		bodyPanel.add(itemListPanel, BorderLayout.CENTER);

		add(bodyPanel, BorderLayout.CENTER);

		// Add click listener to toggle expansion
		addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleExpanded();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (!expanded)
				{
					setBackground(CARD_HOVER);
					headerPanel.setBackground(CARD_HOVER);
					bodyPanel.setBackground(CARD_HOVER);
					itemListPanel.setBackground(CARD_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setBackground(CARD_BACKGROUND);
				headerPanel.setBackground(CARD_BACKGROUND);
				bodyPanel.setBackground(CARD_BACKGROUND);
				itemListPanel.setBackground(CARD_BACKGROUND);
			}
		});

		// Populate item list
		populateItemList();
	}

	/**
	 * Create header with player name and online status
	 */
	private JPanel createHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(CARD_BACKGROUND);

		// Player name
		JLabel nameLabel = new JLabel(playerName);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);
		header.add(nameLabel, BorderLayout.WEST);

		// Online status dot
		statusDot = new JLabel("●");
		statusDot.setFont(new Font("Arial", Font.BOLD, 16));
		statusDot.setForeground(isOnline ? ONLINE_COLOR : OFFLINE_COLOR);
		statusDot.setToolTipText(isOnline ? "Online" : "Offline");
		header.add(statusDot, BorderLayout.EAST);

		return header;
	}

	/**
	 * Populate the item list with offering cards
	 */
	private void populateItemList()
	{
		itemListPanel.removeAll();

		if (offerings.isEmpty())
		{
			JLabel emptyLabel = new JLabel("No items offered");
			emptyLabel.setFont(FontManager.getRunescapeSmallFont());
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setBorder(new EmptyBorder(10, 0, 10, 0));
			emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			itemListPanel.add(emptyLabel);
		}
		else
		{
			for (LendingEntry offering : offerings)
			{
				JPanel itemRow = createItemRow(offering);
				itemListPanel.add(itemRow);
				itemListPanel.add(Box.createVerticalStrut(5));
			}
		}
	}

	/**
	 * Create a row for a single item offering
	 */
	private JPanel createItemRow(LendingEntry offering)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(5, 5, 5, 5));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		// Item icon (left)
		JLabel iconLabel = new JLabel();
		iconLabel.setPreferredSize(new Dimension(32, 32));

		// Load icon asynchronously
		SwingUtilities.invokeLater(() ->
		{
			BufferedImage itemImage = itemManager.getImage(offering.getItemId(), offering.getQuantity(), offering.getQuantity() > 1);
			if (itemImage != null)
			{
				// Scale to 32x32
				Image scaled = itemImage.getScaledInstance(32, 32, Image.SCALE_SMOOTH);
				iconLabel.setIcon(new ImageIcon(scaled));
			}
		});

		row.add(iconLabel, BorderLayout.WEST);

		// Item info (center)
		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel(offering.getItem());
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.WHITE);

		JLabel qtyLabel = new JLabel(String.format("Qty: %d", offering.getQuantity()));
		qtyLabel.setFont(FontManager.getRunescapeSmallFont());
		qtyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		infoPanel.add(nameLabel);
		infoPanel.add(qtyLabel);

		row.add(infoPanel, BorderLayout.CENTER);

		// FIXED: Show different buttons based on ownership
		boolean isOwnItem = currentUserName != null && playerName.equalsIgnoreCase(currentUserName);

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (isOwnItem)
		{
			// User's own item - show Edit and Delete buttons
			JButton editButton = new JButton("Edit");
			editButton.setFont(FontManager.getRunescapeSmallFont());
			editButton.setBackground(ColorScheme.BRAND_ORANGE);
			editButton.setForeground(Color.WHITE);
			editButton.setFocusPainted(false);
			editButton.setBorderPainted(false);
			editButton.setPreferredSize(new Dimension(50, 25));
			editButton.addActionListener(e -> showEditDialog(offering));
			buttonPanel.add(editButton);

			JButton deleteButton = new JButton("X");
			deleteButton.setFont(FontManager.getRunescapeBoldFont());
			deleteButton.setBackground(new Color(180, 50, 50));
			deleteButton.setForeground(Color.WHITE);
			deleteButton.setFocusPainted(false);
			deleteButton.setBorderPainted(false);
			deleteButton.setPreferredSize(new Dimension(30, 25));
			deleteButton.setToolTipText("Remove from marketplace");
			deleteButton.addActionListener(e -> deleteOffering(offering));
			buttonPanel.add(deleteButton);
		}
		else
		{
			// Other player's item - show Request button
			JButton requestButton = new JButton("Request");
			requestButton.setFont(FontManager.getRunescapeSmallFont());
			requestButton.setBackground(ColorScheme.BRAND_ORANGE);
			requestButton.setForeground(Color.WHITE);
			requestButton.setFocusPainted(false);
			requestButton.setBorderPainted(false);
			requestButton.setPreferredSize(new Dimension(80, 25));
			requestButton.addActionListener(e ->
			{
				log.info("Request clicked for item: {} from player: {}", offering.getItem(), playerName);
				if (onRequestCallback != null)
				{
					onRequestCallback.run();
				}
				JOptionPane.showMessageDialog(
					this,
					String.format("Requesting %s from %s\n\nThis feature will send a borrowing request.", offering.getItem(), playerName),
					"Item Request",
					JOptionPane.INFORMATION_MESSAGE
				);
			});
			buttonPanel.add(requestButton);
		}

		row.add(buttonPanel, BorderLayout.EAST);

		return row;
	}

	/**
	 * Show edit dialog for an offering
	 */
	private void showEditDialog(LendingEntry offering)
	{
		JPanel dialogPanel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Item Name (read-only)
		gbc.gridx = 0;
		gbc.gridy = 0;
		dialogPanel.add(new JLabel("Item:"), gbc);

		gbc.gridx = 1;
		JLabel itemNameLabel = new JLabel(offering.getItem());
		dialogPanel.add(itemNameLabel, gbc);

		// Quantity
		gbc.gridx = 0;
		gbc.gridy = 1;
		dialogPanel.add(new JLabel("Quantity:"), gbc);

		gbc.gridx = 1;
		JTextField quantityField = new JTextField(String.valueOf(offering.getQuantity()), 10);
		dialogPanel.add(quantityField, gbc);

		// Value (GP)
		gbc.gridx = 0;
		gbc.gridy = 2;
		dialogPanel.add(new JLabel("Value (GP):"), gbc);

		gbc.gridx = 1;
		JTextField valueField = new JTextField(String.valueOf(offering.getValue()), 10);
		dialogPanel.add(valueField, gbc);

		int result = JOptionPane.showConfirmDialog(
			this,
			dialogPanel,
			"Edit Marketplace Item",
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE
		);

		if (result == JOptionPane.OK_OPTION)
		{
			try
			{
				int newQuantity = Integer.parseInt(quantityField.getText().trim());
				long newValue = Long.parseLong(valueField.getText().trim());

				// Create updated entry
				LendingEntry updated = new LendingEntry();
				updated.setItemId(offering.getItemId());
				updated.setItem(offering.getItem());
				updated.setItemName(offering.getItemName());
				updated.setQuantity(newQuantity);
				updated.setValue(newValue);
				updated.setLender(offering.getLender());
				updated.setGroupId(groupId);

				// Update in marketplace
				if (marketplaceManager != null && groupId != null)
				{
					marketplaceManager.updateOffering(groupId, playerName, offering.getItem(), offering.getItemId(), updated);
					log.info("Updated offering: {} quantity={}, value={}", offering.getItem(), newQuantity, newValue);

					// Refresh UI
					if (onRefreshCallback != null)
					{
						onRefreshCallback.run();
					}
				}
			}
			catch (NumberFormatException e)
			{
				JOptionPane.showMessageDialog(
					this,
					"Please enter valid numbers for Quantity and Value.",
					"Invalid Input",
					JOptionPane.ERROR_MESSAGE
				);
			}
		}
	}

	/**
	 * Delete an offering from the marketplace
	 */
	private void deleteOffering(LendingEntry offering)
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			String.format("Remove '%s' from marketplace?", offering.getItem()),
			"Confirm Removal",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);

		if (confirm == JOptionPane.YES_OPTION)
		{
			if (marketplaceManager != null && groupId != null)
			{
				marketplaceManager.removeOffering(groupId, playerName, offering.getItem(), offering.getItemId());
				log.info("Removed offering: {} from marketplace", offering.getItem());

				// Refresh UI
				if (onRefreshCallback != null)
				{
					onRefreshCallback.run();
				}
			}
		}
	}

	/**
	 * Toggle card expansion
	 */
	private void toggleExpanded()
	{
		expanded = !expanded;

		if (expanded)
		{
			offerCountLabel.setVisible(false);
			itemListPanel.setVisible(true);
			setBackground(ColorScheme.DARK_GRAY_COLOR);
			headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			bodyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			itemListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		}
		else
		{
			offerCountLabel.setVisible(true);
			itemListPanel.setVisible(false);
		}

		revalidate();
		repaint();

		// Notify parent to revalidate
		Container parent = getParent();
		if (parent != null)
		{
			parent.revalidate();
			parent.repaint();
		}
	}

	/**
	 * Update online status
	 */
	public void setOnline(boolean online)
	{
		statusDot.setForeground(online ? ONLINE_COLOR : OFFLINE_COLOR);
		statusDot.setToolTipText(online ? "Online" : "Offline");
	}

	/**
	 * Get player name
	 */
	public String getPlayerName()
	{
		return playerName;
	}
}
