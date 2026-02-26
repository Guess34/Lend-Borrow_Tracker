package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.GroupService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Collection;

/**
 * Persistent navigation header showing account info, group dropdown, and Create/Join buttons.
 */
@Slf4j
public class GroupControlPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final GroupService groupService;
	private final EventBus eventBus;
	private final JLabel accountLabel;
	private final JComboBox<GroupItem> groupDropdown;
	private final JButton createGroupButton;
	private final JButton joinGroupButton;
	private boolean isUpdating = false;
	private volatile String cachedAccountName = null;

	public GroupControlPanel(LendingTrackerPlugin plugin, EventBus eventBus)
	{
		this.plugin = plugin;
		this.groupService = plugin.getGroupService();
		this.eventBus = eventBus;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 10, 8, 10));

		accountLabel = new JLabel("Not logged in");
		accountLabel.setFont(FontManager.getRunescapeSmallFont());
		accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		accountLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		accountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(accountLabel);
		add(Box.createVerticalStrut(4));

		JPanel groupRow = new JPanel(new BorderLayout(5, 0));
		groupRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		JLabel groupLabel = new JLabel("Group:");
		groupLabel.setFont(FontManager.getRunescapeSmallFont());
		groupLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		groupLabel.setPreferredSize(new Dimension(55, 25));
		groupRow.add(groupLabel, BorderLayout.WEST);

		groupDropdown = new JComboBox<>();
		groupDropdown.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupDropdown.setForeground(Color.WHITE);
		groupDropdown.setFont(FontManager.getRunescapeSmallFont());
		groupDropdown.setFocusable(false);
		groupDropdown.setRenderer(new GroupItemRenderer());
		groupDropdown.addActionListener(e -> onGroupChanged());
		groupRow.add(groupDropdown, BorderLayout.CENTER);
		add(groupRow);
		add(Box.createVerticalStrut(8));

		JPanel buttonRow = new JPanel(new GridLayout(1, 2, 10, 0));
		buttonRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		createGroupButton = makeButton("Create", ColorScheme.BRAND_ORANGE, "Create a new group");
		createGroupButton.addActionListener(e -> showCreateGroupDialog());
		buttonRow.add(createGroupButton);
		joinGroupButton = makeButton("Join", ColorScheme.GRAND_EXCHANGE_PRICE, "Join an existing group");
		joinGroupButton.addActionListener(e -> showJoinGroupDialog());
		buttonRow.add(joinGroupButton);
		add(buttonRow);

		refresh();
	}

	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			isUpdating = true;
			try
			{
				String currentAccount = getCurrentAccount();
				boolean loggedIn = currentAccount != null
					&& !currentAccount.equals("Not logged in")
					&& !currentAccount.equals("Loading...");

				accountLabel.setText(loggedIn ? ("\u2022 " + currentAccount) : "Not logged in");
				accountLabel.setForeground(loggedIn ? new Color(0, 200, 83) : ColorScheme.LIGHT_GRAY_COLOR);
				groupDropdown.removeAllItems();
				groupDropdown.setEnabled(loggedIn);
				createGroupButton.setEnabled(loggedIn);
				joinGroupButton.setEnabled(loggedIn);

				if (!loggedIn)
				{
					Collection<LendingGroup> groups = groupService.getAllGroups();
					if (groups.isEmpty())
					{
						groupDropdown.addItem(new GroupItem(null, "Log in to see groups"));
					}
					else
					{
						addGroupItems(groups);
						selectGroupById(groupService.getCurrentGroupIdUnchecked());
					}
				}
				else
				{
					Collection<LendingGroup> groups = groupService.getAllGroups();
					if (groups.isEmpty() && !groupService.hasCurrentAccount())
					{
						log.warn("No groups and no current account - triggering onAccountLogin for: {}", currentAccount);
						groupService.onAccountLogin(currentAccount);
						groups = groupService.getAllGroups();
					}
					if (groups.isEmpty())
					{
						groupDropdown.addItem(new GroupItem(null, "No groups - Create one!"));
					}
					else
					{
						addGroupItems(groups);
						LendingGroup active = groupService.getActiveGroupUnchecked();
						if (active != null)
						{
							selectGroupById(active.getId());
						}
					}
				}
			}
			finally
			{
				isUpdating = false;
			}
		});
	}

	public void setCurrentAccount(String accountName)
	{
		this.cachedAccountName = accountName;
	}

	public void clearCurrentAccount()
	{
		this.cachedAccountName = null;
	}

	private void addGroupItems(Collection<LendingGroup> groups)
	{
		for (LendingGroup g : groups)
		{
			groupDropdown.addItem(new GroupItem(g.getId(), g.getName()));
		}
	}

	private void selectGroupById(String targetId)
	{
		if (targetId == null) return;
		for (int i = 0; i < groupDropdown.getItemCount(); i++)
		{
			if (targetId.equals(groupDropdown.getItemAt(i).groupId))
			{
				groupDropdown.setSelectedIndex(i);
				return;
			}
		}
	}

	private String getCurrentAccount()
	{
		if (cachedAccountName != null && !cachedAccountName.isEmpty())
		{
			return cachedAccountName;
		}
		try
		{
			if (plugin.getClient() != null)
			{
				net.runelite.api.GameState gs = plugin.getClient().getGameState();
				if (gs == net.runelite.api.GameState.LOGGED_IN)
				{
					net.runelite.api.Player lp = plugin.getClient().getLocalPlayer();
					if (lp != null && lp.getName() != null && !lp.getName().isEmpty())
					{
						cachedAccountName = lp.getName();
						return cachedAccountName;
					}
					return "Loading...";
				}
			}
			if (plugin.getConfigManager() != null)
			{
				String stored = plugin.getConfigManager().getConfiguration("lendingtracker", "currentAccount");
				if (stored != null && !stored.isEmpty()) return stored;
			}
		}
		catch (Exception e)
		{
			log.debug("Could not get current account", e);
		}
		return "Not logged in";
	}

	private void onGroupChanged()
	{
		if (isUpdating) return;
		GroupItem selected = (GroupItem) groupDropdown.getSelectedItem();
		if (selected != null && selected.groupId != null)
		{
			groupService.setCurrentGroupId(selected.groupId);
			eventBus.post(new GroupChangedEvent(selected.groupId));
		}
	}

	private void showCreateGroupDialog()
	{
		String name = JOptionPane.showInputDialog(this, "Enter group name:", "Create Group",
			JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.trim().isEmpty()) return;

		try
		{
			String groupId = groupService.createGroup(name.trim(),
				"Created via Lending Tracker", getCurrentAccount());
			if (groupId == null)
			{
				showError("A group with that name already exists!");
				return;
			}
			refresh();
			eventBus.post(new GroupChangedEvent(groupId));
			JOptionPane.showMessageDialog(this,
				String.format("Group '%s' created! You are now the owner.\n\n"
					+ "Invite members from group settings or right-click CC members.", name.trim()),
				"Group Created", JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception e)
		{
			log.error("Failed to create group", e);
			showError("Failed to create group: " + e.getMessage());
		}
	}

	private void showJoinGroupDialog()
	{
		String playerName = getCurrentAccount();
		if (playerName == null || playerName.equals("Not logged in"))
		{
			JOptionPane.showMessageDialog(this, "You must be logged in to join a group.",
				"Not Logged In", JOptionPane.WARNING_MESSAGE);
			return;
		}

		String[] opts = {"Switch to Group", "Use Invite Code", "Cancel"};
		int choice = JOptionPane.showOptionDialog(this,
			"Switch to Group: enter name of group you're already in\n"
				+ "Use Invite Code: enter code to join a new group",
			"Join Group", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
			null, opts, opts[0]);

		if (choice == 0) handleSwitchToGroup(playerName);
		else if (choice == 1) handleJoinWithCode(playerName);
	}

	private void handleSwitchToGroup(String playerName)
	{
		String name = JOptionPane.showInputDialog(this,
			"Enter exact group name (case-sensitive):", "Switch to Group", JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.trim().isEmpty()) return;

		try
		{
			String result = groupService.switchToGroup(name.trim(), playerName);
			switch (result)
			{
				case "success":
					refresh();
					eventBus.post(new GroupChangedEvent(groupService.getCurrentGroupId()));
					JOptionPane.showMessageDialog(this,
						String.format("Switched to group '%s'!", name.trim()),
						"Success", JOptionPane.INFORMATION_MESSAGE);
					break;
				case "not_member":
					showError("You are not a member of '" + name.trim()
						+ "'. You need an invite code from a group admin.");
					break;
				case "not_found":
					showError("Group '" + name.trim() + "' not found. Names are case-sensitive.");
					break;
			}
		}
		catch (Exception e)
		{
			log.error("Failed to switch group", e);
			showError("Failed to switch group: " + e.getMessage());
		}
	}

	private void handleJoinWithCode(String playerName)
	{
		String code = JOptionPane.showInputDialog(this,
			"Enter invite code:", "Join with Invite Code", JOptionPane.PLAIN_MESSAGE);
		if (code == null || code.trim().isEmpty()) return;

		try
		{
			String groupId = groupService.useInviteCode(code.trim(), playerName);
			if (groupId != null)
			{
				refresh();
				eventBus.post(new GroupChangedEvent(groupId));
				JOptionPane.showMessageDialog(this,
					String.format("Successfully joined group '%s'!",
						groupService.getGroupNameById(groupId)),
					"Joined Group", JOptionPane.INFORMATION_MESSAGE);
			}
			else
			{
				showError("Invalid or expired invite code. Ask a group admin for a new one.");
			}
		}
		catch (Exception e)
		{
			log.error("Failed to join with invite code", e);
			showError("Failed to join group: " + e.getMessage());
		}
	}

	private void showError(String message)
	{
		JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
	}

	private static JButton makeButton(String text, Color bg, String tooltip)
	{
		JButton btn = new JButton(text);
		btn.setFont(FontManager.getRunescapeSmallFont());
		btn.setBackground(bg);
		btn.setForeground(Color.WHITE);
		btn.setFocusPainted(false);
		btn.setBorderPainted(false);
		btn.setToolTipText(tooltip);
		return btn;
	}

	private static class GroupItem
	{
		final String groupId;
		final String groupName;

		GroupItem(String groupId, String groupName)
		{
			this.groupId = groupId;
			this.groupName = groupName;
		}

		@Override
		public String toString() { return groupName; }
	}

	private static class GroupItemRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value,
			int index, boolean isSelected, boolean cellHasFocus)
		{
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			setFont(FontManager.getRunescapeSmallFont());
			setForeground(Color.WHITE);
			setBackground(isSelected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
			return this;
		}
	}

	public static class GroupChangedEvent
	{
		private final String groupId;

		public GroupChangedEvent(String groupId) { this.groupId = groupId; }

		public String getGroupId() { return groupId; }
	}
}
