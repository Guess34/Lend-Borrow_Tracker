package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * LendingPanel - Main UI shell with Material Design tabs.
 */
@Slf4j
public class LendingPanel extends PluginPanel
{
	private final LendingTrackerPlugin plugin;
	private final EventBus eventBus;

	// Group control panel (persistent header)
	private final GroupControlPanel groupControlPanel;

	// Connection status bar
	private JLabel connectionDot;
	private JLabel connectionLabel;

	// Display panel — reports small preferred height so child scroll panes can scroll
	private final JPanel display = new JPanel()
	{
		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(super.getPreferredSize().width, 0);
		}
	};
	private final MaterialTabGroup tabGroup;

	// Tab panels
	private DashboardPanel marketplacePanel;
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

		tabGroup = new MaterialTabGroup(display);
		tabGroup.setBorder(new EmptyBorder(5, 0, 0, 0));
		tabGroup.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create tab panels
		marketplacePanel = new DashboardPanel(plugin);
		rosterPanel = new RosterPanel(plugin);
		historyPanel = new HistoryPanel(plugin);
		settingsPanel = new SettingsPanel(plugin);

		// Initialize tabs
		initializeTabs();

		// Connection status bar
		JPanel connectionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
		connectionBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		connectionDot = new JLabel("\u25CF");
		connectionDot.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		connectionDot.setFont(connectionDot.getFont().deriveFont(8f));
		connectionLabel = new JLabel("Not connected");
		connectionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		connectionLabel.setFont(connectionLabel.getFont().deriveFont(Font.PLAIN, 10f));
		connectionBar.add(connectionDot);
		connectionBar.add(connectionLabel);

		// Layout: Group control at top, connection bar, tabs, content in center
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topPanel.add(groupControlPanel);
		topPanel.add(connectionBar);
		topPanel.add(tabGroup);

		add(topPanel, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		// Register for group change events
		eventBus.register(this);

		// Poll relay connection status until connected, then stop
		Timer connectionPoller = new Timer(5000, null);
		connectionPoller.addActionListener(e ->
		{
			boolean connected = plugin.isRelaySyncConnected();
			updateConnectionStatus(connected);
			if (connected)
			{
				connectionPoller.stop();
			}
		});
		connectionPoller.setRepeats(true);
		connectionPoller.start();
	}

	/**
	 * Refresh all panels (called when data changes).
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// Ensure data is loaded for the active group BEFORE refreshing panels
			String activeGroupId = plugin.getGroupService().getCurrentGroupIdUnchecked();
			if (activeGroupId != null)
			{
				plugin.getDataService().loadGroupData(activeGroupId);
			}

			groupControlPanel.refresh();
			marketplacePanel.refresh();
			rosterPanel.refresh();
			historyPanel.refresh();
			settingsPanel.refresh();

			// Sync connection status on every refresh so the indicator is always current
			updateConnectionStatus(plugin.isRelaySyncConnected());
		});
	}

	/**
	 * Update the connection status indicator (called from relay sync callback).
	 */
	public void updateConnectionStatus(boolean connected)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (connectionDot != null)
			{
				connectionDot.setForeground(connected ? new Color(0, 200, 0) : new Color(200, 60, 60));
			}
			if (connectionLabel != null)
			{
				connectionLabel.setText(connected ? "Synced" : "Offline");
				connectionLabel.setForeground(connected
					? new Color(0, 200, 0) : ColorScheme.LIGHT_GRAY_COLOR);
			}
		});
	}

	@Subscribe
	public void onGroupChangedEvent(GroupControlPanel.GroupChangedEvent event)
	{
		// Load data for the newly selected group
		if (event.getGroupId() != null) {
			plugin.getDataService().loadGroupData(event.getGroupId());

			// Restart sync for the newly selected group
			String playerName = plugin.getCurrentPlayerName();
			if (playerName != null) {
				plugin.getGroupService().startSync(event.getGroupId(), playerName);
			}
		}

		refresh();
	}

	/**
	 * Initialize tabs with drawn icon-based MaterialTabs.
	 */
	private void initializeTabs()
	{
		ImageIcon shopIcon = createShopIcon(ColorScheme.BRAND_ORANGE);
		ImageIcon teamIcon = createPeopleIcon(new Color(100, 149, 237));
		ImageIcon logIcon = createClockIcon(new Color(0, 170, 0));
		ImageIcon cfgIcon = createGearIcon(ColorScheme.MEDIUM_GRAY_COLOR);

		MaterialTab marketplaceTab = new MaterialTab(shopIcon, tabGroup, marketplacePanel);
		MaterialTab rosterTab = new MaterialTab(teamIcon, tabGroup, rosterPanel);
		MaterialTab historyTab = new MaterialTab(logIcon, tabGroup, historyPanel);
		MaterialTab settingsTab = new MaterialTab(cfgIcon, tabGroup, settingsPanel);

		marketplaceTab.setToolTipText("Marketplace");
		rosterTab.setToolTipText("Group Roster");
		historyTab.setToolTipText("History & Notifications");
		settingsTab.setToolTipText("Settings");

		tabGroup.addTab(marketplaceTab);
		tabGroup.addTab(rosterTab);
		tabGroup.addTab(historyTab);
		tabGroup.addTab(settingsTab);

		tabGroup.select(marketplaceTab);
	}

	private static Graphics2D createIconGraphics(BufferedImage img)
	{
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

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

	// Panel lifecycle (creation, disposal) is managed by RuneLite via NavigationButton.
}
