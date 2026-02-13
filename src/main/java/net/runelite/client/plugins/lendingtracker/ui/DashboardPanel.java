package net.runelite.client.plugins.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.lendingtracker.LendingTrackerConfig;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.services.core.ItemSetManager;
import net.runelite.client.plugins.lendingtracker.services.core.LendingManager;
import net.runelite.client.plugins.lendingtracker.services.core.MarketplaceManager;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.plugins.lendingtracker.model.ItemSet;
import net.runelite.client.plugins.lendingtracker.services.OnlineStatusService;
import net.runelite.client.plugins.lendingtracker.services.Recorder;
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
 * DashboardPanel - Active loans view with summary header
 * Phase 3: Card-based UI showing all active lendings
 */
@Slf4j
public class DashboardPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final LendingManager lendingManager;
	private final MarketplaceManager marketplaceManager;
	private final ItemSetManager itemSetManager;
	private final GroupConfigStore groupConfigStore;
	private final ItemManager itemManager;
	private final Recorder recorder;

	private final JLabel totalValueLabel;
	private final JLabel activeLoansLabel;
	private final JLabel overdueCountLabel;
	private final JPanel loanListPanel;

	// FIXED: In-memory cache for Looking For requests to ensure immediate display after saving
	// This mirrors how Recorder handles marketplace items for immediate updates
	private final java.util.Map<String, List<LookingForRequest>> lookingForCache = new java.util.concurrent.ConcurrentHashMap<>();

	// ADDED: Timer for periodic online status refresh (updates dots on the fly)
	private final Timer onlineStatusRefreshTimer;
	private static final int ONLINE_STATUS_REFRESH_INTERVAL = 30000; // 30 seconds

	// CHANGED: Track collapsed sections - all start collapsed for a clean initial view
	private final java.util.Set<String> collapsedSections = new java.util.HashSet<>(
		java.util.Arrays.asList("marketplace", "lookingfor", "loans", "itemsets")
	);

	public DashboardPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.lendingManager = plugin.getLendingManager();
		this.marketplaceManager = plugin.getMarketplaceManager();
		this.itemSetManager = plugin.getItemSetManager();
		this.groupConfigStore = plugin.getGroupConfigStore();
		this.itemManager = plugin.getItemManager();
		this.recorder = plugin.getRecorder();

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
		// FIXED: Use wrapper panel with BorderLayout to ensure items display top-down
		JPanel loanListWrapper = new JPanel(new BorderLayout());
		loanListWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);

		loanListPanel = new JPanel();
		loanListPanel.setLayout(new BoxLayout(loanListPanel, BoxLayout.Y_AXIS));
		loanListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// FIXED: Add loanListPanel to NORTH of wrapper so items stack from top
		loanListWrapper.add(loanListPanel, BorderLayout.NORTH);

		JScrollPane scrollPane = new JScrollPane(loanListWrapper);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
		// FIXED: Only vertical scrolling, no horizontal
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(scrollPane, BorderLayout.CENTER);

		// CHANGED: Simplified button panel - 2 primary buttons + More dropdown
		JPanel buttonPanel = new JPanel(new GridLayout(1, 3, 5, 0));
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

		// "More" dropdown for less common actions
		JButton moreButton = new JButton("More \u25BE");
		moreButton.setFont(FontManager.getRunescapeSmallFont());
		moreButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		moreButton.setForeground(Color.WHITE);
		moreButton.setFocusPainted(false);
		moreButton.setToolTipText("More actions");
		moreButton.addActionListener(e -> {
			JPopupMenu moreMenu = new JPopupMenu();

			JMenuItem createSet = new JMenuItem("Create Item Set");
			createSet.addActionListener(ev -> showCreateSetDialog());
			moreMenu.add(createSet);

			JMenuItem myItems = new JMenuItem("My Items");
			myItems.addActionListener(ev -> showMyItemsDialog());
			moreMenu.add(myItems);

			moreMenu.show(moreButton, 0, -moreMenu.getPreferredSize().height);
		});
		buttonPanel.add(moreButton);

		add(buttonPanel, BorderLayout.SOUTH);

		// ADDED: Initialize online status refresh timer
		// This timer ensures online status dots update on the fly as players come/go
		// CHANGED: Lightweight repaint instead of full rebuild every 30s
		onlineStatusRefreshTimer = new Timer(ONLINE_STATUS_REFRESH_INTERVAL, e -> {
			LendingTrackerConfig config = plugin.getConfig();
			if (config != null && config.showOnlineStatus())
			{
				log.debug("Online status refresh timer triggered - lightweight repaint");
				SwingUtilities.invokeLater(() -> loanListPanel.repaint());
			}
		});
		onlineStatusRefreshTimer.setRepeats(true);
		onlineStatusRefreshTimer.start();

		// Initial refresh removed - will be called by plugin after full initialization
		// refresh();
	}

	/**
	 * ADDED: Stop the online status refresh timer (call when panel is disposed)
	 */
	public void stopRefreshTimer()
	{
		if (onlineStatusRefreshTimer != null && onlineStatusRefreshTimer.isRunning())
		{
			onlineStatusRefreshTimer.stop();
			log.debug("Online status refresh timer stopped");
		}
	}

	/**
	 * ADDED: Create a collapsible section header with toggle arrow and item count
	 */
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

	/**
	 * Refresh the dashboard with latest data
	 * FIXED: Now shows BOTH marketplace offerings AND active loans
	 * FIXED: Checks GameState FIRST to determine login status, then uses stored config for player name
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// FIXED: Check GameState FIRST - this is the authoritative source for login status
			// The stored config is only used to get the player name, not to determine login state
			boolean isLoggedIn = false;
			String playerName = null;
			try
			{
				// Method 1: Check GameState FIRST - this is authoritative
				if (plugin.getClient() != null)
				{
					net.runelite.api.GameState gameState = plugin.getClient().getGameState();
					if (gameState == net.runelite.api.GameState.LOGGED_IN)
					{
						isLoggedIn = true;
						log.debug("DashboardPanel: GameState is LOGGED_IN");
					}
					else
					{
						// Not logged in according to GameState - don't trust stored config
						log.debug("DashboardPanel: GameState is {} - not logged in", gameState);
					}
				}

				// Method 2: If logged in, get player name from local player or stored config
				if (isLoggedIn)
				{
					// Try local player first
					if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
					{
						String localPlayerName = plugin.getClient().getLocalPlayer().getName();
						if (localPlayerName != null && !localPlayerName.isEmpty())
						{
							playerName = localPlayerName;
						}
					}
					// Fall back to stored config for player name
					if (playerName == null && plugin.getConfigManager() != null)
					{
						String storedAccount = plugin.getConfigManager().getConfiguration("lendingtracker", "currentAccount");
						if (storedAccount != null && !storedAccount.isEmpty())
						{
							playerName = storedAccount;
							log.debug("DashboardPanel: Using stored account name: {}", storedAccount);
						}
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Could not check login status via client", e);
			}

			// If not logged in, show empty state with login message
			if (!isLoggedIn)
			{
				log.debug("DashboardPanel: Not logged in - showing login message");
				totalValueLabel.setText("Available: 0 GP");
				activeLoansLabel.setText("Marketplace: 0 | Loans: 0");
				overdueCountLabel.setText("Overdue: 0");
				overdueCountLabel.setForeground(Color.GREEN);

				loanListPanel.removeAll();

				JPanel emptyPanel = new JPanel(new BorderLayout());
				emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

				JLabel emptyLabel = new JLabel("<html><center><b style='color: #ff9900;'>Not Logged In</b><br><br>Please log in to your<br>OSRS account to view<br>the marketplace.</center></html>");
				emptyLabel.setForeground(Color.GRAY);
				emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
				emptyPanel.add(emptyLabel, BorderLayout.CENTER);

				loanListPanel.add(emptyPanel);
				loanListPanel.revalidate();
				loanListPanel.repaint();
				return;
			}

			// Get current group ID - use unchecked version since we've already verified login status
			// FIXED: Using unchecked method avoids race conditions with isLoggedIn() during state transitions
			String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
			log.info("DashboardPanel.refresh - groupId from getCurrentGroupIdUnchecked: '{}'", groupId);

			// FIXED: Get marketplace offerings from recorder only
			// MarketplaceManager.addOffering() delegates to recorder.addAvailable(), so both
			// share the same underlying data store. Querying both caused duplicate items.
			List<LendingEntry> marketplaceItems = new java.util.ArrayList<>();
			if (groupId != null && !groupId.isEmpty())
			{
				List<LendingEntry> recorderItems = recorder.getAvailable(groupId);
				if (recorderItems != null)
				{
					marketplaceItems.addAll(recorderItems);
				}
				log.debug("Marketplace items for group {}: {}", groupId, marketplaceItems.size());
			}

			// Get active loans (items currently lent out)
			List<LendingEntry> activeLoans = lendingManager.getActiveEntries();
			if (activeLoans == null)
			{
				activeLoans = java.util.Collections.emptyList();
			}

			// Calculate summary stats from both marketplace and loans
			long totalMarketplaceValue = marketplaceItems.stream()
				.mapToLong(LendingEntry::getValue)
				.sum();

			long totalLoanValue = activeLoans.stream()
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

			// ADDED: Get config and online status service for filtering
			LendingTrackerConfig config = plugin.getLendingConfig();
			OnlineStatusService onlineStatusService = plugin.getOnlineStatusService();
			boolean showOnlineStatus = config != null && config.showOnlineStatus();
			boolean hideOffline = config != null && config.hideOfflineOfferings();

			// ADDED: Get current group for membership filtering
			net.runelite.client.plugins.lendingtracker.model.LendingGroup currentGroupForFilter =
				groupId != null ? groupConfigStore.getGroup(groupId) : null;

			// ADDED: Filter marketplace items - MUST be from group members, optional offline filter
			List<LendingEntry> displayItems = marketplaceItems.stream()
				.filter(item -> {
					String lender = item.getLender();
					if (lender == null) return false; // Hide items without lender info

					// CRITICAL: Only show items from GROUP MEMBERS
					if (currentGroupForFilter != null && !currentGroupForFilter.hasMember(lender))
					{
						log.debug("Filtering out item from non-member: {}", lender);
						return false;
					}

					// Always show current player's own items
					String currentPlayer = getCurrentPlayerName();
					if (lender.equalsIgnoreCase(currentPlayer)) return true;

					// Optional: filter offline offerings if config enabled
					if (hideOffline && onlineStatusService != null)
					{
						return onlineStatusService.isPlayerOnline(lender);
					}
					return true;
				})
				.collect(java.util.stream.Collectors.toList());
			log.debug("Filtered marketplace items: {} of {} shown (membership + offline filter)",
				displayItems.size(), marketplaceItems.size());

			// REMOVED: Test/mock member and item code (TestLender123, FakeBorrower99, OnlinePlayer42, etc.)

			// Show marketplace offerings first
			if (!displayItems.isEmpty())
			{
				// Add section header for marketplace
				// CHANGED: Collapsible section header with item count
				boolean marketCollapsed = collapsedSections.contains("marketplace");
				JPanel marketHeader = createCollapsibleHeader(
					"Available for Lending (" + displayItems.size() + ")",
					ColorScheme.BRAND_ORANGE, "marketplace", marketCollapsed);
				loanListPanel.add(marketHeader);

				if (!marketCollapsed)
				{
					for (LendingEntry item : displayItems)
					{
						MarketplaceCard card = new MarketplaceCard(item, showOnlineStatus, onlineStatusService);
						loanListPanel.add(card);
					}
				}
			}

			// ADDED: Show "Looking For" requests section
			log.info("DashboardPanel.refresh - calling getLookingForRequests with groupId: '{}'", groupId);
			List<LookingForRequest> lookingForRequests = getLookingForRequests(groupId);
			log.info("DashboardPanel.refresh - got {} Looking For requests", lookingForRequests.size());

			// REMOVED: Test Looking For requests (cleanup for release)
			if (!lookingForRequests.isEmpty())
			{
				// CHANGED: Collapsible section header with item count
				boolean lookingForCollapsed = collapsedSections.contains("lookingfor");
				JPanel lookingForHeader = createCollapsibleHeader(
					"Looking For (" + lookingForRequests.size() + ")",
					ColorScheme.GRAND_EXCHANGE_PRICE, "lookingfor", lookingForCollapsed);
				loanListPanel.add(lookingForHeader);

				if (!lookingForCollapsed)
				{
					for (LookingForRequest request : lookingForRequests)
					{
						LookingForCard card = new LookingForCard(request, showOnlineStatus, onlineStatusService);
						loanListPanel.add(card);
					}
				}
			}

			// Then show active loans
			if (!activeLoans.isEmpty())
			{
				// CHANGED: Collapsible section header with item count
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

			// MOVED: Show Item Sets section at bottom
			java.util.List<ItemSet> displaySets = new java.util.ArrayList<>();
			if (groupId != null && !groupId.isEmpty())
			{
				java.util.List<ItemSet> availableSets = itemSetManager.getAvailableItemSets(groupId);

				// Filter by online status if configured
				for (ItemSet set : availableSets)
				{
					boolean isOwnerOnline = true;
					if (showOnlineStatus && onlineStatusService != null && set.getOwnerName() != null)
					{
						isOwnerOnline = onlineStatusService.isPlayerOnline(set.getOwnerName());
					}

					if (!hideOffline || isOwnerOnline)
					{
						displaySets.add(set);
					}
				}

				if (!displaySets.isEmpty())
				{
					// CHANGED: Collapsible section header with item count
					boolean setsCollapsed = collapsedSections.contains("itemsets");
					JPanel setsHeader = createCollapsibleHeader(
						"Item Sets (" + displaySets.size() + ")",
						new Color(100, 149, 237), "itemsets", setsCollapsed);
					loanListPanel.add(setsHeader);

					if (!setsCollapsed)
					{
						for (ItemSet set : displaySets)
						{
							ItemSetCard card = new ItemSetCard(set, showOnlineStatus, onlineStatusService, groupId);
							loanListPanel.add(card);
						}
					}
				}
			}

			// If all sections are empty, show empty state
			if (displayItems.isEmpty() && lookingForRequests.isEmpty() && activeLoans.isEmpty() && displaySets.isEmpty())
			{
				JPanel emptyPanel = new JPanel(new BorderLayout());
				emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				emptyPanel.setBorder(new EmptyBorder(20, 20, 20, 20));

				String message;
				if (groupId == null || groupId.isEmpty())
				{
					message = "<html><center>No group selected<br><br>Select or create a group to start</center></html>";
				}
				else if (hideOffline && !marketplaceItems.isEmpty())
				{
					// Items exist but are hidden because users are offline
					message = "<html><center>All lenders are offline<br><br>Items are hidden when lenders<br>are not online.<br><br>Disable 'Hide Offline Offerings'<br>in settings to see them.</center></html>";
				}
				else
				{
					message = "<html><center>No items in marketplace<br><br>Right-click an item and select<br>'Add to Lending List' to offer it<br><br>Or click 'Looking For' to<br>post what you need to borrow</center></html>";
				}

				JLabel emptyLabel = new JLabel(message);
				emptyLabel.setForeground(Color.GRAY);
				emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
				emptyPanel.add(emptyLabel, BorderLayout.CENTER);

				loanListPanel.add(emptyPanel);
			}

			loanListPanel.revalidate();
			loanListPanel.repaint();
		});
	}

	/**
	 * MarketplaceCard - Displays an item available for lending
	 * FIXED: Added right-click menu and constrained width
	 * ADDED: Online status display
	 */
	private class MarketplaceCard extends JPanel
	{
		private final LendingEntry item;
		private final JPanel detailsPanel;
		private final JPanel rightPanel;
		private final boolean showOnlineStatus;
		private final OnlineStatusService onlineStatusService;

		public MarketplaceCard(LendingEntry item, boolean showOnlineStatus, OnlineStatusService onlineStatusService)
		{
			this.item = item;
			this.showOnlineStatus = showOnlineStatus;
			this.onlineStatusService = onlineStatusService;

			// ADDED: Check if lender is online for styling
			boolean isLenderOnline = true; // Default to online
			String lender = item.getLender();
			if (showOnlineStatus && onlineStatusService != null && lender != null)
			{
				isLenderOnline = onlineStatusService.isPlayerOnline(lender);
			}

			setLayout(new BorderLayout(5, 0));
			// ADDED: Dim background if offline
			Color bgColor = isLenderOnline ? ColorScheme.DARKER_GRAY_COLOR : new Color(40, 40, 40);
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(8, 8, 8, 8)
			));

			// FIXED: Constrain card size to prevent horizontal scroll
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
			// ADDED: Dim text if offline
			itemLabel.setForeground(isLenderOnline ? Color.WHITE : Color.GRAY);

			// Owner name with online status indicator
			String ownerText = "By: " + item.getLender();
			if (showOnlineStatus && onlineStatusService != null && lender != null)
			{
				// Add status dot indicator
				String statusDot = isLenderOnline ? "\u25CF " : "\u25CB "; // ● or ○
				ownerText = statusDot + ownerText;
			}
			JLabel ownerLabel = new JLabel(ownerText);
			ownerLabel.setFont(FontManager.getRunescapeSmallFont());
			// ADDED: Color-coded by online status
			if (showOnlineStatus && onlineStatusService != null && lender != null)
			{
				if (isLenderOnline)
				{
					ownerLabel.setForeground(new Color(100, 255, 100)); // Bright green
				}
				else
				{
					// Check if recently online
					long lastSeen = onlineStatusService.getLastSeenTime(lender);
					long timeDiff = lastSeen > 0 ? Instant.now().toEpochMilli() - lastSeen : Long.MAX_VALUE;
					if (timeDiff < 300000) // Less than 5 minutes
					{
						ownerLabel.setForeground(new Color(255, 255, 100)); // Yellow - recently online
					}
					else
					{
						ownerLabel.setForeground(new Color(150, 150, 150)); // Gray - offline
					}
				}
			}
			else
			{
				ownerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}

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
			valueLabel.setForeground(isLenderOnline ? Color.YELLOW : new Color(180, 180, 100));

			// ADDED: Status shows online/offline instead of just "Avail"
			String statusText = "Avail";
			Color statusColor = Color.GREEN;
			if (showOnlineStatus && onlineStatusService != null && lender != null)
			{
				if (isLenderOnline)
				{
					statusText = "Online";
					statusColor = new Color(100, 255, 100);
				}
				else
				{
					statusText = "Offline";
					statusColor = new Color(150, 150, 150);
				}
			}
			JLabel statusLabel = new JLabel(statusText);
			statusLabel.setFont(FontManager.getRunescapeSmallFont());
			statusLabel.setForeground(statusColor);

			rightPanel.add(valueLabel);
			rightPanel.add(statusLabel);

			add(rightPanel, BorderLayout.EAST);

			// FIXED: Add right-click popup menu
			JPopupMenu popupMenu = createPopupMenu();
			setComponentPopupMenu(popupMenu);

			// Store the base background color for hover effects
			final Color baseBgColor = bgColor;

			// Mouse listener for hover and click
			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					detailsPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					rightPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					setBackground(baseBgColor);
					detailsPanel.setBackground(baseBgColor);
					rightPanel.setBackground(baseBgColor);
				}

				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						popupMenu.show(e.getComponent(), e.getX(), e.getY());
					}
				}

				@Override
				public void mouseReleased(java.awt.event.MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						popupMenu.show(e.getComponent(), e.getX(), e.getY());
					}
				}
			});
		}

		private JPopupMenu createPopupMenu()
		{
			JPopupMenu menu = new JPopupMenu();
			String currentPlayer = getCurrentPlayerName();
			boolean isOwner = item.getLender() != null && item.getLender().equalsIgnoreCase(currentPlayer);

			// ADDED: Check if lender is online
			boolean isLenderOnline = true;
			if (showOnlineStatus && onlineStatusService != null && item.getLender() != null)
			{
				isLenderOnline = onlineStatusService.isPlayerOnline(item.getLender());
			}

			if (isOwner)
			{
				// CHANGED: Check if item is currently lent out (has a borrower)
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
					// ADDED: Full edit option for available items
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
				// Non-owner options - check if lender is online
				if (isLenderOnline)
				{
					JMenuItem borrowItem = new JMenuItem("Request to Borrow");
					borrowItem.addActionListener(e -> requestToBorrow());
					menu.add(borrowItem);
				}
				else
				{
					// ADDED: Lender is offline - show disabled option with explanation
					JMenuItem offlineItem = new JMenuItem("Request to Borrow (User Offline)");
					offlineItem.setEnabled(false);
					offlineItem.setToolTipText("Cannot send request - " + item.getLender() + " is offline");
					menu.add(offlineItem);

					// Add "last seen" info if available
					if (onlineStatusService != null)
					{
						String lastSeen = onlineStatusService.getLastSeenFormatted(item.getLender());
						JMenuItem lastSeenItem = new JMenuItem("Last seen: " + lastSeen);
						lastSeenItem.setEnabled(false);
						menu.add(lastSeenItem);
					}
				}
			}

			return menu;
		}

		// CHANGED: Full edit dialog for marketplace listings (quantity, value, notes, collateral)
		private void showFullEditDialog()
		{
			String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
			if (groupId == null || groupId.isEmpty())
			{
				JOptionPane.showMessageDialog(DashboardPanel.this,
					"No active group selected.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			JPanel editPanel = new JPanel(new GridBagLayout());
			editPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(4, 4, 4, 4);
			gbc.anchor = GridBagConstraints.WEST;
			gbc.fill = GridBagConstraints.HORIZONTAL;

			// Item name (read-only)
			gbc.gridx = 0; gbc.gridy = 0;
			editPanel.add(new JLabel("Item:"), gbc);
			gbc.gridx = 1;
			JLabel itemLabel = new JLabel(item.getItem());
			editPanel.add(itemLabel, gbc);

			// Quantity
			gbc.gridx = 0; gbc.gridy = 1;
			editPanel.add(new JLabel("Quantity:"), gbc);
			gbc.gridx = 1;
			JTextField qtyField = new JTextField(String.valueOf(item.getQuantity()), 10);
			editPanel.add(qtyField, gbc);

			// Value
			gbc.gridx = 0; gbc.gridy = 2;
			editPanel.add(new JLabel("Value (GP):"), gbc);
			gbc.gridx = 1;
			JTextField valueField = new JTextField(String.valueOf(item.getValue()), 10);
			editPanel.add(valueField, gbc);

			// Collateral
			gbc.gridx = 0; gbc.gridy = 3;
			editPanel.add(new JLabel("Collateral:"), gbc);
			gbc.gridx = 1;
			JTextField collateralField = new JTextField(
				item.getCollateralValue() != null ? String.valueOf(item.getCollateralValue()) : "0", 10);
			editPanel.add(collateralField, gbc);

			// Notes
			gbc.gridx = 0; gbc.gridy = 4;
			editPanel.add(new JLabel("Notes:"), gbc);
			gbc.gridx = 1;
			JTextField notesField = new JTextField(item.getNotes() != null ? item.getNotes() : "", 20);
			editPanel.add(notesField, gbc);

			int result = JOptionPane.showConfirmDialog(
				DashboardPanel.this,
				editPanel,
				"Edit Listing - " + item.getItem(),
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE
			);

			if (result == JOptionPane.OK_OPTION)
			{
				try
				{
					int newQty = Integer.parseInt(qtyField.getText().trim());
					long newValue = Long.parseLong(valueField.getText().trim());
					int newCollateral = Integer.parseInt(collateralField.getText().trim());

					if (newQty <= 0)
					{
						JOptionPane.showMessageDialog(DashboardPanel.this,
							"Quantity must be greater than 0.", "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}

					item.setQuantity(newQty);
					item.setValue(newValue);
					item.setCollateralValue(newCollateral);
					item.setCollateralType(newCollateral > 0 ? "GP" : "none");
					item.setNotes(notesField.getText().trim());

					recorder.updateAvailable(groupId, item.getLender(), item.getItem(), item.getItemId(), item);
					refresh();
					log.info("Updated listing for {} - qty: {}, value: {}, collateral: {}",
						item.getItem(), newQty, newValue, newCollateral);
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Please enter valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}

		private void removeFromMarketplace()
		{
			// FIXED: Verify we have a valid group before attempting removal
			String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
			if (groupId == null || groupId.isEmpty())
			{
				JOptionPane.showMessageDialog(DashboardPanel.this,
					"No active group selected. Please select a group first.",
					"Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			int confirm = JOptionPane.showConfirmDialog(
				DashboardPanel.this,
				"Remove " + item.getItem() + " from marketplace?",
				"Confirm Remove",
				JOptionPane.YES_NO_OPTION
			);

			if (confirm == JOptionPane.YES_OPTION)
			{
				recorder.removeAvailable(groupId, item.getLender(), item.getItem(), item.getItemId());
				log.info("Removed {} from marketplace (groupId: {}, lender: {})", item.getItem(), groupId, item.getLender());

				// FIXED: Also remove from marketplaceManager to ensure consistency
				marketplaceManager.removeOffering(groupId, item.getLender(), item.getItem(), item.getItemId());

				// FIXED: Force immediate UI refresh - reload data from ALL sources
				recorder.loadGroupData(groupId);
				marketplaceManager.loadGroupData(groupId);
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

			// ADDED: Show borrow request dialog with duration and terms
			showBorrowRequestDialog(currentPlayer, lender, itemName, item.getItemId(), item.getQuantity());
		}

		/**
		 * ADDED: Show dialog for borrow request with duration and terms agreement
		 * UPDATED: Added days/hours toggle and group name display
		 */
		private void showBorrowRequestDialog(String borrower, String lender, String itemName, int itemId, int quantity)
		{
			// Get current group name for display
			String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
			String groupName = groupId != null ? groupConfigStore.getGroupNameById(groupId) : null;
			String groupDisplay = groupName != null ? " (" + groupName + ")" : "";

			// Create dialog panel
			JPanel dialogPanel = new JPanel();
			dialogPanel.setLayout(new BoxLayout(dialogPanel, BoxLayout.Y_AXIS));
			dialogPanel.setPreferredSize(new Dimension(400, 280));

			// Header with group name
			JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			headerPanel.setBackground(dialogPanel.getBackground());
			headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel headerLabel = new JLabel("Request to Borrow: " + itemName);
			headerLabel.setFont(FontManager.getRunescapeBoldFont());
			headerPanel.add(headerLabel);
			if (groupName != null)
			{
				JLabel groupLabel = new JLabel(" (" + groupName + ")");
				groupLabel.setFont(FontManager.getRunescapeSmallFont());
				groupLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE); // Yellow for group name
				headerPanel.add(groupLabel);
			}
			dialogPanel.add(headerPanel);
			dialogPanel.add(Box.createVerticalStrut(5));

			JLabel ownerLabel = new JLabel("From: " + lender);
			ownerLabel.setFont(FontManager.getRunescapeSmallFont());
			ownerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(ownerLabel);
			dialogPanel.add(Box.createVerticalStrut(15));

			// Duration field with days/hours toggle
			JLabel durationLabel = new JLabel("How long do you need this item?");
			durationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(durationLabel);

			JPanel durationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
			durationPanel.setBackground(dialogPanel.getBackground());
			durationPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			durationPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

			JTextField durationField = new JTextField("7");
			durationField.setPreferredSize(new Dimension(60, 25));
			durationPanel.add(durationField);

			// Days/Hours toggle
			JRadioButton daysRadio = new JRadioButton("Days", true);
			JRadioButton hoursRadio = new JRadioButton("Hours", false);
			ButtonGroup durationGroup = new ButtonGroup();
			durationGroup.add(daysRadio);
			durationGroup.add(hoursRadio);
			durationPanel.add(daysRadio);
			durationPanel.add(hoursRadio);

			dialogPanel.add(durationPanel);
			dialogPanel.add(Box.createVerticalStrut(15));

			// CHANGED: Simplified terms - single checkbox with expandable details
			JCheckBox agreeTermsCheck = new JCheckBox("I agree to the borrowing terms");
			agreeTermsCheck.setFont(FontManager.getRunescapeBoldFont());
			agreeTermsCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(agreeTermsCheck);

			// Expandable details (collapsed by default)
			JLabel termsDetails = new JLabel("<html><font color='#b0b0b0'>" +
				"\u2022 No Wilderness \u2022 No trading to others \u2022 No selling/alching<br>" +
				"\u2022 Return on time \u2022 No risky activities (PvP, dangerous minigames)" +
				"</font></html>");
			termsDetails.setAlignmentX(Component.LEFT_ALIGNMENT);
			termsDetails.setBorder(new EmptyBorder(2, 25, 0, 0));
			dialogPanel.add(termsDetails);

			dialogPanel.add(Box.createVerticalStrut(5));

			JLabel warningLabel = new JLabel("<html><font color='orange'>Breaking terms may result in removal from the group!</font></html>");
			warningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(warningLabel);

			// Show dialog
			int result = JOptionPane.showConfirmDialog(
				DashboardPanel.this,
				dialogPanel,
				"Borrow Request" + groupDisplay,
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE
			);

			if (result == JOptionPane.OK_OPTION)
			{
				// CHANGED: Validate single terms checkbox
				if (!agreeTermsCheck.isSelected())
				{
					JOptionPane.showMessageDialog(
						DashboardPanel.this,
						"You must agree to the borrowing terms before requesting to borrow.",
						"Terms Not Accepted",
						JOptionPane.WARNING_MESSAGE
					);
					return;
				}

				// Validate duration
				int duration;
				boolean isHours = hoursRadio.isSelected();
				try
				{
					duration = Integer.parseInt(durationField.getText().trim());
					int maxValue = isHours ? 8760 : 365; // Max 1 year in hours or days
					if (duration <= 0 || duration > maxValue)
					{
						throw new NumberFormatException("Invalid duration");
					}
				}
				catch (NumberFormatException e)
				{
					String unitText = isHours ? "hours (1-8760)" : "days (1-365)";
					JOptionPane.showMessageDialog(
						DashboardPanel.this,
						"Please enter a valid duration in " + unitText + ".",
						"Invalid Duration",
						JOptionPane.ERROR_MESSAGE
					);
					return;
				}

				// Convert to days for storage if hours selected
				int durationDays = isHours ? Math.max(1, duration / 24) : duration;
				String durationDisplay = isHours ? duration + " hours" : duration + " days";

				// All validated - send the request
				log.info("{} is requesting to borrow {} from {} for {} (terms accepted)",
					borrower, itemName, lender, durationDisplay);

				// Send borrow request with duration info
				plugin.sendBorrowRequest(borrower, lender, itemName, itemId, quantity, durationDays);

				JOptionPane.showMessageDialog(
					DashboardPanel.this,
					"Borrow request sent to " + lender + "!" + groupDisplay + "\n\n" +
						"Duration: " + durationDisplay + "\n" +
						"They will receive a notification in-game.",
					"Request Sent",
					JOptionPane.INFORMATION_MESSAGE
				);
			}
		}
	}

	// CHANGED: Delegates to plugin's centralized getCurrentPlayerName()
	private String getCurrentPlayerName()
	{
		String name = plugin.getCurrentPlayerName();
		return name != null ? name : "Not logged in";
	}

	/**
	 * LoanCard - Individual loan display card
	 */
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

			// Add mouse hover effect
			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					detailsPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					valuePanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					setBackground(ColorScheme.DARKER_GRAY_COLOR);
					detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					valuePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}

				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					// TODO: Open loan details dialog
					log.info("Clicked on loan: {}", loan.getId());
				}
			});
		}

		/**
		 * Format due time as human-readable string
		 */
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

		/**
		 * Check if loan is due soon (within 2 days)
		 */
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

	/**
	 * ADDED: Card for displaying an item set in the marketplace
	 */
	private class ItemSetCard extends JPanel
	{
		private final ItemSet set;
		private final JPanel detailsPanel;
		private final JPanel rightPanel;
		private final boolean showOnlineStatus;
		private final OnlineStatusService onlineStatusService;
		private final String groupId;

		public ItemSetCard(ItemSet set, boolean showOnlineStatus, OnlineStatusService onlineStatusService, String groupId)
		{
			this.set = set;
			this.showOnlineStatus = showOnlineStatus;
			this.onlineStatusService = onlineStatusService;
			this.groupId = groupId;

			// Check if owner is online
			boolean isOwnerOnline = true;
			String owner = set.getOwnerName();
			if (showOnlineStatus && onlineStatusService != null && owner != null)
			{
				isOwnerOnline = onlineStatusService.isPlayerOnline(owner);
			}

			setLayout(new BorderLayout(5, 0));
			Color bgColor = isOwnerOnline ? ColorScheme.DARKER_GRAY_COLOR : new Color(40, 40, 40);
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 1, 0, new Color(100, 149, 237)), // Blue left border for sets
				new EmptyBorder(8, 8, 8, 8)
			));

			setMaximumSize(new Dimension(Integer.MAX_VALUE, 75));
			setPreferredSize(new Dimension(200, 70));

			// ADDED: Tooltip hint to right-click for items list
			setToolTipText("<html><b>" + set.getName() + "</b><br>" +
				set.getItemCount() + " items | " + QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP<br>" +
				"<i>Right-click to view items</i></html>");

			// Left side: Set icon (package icon placeholder)
			JLabel iconLabel = new JLabel("\uD83D\uDCE6"); // 📦 package emoji
			iconLabel.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 24));
			iconLabel.setPreferredSize(new Dimension(36, 36));
			add(iconLabel, BorderLayout.WEST);

			// Center: Set details
			detailsPanel = new JPanel();
			detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
			detailsPanel.setBackground(bgColor);

			// Set name (bold, truncate if needed)
			String setName = set.getName();
			if (setName.length() > 20) setName = setName.substring(0, 17) + "...";
			JLabel nameLabel = new JLabel(setName + " [SET]");
			nameLabel.setFont(FontManager.getRunescapeBoldFont());
			nameLabel.setForeground(isOwnerOnline ? new Color(100, 149, 237) : Color.GRAY); // Blue for sets

			// Items count and owner
			String ownerText = set.getItemCount() + " items | By: " + owner;
			if (showOnlineStatus && onlineStatusService != null && owner != null)
			{
				String statusDot = isOwnerOnline ? "\u25CF " : "\u25CB ";
				ownerText = statusDot + ownerText;
			}
			JLabel ownerLabel = new JLabel(ownerText);
			ownerLabel.setFont(FontManager.getRunescapeSmallFont());
			if (showOnlineStatus && onlineStatusService != null)
			{
				ownerLabel.setForeground(isOwnerOnline ? new Color(100, 255, 100) : new Color(150, 150, 150));
			}
			else
			{
				ownerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}

			detailsPanel.add(nameLabel);
			detailsPanel.add(ownerLabel);

			add(detailsPanel, BorderLayout.CENTER);

			// Right side: Value and status
			rightPanel = new JPanel();
			rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
			rightPanel.setBackground(bgColor);
			rightPanel.setPreferredSize(new Dimension(60, 55));

			JLabel valueLabel = new JLabel(QuantityFormatter.quantityToStackSize(set.getTotalValue()));
			valueLabel.setFont(FontManager.getRunescapeSmallFont());
			valueLabel.setForeground(isOwnerOnline ? Color.YELLOW : new Color(180, 180, 100));

			String statusText = isOwnerOnline ? "Online" : "Offline";
			Color statusColor = isOwnerOnline ? new Color(100, 255, 100) : new Color(150, 150, 150);
			JLabel statusLabel = new JLabel(statusText);
			statusLabel.setFont(FontManager.getRunescapeSmallFont());
			statusLabel.setForeground(statusColor);

			rightPanel.add(valueLabel);
			rightPanel.add(statusLabel);

			add(rightPanel, BorderLayout.EAST);

			// Right-click menu
			JPopupMenu popupMenu = createSetPopupMenu(isOwnerOnline);
			setComponentPopupMenu(popupMenu);

			final Color baseBgColor = bgColor;

			// Mouse hover and click
			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					detailsPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					rightPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					setBackground(baseBgColor);
					detailsPanel.setBackground(baseBgColor);
					rightPanel.setBackground(baseBgColor);
				}

				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					if (e.getClickCount() == 2)
					{
						showSetDetailsDialog(set);
					}
				}

				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						popupMenu.show(e.getComponent(), e.getX(), e.getY());
					}
				}

				@Override
				public void mouseReleased(java.awt.event.MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						popupMenu.show(e.getComponent(), e.getX(), e.getY());
					}
				}
			});
		}

		private JPopupMenu createSetPopupMenu(boolean isOwnerOnline)
		{
			JPopupMenu menu = new JPopupMenu();
			String currentPlayer = getCurrentPlayerName();
			boolean isOwner = set.getOwnerName() != null && set.getOwnerName().equalsIgnoreCase(currentPlayer);

			// View details
			JMenuItem viewItem = new JMenuItem("View Set Details");
			viewItem.addActionListener(e -> showSetDetailsDialog(set));
			menu.add(viewItem);

			menu.addSeparator();

			if (isOwner)
			{
				// Owner options
				if (!set.isLentOut())
				{
					// ADDED: Edit set option (only when not lent out)
					JMenuItem editSetItem = new JMenuItem("Edit Set");
					editSetItem.addActionListener(e -> showEditSetDialog(set));
					menu.add(editSetItem);

					JMenuItem lendItem = new JMenuItem("Lend This Set");
					lendItem.addActionListener(e -> {
						JDialog tempDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(DashboardPanel.this));
						showLendSetDialog(set, groupId, tempDialog);
					});
					menu.add(lendItem);
				}
				else
				{
					// Set is lent out - disable editing
					JMenuItem lentInfo = new JMenuItem("Lent to " + set.getCurrentBorrower());
					lentInfo.setEnabled(false);
					menu.add(lentInfo);

					JMenuItem returnItem = new JMenuItem("Mark as Returned");
					returnItem.addActionListener(e ->
					{
						int confirm = JOptionPane.showConfirmDialog(DashboardPanel.this,
							"Mark set '" + set.getName() + "' as returned from " + set.getCurrentBorrower() + "?",
							"Confirm Return",
							JOptionPane.YES_NO_OPTION);
						if (confirm == JOptionPane.YES_OPTION)
						{
							itemSetManager.returnItemSet(groupId, set.getId());
							refresh();
						}
					});
					menu.add(returnItem);
				}

				JMenuItem deleteItem = new JMenuItem("Delete Set");
				deleteItem.addActionListener(e ->
				{
					int confirm = JOptionPane.showConfirmDialog(DashboardPanel.this,
						"Delete set '" + set.getName() + "'? This cannot be undone.",
						"Confirm Delete",
						JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE);
					if (confirm == JOptionPane.YES_OPTION)
					{
						itemSetManager.deleteItemSet(groupId, set.getId());
						refresh();
					}
				});
				menu.add(deleteItem);
			}
			else
			{
				// Non-owner: request to borrow
				if (isOwnerOnline)
				{
					JMenuItem borrowItem = new JMenuItem("Request to Borrow Set");
					borrowItem.addActionListener(e -> requestToBorrowSet());
					menu.add(borrowItem);
				}
				else
				{
					JMenuItem offlineItem = new JMenuItem("Request to Borrow (Owner Offline)");
					offlineItem.setEnabled(false);
					offlineItem.setToolTipText("Cannot send request - " + set.getOwnerName() + " is offline");
					menu.add(offlineItem);
				}
			}

			return menu;
		}

		private void requestToBorrowSet()
		{
			String currentPlayer = getCurrentPlayerName();
			if (currentPlayer == null || currentPlayer.equals("Not logged in"))
			{
				JOptionPane.showMessageDialog(DashboardPanel.this,
					"You must be logged in to request sets.",
					"Error",
					JOptionPane.ERROR_MESSAGE);
				return;
			}

			// Build items list for display
			StringBuilder itemsList = new StringBuilder();
			if (set.getItems() != null)
			{
				for (ItemSet.ItemSetEntry item : set.getItems())
				{
					itemsList.append("• ").append(item.getItemName()).append(" x").append(item.getQuantity()).append("\n");
				}
			}

			JPanel panel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(5, 5, 5, 5);
			gbc.fill = GridBagConstraints.HORIZONTAL;

			gbc.gridx = 0;
			gbc.gridy = 0;
			gbc.gridwidth = 2;
			panel.add(new JLabel("<html><b>Request Set: " + set.getName() + "</b><br>From: " + set.getOwnerName() + "</html>"), gbc);

			gbc.gridy = 1;
			JTextArea itemsArea = new JTextArea(itemsList.toString());
			itemsArea.setEditable(false);
			itemsArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			itemsArea.setForeground(Color.WHITE);
			JScrollPane itemsScroll = new JScrollPane(itemsArea);
			itemsScroll.setPreferredSize(new Dimension(250, 80));
			panel.add(itemsScroll, gbc);

			gbc.gridy = 2;
			gbc.gridwidth = 1;
			panel.add(new JLabel("Duration (days):"), gbc);

			gbc.gridx = 1;
			JTextField durationField = new JTextField(String.valueOf(set.getDefaultDurationDays()), 5);
			panel.add(durationField, gbc);

			gbc.gridx = 0;
			gbc.gridy = 3;
			gbc.gridwidth = 2;
			panel.add(new JLabel("Total Value: " + QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP"), gbc);

			int result = JOptionPane.showConfirmDialog(DashboardPanel.this,
				panel,
				"Request to Borrow Set",
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE);

			if (result == JOptionPane.OK_OPTION)
			{
				try
				{
					int days = Integer.parseInt(durationField.getText().trim());

					// Send request notification to owner via plugin
					String message = currentPlayer + " wants to borrow your item set '" + set.getName() +
						"' (" + set.getItemCount() + " items, " +
						QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP) for " + days + " days.";

					// Use plugin method for sending requests (handles notification storage)
					plugin.sendSetBorrowRequest(currentPlayer, set.getOwnerName(), set.getName(),
						set.getTotalValue(), set.getItemCount(), days, groupId);

					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Request sent to " + set.getOwnerName() + "!\n\n" +
							"Duration: " + days + " days\n" +
							"They will receive a notification in-game.",
						"Request Sent",
						JOptionPane.INFORMATION_MESSAGE);
				}
				catch (NumberFormatException e)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Invalid duration",
						"Error",
						JOptionPane.ERROR_MESSAGE);
				}
			}
		}

		// ADDED: Edit set dialog (name, description, duration) for owners of unlent sets
		private void showEditSetDialog(ItemSet set)
		{
			JPanel editPanel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(4, 4, 4, 4);
			gbc.anchor = GridBagConstraints.WEST;
			gbc.fill = GridBagConstraints.HORIZONTAL;

			// Set name
			gbc.gridx = 0; gbc.gridy = 0;
			editPanel.add(new JLabel("Name:"), gbc);
			gbc.gridx = 1;
			JTextField nameField = new JTextField(set.getName(), 20);
			editPanel.add(nameField, gbc);

			// Description
			gbc.gridx = 0; gbc.gridy = 1;
			editPanel.add(new JLabel("Description:"), gbc);
			gbc.gridx = 1;
			JTextField descField = new JTextField(set.getDescription() != null ? set.getDescription() : "", 20);
			editPanel.add(descField, gbc);

			// Duration
			gbc.gridx = 0; gbc.gridy = 2;
			editPanel.add(new JLabel("Duration (days):"), gbc);
			gbc.gridx = 1;
			JTextField durationField = new JTextField(String.valueOf(set.getDefaultDurationDays()), 10);
			editPanel.add(durationField, gbc);

			// Items list (read-only info)
			gbc.gridx = 0; gbc.gridy = 3;
			gbc.gridwidth = 2;
			editPanel.add(new JLabel("Items: " + set.getItemCount() + " | Value: " +
				QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP"), gbc);

			int result = JOptionPane.showConfirmDialog(
				DashboardPanel.this,
				editPanel,
				"Edit Set - " + set.getName(),
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE
			);

			if (result == JOptionPane.OK_OPTION)
			{
				try
				{
					String newName = nameField.getText().trim();
					if (newName.isEmpty())
					{
						JOptionPane.showMessageDialog(DashboardPanel.this,
							"Name cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
						return;
					}

					int days = Integer.parseInt(durationField.getText().trim());

					itemSetManager.updateItemSet(
						groupId,
						set.getId(),
						newName,
						descField.getText().trim(),
						null, null,
						days
					);
					refresh();
					log.info("Updated item set '{}' -> name: '{}', duration: {} days", set.getId(), newName, days);
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Invalid duration.", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}
	}

	/**
	 * ADDED: Show dialog to add an item to the marketplace with autocomplete
	 */
	private void showAddItemDialog()
	{
		// Get active group
		net.runelite.client.plugins.lendingtracker.model.LendingGroup activeGroup = groupConfigStore.getActiveGroup();
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
		itemSection.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Search Item",
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(),
			Color.WHITE
		));

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

		// Help text
		JLabel helpLabel = new JLabel("<html><i>Double-click an item to select it</i></html>");
		helpLabel.setForeground(Color.GRAY);
		helpLabel.setHorizontalAlignment(SwingConstants.CENTER);
		itemSection.add(helpLabel, BorderLayout.SOUTH);

		mainPanel.add(itemSection, BorderLayout.NORTH);

		// Selected item details section
		JPanel detailsSection = new JPanel(new BorderLayout(5, 5));
		detailsSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detailsSection.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Item Details",
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(),
			Color.WHITE
		));

		JPanel detailsGrid = new JPanel(new GridBagLayout());
		detailsGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Selected item display
		gbc.gridx = 0;
		gbc.gridy = 0;
		JLabel selectedLabel = new JLabel("Selected:");
		selectedLabel.setForeground(Color.WHITE);
		detailsGrid.add(selectedLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JLabel selectedItemDisplay = new JLabel("(None)");
		selectedItemDisplay.setForeground(Color.YELLOW);
		selectedItemDisplay.setFont(FontManager.getRunescapeBoldFont());
		detailsGrid.add(selectedItemDisplay, gbc);

		// Item ID (hidden but tracked)
		final int[] selectedItemId = {0};

		// Quantity field
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.weightx = 0;
		JLabel qtyLabel = new JLabel("Quantity:");
		qtyLabel.setForeground(Color.WHITE);
		detailsGrid.add(qtyLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JTextField quantityField = new JTextField("1", 10);
		detailsGrid.add(quantityField, gbc);

		// Value field
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		JLabel valueLabel = new JLabel("Value (GP):");
		valueLabel.setForeground(Color.WHITE);
		detailsGrid.add(valueLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JTextField valueField = new JTextField("0", 10);
		valueField.setToolTipText("GE price auto-filled - edit if desired");
		detailsGrid.add(valueField, gbc);

		detailsSection.add(detailsGrid, BorderLayout.CENTER);

		// Total value display
		JLabel totalValueLabel = new JLabel("Total: 0 GP");
		totalValueLabel.setForeground(Color.YELLOW);
		totalValueLabel.setFont(FontManager.getRunescapeBoldFont());
		totalValueLabel.setHorizontalAlignment(SwingConstants.CENTER);
		totalValueLabel.setBorder(new EmptyBorder(5, 0, 5, 0));
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

		// Update total when quantity changes
		quantityField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private void updateTotal()
			{
				try
				{
					int qty = Integer.parseInt(quantityField.getText().trim());
					long value = Long.parseLong(valueField.getText().trim());
					totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(qty * value) + " GP");
				}
				catch (NumberFormatException ignored)
				{
					totalValueLabel.setText("Total: 0 GP");
				}
			}

			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTotal(); }

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTotal(); }

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) { updateTotal(); }
		});

		// Update total when value changes
		valueField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private void updateTotal()
			{
				try
				{
					int qty = Integer.parseInt(quantityField.getText().trim());
					long value = Long.parseLong(valueField.getText().trim());
					totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(qty * value) + " GP");
				}
				catch (NumberFormatException ignored)
				{
					totalValueLabel.setText("Total: 0 GP");
				}
			}

			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTotal(); }

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTotal(); }

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) { updateTotal(); }
		});

		// Autocomplete listener - search items as user types
		itemSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
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
				searchTimer = new javax.swing.Timer(200, evt -> performSearch());
				searchTimer.setRepeats(false);
				searchTimer.start();
			}

			private void performSearch()
			{
				String query = itemSearchField.getText().trim().toLowerCase();
				suggestionModel.clear();

				if (query.length() < 2)
				{
					return;
				}

				// Search for matching items using ItemManager
				plugin.getClientThread().invokeLater(() ->
				{
					java.util.List<ItemSuggestion> results = searchItems(query, 10);
					SwingUtilities.invokeLater(() ->
					{
						for (ItemSuggestion item : results)
						{
							suggestionModel.addElement(item);
						}
					});
				});
			}
		});

		// Double-click to select item
		suggestionList.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					ItemSuggestion selected = suggestionList.getSelectedValue();
					if (selected != null)
					{
						selectedItemId[0] = selected.getItemId();
						selectedItemDisplay.setText(selected.getName());
						valueField.setText(String.valueOf(selected.getGePrice()));
						offerButton.setEnabled(true);

						// Update total
						try
						{
							int qty = Integer.parseInt(quantityField.getText().trim());
							totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(qty * selected.getGePrice()) + " GP");
						}
						catch (NumberFormatException ignored)
						{
							totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(selected.getGePrice()) + " GP");
						}
					}
				}
			}
		});

		// Single-click selection listener
		suggestionList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting())
			{
				ItemSuggestion selected = suggestionList.getSelectedValue();
				if (selected != null)
				{
					selectedItemId[0] = selected.getItemId();
					selectedItemDisplay.setText(selected.getName());
					valueField.setText(String.valueOf(selected.getGePrice()));
					offerButton.setEnabled(true);

					// Update total
					try
					{
						int qty = Integer.parseInt(quantityField.getText().trim());
						totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(qty * selected.getGePrice()) + " GP");
					}
					catch (NumberFormatException ignored)
					{
						totalValueLabel.setText("Total: " + QuantityFormatter.quantityToStackSize(selected.getGePrice()) + " GP");
					}
				}
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
					JOptionPane.showMessageDialog(offerDialog,
						"Please select an item from the search results.",
						"No Item Selected",
						JOptionPane.WARNING_MESSAGE);
					return;
				}

				if (quantity <= 0)
				{
					JOptionPane.showMessageDialog(offerDialog,
						"Quantity must be greater than 0.",
						"Invalid Quantity",
						JOptionPane.WARNING_MESSAGE);
					return;
				}

				// FIXED: Show confirmation before adding to marketplace
				String offerSummary = String.format("Item: %s\nQuantity: %d\nValue: %s GP\n\nAdd this to the marketplace?",
					itemName, quantity, QuantityFormatter.quantityToStackSize(value * quantity));

				int offerConfirm = JOptionPane.showConfirmDialog(offerDialog,
					offerSummary,
					"Confirm Offer",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.QUESTION_MESSAGE);

				if (offerConfirm != JOptionPane.YES_OPTION)
				{
					return; // User cancelled
				}

				// Create lending entry
				LendingEntry entry = new LendingEntry();
				entry.setItemId(itemId);
				entry.setItem(itemName);
				entry.setItemName(itemName);
				entry.setQuantity(quantity);
				entry.setLender(currentPlayer);
				entry.setBorrower(null); // No borrower yet - this is an offering
				entry.setValue(value);
				entry.setLendTime(System.currentTimeMillis());
				entry.setDueTime(0L); // No due date yet
				entry.setGroupId(activeGroup.getId());

				// Add to marketplace
				marketplaceManager.addOffering(activeGroup.getId(), currentPlayer, entry);

				log.info("Added item to marketplace: {} (ID: {}) in group {}",
					itemName, itemId, activeGroup.getName());

				offerDialog.dispose();

				// Refresh the UI
				refresh();
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(offerDialog,
					"Please enter valid numbers for Quantity and Value.",
					"Invalid Input",
					JOptionPane.ERROR_MESSAGE);
			}
			catch (Exception ex)
			{
				log.error("Failed to add item to marketplace", ex);
				JOptionPane.showMessageDialog(offerDialog,
					"Failed to add item: " + ex.getMessage(),
					"Error",
					JOptionPane.ERROR_MESSAGE);
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
	 * Search for items matching the query
	 */
	// FIXED: Use client.getItemCount() instead of hardcoded 30000 so newer items (Oathplate etc.) are found
	// Also uses canonicalize to skip noted/placeholder duplicates
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

	/**
	 * Item suggestion for autocomplete
	 */
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

	/**
	 * ADDED: Show dialog to create an item set (bundle of items to lend together)
	 */
	private void showCreateSetDialog()
	{
		// Get active group
		net.runelite.client.plugins.lendingtracker.model.LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(
				this,
				"Please select a group first before creating an item set.",
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
				"You must be logged into the game to create item sets.",
				"Not Logged In",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		// Create set builder dialog
		JDialog setDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Create Item Set", true);
		setDialog.setLayout(new BorderLayout());
		setDialog.setPreferredSize(new Dimension(450, 550));

		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Top section: Set details
		JPanel detailsPanel = new JPanel(new GridBagLayout());
		detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(3, 3, 3, 3);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Set Name
		gbc.gridx = 0;
		gbc.gridy = 0;
		JLabel nameLabel = new JLabel("Set Name:");
		nameLabel.setForeground(Color.WHITE);
		detailsPanel.add(nameLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField setNameField = new JTextField(20);
		setNameField.setToolTipText("e.g., 'Max Melee Gear', 'Full Torva', 'CoX Setup'");
		detailsPanel.add(setNameField, gbc);

		// Description
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		JLabel descLabel = new JLabel("Description:");
		descLabel.setForeground(Color.WHITE);
		detailsPanel.add(descLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField descField = new JTextField(20);
		descField.setToolTipText("Optional description of the set");
		detailsPanel.add(descField, gbc);

		// Default Duration
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.gridwidth = 1;
		JLabel durationLabel = new JLabel("Default Duration (days):");
		durationLabel.setForeground(Color.WHITE);
		detailsPanel.add(durationLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField durationField = new JTextField("7", 5);
		detailsPanel.add(durationField, gbc);

		mainPanel.add(detailsPanel, BorderLayout.NORTH);

		// Middle section: Add items with autocomplete
		JPanel itemsSection = new JPanel(new BorderLayout(5, 5));
		itemsSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		itemsSection.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Items in Set",
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(),
			Color.WHITE
		));

		// ADDED: Top area with search row and presets dropdown
		JPanel itemsTopPanel = new JPanel();
		itemsTopPanel.setLayout(new BoxLayout(itemsTopPanel, BoxLayout.Y_AXIS));
		itemsTopPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Item entry row
		JPanel addItemRow = new JPanel(new BorderLayout(5, 0));
		addItemRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JTextField itemSearchField = new JTextField(15);
		itemSearchField.setToolTipText("Type item name to search");
		addItemRow.add(itemSearchField, BorderLayout.CENTER);

		JTextField qtyField = new JTextField("1", 3);
		qtyField.setToolTipText("Quantity");
		addItemRow.add(qtyField, BorderLayout.EAST);

		itemsTopPanel.add(addItemRow);

		// ADDED: Barrows presets dropdown
		JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
		presetRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel presetLabel = new JLabel("Presets:");
		presetLabel.setFont(FontManager.getRunescapeSmallFont());
		presetLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		presetRow.add(presetLabel);

		String[] presetOptions = {
			"-- Select --",
			"Torva Armor", "Virtus Armor",
			"Masori Armor", "Masori Armor (f)",
			"Inquisitor's Armor", "Justiciar Armor", "Crystal Armor",
			"Ancestral Armor", "Armadyl Armor", "Bandos Armor",
			"Dharok's Armor", "Guthan's Armor", "Verac's Armor",
			"Torag's Armor", "Karil's Armor", "Ahrim's Armor"
		};
		JComboBox<String> presetDropdown = new JComboBox<>(presetOptions);
		presetDropdown.setFont(FontManager.getRunescapeSmallFont());
		presetDropdown.setBackground(ColorScheme.DARK_GRAY_COLOR);
		presetDropdown.setForeground(Color.WHITE);
		presetRow.add(presetDropdown);

		// ADDED: Include weapon checkbox for sets that have one (Barrows, Inquisitor's)
		JCheckBox includeWeaponCheck = new JCheckBox("+ Weapon");
		includeWeaponCheck.setFont(FontManager.getRunescapeSmallFont());
		includeWeaponCheck.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		includeWeaponCheck.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		includeWeaponCheck.setToolTipText("Include the set's weapon (Barrows, Inquisitor's)");
		presetRow.add(includeWeaponCheck);

		itemsTopPanel.add(presetRow);

		itemsSection.add(itemsTopPanel, BorderLayout.NORTH);

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

		// Items added to set
		DefaultListModel<SetItemEntry> setItemsModel = new DefaultListModel<>();
		JList<SetItemEntry> setItemsList = new JList<>(setItemsModel);
		setItemsList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setItemsList.setForeground(Color.WHITE);
		setItemsList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
		JScrollPane setItemsScroll = new JScrollPane(setItemsList);
		setItemsScroll.setPreferredSize(new Dimension(250, 150));
		setItemsScroll.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Added Items (double-click to remove)",
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(),
			Color.GRAY
		));
		// Keep double-click to remove from set list (intentional - prevents accidental removal)
		itemsSection.add(setItemsScroll, BorderLayout.SOUTH);

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

		JButton createButton = new JButton("Create Set");
		createButton.setBackground(ColorScheme.BRAND_ORANGE);
		createButton.setForeground(Color.WHITE);

		JButton cancelButton = new JButton("Cancel");
		cancelButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cancelButton.setForeground(Color.WHITE);

		buttonRow.add(createButton);
		buttonRow.add(cancelButton);
		bottomPanel.add(buttonRow, BorderLayout.SOUTH);

		mainPanel.add(bottomPanel, BorderLayout.SOUTH);

		setDialog.add(mainPanel);

		// ADDED: Barrows preset handler - populates set items from predefined Barrows sets
		presetDropdown.addActionListener(presetEvt ->
		{
			String preset = (String) presetDropdown.getSelectedItem();
			if (preset == null || preset.startsWith("--")) return;

			// Armor set item IDs (helm, body, legs) and optional weapon
			int[][] armorPieces;
			String[] pieceNames;
			int weaponId = -1;
			String weaponName = null;
			switch (preset)
			{
				// High-end armor sets (helm, body, legs)
				case "Torva Armor":
					armorPieces = new int[][] {{26382}, {26384}, {26386}};
					pieceNames = new String[] {"Torva full helm", "Torva platebody", "Torva platelegs"};
					break;
				case "Virtus Armor":
					armorPieces = new int[][] {{26241}, {26243}, {26245}};
					pieceNames = new String[] {"Virtus mask", "Virtus robe top", "Virtus robe bottom"};
					break;
				case "Masori Armor":
					armorPieces = new int[][] {{27226}, {27229}, {27232}};
					pieceNames = new String[] {"Masori mask", "Masori body", "Masori chaps"};
					break;
				case "Masori Armor (f)":
					armorPieces = new int[][] {{27235}, {27238}, {27241}};
					pieceNames = new String[] {"Masori mask (f)", "Masori body (f)", "Masori chaps (f)"};
					break;
				case "Inquisitor's Armor":
					armorPieces = new int[][] {{24419}, {24420}, {24421}};
					pieceNames = new String[] {"Inquisitor's great helm", "Inquisitor's hauberk", "Inquisitor's plateskirt"};
					weaponId = 24417;
					weaponName = "Inquisitor's mace";
					break;
				case "Justiciar Armor":
					armorPieces = new int[][] {{22326}, {22327}, {22328}};
					pieceNames = new String[] {"Justiciar faceguard", "Justiciar chestguard", "Justiciar legguards"};
					break;
				case "Crystal Armor":
					armorPieces = new int[][] {{23971}, {23975}, {23979}};
					pieceNames = new String[] {"Crystal helm", "Crystal body", "Crystal legs"};
					break;
				case "Ancestral Armor":
					armorPieces = new int[][] {{21018}, {21021}, {21024}};
					pieceNames = new String[] {"Ancestral hat", "Ancestral robe top", "Ancestral robe bottom"};
					break;
				case "Armadyl Armor":
					armorPieces = new int[][] {{11826}, {11828}, {11830}};
					pieceNames = new String[] {"Armadyl helmet", "Armadyl chestplate", "Armadyl chainskirt"};
					break;
				case "Bandos Armor":
					armorPieces = new int[][] {{11832}, {11834}, {11836}};
					pieceNames = new String[] {"Bandos chestplate", "Bandos tassets", "Bandos boots"};
					break;
				// Barrows armor sets (helm, body, legs + optional weapon)
				case "Dharok's Armor":
					armorPieces = new int[][] {{4716}, {4720}, {4722}};
					pieceNames = new String[] {"Dharok's helm", "Dharok's platebody", "Dharok's platelegs"};
					weaponId = 4718;
					weaponName = "Dharok's greataxe";
					break;
				case "Guthan's Armor":
					armorPieces = new int[][] {{4724}, {4728}, {4730}};
					pieceNames = new String[] {"Guthan's helm", "Guthan's platebody", "Guthan's chainskirt"};
					weaponId = 4726;
					weaponName = "Guthan's warspear";
					break;
				case "Verac's Armor":
					armorPieces = new int[][] {{4753}, {4757}, {4759}};
					pieceNames = new String[] {"Verac's helm", "Verac's brassard", "Verac's plateskirt"};
					weaponId = 4755;
					weaponName = "Verac's flail";
					break;
				case "Torag's Armor":
					armorPieces = new int[][] {{4745}, {4749}, {4751}};
					pieceNames = new String[] {"Torag's helm", "Torag's platebody", "Torag's platelegs"};
					weaponId = 4747;
					weaponName = "Torag's hammers";
					break;
				case "Karil's Armor":
					armorPieces = new int[][] {{4732}, {4736}, {4738}};
					pieceNames = new String[] {"Karil's coif", "Karil's leathertop", "Karil's leatherskirt"};
					weaponId = 4734;
					weaponName = "Karil's crossbow";
					break;
				case "Ahrim's Armor":
					armorPieces = new int[][] {{4708}, {4712}, {4714}};
					pieceNames = new String[] {"Ahrim's hood", "Ahrim's robetop", "Ahrim's robeskirt"};
					weaponId = 4710;
					weaponName = "Ahrim's staff";
					break;
				default:
					return;
			}

			// ADDED: If weapon checkbox is checked and this set has a weapon, include it
			final int finalWeaponId = weaponId;
			final String finalWeaponName = weaponName;

			// Auto-fill set name if empty
			if (setNameField.getText().trim().isEmpty())
			{
				String autoName = preset;
				if (includeWeaponCheck.isSelected() && weaponId > 0)
				{
					autoName += " + Weapon";
				}
				setNameField.setText(autoName);
			}

			// Add each armor piece (skip duplicates), then optionally add weapon
			plugin.getClientThread().invokeLater(() ->
			{
				// Add armor pieces
				for (int p = 0; p < armorPieces.length; p++)
				{
					final int itemId = armorPieces[p][0];
					final String fallbackName = pieceNames[p];

					int gePrice = itemManager.getItemPrice(itemId);
					String actualName = fallbackName;
					try
					{
						net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
						if (comp != null && comp.getName() != null && !comp.getName().equals("null"))
						{
							actualName = comp.getName();
						}
					}
					catch (Exception ignored) {}

					final String name = actualName;
					final int price = gePrice;

					SwingUtilities.invokeLater(() ->
					{
						boolean exists = false;
						for (int i = 0; i < setItemsModel.size(); i++)
						{
							if (setItemsModel.get(i).itemId == itemId)
							{
								exists = true;
								break;
							}
						}
						if (!exists)
						{
							setItemsModel.addElement(new SetItemEntry(itemId, name, 1, price));
						}
					});
				}

				// ADDED: Include weapon if checkbox is checked and set has a weapon
				if (includeWeaponCheck.isSelected() && finalWeaponId > 0 && finalWeaponName != null)
				{
					int wepPrice = itemManager.getItemPrice(finalWeaponId);
					String wepActualName = finalWeaponName;
					try
					{
						net.runelite.api.ItemComposition comp = itemManager.getItemComposition(finalWeaponId);
						if (comp != null && comp.getName() != null && !comp.getName().equals("null"))
						{
							wepActualName = comp.getName();
						}
					}
					catch (Exception ignored) {}

					final String wepName = wepActualName;
					final int wepFinalPrice = wepPrice;

					SwingUtilities.invokeLater(() ->
					{
						boolean exists = false;
						for (int i = 0; i < setItemsModel.size(); i++)
						{
							if (setItemsModel.get(i).itemId == finalWeaponId)
							{
								exists = true;
								break;
							}
						}
						if (!exists)
						{
							setItemsModel.addElement(new SetItemEntry(finalWeaponId, wepName, 1, wepFinalPrice));
						}
					});
				}

				// Update total value after all items are added
				SwingUtilities.invokeLater(() ->
				{
					long total = 0;
					for (int i = 0; i < setItemsModel.size(); i++)
					{
						SetItemEntry e2 = setItemsModel.get(i);
						total += (long) e2.value * e2.quantity;
					}
					totalValueDisplay.setText("Total Value: " + QuantityFormatter.quantityToStackSize(total) + " GP");
				});
			});

			// Reset dropdown
			presetDropdown.setSelectedIndex(0);
		});

		// Autocomplete listener
		itemSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
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
				searchTimer = new javax.swing.Timer(200, evt -> performSearch());
				searchTimer.setRepeats(false);
				searchTimer.start();
			}

			private void performSearch()
			{
				String query = itemSearchField.getText().trim().toLowerCase();
				suggestionModel.clear();

				if (query.length() < 2)
				{
					return;
				}

				plugin.getClientThread().invokeLater(() ->
				{
					java.util.List<ItemSuggestion> results = searchItems(query, 8);
					SwingUtilities.invokeLater(() ->
					{
						for (ItemSuggestion item : results)
						{
							suggestionModel.addElement(item);
						}
					});
				});
			}
		});

		// CHANGED: Add item on single-click selection from autocomplete
		suggestionList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting()) return;

			ItemSuggestion selected = suggestionList.getSelectedValue();
			if (selected != null)
			{
				try
				{
					int qty = Integer.parseInt(qtyField.getText().trim());
					if (qty <= 0) qty = 1;

					// Check for duplicates
					for (int i = 0; i < setItemsModel.size(); i++)
					{
						if (setItemsModel.get(i).itemId == selected.getItemId())
						{
							JOptionPane.showMessageDialog(setDialog,
								"Item already in set. Remove it first if you want to change quantity.",
								"Duplicate Item",
								JOptionPane.WARNING_MESSAGE);
							suggestionList.clearSelection();
							return;
						}
					}

					SetItemEntry entry = new SetItemEntry(
						selected.getItemId(),
						selected.getName(),
						qty,
						selected.getGePrice()
					);
					setItemsModel.addElement(entry);

					// Update total
					long total = 0;
					for (int i = 0; i < setItemsModel.size(); i++)
					{
						SetItemEntry e2 = setItemsModel.get(i);
						total += (long) e2.value * e2.quantity;
					}
					totalValueDisplay.setText("Total Value: " + QuantityFormatter.quantityToStackSize(total) + " GP");

					// Clear search field and selection
					itemSearchField.setText("");
					qtyField.setText("1");
					suggestionModel.clear();
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(setDialog,
						"Invalid quantity",
						"Error",
						JOptionPane.ERROR_MESSAGE);
				}
			}
		});

		// Remove item when double-clicked in set list
		setItemsList.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					int idx = setItemsList.getSelectedIndex();
					if (idx >= 0)
					{
						setItemsModel.remove(idx);

						// Update total
						long total = 0;
						for (int i = 0; i < setItemsModel.size(); i++)
						{
							SetItemEntry e2 = setItemsModel.get(i);
							total += (long) e2.value * e2.quantity;
						}
						totalValueDisplay.setText("Total Value: " + QuantityFormatter.quantityToStackSize(total) + " GP");
					}
				}
			}
		});

		// Create button action
		final String finalCurrentPlayer = currentPlayer;
		createButton.addActionListener(e ->
		{
			String setName = setNameField.getText().trim();
			if (setName.isEmpty())
			{
				JOptionPane.showMessageDialog(setDialog,
					"Please enter a name for the set.",
					"Missing Name",
					JOptionPane.WARNING_MESSAGE);
				return;
			}

			if (setItemsModel.isEmpty())
			{
				JOptionPane.showMessageDialog(setDialog,
					"Please add at least one item to the set.",
					"No Items",
					JOptionPane.WARNING_MESSAGE);
				return;
			}

			// FIXED: Show confirmation dialog BEFORE creating the set
			// Build summary for confirmation
			StringBuilder summary = new StringBuilder();
			summary.append("Set Name: ").append(setName).append("\n");
			summary.append("Items (").append(setItemsModel.size()).append("):\n");
			long confirmTotal = 0;
			for (int i = 0; i < setItemsModel.size(); i++)
			{
				SetItemEntry entry = setItemsModel.get(i);
				summary.append("  - ").append(entry.name).append(" x").append(entry.quantity).append("\n");
				confirmTotal += (long) entry.value * entry.quantity;
			}
			summary.append("Total Value: ").append(QuantityFormatter.quantityToStackSize(confirmTotal)).append(" GP\n");
			if (!descField.getText().trim().isEmpty())
			{
				summary.append("Description: ").append(descField.getText().trim()).append("\n");
			}
			summary.append("\nCreate this item set?");

			int confirm = JOptionPane.showConfirmDialog(setDialog,
				summary.toString(),
				"Confirm Create Set",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE);

			if (confirm != JOptionPane.YES_OPTION)
			{
				return; // User cancelled - do NOT create the set
			}

			try
			{
				// Create the set
				ItemSet newSet = itemSetManager.createItemSet(
					activeGroup.getId(),
					setName,
					finalCurrentPlayer,
					descField.getText().trim()
				);

				// Add items to the set
				for (int i = 0; i < setItemsModel.size(); i++)
				{
					SetItemEntry entry = setItemsModel.get(i);
					itemSetManager.addItemToSet(
						activeGroup.getId(),
						newSet.getId(),
						entry.itemId,
						entry.name,
						entry.quantity,
						entry.value
					);
				}

				// Set duration
				try
				{
					int duration = Integer.parseInt(durationField.getText().trim());
					if (duration > 0)
					{
						itemSetManager.updateItemSet(
							activeGroup.getId(),
							newSet.getId(),
							null, null, null, null,
							duration
						);
					}
				}
				catch (NumberFormatException ignored) {}

				log.info("Created item set '{}' with {} items in group {}",
					setName, setItemsModel.size(), activeGroup.getName());

				setDialog.dispose();
				refresh();
			}
			catch (Exception ex)
			{
				log.error("Failed to create item set", ex);
				JOptionPane.showMessageDialog(setDialog,
					"Failed to create set: " + ex.getMessage(),
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		});

		cancelButton.addActionListener(e -> setDialog.dispose());

		setDialog.pack();
		setDialog.setLocationRelativeTo(this);
		setDialog.setVisible(true);
	}

	/**
	 * Helper class for items being added to a set
	 */
	private static class SetItemEntry
	{
		final int itemId;
		final String name;
		final int quantity;
		final int value;

		SetItemEntry(int itemId, String name, int quantity, int value)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
		}

		@Override
		public String toString()
		{
			return name + " x" + quantity + " (" + QuantityFormatter.quantityToStackSize((long) value * quantity) + " GP)";
		}
	}

	/**
	 * ADDED: Show dialog to view and manage user's item sets
	 */
	/**
	 * CHANGED: showMySetsDialog -> showMyItemsDialog
	 * Now shows all user's items organized by category:
	 * - My Sets (item sets I own)
	 * - My Offerings (individual items I'm offering to lend)
	 * - Borrowed Items (items/sets I'm currently borrowing)
	 * - Lent Out (items/sets I've lent to others)
	 */
	private void showMyItemsDialog()
	{
		// Get active group
		net.runelite.client.plugins.lendingtracker.model.LendingGroup activeGroup = groupConfigStore.getActiveGroup();
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
				"You must be logged in to view your items.",
				"Not Logged In",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		String groupId = activeGroup.getId();

		// Get user's data from various sources
		java.util.List<ItemSet> mySets = itemSetManager.getItemSetsByOwner(groupId, currentPlayer);
		java.util.List<ItemSet> borrowedSets = itemSetManager.getItemSetsBorrowedBy(groupId, currentPlayer);
		java.util.List<LendingEntry> myOfferings = marketplaceManager.getOfferingsByOwner(groupId, currentPlayer);
		java.util.List<LendingEntry> borrowedItems = lendingManager.getEntriesBorrowedBy(groupId, currentPlayer);
		java.util.List<LendingEntry> lentOutItems = lendingManager.getEntriesLentBy(groupId, currentPlayer);

		// Create dialog
		JDialog itemsDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "My Items", true);
		itemsDialog.setLayout(new BorderLayout());
		itemsDialog.setPreferredSize(new Dimension(450, 500));

		JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
		mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
		mainPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Tabs for different categories
		JTabbedPane tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);
		tabbedPane.setFont(FontManager.getRunescapeSmallFont());

		// Tab 1: My Sets (item sets I own)
		JPanel setsPanel = createSetsListPanel(mySets, true, groupId, itemsDialog);
		tabbedPane.addTab("My Sets (" + mySets.size() + ")", setsPanel);

		// Tab 2: My Offerings (individual items I'm offering to lend)
		JPanel offeringsPanel = createOfferingsListPanel(myOfferings, groupId, itemsDialog);
		tabbedPane.addTab("Offerings (" + myOfferings.size() + ")", offeringsPanel);

		// Tab 3: Borrowed Items (items and sets I'm currently borrowing)
		JPanel borrowedPanel = createBorrowedItemsPanel(borrowedItems, borrowedSets, groupId, itemsDialog);
		int totalBorrowed = borrowedItems.size() + borrowedSets.size();
		tabbedPane.addTab("Borrowed (" + totalBorrowed + ")", borrowedPanel);

		// Tab 4: Lent Out (items and sets I've lent to others)
		java.util.List<ItemSet> lentSets = mySets.stream()
			.filter(ItemSet::isLentOut)
			.collect(java.util.stream.Collectors.toList());
		JPanel lentPanel = createLentOutPanel(lentOutItems, lentSets, groupId, itemsDialog);
		int totalLent = lentOutItems.size() + lentSets.size();
		tabbedPane.addTab("Lent Out (" + totalLent + ")", lentPanel);

		mainPanel.add(tabbedPane, BorderLayout.CENTER);

		// Close button
		JButton closeButton = new JButton("Close");
		closeButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		closeButton.setForeground(Color.WHITE);
		closeButton.addActionListener(e -> itemsDialog.dispose());

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.add(closeButton);
		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		itemsDialog.add(mainPanel);
		itemsDialog.pack();
		itemsDialog.setLocationRelativeTo(this);
		itemsDialog.setVisible(true);
	}

	/**
	 * Create panel for user's offerings (individual items offered to lend)
	 */
	private JPanel createOfferingsListPanel(java.util.List<LendingEntry> offerings, String groupId, JDialog parentDialog)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (offerings.isEmpty())
		{
			JLabel emptyLabel = new JLabel("<html><center>You haven't offered any items yet.<br><br>Use 'Offer Item' to add items to the marketplace.</center></html>");
			emptyLabel.setForeground(Color.GRAY);
			emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
			panel.add(emptyLabel, BorderLayout.CENTER);
			return panel;
		}

		JPanel listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		for (LendingEntry entry : offerings)
		{
			JPanel card = createOfferingCard(entry, groupId, parentDialog);
			listPanel.add(card);
			listPanel.add(Box.createVerticalStrut(5));
		}

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		panel.add(scrollPane, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Create card for an offering entry
	 */
	private JPanel createOfferingCard(LendingEntry entry, String groupId, JDialog parentDialog)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

		// Left: Item info
		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel(entry.getItemName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		String valueText = "x" + entry.getQuantity() + " | " + QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP";
		JLabel valueLabel = new JLabel(valueText);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.YELLOW);

		infoPanel.add(nameLabel);
		infoPanel.add(valueLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Right: Remove button
		JButton removeButton = new JButton("Remove");
		removeButton.setFont(FontManager.getRunescapeSmallFont());
		removeButton.setBackground(Color.RED.darker());
		removeButton.setForeground(Color.WHITE);
		removeButton.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(parentDialog,
				"Remove '" + entry.getItemName() + "' from marketplace?",
				"Confirm Remove",
				JOptionPane.YES_NO_OPTION);
			if (confirm == JOptionPane.YES_OPTION)
			{
				recorder.removeAvailable(groupId, entry.getLender(), entry.getItemName(), entry.getItemId());
				parentDialog.dispose();
				showMyItemsDialog(); // Refresh
				refresh();
			}
		});

		card.add(removeButton, BorderLayout.EAST);

		return card;
	}

	/**
	 * Create panel for borrowed items (both individual items and sets)
	 */
	private JPanel createBorrowedItemsPanel(java.util.List<LendingEntry> borrowedItems,
											 java.util.List<ItemSet> borrowedSets, String groupId, JDialog parentDialog)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (borrowedItems.isEmpty() && borrowedSets.isEmpty())
		{
			JLabel emptyLabel = new JLabel("<html><center>You haven't borrowed any items or sets.</center></html>");
			emptyLabel.setForeground(Color.GRAY);
			emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
			panel.add(emptyLabel, BorderLayout.CENTER);
			return panel;
		}

		JPanel listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Add borrowed sets first
		if (!borrowedSets.isEmpty())
		{
			JLabel setsHeader = new JLabel("  Borrowed Sets");
			setsHeader.setFont(FontManager.getRunescapeBoldFont());
			setsHeader.setForeground(ColorScheme.BRAND_ORANGE);
			listPanel.add(setsHeader);
			listPanel.add(Box.createVerticalStrut(5));

			for (ItemSet set : borrowedSets)
			{
				JPanel card = createBorrowedSetCard(set, groupId, parentDialog);
				listPanel.add(card);
				listPanel.add(Box.createVerticalStrut(5));
			}
		}

		// Add borrowed individual items
		if (!borrowedItems.isEmpty())
		{
			if (!borrowedSets.isEmpty())
			{
				listPanel.add(Box.createVerticalStrut(10));
			}
			JLabel itemsHeader = new JLabel("  Borrowed Items");
			itemsHeader.setFont(FontManager.getRunescapeBoldFont());
			itemsHeader.setForeground(ColorScheme.BRAND_ORANGE);
			listPanel.add(itemsHeader);
			listPanel.add(Box.createVerticalStrut(5));

			for (LendingEntry entry : borrowedItems)
			{
				JPanel card = createBorrowedItemCard(entry, groupId, parentDialog);
				listPanel.add(card);
				listPanel.add(Box.createVerticalStrut(5));
			}
		}

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		panel.add(scrollPane, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Create card for a borrowed set
	 */
	private JPanel createBorrowedSetCard(ItemSet set, String groupId, JDialog parentDialog)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel("[SET] " + set.getName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		String valueText = set.getItemCount() + " items | " + QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP";
		JLabel valueLabel = new JLabel(valueText);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.YELLOW);

		JLabel ownerLabel = new JLabel("From: " + set.getOwnerName());
		ownerLabel.setFont(FontManager.getRunescapeSmallFont());
		ownerLabel.setForeground(Color.GRAY);

		// Due date status
		String dueText;
		Color dueColor;
		if (set.isOverdue())
		{
			dueText = "OVERDUE";
			dueColor = Color.RED;
		}
		else if (set.getDueAt() > 0)
		{
			long daysLeft = (set.getDueAt() - System.currentTimeMillis()) / (24L * 60 * 60 * 1000);
			dueText = "Due in " + daysLeft + " days";
			dueColor = daysLeft <= 1 ? Color.ORANGE : Color.GREEN;
		}
		else
		{
			dueText = "No due date";
			dueColor = Color.GRAY;
		}
		JLabel dueLabel = new JLabel(dueText);
		dueLabel.setFont(FontManager.getRunescapeSmallFont());
		dueLabel.setForeground(dueColor);

		infoPanel.add(nameLabel);
		infoPanel.add(valueLabel);
		infoPanel.add(ownerLabel);
		infoPanel.add(dueLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Double-click to view details
		card.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					showSetDetailsDialog(set);
				}
			}
		});

		return card;
	}

	/**
	 * Create card for a borrowed individual item
	 */
	private JPanel createBorrowedItemCard(LendingEntry entry, String groupId, JDialog parentDialog)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel(entry.getItemName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		String valueText = "x" + entry.getQuantity() + " | " + QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP";
		JLabel valueLabel = new JLabel(valueText);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.YELLOW);

		JLabel lenderLabel = new JLabel("From: " + entry.getLender());
		lenderLabel.setFont(FontManager.getRunescapeSmallFont());
		lenderLabel.setForeground(Color.GRAY);

		// Due date status
		String dueText;
		Color dueColor;
		if (entry.getDueTime() > 0)
		{
			long now = System.currentTimeMillis();
			if (entry.getDueTime() < now)
			{
				dueText = "OVERDUE";
				dueColor = Color.RED;
			}
			else
			{
				long daysLeft = (entry.getDueTime() - now) / (24L * 60 * 60 * 1000);
				dueText = "Due in " + daysLeft + " days";
				dueColor = daysLeft <= 1 ? Color.ORANGE : Color.GREEN;
			}
		}
		else
		{
			dueText = "No due date";
			dueColor = Color.GRAY;
		}
		JLabel dueLabel = new JLabel(dueText);
		dueLabel.setFont(FontManager.getRunescapeSmallFont());
		dueLabel.setForeground(dueColor);

		infoPanel.add(nameLabel);
		infoPanel.add(valueLabel);
		infoPanel.add(lenderLabel);
		infoPanel.add(dueLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		return card;
	}

	/**
	 * Create panel for items/sets lent out to others
	 */
	private JPanel createLentOutPanel(java.util.List<LendingEntry> lentItems,
									   java.util.List<ItemSet> lentSets, String groupId, JDialog parentDialog)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (lentItems.isEmpty() && lentSets.isEmpty())
		{
			JLabel emptyLabel = new JLabel("<html><center>You haven't lent any items or sets.</center></html>");
			emptyLabel.setForeground(Color.GRAY);
			emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
			panel.add(emptyLabel, BorderLayout.CENTER);
			return panel;
		}

		JPanel listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Add lent sets first
		if (!lentSets.isEmpty())
		{
			JLabel setsHeader = new JLabel("  Lent Sets");
			setsHeader.setFont(FontManager.getRunescapeBoldFont());
			setsHeader.setForeground(ColorScheme.BRAND_ORANGE);
			listPanel.add(setsHeader);
			listPanel.add(Box.createVerticalStrut(5));

			for (ItemSet set : lentSets)
			{
				JPanel card = createLentSetCard(set, groupId, parentDialog);
				listPanel.add(card);
				listPanel.add(Box.createVerticalStrut(5));
			}
		}

		// Add lent individual items
		if (!lentItems.isEmpty())
		{
			if (!lentSets.isEmpty())
			{
				listPanel.add(Box.createVerticalStrut(10));
			}
			JLabel itemsHeader = new JLabel("  Lent Items");
			itemsHeader.setFont(FontManager.getRunescapeBoldFont());
			itemsHeader.setForeground(ColorScheme.BRAND_ORANGE);
			listPanel.add(itemsHeader);
			listPanel.add(Box.createVerticalStrut(5));

			for (LendingEntry entry : lentItems)
			{
				JPanel card = createLentItemCard(entry, groupId, parentDialog);
				listPanel.add(card);
				listPanel.add(Box.createVerticalStrut(5));
			}
		}

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		panel.add(scrollPane, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Create card for a lent set
	 */
	private JPanel createLentSetCard(ItemSet set, String groupId, JDialog parentDialog)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel("[SET] " + set.getName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		String valueText = set.getItemCount() + " items | " + QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP";
		JLabel valueLabel = new JLabel(valueText);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.YELLOW);

		JLabel borrowerLabel = new JLabel("To: " + set.getCurrentBorrower());
		borrowerLabel.setFont(FontManager.getRunescapeSmallFont());
		borrowerLabel.setForeground(Color.GRAY);

		// Due date status
		String dueText;
		Color dueColor;
		if (set.isOverdue())
		{
			dueText = "OVERDUE";
			dueColor = Color.RED;
		}
		else if (set.getDueAt() > 0)
		{
			long daysLeft = (set.getDueAt() - System.currentTimeMillis()) / (24L * 60 * 60 * 1000);
			dueText = "Due in " + daysLeft + " days";
			dueColor = daysLeft <= 1 ? Color.ORANGE : Color.GREEN;
		}
		else
		{
			dueText = "No due date";
			dueColor = Color.GRAY;
		}
		JLabel dueLabel = new JLabel(dueText);
		dueLabel.setFont(FontManager.getRunescapeSmallFont());
		dueLabel.setForeground(dueColor);

		infoPanel.add(nameLabel);
		infoPanel.add(valueLabel);
		infoPanel.add(borrowerLabel);
		infoPanel.add(dueLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Right: Mark Returned button
		JButton returnButton = new JButton("Returned");
		returnButton.setFont(FontManager.getRunescapeSmallFont());
		returnButton.setBackground(Color.GREEN.darker());
		returnButton.setForeground(Color.WHITE);
		returnButton.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(parentDialog,
				"Mark set '" + set.getName() + "' as returned from " + set.getCurrentBorrower() + "?",
				"Confirm Return",
				JOptionPane.YES_NO_OPTION);
			if (confirm == JOptionPane.YES_OPTION)
			{
				itemSetManager.returnItemSet(groupId, set.getId());
				parentDialog.dispose();
				showMyItemsDialog(); // Refresh
				refresh();
			}
		});

		card.add(returnButton, BorderLayout.EAST);

		return card;
	}

	/**
	 * Create card for a lent individual item
	 */
	private JPanel createLentItemCard(LendingEntry entry, String groupId, JDialog parentDialog)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel(entry.getItemName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		String valueText = "x" + entry.getQuantity() + " | " + QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP";
		JLabel valueLabel = new JLabel(valueText);
		valueLabel.setFont(FontManager.getRunescapeSmallFont());
		valueLabel.setForeground(Color.YELLOW);

		JLabel borrowerLabel = new JLabel("To: " + entry.getBorrower());
		borrowerLabel.setFont(FontManager.getRunescapeSmallFont());
		borrowerLabel.setForeground(Color.GRAY);

		// Due date status
		String dueText;
		Color dueColor;
		if (entry.getDueTime() > 0)
		{
			long now = System.currentTimeMillis();
			if (entry.getDueTime() < now)
			{
				dueText = "OVERDUE";
				dueColor = Color.RED;
			}
			else
			{
				long daysLeft = (entry.getDueTime() - now) / (24L * 60 * 60 * 1000);
				dueText = "Due in " + daysLeft + " days";
				dueColor = daysLeft <= 1 ? Color.ORANGE : Color.GREEN;
			}
		}
		else
		{
			dueText = "No due date";
			dueColor = Color.GRAY;
		}
		JLabel dueLabel = new JLabel(dueText);
		dueLabel.setFont(FontManager.getRunescapeSmallFont());
		dueLabel.setForeground(dueColor);

		infoPanel.add(nameLabel);
		infoPanel.add(valueLabel);
		infoPanel.add(borrowerLabel);
		infoPanel.add(dueLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Right: Mark Returned button
		JButton returnButton = new JButton("Returned");
		returnButton.setFont(FontManager.getRunescapeSmallFont());
		returnButton.setBackground(Color.GREEN.darker());
		returnButton.setForeground(Color.WHITE);
		returnButton.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(parentDialog,
				"Mark '" + entry.getItemName() + "' as returned from " + entry.getBorrower() + "?",
				"Confirm Return",
				JOptionPane.YES_NO_OPTION);
			if (confirm == JOptionPane.YES_OPTION)
			{
				// Use moveToHistory to properly mark as returned
				lendingManager.moveToHistory(entry.getId(), true);
				parentDialog.dispose();
				showMyItemsDialog(); // Refresh
				refresh();
			}
		});

		card.add(returnButton, BorderLayout.EAST);

		return card;
	}

	/**
	 * Create a panel displaying a list of item sets
	 */
	private JPanel createSetsListPanel(java.util.List<ItemSet> sets, boolean isOwner, String groupId, JDialog parentDialog)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (sets.isEmpty())
		{
			JLabel emptyLabel = new JLabel(isOwner
				? "<html><center>You haven't created any item sets yet.<br><br>Use 'Create Set' to bundle items together.</center></html>"
				: "<html><center>You haven't borrowed any item sets.</center></html>");
			emptyLabel.setForeground(Color.GRAY);
			emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);
			panel.add(emptyLabel, BorderLayout.CENTER);
			return panel;
		}

		JPanel listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		for (ItemSet set : sets)
		{
			JPanel setCard = createSetCard(set, isOwner, groupId, parentDialog);
			listPanel.add(setCard);
			listPanel.add(Box.createVerticalStrut(5));
		}

		JScrollPane scrollPane = new JScrollPane(listPanel);
		scrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		panel.add(scrollPane, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * Create a card displaying an item set
	 */
	private JPanel createSetCard(ItemSet set, boolean isOwner, String groupId, JDialog parentDialog)
	{
		JPanel card = new JPanel(new BorderLayout(5, 0));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

		// Left: Set info
		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel nameLabel = new JLabel(set.getName());
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);

		String itemsText = set.getItemCount() + " items | " + QuantityFormatter.quantityToStackSize(set.getTotalValue()) + " GP";
		JLabel itemsLabel = new JLabel(itemsText);
		itemsLabel.setFont(FontManager.getRunescapeSmallFont());
		itemsLabel.setForeground(Color.YELLOW);

		String statusText;
		Color statusColor;
		if (set.isLentOut())
		{
			if (set.isOverdue())
			{
				statusText = "OVERDUE - Lent to " + set.getCurrentBorrower();
				statusColor = Color.RED;
			}
			else
			{
				statusText = "Lent to " + set.getCurrentBorrower();
				statusColor = Color.ORANGE;
			}
		}
		else
		{
			statusText = "Available";
			statusColor = Color.GREEN;
		}
		JLabel statusLabel = new JLabel(statusText);
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(statusColor);

		infoPanel.add(nameLabel);
		infoPanel.add(itemsLabel);
		infoPanel.add(statusLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Right: Action buttons
		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (isOwner)
		{
			if (!set.isLentOut())
			{
				JButton lendButton = new JButton("Lend");
				lendButton.setFont(FontManager.getRunescapeSmallFont());
				lendButton.setBackground(ColorScheme.BRAND_ORANGE);
				lendButton.setForeground(Color.WHITE);
				lendButton.addActionListener(e -> showLendSetDialog(set, groupId, parentDialog));
				buttonPanel.add(lendButton);
			}
			else
			{
				JButton returnButton = new JButton("Return");
				returnButton.setFont(FontManager.getRunescapeSmallFont());
				returnButton.setBackground(Color.GREEN.darker());
				returnButton.setForeground(Color.WHITE);
				returnButton.addActionListener(e ->
				{
					int confirm = JOptionPane.showConfirmDialog(parentDialog,
						"Mark set '" + set.getName() + "' as returned from " + set.getCurrentBorrower() + "?",
						"Confirm Return",
						JOptionPane.YES_NO_OPTION);
					if (confirm == JOptionPane.YES_OPTION)
					{
						itemSetManager.returnItemSet(groupId, set.getId());
						parentDialog.dispose();
						showMyItemsDialog(); // Refresh
						refresh();
					}
				});
				buttonPanel.add(returnButton);
			}

			JButton deleteButton = new JButton("Delete");
			deleteButton.setFont(FontManager.getRunescapeSmallFont());
			deleteButton.setBackground(Color.RED.darker());
			deleteButton.setForeground(Color.WHITE);
			deleteButton.addActionListener(e ->
			{
				int confirm = JOptionPane.showConfirmDialog(parentDialog,
					"Delete set '" + set.getName() + "'? This cannot be undone.",
					"Confirm Delete",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);
				if (confirm == JOptionPane.YES_OPTION)
				{
					itemSetManager.deleteItemSet(groupId, set.getId());
					parentDialog.dispose();
					showMyItemsDialog(); // Refresh
					refresh();
				}
			});
			buttonPanel.add(deleteButton);
		}

		card.add(buttonPanel, BorderLayout.EAST);

		// Click to show details
		card.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					showSetDetailsDialog(set);
				}
			}

			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				card.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				infoPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				buttonPanel.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				card.setBackground(ColorScheme.DARK_GRAY_COLOR);
				infoPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
				buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
			}
		});

		return card;
	}

	/**
	 * Show dialog to lend a set to someone
	 */
	private void showLendSetDialog(ItemSet set, String groupId, JDialog parentDialog)
	{
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		gbc.gridx = 0;
		gbc.gridy = 0;
		panel.add(new JLabel("Borrower Name:"), gbc);

		gbc.gridx = 1;
		JTextField borrowerField = new JTextField(15);
		panel.add(borrowerField, gbc);

		gbc.gridx = 0;
		gbc.gridy = 1;
		panel.add(new JLabel("Duration (days):"), gbc);

		gbc.gridx = 1;
		JTextField durationField = new JTextField(String.valueOf(set.getDefaultDurationDays()), 5);
		panel.add(durationField, gbc);

		int result = JOptionPane.showConfirmDialog(parentDialog,
			panel,
			"Lend Set: " + set.getName(),
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE);

		if (result == JOptionPane.OK_OPTION)
		{
			String borrower = borrowerField.getText().trim();
			if (borrower.isEmpty())
			{
				JOptionPane.showMessageDialog(parentDialog,
					"Please enter the borrower's name.",
					"Missing Borrower",
					JOptionPane.WARNING_MESSAGE);
				return;
			}

			try
			{
				int days = Integer.parseInt(durationField.getText().trim());
				long dueTime = System.currentTimeMillis() + (days * 24L * 60 * 60 * 1000);

				itemSetManager.lendItemSet(groupId, set.getId(), borrower, dueTime);

				JOptionPane.showMessageDialog(parentDialog,
					String.format("Set '%s' lent to %s for %d days", set.getName(), borrower, days),
					"Set Lent",
					JOptionPane.INFORMATION_MESSAGE);

				parentDialog.dispose();
				showMyItemsDialog(); // Refresh
				refresh();
			}
			catch (NumberFormatException e)
			{
				JOptionPane.showMessageDialog(parentDialog,
					"Invalid duration",
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		}
	}

	/**
	 * Show detailed view of an item set
	 */
	private void showSetDetailsDialog(ItemSet set)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("<html><body style='width: 250px;'>");
		sb.append("<h3>").append(set.getName()).append("</h3>");

		if (set.getDescription() != null && !set.getDescription().isEmpty())
		{
			sb.append("<p><i>").append(set.getDescription()).append("</i></p>");
		}

		sb.append("<p><b>Owner:</b> ").append(set.getOwnerName()).append("</p>");
		sb.append("<p><b>Total Value:</b> ").append(QuantityFormatter.quantityToStackSize(set.getTotalValue())).append(" GP</p>");

		sb.append("<p><b>Items (").append(set.getItemCount()).append("):</b></p><ul>");
		if (set.getItems() != null)
		{
			for (ItemSet.ItemSetEntry item : set.getItems())
			{
				sb.append("<li>").append(item.getItemName()).append(" x").append(item.getQuantity())
					.append(" (").append(QuantityFormatter.quantityToStackSize(item.getValue() * item.getQuantity())).append(" GP)</li>");
			}
		}
		sb.append("</ul>");

		if (set.isLentOut())
		{
			sb.append("<p><b>Status:</b> <font color='orange'>Lent to ").append(set.getCurrentBorrower()).append("</font></p>");
			if (set.isOverdue())
			{
				sb.append("<p><font color='red'><b>OVERDUE!</b></font></p>");
			}
		}
		else
		{
			sb.append("<p><b>Status:</b> <font color='green'>Available</font></p>");
		}

		sb.append("</body></html>");

		JOptionPane.showMessageDialog(this,
			sb.toString(),
			"Item Set Details",
			JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * ADDED: Show detailed view of a Looking For request with all items
	 */
	private void showLookingForDetails(LookingForRequest request)
	{
		StringBuilder sb = new StringBuilder();
		sb.append("<html><body style='width: 280px;'>");
		sb.append("<h3>").append(request.itemName).append("</h3>");

		sb.append("<p><b>Requester:</b> ").append(request.requesterName).append("</p>");
		sb.append("<p><b>Duration:</b> ").append(request.durationDays).append(" days</p>");
		sb.append("<p><b>Total Value:</b> ").append(QuantityFormatter.quantityToStackSize(request.getTotalValue())).append(" GP</p>");

		if (request.notes != null && !request.notes.isEmpty())
		{
			sb.append("<p><b>Notes:</b> <i>").append(request.notes).append("</i></p>");
		}

		sb.append("<p><b>Items Needed (").append(request.getItemCount()).append("):</b></p><ul>");
		if (!request.items.isEmpty())
		{
			for (LookingForItem item : request.items)
			{
				sb.append("<li>").append(item.itemName).append(" x").append(item.quantity)
					.append(" (").append(QuantityFormatter.quantityToStackSize(item.value * item.quantity)).append(" GP)</li>");
			}
		}
		else
		{
			// Single item request
			sb.append("<li>").append(request.itemName).append(" x").append(request.quantity).append("</li>");
		}
		sb.append("</ul>");

		sb.append("<p><b>Posted:</b> ").append(request.getPostedTimeFormatted()).append("</p>");

		sb.append("</body></html>");

		JOptionPane.showMessageDialog(this,
			sb.toString(),
			"Looking For - Request Details",
			JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * ADDED: Show dialog to post a "Looking For" request
	 * UPDATED: Now matches Create Set style with autocomplete and multiple items
	 */
	private void showLookingForDialog()
	{
		// Get active group
		net.runelite.client.plugins.lendingtracker.model.LendingGroup activeGroup = groupConfigStore.getActiveGroup();
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
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(3, 3, 3, 3);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Request Title (optional)
		gbc.gridx = 0;
		gbc.gridy = 0;
		JLabel titleLabel = new JLabel("Request Title:");
		titleLabel.setForeground(Color.WHITE);
		detailsPanel.add(titleLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField titleField = new JTextField(20);
		titleField.setToolTipText("Optional title like 'Need CoX Gear' or 'ToB Setup'");
		detailsPanel.add(titleField, gbc);

		// Duration
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.gridwidth = 1;
		JLabel durationLabel = new JLabel("Duration (days):");
		durationLabel.setForeground(Color.WHITE);
		detailsPanel.add(durationLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField durationField = new JTextField("7", 5);
		detailsPanel.add(durationField, gbc);

		// Notes
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.gridwidth = 1;
		JLabel notesLabel = new JLabel("Notes:");
		notesLabel.setForeground(Color.WHITE);
		detailsPanel.add(notesLabel, gbc);

		gbc.gridx = 1;
		gbc.gridwidth = 2;
		JTextField notesField = new JTextField(20);
		notesField.setToolTipText("Optional note to potential lenders");
		detailsPanel.add(notesField, gbc);

		mainPanel.add(detailsPanel, BorderLayout.NORTH);

		// Middle section: Add items with autocomplete
		JPanel itemsSection = new JPanel(new BorderLayout(5, 5));
		itemsSection.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		itemsSection.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Items Needed",
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(),
			Color.WHITE
		));

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
		requestItemsScroll.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Added Items (double-click to remove)",
			javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
			javax.swing.border.TitledBorder.DEFAULT_POSITION,
			FontManager.getRunescapeSmallFont(),
			Color.GRAY
		));
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

		// Autocomplete listener
		itemSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
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
				searchTimer = new javax.swing.Timer(200, evt -> performSearch());
				searchTimer.setRepeats(false);
				searchTimer.start();
			}

			private void performSearch()
			{
				String query = itemSearchField.getText().trim().toLowerCase();
				suggestionModel.clear();

				if (query.length() < 2)
				{
					return;
				}

				plugin.getClientThread().invokeLater(() ->
				{
					java.util.List<ItemSuggestion> results = searchItems(query, 8);
					SwingUtilities.invokeLater(() ->
					{
						for (ItemSuggestion item : results)
						{
							suggestionModel.addElement(item);
						}
					});
				});
			}
		});

		// CHANGED: Add item on single-click selection from autocomplete (same as Item Set dialog)
		suggestionList.addListSelectionListener(e ->
		{
			if (e.getValueIsAdjusting()) return;

			ItemSuggestion selected = suggestionList.getSelectedValue();
			if (selected != null)
			{
				try
				{
					int qty = Integer.parseInt(qtyField.getText().trim());
					if (qty <= 0) qty = 1;

					// Check for duplicates
					for (int i = 0; i < requestItemsModel.size(); i++)
					{
						if (requestItemsModel.get(i).itemId == selected.getItemId())
						{
							JOptionPane.showMessageDialog(lookingForDialog,
								"Item already in request. Remove it first to change quantity.",
								"Duplicate Item",
								JOptionPane.WARNING_MESSAGE);
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

					// Update total
					long total = 0;
					for (int i = 0; i < requestItemsModel.size(); i++)
					{
						LookingForItem it = requestItemsModel.get(i);
						total += (long) it.value * it.quantity;
					}
					totalValueDisplay.setText("Total Value: " + QuantityFormatter.quantityToStackSize(total) + " GP");

					// Clear search field
					itemSearchField.setText("");
					qtyField.setText("1");
					suggestionModel.clear();
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(lookingForDialog,
						"Invalid quantity",
						"Error",
						JOptionPane.ERROR_MESSAGE);
				}
			}
		});

		// Remove item when double-clicked in request list
		requestItemsList.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() == 2)
				{
					int idx = requestItemsList.getSelectedIndex();
					if (idx >= 0)
					{
						requestItemsModel.remove(idx);

						// Update total
						long total = 0;
						for (int i = 0; i < requestItemsModel.size(); i++)
						{
							LookingForItem it = requestItemsModel.get(i);
							total += (long) it.value * it.quantity;
						}
						totalValueDisplay.setText("Total Value: " + QuantityFormatter.quantityToStackSize(total) + " GP");
					}
				}
			}
		});

		// Post button action
		final String finalCurrentPlayer = currentPlayer;
		postButton.addActionListener(e ->
		{
			if (requestItemsModel.isEmpty())
			{
				JOptionPane.showMessageDialog(lookingForDialog,
					"Please add at least one item to your request.",
					"No Items",
					JOptionPane.WARNING_MESSAGE);
				return;
			}

			try
			{
				int duration = Integer.parseInt(durationField.getText().trim());
				String title = titleField.getText().trim();
				String notes = notesField.getText().trim();

				// Build items list
				java.util.List<LookingForItem> items = new java.util.ArrayList<>();
				for (int i = 0; i < requestItemsModel.size(); i++)
				{
					items.add(requestItemsModel.get(i));
				}

				// Create display name - use title if provided, otherwise first item
				String displayName = title.isEmpty()
					? (items.size() == 1 ? items.get(0).itemName : items.get(0).itemName + " + " + (items.size() - 1) + " more")
					: title;

				// Serialize items for storage
				StringBuilder itemsStr = new StringBuilder();
				for (int i = 0; i < items.size(); i++)
				{
					if (i > 0) itemsStr.append(",");
					LookingForItem item = items.get(i);
					itemsStr.append(item.itemId).append(":").append(item.itemName.replace(":", "").replace(",", ""))
						.append(":").append(item.quantity).append(":").append(item.value);
				}

				// Store the looking for request
				String groupId = activeGroup.getId();
				String requestId = String.valueOf(System.currentTimeMillis());
				String requestData = String.format("%s|%s|%d|%d|%s|%s",
					finalCurrentPlayer, displayName, items.size(), duration, notes, itemsStr.toString());

				log.info("Saving Looking For request - groupId: '{}', requestId: '{}', items: {}",
					groupId, requestId, items.size());

				saveLookingForRequest(groupId, requestId, requestData);

				// Notify group members
				long totalValue = items.stream().mapToLong(it -> it.value * it.quantity).sum();
				final String chatMessage = String.format("[Lending Tracker] %s is looking for: %s (%d items, %s GP) for %d days",
					finalCurrentPlayer, displayName, items.size(), QuantityFormatter.quantityToStackSize(totalValue), duration);

				if (plugin.getClientThread() != null)
				{
					plugin.getClientThread().invokeLater(() ->
					{
						try
						{
							plugin.getClient().addChatMessage(
								net.runelite.api.ChatMessageType.GAMEMESSAGE,
								"",
								chatMessage,
								null
							);
						}
						catch (Exception ex)
						{
							log.warn("Failed to add chat message: {}", ex.getMessage());
						}
					});
				}

				JOptionPane.showMessageDialog(lookingForDialog,
					"Your request has been posted!\n\n" +
						"Items: " + items.size() + "\n" +
						"Total Value: " + QuantityFormatter.quantityToStackSize(totalValue) + " GP\n" +
						"Duration: " + duration + " days\n\n" +
						"Group members will be notified.",
					"Request Posted",
					JOptionPane.INFORMATION_MESSAGE);

				lookingForDialog.dispose();
				refresh();
			}
			catch (NumberFormatException ex)
			{
				JOptionPane.showMessageDialog(lookingForDialog,
					"Invalid duration",
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		});

		cancelButton.addActionListener(e -> lookingForDialog.dispose());

		lookingForDialog.pack();
		lookingForDialog.setLocationRelativeTo(this);
		lookingForDialog.setVisible(true);
	}

	/**
	 * LEGACY: Original simple dialog - kept for backwards compatibility but unused
	 */
	private void showLookingForDialogLegacy()
	{
		// Get active group
		net.runelite.client.plugins.lendingtracker.model.LendingGroup activeGroup = groupConfigStore.getActiveGroup();
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

		// Create dialog
		JPanel dialogPanel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.fill = GridBagConstraints.HORIZONTAL;

		// Item Name
		gbc.gridx = 0;
		gbc.gridy = 0;
		dialogPanel.add(new JLabel("Item Name:"), gbc);

		gbc.gridx = 1;
		JTextField itemNameField = new JTextField(20);
		dialogPanel.add(itemNameField, gbc);

		// Quantity needed
		gbc.gridx = 0;
		gbc.gridy = 1;
		dialogPanel.add(new JLabel("Quantity:"), gbc);

		gbc.gridx = 1;
		JTextField quantityField = new JTextField("1", 10);
		dialogPanel.add(quantityField, gbc);

		// Duration needed
		gbc.gridx = 0;
		gbc.gridy = 2;
		dialogPanel.add(new JLabel("Duration (days):"), gbc);

		gbc.gridx = 1;
		JTextField durationField = new JTextField("7", 10);
		dialogPanel.add(durationField, gbc);

		// Notes
		gbc.gridx = 0;
		gbc.gridy = 3;
		dialogPanel.add(new JLabel("Notes:"), gbc);

		gbc.gridx = 1;
		JTextField notesField = new JTextField(20);
		notesField.setToolTipText("Optional note to lenders");
		dialogPanel.add(notesField, gbc);

		int result = JOptionPane.showConfirmDialog(
			this,
			dialogPanel,
			"Looking For Item",
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE
		);

		if (result == JOptionPane.OK_OPTION)
		{
			try
			{
				String itemName = itemNameField.getText().trim();
				int quantity = Integer.parseInt(quantityField.getText().trim());
				int duration = Integer.parseInt(durationField.getText().trim());
				String notes = notesField.getText().trim();

				if (itemName.isEmpty())
				{
					throw new IllegalArgumentException("Item name cannot be empty");
				}

				// Store the looking for request with proper ID tracking
				String groupId = activeGroup.getId();
				String requestId = String.valueOf(System.currentTimeMillis());
				String requestData = String.format("%s|%s|%d|%d|%s|",
					currentPlayer, itemName, quantity, duration, notes);

				log.info("Saving Looking For request - groupId: '{}', requestId: '{}', data: '{}'",
					groupId, requestId, requestData);

				saveLookingForRequest(groupId, requestId, requestData);

				// FIXED: Verify cache was updated
				log.info("After save - cache contains groupId '{}': {}, cache size for group: {}",
					groupId, lookingForCache.containsKey(groupId),
					lookingForCache.containsKey(groupId) ? lookingForCache.get(groupId).size() : 0);

				log.info("Posted looking for request: {} wants {} x{} for {} days",
					currentPlayer, itemName, quantity, duration);

				// FIXED: Notify group members via chat - must use clientThread.invokeLater
				// because addChatMessage must be called on client thread, not EDT
				final String chatMessage = String.format("[Lending Tracker] %s is looking for: %s (x%d) for %d days",
					currentPlayer, itemName, quantity, duration);
				if (plugin.getClientThread() != null)
				{
					plugin.getClientThread().invokeLater(() ->
					{
						try
						{
							plugin.getClient().addChatMessage(
								net.runelite.api.ChatMessageType.GAMEMESSAGE,
								"",
								chatMessage,
								null
							);
						}
						catch (Exception e)
						{
							log.warn("Failed to add chat message: {}", e.getMessage());
						}
					});
				}

				JOptionPane.showMessageDialog(
					this,
					"Your request has been posted!\n\nGroup members will be notified.",
					"Request Posted",
					JOptionPane.INFORMATION_MESSAGE
				);

				refresh();
			}
			catch (NumberFormatException e)
			{
				JOptionPane.showMessageDialog(
					this,
					"Please enter valid numbers for quantity and duration.",
					"Invalid Input",
					JOptionPane.ERROR_MESSAGE
				);
			}
		}
	}

	/**
	 * ADDED: Load "Looking For" requests from config for a group
	 * FIXED: Now uses in-memory cache as primary source for immediate display
	 * FIXED: Cache is checked first and always trusted if it exists (containsKey check)
	 */
	private List<LookingForRequest> getLookingForRequests(String groupId)
	{
		if (groupId == null || groupId.isEmpty())
		{
			return new java.util.ArrayList<>();
		}

		// FIXED: Check if cache has this group (even empty list means we've initialized it)
		// This ensures immediate display after saving - cache is the source of truth once populated
		if (lookingForCache.containsKey(groupId))
		{
			List<LookingForRequest> cached = lookingForCache.get(groupId);
			log.debug("getLookingForRequests: returning {} cached requests for group {}",
				cached != null ? cached.size() : 0, groupId);
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

			// FIXED: Always cache the result (even if empty) so containsKey works next time
			lookingForCache.put(groupId, new java.util.ArrayList<>(requests));
			log.debug("getLookingForRequests: loaded {} requests from config for group {}", requests.size(), groupId);
		}
		catch (Exception e)
		{
			log.error("Failed to load looking for requests", e);
		}

		return requests;
	}

	/**
	 * ADDED: Save a "Looking For" request with ID tracking
	 * FIXED: Now also updates in-memory cache for immediate display
	 */
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

		// FIXED: Also update in-memory cache for immediate display
		LookingForRequest newRequest = LookingForRequest.parse(requestId, requestData);
		if (newRequest != null)
		{
			List<LookingForRequest> cached = lookingForCache.computeIfAbsent(groupId, k -> new java.util.ArrayList<>());
			cached.add(newRequest);
			log.debug("Added looking for request to cache: {} in group {}", requestId, groupId);
		}
	}

	/**
	 * ADDED: Remove a "Looking For" request
	 * FIXED: Now also removes from in-memory cache for immediate display update
	 */
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

		// FIXED: Also remove from in-memory cache for immediate display update
		List<LookingForRequest> cached = lookingForCache.get(groupId);
		if (cached != null)
		{
			cached.removeIf(r -> r.id != null && r.id.equals(requestId));
			log.debug("Removed looking for request from cache: {} in group {}", requestId, groupId);
		}

		log.info("Removed looking for request: {} from group {}", requestId, groupId);
	}

	/**
	 * ADDED: Data class for Looking For requests
	 * UPDATED: Now supports multiple items like item sets
	 */
	private static class LookingForRequest
	{
		String id;
		String requesterName;
		String itemName;        // Primary item name (for display) or request title
		int quantity;           // Quantity of primary item or total items count
		int durationDays;
		String notes;
		long postedTime;
		// ADDED: Support for multiple items
		java.util.List<LookingForItem> items = new java.util.ArrayList<>();

		static LookingForRequest parse(String id, String data)
		{
			try
			{
				String[] parts = data.split("\\|", 6);
				if (parts.length < 4) return null;

				LookingForRequest request = new LookingForRequest();
				request.id = id;
				request.requesterName = parts[0];
				request.itemName = parts[1];
				request.quantity = Integer.parseInt(parts[2]);
				request.durationDays = Integer.parseInt(parts[3]);
				request.notes = parts.length > 4 ? parts[4] : "";

				// ADDED: Parse items list if present (format: itemId:name:qty,itemId:name:qty,...)
				if (parts.length > 5 && !parts[5].isEmpty())
				{
					String[] itemParts = parts[5].split(",");
					for (String itemPart : itemParts)
					{
						String[] itemData = itemPart.split(":");
						if (itemData.length >= 3)
						{
							try
							{
								LookingForItem item = new LookingForItem();
								item.itemId = Integer.parseInt(itemData[0]);
								item.itemName = itemData[1];
								item.quantity = Integer.parseInt(itemData[2]);
								item.value = itemData.length > 3 ? Long.parseLong(itemData[3]) : 0;
								request.items.add(item);
							}
							catch (NumberFormatException ignored) {}
						}
					}
				}

				// Try to parse ID as timestamp for posted time
				try
				{
					request.postedTime = Long.parseLong(id);
				}
				catch (NumberFormatException e)
				{
					request.postedTime = System.currentTimeMillis();
				}

				return request;
			}
			catch (Exception e)
			{
				return null;
			}
		}

		String getPostedTimeFormatted()
		{
			if (postedTime == 0) return "Unknown";

			long timeDiff = System.currentTimeMillis() - postedTime;
			if (timeDiff < 60000) return "Just now";
			if (timeDiff < 3600000) return (timeDiff / 60000) + "m ago";
			if (timeDiff < 86400000) return (timeDiff / 3600000) + "h ago";
			return (timeDiff / 86400000) + "d ago";
		}

		// ADDED: Get total value of all items
		long getTotalValue()
		{
			if (items.isEmpty()) return 0;
			return items.stream().mapToLong(i -> i.value * i.quantity).sum();
		}

		// ADDED: Get item count
		int getItemCount()
		{
			return items.isEmpty() ? 1 : items.size();
		}

		// ADDED: Check if this is a multi-item request
		boolean isMultiItem()
		{
			return items.size() > 1;
		}

		// ADDED: Serialize items for storage
		String serializeItems()
		{
			if (items.isEmpty()) return "";
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < items.size(); i++)
			{
				if (i > 0) sb.append(",");
				LookingForItem item = items.get(i);
				sb.append(item.itemId).append(":").append(item.itemName.replace(":", ""))
					.append(":").append(item.quantity).append(":").append(item.value);
			}
			return sb.toString();
		}
	}

	/**
	 * ADDED: Individual item in a Looking For request
	 */
	private static class LookingForItem
	{
		int itemId;
		String itemName;
		int quantity;
		long value;
	}

	/**
	 * ADDED: Card to display a "Looking For" request
	 */
	private class LookingForCard extends JPanel
	{
		private final LookingForRequest request;
		private final JPanel detailsPanel;
		private final JPanel rightPanel;
		private final boolean showOnlineStatus;
		private final OnlineStatusService onlineStatusService;

		public LookingForCard(LookingForRequest request, boolean showOnlineStatus, OnlineStatusService onlineStatusService)
		{
			this.request = request;
			this.showOnlineStatus = showOnlineStatus;
			this.onlineStatusService = onlineStatusService;

			// Check if requester is online
			boolean isRequesterOnline = true;
			if (showOnlineStatus && onlineStatusService != null && request.requesterName != null)
			{
				isRequesterOnline = onlineStatusService.isPlayerOnline(request.requesterName);
			}

			setLayout(new BorderLayout(5, 0));
			// Use a blue-tinted background to differentiate from offerings
			Color bgColor = isRequesterOnline
				? new Color(45, 50, 60) // Blue-gray for online
				: new Color(35, 38, 45); // Darker for offline
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(8, 8, 8, 8)
			));

			setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
			setPreferredSize(new Dimension(200, 60));

			// ADDED: Tooltip hint - show items if multi-item request
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
			itemLabel.setForeground(isRequesterOnline ? Color.WHITE : Color.GRAY);

			// Requester name with online status
			String requesterText = "By: " + request.requesterName;
			if (showOnlineStatus && onlineStatusService != null)
			{
				String statusDot = isRequesterOnline ? "\u25CF " : "\u25CB ";
				requesterText = statusDot + requesterText;
			}
			JLabel requesterLabel = new JLabel(requesterText);
			requesterLabel.setFont(FontManager.getRunescapeSmallFont());
			// FIXED: Use green for online (same as MarketplaceCard) instead of blue
			if (showOnlineStatus)
			{
				if (isRequesterOnline)
				{
					requesterLabel.setForeground(new Color(100, 255, 100)); // Bright green for online
				}
				else
				{
					// Check if recently online
					long lastSeen = onlineStatusService != null ? onlineStatusService.getLastSeenTime(request.requesterName) : 0;
					long timeDiff = lastSeen > 0 ? Instant.now().toEpochMilli() - lastSeen : Long.MAX_VALUE;
					if (timeDiff < 300000) // Less than 5 minutes
					{
						requesterLabel.setForeground(new Color(255, 255, 100)); // Yellow - recently online
					}
					else
					{
						requesterLabel.setForeground(new Color(150, 150, 150)); // Gray - offline
					}
				}
			}
			else
			{
				requesterLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}

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

			// Right-click menu
			JPopupMenu popupMenu = createLookingForPopupMenu();
			setComponentPopupMenu(popupMenu);

			// Store background for hover
			final Color baseBgColor = bgColor;

			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					Color hoverColor = new Color(55, 60, 70);
					setBackground(hoverColor);
					detailsPanel.setBackground(hoverColor);
					rightPanel.setBackground(hoverColor);
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					setBackground(baseBgColor);
					detailsPanel.setBackground(baseBgColor);
					rightPanel.setBackground(baseBgColor);
				}

				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						popupMenu.show(e.getComponent(), e.getX(), e.getY());
					}
				}

				@Override
				public void mouseReleased(java.awt.event.MouseEvent e)
				{
					if (e.isPopupTrigger())
					{
						popupMenu.show(e.getComponent(), e.getX(), e.getY());
					}
				}
			});
		}

		private JPopupMenu createLookingForPopupMenu()
		{
			JPopupMenu menu = new JPopupMenu();
			String currentPlayer = getCurrentPlayerName();
			boolean isOwner = request.requesterName != null && request.requesterName.equalsIgnoreCase(currentPlayer);

			// ADDED: Check if requester is online
			boolean isRequesterOnline = true;
			if (showOnlineStatus && onlineStatusService != null && request.requesterName != null)
			{
				isRequesterOnline = onlineStatusService.isPlayerOnline(request.requesterName);
			}

			// ADDED: View items option for multi-item requests
			if (request.isMultiItem())
			{
				JMenuItem viewItemsItem = new JMenuItem("View Requested Items");
				viewItemsItem.addActionListener(e -> showLookingForDetails(request));
				menu.add(viewItemsItem);
				menu.addSeparator();
			}

			if (isOwner)
			{
				// ADDED: Edit request option for owner
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
						String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
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
				// Others can offer to lend - check if requester is online
				if (isRequesterOnline)
				{
					JMenuItem offerItem = new JMenuItem("I Have This Item");
					offerItem.addActionListener(e -> {
						showOfferToLendDialog(currentPlayer, request);
					});
					menu.add(offerItem);
				}
				else
				{
					// ADDED: Requester is offline - show disabled option with explanation
					JMenuItem offlineItem = new JMenuItem("I Have This Item (User Offline)");
					offlineItem.setEnabled(false);
					offlineItem.setToolTipText("Cannot send offer - " + request.requesterName + " is offline");
					menu.add(offlineItem);

					// Add "last seen" info if available
					if (onlineStatusService != null)
					{
						String lastSeen = onlineStatusService.getLastSeenFormatted(request.requesterName);
						JMenuItem lastSeenItem = new JMenuItem("Last seen: " + lastSeen);
						lastSeenItem.setEnabled(false);
						menu.add(lastSeenItem);
					}
				}
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

		// ADDED: Edit looking for request dialog for owners
		private void showEditLookingForDialog(LookingForRequest request)
		{
			JPanel editPanel = new JPanel(new GridBagLayout());
			GridBagConstraints gbc = new GridBagConstraints();
			gbc.insets = new Insets(4, 4, 4, 4);
			gbc.anchor = GridBagConstraints.WEST;
			gbc.fill = GridBagConstraints.HORIZONTAL;

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
					String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
					if (groupId != null)
					{
						String requestData = String.format("%s|%s|%d|%d|%s|",
							request.requesterName, newItemName, newQty, newDuration, newNotes);
						saveLookingForRequest(groupId, request.id, requestData);
						refresh();
						log.info("Updated looking for request: {} -> {}", request.id, newItemName);
					}
				}
				catch (NumberFormatException ex)
				{
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Please enter valid numbers.", "Error", JOptionPane.ERROR_MESSAGE);
				}
			}
		}

		/**
		 * ADDED: Show dialog for offering to lend an item to someone who's looking for it
		 * UPDATED: Added terms checkboxes, days/hours toggle, and group name display
		 */
		private void showOfferToLendDialog(String lender, LookingForRequest request)
		{
			// Get current group name for display
			String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
			String groupName = groupId != null ? groupConfigStore.getGroupNameById(groupId) : null;
			String groupDisplay = groupName != null ? " (" + groupName + ")" : "";

			// Create dialog panel
			JPanel dialogPanel = new JPanel();
			dialogPanel.setLayout(new BoxLayout(dialogPanel, BoxLayout.Y_AXIS));
			dialogPanel.setPreferredSize(new Dimension(380, 480));

			// Header with group name
			JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
			headerPanel.setBackground(dialogPanel.getBackground());
			headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel headerLabel = new JLabel("Offer to Lend: " + request.itemName);
			headerLabel.setFont(FontManager.getRunescapeBoldFont());
			headerPanel.add(headerLabel);
			if (groupName != null)
			{
				JLabel groupLabel = new JLabel(" (" + groupName + ")");
				groupLabel.setFont(FontManager.getRunescapeSmallFont());
				groupLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE); // Yellow for group name
				headerPanel.add(groupLabel);
			}
			dialogPanel.add(headerPanel);
			dialogPanel.add(Box.createVerticalStrut(5));

			JLabel requesterLabel = new JLabel("To: " + request.requesterName);
			requesterLabel.setFont(FontManager.getRunescapeSmallFont());
			requesterLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(requesterLabel);
			dialogPanel.add(Box.createVerticalStrut(5));

			JLabel requestedQtyLabel = new JLabel("Requested: x" + request.quantity + " for " + request.durationDays + " days");
			requestedQtyLabel.setFont(FontManager.getRunescapeSmallFont());
			requestedQtyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			requestedQtyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(requestedQtyLabel);
			dialogPanel.add(Box.createVerticalStrut(15));

			// Quantity to offer
			JLabel qtyLabel = new JLabel("Quantity you can lend:");
			qtyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(qtyLabel);

			JTextField qtyField = new JTextField(String.valueOf(request.quantity));
			qtyField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
			qtyField.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(qtyField);
			dialogPanel.add(Box.createVerticalStrut(10));

			// ADDED: Duration field with days/hours toggle
			JLabel durationLabel = new JLabel("How long can you lend this item?");
			durationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(durationLabel);

			JPanel durationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
			durationPanel.setBackground(dialogPanel.getBackground());
			durationPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			durationPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

			JTextField durationField = new JTextField(String.valueOf(request.durationDays));
			durationField.setPreferredSize(new Dimension(60, 25));
			durationPanel.add(durationField);

			// Days/Hours toggle
			JRadioButton daysRadio = new JRadioButton("Days", true);
			JRadioButton hoursRadio = new JRadioButton("Hours", false);
			ButtonGroup durationGroup = new ButtonGroup();
			durationGroup.add(daysRadio);
			durationGroup.add(hoursRadio);
			durationPanel.add(daysRadio);
			durationPanel.add(hoursRadio);

			dialogPanel.add(durationPanel);
			dialogPanel.add(Box.createVerticalStrut(15));

			// CHANGED: Simplified terms - single checkbox with standard terms applied
			JCheckBox applyStandardTerms = new JCheckBox("Apply standard lending terms");
			applyStandardTerms.setSelected(true);
			applyStandardTerms.setFont(FontManager.getRunescapeBoldFont());
			applyStandardTerms.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(applyStandardTerms);

			JLabel termsDetails = new JLabel("<html><font size='2' color='#b0b0b0'>" +
				"\u2022 No Wilderness \u2022 No trading to others \u2022 No selling/alching<br>" +
				"\u2022 Return on time \u2022 No risky activities" +
				"</font></html>");
			termsDetails.setAlignmentX(Component.LEFT_ALIGNMENT);
			termsDetails.setBorder(new EmptyBorder(2, 25, 0, 0));
			dialogPanel.add(termsDetails);

			dialogPanel.add(Box.createVerticalStrut(10));

			// Optional message
			JLabel msgLabel = new JLabel("Message to requester (optional):");
			msgLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(msgLabel);

			JTextField msgField = new JTextField();
			msgField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
			msgField.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(msgField);
			dialogPanel.add(Box.createVerticalStrut(10));

			// Info about what happens
			JLabel infoLabel = new JLabel("<html><font color='#AAAAAA'>The requester will be notified that you have " +
				"this item available. They must accept your terms to borrow.</font></html>");
			infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			dialogPanel.add(infoLabel);

			// Show dialog
			int result = JOptionPane.showConfirmDialog(
				DashboardPanel.this,
				dialogPanel,
				"Offer Item" + groupDisplay,
				JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.PLAIN_MESSAGE
			);

			if (result == JOptionPane.OK_OPTION)
			{
				// Validate quantity
				int offerQty;
				try
				{
					offerQty = Integer.parseInt(qtyField.getText().trim());
					if (offerQty <= 0)
					{
						throw new NumberFormatException("Invalid quantity");
					}
				}
				catch (NumberFormatException e)
				{
					JOptionPane.showMessageDialog(
						DashboardPanel.this,
						"Please enter a valid quantity.",
						"Invalid Quantity",
						JOptionPane.ERROR_MESSAGE
					);
					return;
				}

				// Validate duration
				int duration;
				try
				{
					duration = Integer.parseInt(durationField.getText().trim());
					if (duration <= 0)
					{
						throw new NumberFormatException("Invalid duration");
					}
					// Convert hours to fractional days if hours selected
					if (hoursRadio.isSelected())
					{
						// Store as hours for now, plugin will handle conversion
						// Use negative to indicate hours (convention)
						duration = -duration; // Negative = hours
					}
				}
				catch (NumberFormatException e)
				{
					JOptionPane.showMessageDialog(
						DashboardPanel.this,
						"Please enter a valid duration.",
						"Invalid Duration",
						JOptionPane.ERROR_MESSAGE
					);
					return;
				}

				// CHANGED: Build terms string from single checkbox
				String terms;
				if (applyStandardTerms.isSelected())
				{
					terms = "NO_WILDERNESS,NO_TRADING,NO_SELLING,RETURN_ON_TIME,NO_RISKY,";
				}
				else
				{
					terms = "";
				}

				String message = msgField.getText().trim();

				// Send the offer notification
				log.info("{} is offering to lend {} x{} to {} for {} {} (terms: {})",
					lender, request.itemName, offerQty, request.requesterName,
					Math.abs(duration), duration < 0 ? "hours" : "days", terms);

				// Duration display for confirmation and chat message
				String durationDisplay = duration < 0 ? Math.abs(duration) + " hours" : duration + " days";

				// Store offer and notify (pass actual duration and display string)
				int durationDays = duration < 0 ? Math.max(1, Math.abs(duration) / 24) : duration; // Convert hours to days for storage
				plugin.sendLendOffer(lender, request.requesterName, request.itemName, offerQty, durationDays, message, durationDisplay);

				JOptionPane.showMessageDialog(
					DashboardPanel.this,
					"Your offer has been sent to " + request.requesterName + "!" + groupDisplay + "\n\n" +
						"Item: " + request.itemName + " x" + offerQty + "\n" +
						"Duration: " + durationDisplay + "\n" +
						"They will receive a notification in-game.",
					"Offer Sent",
					JOptionPane.INFORMATION_MESSAGE
				);
			}
		}
	}
}
