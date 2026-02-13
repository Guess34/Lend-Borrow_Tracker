package net.runelite.client.plugins.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Friend;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.GroupMember;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.plugins.lendingtracker.services.OnlineStatusService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RosterPanel - Shows group members with online/offline status (like friends list)
 */
@Slf4j
public class RosterPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final GroupConfigStore groupConfigStore;

	private final JLabel headerLabel;
	private final IconTextField searchBar;
	private final JPanel memberListPanel;
	private final List<JPanel> allMemberRows = new ArrayList<>();

	public RosterPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.groupConfigStore = plugin.getGroupConfigStore();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Header with group name
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(new EmptyBorder(10, 10, 5, 10));

		headerLabel = new JLabel("Group Roster");
		headerLabel.setFont(FontManager.getRunescapeBoldFont());
		headerLabel.setForeground(Color.WHITE);
		headerPanel.add(headerLabel, BorderLayout.CENTER);

		// Create search bar
		JPanel searchPanel = new JPanel(new BorderLayout());
		searchPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchPanel.setBorder(new EmptyBorder(5, 10, 10, 10));

		searchBar = new IconTextField();
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(0, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setMinimumSize(new Dimension(0, 30));
		searchBar.addActionListener(e -> filterMembers(searchBar.getText()));
		searchBar.addClearListener(this::clearSearch);
		searchBar.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyReleased(java.awt.event.KeyEvent e)
			{
				filterMembers(searchBar.getText());
			}
		});

		searchPanel.add(searchBar, BorderLayout.CENTER);

		// Top panel combines header and search
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topPanel.add(headerPanel, BorderLayout.NORTH);
		topPanel.add(searchPanel, BorderLayout.SOUTH);

		add(topPanel, BorderLayout.NORTH);

		// Create scrollable member list
		memberListPanel = new JPanel();
		memberListPanel.setLayout(new BoxLayout(memberListPanel, BoxLayout.Y_AXIS));
		memberListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JScrollPane scrollPane = new JScrollPane(memberListPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

		add(scrollPane, BorderLayout.CENTER);
	}

	/**
	 * Refresh the roster with latest data
	 * FIXED: Now checks login status before showing group data
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			log.info("RosterPanel.refresh() called");

			// ADDED: Check login status first
			boolean isLoggedIn = false;
			try
			{
				if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
				{
					String playerName = plugin.getClient().getLocalPlayer().getName();
					if (playerName != null && !playerName.isEmpty())
					{
						isLoggedIn = true;
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Could not check login status", e);
			}

			// If not logged in, show login message and don't show any group data
			if (!isLoggedIn)
			{
				log.info("User not logged in - showing login message");
				headerLabel.setText("Group Roster");
				showNotLoggedInMessage();
				return;
			}

			// Get active group - only if logged in
			LendingGroup activeGroup = groupConfigStore.getActiveGroup();
			if (activeGroup == null)
			{
				log.info("No active group - showing message");
				headerLabel.setText("No Group Selected");
				showNoGroupMessage();
				return;
			}

			// Update header with group name
			headerLabel.setText(activeGroup.getName() + " Roster");
			log.info("Active group: {} ({})", activeGroup.getName(), activeGroup.getId());

			// Get online players for status checking
			Set<String> onlinePlayers = getOnlinePlayers();

			// Get group members
			List<GroupMember> members = activeGroup.getMembers();
			log.info("Group has {} members", members != null ? members.size() : 0);

			// Clear and rebuild member list
			memberListPanel.removeAll();
			allMemberRows.clear();

			if (members == null || members.isEmpty())
			{
				showEmptyMessage();
			}
			else
			{
				// Sort members: online first, then by role rank, then alphabetically
				List<GroupMember> sortedMembers = new ArrayList<>(members);
				sortedMembers.sort((a, b) -> {
					boolean aOnline = isPlayerOnline(a.getName(), onlinePlayers);
					boolean bOnline = isPlayerOnline(b.getName(), onlinePlayers);

					// Online first
					if (aOnline != bOnline) return aOnline ? -1 : 1;

					// Then by role rank (higher rank first)
					int aRank = GroupConfigStore.getRoleRank(a.getRole());
					int bRank = GroupConfigStore.getRoleRank(b.getRole());
					if (aRank != bRank) return bRank - aRank;

					// Then alphabetically
					return a.getName().compareToIgnoreCase(b.getName());
				});

				// Create member rows
				for (GroupMember member : sortedMembers)
				{
					boolean isOnline = isPlayerOnline(member.getName(), onlinePlayers);
					JPanel memberRow = createMemberRow(member, isOnline);
					allMemberRows.add(memberRow);
					memberListPanel.add(memberRow);
					memberListPanel.add(Box.createVerticalStrut(2));
				}
			}

			memberListPanel.revalidate();
			memberListPanel.repaint();
		});
	}

	/**
	 * Create a row for a group member
	 * FIXED: Now shows online status with world number
	 */
	private JPanel createMemberRow(GroupMember member, boolean isOnline)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(8, 10, 8, 10));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));

		// Left: Online indicator + Name + Status
		JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Online/Offline indicator (colored dot)
		JLabel statusDot = new JLabel("\u2022"); // Bullet character
		statusDot.setFont(new Font("Arial", Font.BOLD, 20));
		statusDot.setForeground(isOnline ? new Color(0, 200, 83) : new Color(150, 150, 150));

		// Get world number for online players
		String statusTooltip = "Offline";
		String worldText = "";
		if (isOnline)
		{
			OnlineStatusService onlineStatusService = plugin.getOnlineStatusService();
			if (onlineStatusService != null)
			{
				OnlineStatusService.OnlineStatus status = onlineStatusService.getPlayerStatus(member.getName());
				if (status != null && status.world > 0)
				{
					worldText = " (W" + status.world + ")";
					statusTooltip = "Online - World " + status.world;
				}
				else
				{
					statusTooltip = "Online";
				}
			}
			else
			{
				statusTooltip = "Online";
			}
		}
		statusDot.setToolTipText(statusTooltip);
		leftPanel.add(statusDot);

		// Player name with world if online
		JLabel nameLabel = new JLabel(member.getName() + worldText);
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(isOnline ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		leftPanel.add(nameLabel);

		row.add(leftPanel, BorderLayout.WEST);

		// Right: Role badge
		String role = member.getRole() != null ? member.getRole() : "member";
		JLabel roleLabel = new JLabel(formatRoleName(role));
		roleLabel.setFont(FontManager.getRunescapeSmallFont());
		roleLabel.setOpaque(true);
		roleLabel.setBorder(new EmptyBorder(2, 6, 2, 6));

		// Color-code by role
		switch (role.toLowerCase())
		{
			case "owner":
				roleLabel.setBackground(new Color(255, 215, 0)); // Gold
				roleLabel.setForeground(Color.BLACK);
				break;
			case "co-owner":
				roleLabel.setBackground(new Color(192, 192, 192)); // Silver
				roleLabel.setForeground(Color.BLACK);
				break;
			case "admin":
				roleLabel.setBackground(ColorScheme.BRAND_ORANGE);
				roleLabel.setForeground(Color.WHITE);
				break;
			case "mod":
				roleLabel.setBackground(new Color(100, 149, 237)); // Cornflower blue
				roleLabel.setForeground(Color.WHITE);
				break;
			default:
				roleLabel.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
				roleLabel.setForeground(Color.WHITE);
				break;
		}

		row.add(roleLabel, BorderLayout.EAST);

		// Store the member name for filtering
		row.putClientProperty("memberName", member.getName().toLowerCase());

		// ADDED: Right-click context menu
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShowPopup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShowPopup(e);
			}

			private void maybeShowPopup(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showMemberPopup(e.getComponent(), e.getX(), e.getY(), member, isOnline);
				}
			}
		});

		return row;
	}

	/**
	 * Get set of online players (from friends chat / clan chat / friends list)
	 */
	private Set<String> getOnlinePlayers()
	{
		Set<String> online = new HashSet<>();

		try
		{
			// Check Friends Chat members
			FriendsChatManager fcManager = plugin.getClient().getFriendsChatManager();
			if (fcManager != null)
			{
				FriendsChatMember[] fcMembers = fcManager.getMembers();
				if (fcMembers != null)
				{
					for (FriendsChatMember m : fcMembers)
					{
						if (m != null && m.getName() != null)
						{
							online.add(m.getName().toLowerCase());
						}
					}
				}
			}

			// Check friends list for online status
			Friend[] friends = plugin.getClient().getFriendContainer().getMembers();
			if (friends != null)
			{
				for (Friend f : friends)
				{
					if (f != null && f.getName() != null && f.getWorld() > 0)
					{
						online.add(f.getName().toLowerCase());
					}
				}
			}

			// Current player is always "online"
			if (plugin.getClient().getLocalPlayer() != null)
			{
				String localName = plugin.getClient().getLocalPlayer().getName();
				if (localName != null)
				{
					online.add(localName.toLowerCase());
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Error getting online players", e);
		}

		return online;
	}

	/**
	 * Check if a player is online
	 * FIXED: Also checks OnlineStatusService for test/fake players
	 */
	private boolean isPlayerOnline(String playerName, Set<String> onlinePlayers)
	{
		if (playerName == null) return false;

		// First check the game-based online players
		if (onlinePlayers.contains(playerName.toLowerCase()))
		{
			return true;
		}

		// Also check OnlineStatusService for manually set status (test players)
		OnlineStatusService onlineStatusService = plugin.getOnlineStatusService();
		if (onlineStatusService != null && onlineStatusService.isPlayerOnline(playerName))
		{
			return true;
		}

		return false;
	}

	/**
	 * ADDED: Show right-click context menu for a member row
	 */
	private void showMemberPopup(Component component, int x, int y, GroupMember member, boolean isOnline)
	{
		JPopupMenu popup = new JPopupMenu();

		// View Activity
		JMenuItem viewActivity = new JMenuItem("View Activity");
		viewActivity.addActionListener(e ->
		{
			log.info("View activity for: {}", member.getName());
			JOptionPane.showMessageDialog(this,
				"Activity history for " + member.getName() + "\n(Switch to Log tab for full history)",
				"Player Activity",
				JOptionPane.INFORMATION_MESSAGE);
		});
		popup.add(viewActivity);

		// CHANGED: Show world info for online players (informational, not actionable)
		if (isOnline)
		{
			OnlineStatusService onlineStatusService = plugin.getOnlineStatusService();
			if (onlineStatusService != null)
			{
				OnlineStatusService.OnlineStatus status = onlineStatusService.getPlayerStatus(member.getName());
				if (status != null && status.world > 0)
				{
					JMenuItem worldInfo = new JMenuItem("World " + status.world);
					worldInfo.setEnabled(false);
					popup.add(worldInfo);
				}
			}
		}

		popup.show(component, x, y);
	}

	/**
	 * CHANGED: Delegates to shared utility in GroupConfigStore
	 */
	private String formatRoleName(String role)
	{
		return GroupConfigStore.formatRoleName(role);
	}

	/**
	 * Filter members by name
	 */
	private void filterMembers(String query)
	{
		String lowerQuery = query.toLowerCase().trim();

		for (JPanel row : allMemberRows)
		{
			String memberName = (String) row.getClientProperty("memberName");
			boolean matches = lowerQuery.isEmpty() ||
				(memberName != null && memberName.contains(lowerQuery));
			row.setVisible(matches);
		}

		memberListPanel.revalidate();
		memberListPanel.repaint();
	}

	/**
	 * Clear search filter
	 */
	private void clearSearch()
	{
		searchBar.setText("");
		filterMembers("");
	}

	/**
	 * ADDED: Show not logged in message
	 */
	private void showNotLoggedInMessage()
	{
		memberListPanel.removeAll();
		allMemberRows.clear();

		JPanel emptyPanel = new JPanel(new BorderLayout());
		emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

		JLabel emptyLabel = new JLabel("<html><center><b style='color: #ff9900;'>Not Logged In</b><br><br>Please log in to your<br>OSRS account to see<br>group members.</center></html>");
		emptyLabel.setFont(FontManager.getRunescapeFont());
		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);

		emptyPanel.add(emptyLabel, BorderLayout.CENTER);
		memberListPanel.add(emptyPanel);

		memberListPanel.revalidate();
		memberListPanel.repaint();
	}

	/**
	 * Show no group message
	 */
	private void showNoGroupMessage()
	{
		memberListPanel.removeAll();
		allMemberRows.clear();

		JPanel emptyPanel = new JPanel(new BorderLayout());
		emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

		JLabel emptyLabel = new JLabel("<html><center><b>No Active Group</b><br><br>Create or join a group<br>to see group members</center></html>");
		emptyLabel.setFont(FontManager.getRunescapeFont());
		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);

		emptyPanel.add(emptyLabel, BorderLayout.CENTER);
		memberListPanel.add(emptyPanel);

		memberListPanel.revalidate();
		memberListPanel.repaint();
	}

	/**
	 * Show empty members message
	 */
	private void showEmptyMessage()
	{
		JPanel emptyPanel = new JPanel(new BorderLayout());
		emptyPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		emptyPanel.setBorder(new EmptyBorder(40, 20, 40, 20));

		JLabel emptyLabel = new JLabel("<html><center>No members in this group<br><br>Invite members using<br>the Settings tab</center></html>");
		emptyLabel.setFont(FontManager.getRunescapeFont());
		emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		emptyLabel.setHorizontalAlignment(SwingConstants.CENTER);

		emptyPanel.add(emptyLabel, BorderLayout.CENTER);
		memberListPanel.add(emptyPanel);
	}
}
