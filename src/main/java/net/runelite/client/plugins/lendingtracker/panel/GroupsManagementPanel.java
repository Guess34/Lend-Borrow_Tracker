package net.runelite.client.plugins.lendingtracker.panel;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.model.GroupMember;
import net.runelite.client.plugins.lendingtracker.model.PendingInvite;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Clipboard;
import java.util.List;

/**
 * Merged Groups & Party management panel with dropdown tab navigation
 */
@Slf4j
public class GroupsManagementPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final GroupConfigStore groupConfigStore;
	private final Client client;
	private final JPanel contentPanel;
	private JComboBox<String> viewSelector;
	private JComboBox<String> groupSelector;
	private String currentView = "Overview";

	// Role hierarchy constants
	private static final String ROLE_OWNER = "owner";
	private static final String ROLE_CO_OWNER = "co-owner";
	private static final String ROLE_MODERATOR = "moderator";
	private static final String ROLE_MEMBER = "member";

	public GroupsManagementPanel(LendingTrackerPlugin plugin, GroupConfigStore groupConfigStore, Client client)
	{
		this.plugin = plugin;
		this.groupConfigStore = groupConfigStore;
		this.client = client;

		setLayout(new BorderLayout(0, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 10, 10, 10));

		// Top controls panel
		add(buildTopControlsPanel(), BorderLayout.NORTH);

		// Dynamic content panel
		contentPanel = new JPanel(new BorderLayout());
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(contentPanel, BorderLayout.CENTER);

		// Load initial view
		refreshContent();
	}

	private JPanel buildTopControlsPanel()
	{
		JPanel topPanel = new JPanel(new BorderLayout(3, 3));
		topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		topPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(12, 15, 12, 15)
		));

		// Group selection row
		JPanel groupRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
		groupRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel groupLabel = new JLabel("Group:");
		groupLabel.setForeground(Color.WHITE);
		groupLabel.setFont(groupLabel.getFont().deriveFont(Font.BOLD));
		groupRow.add(groupLabel);

		groupSelector = new JComboBox<>();
		groupSelector.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupSelector.setForeground(Color.WHITE);
		loadGroupsIntoSelector();
		groupSelector.addActionListener(e -> {
			String selectedGroupName = (String) groupSelector.getSelectedItem();
			if (selectedGroupName != null && !selectedGroupName.equals("No Groups")) {
				String groupId = groupConfigStore.getGroupIdByName(selectedGroupName);
				if (groupId != null) {
					groupConfigStore.setCurrentGroupId(groupId);
					refreshContent();
				}
			}
		});
		groupRow.add(groupSelector);

		topPanel.add(groupRow, BorderLayout.NORTH);

		// View selection row
		JPanel viewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		viewRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel viewLabel = new JLabel("View:");
		viewLabel.setForeground(Color.WHITE);
		viewLabel.setFont(viewLabel.getFont().deriveFont(Font.BOLD));
		viewRow.add(viewLabel);

		viewSelector = new JComboBox<>(new String[]{
			"Overview", "Pending Invites", "Members", "Settings"
		});
		viewSelector.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		viewSelector.setForeground(Color.WHITE);
		viewSelector.addActionListener(e -> {
			currentView = (String) viewSelector.getSelectedItem();
			refreshContent();
		});
		viewRow.add(viewSelector);

		topPanel.add(viewRow, BorderLayout.SOUTH);

		return topPanel;
	}

	private void refreshContent()
	{
		contentPanel.removeAll();

		switch (currentView) {
			case "Overview":
				contentPanel.add(buildOverviewPanel(), BorderLayout.CENTER);
				break;
			case "Pending Invites":
				contentPanel.add(buildPendingInvitesPanel(), BorderLayout.CENTER);
				break;
			case "Members":
				contentPanel.add(buildMembersPanel(), BorderLayout.CENTER);
				break;
			case "Settings":
				contentPanel.add(buildSettingsPanel(), BorderLayout.CENTER);
				break;
		}

		contentPanel.revalidate();
		contentPanel.repaint();
	}

	// ==================== OVERVIEW TAB ====================
	private JPanel buildOverviewPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		LendingGroup group = getCurrentGroup();
		if (group == null) {
			panel.add(createMessageLabel("No group selected. Create or select a group above."));
			return panel;
		}

		String currentPlayer = getCurrentPlayerName();
		GroupMember member = group.getMember(currentPlayer);

		// For sample group, also check if there's a member called "You" (placeholder owner)
		if (member == null && "sample-group".equals(group.getId())) {
			member = group.getMember("You");
		}

		String role = member != null ? member.getRole() : ROLE_MEMBER;

		// Group info section
		panel.add(buildGroupInfoSection(group, role));
		panel.add(Box.createVerticalStrut(10));

		// Group code section - only show to owners, co-owners, and moderators
		if (canManageCodes(role)) {
			panel.add(buildGroupCodeSection(group, role));
			panel.add(Box.createVerticalStrut(10));

			// Clan code section
			panel.add(buildClanCodeSection(group, role));
			panel.add(Box.createVerticalStrut(10));
		}

		// Quick stats
		panel.add(buildQuickStatsSection(group));

		JPanel wrapper = new JPanel(new BorderLayout()); wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR); JScrollPane scrollPane = new JScrollPane(panel); scrollPane.setBorder(null); scrollPane.getVerticalScrollBar().setUnitIncrement(16); styleScrollBar(scrollPane); wrapper.add(scrollPane, BorderLayout.CENTER); return wrapper;
	}

	private JPanel buildGroupInfoSection(LendingGroup group, String role)
	{
		JPanel section = new JPanel(new GridBagLayout());
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"📊 Group Information",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			Color.WHITE
		));

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new Insets(5, 10, 5, 10);
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;

		addInfoRow(section, gbc, "Group Name:", group.getName());
		addInfoRow(section, gbc, "Your Role:", getRoleDisplay(role));
		addInfoRow(section, gbc, "Total Members:", String.valueOf(group.getMembers().size()));
		addInfoRow(section, gbc, "Created:", new java.text.SimpleDateFormat("MMM dd, yyyy").format(new java.util.Date(group.getCreatedDate())));

		return section;
	}

	private JPanel buildGroupCodeSection(LendingGroup group, String role)
	{
		JPanel section = new JPanel(new BorderLayout(5, 5));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"Group Code",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			ColorScheme.BRAND_ORANGE
		));

		// Content panel
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		// Code and copy button row
		JPanel codeRow = new JPanel(new BorderLayout(5, 0));
		codeRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel codeLabel = new JLabel(group.getInviteCode());
		codeLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
		codeLabel.setForeground(ColorScheme.BRAND_ORANGE);
		codeRow.add(codeLabel, BorderLayout.WEST);

		JButton copyBtn = new JButton("Copy");
		copyBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyBtn.setForeground(Color.WHITE);
		copyBtn.setPreferredSize(new Dimension(65, 26));
		copyBtn.setToolTipText("Copy group code to clipboard (single-use per person)");
		copyBtn.addActionListener(e -> copyToClipboard(group.getInviteCode()));
		codeRow.add(copyBtn, BorderLayout.EAST);

		contentPanel.add(codeRow);
		contentPanel.add(Box.createVerticalStrut(5));

		// Action row (Regen button and used count)
		JPanel actionRow = new JPanel(new BorderLayout(5, 0));
		actionRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton regenBtn = new JButton("Regen");
		regenBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		regenBtn.setForeground(Color.WHITE);
		regenBtn.setPreferredSize(new Dimension(70, 26));
		regenBtn.setToolTipText("Generate new group code or enter custom code");
		// Disable regen for sample group
		boolean isSampleGroup = "sample-group".equals(group.getId());
		if (isSampleGroup) {
			regenBtn.setEnabled(false);
			regenBtn.setToolTipText("Cannot modify sample group codes. Create your own group to use this feature.");
		} else {
			regenBtn.addActionListener(e -> showCustomCodeDialog(group, true));
		}
		actionRow.add(regenBtn, BorderLayout.WEST);

		JLabel usedLabel = new JLabel("Used: " + group.getUsedGroupCodes().size());
		usedLabel.setForeground(Color.LIGHT_GRAY);
		usedLabel.setFont(new Font("Arial", Font.PLAIN, 11));
		actionRow.add(usedLabel, BorderLayout.EAST);

		contentPanel.add(actionRow);

		section.add(contentPanel, BorderLayout.CENTER);

		return section;
	}

	private JPanel buildClanCodeSection(LendingGroup group, String role)
	{
		JPanel section = new JPanel(new BorderLayout(5, 5));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"Clan Code",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			ColorScheme.BRAND_ORANGE
		));

		// Content panel
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		// Code and copy button row
		JPanel codeRow = new JPanel(new BorderLayout(5, 0));
		codeRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel codeLabel = new JLabel(group.getClanCode());
		codeLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
		codeLabel.setForeground(group.isClanCodeEnabled() ? ColorScheme.BRAND_ORANGE : Color.GRAY);
		codeRow.add(codeLabel, BorderLayout.WEST);

		JButton copyBtn = new JButton("Copy");
		copyBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		copyBtn.setForeground(Color.WHITE);
		copyBtn.setPreferredSize(new Dimension(65, 26));
		copyBtn.setEnabled(group.isClanCodeEnabled());
		copyBtn.setToolTipText("Copy clan code to clipboard (multi-use)");
		copyBtn.addActionListener(e -> copyToClipboard(group.getClanCode()));
		codeRow.add(copyBtn, BorderLayout.EAST);

		contentPanel.add(codeRow);
		contentPanel.add(Box.createVerticalStrut(5));

		// Control buttons row (On/Off and Regen)
		JPanel controlRow = new JPanel(new BorderLayout(5, 0));
		controlRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Disable controls for sample group
		boolean isSampleGroup = "sample-group".equals(group.getId());

		JButton toggleBtn = new JButton(group.isClanCodeEnabled() ? "Off" : "On");
		toggleBtn.setBackground(group.isClanCodeEnabled() ? Color.RED.darker() : Color.GREEN.darker());
		toggleBtn.setForeground(Color.WHITE);
		toggleBtn.setPreferredSize(new Dimension(50, 26));
		toggleBtn.setToolTipText("Enable or disable the clan code");
		if (isSampleGroup) {
			toggleBtn.setEnabled(false);
			toggleBtn.setToolTipText("Cannot modify sample group. Create your own group to use this feature.");
		} else {
			toggleBtn.addActionListener(e -> {
				group.setClanCodeEnabled(!group.isClanCodeEnabled());
				saveGroup(group);
				refreshContent();
			});
		}
		controlRow.add(toggleBtn, BorderLayout.WEST);

		JButton regenBtn = new JButton("Regen");
		regenBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		regenBtn.setForeground(Color.WHITE);
		regenBtn.setPreferredSize(new Dimension(70, 26));
		regenBtn.setToolTipText("Generate new clan code or enter custom code");
		if (isSampleGroup) {
			regenBtn.setEnabled(false);
			regenBtn.setToolTipText("Cannot modify sample group codes. Create your own group to use this feature.");
		} else {
			regenBtn.addActionListener(e -> showCustomCodeDialog(group, false));
		}
		controlRow.add(regenBtn, BorderLayout.EAST);

		contentPanel.add(controlRow);
		contentPanel.add(Box.createVerticalStrut(5));

		// Stats label
		JLabel usedLabel = new JLabel("Used: " + group.getClanCodeUseCount() + " times");
		usedLabel.setForeground(Color.LIGHT_GRAY);
		usedLabel.setFont(new Font("Arial", Font.PLAIN, 11));
		contentPanel.add(usedLabel);

		section.add(contentPanel, BorderLayout.CENTER);

		return section;
	}
	private JPanel buildQuickStatsSection(LendingGroup group)
	{
		JPanel section = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"📈 Quick Stats",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			Color.WHITE
		));

		int pendingCount = group.getPendingInvites() != null ? group.getPendingInvites().size() : 0;
		addStatLabel(section, "Pending Invites", String.valueOf(pendingCount), pendingCount > 0 ? ColorScheme.BRAND_ORANGE : Color.LIGHT_GRAY);

		long owners = group.getMembers().stream().filter(m -> ROLE_OWNER.equals(m.getRole())).count();
		long coOwners = group.getMembers().stream().filter(m -> ROLE_CO_OWNER.equals(m.getRole())).count();
		long mods = group.getMembers().stream().filter(m -> ROLE_MODERATOR.equals(m.getRole())).count();

		addStatLabel(section, "Owners", String.valueOf(owners), Color.YELLOW);
		addStatLabel(section, "Co-Owners", String.valueOf(coOwners), Color.CYAN);
		addStatLabel(section, "Moderators", String.valueOf(mods), Color.GREEN);

		return section;
	}

	// ==================== PENDING INVITES TAB ====================
	private JPanel buildPendingInvitesPanel()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		LendingGroup group = getCurrentGroup();
		if (group == null) {
			panel.add(createMessageLabel("No group selected."), BorderLayout.CENTER);
			return panel;
		}

		String currentPlayer = getCurrentPlayerName();
		GroupMember member = group.getMember(currentPlayer);
		String role = member != null ? member.getRole() : ROLE_MEMBER;

		if (!canApproveInvites(role)) {
			panel.add(createMessageLabel("You don't have permission to manage invites."), BorderLayout.CENTER);
			return panel;
		}

		List<PendingInvite> invites = group.getPendingInvites();
		if (invites == null || invites.isEmpty()) {
			panel.add(createMessageLabel("No pending invites."), BorderLayout.CENTER);
			return panel;
		}

		// Build list of pending invites
		JPanel invitesContainer = new JPanel();
		invitesContainer.setLayout(new BoxLayout(invitesContainer, BoxLayout.Y_AXIS));
		invitesContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (PendingInvite invite : invites) {
			invitesContainer.add(buildInviteCard(group, invite));
			invitesContainer.add(Box.createVerticalStrut(10));
		}

		JScrollPane scrollPane = new JScrollPane(invitesContainer);
		styleScrollBar(scrollPane);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		panel.add(scrollPane, BorderLayout.CENTER);

		return panel;
	}

	private JPanel buildInviteCard(LendingGroup group, PendingInvite invite)
	{
		JPanel card = new JPanel(new BorderLayout(3, 3));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			new EmptyBorder(10, 15, 10, 15)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

		// Info panel
		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel nameLabel = new JLabel(invite.getPlayerName());
		nameLabel.setFont(new Font("Arial", Font.BOLD, 14));
		nameLabel.setForeground(Color.WHITE);
		infoPanel.add(nameLabel);

		JLabel codeLabel = new JLabel("Code used: " + invite.getCodeUsed() + " (" + invite.getCodeType() + ")");
		codeLabel.setForeground(Color.LIGHT_GRAY);
		infoPanel.add(codeLabel);

		JLabel dateLabel = new JLabel("Requested: " + new java.text.SimpleDateFormat("MMM dd, HH:mm").format(new java.util.Date(invite.getRequestDate())));
		dateLabel.setForeground(Color.LIGHT_GRAY);
		infoPanel.add(dateLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Action buttons
		JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		buttonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton approveBtn = new JButton("✓ Approve");
		approveBtn.setBackground(Color.GREEN.darker());
		approveBtn.setForeground(Color.WHITE);
		approveBtn.addActionListener(e -> approveInvite(group, invite));
		buttonsPanel.add(approveBtn);

		JButton denyBtn = new JButton("✗ Deny");
		denyBtn.setBackground(Color.RED.darker());
			denyBtn.setToolTipText("Reject this join request");
		denyBtn.setForeground(Color.WHITE);
		denyBtn.addActionListener(e -> denyInvite(group, invite));
		buttonsPanel.add(denyBtn);

		card.add(buttonsPanel, BorderLayout.EAST);

		return card;
	}

	// ==================== MEMBERS TAB ====================
	private JPanel buildMembersPanel()
	{
		JPanel panel = new JPanel(new BorderLayout(0, 10));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		LendingGroup group = getCurrentGroup();
		if (group == null) {
			panel.add(createMessageLabel("No group selected."), BorderLayout.CENTER);
			return panel;
		}

		String currentPlayer = getCurrentPlayerName();
		GroupMember currentMember = group.getMember(currentPlayer);
		String currentRole = currentMember != null ? currentMember.getRole() : ROLE_MEMBER;

		// Search bar
		JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
		searchPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		JLabel searchLabel = new JLabel("Search:");
		searchLabel.setForeground(Color.WHITE);
		searchLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 10));
		searchPanel.add(searchLabel, BorderLayout.WEST);

		JTextField searchField = new JTextField();
		searchField.setPreferredSize(new Dimension(200, 30));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setForeground(Color.WHITE);
		searchField.setCaretColor(Color.WHITE);
		searchPanel.add(searchField, BorderLayout.CENTER);
		panel.add(searchPanel, BorderLayout.NORTH);

		// Members list
		JPanel membersContainer = new JPanel();
		membersContainer.setLayout(new BoxLayout(membersContainer, BoxLayout.Y_AXIS));
		membersContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		for (GroupMember member : group.getMembers()) {
			membersContainer.add(buildMemberCard(group, member, currentPlayer, currentRole));
			membersContainer.add(Box.createVerticalStrut(5));
		}

		JScrollPane scrollPane = new JScrollPane(membersContainer);
		styleScrollBar(scrollPane);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		panel.add(scrollPane, BorderLayout.CENTER);

		return panel;
	}

	private JPanel buildMemberCard(LendingGroup group, GroupMember member, String currentPlayer, String currentRole)
	{
		JPanel card = new JPanel(new BorderLayout(3, 3));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 12, 8, 12)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

		// Member info
		JPanel infoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		infoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		String roleIcon = getRoleIcon(member.getRole());
		JLabel nameLabel = new JLabel(roleIcon + " " + member.getName());
		nameLabel.setFont(new Font("Arial", Font.BOLD, 13));
		nameLabel.setForeground(Color.WHITE);
		infoPanel.add(nameLabel);

		JLabel roleLabel = new JLabel("(" + getRoleDisplay(member.getRole()) + ")");
		roleLabel.setForeground(getRoleColor(member.getRole()));
		infoPanel.add(roleLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		// Action buttons
		JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
		buttonsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Can't manage yourself
		if (!member.getName().equals(currentPlayer)) {
			// Permissions button (owner/co-owner only)
			if (canManagePermissions(currentRole, member.getRole())) {
				JButton permsBtn = new JButton("⚙ Permissions");
				permsBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				permsBtn.setForeground(Color.WHITE);
				permsBtn.addActionListener(e -> showManagePermissionsDialog(group, member));
				buttonsPanel.add(permsBtn);
			}

			// Remove button (if has permission)
			if (canRemoveMember(currentRole, member.getRole())) {
				JButton removeBtn = new JButton("🗑 Remove");
				removeBtn.setToolTipText("Remove this member from the group");
				removeBtn.setBackground(Color.RED.darker());
				removeBtn.setForeground(Color.WHITE);
				removeBtn.addActionListener(e -> removeMember(group, member));
				buttonsPanel.add(removeBtn);
			}
		}

		card.add(buttonsPanel, BorderLayout.EAST);

		return card;
	}

	// ==================== PARTY ACTIVITY TAB ====================
	private JPanel buildPartyActivityPanel()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Party activity tracking feature placeholder - not yet implemented
		JLabel label = new JLabel("<html><center>📢 Party Activity Tracking<br/><br/>Coming soon...</center></html>", SwingConstants.CENTER);
		label.setForeground(Color.LIGHT_GRAY);
		panel.add(label, BorderLayout.CENTER);

		return panel;
	}

	// ==================== SETTINGS TAB ====================
	private JPanel buildSettingsPanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));

		LendingGroup group = getCurrentGroup();
		if (group == null) {
			panel.add(createMessageLabel("No group selected."));
			return panel;
		}

		String currentPlayer = getCurrentPlayerName();
		GroupMember member = group.getMember(currentPlayer);
		String role = member != null ? member.getRole() : ROLE_MEMBER;
		boolean isOwner = ROLE_OWNER.equals(role);
		boolean isStaff = isOwner || ROLE_CO_OWNER.equals(role) || "admin".equals(role);

		// ==================== SECTION 1: GROUP INFO (All Members) ====================
		JPanel groupInfoPanel = new JPanel();
		groupInfoPanel.setLayout(new BoxLayout(groupInfoPanel, BoxLayout.Y_AXIS));
		groupInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupInfoPanel.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"Group Info",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			ColorScheme.BRAND_ORANGE
		));

		JPanel infoContent = new JPanel(new GridLayout(3, 2, 5, 3));
		infoContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		infoContent.setBorder(new EmptyBorder(5, 10, 5, 10));

		infoContent.add(createInfoLabel("Group:"));
		infoContent.add(createValueLabel(group.getName()));
		infoContent.add(createInfoLabel("Members:"));
		infoContent.add(createValueLabel(String.valueOf(group.getMembers().size())));
		infoContent.add(createInfoLabel("Your Role:"));
		JLabel roleLabel = createValueLabel(formatRole(role));
		roleLabel.setForeground(getRoleColor(role));
		infoContent.add(roleLabel);

		groupInfoPanel.add(infoContent);
		panel.add(groupInfoPanel);
		panel.add(Box.createVerticalStrut(10));

		// ==================== SECTION 2: NOTIFICATIONS (All Members) ====================
		JPanel notificationsPanel = new JPanel();
		notificationsPanel.setLayout(new BoxLayout(notificationsPanel, BoxLayout.Y_AXIS));
		notificationsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		notificationsPanel.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(new Color(88, 101, 242)), // Discord blurple
			"Discord Notifications",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			new Color(88, 101, 242)
		));

		JLabel webhookInfo = new JLabel("<html><small>Connect Discord to receive loan notifications.</small></html>");
		webhookInfo.setForeground(Color.LIGHT_GRAY);
		webhookInfo.setBorder(new EmptyBorder(5, 10, 5, 10));
		notificationsPanel.add(webhookInfo);

		JPanel urlPanel = new JPanel(new BorderLayout(5, 0));
		urlPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		urlPanel.setBorder(new EmptyBorder(0, 10, 5, 10));

		JTextField webhookField = new JTextField();
		webhookField.setBackground(ColorScheme.DARK_GRAY_COLOR);
		webhookField.setForeground(Color.WHITE);
		webhookField.setCaretColor(Color.WHITE);
		String existingUrl = groupConfigStore.getGroupWebhookUrl(group.getId());
		if (existingUrl != null) {
			webhookField.setText(existingUrl);
		}
		urlPanel.add(webhookField, BorderLayout.CENTER);
		notificationsPanel.add(urlPanel);

		JPanel webhookBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
		webhookBtnPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton saveWebhookBtn = new JButton("Save");
		saveWebhookBtn.setBackground(new Color(88, 101, 242));
		saveWebhookBtn.setForeground(Color.WHITE);
		saveWebhookBtn.addActionListener(e -> {
			String url = webhookField.getText().trim();
			if (!url.isEmpty() && url.startsWith("https://discord.com/api/webhooks/")) {
				groupConfigStore.setGroupWebhookUrl(group.getId(), url);
				JOptionPane.showMessageDialog(this, "Webhook URL saved!", "Success", JOptionPane.INFORMATION_MESSAGE);
			} else if (url.isEmpty()) {
				groupConfigStore.setGroupWebhookUrl(group.getId(), null);
				JOptionPane.showMessageDialog(this, "Webhook URL cleared.", "Success", JOptionPane.INFORMATION_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(this, "Invalid URL. Must start with:\nhttps://discord.com/api/webhooks/",
					"Invalid URL", JOptionPane.ERROR_MESSAGE);
			}
		});
		webhookBtnPanel.add(saveWebhookBtn);

		if (isStaff) {
			JButton testWebhookBtn = new JButton("Test");
			testWebhookBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
			testWebhookBtn.setForeground(Color.WHITE);
			testWebhookBtn.addActionListener(e -> {
				String url = webhookField.getText().trim();
				if (!url.isEmpty() && url.startsWith("https://discord.com/api/webhooks/")) {
					plugin.testDiscordWebhook(url, group.getName());
					JOptionPane.showMessageDialog(this, "Test message sent!", "Test Sent", JOptionPane.INFORMATION_MESSAGE);
				} else {
					JOptionPane.showMessageDialog(this, "Enter a valid webhook URL first.", "No URL", JOptionPane.WARNING_MESSAGE);
				}
			});
			webhookBtnPanel.add(testWebhookBtn);
		}

		notificationsPanel.add(webhookBtnPanel);
		panel.add(notificationsPanel);
		panel.add(Box.createVerticalStrut(10));

		// ==================== SECTION 3: SCREENSHOTS (All Members) ====================
		JPanel screenshotPanel = new JPanel();
		screenshotPanel.setLayout(new BoxLayout(screenshotPanel, BoxLayout.Y_AXIS));
		screenshotPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		screenshotPanel.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"Screenshots",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			Color.WHITE
		));

		JLabel screenshotInfo = new JLabel("<html><small>Proof screenshots for trades and loans.</small></html>");
		screenshotInfo.setForeground(Color.LIGHT_GRAY);
		screenshotInfo.setBorder(new EmptyBorder(5, 10, 5, 10));
		screenshotPanel.add(screenshotInfo);

		JPanel screenshotBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
		screenshotBtnPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JButton openFolderBtn = new JButton("Open Folder");
		openFolderBtn.setBackground(ColorScheme.BRAND_ORANGE);
		openFolderBtn.setForeground(Color.WHITE);
		openFolderBtn.addActionListener(e -> {
			String username = getCurrentPlayerName();
			plugin.getProofScreenshot().openScreenshotFolder(username, group.getName());
		});
		screenshotBtnPanel.add(openFolderBtn);
		screenshotPanel.add(screenshotBtnPanel);

		JLabel pathLabel = new JLabel("<html><small><i>Location: ~/LendingTracker/proof/</i></small></html>");
		pathLabel.setForeground(Color.GRAY);
		pathLabel.setBorder(new EmptyBorder(0, 10, 5, 10));
		screenshotPanel.add(pathLabel);

		panel.add(screenshotPanel);
		panel.add(Box.createVerticalStrut(10));

		// ==================== SECTION 4: MEMBER ACTIONS (All Members except owner for Leave) ====================
		JPanel memberActionsPanel = new JPanel();
		memberActionsPanel.setLayout(new BoxLayout(memberActionsPanel, BoxLayout.Y_AXIS));
		memberActionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		memberActionsPanel.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(Color.ORANGE.darker()),
			"Member Actions",
			TitledBorder.LEFT, TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 10),
			Color.ORANGE
		));

		JPanel memberBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
		memberBtnPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		if (!isOwner) {
			// Only non-owners can leave
			JButton leaveBtn = new JButton("Leave Group");
			leaveBtn.setBackground(Color.ORANGE.darker());
			leaveBtn.setForeground(Color.WHITE);
			leaveBtn.setToolTipText("Leave this group");
			leaveBtn.addActionListener(e -> leaveGroup(group));
			memberBtnPanel.add(leaveBtn);
		} else {
			JLabel ownerNote = new JLabel("<html><small><i>Owners must transfer or delete group to leave.</i></small></html>");
			ownerNote.setForeground(Color.GRAY);
			memberBtnPanel.add(ownerNote);
		}

		memberActionsPanel.add(memberBtnPanel);
		panel.add(memberActionsPanel);
		panel.add(Box.createVerticalStrut(10));

		// ==================== SECTION 5: DANGER ZONE (Owner Only) ====================
		if (isOwner) {
			JPanel dangerZonePanel = new JPanel();
			dangerZonePanel.setLayout(new BoxLayout(dangerZonePanel, BoxLayout.Y_AXIS));
			dangerZonePanel.setBackground(new Color(60, 30, 30)); // Dark red background
			dangerZonePanel.setBorder(BorderFactory.createTitledBorder(
				BorderFactory.createLineBorder(Color.RED.darker(), 2),
				"⚠ Danger Zone (Owner Only)",
				TitledBorder.LEFT, TitledBorder.TOP,
				new Font("Arial", Font.BOLD, 10),
				Color.RED
			));

			// Transfer Ownership Section
			JPanel transferPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
			transferPanel.setBackground(new Color(60, 30, 30));

			JLabel transferLabel = new JLabel("Transfer to:");
			transferLabel.setForeground(Color.WHITE);
			transferPanel.add(transferLabel);

			// Dropdown of members (excluding owner)
			JComboBox<String> memberDropdown = new JComboBox<>();
			memberDropdown.setBackground(ColorScheme.DARK_GRAY_COLOR);
			memberDropdown.setForeground(Color.WHITE);
			for (GroupMember m : group.getMembers()) {
				if (!m.getName().equalsIgnoreCase(currentPlayer)) {
					memberDropdown.addItem(m.getName() + " (" + formatRole(m.getRole()) + ")");
				}
			}
			if (memberDropdown.getItemCount() == 0) {
				memberDropdown.addItem("No other members");
				memberDropdown.setEnabled(false);
			}
			transferPanel.add(memberDropdown);

			JButton transferBtn = new JButton("Transfer Ownership");
			transferBtn.setBackground(new Color(180, 80, 0)); // Orange-red
			transferBtn.setForeground(Color.WHITE);
			transferBtn.setEnabled(memberDropdown.getItemCount() > 0 && memberDropdown.isEnabled());
			transferBtn.addActionListener(e -> {
				String selected = (String) memberDropdown.getSelectedItem();
				if (selected == null || selected.equals("No other members")) return;

				// Extract player name from "Name (Role)" format
				String newOwnerName = selected.substring(0, selected.lastIndexOf(" ("));

				int confirm = JOptionPane.showConfirmDialog(this,
					"<html><b>TRANSFER OWNERSHIP</b><br><br>" +
					"You are about to transfer ownership to:<br><b>" + newOwnerName + "</b><br><br>" +
					"<font color='red'>WARNING: This action cannot be undone!</font><br>" +
					"You will become a Co-Owner after transfer.<br><br>" +
					"Are you absolutely sure?</html>",
					"Confirm Ownership Transfer",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);

				if (confirm == JOptionPane.YES_OPTION) {
					// Double confirmation for safety
					int doubleConfirm = JOptionPane.showConfirmDialog(this,
						"Final confirmation: Transfer ownership to " + newOwnerName + "?",
						"Final Confirmation",
						JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE);

					if (doubleConfirm == JOptionPane.YES_OPTION) {
						transferOwnership(group, newOwnerName, currentPlayer);
					}
				}
			});
			transferPanel.add(transferBtn);

			dangerZonePanel.add(transferPanel);

			// Separator
			JSeparator separator = new JSeparator();
			separator.setForeground(Color.RED.darker());
			dangerZonePanel.add(Box.createVerticalStrut(5));
			dangerZonePanel.add(separator);
			dangerZonePanel.add(Box.createVerticalStrut(5));

			// Delete Group Section
			JPanel deletePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
			deletePanel.setBackground(new Color(60, 30, 30));

			JButton deleteBtn = new JButton("Delete Group Permanently");
			deleteBtn.setBackground(Color.RED.darker());
			deleteBtn.setForeground(Color.WHITE);
			deleteBtn.setToolTipText("Permanently delete this group and all data");
			deleteBtn.addActionListener(e -> deleteGroup(group));
			deletePanel.add(deleteBtn);

			dangerZonePanel.add(deletePanel);

			panel.add(dangerZonePanel);
		}

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane scrollPane = new JScrollPane(panel);
		styleScrollBar(scrollPane);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		wrapper.add(scrollPane, BorderLayout.CENTER);
		return wrapper;
	}

	/**
	 * Transfer group ownership to another member
	 */
	private void transferOwnership(LendingGroup group, String newOwnerName, String currentOwner)
	{
		try {
			// Verify new owner exists in group
			GroupMember newOwner = group.getMember(newOwnerName);
			if (newOwner == null) {
				JOptionPane.showMessageDialog(this, "Member not found: " + newOwnerName, "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			// Update roles using GroupConfigStore's setMemberRole method
			// First promote new owner
			boolean promoted = groupConfigStore.setMemberRole(group.getId(), currentOwner, newOwnerName, ROLE_OWNER);
			if (!promoted) {
				JOptionPane.showMessageDialog(this, "Failed to promote new owner.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			// Then demote old owner to co-owner
			boolean demoted = groupConfigStore.setMemberRole(group.getId(), newOwnerName, currentOwner, ROLE_CO_OWNER);
			if (!demoted) {
				// Rollback - this shouldn't happen but just in case
				groupConfigStore.setMemberRole(group.getId(), currentOwner, newOwnerName, newOwner.getRole());
				JOptionPane.showMessageDialog(this, "Failed to complete transfer. Changes rolled back.", "Error", JOptionPane.ERROR_MESSAGE);
				return;
			}

			JOptionPane.showMessageDialog(this,
				"Ownership transferred to " + newOwnerName + "!\nYou are now a Co-Owner.",
				"Ownership Transferred",
				JOptionPane.INFORMATION_MESSAGE);

			// Refresh the panel
			refreshContent();

			log.info("Ownership of group '{}' transferred from {} to {}", group.getName(), currentOwner, newOwnerName);
		} catch (Exception e) {
			log.error("Failed to transfer ownership", e);
			JOptionPane.showMessageDialog(this, "Failed to transfer ownership: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Helper to create info labels for the settings panel
	 */
	private JLabel createInfoLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.LIGHT_GRAY);
		label.setFont(new Font("Arial", Font.PLAIN, 11));
		return label;
	}

	/**
	 * Helper to create value labels for the settings panel
	 */
	private JLabel createValueLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(new Font("Arial", Font.BOLD, 11));
		return label;
	}

	/**
	 * Format role name for display
	 */
	private String formatRole(String role)
	{
		if (role == null) return "Member";
		switch (role.toLowerCase()) {
			case "owner": return "Owner";
			case "co-owner": return "Co-Owner";
			case "admin": return "Admin";
			case "mod":
			case "moderator": return "Moderator";
			default: return "Member";
		}
	}

	/**
	 * Get color for role display
	 */
	private Color getRoleColor(String role)
	{
		if (role == null) return Color.WHITE;
		switch (role.toLowerCase()) {
			case "owner": return new Color(255, 215, 0); // Gold
			case "co-owner": return new Color(192, 192, 192); // Silver
			case "admin": return new Color(0, 191, 255); // Deep sky blue
			case "mod":
			case "moderator": return new Color(50, 205, 50); // Lime green
			default: return Color.WHITE;
		}
	}

	// ==================== HELPER METHODS ====================

	private LendingGroup getCurrentGroup()
	{
		String groupId = groupConfigStore.getCurrentGroupId();
		if (groupId == null) return null;
		return groupConfigStore.getGroup(groupId);
	}

	private void loadGroupsIntoSelector()
	{
		groupSelector.removeAllItems();
		try {
			java.util.Collection<LendingGroup> groups = groupConfigStore.getAllGroups();
			if (groups != null && !groups.isEmpty()) {
				for (LendingGroup group : groups) {
					if (group != null && group.getName() != null) {
						groupSelector.addItem(group.getName());
					}
				}
				String currentGroupId = groupConfigStore.getCurrentGroupId();
				if (currentGroupId != null) {
					String currentName = groupConfigStore.getGroupNameById(currentGroupId);
					groupSelector.setSelectedItem(currentName);
				}
			} else {
				groupSelector.addItem("No Groups");
			}
		} catch (Exception e) {
			log.error("Error loading groups", e);
			groupSelector.addItem("No Groups");
		}
	}

	private boolean canManageCodes(String role)
	{
		return ROLE_OWNER.equals(role) || ROLE_CO_OWNER.equals(role) || ROLE_MODERATOR.equals(role);
	}

	private boolean canApproveInvites(String role)
	{
		return ROLE_OWNER.equals(role) || ROLE_CO_OWNER.equals(role) || ROLE_MODERATOR.equals(role);
	}

	private boolean canManagePermissions(String myRole, String targetRole)
	{
		if (ROLE_OWNER.equals(myRole)) return true;
		if (ROLE_CO_OWNER.equals(myRole)) {
			return !ROLE_OWNER.equals(targetRole) && !ROLE_CO_OWNER.equals(targetRole);
		}
		return false;
	}

	private boolean canRemoveMember(String myRole, String targetRole)
	{
		if (ROLE_OWNER.equals(myRole)) {
			return !ROLE_OWNER.equals(targetRole); // Can't remove other owners
		}
		if (ROLE_CO_OWNER.equals(myRole)) {
			return ROLE_MODERATOR.equals(targetRole) || ROLE_MEMBER.equals(targetRole);
		}
		if (ROLE_MODERATOR.equals(myRole)) {
			return ROLE_MEMBER.equals(targetRole);
		}
		return false;
	}

	private String getRoleDisplay(String role)
	{
		switch (role) {
			case ROLE_OWNER: return "Owner";
			case ROLE_CO_OWNER: return "Co-Owner";
			case ROLE_MODERATOR: return "Moderator";
			case ROLE_MEMBER: return "Member";
			default: return "Unknown";
		}
	}

	private String getRoleIcon(String role)
	{
		switch (role) {
			case ROLE_OWNER: return "👑";
			case ROLE_CO_OWNER: return "⭐";
			case ROLE_MODERATOR: return "🛡";
			case ROLE_MEMBER: return "🟢";
			default: return "⚫";
		}
	}

	// NOTE: getRoleColor is defined earlier in the file (around line 1003)

	private void copyToClipboard(String text)
	{
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(new StringSelection(text), null);
		JOptionPane.showMessageDialog(this, "Copied to clipboard!", "Success", JOptionPane.INFORMATION_MESSAGE);
	}

	private void approveInvite(LendingGroup group, PendingInvite invite)
	{
		// Add as member
		GroupMember newMember = new GroupMember(invite.getPlayerName(), ROLE_MEMBER);
		group.addMember(newMember);

		// Mark code as used if it was a group code
		if ("group".equals(invite.getCodeType())) {
			group.markGroupCodeUsed(invite.getPlayerName());
		} else if ("clan".equals(invite.getCodeType())) {
			group.setClanCodeUseCount(group.getClanCodeUseCount() + 1);
		}

		// Remove invite
		group.getPendingInvites().remove(invite);
		saveGroup(group);

		JOptionPane.showMessageDialog(this, invite.getPlayerName() + " has been added to the group!", "Invite Approved", JOptionPane.INFORMATION_MESSAGE);
		refreshContent();
	}

	private void denyInvite(LendingGroup group, PendingInvite invite)
	{
		group.getPendingInvites().remove(invite);
		saveGroup(group);
		refreshContent();
	}

	private void removeMember(LendingGroup group, GroupMember member)
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"Remove " + member.getName() + " from the group?",
			"Confirm Removal",
			JOptionPane.YES_NO_OPTION
		);

		if (confirm == JOptionPane.YES_OPTION) {
			group.removeMember(member.getName());
			saveGroup(group);
			refreshContent();
		}
	}

	private void leaveGroup(LendingGroup group)
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"Are you sure you want to leave this group?",
			"Leave Group",
			JOptionPane.YES_NO_OPTION
		);

		if (confirm == JOptionPane.YES_OPTION) {
			String currentPlayer = getCurrentPlayerName();
			group.removeMember(currentPlayer);
			saveGroup(group);
			loadGroupsIntoSelector();
			refreshContent();
		}
	}

	private void deleteGroup(LendingGroup group)
	{
		int confirm = JOptionPane.showConfirmDialog(
			this,
			"PERMANENTLY DELETE this group? This cannot be undone!",
			"Delete Group",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);

		if (confirm == JOptionPane.YES_OPTION) {
			groupConfigStore.deleteGroup(group.getId());
			loadGroupsIntoSelector();
			refreshContent();
		}
	}

	private void showCreateGroupDialog()
	{
		// Create group functionality - dialog coming soon
		JOptionPane.showMessageDialog(this, "Create Group dialog coming soon", "Info", JOptionPane.INFORMATION_MESSAGE);
	}

	private void showManagePermissionsDialog(LendingGroup group, GroupMember member)
	{
		String[] roles = {ROLE_MEMBER, ROLE_MODERATOR, ROLE_CO_OWNER};
		String currentPlayer = getCurrentPlayerName();
		GroupMember currentMember = group.getMember(currentPlayer);

		// Owner can promote to owner, co-owner can't
		if (ROLE_OWNER.equals(currentMember.getRole())) {
			roles = new String[]{ROLE_MEMBER, ROLE_MODERATOR, ROLE_CO_OWNER, ROLE_OWNER};
		}

		String newRole = (String) JOptionPane.showInputDialog(
			this,
			"Select new role for " + member.getName() + ":",
			"Manage Permissions",
			JOptionPane.QUESTION_MESSAGE,
			null,
			roles,
			member.getRole()
		);

		if (newRole != null && !newRole.equals(member.getRole())) {
			member.setRole(newRole);
			saveGroup(group);
			refreshContent();
		}
	}

	private JLabel createMessageLabel(String text)
	{
		JLabel label = new JLabel("<html><center>" + text + "</center></html>", SwingConstants.CENTER);
		label.setForeground(Color.LIGHT_GRAY);
		return label;
	}

	private void addInfoRow(JPanel panel, GridBagConstraints gbc, String label, String value)
	{
		JLabel labelComp = new JLabel(label);
		labelComp.setForeground(Color.LIGHT_GRAY);
		panel.add(labelComp, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JLabel valueComp = new JLabel(value);
		valueComp.setForeground(Color.WHITE);
		valueComp.setFont(valueComp.getFont().deriveFont(Font.BOLD));
		panel.add(valueComp, gbc);

		gbc.gridx = 0;
		gbc.gridy++;
		gbc.weightx = 0;
	}

	private void addStatLabel(JPanel panel, String label, String value, Color color)
	{
		JPanel statPanel = new JPanel();
		statPanel.setLayout(new BoxLayout(statPanel, BoxLayout.Y_AXIS));
		statPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel valueLabel = new JLabel(value);
		valueLabel.setFont(new Font("Arial", Font.BOLD, 20));
		valueLabel.setForeground(color);
		valueLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		statPanel.add(valueLabel);

		JLabel labelComp = new JLabel(label);
		labelComp.setForeground(Color.LIGHT_GRAY);
		labelComp.setAlignmentX(Component.CENTER_ALIGNMENT);
		statPanel.add(labelComp);

		panel.add(statPanel);
	}

	public void refresh()
	{
		loadGroupsIntoSelector();
		refreshContent();
	}

	private String getCurrentPlayerName()
	{
		if (client == null || client.getLocalPlayer() == null) {
			return "Unknown";
		}
		String name = client.getLocalPlayer().getName();
		return (name != null && !name.isEmpty()) ? name : "Unknown";
	}

	private void saveGroup(LendingGroup group)
	{
		// Groups are persisted automatically by GroupConfigStore when methods like
		// addMember, removeMember, etc. are called. For direct group modifications,
		// we need to trigger a re-persist. Since GroupConfigStore doesn't have a save method,
		// the modifications to the group object are already in the stored reference.
		// Just trigger a refresh to update the UI.
		refresh();
	}

	private void showCustomCodeDialog(LendingGroup group, boolean isGroupCode)
	{
		String[] options = {"Generate New", "Enter Custom", "Cancel"};
		int choice = JOptionPane.showOptionDialog(this,
			"Choose how to update the " + (isGroupCode ? "Group" : "Clan") + " Code:",
			"Update Code",
			JOptionPane.DEFAULT_OPTION,
			JOptionPane.QUESTION_MESSAGE,
			null,
			options,
			options[0]);

		if (choice == 0)
		{
			// Generate new
			if (isGroupCode)
			{
				group.regenerateGroupCode();
			}
			else
			{
				group.regenerateClanCode();
			}
			saveGroup(group);
			refreshContent();
		}
		else if (choice == 1)
		{
			// Enter custom
			String customCode = JOptionPane.showInputDialog(this,
				"Enter custom code (format: ABC-123-XYZ):",
				isGroupCode ? group.getInviteCode() : group.getClanCode());

			if (customCode != null && !customCode.trim().isEmpty())
			{
				if (isGroupCode)
				{
					groupConfigStore.setCustomGroupCode(group.getId(), customCode.trim());
				}
				else
				{
					groupConfigStore.setCustomClanCode(group.getId(), customCode.trim());
				}
				refreshContent();
			}
		}
	}
	private void styleScrollBar(JScrollPane scrollPane)
	{
		// Vertical scrollbar
		scrollPane.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = ColorScheme.BRAND_ORANGE;
				this.trackColor = ColorScheme.DARKER_GRAY_COLOR;
			}
			@Override
			protected JButton createDecreaseButton(int orientation) {
				JButton button = super.createDecreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
			@Override
			protected JButton createIncreaseButton(int orientation) {
				JButton button = super.createIncreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
		});
		// Horizontal scrollbar
		scrollPane.getHorizontalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = ColorScheme.BRAND_ORANGE;
				this.trackColor = ColorScheme.DARKER_GRAY_COLOR;
			}
			@Override
			protected JButton createDecreaseButton(int orientation) {
				JButton button = super.createDecreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
			@Override
			protected JButton createIncreaseButton(int orientation) {
				JButton button = super.createIncreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
		});
	}
}
