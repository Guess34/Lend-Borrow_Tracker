package com.guess34.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.GroupService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.List;

@Slf4j
public class SettingsPanel extends JPanel
{
	private static final Color DANGER_BG = new Color(60, 30, 30);
	private static final Color DANGER_BORDER = new Color(180, 50, 50);
	private static final Color DANGER_TEXT = new Color(255, 100, 100);

	private final LendingTrackerPlugin plugin;
	private final GroupService groupService;

	private JLabel groupNameLabel, memberCountLabel, roleLabel, inviteCodeLabel;
	private JButton generateCodeButton, copyCodeButton;
	private JButton deleteGroupButton, leaveGroupButton, transferOwnershipButton;
	private JPanel membersListPanel, permissionsPanel, dangerZonePanel;
	private JPanel contentPanel, notLoggedInPanel, inviteCodePanel, membersSection;
	private JCheckBox coOwnerKickCb, adminKickCb, modKickCb;
	private JCheckBox coOwnerInviteCb, adminInviteCb, modInviteCb;
	private JComboBox<String> transferOwnerDropdown;
	private JScrollPane scrollPane;

	public SettingsPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.groupService = plugin.getGroupService();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		notLoggedInPanel = buildNotLoggedInPanel();

		// Content panel tracks viewport width to prevent horizontal overflow
		contentPanel = new JPanel()
		{
			@Override
			public Dimension getPreferredSize()
			{
				Dimension d = super.getPreferredSize();
				// Report width 0 so JScrollPane uses viewport width instead of content width
				// This prevents wide HTML labels from overflowing the right edge
				return new Dimension(0, d.height);
			}
		};
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

		contentPanel.add(buildGroupInfoSection());
		contentPanel.add(Box.createVerticalStrut(10));
		membersSection = buildMembersSection();
		contentPanel.add(membersSection);
		contentPanel.add(Box.createVerticalStrut(10));
		inviteCodePanel = buildInviteCodeSection();
		contentPanel.add(inviteCodePanel);
		contentPanel.add(Box.createVerticalStrut(10));
		contentPanel.add(buildScreenshotSection());
		contentPanel.add(Box.createVerticalStrut(10));
		contentPanel.add(buildFeedbackSection());
		contentPanel.add(Box.createVerticalStrut(10));
		contentPanel.add(buildPermissionsSection());
		contentPanel.add(Box.createVerticalStrut(10));
		contentPanel.add(buildDangerZoneSection());

