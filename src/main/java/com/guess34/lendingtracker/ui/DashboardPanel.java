package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.services.DataService;
import com.guess34.lendingtracker.services.GroupService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.QuantityFormatter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * DashboardPanel - Active loans view with summary header.
 * Card-based UI showing marketplace offerings, borrow requests, and active loans.
 */
@Slf4j
public class DashboardPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final DataService dataService;
	private final GroupService groupService;
	private final ItemManager itemManager;

	private final JLabel totalValueLabel;
	private final JLabel activeLoansLabel;
	private final JLabel overdueCountLabel;
	private final JPanel loanListPanel;

	// In-memory cache for Looking For requests to ensure immediate display after saving
	private final java.util.Map<String, List<LookingForRequest>> lookingForCache = new java.util.concurrent.ConcurrentHashMap<>();

	// Track collapsed sections - all start collapsed for a clean initial view
	private final java.util.Set<String> collapsedSections = new java.util.HashSet<>(
		java.util.Arrays.asList("marketplace", "lookingfor", "loans")
	);

	public DashboardPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.dataService = plugin.getDataService();
		this.groupService = plugin.getGroupService();
		this.itemManager = plugin.getItemManager();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create summary header
		JPanel summaryHeader = new JPanel();
		summaryHeader.setLayout(new BoxLayout(summaryHeader, BoxLayout.Y_AXIS));
		summaryHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		summaryHeader.setBorder(new EmptyBorder(10, 10, 10, 10));

		// Total value available in marketplace (for current group)
		totalValueLabel = new JLabel("Available: 0 GP");
		totalValueLabel.setFont(FontManager.getRunescapeBoldFont());
		totalValueLabel.setForeground(Color.YELLOW);
		totalValueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		totalValueLabel.setToolTipText("<html><b>Available Value</b><br>Total GP value of items posted to<br>the marketplace in the current group.<br><br>Use 'Offer Item' to add items.</html>");

		// Active loans count (marketplace items and active loans)
		activeLoansLabel = new JLabel("Marketplace: 0 | Loans: 0");
		activeLoansLabel.setFont(FontManager.getRunescapeSmallFont());
		activeLoansLabel.setForeground(Color.WHITE);
		activeLoansLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		activeLoansLabel.setToolTipText("<html><b>Marketplace</b> = Items offered for lending<br><b>Loans</b> = Items currently lent out</html>");

		// Overdue count
		overdueCountLabel = new JLabel("Overdue: 0");
		overdueCountLabel.setFont(FontManager.getRunescapeSmallFont());
		overdueCountLabel.setForeground(Color.RED);
		overdueCountLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		overdueCountLabel.setToolTipText("<html><b>Overdue Loans</b><br>Number of loans past their due date</html>");

		summaryHeader.add(totalValueLabel);
		summaryHeader.add(Box.createVerticalStrut(5));
		summaryHeader.add(activeLoansLabel);
		summaryHeader.add(Box.createVerticalStrut(5));
		summaryHeader.add(overdueCountLabel);

		add(summaryHeader, BorderLayout.NORTH);

		// Create loan list panel (scrollable)
		// Wrapper panel with BorderLayout ensures items stack top-down
		JPanel loanListWrapper = new JPanel(new BorderLayout());
		loanListWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);

		loanListPanel = new JPanel();
		loanListPanel.setLayout(new BoxLayout(loanListPanel, BoxLayout.Y_AXIS));
		loanListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		loanListWrapper.add(loanListPanel, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane(loanListWrapper);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(scrollPane, BorderLayout.CENTER);

		JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 5, 0));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		JButton addToMarketplaceButton = new JButton("Offer Item");
		addToMarketplaceButton.setFont(FontManager.getRunescapeSmallFont());
		addToMarketplaceButton.setBackground(ColorScheme.BRAND_ORANGE);
		addToMarketplaceButton.setForeground(Color.WHITE);
		addToMarketplaceButton.setFocusPainted(false);
		addToMarketplaceButton.setToolTipText("Offer a single item to lend");
		addToMarketplaceButton.addActionListener(e -> showAddItemDialog());
		buttonPanel.add(addToMarketplaceButton);

		JButton lookingForButton = new JButton("Looking For");
		lookingForButton.setFont(FontManager.getRunescapeSmallFont());
		lookingForButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
		lookingForButton.setForeground(Color.WHITE);
		lookingForButton.setFocusPainted(false);
		lookingForButton.setToolTipText("Post an item you want to borrow");
		lookingForButton.addActionListener(e -> showLookingForDialog());
		buttonPanel.add(lookingForButton);

		add(buttonPanel, BorderLayout.SOUTH);
	}

	private JPanel createCollapsibleHeader(String title, Color color, String sectionId, boolean collapsed)
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(8, 10, 5, 10));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		String arrow = collapsed ? "\u25B6 " : "\u25BC "; // Right or Down triangle
		JLabel label = new JLabel(arrow + title);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(color);
		header.add(label, BorderLayout.WEST);

		header.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (collapsedSections.contains(sectionId))
				{
					collapsedSections.remove(sectionId);
				}
				else
				{
					collapsedSections.add(sectionId);
				}
				refresh();
			}
		});

		return header;
	}

	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// Check GameState FIRST - authoritative source for login status
			boolean isLoggedIn = false;
			try
			{
				if (plugin.getClient() != null &&
					plugin.getClient().getGameState() == net.runelite.api.GameState.LOGGED_IN)
				{
					isLoggedIn = true;
				}
			}
			catch (Exception e)
			{
				log.debug("Could not check login status", e);
			}

			if (!isLoggedIn)
			{
				totalValueLabel.setText("Available: 0 GP");
				activeLoansLabel.setText("Marketplace: 0 | Loans: 0");
				overdueCountLabel.setText("Overdue: 0");
				overdueCountLabel.setForeground(Color.GREEN);

				loanListPanel.removeAll();

				JPanel emptyPanel = createEmptyStatePanel(
					"<html><center><b style='color: #ff9900;'>Not Logged In</b><br><br>Please log in to your<br>OSRS account to view<br>the marketplace.</center></html>");
				emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

				loanListPanel.add(emptyPanel);
				loanListPanel.revalidate();
				loanListPanel.repaint();
				return;
			}

			String groupId = groupService.getCurrentGroupIdUnchecked();

			// Get marketplace offerings from DataService
			List<LendingEntry> marketplaceItems = new java.util.ArrayList<>();
			if (groupId != null && !groupId.isEmpty())
			{
				List<LendingEntry> items = dataService.getAvailable(groupId);
				if (items != null) marketplaceItems.addAll(items);
			}

			// Get active loans (items currently lent out)
			List<LendingEntry> activeLoans = dataService.getActiveEntries();
			if (activeLoans == null)
			{
				activeLoans = java.util.Collections.emptyList();
			}

			// Calculate summary stats from both marketplace and loans
			long totalMarketplaceValue = marketplaceItems.stream()
				.mapToLong(LendingEntry::getValue)
				.sum();

			long overdueCount = activeLoans.stream()
				.filter(LendingEntry::isOverdue)
				.count();

			// Update summary labels
			totalValueLabel.setText("Available: " + QuantityFormatter.quantityToStackSize(totalMarketplaceValue) + " GP");
			activeLoansLabel.setText("Marketplace: " + marketplaceItems.size() + " | Loans: " + activeLoans.size());
			overdueCountLabel.setText("Overdue: " + overdueCount);
			overdueCountLabel.setForeground(overdueCount > 0 ? Color.RED : Color.GREEN);

			// Clear and rebuild cards
			loanListPanel.removeAll();

			// Filter marketplace items - only show items from group members
			com.guess34.lendingtracker.model.LendingGroup currentGroupForFilter =
				groupId != null ? groupService.getGroup(groupId) : null;
			List<LendingEntry> displayItems = marketplaceItems.stream()
				.filter(item -> {
					String lender = item.getLender();
					if (lender == null) return false;
					return currentGroupForFilter == null || currentGroupForFilter.hasMember(lender);
				})
				.collect(java.util.stream.Collectors.toList());

			// Show marketplace offerings first
			if (!displayItems.isEmpty())
			{
				// Add section header for marketplace
				// Collapsible section header with item count
				boolean marketCollapsed = collapsedSections.contains("marketplace");
				JPanel marketHeader = createCollapsibleHeader(
					"Available for Lending (" + displayItems.size() + ")",
					ColorScheme.BRAND_ORANGE, "marketplace", marketCollapsed);
				loanListPanel.add(marketHeader);

				if (!marketCollapsed)
				{
					for (LendingEntry item : displayItems)
					{
						MarketplaceCard card = new MarketplaceCard(item);
						loanListPanel.add(card);
					}
				}
			}

			// Show "Looking For" requests section
			List<LookingForRequest> lookingForRequests = getLookingForRequests(groupId);
			if (!lookingForRequests.isEmpty())
			{
				// Collapsible section header with item count
				boolean lookingForCollapsed = collapsedSections.contains("lookingfor");
				JPanel lookingForHeader = createCollapsibleHeader(
					"Looking For (" + lookingForRequests.size() + ")",
					ColorScheme.GRAND_EXCHANGE_PRICE, "lookingfor", lookingForCollapsed);
				loanListPanel.add(lookingForHeader);

				if (!lookingForCollapsed)
				{
					for (LookingForRequest request : lookingForRequests)
					{
						LookingForCard card = new LookingForCard(request);
						loanListPanel.add(card);
					}
				}
			}

			// Then show active loans
			if (!activeLoans.isEmpty())
			{
				// Collapsible section header with item count
				boolean loansCollapsed = collapsedSections.contains("loans");
				JPanel loanHeader = createCollapsibleHeader(
					"Active Loans (" + activeLoans.size() + ")",
					Color.YELLOW, "loans", loansCollapsed);
				loanListPanel.add(loanHeader);

				if (!loansCollapsed)
				{
					for (LendingEntry loan : activeLoans)
					{
						LoanCard card = new LoanCard(loan);
						loanListPanel.add(card);
					}
				}
			}

			// If all sections are empty, show empty state
			if (displayItems.isEmpty() && lookingForRequests.isEmpty() && activeLoans.isEmpty())
			{
				String message = (groupId == null || groupId.isEmpty())
					? "<html><center>No group selected<br><br>Select or create a group to start</center></html>"
					: "<html><center>No items in marketplace<br><br>Right-click an item and select<br>'Add to Lending List' to offer it<br><br>Or click 'Looking For' to<br>post what you need to borrow</center></html>";

				loanListPanel.add(createEmptyStatePanel(message));
			}

			loanListPanel.revalidate();
			loanListPanel.repaint();
		});
	}

	private class MarketplaceCard extends JPanel
	{
		private final LendingEntry item;
		private final JPanel detailsPanel;
		private final JPanel rightPanel;

		public MarketplaceCard(LendingEntry item)
		{
			this.item = item;

			setLayout(new BorderLayout(5, 0));
			Color bgColor = ColorScheme.DARKER_GRAY_COLOR;
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(8, 8, 8, 8)
			));

			setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
			setPreferredSize(new Dimension(200, 60));

			// Left side: Item icon (fixed width)
			JLabel iconLabel = new JLabel();
			iconLabel.setPreferredSize(new Dimension(36, 36));
			try
			{
				BufferedImage itemImage = itemManager.getImage(item.getItemId(), item.getQuantity(), item.getQuantity() > 1);
				if (itemImage != null)
				{
					iconLabel.setIcon(new ImageIcon(itemImage));
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to load item icon for {}", item.getItemId());
			}
			add(iconLabel, BorderLayout.WEST);

			// Center: Item details
			detailsPanel = new JPanel();
			detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
			detailsPanel.setBackground(bgColor);

			// Item name (truncate if too long)
			String itemName = item.getItem();
			if (itemName.length() > 18) itemName = itemName.substring(0, 15) + "...";
			JLabel itemLabel = new JLabel(itemName + " x" + item.getQuantity());
			itemLabel.setFont(FontManager.getRunescapeSmallFont());
			itemLabel.setForeground(Color.WHITE);

			// Owner name
			String ownerText = "By: " + item.getLender();
			JLabel ownerLabel = new JLabel(ownerText);
			ownerLabel.setFont(FontManager.getRunescapeSmallFont());
			ownerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			detailsPanel.add(itemLabel);
			detailsPanel.add(ownerLabel);

			add(detailsPanel, BorderLayout.CENTER);

			// Right side: Value and status (compact)
			rightPanel = new JPanel();
			rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
			rightPanel.setBackground(bgColor);
			rightPanel.setPreferredSize(new Dimension(50, 45));

			JLabel valueLabel = new JLabel(QuantityFormatter.quantityToStackSize(item.getValue()));
			valueLabel.setFont(FontManager.getRunescapeSmallFont());
			valueLabel.setForeground(Color.YELLOW);

			JLabel statusLabel = new JLabel("Avail");
			statusLabel.setFont(FontManager.getRunescapeSmallFont());
			statusLabel.setForeground(Color.GREEN);

			rightPanel.add(valueLabel);
			rightPanel.add(statusLabel);

			add(rightPanel, BorderLayout.EAST);

			setComponentPopupMenu(createPopupMenu());
			addHoverEffect(this, ColorScheme.DARKER_GRAY_HOVER_COLOR, bgColor, detailsPanel, rightPanel);
		}

		private JPopupMenu createPopupMenu()
		{
			JPopupMenu menu = new JPopupMenu();
			String currentPlayer = getCurrentPlayerName();
			boolean isOwner = item.getLender() != null && item.getLender().equalsIgnoreCase(currentPlayer);

			if (isOwner)
			{
				boolean isLentOut = item.getBorrower() != null && !item.getBorrower().isEmpty();

				if (isLentOut)
				{
					// Item is lent out - show info but disable editing
					JMenuItem lentInfo = new JMenuItem("Currently lent to " + item.getBorrower());
					lentInfo.setEnabled(false);
					menu.add(lentInfo);
				}
				else
				{
					JMenuItem editItem = new JMenuItem("Edit Listing");
					editItem.addActionListener(e -> showFullEditDialog());
					menu.add(editItem);

					JMenuItem removeItem = new JMenuItem("Remove from Marketplace");
					removeItem.addActionListener(e -> removeFromMarketplace());
					menu.add(removeItem);
				}
			}
			else
			{
				JMenuItem borrowItem = new JMenuItem("Request to Borrow");
				borrowItem.addActionListener(e -> requestToBorrow());
				menu.add(borrowItem);
			}

			return menu;
		}

		private void showFullEditDialog()
		{
			String groupId = groupService.getCurrentGroupIdUnchecked();
			if (groupId == null || groupId.isEmpty())
			{
				JOptionPane.showMessageDialog(DashboardPanel.this, "No active group.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			JPanel p = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = createDefaultGbc();
			gbc.anchor = GridBagConstraints.WEST;

			gbc.gridx = 0; gbc.gridy = 0; p.add(new JLabel("Item:"), gbc);
			gbc.gridx = 1; p.add(new JLabel(item.getItem()), gbc);
			gbc.gridx = 0; gbc.gridy = 1; p.add(new JLabel("Quantity:"), gbc);
			gbc.gridx = 1; JTextField qtyField = new JTextField(String.valueOf(item.getQuantity()), 10); p.add(qtyField, gbc);
			gbc.gridx = 0; gbc.gridy = 2; p.add(new JLabel("Value (GP):"), gbc);
			gbc.gridx = 1; JTextField valueField = new JTextField(String.valueOf(item.getValue()), 10); p.add(valueField, gbc);
			gbc.gridx = 0; gbc.gridy = 3; p.add(new JLabel("Collateral:"), gbc);
			gbc.gridx = 1; JTextField collateralField = new JTextField(
				item.getCollateralValue() != null ? String.valueOf(item.getCollateralValue()) : "0", 10); p.add(collateralField, gbc);
			gbc.gridx = 0; gbc.gridy = 4; p.add(new JLabel("Notes:"), gbc);
			gbc.gridx = 1; JTextField notesField = new JTextField(item.getNotes() != null ? item.getNotes() : "", 20); p.add(notesField, gbc);

			if (JOptionPane.showConfirmDialog(DashboardPanel.this, p, "Edit - " + item.getItem(),
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION)
			{
				try
				{
					int newQty = Integer.parseInt(qtyField.getText().trim());
					long newValue = Long.parseLong(valueField.getText().trim());
					int newCollateral = Integer.parseInt(collateralField.getText().trim());
					if (newQty <= 0) { JOptionPane.showMessageDialog(DashboardPanel.this, "Quantity must be > 0.", "Error", JOptionPane.ERROR_MESSAGE); return; }
					item.setQuantity(newQty);
					item.setValue(newValue);
					item.setCollateralValue(newCollateral);
					item.setCollateralType(newCollateral > 0 ? "GP" : "none");
					item.setNotes(notesField.getText().trim());
					dataService.updateAvailable(groupId, item.getLender(), item.getItem(), item.getItemId(), item);
					refresh();
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this, "Enter valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}

		private void removeFromMarketplace()
		{
			String groupId = groupService.getCurrentGroupIdUnchecked();
			if (groupId == null || groupId.isEmpty())
			{
				JOptionPane.showMessageDialog(DashboardPanel.this, "No active group.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
			if (JOptionPane.showConfirmDialog(DashboardPanel.this, "Remove " + item.getItem() + " from marketplace?",
				"Confirm", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
			{
				dataService.removeAvailable(groupId, item.getLender(), item.getItem(), item.getItemId());
				dataService.removeOffering(groupId, item.getLender(), item.getItem(), item.getItemId());
				dataService.loadGroupData(groupId);
				refresh();
			}
		}

		private void requestToBorrow()
		{
			String currentPlayer = getCurrentPlayerName();
			if (currentPlayer == null || currentPlayer.equals("Not logged in"))
			{
				JOptionPane.showMessageDialog(DashboardPanel.this, "You must be logged in to request items.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			String lender = item.getLender();
			String itemName = item.getItem();

			showBorrowRequestDialog(currentPlayer, lender, itemName, item.getItemId(), item.getQuantity());
		}

		private void showBorrowRequestDialog(String borrower, String lender, String itemName, int itemId, int quantity)
		{
			String groupId = groupService.getCurrentGroupIdUnchecked();
			String groupName = groupId != null ? groupService.getGroupNameById(groupId) : null;

			JPanel panel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = createDefaultGbc();
			gbc.gridy = 0; gbc.gridwidth = 2;
			panel.add(new JLabel("<html><b>Borrow: " + itemName + "</b> from " + lender +
				(groupName != null ? " <font color='#FFA500'>(" + groupName + ")</font>" : "") + "</html>"), gbc);

			gbc.gridy = 1; gbc.gridwidth = 1;
			panel.add(new JLabel("Duration:"), gbc);
			gbc.gridx = 1;
			JTextField durationField = new JTextField("7", 5);
			panel.add(durationField, gbc);

			gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
			JRadioButton daysRadio = new JRadioButton("Days", true);
			JRadioButton hoursRadio = new JRadioButton("Hours");
			ButtonGroup durationGroup = new ButtonGroup();
			durationGroup.add(daysRadio);
			durationGroup.add(hoursRadio);
			JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
			radioPanel.add(daysRadio);
			radioPanel.add(hoursRadio);
			panel.add(radioPanel, gbc);

			gbc.gridy = 3;
			JCheckBox agreeTermsCheck = new JCheckBox("<html>I agree to the borrowing terms<br>" +
				"<font size='2' color='#b0b0b0'>\u2022 No Wilderness \u2022 No trading \u2022 Return on time</font></html>");
			panel.add(agreeTermsCheck, gbc);

			gbc.gridy = 4;
			panel.add(new JLabel("<html><font color='orange'>Breaking terms may result in group removal!</font></html>"), gbc);

			int result = JOptionPane.showConfirmDialog(DashboardPanel.this, panel,
				"Borrow Request", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

			if (result == JOptionPane.OK_OPTION)
			{
				if (!agreeTermsCheck.isSelected())
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"You must agree to the borrowing terms.", "Terms Not Accepted", JOptionPane.WARNING_MESSAGE);
					return;
				}
				boolean isHours = hoursRadio.isSelected();
				try
				{
					int duration = Integer.parseInt(durationField.getText().trim());
					int maxValue = isHours ? 8760 : 365;
					if (duration <= 0 || duration > maxValue) throw new NumberFormatException();
					int durationDays = isHours ? Math.max(1, duration / 24) : duration;
					String durationDisplay = isHours ? duration + " hours" : duration + " days";
					plugin.sendBorrowRequest(borrower, lender, itemName, itemId, quantity, durationDays);
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Borrow request sent to " + lender + "!\nDuration: " + durationDisplay,
						"Request Sent", JOptionPane.INFORMATION_MESSAGE);
				}
				catch (NumberFormatException e)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Please enter a valid duration.", "Invalid Duration", JOptionPane.ERROR_MESSAGE);
				}
			}
		}
	}

	private String getCurrentPlayerName()
	{
		String name = plugin.getCurrentPlayerName();
		return name != null ? name : "Not logged in";
	}

	private javax.swing.border.TitledBorder createTitledBorder(String title)
	{
		return BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), title,
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(), Color.WHITE);
	}

	private static JPanel createEmptyStatePanel(String htmlMessage)
	{
		JPanel emptyPanel = new JPanel(new BorderLayout());
		emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

		JLabel emptyLabel = new JLabel(htmlMessage);
		emptyLabel.setForeground(Color.GRAY);
		emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
		emptyPanel.add(emptyLabel, BorderLayout.CENTER);

		return emptyPanel;
	}

	private static void addHoverEffect(JPanel card, Color hoverColor, Color normalColor, JPanel... subPanels)
	{
		card.addMouseListener(new java.awt.event.MouseAdapter()
		{
			private void setBg(Color c)
			{
				card.setBackground(c);
				for (JPanel p : subPanels)
				{
					p.setBackground(c);
				}
			}
			public void mouseEntered(java.awt.event.MouseEvent e) { setBg(hoverColor); }
			public void mouseExited(java.awt.event.MouseEvent e) { setBg(normalColor); }
		});
	}

	private static GridBagConstraints createDefaultGbc()
	{
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(4, 4, 4, 4);
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.gridx = 0;
		return gbc;
	}

	private class LoanCard extends JPanel
	{
		private final LendingEntry loan;

		public LoanCard(LendingEntry loan)
		{
			this.loan = loan;

			setLayout(new BorderLayout(10, 0));
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(10, 10, 10, 10)
			));

			// Left side: Item icon
			JLabel iconLabel = new JLabel();
			try
			{
				BufferedImage itemImage = itemManager.getImage(loan.getItemId(), loan.getQuantity(), loan.getQuantity() > 1);
				if (itemImage != null)
				{
					iconLabel.setIcon(new ImageIcon(itemImage));
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to load item icon for {}", loan.getItemId());
			}

			add(iconLabel, BorderLayout.WEST);

			// Center: Loan details
			JPanel detailsPanel = new JPanel();
			detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
			detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			// Item name
			JLabel itemLabel = new JLabel(loan.getItem() + " x" + loan.getQuantity());
			itemLabel.setFont(FontManager.getRunescapeBoldFont());
			itemLabel.setForeground(Color.WHITE);

			// Borrower name
			JLabel borrowerLabel = new JLabel("Lent to: " + loan.getBorrower());
			borrowerLabel.setFont(FontManager.getRunescapeSmallFont());
			borrowerLabel.setForeground(Color.LIGHT_GRAY);

			// Due time
			String dueTimeText = formatDueTime(loan.getDueTime());
			JLabel dueTimeLabel = new JLabel(dueTimeText);
			dueTimeLabel.setFont(FontManager.getRunescapeSmallFont());

			// Color code due time
			if (loan.isOverdue())
			{
				dueTimeLabel.setForeground(Color.RED);
			}
			else if (isDueSoon(loan.getDueTime()))
			{
				dueTimeLabel.setForeground(Color.YELLOW);
			}
			else
			{
				dueTimeLabel.setForeground(Color.GREEN);
			}

			detailsPanel.add(itemLabel);
			detailsPanel.add(Box.createVerticalStrut(3));
			detailsPanel.add(borrowerLabel);
			detailsPanel.add(Box.createVerticalStrut(3));
			detailsPanel.add(dueTimeLabel);

			add(detailsPanel, BorderLayout.CENTER);

			// Right side: Value
			JPanel valuePanel = new JPanel();
			valuePanel.setLayout(new BoxLayout(valuePanel, BoxLayout.Y_AXIS));
			valuePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			JLabel valueLabel = new JLabel(QuantityFormatter.quantityToStackSize(loan.getValue()) + " GP");
			valueLabel.setFont(FontManager.getRunescapeSmallFont());
			valueLabel.setForeground(Color.YELLOW);
			valueLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);

			valuePanel.add(valueLabel);

			add(valuePanel, BorderLayout.EAST);

			addHoverEffect(this, ColorScheme.DARKER_GRAY_HOVER_COLOR, ColorScheme.DARKER_GRAY_COLOR, detailsPanel, valuePanel);
		}

		private String formatDueTime(long dueTime)
		{
			if (dueTime <= 0)
			{
				return "No due date";
			}

			long now = Instant.now().toEpochMilli();
			if (dueTime < now)
			{
				// Overdue
				long daysOverdue = ChronoUnit.DAYS.between(Instant.ofEpochMilli(dueTime), Instant.now());
				return "OVERDUE by " + daysOverdue + " days";
			}
			else
			{
				// Due in future
				long daysUntilDue = ChronoUnit.DAYS.between(Instant.now(), Instant.ofEpochMilli(dueTime));
				if (daysUntilDue == 0)
				{
					return "Due TODAY";
				}
				else if (daysUntilDue == 1)
				{
					return "Due tomorrow";
				}
				else
				{
					return "Due in " + daysUntilDue + " days";
				}
			}
		}

		private boolean isDueSoon(long dueTime)
		{
			if (dueTime <= 0)
			{
				return false;
			}

			long now = Instant.now().toEpochMilli();
			long twoDaysFromNow = Instant.now().plus(2, ChronoUnit.DAYS).toEpochMilli();

			return dueTime > now && dueTime <= twoDaysFromNow;
		}
	}

	private void showAddItemDialog()
	{
		// Get active group
		com.guess34.lendingtracker.model.LendingGroup activeGroup = groupService.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(
				this,
				"Please select a group first before adding items to the marketplace.",
				"No Active Group",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		// Get current player name
		String currentPlayer = plugin.getClient().getLocalPlayer() != null
			? plugin.getClient().getLocalPlayer().getName()
			: null;

		if (currentPlayer == null)
		{
			JOptionPane.showMessageDialog(
				this,
				"You must be logged into the game to add items.",
				"Not Logged In",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		// Create proper JDialog like Create Set
		JDialog offerDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Offer Item to Marketplace", true);
		offerDialog.setLayout(new BorderLayout());
		offerDialog.setPreferredSize(new Dimension(400, 450));

		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Item search section with autocomplete
		JPanel itemSection = new JPanel(new BorderLayout(5, 5));
		itemSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		itemSection.setBorder(createTitledBorder("Search Item"));

		// Search field
		JTextField itemSearchField = new JTextField(20);
		itemSearchField.setToolTipText("Type item name to search");
		itemSection.add(itemSearchField, BorderLayout.NORTH);

		// Autocomplete suggestions
		DefaultListModel<ItemSuggestion> suggestionModel = new DefaultListModel<>();
		JList<ItemSuggestion> suggestionList = new JList<>(suggestionModel);
		suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		suggestionList.setVisibleRowCount(5);
		suggestionList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		suggestionList.setForeground(Color.WHITE);
		suggestionList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
		JScrollPane suggestionScroll = new JScrollPane(suggestionList);
		suggestionScroll.setPreferredSize(new Dimension(300, 120));
		itemSection.add(suggestionScroll, BorderLayout.CENTER);

		mainPanel.add(itemSection, BorderLayout.NORTH);

		// Selected item details section
		JPanel detailsSection = new JPanel(new BorderLayout(5, 5));
		detailsSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detailsSection.setBorder(createTitledBorder("Item Details"));
		JPanel detailsGrid = new JPanel(new GridBagLayout());
		detailsGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints gbc = createDefaultGbc();
		gbc.insets = new Insets(5, 5, 5, 5);

		gbc.gridx = 0; gbc.gridy = 0;
		detailsGrid.add(new JLabel("Selected:"), gbc);
		gbc.gridx = 1; gbc.weightx = 1.0;
		JLabel selectedItemDisplay = new JLabel("(None)");
		selectedItemDisplay.setForeground(Color.YELLOW);
		selectedItemDisplay.setFont(FontManager.getRunescapeBoldFont());
		detailsGrid.add(selectedItemDisplay, gbc);
		final int[] selectedItemId = {0};

		gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
		detailsGrid.add(new JLabel("Quantity:"), gbc);
		gbc.gridx = 1; gbc.weightx = 1.0;
		JTextField quantityField = new JTextField("1", 10);
		detailsGrid.add(quantityField, gbc);

		gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
		detailsGrid.add(new JLabel("Value (GP):"), gbc);
		gbc.gridx = 1; gbc.weightx = 1.0;
		JTextField valueField = new JTextField("0", 10);
		detailsGrid.add(valueField, gbc);
		detailsSection.add(detailsGrid, BorderLayout.CENTER);

		JLabel totalValueLabel = new JLabel("Total: 0 GP");
		totalValueLabel.setForeground(Color.YELLOW);
		totalValueLabel.setFont(FontManager.getRunescapeBoldFont());
		totalValueLabel.setHorizontalAlignment(SwingConstants.CENTER);
		detailsSection.add(totalValueLabel, BorderLayout.SOUTH);
		mainPanel.add(detailsSection, BorderLayout.CENTER);

		// Buttons at bottom
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton offerButton = new JButton("Offer Item");
		offerButton.setBackground(ColorScheme.BRAND_ORANGE);
		offerButton.setForeground(Color.WHITE);
		offerButton.setEnabled(false); // Disabled until item selected

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cancelButton.setForeground(Color.WHITE);

		buttonPanel.add(offerButton);
		buttonPanel.add(cancelButton);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		offerDialog.add(mainPanel);

		// Auto-update total when quantity or value changes
		Runnable updateTotal = () -> {
			try
			{
				int qty = Integer.parseInt(quantityField.getText().trim());
				long val = Long.parseLong(valueField.getText().trim());
				totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(qty * val) + " GP");
			}
			catch (NumberFormatException ignored) { totalValueLabel.setText("Total: 0 GP"); }
		};
		javax.swing.event.DocumentListener totalUpdater = new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTotal.run(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTotal.run(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { updateTotal.run(); }
		};
		quantityField.getDocument().addDocumentListener(totalUpdater);
		valueField.getDocument().addDocumentListener(totalUpdater);

		attachItemSearchListener(itemSearchField, suggestionModel, 10);

		// Selection listener - selects item on click
		suggestionList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting()) return;
			ItemSuggestion selected = suggestionList.getSelectedValue();
			if (selected != null)
			{
				selectedItemId[0] = selected.getItemId();
				selectedItemDisplay.setText(selected.getName());
				valueField.setText(String.valueOf(selected.getGePrice()));
				offerButton.setEnabled(true);
				updateTotal.run();
			}
		});

		// Offer button action
		offerButton.addActionListener(e ->
		{
			try
			{
				int itemId = selectedItemId[0];
				String itemName = selectedItemDisplay.getText();
				int quantity = Integer.parseInt(quantityField.getText().trim());
				long value = Long.parseLong(valueField.getText().trim());
				if (itemId <= 0 || itemName.equals("(None)"))
				{
					JOptionPane.showMessageDialog(offerDialog, "Please select an item.", "No Item", JOptionPane.WARNING_MESSAGE);
					return;
				}
				if (quantity <= 0)
				{
					JOptionPane.showMessageDialog(offerDialog, "Quantity must be > 0.", "Invalid", JOptionPane.WARNING_MESSAGE);
					return;
				}
				String summary = String.format("Item: %s\nQuantity: %d\nValue: %s GP\n\nAdd to marketplace?",
					itemName, quantity, QuantityFormatter.quantityToStackSize(value * quantity));
				if (JOptionPane.showConfirmDialog(offerDialog, summary, "Confirm Offer",
					JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;

				LendingEntry entry = new LendingEntry();
				entry.setItemId(itemId);
				entry.setItem(itemName);
				entry.setItemName(itemName);
				entry.setQuantity(quantity);
				entry.setLender(currentPlayer);
				entry.setBorrower(null);
				entry.setValue(value);
				entry.setLendTime(System.currentTimeMillis());
				entry.setDueTime(0L);
				entry.setGroupId(activeGroup.getId());
				dataService.addOffering(activeGroup.getId(), currentPlayer, entry);
				offerDialog.dispose();
				refresh();
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(offerDialog, "Enter valid numbers.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
			}
			catch (Exception ex)
			{
				log.error("Failed to add item", ex);
				JOptionPane.showMessageDialog(offerDialog, "Failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			}
		});

		// Cancel button action
		cancelButton.addActionListener(e -> offerDialog.dispose());

		// Show dialog
		offerDialog.pack();
		offerDialog.setLocationRelativeTo(this);
		offerDialog.setVisible(true);
	}

	/**
	 * Attach a debounced autocomplete search listener to a text field.
	 * Searches items after 200ms delay and populates the suggestion model.
	 */
	private void attachItemSearchListener(JTextField searchField, DefaultListModel<ItemSuggestion> model, int maxResults)
	{
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private javax.swing.Timer searchTimer;

			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) { scheduleSearch(); }

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) { scheduleSearch(); }

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) { scheduleSearch(); }

			private void scheduleSearch()
			{
				if (searchTimer != null && searchTimer.isRunning())
				{
					searchTimer.stop();
				}
				searchTimer = new javax.swing.Timer(200, evt ->
				{
					String query = searchField.getText().trim().toLowerCase();
					model.clear();
					if (query.length() < 2) return;
					plugin.getClientThread().invokeLater(() ->
					{
						java.util.List<ItemSuggestion> results = searchItems(query, maxResults);
						SwingUtilities.invokeLater(() ->
						{
							for (ItemSuggestion item : results)
							{
								model.addElement(item);
							}
						});
					});
				});
				searchTimer.setRepeats(false);
				searchTimer.start();
			}
		});
	}

	// Uses canonicalize to skip noted/placeholder duplicates
	private java.util.List<ItemSuggestion> searchItems(String query, int maxResults)
	{
		java.util.List<ItemSuggestion> results = new java.util.ArrayList<>();
		java.util.Set<Integer> seenIds = new java.util.HashSet<>();

		try
		{
			int itemCount = plugin.getClient().getItemCount();
			for (int i = 0; i < itemCount && results.size() < maxResults; i++)
			{
				try
				{
					// Canonicalize to skip noted/placeholder variants
					int canonId = itemManager.canonicalize(i);
					if (seenIds.contains(canonId))
					{
						continue;
					}
					seenIds.add(canonId);

					net.runelite.api.ItemComposition comp = itemManager.getItemComposition(canonId);
					if (comp != null && comp.getName() != null && !comp.getName().equals("null"))
					{
						String name = comp.getName().toLowerCase();
						if (name.contains(query) && comp.isTradeable())
						{
							int gePrice = itemManager.getItemPrice(canonId);
							results.add(new ItemSuggestion(canonId, comp.getName(), gePrice));
						}
					}
				}
				catch (Exception ignored)
				{
					// Skip items that can't be loaded
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Error searching items", e);
		}

		// Sort by relevance (exact match first, then starts-with, then contains)
		results.sort((a, b) ->
		{
			String aLower = a.getName().toLowerCase();
			String bLower = b.getName().toLowerCase();
			boolean aExact = aLower.equals(query);
			boolean bExact = bLower.equals(query);
			if (aExact && !bExact) return -1;
			if (!aExact && bExact) return 1;
			boolean aStarts = aLower.startsWith(query);
			boolean bStarts = bLower.startsWith(query);
			if (aStarts && !bStarts) return -1;
			if (!aStarts && bStarts) return 1;
			return a.getName().compareToIgnoreCase(b.getName());
		});

		return results;
	}

	private static class ItemSuggestion
	{
		private final int itemId;
		private final String name;
		private final int gePrice;

		public ItemSuggestion(int itemId, String name, int gePrice)
		{
			this.itemId = itemId;
			this.name = name;
			this.gePrice = gePrice;
		}

		public int getItemId() { return itemId; }
		public String getName() { return name; }
		public int getGePrice() { return gePrice; }

		@Override
		public String toString()
		{
			return name + " (ID: " + itemId + ") - " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(gePrice) + " GP";
		}
	}

	private void showLookingForDialog()
	{
		// Get active group
		com.guess34.lendingtracker.model.LendingGroup activeGroup = groupService.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(
				this,
				"Please select a group first.",
				"No Active Group",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		String currentPlayer = getCurrentPlayerName();
		if (currentPlayer == null || currentPlayer.equals("Not logged in"))
		{
			JOptionPane.showMessageDialog(
				this,
				"You must be logged in to post a request.",
				"Not Logged In",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		// Create dialog matching Create Set style
		JDialog lookingForDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Looking For Items", true);
		lookingForDialog.setLayout(new BorderLayout());
		lookingForDialog.setPreferredSize(new Dimension(450, 500));

		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Top section: Request details
		JPanel detailsPanel = new JPanel(new GridBagLayout());
		detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints gbc = createDefaultGbc();
		gbc.insets = new Insets(3, 3, 3, 3);

		gbc.gridx = 0; gbc.gridy = 0;
		detailsPanel.add(new JLabel("Title:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JTextField titleField = new JTextField(20);
		detailsPanel.add(titleField, gbc);

		gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1;
		detailsPanel.add(new JLabel("Duration (days):"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JTextField durationField = new JTextField("7", 5);
		detailsPanel.add(durationField, gbc);

		gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1;
		detailsPanel.add(new JLabel("Notes:"), gbc);
		gbc.gridx = 1; gbc.gridwidth = 2;
		JTextField notesField = new JTextField(20);
		detailsPanel.add(notesField, gbc);

		mainPanel.add(detailsPanel, BorderLayout.NORTH);

		// Middle section: Add items with autocomplete
		JPanel itemsSection = new JPanel(new BorderLayout(5, 5));
		itemsSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		itemsSection.setBorder(createTitledBorder("Items Needed"));

		// Item entry row
		JPanel addItemRow = new JPanel(new BorderLayout(5, 0));
		addItemRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JTextField itemSearchField = new JTextField(15);
		itemSearchField.setToolTipText("Type item name to search");
		addItemRow.add(itemSearchField, BorderLayout.CENTER);

		JTextField qtyField = new JTextField("1", 3);
		qtyField.setToolTipText("Quantity");
		addItemRow.add(qtyField, BorderLayout.EAST);

		itemsSection.add(addItemRow, BorderLayout.NORTH);

		// Autocomplete suggestions
		DefaultListModel<ItemSuggestion> suggestionModel = new DefaultListModel<>();
		JList<ItemSuggestion> suggestionList = new JList<>(suggestionModel);
		suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		suggestionList.setVisibleRowCount(4);
		suggestionList.setBackground(ColorScheme.DARK_GRAY_COLOR);
		suggestionList.setForeground(Color.WHITE);
		suggestionList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
		JScrollPane suggestionScroll = new JScrollPane(suggestionList);
		suggestionScroll.setPreferredSize(new Dimension(250, 80));
		itemsSection.add(suggestionScroll, BorderLayout.CENTER);

		// Items added to request
		DefaultListModel<LookingForItem> requestItemsModel = new DefaultListModel<>();
		JList<LookingForItem> requestItemsList = new JList<>(requestItemsModel);
		requestItemsList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		requestItemsList.setForeground(Color.WHITE);
		requestItemsList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
		requestItemsList.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof LookingForItem)
				{
					LookingForItem item = (LookingForItem) value;
					setText(item.itemName + " x" + item.quantity + " (" + QuantityFormatter.quantityToStackSize(item.value * item.quantity) + " GP)");
				}
				setBackground(isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
				return this;
			}
		});
		JScrollPane requestItemsScroll = new JScrollPane(requestItemsList);
		requestItemsScroll.setPreferredSize(new Dimension(250, 120));
		requestItemsScroll.setBorder(createTitledBorder("Added Items (double-click to remove)"));
		itemsSection.add(requestItemsScroll, BorderLayout.SOUTH);

		mainPanel.add(itemsSection, BorderLayout.CENTER);

		// Total value display
		JLabel totalValueDisplay = new JLabel("Total Value: 0 GP");
		totalValueDisplay.setForeground(Color.YELLOW);
		totalValueDisplay.setFont(FontManager.getRunescapeBoldFont());
		totalValueDisplay.setHorizontalAlignment(SwingConstants.CENTER);

		JPanel bottomPanel = new JPanel(new BorderLayout());
		bottomPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bottomPanel.add(totalValueDisplay, BorderLayout.NORTH);

		// Buttons
		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
		buttonRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton postButton = new JButton("Post Request");
		postButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
		postButton.setForeground(Color.WHITE);

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cancelButton.setForeground(Color.WHITE);

		buttonRow.add(postButton);
		buttonRow.add(cancelButton);
		bottomPanel.add(buttonRow, BorderLayout.SOUTH);

		mainPanel.add(bottomPanel, BorderLayout.SOUTH);

		lookingForDialog.add(mainPanel);

		// Autocomplete search with debounce
		attachItemSearchListener(itemSearchField, suggestionModel, 8);

		// Helper to recalculate total value display
		Runnable updateLfTotal = () -> {
			long total = 0;
			for (int i = 0; i < requestItemsModel.size(); i++)
			{
				LookingForItem it = requestItemsModel.get(i);
				total += (long) it.value * it.quantity;
			}
			totalValueDisplay.setText("Total Value: " + QuantityFormatter.quantityToStackSize(total) + " GP");
		};

		// Add item on single-click selection from autocomplete
		suggestionList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting()) return;
			ItemSuggestion selected = suggestionList.getSelectedValue();
			if (selected == null) return;
			try
			{
				int qty = Integer.parseInt(qtyField.getText().trim());
				if (qty <= 0) qty = 1;
				for (int i = 0; i < requestItemsModel.size(); i++)
				{
					if (requestItemsModel.get(i).itemId == selected.getItemId())
					{
						JOptionPane.showMessageDialog(lookingForDialog, "Item already in request.", "Duplicate", JOptionPane.WARNING_MESSAGE);
						suggestionList.clearSelection();
						return;
					}
				}
				LookingForItem item = new LookingForItem();
				item.itemId = selected.getItemId();
				item.itemName = selected.getName();
				item.quantity = qty;
				item.value = selected.getGePrice();
				requestItemsModel.addElement(item);
				updateLfTotal.run();
				itemSearchField.setText("");
				qtyField.setText("1");
				suggestionModel.clear();
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(lookingForDialog, "Invalid quantity", "Error", JOptionPane.ERROR_MESSAGE);
			}
		});

		// Remove item when double-clicked in request list
		requestItemsList.addMouseListener(new java.awt.event.MouseAdapter()
		{
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2 && requestItemsList.getSelectedIndex() >= 0)
				{
					requestItemsModel.remove(requestItemsList.getSelectedIndex());
					updateLfTotal.run();
				}
			}
		});

		// Post button action
		final String finalCurrentPlayer = currentPlayer;
		postButton.addActionListener(e ->
		{
			if (requestItemsModel.isEmpty())
			{
				JOptionPane.showMessageDialog(lookingForDialog, "Please add at least one item.", "No Items", JOptionPane.WARNING_MESSAGE);
				return;
			}
			try
			{
				int duration = Integer.parseInt(durationField.getText().trim());
				String title = titleField.getText().trim();
				String notes = notesField.getText().trim();
				java.util.List<LookingForItem> items = new java.util.ArrayList<>();
				for (int i = 0; i < requestItemsModel.size(); i++) items.add(requestItemsModel.get(i));
				String displayName = title.isEmpty()
					? (items.size() == 1 ? items.get(0).itemName : items.get(0).itemName + " + " + (items.size() - 1) + " more")
					: title;
				StringBuilder itemsStr = new StringBuilder();
				for (int i = 0; i < items.size(); i++)
				{
					if (i > 0) itemsStr.append(",");
					LookingForItem item = items.get(i);
					itemsStr.append(item.itemId).append(":").append(item.itemName.replace(":", "").replace(",", ""))
						.append(":").append(item.quantity).append(":").append(item.value);
				}
				String groupId = activeGroup.getId();
				String requestId = String.valueOf(System.currentTimeMillis());
				saveLookingForRequest(groupId, requestId, String.format("%s|%s|%d|%d|%s|%s",
					finalCurrentPlayer, displayName, items.size(), duration, notes, itemsStr.toString()));
				long totalValue = items.stream().mapToLong(it -> it.value * it.quantity).sum();
				final String chatMsg = String.format("[Lending Tracker] %s is looking for: %s (%d items, %s GP) for %d days",
					finalCurrentPlayer, displayName, items.size(), QuantityFormatter.quantityToStackSize(totalValue), duration);
				if (plugin.getClientThread() != null)
				{
					plugin.getClientThread().invokeLater(() -> {
						try { plugin.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", chatMsg, null); }
						catch (Exception ex) { log.warn("Failed to add chat message", ex); }
					});
				}
				JOptionPane.showMessageDialog(lookingForDialog,
					"Request posted! Items: " + items.size() + " | " + QuantityFormatter.quantityToStackSize(totalValue) + " GP | " + duration + " days",
					"Request Posted", JOptionPane.INFORMATION_MESSAGE);
				lookingForDialog.dispose();
				refresh();
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(lookingForDialog, "Invalid duration", "Error", JOptionPane.ERROR_MESSAGE);
			}
		});

		cancelButton.addActionListener(e -> lookingForDialog.dispose());

		lookingForDialog.pack();
		lookingForDialog.setLocationRelativeTo(this);
		lookingForDialog.setVisible(true);
	}

	private List<LookingForRequest> getLookingForRequests(String groupId)
	{
		if (groupId == null || groupId.isEmpty())
		{
			return new java.util.ArrayList<>();
		}

		// Check cache first - source of truth once populated
		if (lookingForCache.containsKey(groupId))
		{
			List<LookingForRequest> cached = lookingForCache.get(groupId);
			return cached != null ? new java.util.ArrayList<>(cached) : new java.util.ArrayList<>();
		}

		// First time loading this group - load from config and cache it
		List<LookingForRequest> requests = new java.util.ArrayList<>();
		try
		{
			String requestIdsKey = "lookingForIds." + groupId;
			String requestIdsStr = plugin.getConfigManager().getConfiguration("lendingtracker", requestIdsKey);

			if (requestIdsStr != null && !requestIdsStr.isEmpty())
			{
				String[] requestIds = requestIdsStr.split(",");
				for (String requestId : requestIds)
				{
					String requestKey = "lookingFor." + groupId + "." + requestId;
					String requestData = plugin.getConfigManager().getConfiguration("lendingtracker", requestKey);

					if (requestData != null && !requestData.isEmpty())
					{
						LookingForRequest request = LookingForRequest.parse(requestId, requestData);
						if (request != null)
						{
							requests.add(request);
						}
					}
				}
			}

			lookingForCache.put(groupId, new java.util.ArrayList<>(requests));
		}
		catch (Exception e)
		{
			log.error("Failed to load looking for requests", e);
		}

		return requests;
	}

	private void saveLookingForRequest(String groupId, String requestId, String requestData)
	{
		// Save the request data to config (for persistence)
		String requestKey = "lookingFor." + groupId + "." + requestId;
		plugin.getConfigManager().setConfiguration("lendingtracker", requestKey, requestData);

		// Update the list of request IDs in config
		String requestIdsKey = "lookingForIds." + groupId;
		String existingIds = plugin.getConfigManager().getConfiguration("lendingtracker", requestIdsKey);

		String newIds;
		if (existingIds == null || existingIds.isEmpty())
		{
			newIds = requestId;
		}
		else
		{
			newIds = existingIds + "," + requestId;
		}
		plugin.getConfigManager().setConfiguration("lendingtracker", requestIdsKey, newIds);

		// Also update in-memory cache for immediate display
		LookingForRequest newRequest = LookingForRequest.parse(requestId, requestData);
		if (newRequest != null)
		{
			lookingForCache.computeIfAbsent(groupId, k -> new java.util.ArrayList<>()).add(newRequest);
		}
	}

	private void removeLookingForRequest(String groupId, String requestId)
	{
		// Remove the request data from config
		String requestKey = "lookingFor." + groupId + "." + requestId;
		plugin.getConfigManager().unsetConfiguration("lendingtracker", requestKey);

		// Update the list of request IDs in config
		String requestIdsKey = "lookingForIds." + groupId;
		String existingIds = plugin.getConfigManager().getConfiguration("lendingtracker", requestIdsKey);

		if (existingIds != null && !existingIds.isEmpty())
		{
			String[] ids = existingIds.split(",");
			StringBuilder newIds = new StringBuilder();
			for (String id : ids)
			{
				if (!id.equals(requestId))
				{
					if (newIds.length() > 0) newIds.append(",");
					newIds.append(id);
				}
			}
			plugin.getConfigManager().setConfiguration("lendingtracker", requestIdsKey, newIds.toString());
		}

		// Also remove from in-memory cache
		List<LookingForRequest> cached = lookingForCache.get(groupId);
		if (cached != null) cached.removeIf(r -> r.id != null && r.id.equals(requestId));
	}

	private static class LookingForRequest
	{
		String id;
		String requesterName;
		String itemName;        // Primary item name (for display) or request title
		int quantity;           // Quantity of primary item or total items count
		int durationDays;
		String notes;
		long postedTime;
		java.util.List<LookingForItem> items = new java.util.ArrayList<>();

		static LookingForRequest parse(String id, String data)
		{
			try
			{
				String[] parts = data.split("\\|", 6);
				if (parts.length < 4) return null;
				LookingForRequest r = new LookingForRequest();
				r.id = id;
				r.requesterName = parts[0];
				r.itemName = parts[1];
				r.quantity = Integer.parseInt(parts[2]);
				r.durationDays = Integer.parseInt(parts[3]);
				r.notes = parts.length > 4 ? parts[4] : "";
				if (parts.length > 5 && !parts[5].isEmpty())
				{
					for (String itemPart : parts[5].split(","))
					{
						String[] d = itemPart.split(":");
						if (d.length >= 3)
						{
							try
							{
								LookingForItem item = new LookingForItem();
								item.itemId = Integer.parseInt(d[0]);
								item.itemName = d[1];
								item.quantity = Integer.parseInt(d[2]);
								item.value = d.length > 3 ? Long.parseLong(d[3]) : 0;
								r.items.add(item);
							}
							catch (NumberFormatException ignored) {}
						}
					}
				}
				try { r.postedTime = Long.parseLong(id); }
				catch (NumberFormatException e) { r.postedTime = System.currentTimeMillis(); }
				return r;
			}
			catch (Exception e) { return null; }
		}

		String getPostedTimeFormatted()
		{
			if (postedTime == 0) return "Unknown";
			long d = System.currentTimeMillis() - postedTime;
			if (d < 60000) return "Just now";
			if (d < 3600000) return (d / 60000) + "m ago";
			if (d < 86400000) return (d / 3600000) + "h ago";
			return (d / 86400000) + "d ago";
		}

		long getTotalValue() { return items.stream().mapToLong(i -> i.value * i.quantity).sum(); }
		int getItemCount() { return items.isEmpty() ? 1 : items.size(); }
		boolean isMultiItem() { return items.size() > 1; }
	}

	private static class LookingForItem
	{
		int itemId;
		String itemName;
		int quantity;
		long value;
	}

	private class LookingForCard extends JPanel
	{
		private final LookingForRequest request;
		private final JPanel detailsPanel;
		private final JPanel rightPanel;

		public LookingForCard(LookingForRequest request)
		{
			this.request = request;

			setLayout(new BorderLayout(5, 0));
			// Use a blue-tinted background to differentiate from offerings
			Color bgColor = new Color(45, 50, 60); // Blue-gray
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(8, 8, 8, 8)
			));

			setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
			setPreferredSize(new Dimension(200, 60));

			if (request.isMultiItem())
			{
				StringBuilder tooltipSb = new StringBuilder();
				tooltipSb.append("<html><b>").append(request.itemName).append("</b><br>");
				tooltipSb.append(request.getItemCount()).append(" items | ").append(QuantityFormatter.quantityToStackSize(request.getTotalValue())).append(" GP<br>");
				tooltipSb.append("<i>Right-click to view all items</i></html>");
				setToolTipText(tooltipSb.toString());
			}
			else
			{
				setToolTipText("<html><b>" + request.itemName + "</b> x" + request.quantity + "<br>" +
					"Duration: " + request.durationDays + " days<br>" +
					(request.notes != null && !request.notes.isEmpty() ? "Note: " + request.notes : "") + "</html>");
			}

			// Left side: "Want" icon/indicator
			JLabel iconLabel = new JLabel("WANT");
			iconLabel.setFont(FontManager.getRunescapeSmallFont());
			iconLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
			iconLabel.setPreferredSize(new Dimension(36, 36));
			iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
			add(iconLabel, BorderLayout.WEST);

			// Center: Request details
			detailsPanel = new JPanel();
			detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
			detailsPanel.setBackground(bgColor);

			// Item name
			String itemName = request.itemName;
			if (itemName.length() > 18) itemName = itemName.substring(0, 15) + "...";
			JLabel itemLabel = new JLabel(itemName + " x" + request.quantity);
			itemLabel.setFont(FontManager.getRunescapeSmallFont());
			itemLabel.setForeground(Color.WHITE);

			// Requester name
			String requesterText = "By: " + request.requesterName;
			JLabel requesterLabel = new JLabel(requesterText);
			requesterLabel.setFont(FontManager.getRunescapeSmallFont());
			requesterLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			detailsPanel.add(itemLabel);
			detailsPanel.add(requesterLabel);

			add(detailsPanel, BorderLayout.CENTER);

			// Right side: Duration and posted time
			rightPanel = new JPanel();
			rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
			rightPanel.setBackground(bgColor);
			rightPanel.setPreferredSize(new Dimension(55, 45));

			JLabel durationLabel = new JLabel(request.durationDays + " days");
			durationLabel.setFont(FontManager.getRunescapeSmallFont());
			durationLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);

			JLabel timeLabel = new JLabel(request.getPostedTimeFormatted());
			timeLabel.setFont(FontManager.getRunescapeSmallFont());
			timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			rightPanel.add(durationLabel);
			rightPanel.add(timeLabel);

			add(rightPanel, BorderLayout.EAST);

			setComponentPopupMenu(createLookingForPopupMenu());
			addHoverEffect(this, new Color(55, 60, 70), bgColor, detailsPanel, rightPanel);
		}

		private JPopupMenu createLookingForPopupMenu()
		{
			JPopupMenu menu = new JPopupMenu();
			String currentPlayer = getCurrentPlayerName();
			boolean isOwner = request.requesterName != null && request.requesterName.equalsIgnoreCase(currentPlayer);

			// View items option for multi-item requests (details shown via tooltip)
			if (request.isMultiItem())
			{
				JMenuItem viewItemsItem = new JMenuItem(request.getItemCount() + " items - " +
					QuantityFormatter.quantityToStackSize(request.getTotalValue()) + " GP");
				viewItemsItem.setEnabled(false);
				menu.add(viewItemsItem);
				menu.addSeparator();
			}

			if (isOwner)
			{
				JMenuItem editItem = new JMenuItem("Edit Request");
				editItem.addActionListener(e -> showEditLookingForDialog(request));
				menu.add(editItem);

				// Owner can remove their own request
				JMenuItem removeItem = new JMenuItem("Remove Request");
				removeItem.addActionListener(e -> {
					int confirm = JOptionPane.showConfirmDialog(
						DashboardPanel.this,
						"Remove your request for " + request.itemName + "?",
						"Confirm Remove",
						JOptionPane.YES_NO_OPTION
					);
					if (confirm == JOptionPane.YES_OPTION)
					{
						String groupId = groupService.getCurrentGroupIdUnchecked();
						if (groupId != null)
						{
							removeLookingForRequest(groupId, request.id);
							refresh();
						}
					}
				});
				menu.add(removeItem);
			}
			else
			{
				JMenuItem offerItem = new JMenuItem("I Have This Item");
				offerItem.addActionListener(e -> {
					showOfferToLendDialog(currentPlayer, request);
				});
				menu.add(offerItem);
			}

			// Show notes if any
			if (request.notes != null && !request.notes.isEmpty())
			{
				menu.addSeparator();
				JMenuItem notesItem = new JMenuItem("Notes: " + request.notes);
				notesItem.setEnabled(false);
				menu.add(notesItem);
			}

			return menu;
		}

		private void showEditLookingForDialog(LookingForRequest request)
		{
			JPanel editPanel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = createDefaultGbc();
			gbc.anchor = GridBagConstraints.WEST;

			gbc.gridx = 0; gbc.gridy = 0;
			editPanel.add(new JLabel("Item Name:"), gbc);
			gbc.gridx = 1;
			JTextField itemNameField = new JTextField(request.itemName, 20);
			editPanel.add(itemNameField, gbc);

			gbc.gridx = 0; gbc.gridy = 1;
			editPanel.add(new JLabel("Quantity:"), gbc);
			gbc.gridx = 1;
			JTextField quantityField = new JTextField(String.valueOf(request.quantity), 10);
			editPanel.add(quantityField, gbc);

			gbc.gridx = 0; gbc.gridy = 2;
			editPanel.add(new JLabel("Duration (days):"), gbc);
			gbc.gridx = 1;
			JTextField durationField = new JTextField(String.valueOf(request.durationDays), 10);
			editPanel.add(durationField, gbc);

			gbc.gridx = 0; gbc.gridy = 3;
			editPanel.add(new JLabel("Notes:"), gbc);
			gbc.gridx = 1;
			JTextField notesField = new JTextField(request.notes != null ? request.notes : "", 20);
			editPanel.add(notesField, gbc);

			int result = JOptionPane.showConfirmDialog(
				DashboardPanel.this,
				editPanel,
				"Edit Request",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE
			);

			if (result == JOptionPane.OK_OPTION)
			{
				try
				{
					String newItemName = itemNameField.getText().trim();
					int newQty = Integer.parseInt(quantityField.getText().trim());
					int newDuration = Integer.parseInt(durationField.getText().trim());
					String newNotes = notesField.getText().trim();

					if (newItemName.isEmpty())
					{
						JOptionPane.showMessageDialog(DashboardPanel.this,
							"Item name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}

					// Update the request data and re-save
					String groupId = groupService.getCurrentGroupIdUnchecked();
					if (groupId != null)
					{
						String requestData = String.format("%s|%s|%d|%d|%s|",
							request.requesterName, newItemName, newQty, newDuration, newNotes);
						saveLookingForRequest(groupId, request.id, requestData);
						refresh();
					}
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Please enter valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}

		private void showOfferToLendDialog(String lender, LookingForRequest request)
		{
			String groupId = groupService.getCurrentGroupIdUnchecked();
			String groupName = groupId != null ? groupService.getGroupNameById(groupId) : null;

			JPanel panel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = createDefaultGbc();
			gbc.gridy = 0; gbc.gridwidth = 2;
			panel.add(new JLabel("<html><b>Offer: " + request.itemName + "</b> to " + request.requesterName +
				(groupName != null ? " <font color='#FFA500'>(" + groupName + ")</font>" : "") +
				"<br><font color='gray'>Requested: x" + request.quantity + " for " + request.durationDays + " days</font></html>"), gbc);

			gbc.gridy = 1; gbc.gridwidth = 1;
			panel.add(new JLabel("Quantity:"), gbc);
			gbc.gridx = 1;
			JTextField qtyField = new JTextField(String.valueOf(request.quantity), 5);
			panel.add(qtyField, gbc);

			gbc.gridx = 0; gbc.gridy = 2;
			panel.add(new JLabel("Duration:"), gbc);
			gbc.gridx = 1;
			JTextField durationField = new JTextField(String.valueOf(request.durationDays), 5);
			panel.add(durationField, gbc);

			gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
			JRadioButton daysRadio = new JRadioButton("Days", true);
			JRadioButton hoursRadio = new JRadioButton("Hours");
			ButtonGroup durationGroup = new ButtonGroup();
			durationGroup.add(daysRadio);
			durationGroup.add(hoursRadio);
			JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
			radioPanel.add(daysRadio);
			radioPanel.add(hoursRadio);
			panel.add(radioPanel, gbc);

			gbc.gridy = 4;
			JCheckBox applyTerms = new JCheckBox("<html>Apply standard lending terms<br>" +
				"<font size='2' color='#b0b0b0'>\u2022 No Wilderness \u2022 No trading \u2022 Return on time</font></html>");
			applyTerms.setSelected(true);
			panel.add(applyTerms, gbc);

			gbc.gridy = 5; gbc.gridwidth = 1;
			panel.add(new JLabel("Message:"), gbc);
			gbc.gridx = 1;
			JTextField msgField = new JTextField(15);
			panel.add(msgField, gbc);

			int result = JOptionPane.showConfirmDialog(DashboardPanel.this, panel,
				"Offer Item", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

			if (result == JOptionPane.OK_OPTION)
			{
				try
				{
					int offerQty = Integer.parseInt(qtyField.getText().trim());
					int duration = Integer.parseInt(durationField.getText().trim());
					if (offerQty <= 0 || duration <= 0) throw new NumberFormatException();
					if (hoursRadio.isSelected()) duration = -duration; // Negative = hours
					String durationDisplay = duration < 0 ? Math.abs(duration) + " hours" : duration + " days";
					int durationDays = duration < 0 ? Math.max(1, Math.abs(duration) / 24) : duration;
					plugin.sendLendOffer(lender, request.requesterName, request.itemName, offerQty, durationDays, msgField.getText().trim(), durationDisplay);
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Offer sent to " + request.requesterName + "!\nItem: " + request.itemName + " x" + offerQty + "\nDuration: " + durationDisplay,
						"Offer Sent", JOptionPane.INFORMATION_MESSAGE);
				}
				catch (NumberFormatException e)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Please enter valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}
	}
}
