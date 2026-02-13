package net.runelite.client.plugins.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.plugins.lendingtracker.services.Recorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * GroupControlPanel - Persistent navigation header for group and account selection
 * Phase 3: Allows users to switch between groups and accounts
 */
@Slf4j
public class GroupControlPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final GroupConfigStore groupConfigStore;
	private final Recorder recorder;
	private final EventBus eventBus;

	// CHANGED: Replaced account dropdown with compact label (only shows logged-in user)
	private final JLabel accountLabel;
	private final JComboBox<GroupItem> groupDropdown;
	private final JButton createGroupButton;
	private final JButton joinGroupButton;

	// FIXED: Flag to prevent infinite loop during programmatic updates
	private boolean isUpdating = false;

	// FIXED: Store current account name set from plugin (thread-safe)
	// This is set by the plugin when login is detected on the client thread
	private volatile String cachedAccountName = null;

	public GroupControlPanel(LendingTrackerPlugin plugin, EventBus eventBus)
	{
		this.plugin = plugin;
		this.groupConfigStore = plugin.getGroupConfigStore();
		this.recorder = plugin.getRecorder();
		this.eventBus = eventBus;

		// FIXED: Use BoxLayout for vertical stacking - cleaner layout
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 10, 8, 10));

		// CHANGED: Compact account label instead of dropdown (saves ~33px vertical space)
		accountLabel = new JLabel("Not logged in");
		accountLabel.setFont(FontManager.getRunescapeSmallFont());
		accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		accountLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		accountLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(accountLabel);
		add(Box.createVerticalStrut(4));

		// Row 2: Group dropdown with label
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

		// Row 3: Create and Join buttons side by side
		JPanel buttonRow = new JPanel(new GridLayout(1, 2, 10, 0));
		buttonRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		createGroupButton = new JButton("Create");
		createGroupButton.setFont(FontManager.getRunescapeSmallFont());
		createGroupButton.setBackground(ColorScheme.BRAND_ORANGE);
		createGroupButton.setForeground(Color.WHITE);
		createGroupButton.setFocusPainted(false);
		createGroupButton.setBorderPainted(false);
		createGroupButton.setToolTipText("Create a new group");
		createGroupButton.addActionListener(e -> showCreateGroupDialog());
		buttonRow.add(createGroupButton);

		joinGroupButton = new JButton("Join");
		joinGroupButton.setFont(FontManager.getRunescapeSmallFont());
		joinGroupButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
		joinGroupButton.setForeground(Color.WHITE);
		joinGroupButton.setFocusPainted(false);
		joinGroupButton.setBorderPainted(false);
		joinGroupButton.setToolTipText("Join an existing group");
		joinGroupButton.addActionListener(e -> showJoinGroupDialog());
		buttonRow.add(joinGroupButton);

		add(buttonRow);

		// Initial load
		refresh();
	}

	/**
	 * Refresh the dropdowns with current data
	 * FIXED: Now properly disables group controls when not logged in
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// FIXED: Set flag to prevent listener from firing during update
			isUpdating = true;

			try
			{
				// CHANGED: Update compact account label instead of dropdown
				String currentAccount = getCurrentAccount();
				boolean isLoggedIn = currentAccount != null &&
					!currentAccount.equals("Not logged in") &&
					!currentAccount.equals("Loading...");

				log.info("Refreshing account label - current user: {}, logged in: {}", currentAccount, isLoggedIn);

				if (isLoggedIn)
				{
					accountLabel.setText("\u2022 " + currentAccount);
					accountLabel.setForeground(new Color(0, 200, 83));
				}
				else
				{
					accountLabel.setText("Not logged in");
					accountLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				}

				// FIXED: Clear active group when not logged in
				groupDropdown.removeAllItems();

				if (!isLoggedIn)
				{
					// Not logged in - show groups but disable interaction
					// CHANGED: Show groups in dropdown even when logged out for reference
					java.util.Collection<LendingGroup> allGroups = groupConfigStore.getAllGroups();
					// FIXED: Get preserved active group ID to show correct selection when logged out
					String preservedActiveGroupId = groupConfigStore.getCurrentGroupIdUnchecked();

					if (allGroups.isEmpty())
					{
						groupDropdown.addItem(new GroupItem(null, "Log in to see groups"));
					}
					else
					{
						// Show groups in disabled state, but try to select the preserved active group
						for (LendingGroup group : allGroups)
						{
							groupDropdown.addItem(new GroupItem(group.getId(), group.getName()));
						}

						// FIXED: Select the preserved active group for visual consistency
						if (preservedActiveGroupId != null)
						{
							for (int i = 0; i < groupDropdown.getItemCount(); i++)
							{
								GroupItem item = groupDropdown.getItemAt(i);
								if (item.groupId != null && item.groupId.equals(preservedActiveGroupId))
								{
									groupDropdown.setSelectedIndex(i);
									log.info("Logged out - preserved selection: {}", item.groupName);
									break;
								}
							}
						}
					}

					groupDropdown.setEnabled(false);
					createGroupButton.setEnabled(false);
					joinGroupButton.setEnabled(false);

					log.info("User not logged in - showing {} groups (disabled), preserved active: {}",
						allGroups.size(), preservedActiveGroupId);
				}
				else
				{
					// Logged in - enable controls and load groups
					groupDropdown.setEnabled(true);
					createGroupButton.setEnabled(true);
					joinGroupButton.setEnabled(true);

					// FIXED: Ensure groups are loaded for this account
					// This is a safety net in case onAccountLogin wasn't called properly on startup
					java.util.Collection<LendingGroup> allGroups = groupConfigStore.getAllGroups();
					if (allGroups.isEmpty() && groupConfigStore.hasCurrentAccount() == false)
					{
						// Groups not loaded and no current account - trigger account login
						log.warn("Groups empty and no current account set - triggering onAccountLogin for: {}", currentAccount);
						groupConfigStore.onAccountLogin(currentAccount);
						allGroups = groupConfigStore.getAllGroups();
						log.info("After fallback onAccountLogin: {} groups loaded", allGroups.size());
					}

					java.util.List<LendingGroup> groups = new java.util.ArrayList<>(allGroups);
					// FIXED: Use unchecked version since we've verified login status
					LendingGroup activeGroup = groupConfigStore.getActiveGroupUnchecked();

					log.info("Refreshing group dropdown - found {} groups, active: {}",
						groups.size(), activeGroup != null ? activeGroup.getName() : "none");

					if (groups.isEmpty())
					{
						groupDropdown.addItem(new GroupItem(null, "No groups - Create one!"));
						log.info("No groups found - showing placeholder");
					}
					else
					{
						for (LendingGroup group : groups)
						{
							groupDropdown.addItem(new GroupItem(group.getId(), group.getName()));
							log.info("Added group to dropdown: {} ({})", group.getName(), group.getId());
						}

						// Select active group
						if (activeGroup != null)
						{
							for (int i = 0; i < groupDropdown.getItemCount(); i++)
							{
								GroupItem item = groupDropdown.getItemAt(i);
								if (item.groupId != null && item.groupId.equals(activeGroup.getId()))
								{
									groupDropdown.setSelectedIndex(i);
									log.info("Selected active group: {}", activeGroup.getName());
									break;
								}
							}
						}
					}
				}
			}
			finally
			{
				// FIXED: Always clear flag, even if exception occurs
				isUpdating = false;
			}
		});
	}

	/**
	 * Set the current account name directly from the plugin.
	 * Called when login is detected.
	 *
	 * @param accountName The logged-in player's name, or null if logged out
	 */
	public void setCurrentAccount(String accountName)
	{
		log.info("setCurrentAccount called with: {}", accountName);
		this.cachedAccountName = accountName;
	}

	/**
	 * Get current logged-in account name (player's display name)
	 * FIXED: Uses multiple fallback sources to reliably get account name.
	 */
	private String getCurrentAccount()
	{
		// First try: Use cached account name if available
		if (cachedAccountName != null && !cachedAccountName.isEmpty())
		{
			log.debug("Got account from cache: {}", cachedAccountName);
			return cachedAccountName;
		}

		try
		{
			// Second try: Get directly from local player (this works from any thread for reading)
			if (plugin.getClient() != null)
			{
				net.runelite.api.GameState gameState = plugin.getClient().getGameState();

				if (gameState == net.runelite.api.GameState.LOGGED_IN)
				{
					net.runelite.api.Player localPlayer = plugin.getClient().getLocalPlayer();
					if (localPlayer != null)
					{
						String playerName = localPlayer.getName();
						if (playerName != null && !playerName.isEmpty())
						{
							log.debug("Got account from local player: {}", playerName);
							// Cache it for next time
							cachedAccountName = playerName;
							return playerName;
						}
					}
					// Logged in but player not loaded yet
					log.debug("Game state is LOGGED_IN but player name not available yet");
					return "Loading...";
				}
			}

			// Third try: Get from stored config (set on login)
			if (plugin.getConfigManager() != null)
			{
				String storedAccount = plugin.getConfigManager().getConfiguration("lendingtracker", "currentAccount");
				if (storedAccount != null && !storedAccount.isEmpty())
				{
					log.debug("Got account from config: {}", storedAccount);
					return storedAccount;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Could not get current account", e);
		}
		return "Not logged in";
	}

	/**
	 * Clear the cached account name (called on logout)
	 */
	public void clearCurrentAccount()
	{
		log.info("clearCurrentAccount called - clearing cached account");
		this.cachedAccountName = null;
	}

	/**
	 * Handle group dropdown change
	 */
	private void onGroupChanged()
	{
		// FIXED: Ignore events triggered during programmatic updates
		if (isUpdating)
		{
			return;
		}

		GroupItem selected = (GroupItem) groupDropdown.getSelectedItem();
		if (selected != null && selected.groupId != null)
		{
			log.info("Group changed to: {} ({})", selected.groupName, selected.groupId);

			// FIXED: Actually set the current group ID so it persists
			groupConfigStore.setCurrentGroupId(selected.groupId);

			// Fire event to refresh tabs
			eventBus.post(new GroupChangedEvent(selected.groupId));
		}
	}

	// REMOVED: showAddGroupMenu() - no longer needed with separate Create/Join buttons

	/**
	 * Show dialog to create a new group
	 */
	private void showCreateGroupDialog()
	{
		String groupName = JOptionPane.showInputDialog(
			this,
			"Enter group name:",
			"Create Group",
			JOptionPane.PLAIN_MESSAGE
		);

		if (groupName != null && !groupName.trim().isEmpty())
		{
			try
			{
				// GroupConfigStore.createGroup requires: name, description, ownerName
				String description = "Created via Lending Tracker";
				String ownerName = getCurrentAccount();

				String groupId = groupConfigStore.createGroup(groupName.trim(), description, ownerName);

				if (groupId == null)
				{
					JOptionPane.showMessageDialog(
						this,
						"A group with that name already exists!",
						"Error",
						JOptionPane.ERROR_MESSAGE
					);
					return;
				}

				log.info("Created new group: {} (ID: {})", groupName, groupId);

				// Refresh UI - the new group is auto-selected
				refresh();

				// Fire event to refresh tabs
				eventBus.post(new GroupChangedEvent(groupId));

				// FIXED: Simple confirmation - no codes shown (codes generated on demand)
				JOptionPane.showMessageDialog(
					this,
					String.format("Group '%s' created!\n\nYou are now the owner of this group.\n\n" +
						"To invite members:\n" +
						"- Generate a single-use invite code from group settings\n" +
						"- Or right-click CC members to invite directly (staff only)",
						groupName.trim()),
					"Group Created",
					JOptionPane.INFORMATION_MESSAGE
				);
			}
			catch (Exception e)
			{
				log.error("Failed to create group", e);
				JOptionPane.showMessageDialog(
					this,
					"Failed to create group: " + e.getMessage(),
					"Error",
					JOptionPane.ERROR_MESSAGE
				);
			}
		}
	}

	/**
	 * Show dialog to join an existing group
	 * FIXED: Requires invite code to join new groups, or group name to switch to existing membership
	 */
	private void showJoinGroupDialog()
	{
		// Get current player name first
		String playerName = getCurrentAccount();
		if (playerName == null || playerName.equals("Not logged in"))
		{
			JOptionPane.showMessageDialog(
				this,
				"You must be logged in to join a group.",
				"Not Logged In",
				JOptionPane.WARNING_MESSAGE
			);
			return;
		}

		// Create dialog with two options: switch to existing group OR use invite code
		String[] options = {"Switch to Group", "Use Invite Code", "Cancel"};
		int choice = JOptionPane.showOptionDialog(
			this,
			"How would you like to join a group?\n\n" +
				"• Switch to Group: Enter name of a group you're already a member of\n" +
				"• Use Invite Code: Enter an invite code to join a new group",
			"Join Group",
			JOptionPane.DEFAULT_OPTION,
			JOptionPane.QUESTION_MESSAGE,
			null,
			options,
			options[0]
		);

		if (choice == 0) // Switch to existing group by name
		{
			String groupName = JOptionPane.showInputDialog(
				this,
				"Enter exact group name (case-sensitive):\n(You must already be a member of this group)",
				"Switch to Group",
				JOptionPane.PLAIN_MESSAGE
			);

			if (groupName != null && !groupName.trim().isEmpty())
			{
				try
				{
					log.info("Attempting to switch to group: '{}' as player: {}", groupName.trim(), playerName);
					String result = groupConfigStore.switchToGroup(groupName.trim(), playerName);

					switch (result)
					{
						case "success":
							log.info("Successfully switched to group: {}", groupName.trim());
							refresh();
							eventBus.post(new GroupChangedEvent(groupConfigStore.getCurrentGroupId()));
							JOptionPane.showMessageDialog(
								this,
								String.format("Switched to group '%s'!", groupName.trim()),
								"Success",
								JOptionPane.INFORMATION_MESSAGE
							);
							break;

						case "not_member":
							JOptionPane.showMessageDialog(
								this,
								String.format("You are not a member of '%s'.\n\nTo join this group, you need an invite code from a group admin.", groupName.trim()),
								"Not a Member",
								JOptionPane.WARNING_MESSAGE
							);
							break;

						case "not_found":
							JOptionPane.showMessageDialog(
								this,
								String.format("Group '%s' not found.\n\nGroup names are case-sensitive.\nMake sure you typed the exact name.", groupName.trim()),
								"Group Not Found",
								JOptionPane.ERROR_MESSAGE
							);
							break;
					}
				}
				catch (Exception e)
				{
					log.error("Failed to switch group", e);
					JOptionPane.showMessageDialog(
						this,
						"Failed to switch group: " + e.getMessage(),
						"Error",
						JOptionPane.ERROR_MESSAGE
					);
				}
			}
		}
		else if (choice == 1) // Use invite code
		{
			String inviteCode = JOptionPane.showInputDialog(
				this,
				"Enter invite code:",
				"Join with Invite Code",
				JOptionPane.PLAIN_MESSAGE
			);

			if (inviteCode != null && !inviteCode.trim().isEmpty())
			{
				try
				{
					log.info("Attempting to join with invite code: {} as player: {}", inviteCode.trim(), playerName);

					// Try to use the invite code
					String groupId = groupConfigStore.useInviteCode(inviteCode.trim(), playerName);

					if (groupId != null)
					{
						String groupName = groupConfigStore.getGroupNameById(groupId);
						log.info("Successfully joined group '{}' with invite code", groupName);
						refresh();
						eventBus.post(new GroupChangedEvent(groupId));
						JOptionPane.showMessageDialog(
							this,
							String.format("Successfully joined group '%s'!", groupName),
							"Joined Group",
							JOptionPane.INFORMATION_MESSAGE
						);
					}
					else
					{
						JOptionPane.showMessageDialog(
							this,
							"Invalid or expired invite code.\n\nInvite codes are single-use and case-sensitive.\nAsk a group admin for a new code.",
							"Invalid Code",
							JOptionPane.ERROR_MESSAGE
						);
					}
				}
				catch (Exception e)
				{
					log.error("Failed to join with invite code", e);
					JOptionPane.showMessageDialog(
						this,
						"Failed to join group: " + e.getMessage(),
						"Error",
						JOptionPane.ERROR_MESSAGE
					);
				}
			}
		}
	}

	/**
	 * Group item wrapper for dropdown
	 */
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
		public String toString()
		{
			return groupName;
		}
	}

	/**
	 * Custom renderer for group dropdown
	 */
	private static class GroupItemRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value,
			int index, boolean isSelected, boolean cellHasFocus)
		{
			Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

			if (c instanceof JLabel && value instanceof GroupItem)
			{
				JLabel label = (JLabel) c;
				GroupItem item = (GroupItem) value;
				label.setText(item.groupName);
				label.setFont(FontManager.getRunescapeSmallFont());

				if (isSelected)
				{
					label.setBackground(ColorScheme.BRAND_ORANGE);
					label.setForeground(Color.WHITE);
				}
				else
				{
					label.setBackground(ColorScheme.DARKER_GRAY_COLOR);
					label.setForeground(Color.WHITE);
				}
			}

			return c;
		}
	}

	/**
	 * Event fired when group selection changes
	 */
	public static class GroupChangedEvent
	{
		private final String groupId;

		public GroupChangedEvent(String groupId)
		{
			this.groupId = groupId;
		}

		public String getGroupId()
		{
			return groupId;
		}
	}
}
