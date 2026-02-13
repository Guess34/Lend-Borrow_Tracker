package net.runelite.client.plugins.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * LendingPanel - Main UI shell with Material Design tabs
 * Phase 3: Modular UI with Flipping Utilities style
 */
@Slf4j
public class LendingPanel extends PluginPanel
{
	private final LendingTrackerPlugin plugin;
	private final EventBus eventBus;

	// Group control panel (persistent header)
	private final GroupControlPanel groupControlPanel;

	// Display panel that holds the active tab content
	private final JPanel display = new JPanel();
	private final MaterialTabGroup tabGroup;

	// Tab panels
	private DashboardPanel marketplacePanel; // Renamed from dashboardPanel
	private RosterPanel rosterPanel;
	private HistoryPanel historyPanel;
	private SettingsPanel settingsPanel;

	public LendingPanel(LendingTrackerPlugin plugin, EventBus eventBus)
	{
		super(false);
		this.plugin = plugin;
		this.eventBus = eventBus;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create group control panel at the top
		groupControlPanel = new GroupControlPanel(plugin, eventBus);

		// FIXED: MaterialTabGroup needs display panel in constructor
		tabGroup = new MaterialTabGroup(display);
		tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));
		tabGroup.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create tab panels
		marketplacePanel = new DashboardPanel(plugin); // Marketplace (was Dashboard)
		rosterPanel = new RosterPanel(plugin);
		historyPanel = new HistoryPanel(plugin);
		settingsPanel = new SettingsPanel(plugin);

		// Initialize tabs
		initializeTabs();

		// Layout: Group control at top, tabs below, content in center
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topPanel.add(groupControlPanel, BorderLayout.NORTH);
		topPanel.add(tabGroup, BorderLayout.SOUTH);

		add(topPanel, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// Register for group change events
		eventBus.register(this);

		log.info("LendingPanel initialized with group controls and 4 tabs");
	}

	/**
	 * Refresh all panels (called when data changes)
	 * FIXED: Now ensures data is loaded for the active group before refreshing panels
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// FIXED: Ensure data is loaded for the active group BEFORE refreshing panels
			// This fixes the issue where items don't show on login until group switch
			String activeGroupId = plugin.getGroupConfigStore().getCurrentGroupIdUnchecked();
			if (activeGroupId != null)
			{
				// Load data from all storage systems for the active group
				plugin.getRecorder().loadGroupData(activeGroupId);
				plugin.getMarketplaceManager().loadGroupData(activeGroupId);
				plugin.getItemSetManager().loadGroupData(activeGroupId);
				log.debug("LendingPanel.refresh: Loaded data for active group: {}", activeGroupId);
			}

			if (groupControlPanel != null)
			{
				groupControlPanel.refresh();
			}
			if (marketplacePanel != null)
			{
				marketplacePanel.refresh();
			}
			if (rosterPanel != null)
			{
				rosterPanel.refresh();
			}
			if (historyPanel != null)
			{
				historyPanel.refresh();
			}
			if (settingsPanel != null)
			{
				settingsPanel.refresh();
			}
		});
	}

	/**
	 * Handle group change events
	 */
	@Subscribe
	public void onGroupChangedEvent(GroupControlPanel.GroupChangedEvent event)
	{
		log.info("Group changed event received: {}", event.getGroupId());

		// Load data for the newly selected group from ALL systems
		if (event.getGroupId() != null) {
			// Load from recorder (primary storage used by right-click "Add to Lending List")
			plugin.getRecorder().loadGroupData(event.getGroupId());
			log.info("Loaded recorder data for group: {}", event.getGroupId());

			// Also load from marketplaceManager (used by button adds)
			plugin.getMarketplaceManager().loadGroupData(event.getGroupId());
			log.info("Loaded marketplace manager data for group: {}", event.getGroupId());

			// Load item sets for the group
			plugin.getItemSetManager().loadGroupData(event.getGroupId());
			log.info("Loaded item set data for group: {}", event.getGroupId());

			// FIXED: Restart sync for the newly selected group
			String playerName = plugin.getCurrentPlayerName();
			if (playerName != null) {
				plugin.getGroupSyncService().startSync(event.getGroupId(), playerName);
				log.info("Restarted sync for group: {}", event.getGroupId());
			}
		}

		refresh();
	}

	// Store tabs for easy access
	private MaterialTab marketplaceTab;
	private MaterialTab rosterTab;
	private MaterialTab historyTab;
	private MaterialTab settingsTab;

	/**
	 * Initialize tabs with drawn icon-based MaterialTabs
	 * CHANGED: Replaced Unicode symbol tabs with Graphics2D drawn icons
	 */
	private void initializeTabs()
	{
		ImageIcon shopIcon = createShopIcon(ColorScheme.BRAND_ORANGE);
		ImageIcon teamIcon = createPeopleIcon(new Color(100, 149, 237));
		ImageIcon logIcon = createClockIcon(new Color(0, 170, 0));
		ImageIcon cfgIcon = createGearIcon(ColorScheme.MEDIUM_GRAY_COLOR);

		marketplaceTab = new MaterialTab(shopIcon, tabGroup, marketplacePanel);
		rosterTab = new MaterialTab(teamIcon, tabGroup, rosterPanel);
		historyTab = new MaterialTab(logIcon, tabGroup, historyPanel);
		settingsTab = new MaterialTab(cfgIcon, tabGroup, settingsPanel);

		marketplaceTab.setToolTipText("Marketplace");
		rosterTab.setToolTipText("Group Roster");
		historyTab.setToolTipText("History & Notifications");
		settingsTab.setToolTipText("Settings");

		tabGroup.addTab(marketplaceTab);
		tabGroup.addTab(rosterTab);
		tabGroup.addTab(historyTab);
		tabGroup.addTab(settingsTab);

		tabGroup.select(marketplaceTab);

		log.info("Tabs initialized with drawn icons");
	}

	/**
	 * Create a base icon image with antialiasing enabled
	 */
	private static Graphics2D createIconGraphics(BufferedImage img)
	{
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

	/**
	 * Shopping bag icon for Marketplace tab
	 */
	private static ImageIcon createShopIcon(Color color)
	{
		int size = 20;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = createIconGraphics(img);
		g.setColor(color);
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		// Bag body
		g.drawRoundRect(4, 7, 12, 10, 2, 2);
		// Handle (arc on top)
		g.drawArc(6, 2, 8, 10, 0, 180);

		g.dispose();
		return new ImageIcon(img);
	}

	/**
	 * Two-person silhouette icon for Roster tab
	 */
	private static ImageIcon createPeopleIcon(Color color)
	{
		int size = 20;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = createIconGraphics(img);
		g.setColor(color);

		// Front person - head
		g.fillOval(7, 2, 6, 6);
		// Front person - body
		g.fillArc(5, 9, 10, 10, 0, 180);

		// Back person (offset left, slightly behind) - head
		g.fillOval(2, 4, 5, 5);
		// Back person - body
		g.fillArc(0, 10, 9, 9, 0, 180);

		g.dispose();
		return new ImageIcon(img);
	}

	/**
	 * Clock icon for History tab
	 */
	private static ImageIcon createClockIcon(Color color)
	{
		int size = 20;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = createIconGraphics(img);
		g.setColor(color);
		g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		// Clock face
		g.drawOval(2, 2, 16, 16);
		// Hour hand (pointing to 10 o'clock)
		g.drawLine(10, 10, 7, 5);
		// Minute hand (pointing to 2 o'clock)
		g.drawLine(10, 10, 14, 7);
		// Center dot
		g.fillOval(9, 9, 3, 3);

		g.dispose();
		return new ImageIcon(img);
	}

	/**
	 * Gear/cog icon for Settings tab
	 */
	private static ImageIcon createGearIcon(Color color)
	{
		int size = 20;
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = createIconGraphics(img);
		g.setColor(color);

		int cx = 10, cy = 10;
		int outerR = 8, innerR = 5;
		int teeth = 8;

		// Build gear shape as polygon
		int points = teeth * 2;
		int[] xPoints = new int[points];
		int[] yPoints = new int[points];
		for (int i = 0; i < points; i++)
		{
			double angle = Math.PI * 2 * i / points - Math.PI / 2;
			int r = (i % 2 == 0) ? outerR : innerR;
			xPoints[i] = cx + (int) Math.round(r * Math.cos(angle));
			yPoints[i] = cy + (int) Math.round(r * Math.sin(angle));
		}
		g.fillPolygon(xPoints, yPoints, points);

		// Center hole
		g.setColor(new Color(0, 0, 0, 0));
		g.setComposite(AlphaComposite.Clear);
		g.fillOval(cx - 3, cy - 3, 6, 6);

		g.dispose();
		return new ImageIcon(img);
	}

	/**
	 * Switch to marketplace tab
	 */
	public void switchToMarketplace()
	{
		tabGroup.select(marketplaceTab);
	}

	/**
	 * Switch to roster tab
	 */
	public void switchToRoster()
	{
		tabGroup.select(rosterTab);
	}

	/**
	 * Switch to history tab
	 */
	public void switchToHistory()
	{
		tabGroup.select(historyTab);
	}

	/**
	 * Get the marketplace panel
	 */
	public DashboardPanel getMarketplacePanel()
	{
		return marketplacePanel;
	}

	/**
	 * Get the roster panel
	 */
	public RosterPanel getRosterPanel()
	{
		return rosterPanel;
	}

	/**
	 * Get the history panel
	 */
	public HistoryPanel getHistoryPanel()
	{
		return historyPanel;
	}

	/**
	 * Get the settings panel
	 */
	public SettingsPanel getSettingsPanel()
	{
		return settingsPanel;
	}

	/**
	 * FIXED: Set the current account name directly.
	 * This is the proper way to update the account dropdown on login.
	 * Called from the plugin after detecting login on the client thread.
	 *
	 * @param accountName The logged-in player's name, or null if logged out
	 */
	public void setCurrentAccount(String accountName)
	{
		log.info("LendingPanel.setCurrentAccount: {}", accountName);
		if (groupControlPanel != null)
		{
			groupControlPanel.setCurrentAccount(accountName);
		}
	}

	/**
	 * FIXED: Clear the current account (called on logout)
	 */
	public void clearCurrentAccount()
	{
		log.info("LendingPanel.clearCurrentAccount called");
		if (groupControlPanel != null)
		{
			groupControlPanel.clearCurrentAccount();
		}
	}

	/**
	 * ADDED: Shutdown method to clean up timers and resources
	 */
	public void shutdown()
	{
		// Stop the online status refresh timer in the marketplace panel
		if (marketplacePanel != null)
		{
			marketplacePanel.stopRefreshTimer();
		}
		// Unregister from event bus
		eventBus.unregister(this);
		log.info("LendingPanel shutdown complete");
	}
}
