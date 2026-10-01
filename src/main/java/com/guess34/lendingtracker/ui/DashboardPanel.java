package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingRequest;
import com.guess34.lendingtracker.services.ArmourSets;
import com.guess34.lendingtracker.services.DataService;
import com.guess34.lendingtracker.services.GroupService;
import com.guess34.lendingtracker.services.ItemCategories;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.AsyncBufferedImage;
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
	private final ItemCategories itemCategories;
	private final ArmourSets armourSets;

	// Search and filter live OUTSIDE the list, which is torn down and rebuilt on
	// every refresh - inside it, each keystroke would rebuild the field being typed in.
	private final IconTextField searchField;
	private final JComboBox<String> filterBox;
	// The filter's plain name, without the "(12)" the dropdown shows. Kept apart
	// so the counts can be rewritten on every redraw without losing the choice.
	private String filterKey = FILTER_ALL;
	private boolean rewritingFilters;
	private final Timer searchDebounce;

	// With a big marketplace every owner starts folded so the list is a directory
	// of people, not hundreds of rows. Smaller ones start open.
	private static final int FOLD_OWNERS_ABOVE = 15;
	private static final long END_GAME_VALUE = 10_000_000L;
	private static final String FILTER_ALL = "All items";
	private static final String FILTER_MINE = "My listings";
	private static final String FILTER_SETS = "Sets";
	private static final String FILTER_END_GAME = "End game (10m+)";
	private final java.util.Set<String> ownerToggles = new java.util.HashSet<>();
	private final java.util.Set<String> expandedSets = new java.util.HashSet<>();
	// Looking For posts asking for several items fold away the same way, and start
	// closed so a few long wishlists can't swamp the board.
	private final java.util.Set<String> expandedWants = new java.util.HashSet<>();

	// Legacy Looking For posts were kept only in local config; each group is moved
	// onto the synced requests once per session.
	private final java.util.Set<String> migratedLookingFor = new java.util.HashSet<>();

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
		this.itemCategories = plugin.getItemCategories();
		this.armourSets = plugin.getArmourSets();

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

		searchField = new IconTextField();
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(200, 28));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.setToolTipText("Search items, sets or players");
		searchDebounce = new Timer(250, e -> refresh());
		searchDebounce.setRepeats(false);
		searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			public void insertUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { searchDebounce.restart(); }
		});

		java.util.List<String> filters = new java.util.ArrayList<>(java.util.Arrays.asList(
			FILTER_ALL, FILTER_MINE, FILTER_SETS, FILTER_END_GAME));
		for (ItemCategories.Category c : ItemCategories.Category.values())
		{
			filters.add(c.getLabel());
		}
		filterBox = new JComboBox<>(filters.toArray(new String[0]));
		filterBox.setFont(FontManager.getRunescapeSmallFont());
		filterBox.setToolTipText("Show only one kind of gear - the number is how many are listed now");
		// It sat under the search box in the same flat grey and was being missed
		// entirely. An accent edge and the orange the section headers already use
		// make it read as something you can press.
		filterBox.setForeground(new Color(0xFF, 0xC0, 0x60));
		filterBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		filterBox.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE),
			new EmptyBorder(3, 6, 3, 3)));
		filterBox.setPreferredSize(new Dimension(200, 26));
		// Some look-and-feels ignore the colours above on the closed box, so the
		// renderer sets them itself.
		filterBox.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focused)
			{
				Component c = super.getListCellRendererComponent(list, value, index, selected, focused);
				c.setFont(FontManager.getRunescapeSmallFont());
				c.setForeground(selected ? Color.WHITE : new Color(0xFF, 0xC0, 0x60));
				c.setBackground(selected ? ColorScheme.BRAND_ORANGE.darker() : ColorScheme.DARKER_GRAY_COLOR);
				return c;
			}
		});
		filterBox.addActionListener(e ->
		{
			if (rewritingFilters)
			{
				return;
			}
			filterKey = withoutCount((String) filterBox.getSelectedItem());
			refresh();
		});

		JPanel filterBar = new JPanel(new BorderLayout(0, 4));
		filterBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBar.setBorder(new EmptyBorder(6, 6, 2, 6));
		filterBar.add(searchField, BorderLayout.NORTH);
		filterBar.add(filterBox, BorderLayout.SOUTH);

		JPanel north = new JPanel(new BorderLayout());
		north.add(summaryHeader, BorderLayout.NORTH);
		north.add(filterBar, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);

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

			// Armour listed piece by piece becomes a real item set, so the whole
			// group sees it grouped - not just whoever happens to be looking at it.
			// Costs nothing unless our own loose armour has actually changed.
			// The live name, not the config fallback: this writes and publishes, and
			// the fallback exists for read-only display when logged out.
			String meNow = plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null
				? plugin.getClient().getLocalPlayer().getName() : null;
			announceNewSets(armourSets.groupNewKits(groupId, meNow, this::refresh));

			// Get marketplace offerings from DataService
			List<LendingEntry> marketplaceItems = new java.util.ArrayList<>();
			if (groupId != null && !groupId.isEmpty())
			{
				List<LendingEntry> items = dataService.getAvailable(groupId);
				if (items != null) marketplaceItems.addAll(items);
			}

			// Get active loans (items currently lent out)
			List<LendingEntry> allActiveLoans = dataService.getActiveEntries();
			if (allActiveLoans == null)
			{
				allActiveLoans = java.util.Collections.emptyList();
			}
			// A loan belongs to the group it was made in. Loans from OTHER groups are
			// still shown, but separately and only to the two parties - a personal
			// reminder, not this group's business. Nobody else can see them either way:
			// the published snapshot is filtered by groupId.
			final String activeGroupId = groupId;
			final String selfName = getCurrentPlayerName();
			List<LendingEntry> activeLoans = allActiveLoans.stream()
				.filter(e -> activeGroupId != null && activeGroupId.equals(e.getGroupId()))
				.collect(java.util.stream.Collectors.toList());
			List<LendingEntry> otherGroupLoans = allActiveLoans.stream()
				.filter(e -> activeGroupId == null || !activeGroupId.equals(e.getGroupId()))
				.filter(e -> selfName != null
					&& (selfName.equalsIgnoreCase(e.getLender()) || selfName.equalsIgnoreCase(e.getBorrower())))
				.collect(java.util.stream.Collectors.toList());

			long overdueCount = activeLoans.stream()
				.filter(LendingEntry::isOverdue)
				.count();

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
				.map(this::asAvailableNow)
				.filter(java.util.Objects::nonNull)
				.collect(java.util.stream.Collectors.toList());

			// Counted from what is actually on the board, so the headline can't
			// claim gear that is on somebody's back or outside the group.
			totalValueLabel.setText("Available: "
				+ QuantityFormatter.quantityToStackSize(displayItems.stream()
					.mapToLong(LendingEntry::getValue).sum()) + " GP");
			activeLoansLabel.setText("Marketplace: " + displayItems.size()
				+ " | Loans: " + activeLoans.size());

			// Categories come from the client thread; ask for anything not seen yet
			// and draw again once it is known.
			itemCategories.prime(displayItems.stream().map(LendingEntry::getItemId)
				.collect(java.util.stream.Collectors.toList()), this::refresh);

			List<ListingUnit> marketUnits = buildUnits(displayItems);
			showFilterCounts(displayItems, marketUnits, getCurrentPlayerName());
			String query = searchQuery();

			// Direct requests (borrow requests / lend offers) involving me
			String me = getCurrentPlayerName();
			List<LendingRequest> incomingRequests = new java.util.ArrayList<>();
			List<LendingRequest> outgoingRequests = new java.util.ArrayList<>();
			if (groupId != null && !groupId.isEmpty() && me != null && !me.equals("Not logged in"))
			{
				incomingRequests.addAll(dataService.getPendingRequestsFor(groupId, me));
				// Staff-review removals visible to eligible uninvolved owners/co-owners
				incomingRequests.addAll(dataService.getPendingStaffRemovalsFor(groupId, me,
					groupService.getGroup(groupId)));
				// Looking For posts are requests too, but they have their own section.
				outgoingRequests.addAll(dataService.getRequestsFrom(groupId, me).stream()
					.filter(r -> r.isPending() && !r.isLookingFor())
					.collect(java.util.stream.Collectors.toList()));
			}

			// Looking For, matched against what is listed right now
			List<LookingForRequest> lookingForRequests = getLookingForRequests(groupId);
			java.util.Set<Integer> myBases = new java.util.HashSet<>();
			for (LendingEntry item : displayItems)
			{
				if (me.equalsIgnoreCase(item.getLender())) myBases.add(ItemVariationMapping.map(item.getItemId()));
			}
			int wantsMine = 0;
			for (LookingForRequest r : lookingForRequests)
			{
				r.listedBy = whoListed(r, displayItems);
				r.wantsMine = !me.equalsIgnoreCase(r.requesterName) && r.wantsAny(myBases, displayItems, me);
				if (r.wantsMine) wantsMine++;
			}
			if (!query.isEmpty())
			{
				lookingForRequests.removeIf(r -> !r.matches(query));
			}

			long myOverdue = java.util.stream.Stream.concat(activeLoans.stream(), otherGroupLoans.stream())
				.filter(LendingEntry::isOverdue)
				.filter(e -> me.equalsIgnoreCase(e.getLender()) || me.equalsIgnoreCase(e.getBorrower()))
				.count();

			// What needs this player's attention, before anything to browse.
			if (!incomingRequests.isEmpty() || myOverdue > 0 || wantsMine > 0)
			{
				loanListPanel.add(createNeedsYouStrip(incomingRequests.size(), myOverdue, wantsMine));
			}

			if (!incomingRequests.isEmpty() || !outgoingRequests.isEmpty())
			{
				boolean requestsCollapsed = collapsedSections.contains("requests");
				JPanel requestsHeader = createCollapsibleHeader(
					"Requests (" + (incomingRequests.size() + outgoingRequests.size()) + ")",
					new Color(0x64, 0xC8, 0x64), "requests", requestsCollapsed);
				loanListPanel.add(requestsHeader);

				if (!requestsCollapsed)
				{
					for (LendingRequest request : incomingRequests)
					{
						loanListPanel.add(new RequestCard(request, true));
					}
					for (LendingRequest request : outgoingRequests)
					{
						loanListPanel.add(new RequestCard(request, false));
					}
				}
			}

			if (!displayItems.isEmpty())
			{
				addMarketplaceSection(displayItems, marketUnits, me, query);
			}

			if (!lookingForRequests.isEmpty())
			{
				// Collapsible section header with item count
				boolean lookingForCollapsed = collapsedSections.contains("lookingfor") && query.isEmpty();
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
						if (request.isMultiItem() && expandedWants.contains(request.id))
						{
							for (LookingForItem item : request.items)
							{
								loanListPanel.add(new WantedItemRow(item));
							}
						}
					}
				}
			}

			// Loans living in another group - shown to the lender/borrower only, so an
			// outstanding item is not forgotten just because a different group is
			// selected. Labelled with its group so it cannot be mistaken for this one.
			if (!otherGroupLoans.isEmpty())
			{
				boolean otherCollapsed = collapsedSections.contains("otherloans");
				JPanel otherHeader = createCollapsibleHeader(
					"Your Loans in Other Groups (" + otherGroupLoans.size() + ")",
					Color.GRAY, "otherloans", otherCollapsed);
				loanListPanel.add(otherHeader);
				if (!otherCollapsed)
				{
					for (LendingEntry loan : otherGroupLoans)
					{
						String ownerGroup = groupService.getGroupNameById(loan.getGroupId());
						JLabel tag = new JLabel(ownerGroup != null ? ownerGroup : "another group");
						tag.setFont(FontManager.getRunescapeSmallFont());
						tag.setForeground(Color.GRAY);
						tag.setBorder(new EmptyBorder(2, 6, 0, 0));
						loanListPanel.add(tag);
						loanListPanel.add(new LoanCard(loan, true));
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
			if (displayItems.isEmpty() && lookingForRequests.isEmpty() && activeLoans.isEmpty()
				&& incomingRequests.isEmpty() && outgoingRequests.isEmpty() && otherGroupLoans.isEmpty())
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

	// ---------------------------------------------------------------- marketplace

	/** "Melee (12)" -> "Melee". */
	private static String withoutCount(String label)
	{
		return label == null ? FILTER_ALL : label.replaceAll(" [(]\\d+[)]$", "");
	}

	/**
	 * Put a live count on every filter, so you can see there is no tank gear
	 * listed without picking it first. Rebuilt on each redraw; the choice itself
	 * lives in filterKey, not in the visible label.
	 */
	private void showFilterCounts(List<LendingEntry> items, List<ListingUnit> units, String me)
	{
		int inSets = 0;
		for (ListingUnit u : units)
		{
			if (u.bundled()) inSets += u.pieces.size();
		}
		java.util.List<String> labels = new java.util.ArrayList<>();
		labels.add(FILTER_ALL + " (" + items.size() + ")");
		labels.add(FILTER_MINE + " (" + items.stream().filter(e -> e.getLender() != null
			&& e.getLender().equalsIgnoreCase(me)).count() + ")");
		labels.add(FILTER_SETS + " (" + inSets + ")");
		labels.add(FILTER_END_GAME + " (" + items.stream().filter(DashboardPanel::isEndGame).count() + ")");
		for (ItemCategories.Category c : ItemCategories.Category.values())
		{
			labels.add(c.getLabel() + " (" + items.stream().filter(e -> hasCategory(e, c)).count() + ")");
		}

		String wanted = null;
		for (String label : labels)
		{
			if (withoutCount(label).equals(filterKey))
			{
				wanted = label;
			}
		}
		rewritingFilters = true;
		try
		{
			filterBox.removeAllItems();
			for (String label : labels)
			{
				filterBox.addItem(label);
			}
			filterBox.setSelectedItem(wanted != null ? wanted : labels.get(0));
		}
		finally
		{
			rewritingFilters = false;
		}
	}

	/**
	 * Gear its owner is wearing right now cannot be handed over, so it comes off
	 * the board until they take it off, and a part-worn stack shows only what is
	 * free. Returns null for a row that should not be shown at all.
	 *
	 * Your own listings always stay put and are marked instead - a row vanishing
	 * from your own list is indistinguishable from losing it, and every way of
	 * managing a listing hangs off that row.
	 *
	 * Only honoured while the owner is online. What they published means "worn
	 * right now", and once they log off nothing can ever update it, so it goes
	 * back to showing in full. Every branch here errs towards showing.
	 */
	private LendingEntry asAvailableNow(LendingEntry item)
	{
		if (item.wornQuantity() <= 0)
		{
			return item;
		}
		String me = getCurrentPlayerName();
		if (me != null && me.equalsIgnoreCase(item.getLender()))
		{
			return item;
		}
		if (!groupService.getOnlineMembers().containsKey(item.getLender().toLowerCase()))
		{
			return item;
		}
		int free = item.availableQuantity();
		if (free <= 0)
		{
			return null;
		}
		// Value is the whole stack's, so it has to come down with the count or the
		// row contradicts itself.
		LendingEntry shown = new LendingEntry(item);
		shown.setQuantity(free);
		if (item.getQuantity() > 0)
		{
			shown.setValue(item.getValue() * free / item.getQuantity());
		}
		shown.setWornQty(null);
		return shown;
	}

	/**
	 * Say what was just grouped. Listings folding into a set on their own would
	 * otherwise look like they had gone missing.
	 */
	private void announceNewSets(java.util.List<String> made)
	{
		if (made == null || made.isEmpty())
		{
			return;
		}
		// Open the marketplace so the new set is actually on screen. It starts
		// folded every session, so grouping something inside it otherwise looks
		// exactly like grouping nothing at all.
		collapsedSections.remove("marketplace");
		plugin.getClientThread().invokeLater(() ->
		{
			if (plugin.getClient() == null || plugin.getClient().getLocalPlayer() == null)
			{
				return;
			}
			// The first run after an update can find several at once, so say them
			// together rather than filling the chatbox.
			String what = made.size() == 1 ? "'" + made.get(0) + "'"
				: made.size() + " sets (" + String.join(", ", made.subList(0, Math.min(3, made.size())))
					+ (made.size() > 3 ? ", ..." : "") + ")";
			plugin.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>" + ("Lending Tracker: grouped your listings into " + what
					+ ". Right-click a set to break it up."), "");
		});
	}

	/**
	 * An item icon with a line struck through it and the colour drained out: this
	 * one is on your back right now, so the group cannot see it. Reads at a glance
	 * where a line of text does not.
	 */
	private static class WornIcon extends JLabel
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// Knock the icon back so the struck-through one reads as unavailable
			// next to the ones that are.
			g2.setColor(new Color(20, 22, 26, 130));
			g2.fillRect(0, 0, getWidth(), getHeight());
			g2.setStroke(new BasicStroke(2f));
			g2.setColor(new Color(0xE0, 0xA8, 0x48));
			int pad = 5;
			g2.drawLine(pad, getHeight() - pad, getWidth() - pad, pad);
			g2.dispose();
		}
	}

	/** True when this listing is ours and is on our back this moment. */
	private boolean wornByMe(LendingEntry e)
	{
		if (e == null || e.wornQuantity() <= 0)
		{
			return false;
		}
		String myName = getCurrentPlayerName();
		return myName != null && myName.equalsIgnoreCase(e.getLender());
	}

	/**
	 * Tick which pieces of a set to ask for. Opening the set and right-clicking one
	 * row already worked, but nobody finds it, and wanting three pieces out of five
	 * is an ordinary thing to want rather than an edge case.
	 */
	private void showPieceRequestDialog(String owner, List<LendingEntry> pieces, String label)
	{
		if (pieces == null || pieces.isEmpty())
		{
			return;
		}
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		java.util.Map<JCheckBox, LendingEntry> boxes = new java.util.LinkedHashMap<>();
		for (LendingEntry p : pieces)
		{
			JCheckBox box = new JCheckBox(p.getItem()
				+ (p.getQuantity() > 1 ? " x" + p.getQuantity() : "")
				+ "   " + QuantityFormatter.quantityToStackSize(p.getValue()), true);
			boxes.put(box, p);
			list.add(box);
		}

		JPanel body = new JPanel(new BorderLayout(0, 6));
		body.add(new JLabel("Which pieces of " + label + " do you want?"), BorderLayout.NORTH);
		body.add(list, BorderLayout.CENTER);
		if (JOptionPane.showConfirmDialog(this, body, "Request Items",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}

		List<LendingEntry> chosen = new java.util.ArrayList<>();
		for (java.util.Map.Entry<JCheckBox, LendingEntry> b : boxes.entrySet())
		{
			if (b.getKey().isSelected())
			{
				chosen.add(b.getValue());
			}
		}
		if (chosen.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Tick at least one item.", "Request Items",
				JOptionPane.WARNING_MESSAGE);
			return;
		}
		showBorrowRequestDialog(owner, chosen,
			chosen.size() == pieces.size() ? label : chosen.size() + " from " + label);
	}

	private String searchQuery()
	{
		String q = searchField.getText();
		return q == null ? "" : q.trim().toLowerCase();
	}

	/**
	 * One thing to show in the marketplace: a loose listing, or every listed piece
	 * of one owner's set.
	 */
	private final class ListingUnit
	{
		final String owner;
		final String setId;
		final String setName;
		// Set when these pieces were read as a kit just for drawing, because their
		// owner's client has not grouped them into a real set. Nothing about the
		// listings themselves changes - only how they are shown here.
		final String autoKey;
		final List<LendingEntry> pieces = new java.util.ArrayList<>();

		ListingUnit(String owner, String setId, String setName)
		{
			this(owner, setId, setName, null);
		}

		ListingUnit(String owner, String setId, String setName, String autoKey)
		{
			this.owner = owner;
			this.setId = setId;
			this.setName = setName;
			this.autoKey = autoKey;
		}

		long value()
		{
			return pieces.stream().mapToLong(LendingEntry::getValue).sum();
		}

		/** Drawn as one bundle rather than a single listing. */
		boolean bundled()
		{
			return setId != null || autoKey != null;
		}

		String key()
		{
			return autoKey != null ? autoKey : owner.toLowerCase() + "#" + setId;
		}

		boolean matches(String query, String filter, String me)
		{
			if (!query.isEmpty())
			{
				boolean hit = owner.toLowerCase().contains(query)
					|| (setName != null && setName.toLowerCase().contains(query))
					|| pieces.stream().anyMatch(p -> p.getItem() != null && p.getItem().toLowerCase().contains(query));
				if (!hit) return false;
			}
			if (filter == null || FILTER_ALL.equals(filter)) return true;
			if (FILTER_MINE.equals(filter)) return owner.equalsIgnoreCase(me);
			if (FILTER_SETS.equals(filter)) return bundled();
			if (FILTER_END_GAME.equals(filter)) return pieces.stream().anyMatch(DashboardPanel::isEndGame);
			ItemCategories.Category wanted = null;
			for (ItemCategories.Category c : ItemCategories.Category.values())
			{
				if (c.getLabel().equals(filter)) wanted = c;
			}
			final ItemCategories.Category w = wanted;
			return w != null && pieces.stream().anyMatch(p -> hasCategory(p, w));
		}
	}

	private static boolean isEndGame(LendingEntry e)
	{
		long each = e.getQuantity() > 1 ? e.getValue() / e.getQuantity() : e.getValue();
		return each >= END_GAME_VALUE;
	}

	private boolean hasCategory(LendingEntry e, ItemCategories.Category c)
	{
		java.util.Set<ItemCategories.Category> cats = itemCategories.get(e.getItemId());
		return cats != null && cats.contains(c);
	}

	/** "Melee · Slash · DPS" - what the item is for, worked out from its stats. */
	private String categoryText(LendingEntry e)
	{
		java.util.List<String> parts = new java.util.ArrayList<>();
		java.util.Set<ItemCategories.Category> cats = itemCategories.get(e.getItemId());
		if (cats != null)
		{
			for (ItemCategories.Category c : cats) parts.add(c.getLabel());
		}
		if (isEndGame(e)) parts.add("End game");
		return String.join(" · ", parts);
	}

	/** Group listings into units: each owner's set becomes one unit, loose pieces their own. */
	private List<ListingUnit> buildUnits(List<LendingEntry> items)
	{
		java.util.Map<String, ListingUnit> units = new java.util.LinkedHashMap<>();
		List<LendingEntry> loose = new java.util.ArrayList<>();
		for (LendingEntry e : items)
		{
			String owner = e.getLender();
			if (e.isInSet())
			{
				String key = owner.toLowerCase() + "#" + e.getSetId();
				units.computeIfAbsent(key, k -> new ListingUnit(owner, e.getSetId(), e.getSetName())).pieces.add(e);
			}
			else
			{
				loose.add(e);
			}
		}

		// Whatever is still loose and belongs to somebody else is read for kits too,
		// so the board looks the same to everyone looking at it rather than only to
		// the people whose own client has grouped their gear. This is drawing only -
		// nothing is written and nothing is published.
		java.util.Map<LendingEntry, ListingUnit> drawn = new java.util.IdentityHashMap<>();
		java.util.Map<String, List<LendingEntry>> looseByOwner = new java.util.LinkedHashMap<>();
		String me = getCurrentPlayerName();
		for (LendingEntry e : loose)
		{
			// Our own gear is governed by real sets; anything of ours still loose is
			// loose because we said so.
			if (e.getLender() == null || e.getLender().equalsIgnoreCase(me))
			{
				continue;
			}
			looseByOwner.computeIfAbsent(e.getLender(), k -> new java.util.ArrayList<>()).add(e);
		}
		for (java.util.Map.Entry<String, List<LendingEntry>> owned : looseByOwner.entrySet())
		{
			for (ArmourSets.Kit kit : armourSets.displayKits(owned.getValue()))
			{
				ListingUnit u = new ListingUnit(owned.getKey(), null, kit.getName(),
					owned.getKey().toLowerCase() + "#auto:" + kit.getFamily());
				u.pieces.addAll(kit.getPieces());
				for (LendingEntry e : kit.getPieces())
				{
					drawn.put(e, u);
				}
			}
		}

		int singles = 0;
		for (LendingEntry e : loose)
		{
			ListingUnit kit = drawn.get(e);
			if (kit != null)
			{
				units.putIfAbsent(kit.key(), kit);
				continue;
			}
			ListingUnit u = new ListingUnit(e.getLender(), null, null);
			u.pieces.add(e);
			units.put("loose#" + (singles++), u);
		}
		return new java.util.ArrayList<>(units.values());
	}

	private void addMarketplaceSection(List<LendingEntry> displayItems, List<ListingUnit> built,
		String me, String query)
	{
		String filter = filterKey;
		boolean filtering = !query.isEmpty() || (filter != null && !FILTER_ALL.equals(filter));

		// A copy: the filtering below removes from it, and the counts were taken
		// from the original.
		List<ListingUnit> units = new java.util.ArrayList<>(built);
		if (filtering)
		{
			units.removeIf(u -> !u.matches(query, filter, me));
		}
		int shown = units.stream().mapToInt(u -> u.pieces.size()).sum();

		// A search or filter opens the section - hiding the results of a search
		// behind a folded header would look like there were none.
		boolean marketCollapsed = collapsedSections.contains("marketplace") && !filtering;
		loanListPanel.add(createCollapsibleHeader(
			"Available for Lending (" + (filtering ? shown + " of " + displayItems.size() : displayItems.size()) + ")",
			ColorScheme.BRAND_ORANGE, "marketplace", marketCollapsed));
		if (marketCollapsed) return;

		if (units.isEmpty())
		{
			JLabel none = new JLabel("Nothing matches.");
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(Color.GRAY);
			none.setBorder(new EmptyBorder(4, 12, 6, 0));
			loanListPanel.add(none);
			return;
		}

		// Bundles first, then single items, each lot by value. A lone item wedged
		// between two set rows leaves a gap in the list for no reason.
		java.util.Comparator<ListingUnit> byValue =
			java.util.Comparator.comparing((ListingUnit u) -> u.bundled() ? 0 : 1)
				.thenComparing(java.util.Comparator.comparingLong(ListingUnit::value).reversed());

		if (filtering)
		{
			// Results: one flat list, best first, each row saying whose it is.
			// Capped - a filter as broad as "Melee" across a whole clan would
			// otherwise build hundreds of rows at once.
			units.sort(byValue);
			int cap = 60;
			for (int i = 0; i < units.size() && i < cap; i++) addUnit(units.get(i), true);
			if (units.size() > cap)
			{
				JLabel more = new JLabel("...and " + (units.size() - cap) + " more - search to narrow it down");
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setForeground(Color.GRAY);
				more.setBorder(new EmptyBorder(4, 12, 6, 0));
				loanListPanel.add(more);
			}
			return;
		}

		// Browsing: a directory of owners, you first, then everyone A-Z.
		java.util.Map<String, List<ListingUnit>> byOwner = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (ListingUnit u : units)
		{
			byOwner.computeIfAbsent(u.owner, k -> new java.util.ArrayList<>()).add(u);
		}
		List<String> owners = new java.util.ArrayList<>(byOwner.keySet());
		owners.sort((a, b) -> a.equalsIgnoreCase(me) ? -1 : b.equalsIgnoreCase(me) ? 1 : a.compareToIgnoreCase(b));
		boolean big = displayItems.size() > FOLD_OWNERS_ABOVE;
		for (String owner : owners)
		{
			List<ListingUnit> list = byOwner.get(owner);
			list.sort(byValue);
			int pieces = list.stream().mapToInt(u -> u.pieces.size()).sum();
			long value = list.stream().mapToLong(ListingUnit::value).sum();
			String key = owner.toLowerCase();
			// The toggle set means "flipped from the default", whichever way the
			// default currently points.
			boolean open = big == ownerToggles.contains(key);
			loanListPanel.add(createOwnerHeader(owner, owner.equalsIgnoreCase(me), pieces, value, open, key));
			if (open)
			{
				for (ListingUnit u : list) addUnit(u, false);
			}
		}
	}

	private void addUnit(ListingUnit u, boolean showOwner)
	{
		if (!u.bundled())
		{
			loanListPanel.add(new MarketplaceCard(u.pieces.get(0), showOwner, false));
			return;
		}
		loanListPanel.add(new SetCard(u, showOwner));
		if (expandedSets.contains(u.key()))
		{
			for (LendingEntry piece : u.pieces)
			{
				loanListPanel.add(new MarketplaceCard(piece, false, true));
			}
		}
	}

	private JPanel createOwnerHeader(String owner, boolean isMe, int count, long value, boolean open, String key)
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(new EmptyBorder(5, 12, 3, 10));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		JLabel name = new JLabel((open ? "▼ " : "▶ ") + owner + (isMe ? " (you)" : "") + "  " + count);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(isMe ? new Color(0x8C, 0xE0, 0x8C) : Color.WHITE);
		header.add(name, BorderLayout.WEST);

		JLabel worth = new JLabel(QuantityFormatter.quantityToStackSize(value));
		worth.setFont(FontManager.getRunescapeSmallFont());
		worth.setForeground(Color.YELLOW);
		header.add(worth, BorderLayout.EAST);

		header.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				if (!ownerToggles.remove(key)) ownerToggles.add(key);
				refresh();
			}
		});
		return header;
	}

	/** Who has something from this post listed right now (not the poster). */
	private List<String> whoListed(LookingForRequest r, List<LendingEntry> listings)
	{
		java.util.Set<String> owners = new java.util.LinkedHashSet<>();
		for (LendingEntry e : listings)
		{
			if (e.getLender() == null || e.getLender().equalsIgnoreCase(r.requesterName)) continue;
			if (r.wants(e)) owners.add(e.getLender());
		}
		return new java.util.ArrayList<>(owners);
	}

	private JPanel createNeedsYouStrip(int requests, long overdue, int wanted)
	{
		JPanel strip = new JPanel();
		strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
		strip.setBackground(new Color(58, 46, 34));
		strip.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE),
			new EmptyBorder(6, 8, 6, 8)));

		JLabel title = new JLabel("Needs you");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		strip.add(title);

		if (requests > 0)
		{
			addNeedsYouLine(strip, requests + (requests == 1 ? " request is" : " requests are") + " waiting for you",
				"requests");
		}
		if (overdue > 0)
		{
			addNeedsYouLine(strip, overdue + (overdue == 1 ? " loan is" : " loans are") + " overdue", "loans", "otherloans");
		}
		if (wanted > 0)
		{
			addNeedsYouLine(strip, wanted + (wanted == 1 ? " member wants" : " members want") + " gear you listed",
				"lookingfor");
		}
		strip.setMaximumSize(new Dimension(Integer.MAX_VALUE, strip.getPreferredSize().height));
		return strip;
	}

	private void addNeedsYouLine(JPanel strip, String text, String... sections)
	{
		JLabel line = new JLabel("› " + text);
		line.setFont(FontManager.getRunescapeSmallFont());
		line.setForeground(Color.WHITE);
		line.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		line.setToolTipText("Show them");
		line.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				for (String s : sections) collapsedSections.remove(s);
				refresh();
			}
		});
		strip.add(line);
	}

	/**
	 * Ask to borrow one listing or a whole set. A set goes out as one request per
	 * piece: the lender answers each on its own, and a piece that is already out
	 * does not hold up the rest.
	 */
	private void showBorrowRequestDialog(String lender, List<LendingEntry> pieces, String title)
	{
		String borrower = getCurrentPlayerName();
		if (borrower == null || borrower.equals("Not logged in"))
		{
			JOptionPane.showMessageDialog(this, "You must be logged in to request items.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (pieces.isEmpty())
		{
			return;
		}
		String groupId = groupService.getCurrentGroupIdUnchecked();
		String groupName = groupId != null ? groupService.getGroupNameById(groupId) : null;

		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = createDefaultGbc();
		gbc.gridy = 0; gbc.gridwidth = 2;
		StringBuilder head = new StringBuilder("<html><b>Borrow: ").append(escapeHtml(title)).append("</b> from ")
			.append(escapeHtml(lender));
		if (groupName != null) head.append(" <font color='#FFA500'>(").append(escapeHtml(groupName)).append(")</font>");
		if (pieces.size() > 1)
		{
			head.append("<br><font color='gray'>").append(pieces.size()).append(" pieces:");
			for (LendingEntry p : pieces) head.append("<br>• ").append(escapeHtml(p.getItem()));
			head.append("</font>");
		}
		panel.add(new JLabel(head.append("</html>").toString()), gbc);

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
			"<font size='2' color='#b0b0b0'>• No Wilderness • No trading • Return on time</font></html>");
		panel.add(agreeTermsCheck, gbc);

		gbc.gridy = 4;
		panel.add(new JLabel("<html><font color='orange'>Breaking terms may result in group removal!</font></html>"), gbc);

		int result = JOptionPane.showConfirmDialog(this, panel,
			"Borrow Request", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result != JOptionPane.OK_OPTION)
		{
			return;
		}
		if (!agreeTermsCheck.isSelected())
		{
			JOptionPane.showMessageDialog(this,
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
			int sent = 0;
			for (LendingEntry p : pieces)
			{
				if (plugin.sendBorrowRequest(borrower, lender, p.getItem(), p.getItemId(), p.getQuantity(), durationDays))
				{
					sent++;
				}
			}
			if (sent > 0)
			{
				String deliveryNote = plugin.isRelaySyncConnected()
					? "They'll see it in their Lending Tracker panel."
					: "Cloud Sync is offline — it will be delivered when they next sync.";
				String what = pieces.size() > 1 ? sent + " requests (one per piece)" : "Borrow request";
				JOptionPane.showMessageDialog(this,
					what + " sent to " + lender + "!\nDuration: " + durationDisplay + "\n" + deliveryNote,
					"Request Sent", JOptionPane.INFORMATION_MESSAGE);
			}
			else
			{
				JOptionPane.showMessageDialog(this,
					"Could not send the request — no active group.",
					"Request Not Sent", JOptionPane.ERROR_MESSAGE);
			}
		}
		catch (NumberFormatException e)
		{
			JOptionPane.showMessageDialog(this,
				"Please enter a valid duration.", "Invalid Duration", JOptionPane.ERROR_MESSAGE);
		}
	}

	private static String escapeHtml(String s)
	{
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * Put listings into a set: pick a new or existing set of yours, name it, and
	 * tick the pieces. Unticking a piece of an existing set takes it out.
	 */
	private void showSetDialog(LendingEntry seed)
	{
		String groupId = groupService.getCurrentGroupIdUnchecked();
		String owner = seed.getLender();
		if (groupId == null || owner == null) return;

		List<LendingEntry> mine = new java.util.ArrayList<>();
		for (LendingEntry e : dataService.getOfferingsByOwner(groupId, owner))
		{
			if (e.getBorrower() == null || e.getBorrower().isEmpty()) mine.add(e);
		}
		java.util.Map<String, String> sets = new java.util.LinkedHashMap<>();
		for (LendingEntry e : mine)
		{
			if (e.isInSet()) sets.putIfAbsent(e.getSetId(), e.getSetName() != null ? e.getSetName() : "Set");
		}
		List<String> setIds = new java.util.ArrayList<>(sets.keySet());

		JComboBox<String> which = new JComboBox<>();
		which.addItem("New set");
		for (String id : setIds) which.addItem(sets.get(id));
		if (seed.isInSet() && setIds.contains(seed.getSetId()))
		{
			which.setSelectedIndex(setIds.indexOf(seed.getSetId()) + 1);
		}

		JTextField nameField = new JTextField(16);

		JPanel checks = new JPanel();
		checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
		java.util.Map<JCheckBox, LendingEntry> boxes = new java.util.LinkedHashMap<>();
		for (LendingEntry e : mine)
		{
			JCheckBox box = new JCheckBox(e.getItem() + (e.getQuantity() > 1 ? " x" + e.getQuantity() : "")
				+ (e.isInSet() && !e.getSetId().equals(seed.getSetId()) ? "  (in " + e.getSetName() + ")" : ""));
			boxes.put(box, e);
			checks.add(box);
		}
		Runnable showChoice = () ->
		{
			int i = which.getSelectedIndex();
			String chosen = i > 0 ? setIds.get(i - 1) : null;
			nameField.setText(chosen != null ? sets.get(chosen) : "");
			for (java.util.Map.Entry<JCheckBox, LendingEntry> b : boxes.entrySet())
			{
				LendingEntry e = b.getValue();
				b.getKey().setSelected(e.getItemId() == seed.getItemId()
					|| (chosen != null && chosen.equals(e.getSetId())));
			}

			// Whatever is ticked, the pieces of it that form a real kit are locked in:
			// they group themselves, and the way one leaves is by being sold or lent.
			// Only the odds and ends can be unticked.
			List<LendingEntry> ticked = new java.util.ArrayList<>();
			for (java.util.Map.Entry<JCheckBox, LendingEntry> b : boxes.entrySet())
			{
				if (b.getKey().isSelected()) ticked.add(b.getValue());
			}
			java.util.Set<Integer> locked = armourSets.kitCore(ticked);
			for (java.util.Map.Entry<JCheckBox, LendingEntry> b : boxes.entrySet())
			{
				boolean fixed = locked.contains(b.getValue().getItemId());
				b.getKey().setEnabled(!fixed);
				b.getKey().setToolTipText(fixed
					? "Part of the kit - it leaves the set by being sold or lent" : null);
			}
		};
		which.addActionListener(ev -> showChoice.run());
		showChoice.run();

		JPanel p = new JPanel(new BorderLayout(0, 6));
		JPanel top = new JPanel(new GridLayout(2, 2, 4, 4));
		top.add(new JLabel("Set:"));
		top.add(which);
		top.add(new JLabel("Name:"));
		top.add(nameField);
		p.add(top, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(checks);
		scroll.setPreferredSize(new Dimension(260, Math.min(260, 26 * boxes.size() + 10)));
		p.add(scroll, BorderLayout.CENTER);
		p.add(new JLabel("<html><font color='gray'>A piece that is lent out leaves the set on the<br>"
			+ "marketplace and rejoins it when it comes home.</font></html>"), BorderLayout.SOUTH);

		if (JOptionPane.showConfirmDialog(this, p, "Item Set", JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
		{
			return;
		}
		String name = nameField.getText().trim();
		if (name.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Give the set a name.", "Item Set", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (name.length() > 30) name = name.substring(0, 30);
		int i = which.getSelectedIndex();
		String setId = i > 0 ? setIds.get(i - 1) : java.util.UUID.randomUUID().toString();

		// Real sets are the plugin's to make, whoever is doing the adding. If what
		// was ticked contains a kit, the whole lot joins THAT kit's set instead of
		// becoming a second claim on the same gear - two sets fighting over one
		// piece would settle differently on different machines.
		List<LendingEntry> picked = new java.util.ArrayList<>();
		for (java.util.Map.Entry<JCheckBox, LendingEntry> b : boxes.entrySet())
		{
			if (b.getKey().isSelected()) picked.add(b.getValue());
		}
		ArmourSets.Kit kitSet = armourSets.kitSetFor(groupId, owner, picked);
		if (kitSet != null)
		{
			if (picked.size() == kitSet.getPieces().size())
			{
				JOptionPane.showMessageDialog(this,
					"Those already group themselves as a set - no need to make one.\n"
						+ "Sets are for adding something to a kit, or mixing pieces that\n"
						+ "don't go together on their own.",
					"Item Set", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			setId = ArmourSets.setIdOf(kitSet);
			name = kitSet.getName();
		}

		List<Integer> chosen = new java.util.ArrayList<>();
		List<Integer> dropped = new java.util.ArrayList<>();
		List<LendingEntry> droppedRows = new java.util.ArrayList<>();
		for (java.util.Map.Entry<JCheckBox, LendingEntry> b : boxes.entrySet())
		{
			LendingEntry e = b.getValue();
			if (b.getKey().isSelected())
			{
				chosen.add(e.getItemId());
			}
			else if (setId.equals(e.getSetId())) { dropped.add(e.getItemId()); droppedRows.add(e); }
		}
		if (chosen.isEmpty())
		{
			JOptionPane.showMessageDialog(this, "Tick at least one item.", "Item Set", JOptionPane.WARNING_MESSAGE);
			return;
		}
		dataService.applySetToListings(groupId, owner, chosen, setId, name);
		if (!dropped.isEmpty())
		{
			dataService.applySetToListings(groupId, owner, dropped, null, null);
		}
		expandedSets.add(owner.toLowerCase() + "#" + setId);
		refresh();
	}

	/** One owner's set: name, value and the pieces' icons. Click to open it. */
	private class SetCard extends JPanel
	{
		SetCard(ListingUnit unit, boolean showOwner)
		{
			boolean drawnOnly = unit.autoKey != null;
			// Coloured by WHOSE it is, not by how it came about: yours in the warm
			// colour, everyone else's in the cool one. Whether a set was worked out
			// or built by hand is our business, and making people read it off a
			// colour only taught them that two identical things looked different.
			boolean yours = getCurrentPlayerName() != null
				&& unit.owner.equalsIgnoreCase(getCurrentPlayerName());
			setLayout(new BorderLayout(4, 2));
			Color bg = yours ? new Color(48, 44, 38) : new Color(40, 44, 52);
			setBackground(bg);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 2, 1, 0,
					yours ? ColorScheme.BRAND_ORANGE : new Color(0x6E, 0x92, 0xB8)),
				new EmptyBorder(5, 8, 5, 6)));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			boolean open = expandedSets.contains(unit.key());
			String name = unit.setName != null && !unit.setName.isEmpty() ? unit.setName : "Set";

			// Gear of ours that is on our back is off the board for everyone else, and
			// the row has to say so. It stays here rather than vanishing because this is
			// where the owner manages it, and a listing that disappeared from your own
			// list would read as one you had lost.
			String myName = getCurrentPlayerName();
			boolean mineHere = myName != null && unit.owner.equalsIgnoreCase(myName);
			int wornPieces = 0;
			for (LendingEntry piece : unit.pieces)
			{
				if (piece.wornQuantity() > 0) wornPieces++;
			}
			String wornTag = !mineHere || wornPieces == 0 ? ""
				: wornPieces == unit.pieces.size() ? "  - worn"
					: "  - " + wornPieces + " worn";

			JLabel title = new JLabel((open ? "▼ " : "▶ ") + name
				+ "  (" + unit.pieces.size() + ")" + wornTag);
			title.setFont(FontManager.getRunescapeSmallFont());
			title.setForeground(!wornTag.isEmpty() ? new Color(0xE0, 0xA8, 0x48)
				: yours ? new Color(0xFF, 0xC0, 0x60) : new Color(0xA8, 0xC8, 0xE8));

			JLabel worth = new JLabel(QuantityFormatter.quantityToStackSize(unit.value()));
			worth.setFont(FontManager.getRunescapeSmallFont());
			worth.setForeground(Color.YELLOW);

			JPanel top = new JPanel(new BorderLayout());
			top.setOpaque(false);
			top.add(title, BorderLayout.WEST);
			top.add(worth, BorderLayout.EAST);
			add(top, BorderLayout.NORTH);

			JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
			icons.setOpaque(false);
			int max = 4;
			for (int i = 0; i < unit.pieces.size() && i < max; i++)
			{
				LendingEntry p = unit.pieces.get(i);
				JLabel icon = wornByMe(p) ? new WornIcon() : new JLabel();
				icon.setPreferredSize(new Dimension(36, 32));
				AsyncBufferedImage img = itemManager.getImage(p.getItemId(), p.getQuantity(), p.getQuantity() > 1);
				if (img != null) img.addTo(icon);
				icons.add(icon);
			}
			if (unit.pieces.size() > max)
			{
				JLabel more = new JLabel("+" + (unit.pieces.size() - max));
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setForeground(Color.LIGHT_GRAY);
				icons.add(more);
			}
			add(icons, BorderLayout.CENTER);

			if (showOwner)
			{
				JLabel by = new JLabel("By: " + unit.owner);
				by.setFont(FontManager.getRunescapeSmallFont());
				by.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				add(by, BorderLayout.SOUTH);
			}

			StringBuilder tip = new StringBuilder("<html><b>").append(escapeHtml(name)).append("</b> by ")
				.append(escapeHtml(unit.owner));
			for (LendingEntry p : unit.pieces)
			{
				tip.append("<br>• ").append(escapeHtml(p.getItem()))
					.append(" - ").append(QuantityFormatter.quantityToStackSize(p.getValue()));
			}
			if (drawnOnly)
			{
				tip.append("<br><i>Grouped from the names - each piece still lends on its own</i>");
			}
			setToolTipText(tip.append("<br><i>Click to open, right-click for options</i></html>").toString());

			int h = showOwner ? 76 : 62;
			setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
			setPreferredSize(new Dimension(200, h));

			String me = getCurrentPlayerName();
			boolean mineSet = unit.owner.equalsIgnoreCase(me);
			JPopupMenu menu = new JPopupMenu();
			if (drawnOnly)
			{
				JMenuItem all = new JMenuItem("Request whole set");
				all.addActionListener(e -> showBorrowRequestDialog(unit.owner, unit.pieces, name));
				menu.add(all);
				JMenuItem some = new JMenuItem("Request some items...");
				some.addActionListener(e -> showPieceRequestDialog(unit.owner, unit.pieces, name));
				menu.add(some);
			}
			else if (mineSet)
			{
				JMenuItem edit = new JMenuItem("Edit set...");
				edit.addActionListener(e -> showSetDialog(unit.pieces.get(0)));
				menu.add(edit);
				// A set that IS a kit cannot be dissolved - a piece leaves it by being
				// sold or lent, and the rest stay grouped. Only one the owner
				// assembled out of odds and ends - a Bandos chest with Justiciar legs
				// - comes apart wholesale. A kit with extras added to it keeps the kit
				// and lets the extras go, which the Edit set dialog handles.
				if (armourSets.kitCore(unit.pieces).isEmpty())
				{
					JMenuItem breakUp = new JMenuItem("Break up set");
					breakUp.addActionListener(e ->
					{
						if (JOptionPane.showConfirmDialog(DashboardPanel.this,
							"Break up '" + name + "'? The pieces stay listed on their own.",
							"Break Up Set", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
						{
							String groupId = groupService.getCurrentGroupIdUnchecked();
							if (groupId != null) dataService.breakSet(groupId, unit.owner, unit.setId);
							refresh();
						}
					});
					menu.add(breakUp);
				}
			}
			else
			{
				JMenuItem all = new JMenuItem("Request whole set");
				all.addActionListener(e -> showBorrowRequestDialog(unit.owner, unit.pieces, name));
				menu.add(all);
				JMenuItem some = new JMenuItem("Request some items...");
				some.addActionListener(e -> showPieceRequestDialog(unit.owner, unit.pieces, name));
				menu.add(some);
			}
			setComponentPopupMenu(menu);

			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					if (!SwingUtilities.isLeftMouseButton(e)) return;
					if (!expandedSets.remove(unit.key())) expandedSets.add(unit.key());
					refresh();
				}
			});
		}
	}

	private class MarketplaceCard extends JPanel
	{
		private final LendingEntry item;
		private final JPanel detailsPanel;
		private final JPanel rightPanel;

		public MarketplaceCard(LendingEntry item, boolean showOwner, boolean inSet)
		{
			this.item = item;

			setLayout(new BorderLayout(5, 0));
			Color bgColor = inSet ? new Color(40, 38, 35) : ColorScheme.DARKER_GRAY_COLOR;
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(3, inSet ? 18 : 6, 3, 6)
			));

			// Compact on purpose: with a whole clan listing gear, row height is what
			// decides whether the marketplace can be read at all.
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
			setPreferredSize(new Dimension(200, 42));

			// Left side: Item icon (fixed width), struck through when we are wearing it
			JLabel iconLabel = wornByMe(item) ? new WornIcon() : new JLabel();
			iconLabel.setPreferredSize(new Dimension(36, 32));
			try
			{
				AsyncBufferedImage itemImage = itemManager.getImage(item.getItemId(), item.getQuantity(), item.getQuantity() > 1);
				if (itemImage != null)
				{
					itemImage.addTo(iconLabel);
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
			String itemName = item.getItem() != null ? item.getItem() : "?";
			if (itemName.length() > 18) itemName = itemName.substring(0, 15) + "...";
			JLabel itemLabel = new JLabel(itemName + (item.getQuantity() > 1 ? " x" + item.getQuantity() : ""));
			itemLabel.setFont(FontManager.getRunescapeSmallFont());
			itemLabel.setForeground(Color.WHITE);

			// Under an owner's heading the owner is already said, so the second line
			// says what the item is for instead.
			String cats = categoryText(item);
			String sub = showOwner ? "By: " + item.getLender() : cats;
			// Only ever our own rows: what a peer publishes about their own gear is
			// used to hide the row, not to talk about it.
			String meNow = getCurrentPlayerName();
			boolean wornMine = item.wornQuantity() > 0
				&& meNow != null && meNow.equalsIgnoreCase(item.getLender());
			if (wornMine)
			{
				sub = item.availableQuantity() > 0
					? "Worn " + item.wornQuantity() + " of " + item.getQuantity()
					: "Worn - hidden";
			}
			JLabel subLabel = new JLabel(sub);
			subLabel.setFont(FontManager.getRunescapeSmallFont());
			subLabel.setForeground(wornMine ? new Color(0xE0, 0xA8, 0x48) : ColorScheme.LIGHT_GRAY_COLOR);

			detailsPanel.add(itemLabel);
			detailsPanel.add(subLabel);

			add(detailsPanel, BorderLayout.CENTER);

			// Right side: value
			rightPanel = new JPanel(new BorderLayout());
			rightPanel.setBackground(bgColor);

			JLabel valueLabel = new JLabel(QuantityFormatter.quantityToStackSize(item.getValue()));
			valueLabel.setFont(FontManager.getRunescapeSmallFont());
			valueLabel.setForeground(Color.YELLOW);
			rightPanel.add(valueLabel, BorderLayout.CENTER);

			add(rightPanel, BorderLayout.EAST);

			setToolTipText("<html><b>" + escapeHtml(item.getItem()) + "</b> by " + escapeHtml(item.getLender())
				+ (cats.isEmpty() ? "" : "<br>" + escapeHtml(cats))
				+ (item.isInSet() ? "<br>Part of set: " + escapeHtml(item.getSetName()) : "")
				+ (item.getNotes() != null && !item.getNotes().isEmpty() ? "<br>Note: " + escapeHtml(item.getNotes()) : "")
				+ "</html>");

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

					JMenuItem setItem = new JMenuItem(item.isInSet() ? "Edit set..." : "Add to a set...");
					setItem.addActionListener(e -> showSetDialog(item));
					menu.add(setItem);

					// A piece of a real kit is not yours to pull out one at a time: it
					// leaves by being sold or lent. Only the odds and ends added to a
					// set can be taken back out.
					boolean kitPiece = false;
					if (item.isInSet())
					{
						String gid2 = groupService.getCurrentGroupIdUnchecked();
						if (gid2 != null)
						{
							List<LendingEntry> sameSet = new java.util.ArrayList<>();
							for (LendingEntry o : dataService.getOfferingsByOwner(gid2, item.getLender()))
							{
								if (item.getSetId().equals(o.getSetId())) sameSet.add(o);
							}
							kitPiece = armourSets.kitCore(sameSet).contains(item.getItemId());
						}
					}
					if (item.isInSet() && !kitPiece)
					{
						JMenuItem outOfSet = new JMenuItem("Take out of set");
						outOfSet.addActionListener(e ->
						{
							String groupId = groupService.getCurrentGroupIdUnchecked();
							if (groupId != null)
							{
								dataService.applySetToListings(groupId, item.getLender(),
									java.util.Collections.singletonList(item.getItemId()), null, null);
								refresh();
							}
						});
						menu.add(outOfSet);
					}

					JMenuItem removeItem = new JMenuItem("Remove from Marketplace");
					removeItem.addActionListener(e -> removeFromMarketplace());
					menu.add(removeItem);
				}
			}
			else
			{
				JMenuItem borrowItem = new JMenuItem("Request to Borrow");
				borrowItem.addActionListener(e -> showBorrowRequestDialog(item.getLender(),
					java.util.Collections.singletonList(item), item.getItem()));
				menu.add(borrowItem);
				if (item.isInSet())
				{
					JMenuItem whole = new JMenuItem("Request whole set");
					whole.addActionListener(e ->
					{
						String groupId = groupService.getCurrentGroupIdUnchecked();
						List<LendingEntry> pieces = new java.util.ArrayList<>();
						for (LendingEntry o : dataService.getOfferingsByOwner(groupId, item.getLender()))
						{
							if (item.getSetId().equals(o.getSetId())) pieces.add(o);
						}
						showBorrowRequestDialog(item.getLender(), pieces, item.getSetName());
					});
					menu.add(whole);
				}
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
				// Taken down on purpose, so it should not rejoin its set if relisted.
				dataService.forgetSetMembership(groupId, item.getLender(), item.getItemId());
				// No reload: removeAvailable has already changed memory and saved it,
				// so re-reading config would only race the worn-state writer for
				// nothing. See DataService.ensureHydrated.
				refresh();
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
			this(loan, false);
		}

		/**
		 * readOnly suppresses the action menu. Loans shown from ANOTHER group are a
		 * reminder only - offering this group's actions on them would act on a loan
		 * that does not belong here, and expose its details through those menus.
		 */
		public LoanCard(LendingEntry loan, boolean readOnly)
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

			// Hover anywhere on the card shows the full deal: borrower, dates,
			// collateral, notes (tooltips don't inherit, so set on every component)
			LoanTooltip.apply(loan, this, iconLabel, detailsPanel, itemLabel,
				borrowerLabel, dueTimeLabel, valuePanel, valueLabel);

			if (!readOnly)
			{
				setComponentPopupMenu(buildLoanMenu());
			}
			addHoverEffect(this, ColorScheme.DARKER_GRAY_HOVER_COLOR, ColorScheme.DARKER_GRAY_COLOR, detailsPanel, valuePanel);
		}

		private JPopupMenu buildLoanMenu()
		{
			String me = getCurrentPlayerName();
			boolean iAmLender = me != null && me.equalsIgnoreCase(loan.getLender());
			boolean iAmBorrower = me != null && me.equalsIgnoreCase(loan.getBorrower());
			boolean noCollateral = !DataService.hasCollateral(loan);
			String groupId = groupService.getCurrentGroupIdUnchecked();

			JPopupMenu menu = new JPopupMenu();

			// The lender can forgive their OWN loan, but only when no collateral was
			// taken (a collateralised loan is a real exchange — that needs the
			// two-party or staff removal path, not a one-click drop).
			if (iAmLender && noCollateral)
			{
				JMenuItem forgive = new JMenuItem("Forgive loan (no collateral)");
				forgive.addActionListener(e ->
				{
					int confirm = JOptionPane.showConfirmDialog(DashboardPanel.this,
						"Forgive this loan of " + loan.getItem() + " to " + loan.getBorrower() + "?\n"
							+ "It will be removed from active tracking for the whole group\n"
							+ "and archived in your history as forgiven.",
						"Forgive Loan", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
					if (confirm == JOptionPane.YES_OPTION)
					{
						if (dataService.forgiveLoan(loan.getId(), me))
						{
							plugin.getDiscordWebhook().post(
								com.guess34.lendingtracker.services.DiscordWebhook.Event.FORGIVEN, loan, me);
							refresh();
						}
						else
						{
							JOptionPane.showMessageDialog(DashboardPanel.this,
								"Couldn't forgive this loan (it may have collateral or already be settled).",
								"Not Removed", JOptionPane.ERROR_MESSAGE);
						}
					}
				});
				menu.add(forgive);
			}

			// Any party to the loan can propose removal. Routing is decided by the
			// plugin: the counterparty approves when they're a group member (first
			// course of action, even if offline); staff review only when the
			// counterparty was never in the group, or as escalation after a decline.
			if ((iAmLender || iAmBorrower) && groupId != null)
			{
				if (dataService.hasPendingRemovalFor(groupId, loan.getId()))
				{
					JMenuItem pending = new JMenuItem("Removal already requested — awaiting approval");
					pending.setEnabled(false);
					menu.add(pending);
				}
				else
				{
					boolean canEscalate = dataService.hasDeclinedMutualRemovalFor(groupId, loan.getId());
					JMenuItem remove = new JMenuItem(canEscalate
						? "Escalate removal to staff…" : "Request loan removal…");
					final boolean escalate = canEscalate;
					remove.addActionListener(e -> proposeRemoval(loan, escalate));
					menu.add(remove);
				}
			}

			return menu.getComponentCount() > 0 ? menu : null;
		}

		private void proposeRemoval(LendingEntry loan, boolean escalate)
		{
			String reason = (String) JOptionPane.showInputDialog(DashboardPanel.this,
				"Why should this loan be removed?\n(e.g. \"Loan mode was on by accident — this was a gift\")",
				"Request Loan Removal", JOptionPane.QUESTION_MESSAGE, null, null, "");
			if (reason == null)
			{
				return; // cancelled
			}

			LendingTrackerPlugin.RemovalRoute route = plugin.requestLoanRemoval(loan, reason.trim(), escalate);
			switch (route)
			{
				case MUTUAL:
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Removal requested. The other party ("
							+ (getCurrentPlayerName().equalsIgnoreCase(loan.getLender())
								? loan.getBorrower() : loan.getLender())
							+ ") must approve it — they'll be notified, even if they're offline right now.",
						"Awaiting Approval", JOptionPane.INFORMATION_MESSAGE);
					break;
				case STAFF:
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Removal sent to staff review — an owner or co-owner who isn't part of\n"
							+ "this loan will look into it and approve or decline.",
						"Awaiting Staff Review", JOptionPane.INFORMATION_MESSAGE);
					break;
				case ALREADY_PENDING:
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"A removal request for this loan is already awaiting approval.",
						"Already Requested", JOptionPane.INFORMATION_MESSAGE);
					break;
				case NO_REVIEWER:
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Nobody in this group can review this removal — the only staff\n"
							+ "are involved in the loan themselves. Ask an owner to add a\n"
							+ "co-owner or admin who isn't part of it, then try again.",
						"No Reviewer Available", JOptionPane.WARNING_MESSAGE);
					break;
				default:
					JOptionPane.showMessageDialog(DashboardPanel.this,
						"Only the lender or borrower of a loan can request its removal.",
						"Not Allowed", JOptionPane.ERROR_MESSAGE);
			}
			refresh();
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
							long gePrice = itemManager.getItemPrice(canonId);
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
		private final long gePrice;

		public ItemSuggestion(int itemId, String name, long gePrice)
		{
			this.itemId = itemId;
			this.name = name;
			this.gePrice = gePrice;
		}

		public int getItemId() { return itemId; }
		public String getName() { return name; }
		public long getGePrice() { return gePrice; }

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
				// Millisecond timestamps collide: two requests made in the same
				// millisecond shared an id and silently overwrote each other.
				// Existing ids stay readable - nothing parses this back to a number.
				String requestId = java.util.UUID.randomUUID().toString();
				// "|" separates the fields, so it can't appear inside one.
				saveLookingForRequest(groupId, requestId, String.format("%s|%s|%d|%d|%s|%s",
					finalCurrentPlayer, displayName.replace("|", "/"), items.size(), duration,
					notes.replace("|", "/"), itemsStr.toString()));
				long totalValue = items.stream().mapToLong(it -> it.value * it.quantity).sum();
				final String chatMsg = String.format("[Lending Tracker] %s is looking for: %s (%d items, %s GP) for %d days",
					finalCurrentPlayer, displayName, items.size(), QuantityFormatter.quantityToStackSize(totalValue), duration);
				if (plugin.getClientThread() != null)
				{
					plugin.getClientThread().invokeLater(() -> {
						try { plugin.getClient().addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>" + (chatMsg), null); }
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

	/**
	 * Live Looking For posts for the group. They ride on the synced requests, so
	 * every member sees every post - they used to live only in the poster's own
	 * config and never left their machine.
	 */
	private List<LookingForRequest> getLookingForRequests(String groupId)
	{
		List<LookingForRequest> out = new java.util.ArrayList<>();
		if (groupId == null || groupId.isEmpty())
		{
			return out;
		}
		migrateLegacyLookingFor(groupId);
		for (LendingRequest r : dataService.getLookingForPosts(groupId))
		{
			LookingForRequest lf = r.getMessage() != null ? LookingForRequest.parse(r.getId(), r.getMessage()) : null;
			if (lf == null)
			{
				lf = new LookingForRequest();
				lf.id = r.getId();
				lf.itemName = r.getItemName() != null ? r.getItemName() : "Item";
				lf.quantity = Math.max(1, r.getQuantity());
				lf.durationDays = r.getDurationDays();
				lf.notes = "";
			}
			// The synced sender, not whatever the text claims.
			lf.requesterName = r.getFrom();
			lf.postedTime = r.getCreatedAt() > 0 ? r.getCreatedAt() : r.getUpdatedAt();
			out.add(lf);
		}
		return out;
	}

	/**
	 * Move this player's old local-only posts onto the synced requests, once.
	 * Posts by another account on this machine are left for that account to move
	 * when it logs in - only the poster may publish their own post.
	 */
	private void migrateLegacyLookingFor(String groupId)
	{
		String me = plugin.getCurrentPlayerName();
		if (me == null || !migratedLookingFor.add(groupId + ":" + me.toLowerCase()))
		{
			return;
		}
		try
		{
			String idsKey = "lookingForIds." + groupId;
			String idsStr = plugin.getConfigManager().getConfiguration("lendingtracker", idsKey);
			if (idsStr == null || idsStr.isEmpty())
			{
				return;
			}
			java.util.Set<String> remaining = new java.util.LinkedHashSet<>();
			for (String raw : idsStr.split(","))
			{
				String id = raw.trim();
				if (id.isEmpty()) continue;
				String key = "lookingFor." + groupId + "." + id;
				String data = plugin.getConfigManager().getConfiguration("lendingtracker", key);
				LookingForRequest parsed = data != null ? LookingForRequest.parse(id, data) : null;
				if (parsed == null)
				{
					plugin.getConfigManager().unsetConfiguration("lendingtracker", key);
					continue;
				}
				if (!me.equalsIgnoreCase(parsed.requesterName))
				{
					remaining.add(id);
					continue;
				}
				publishLookingFor(groupId, id, data);
				plugin.getConfigManager().unsetConfiguration("lendingtracker", key);
			}
			if (remaining.isEmpty())
			{
				plugin.getConfigManager().unsetConfiguration("lendingtracker", idsKey);
			}
			else
			{
				plugin.getConfigManager().setConfiguration("lendingtracker", idsKey, String.join(",", remaining));
			}
		}
		catch (Exception e)
		{
			log.warn("Could not move old Looking For posts: {}", e.getMessage());
		}
	}

	private void saveLookingForRequest(String groupId, String requestId, String requestData)
	{
		// An edit goes out as a fresh post. The request merge only carries the
		// STATUS forward for a row a peer already holds, so edited text would
		// never reach anyone who had seen the original.
		boolean exists = dataService.getLookingForPosts(groupId).stream()
			.anyMatch(r -> requestId.equals(r.getId()));
		String id = requestId;
		if (exists)
		{
			dataService.updateRequestStatus(groupId, requestId, LendingRequest.STATUS_CANCELLED);
			id = java.util.UUID.randomUUID().toString();
		}
		publishLookingFor(groupId, id, requestData);
	}

	private void publishLookingFor(String groupId, String id, String requestData)
	{
		LookingForRequest parsed = LookingForRequest.parse(id, requestData);
		if (parsed == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		LendingRequest r = new LendingRequest();
		r.setId(id);
		r.setType(LendingRequest.TYPE_LOOKING_FOR);
		r.setFrom(parsed.requesterName);
		r.setTo("");
		r.setItemName(parsed.itemName);
		r.setItemId(parsed.items.isEmpty() ? 0 : parsed.items.get(0).itemId);
		r.setQuantity(parsed.quantity);
		r.setDurationDays(parsed.durationDays);
		r.setMessage(requestData);
		r.setStatus(LendingRequest.STATUS_PENDING);
		r.setCreatedAt(now);
		r.setUpdatedAt(now);
		dataService.addRequest(groupId, r);
	}

	private void removeLookingForRequest(String groupId, String requestId)
	{
		// Cancelled rather than deleted, so the removal reaches everyone.
		dataService.updateRequestStatus(groupId, requestId, LendingRequest.STATUS_CANCELLED);
	}

	/**
	 * Card for a direct request (borrow request or lend offer).
	 * Incoming requests can be accepted or declined; outgoing ones cancelled.
	 */
	private class RequestCard extends JPanel
	{
		private final LendingRequest request;
		private final boolean incoming;

		public RequestCard(LendingRequest request, boolean incoming)
		{
			this.request = request;
			this.incoming = incoming;

			setLayout(new BorderLayout(5, 0));
			Color bgColor = incoming ? new Color(45, 60, 45) : new Color(55, 55, 45); // Green tint in, amber tint out
			setBackground(bgColor);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(8, 8, 8, 8)
			));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 65));
			setPreferredSize(new Dimension(200, 60));

			String title;
			if (request.isRemoval())
			{
				if (incoming)
				{
					title = request.isStaffRemoval()
						? "STAFF REVIEW: " + request.getFrom() + " asks to remove loan"
						: request.getFrom() + " asks to remove the loan of";
				}
				else
				{
					title = request.isStaffRemoval()
						? "You asked staff to remove loan"
						: "You asked " + request.getTo() + " to remove loan";
				}
			}
			else if (incoming)
			{
				title = request.isBorrowRequest()
					? request.getFrom() + " wants to borrow"
					: request.getFrom() + " offers to lend you";
			}
			else
			{
				title = request.isBorrowRequest()
					? "You asked " + request.getTo() + " for"
					: "You offered " + request.getTo();
			}

			JPanel detailsPanel = new JPanel();
			detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
			detailsPanel.setBackground(bgColor);

			JLabel titleLabel = new JLabel(title);
			titleLabel.setFont(FontManager.getRunescapeSmallFont());
			titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			detailsPanel.add(titleLabel);

			JLabel itemLabel = new JLabel(request.getItemName()
				+ (request.getQuantity() > 1 ? " x" + request.getQuantity() : "")
				+ (request.isRemoval() ? "" : "  • " + request.getDurationDays() + " days"));
			itemLabel.setFont(FontManager.getRunescapeBoldFont());
			itemLabel.setForeground(Color.WHITE);
			detailsPanel.add(itemLabel);

			if (request.getMessage() != null && !request.getMessage().isEmpty())
			{
				setToolTipText("Message: " + request.getMessage());
			}

			add(detailsPanel, BorderLayout.CENTER);

			JLabel hintLabel = new JLabel(incoming ? "<html><center>Right-click<br>to respond</center></html>" : "Pending");
			hintLabel.setFont(FontManager.getRunescapeSmallFont());
			hintLabel.setForeground(Color.GRAY);
			add(hintLabel, BorderLayout.EAST);

			setComponentPopupMenu(createRequestPopupMenu());
		}

		private JPopupMenu createRequestPopupMenu()
		{
			JPopupMenu menu = new JPopupMenu();
			if (incoming)
			{
				JMenuItem acceptItem = new JMenuItem("Accept");
				acceptItem.addActionListener(e -> acceptRequest(request));
				menu.add(acceptItem);

				JMenuItem declineItem = new JMenuItem("Decline");
				declineItem.addActionListener(e -> respondToRequest(request, LendingRequest.STATUS_DECLINED));
				menu.add(declineItem);
			}
			else
			{
				JMenuItem cancelItem = new JMenuItem("Cancel Request");
				cancelItem.addActionListener(e -> respondToRequest(request, LendingRequest.STATUS_CANCELLED));
				menu.add(cancelItem);
			}

			if (request.getMessage() != null && !request.getMessage().isEmpty())
			{
				menu.addSeparator();
				JMenuItem msgItem = new JMenuItem("Message: " + request.getMessage());
				msgItem.setEnabled(false);
				menu.add(msgItem);
			}
			return menu;
		}
	}

	/** Accept an incoming request: create the loan and mark the request accepted. */
	private void acceptRequest(LendingRequest request)
	{
		String groupId = groupService.getCurrentGroupIdUnchecked();
		String me = getCurrentPlayerName();
		if (groupId == null || me == null || me.equals("Not logged in"))
		{
			JOptionPane.showMessageDialog(this, "You must be logged in with an active group.",
				"Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		// Re-check the live status: the request may have been accepted/declined on
		// another client and merged in since this card was drawn. Acting on a stale
		// card would create a duplicate loan and flip the status back to accepted.
		LendingRequest current = dataService.getRequests(groupId).stream()
			.filter(r -> request.getId().equals(r.getId()))
			.findFirst().orElse(null);
		if (current == null || !current.isPending())
		{
			JOptionPane.showMessageDialog(this,
				"This request has already been handled.", "Already Handled",
				JOptionPane.INFORMATION_MESSAGE);
			refresh();
			return;
		}

		// Removal requests: approving doesn't create anything — it authorizes the
		// lender's client to retire the loan (audit-stamped, archived to history)
		if (request.isRemoval())
		{
			LendingEntry target = dataService.getActiveEntry(request.getEntryId());
			String what = target != null
				? target.getItem() + " (" + target.getLender() + " -> " + target.getBorrower() + ")"
				: request.getItemName();
			String role = request.isStaffRemoval() ? "As uninvolved staff, approve" : "Approve";
			int ok = JOptionPane.showConfirmDialog(this,
				role + " removing this loan from active tracking?\n" + what
					+ (request.getMessage() != null && !request.getMessage().isEmpty()
						? "\nReason: " + request.getMessage() : "")
					+ "\nIt will be archived to history with an approval record.",
				"Approve Removal", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (ok != JOptionPane.YES_OPTION)
			{
				return;
			}
			dataService.updateRequestStatus(groupId, request.getId(), LendingRequest.STATUS_ACCEPTED);
			// If I'm the lender (or eligible fallback), execute immediately;
			// otherwise the lender's client executes when the approval syncs
			plugin.applyApprovedRemovals();
			refresh();
			return;
		}

		// Borrow request: I'm the lender. Lend offer: I'm the borrower.
		String lender = request.isBorrowRequest() ? me : request.getFrom();
		String borrower = request.isBorrowRequest() ? request.getFrom() : me;
		int quantity = Math.max(1, request.getQuantity());

		String summary = String.format("%s\nLender: %s\nBorrower: %s\nDuration: %d days\n\nRecord this loan?",
			request.getItemName() + (quantity > 1 ? " x" + quantity : ""),
			lender, borrower, request.getDurationDays());
		if (JOptionPane.showConfirmDialog(this, summary, "Accept Request",
			JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
		{
			return;
		}

		LendingEntry entry = new LendingEntry();
		entry.setId(java.util.UUID.randomUUID().toString());
		entry.setItem(request.getItemName());
		entry.setItemId(request.getItemId());
		entry.setQuantity(quantity);
		entry.setNotes(request.getMessage());
		long value = 0;
		try
		{
			if (request.getItemId() > 0)
			{
				value = (long) itemManager.getItemPrice(request.getItemId()) * quantity;
			}
		}
		catch (Exception e)
		{
			log.debug("Could not look up item price for {}", request.getItemId());
		}
		entry.setValue(value);

		long dueTime = System.currentTimeMillis() + request.getDurationDays() * 86400000L;
		dataService.addLoan(groupId, lender, borrower, entry, dueTime);
		plugin.getDiscordWebhook().post(com.guess34.lendingtracker.services.DiscordWebhook.Event.LOAN, entry, me);
		dataService.updateRequestStatus(groupId, request.getId(), LendingRequest.STATUS_ACCEPTED);

		// If I lent from my marketplace listing, reduce or remove it. Use the
		// offering's own lender key (not `me`) so a case difference in the stored
		// owner key doesn't leave the item still showing as available.
		if (request.isBorrowRequest() && request.getItemId() > 0)
		{
			for (LendingEntry offering : dataService.getOfferingsByOwner(groupId, me))
			{
				if (offering.getItemId() == request.getItemId())
				{
					String ownerKey = offering.getLender() != null ? offering.getLender() : me;
					if (offering.getQuantity() > quantity)
					{
						LendingEntry updated = new LendingEntry(offering);
						updated.setQuantity(offering.getQuantity() - quantity);
						dataService.updateAvailable(groupId, ownerKey, offering.getItem(), offering.getItemId(), updated);
					}
					else
					{
						dataService.removeAvailable(groupId, ownerKey, offering.getItem(), offering.getItemId());
					}
					break;
				}
			}
		}

		refresh();
	}

	/** Decline an incoming request or cancel an outgoing one. */
	private void respondToRequest(LendingRequest request, String status)
	{
		String groupId = groupService.getCurrentGroupIdUnchecked();
		if (groupId == null)
		{
			return;
		}
		dataService.updateRequestStatus(groupId, request.getId(), status);
		refresh();
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
		// Filled in per redraw: who has something from this post listed, and
		// whether the viewer is one of them.
		java.util.List<String> listedBy = new java.util.ArrayList<>();
		boolean wantsMine;

		/** Does this listing satisfy anything in the post? By item where known, else by name. */
		boolean wants(LendingEntry e)
		{
			int base = ItemVariationMapping.map(e.getItemId());
			for (LookingForItem it : items)
			{
				if (it.itemId > 0 && ItemVariationMapping.map(it.itemId) == base) return true;
				if (it.itemName != null && it.itemName.equalsIgnoreCase(e.getItem())) return true;
			}
			return items.isEmpty() && itemName != null && itemName.equalsIgnoreCase(e.getItem());
		}

		boolean wantsAny(java.util.Set<Integer> bases, List<LendingEntry> listings, String owner)
		{
			for (LendingEntry e : listings)
			{
				if (owner.equalsIgnoreCase(e.getLender()) && bases.contains(ItemVariationMapping.map(e.getItemId())) && wants(e))
				{
					return true;
				}
			}
			return false;
		}

		boolean matches(String query)
		{
			if ((requesterName != null && requesterName.toLowerCase().contains(query))
				|| (itemName != null && itemName.toLowerCase().contains(query)))
			{
				return true;
			}
			for (LookingForItem it : items)
			{
				if (it.itemName != null && it.itemName.toLowerCase().contains(query)) return true;
			}
			return false;
		}

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

	/** "2 x Shark" for a single-item post, from the item list where there is one. */
	private static String wantedLine(LookingForRequest r)
	{
		LookingForItem only = r.items.isEmpty() ? null : r.items.get(0);
		String name = only != null && only.itemName != null ? only.itemName : r.itemName;
		int qty = only != null ? Math.max(1, only.quantity) : Math.max(1, r.quantity);
		if (name.length() > 20) name = name.substring(0, 17) + "...";
		return qty + " x " + name;
	}

	/** One wanted item, shown under an opened Looking For post. */
	private class WantedItemRow extends JPanel
	{
		WantedItemRow(LookingForItem item)
		{
			setLayout(new BorderLayout(5, 0));
			Color bg = new Color(38, 42, 50);
			setBackground(bg);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(3, 18, 3, 6)));
			setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
			setPreferredSize(new Dimension(200, 38));

			JLabel icon = new JLabel();
			icon.setPreferredSize(new Dimension(36, 32));
			if (item.itemId > 0)
			{
				AsyncBufferedImage img = itemManager.getImage(item.itemId,
					Math.max(1, item.quantity), item.quantity > 1);
				if (img != null)
				{
					img.addTo(icon);
				}
			}
			add(icon, BorderLayout.WEST);

			String name = item.itemName != null ? item.itemName : "?";
			if (name.length() > 18) name = name.substring(0, 15) + "...";
			JLabel label = new JLabel(Math.max(1, item.quantity) + " x " + name);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(Color.WHITE);
			add(label, BorderLayout.CENTER);

			if (item.value > 0)
			{
				JLabel worth = new JLabel(QuantityFormatter.quantityToStackSize(item.value * Math.max(1, item.quantity)));
				worth.setFont(FontManager.getRunescapeSmallFont());
				worth.setForeground(Color.YELLOW);
				add(worth, BorderLayout.EAST);
			}
		}
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

			setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
			setPreferredSize(new Dimension(200, 74));

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

			// Left side: the item's own icon - the first one on a multi-item post
			boolean multi = request.isMultiItem();
			boolean open = multi && expandedWants.contains(request.id);
			LookingForItem lead = request.items.isEmpty() ? null : request.items.get(0);
			JLabel iconLabel = new JLabel();
			iconLabel.setPreferredSize(new Dimension(36, 32));
			iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
			if (lead != null && lead.itemId > 0)
			{
				AsyncBufferedImage img = itemManager.getImage(lead.itemId,
					Math.max(1, lead.quantity), lead.quantity > 1);
				if (img != null)
				{
					img.addTo(iconLabel);
				}
			}
			else
			{
				// Posts made before item ids were recorded have only a name
				iconLabel.setText("WANT");
				iconLabel.setFont(FontManager.getRunescapeSmallFont());
				iconLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
			}
			add(iconLabel, BorderLayout.WEST);

			// Center: Request details
			detailsPanel = new JPanel();
			detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
			detailsPanel.setBackground(bgColor);

			// The post's own title on top...
			String title = request.itemName;
			if (title.length() > 20) title = title.substring(0, 17) + "...";
			JLabel titleLabel = new JLabel(title);
			titleLabel.setFont(FontManager.getRunescapeSmallFont());
			titleLabel.setForeground(Color.WHITE);

			// ...and what they actually want underneath it. Several items read as a
			// set: one line that opens on a click, closed until then.
			JLabel wantLabel = new JLabel(multi
				? (open ? "\u25BC " : "\u25B6 ") + request.getItemCount() + " items  \u00B7  "
					+ QuantityFormatter.quantityToStackSize(request.getTotalValue())
				: wantedLine(request));
			wantLabel.setFont(FontManager.getRunescapeSmallFont());
			wantLabel.setForeground(multi ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.LIGHT_GRAY_COLOR);

			// Requester name
			String requesterText = "By: " + request.requesterName;
			JLabel requesterLabel = new JLabel(requesterText);
			requesterLabel.setFont(FontManager.getRunescapeSmallFont());
			requesterLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

			detailsPanel.add(titleLabel);
			detailsPanel.add(wantLabel);
			detailsPanel.add(requesterLabel);

			// Matched against the marketplace, so nobody has to go looking.
			if (request.wantsMine || !request.listedBy.isEmpty())
			{
				String who = request.wantsMine ? "You have this listed"
					: "Listed by " + request.listedBy.get(0)
						+ (request.listedBy.size() > 1 ? " +" + (request.listedBy.size() - 1) : "");
				JLabel match = new JLabel(who);
				match.setFont(FontManager.getRunescapeSmallFont());
				match.setForeground(request.wantsMine ? ColorScheme.BRAND_ORANGE : new Color(0x8C, 0xE0, 0x8C));
				match.setToolTipText("<html>Listed right now by:<br>" + String.join("<br>", request.listedBy) + "</html>");
				detailsPanel.add(match);
				setMaximumSize(new Dimension(Integer.MAX_VALUE, 94));
				setPreferredSize(new Dimension(200, 88));
			}

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

			if (multi)
			{
				setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				addMouseListener(new java.awt.event.MouseAdapter()
				{
					@Override
					public void mouseClicked(java.awt.event.MouseEvent e)
					{
						if (!SwingUtilities.isLeftMouseButton(e)) return;
						if (!expandedWants.remove(request.id)) expandedWants.add(request.id);
						refresh();
					}
				});
			}
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
						// Keep the item list, or the post can no longer be matched
						// against the marketplace by item.
						StringBuilder itemsStr = new StringBuilder();
						for (LookingForItem it : request.items)
						{
							if (itemsStr.length() > 0) itemsStr.append(",");
							itemsStr.append(it.itemId).append(":").append(it.itemName.replace(":", "").replace(",", ""))
								.append(":").append(it.quantity).append(":").append(it.value);
						}
						String requestData = String.format("%s|%s|%d|%d|%s|%s",
							request.requesterName, newItemName, newQty, newDuration,
							newNotes.replace("|", "/"), itemsStr);
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
					boolean sent = plugin.sendLendOffer(lender, request.requesterName, request.itemName, offerQty, durationDays, msgField.getText().trim(), durationDisplay);
					if (sent)
					{
						String deliveryNote = plugin.isRelaySyncConnected()
							? "They'll see it in their Lending Tracker panel."
							: "Cloud Sync is offline — it will be delivered when they next sync.";
						JOptionPane.showMessageDialog(DashboardPanel.this,
							"Offer sent to " + request.requesterName + "!\nItem: " + request.itemName + " x" + offerQty + "\nDuration: " + durationDisplay + "\n" + deliveryNote,
							"Offer Sent", JOptionPane.INFORMATION_MESSAGE);
					}
					else
					{
						JOptionPane.showMessageDialog(DashboardPanel.this,
							"Could not send the offer — no active group.",
							"Offer Not Sent", JOptionPane.ERROR_MESSAGE);
					}
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