		// Scroll pane wraps content directly (same pattern as RosterPanel)
		scrollPane = new JScrollPane(contentPanel);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(notLoggedInPanel, BorderLayout.CENTER);
	}

	// ---- Section Builders ----

	private JPanel buildNotLoggedInPanel()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(new EmptyBorder(50, 20, 50, 20));
		JLabel lbl = new JLabel("<html><center><b style='color:#ff9900;font-size:14px;'>Not Logged In</b>" +
			"<br><br>Log in to access group settings.</center></html>");
		lbl.setFont(FontManager.getRunescapeFont());
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lbl.setHorizontalAlignment(SwingConstants.CENTER);
		p.add(lbl, BorderLayout.CENTER);
		return p;
	}

	private JPanel buildGroupInfoSection()
	{
		JPanel section = sectionPanel("Active Group");
		JPanel grid = new JPanel(new GridLayout(3, 2, 5, 5));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.add(label("Name:", false));
		groupNameLabel = label("No active group", true);
		grid.add(groupNameLabel);
		grid.add(label("Members:", false));
		memberCountLabel = label("0", true);
		grid.add(memberCountLabel);
		grid.add(label("Your Role:", false));
		roleLabel = label("-", true);
		grid.add(roleLabel);
		section.add(grid, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildInviteCodeSection()
	{
		JPanel section = sectionPanel("Invite Code");
		JPanel content = boxPanel();

		content.add(smallLabel("Single-use code (void after 1 join)"));
		content.add(Box.createVerticalStrut(5));

		inviteCodeLabel = new JLabel("No active code");
		inviteCodeLabel.setFont(FontManager.getRunescapeFont());
		inviteCodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		inviteCodeLabel.setAlignmentX(LEFT_ALIGNMENT);
		inviteCodeLabel.setBorder(new CompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1), new EmptyBorder(5, 8, 5, 8)));
		content.add(inviteCodeLabel);
		content.add(Box.createVerticalStrut(5));

		JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		btns.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		btns.setAlignmentX(LEFT_ALIGNMENT);
		generateCodeButton = smallButton("Generate", ColorScheme.BRAND_ORANGE);
		generateCodeButton.addActionListener(e -> generateInviteCode());
		btns.add(generateCodeButton);
		copyCodeButton = smallButton("Copy", ColorScheme.DARK_GRAY_COLOR);
		copyCodeButton.setEnabled(false);
		copyCodeButton.addActionListener(e -> copyInviteCode());
		btns.add(copyCodeButton);
		content.add(btns);

		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildScreenshotSection()
	{
		JPanel section = sectionPanel("Screenshots");
		JPanel content = boxPanel();
		content.add(smallLabel("Proof screenshots are saved locally:"));
		content.add(Box.createVerticalStrut(5));
		JButton btn = smallButton("Open Screenshot Folder", ColorScheme.BRAND_ORANGE);
		btn.setAlignmentX(LEFT_ALIGNMENT);
		btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		btn.addActionListener(e -> {
			LendingGroup g = groupService.getActiveGroup();
			plugin.getProofScreenshot().openScreenshotFolder(
				getCurrentUsername(), g != null ? g.getName() : "default");
		});
		content.add(btn);
		content.add(Box.createVerticalStrut(3));
		content.add(smallLabel("<i>~/.runelite/lending-tracker/proof/</i>"));
		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildFeedbackSection()
	{
		JPanel section = sectionPanel("Feedback");
		JPanel content = boxPanel();
		content.add(smallLabel("Found a bug or have a suggestion?"));
		content.add(Box.createVerticalStrut(5));
		JButton btn = smallButton("Report Issue on GitHub", ColorScheme.BRAND_ORANGE);
		btn.setAlignmentX(LEFT_ALIGNMENT);
		btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		btn.addActionListener(e -> {
			try
			{
				Desktop.getDesktop().browse(new URI("https://github.com/Guess34/Lend-Borrow_Tracker/issues"));
			}
			catch (Exception ex)
			{
				log.warn("Failed to open browser", ex);
				JOptionPane.showMessageDialog(this,
					"Could not open browser.\nVisit: github.com/Guess34/Lend-Borrow_Tracker/issues",
					"Open Failed", JOptionPane.WARNING_MESSAGE);
			}
		});
		content.add(btn);
		section.add(content, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildPermissionsSection()
	{
		permissionsPanel = sectionPanel("Permissions");
		JPanel content = boxPanel();

		content.add(smallLabel("Who can kick:"));
		content.add(Box.createVerticalStrut(4));
		coOwnerKickCb = permCheckbox("Co-Owners", true, "kick", "co-owner");
		content.add(coOwnerKickCb);
		adminKickCb = permCheckbox("Admins", true, "kick", "admin");
		content.add(adminKickCb);
		modKickCb = permCheckbox("Mods", true, "kick", "mod");
		content.add(modKickCb);
		content.add(Box.createVerticalStrut(3));
		content.add(smallLabel("<i>Lower ranks only</i>"));
		content.add(Box.createVerticalStrut(8));

		content.add(smallLabel("Who can generate invite codes:"));
		content.add(Box.createVerticalStrut(4));
		coOwnerInviteCb = permCheckbox("Co-Owners", true, "invite", "co-owner");
		content.add(coOwnerInviteCb);
		adminInviteCb = permCheckbox("Admins", true, "invite", "admin");
		content.add(adminInviteCb);
		modInviteCb = permCheckbox("Mods", false, "invite", "mod");
		content.add(modInviteCb);

		permissionsPanel.add(content, BorderLayout.CENTER);
		return permissionsPanel;
	}

	private JPanel buildMembersSection()
	{
		JPanel section = sectionPanel("Manage Members");
		JLabel note = new JLabel("<html><i>Full list on Roster tab. Lower-ranked members shown here.</i></html>");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setBorder(new EmptyBorder(0, 5, 5, 5));
		section.add(note, BorderLayout.NORTH);

		membersListPanel = new JPanel();
		membersListPanel.setLayout(new BoxLayout(membersListPanel, BoxLayout.Y_AXIS));
		membersListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		membersListPanel.add(label("No members", false));
		section.add(membersListPanel, BorderLayout.CENTER);
		return section;
	}

	private JPanel buildDangerZoneSection()
	{
		dangerZonePanel = new JPanel(new BorderLayout(3, 3))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		dangerZonePanel.setBackground(DANGER_BG);
		dangerZonePanel.setBorder(new CompoundBorder(
			new LineBorder(DANGER_BORDER, 1), new EmptyBorder(6, 6, 6, 6)));

		JLabel title = new JLabel("Danger Zone");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(DANGER_TEXT);
		dangerZonePanel.add(title, BorderLayout.NORTH);

		JPanel btns = new JPanel();
		btns.setLayout(new BoxLayout(btns, BoxLayout.Y_AXIS));
		btns.setBackground(DANGER_BG);

		leaveGroupButton = dangerButton("Leave Group", new Color(150, 80, 30));
		leaveGroupButton.addActionListener(e -> leaveGroup());
		btns.add(leaveGroupButton);
		btns.add(Box.createVerticalStrut(8));

		JSeparator sep = new JSeparator();
		sep.setForeground(DANGER_BORDER);
		sep.setBackground(DANGER_BG);
		sep.setAlignmentX(LEFT_ALIGNMENT);
		sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
		btns.add(sep);
		btns.add(Box.createVerticalStrut(5));

		JLabel ownerLbl = new JLabel("Owner Only:");
		ownerLbl.setFont(FontManager.getRunescapeSmallFont());
		ownerLbl.setForeground(new Color(255, 150, 150));
		ownerLbl.setAlignmentX(LEFT_ALIGNMENT);
		btns.add(ownerLbl);
		btns.add(Box.createVerticalStrut(5));

		JLabel transferLbl = new JLabel("Transfer Ownership:");
		transferLbl.setFont(FontManager.getRunescapeSmallFont());
		transferLbl.setForeground(new Color(255, 200, 150));
		transferLbl.setAlignmentX(LEFT_ALIGNMENT);
		btns.add(transferLbl);
		btns.add(Box.createVerticalStrut(3));

		transferOwnerDropdown = new JComboBox<>();
		transferOwnerDropdown.setFont(FontManager.getRunescapeSmallFont());
		transferOwnerDropdown.setAlignmentX(LEFT_ALIGNMENT);
		transferOwnerDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		btns.add(transferOwnerDropdown);
		btns.add(Box.createVerticalStrut(3));

		transferOwnershipButton = dangerButton("Transfer Ownership", new Color(180, 100, 50));
		transferOwnershipButton.addActionListener(e -> transferOwnership());
		btns.add(transferOwnershipButton);
		btns.add(Box.createVerticalStrut(5));

		deleteGroupButton = dangerButton("Delete Group", DANGER_BORDER);
		deleteGroupButton.addActionListener(e -> deleteGroup());
		btns.add(deleteGroupButton);
		btns.add(Box.createVerticalStrut(3));

		JLabel warn = new JLabel("<html><i>Deletes all data!</i></html>");
		warn.setFont(FontManager.getRunescapeSmallFont());
		warn.setForeground(new Color(255, 150, 150));
		warn.setAlignmentX(LEFT_ALIGNMENT);
		btns.add(warn);

		dangerZonePanel.add(btns, BorderLayout.CENTER);
		return dangerZonePanel;
	}

	// ---- Actions ----

	private void generateInviteCode()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) return;
		String user = getCurrentUsername();
		if (!groupService.canGenerateInviteCode(g.getId(), user))
		{
			JOptionPane.showMessageDialog(this, "You don't have permission to generate invite codes.",
				"Permission Denied", JOptionPane.WARNING_MESSAGE);
			return;
		}
		String code = groupService.generateSingleUseInviteCode(g.getId());
		if (code != null)
		{
			inviteCodeLabel.setText(code);
			inviteCodeLabel.setForeground(ColorScheme.BRAND_ORANGE);
			copyCodeButton.setEnabled(true);
		}
	}

	private void copyInviteCode()
	{
		String code = inviteCodeLabel.getText();
		if (code != null && !code.equals("No active code"))
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
			JOptionPane.showMessageDialog(this, "Invite code copied!", "Copied", JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void transferOwnership()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) return;
		String user = getCurrentUsername();
		if (!groupService.isOwner(g.getId(), user))
		{
			JOptionPane.showMessageDialog(this, "Only the owner can transfer ownership!",
				"Permission Denied", JOptionPane.ERROR_MESSAGE);
			return;
		}
		String target = (String) transferOwnerDropdown.getSelectedItem();
		if (target == null || target.isEmpty() || target.equals("Select member..."))
		{
			JOptionPane.showMessageDialog(this, "Select a member first.",
				"No Selection", JOptionPane.WARNING_MESSAGE);
			return;
		}
		int confirm = JOptionPane.showConfirmDialog(this,
			String.format("Transfer ownership of '%s' to %s?\nYou will become Co-Owner. This cannot be easily undone!",
				g.getName(), target),
			"Confirm Transfer", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (confirm != JOptionPane.YES_OPTION) return;

		try
		{
			if (groupService.transferOwnership(g.getId(), user, target))
			{
				JOptionPane.showMessageDialog(this,
					"Ownership transferred to " + target + "!\nYou are now Co-Owner.",
					"Transfer Complete", JOptionPane.INFORMATION_MESSAGE);
				plugin.refreshPanel();
			}
			else
			{
				JOptionPane.showMessageDialog(this, "Transfer failed.", "Error", JOptionPane.ERROR_MESSAGE);
			}
		}
		catch (Exception e)
		{
			log.error("Failed to transfer ownership", e);
			JOptionPane.showMessageDialog(this, "Transfer failed: " + e.getMessage(),
				"Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void leaveGroup()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) return;
		String user = getCurrentUsername();
		if (groupService.isOwner(g.getId(), user))
		{
			JOptionPane.showMessageDialog(this,
				"As owner, you cannot leave.\nTransfer ownership or delete the group instead.",
				"Cannot Leave", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (JOptionPane.showConfirmDialog(this,
			String.format("Leave '%s'?\nYou'll need a new invite to rejoin.", g.getName()),
			"Confirm Leave", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
		{
			groupService.removeMember(g.getId(), user);
			JOptionPane.showMessageDialog(this, "You have left '" + g.getName() + "'.",
				"Left Group", JOptionPane.INFORMATION_MESSAGE);
			plugin.refreshPanel();
		}
	}

	private void deleteGroup()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) return;
		String user = getCurrentUsername();
		if (!groupService.isOwner(g.getId(), user))
		{
			JOptionPane.showMessageDialog(this, "Only the owner can delete the group!",
				"Permission Denied", JOptionPane.ERROR_MESSAGE);
			return;
		}
		if (JOptionPane.showConfirmDialog(this,
			String.format("PERMANENTLY DELETE '%s'?\nAll data will be lost. This cannot be undone!", g.getName()),
			"Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
		{
			return;
		}
		String groupId = g.getId();
		String groupName = g.getName();
		try
		{
			plugin.getDataService().clearGroupData(groupId);
			plugin.getDataService().clearItemSetData(groupId);
			groupService.deleteGroup(groupId);
			JOptionPane.showMessageDialog(this, "Group '" + groupName + "' deleted.",
				"Deleted", JOptionPane.INFORMATION_MESSAGE);
			plugin.refreshPanel();
		}
		catch (Exception e)
		{
			log.error("Failed to delete group", e);
			JOptionPane.showMessageDialog(this, "Delete failed: " + e.getMessage(),
				"Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void updatePermission(String permType, String role, boolean enabled)
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) return;
		String user = getCurrentUsername();
		boolean ok = "kick".equals(permType)
			? groupService.setKickPermission(g.getId(), user, role, enabled)
			: groupService.setInvitePermission(g.getId(), user, role, enabled);
		if (ok)
		{
			refresh();
		}
		else
		{
			JCheckBox cb = getPermCheckbox(permType, role);
			if (cb != null) cb.setSelected(!enabled);
			JOptionPane.showMessageDialog(this, "You don't have permission to change this setting.",
				"Permission Denied", JOptionPane.WARNING_MESSAGE);
		}
	}

	// ---- Member Row ----

	private JPanel createMemberRow(GroupMember member, String groupId, String currentUser, boolean isOwner)
	{
		boolean canRole = groupService.canChangeRole(groupId, currentUser, member.getName());
		boolean canKick = groupService.canKick(groupId, currentUser, member.getName());
		String role = member.getRole() != null ? member.getRole() : "member";

		JPanel row = new JPanel(new BorderLayout(0, 4));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new CompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 6, 6, 6)));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));

		// Top: name (fills width) + badge (fixed right)
		JPanel nameRow = new JPanel(new BorderLayout(5, 0));
		nameRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel name = new JLabel(member.getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		nameRow.add(name, BorderLayout.CENTER);

		JLabel badge = new JLabel(GroupService.formatRoleName(role));
		badge.setFont(FontManager.getRunescapeSmallFont());
		badge.setOpaque(true);
		badge.setBorder(new EmptyBorder(1, 5, 1, 5));
		badge.setBackground(GroupService.getRoleBackgroundColor(role));
		badge.setForeground(GroupService.getRoleForegroundColor(role));
		nameRow.add(badge, BorderLayout.EAST);

		row.add(nameRow, BorderLayout.NORTH);

		// Bottom: controls (only if user has permissions)
		if (canRole || canKick)
		{
			JPanel controls = new JPanel(new BorderLayout(3, 0));
			controls.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			if (canRole)
			{
				String[] roles = isOwner ? GroupService.getAvailableRoles() : new String[]{"admin", "mod", "member"};
				JComboBox<String> dd = new JComboBox<>(roles);
				dd.setFont(FontManager.getRunescapeSmallFont());
				dd.setSelectedItem(role.toLowerCase());
				dd.setPreferredSize(new Dimension(0, 22));
				dd.addActionListener(e -> {
					String nr = (String) dd.getSelectedItem();
					if (nr != null && !nr.equalsIgnoreCase(role))
					{
						if (groupService.setMemberRole(groupId, currentUser, member.getName(), nr))
						{
							plugin.refreshPanel();
						}
						else
						{
							JOptionPane.showMessageDialog(this, "Failed to change role.",
								"Error", JOptionPane.ERROR_MESSAGE);
							dd.setSelectedItem(role);
						}
					}
				});
				controls.add(dd, BorderLayout.CENTER);
			}
			if (canKick)
			{
				JButton kick = new JButton("Kick");
				kick.setFont(FontManager.getRunescapeSmallFont());
				kick.setBackground(DANGER_BORDER);
				kick.setForeground(Color.WHITE);
				kick.setFocusPainted(false);
				kick.setBorderPainted(false);
				kick.setPreferredSize(new Dimension(50, 22));
				kick.addActionListener(e -> {
					if (JOptionPane.showConfirmDialog(this,
						"Kick " + member.getName() + " from the group?",
						"Confirm Kick", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION)
					{
						if (groupService.removeMemberFromGroup(groupId, currentUser, member.getName()))
						{
							plugin.getDataService().removeItemsForLender(groupId, member.getName());
							plugin.refreshPanel();
						}
						else
						{
							JOptionPane.showMessageDialog(this, "Failed to kick.",
								"Error", JOptionPane.ERROR_MESSAGE);
						}
					}
				});
				controls.add(kick, BorderLayout.EAST);
			}
			row.add(controls, BorderLayout.CENTER);
		}
		return row;
	}

	// ---- Refresh ----

	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			String currentUser = null;
			boolean loggedIn = false;
			try
			{
				if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
				{
					String pn = plugin.getClient().getLocalPlayer().getName();
					if (pn != null && !pn.isEmpty()) { currentUser = pn; loggedIn = true; }
				}
			}
			catch (Exception e) { log.debug("Could not get player name", e); }

			if (!loggedIn)
			{
				String u = getCurrentUsername();
				if (u != null && !u.equals("Unknown") && !u.isEmpty()) { currentUser = u; loggedIn = true; }
			}

			LendingGroup g = loggedIn ? groupService.getActiveGroupUnchecked() : null;
			removeAll();

			if (!loggedIn)
			{
				add(notLoggedInPanel, BorderLayout.CENTER);
			}
			else if (g == null)
			{
				add(scrollPane, BorderLayout.CENTER);
				showNoGroupState();
			}
			else
			{
				add(scrollPane, BorderLayout.CENTER);
				boolean isOwner = groupService.isOwner(g.getId(), currentUser);
				boolean isCoOwner = groupService.isCoOwner(g.getId(), currentUser);
				boolean isAdmin = groupService.isAdmin(g.getId(), currentUser);
				boolean isMod = groupService.isMod(g.getId(), currentUser);
				showGroupContent(g, currentUser, isOwner, isCoOwner, isAdmin, isMod, isOwner || isCoOwner || isAdmin);
			}
			revalidate();
			repaint();
		});
	}

	private void showNoGroupState()
	{
		groupNameLabel.setText("No active group");
		memberCountLabel.setText("0");
		roleLabel.setText("-");
		inviteCodeLabel.setText("No active code");
		inviteCodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		copyCodeButton.setEnabled(false);
		generateCodeButton.setEnabled(false);
		permissionsPanel.setVisible(false);
		if (dangerZonePanel != null) dangerZonePanel.setVisible(false);
		if (transferOwnerDropdown != null) { transferOwnerDropdown.removeAllItems(); transferOwnerDropdown.setEnabled(false); }
		if (transferOwnershipButton != null) transferOwnershipButton.setEnabled(false);

		membersListPanel.removeAll();
		membersListPanel.add(label("Select or create a group", false));
		membersListPanel.revalidate();
		membersListPanel.repaint();
	}

	private void showGroupContent(LendingGroup g, String currentUser,
								   boolean isOwner, boolean isCoOwner, boolean isAdmin, boolean isMod, boolean isStaff)
	{
		groupNameLabel.setText(g.getName());
		memberCountLabel.setText(String.valueOf(g.getMembers().size()));
		String rawRole = groupService.getMemberRole(g.getId(), currentUser);
		roleLabel.setText(GroupService.formatRoleName(rawRole != null ? rawRole : "member"));

		// Invite code
		boolean canInvite = groupService.canGenerateInviteCode(g.getId(), currentUser);
		if (inviteCodePanel != null) inviteCodePanel.setVisible(canInvite || isStaff);
		if (canInvite || isStaff)
		{
			if (g.hasActiveInviteCode())
			{
				inviteCodeLabel.setText(g.getInviteCode());
				inviteCodeLabel.setForeground(ColorScheme.BRAND_ORANGE);
				copyCodeButton.setEnabled(true);
			}
			else
			{
				inviteCodeLabel.setText("No active code");
				inviteCodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				copyCodeButton.setEnabled(false);
			}
			generateCodeButton.setEnabled(canInvite);
		}

		// Permissions
		boolean canEditPerms = isOwner || isCoOwner;
		permissionsPanel.setVisible(canEditPerms);
		if (canEditPerms)
		{
			coOwnerKickCb.setSelected(g.isCoOwnerCanKick());
			adminKickCb.setSelected(g.isAdminCanKick());
			modKickCb.setSelected(g.isModCanKick());
			coOwnerKickCb.setEnabled(isOwner);
			coOwnerInviteCb.setSelected(g.isCoOwnerCanInvite());
			adminInviteCb.setSelected(g.isAdminCanInvite());
			modInviteCb.setSelected(g.isModCanInvite());
			coOwnerInviteCb.setEnabled(isOwner);
		}

		// Members
		if (membersSection != null)
		{
			membersSection.setVisible(isStaff || (isMod && g.isModCanKick()));
		}
		membersListPanel.removeAll();
		List<GroupMember> members = g.getMembers();
		int rank = GroupService.getRoleRank(rawRole);
		int managed = 0;
		for (GroupMember m : members)
		{
			if (GroupService.getRoleRank(m.getRole()) < rank || isOwner)
			{
				membersListPanel.add(createMemberRow(m, g.getId(), currentUser, isOwner));
				managed++;
			}
		}
		if (managed == 0)
		{
			JLabel lbl = label(members.isEmpty() ? "No members" : "No members you can manage", false);
			lbl.setBorder(new EmptyBorder(5, 5, 5, 5));
			membersListPanel.add(lbl);
		}
		membersListPanel.revalidate();
		membersListPanel.repaint();

		// Danger zone
		if (dangerZonePanel != null)
		{
			dangerZonePanel.setVisible(true);
			deleteGroupButton.setEnabled(isOwner);
			deleteGroupButton.setVisible(isOwner);
			leaveGroupButton.setEnabled(!isOwner);
			leaveGroupButton.setVisible(!isOwner);
			transferOwnershipButton.setEnabled(isOwner);
			transferOwnershipButton.setVisible(isOwner);
			transferOwnerDropdown.setEnabled(isOwner);
			transferOwnerDropdown.setVisible(isOwner);

			if (isOwner)
			{
				transferOwnerDropdown.removeAllItems();
				transferOwnerDropdown.addItem("Select member...");
				for (GroupMember m : members)
				{
					if (!m.getName().equalsIgnoreCase(currentUser)) transferOwnerDropdown.addItem(m.getName());
				}
				if (transferOwnerDropdown.getItemCount() <= 1)
				{
					transferOwnershipButton.setEnabled(false);
					transferOwnerDropdown.setEnabled(false);
				}
			}
		}
	}

	// ---- Utility ----

	private String getCurrentUsername()
	{
		try
		{
			if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
			{
				String n = plugin.getClient().getLocalPlayer().getName();
				if (n != null && !n.isEmpty()) return n;
			}
			String u = plugin.getClient().getUsername();
			if (u != null && !u.isEmpty()) return u;
		}
		catch (Exception e) { log.debug("Could not get username", e); }
		return "Unknown";
	}

	private JPanel sectionPanel(String title)
	{
		JPanel p = new JPanel(new BorderLayout(3, 3))
		{
			@Override
			public Dimension getMaximumSize()
			{
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(new CompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1), new EmptyBorder(6, 6, 6, 6)));
		if (title != null && !title.isEmpty())
		{
			JLabel lbl = new JLabel(title);
			lbl.setFont(FontManager.getRunescapeBoldFont());
			lbl.setForeground(ColorScheme.BRAND_ORANGE);
			p.add(lbl, BorderLayout.NORTH);
		}
		return p;
	}

	private JPanel boxPanel()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		return p;
	}

	private JLabel label(String text, boolean isValue)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(isValue ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private JLabel smallLabel(String html)
	{
		JLabel l = new JLabel("<html>" + html + "</html>");
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private JButton smallButton(String text, Color bg)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setBackground(bg);
		b.setForeground(Color.WHITE);
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 6, 2, 6));
		return b;
	}

	private JButton dangerButton(String text, Color bg)
	{
		JButton b = smallButton(text, bg);
		b.setAlignmentX(LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		b.setMargin(new Insets(2, 4, 2, 4));
		return b;
	}

	private JCheckBox permCheckbox(String text, boolean selected, String permType, String role)
	{
		JCheckBox cb = new JCheckBox(text);
		cb.setFont(FontManager.getRunescapeSmallFont());
		cb.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		cb.setForeground(Color.WHITE);
		cb.setSelected(selected);
		cb.setAlignmentX(LEFT_ALIGNMENT);
		cb.addActionListener(e -> updatePermission(permType, role, cb.isSelected()));
		return cb;
	}

	private JCheckBox getPermCheckbox(String permType, String role)
	{
		if ("kick".equals(permType))
		{
			switch (role) { case "co-owner": return coOwnerKickCb; case "admin": return adminKickCb; case "mod": return modKickCb; }
		}
		else
		{
			switch (role) { case "co-owner": return coOwnerInviteCb; case "admin": return adminInviteCb; case "mod": return modInviteCb; }
		}
		return null;
	}

}
