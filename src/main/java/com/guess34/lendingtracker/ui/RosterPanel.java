package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Friend;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.GroupService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class RosterPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final GroupService groupService;

	private final JLabel headerLabel;
	private final IconTextField searchBar;
	private final JPanel memberListPanel;
	private final List<JPanel> allMemberRows = new ArrayList<>();

	public RosterPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.groupService = plugin.getGroupService();

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

	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
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

			if (!isLoggedIn)
			{
				headerLabel.setText("Group Roster");
				showMessage("<html><center><b style='color: #ff9900;'>Not Logged In</b><br><br>Please log in to your<br>OSRS account to see<br>group members.</center></html>");
				return;
			}

			LendingGroup activeGroup = groupService.getActiveGroup();
			if (activeGroup == null)
			{
				headerLabel.setText("No Group Selected");
				showMessage("<html><center><b>No Active Group</b><br><br>Create or join a group<br>to see group members</center></html>");
				return;
			}

			// Update header with group name
			headerLabel.setText(activeGroup.getName() + " Roster");

			// Get online players for status checking (maps lowercase name -> world number)
			Map<String, Integer> onlinePlayers = getOnlinePlayers();

			// Get group members
			List<GroupMember> members = activeGroup.getMembers();

			// Clear and rebuild member list
			memberListPanel.removeAll();
			allMemberRows.clear();

			if (members == null || members.isEmpty())
			{
				showMessage("<html><center>No members in this group<br><br>Invite members using<br>the Settings tab</center></html>");
			}
			else
			{
				// Sort members: online first, then by role rank, then alphabetically
				List<GroupMember> sortedMembers = new ArrayList<>(members);
				sortedMembers.sort((a, b) -> {
					boolean aOnline = onlinePlayers.containsKey(a.getName().toLowerCase());
					boolean bOnline = onlinePlayers.containsKey(b.getName().toLowerCase());

					if (aOnline != bOnline) return aOnline ? -1 : 1;

					int aRank = GroupService.getRoleRank(a.getRole());
					int bRank = GroupService.getRoleRank(b.getRole());
					if (aRank != bRank) return bRank - aRank;

					return a.getName().compareToIgnoreCase(b.getName());
				});

				for (GroupMember member : sortedMembers)
				{
					boolean isOnline = onlinePlayers.containsKey(member.getName().toLowerCase());
					Integer worldVal = onlinePlayers.get(member.getName().toLowerCase());
					int world = worldVal != null ? worldVal : 0;
					JPanel memberRow = createMemberRow(member, isOnline, world);
					allMemberRows.add(memberRow);
					memberListPanel.add(memberRow);
					memberListPanel.add(Box.createVerticalStrut(2));
				}
			}

			memberListPanel.revalidate();
			memberListPanel.repaint();
		});
	}

	private JPanel createMemberRow(GroupMember member, boolean isOnline, int world)
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

		// Get world number for online players from friends list
		String statusTooltip = "Offline";
		String worldText = "";
		if (isOnline)
		{
			if (world > 0)
			{
				worldText = " (W" + world + ")";
				statusTooltip = "Online - World " + world;
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
		JLabel roleLabel = new JLabel(GroupService.formatRoleName(role));
		roleLabel.setFont(FontManager.getRunescapeSmallFont());
		roleLabel.setOpaque(true);
		roleLabel.setBorder(new EmptyBorder(2, 6, 2, 6));
		roleLabel.setBackground(GroupService.getRoleBackgroundColor(role));
		roleLabel.setForeground(GroupService.getRoleForegroundColor(role));

		row.add(roleLabel, BorderLayout.EAST);

		// Store the member name for filtering
		row.putClientProperty("memberName", member.getName().toLowerCase());

		// Right-click context menu
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
					showMemberPopup(e.getComponent(), e.getX(), e.getY(), member, isOnline, world);
				}
			}
		});

		return row;
	}

	private Map<String, Integer> getOnlinePlayers()
	{
		Map<String, Integer> online = new HashMap<>();

		try
		{
			// Check Friends Chat members (world not available from FriendsChatMember)
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
							online.putIfAbsent(m.getName().toLowerCase(), 0);
						}
					}
				}
			}

			// Check friends list for online status (includes world number)
			Friend[] friends = plugin.getClient().getFriendContainer().getMembers();
			if (friends != null)
			{
				for (Friend f : friends)
				{
					if (f != null && f.getName() != null && f.getWorld() > 0)
					{
						online.put(f.getName().toLowerCase(), f.getWorld());
					}
				}
			}

			// Current player is always "online" on their current world
			if (plugin.getClient().getLocalPlayer() != null)
			{
				String localName = plugin.getClient().getLocalPlayer().getName();
				if (localName != null)
				{
					int currentWorld = plugin.getClient().getWorld();
					online.put(localName.toLowerCase(), currentWorld);
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Error getting online players", e);
		}

		return online;
	}

	private void showMemberPopup(Component component, int x, int y, GroupMember member, boolean isOnline, int world)
	{
		JPopupMenu popup = new JPopupMenu();

		// View Activity
		JMenuItem viewActivity = new JMenuItem("View Activity");
		viewActivity.addActionListener(e ->
		{
			JOptionPane.showMessageDialog(this,
				"Activity history for " + member.getName() + "\n(Switch to Log tab for full history)",
				"Player Activity",
				JOptionPane.INFORMATION_MESSAGE);
		});
		popup.add(viewActivity);

		// Show world info for online players (informational, not actionable)
		if (isOnline && world > 0)
		{
			JMenuItem worldInfo = new JMenuItem("World " + world);
			worldInfo.setEnabled(false);
			popup.add(worldInfo);
		}

		popup.show(component, x, y);
	}

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

	private void clearSearch()
	{
		searchBar.setText("");
		filterMembers("");
	}

	private void showMessage(String html)
	{
		memberListPanel.removeAll();
		allMemberRows.clear();

		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(40, 20, 40, 20));

		JLabel label = new JLabel(html);
		label.setFont(FontManager.getRunescapeFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setHorizontalAlignment(SwingConstants.CENTER);

		panel.add(label, BorderLayout.CENTER);
		memberListPanel.add(panel);

		memberListPanel.revalidate();
		memberListPanel.repaint();
	}
}
