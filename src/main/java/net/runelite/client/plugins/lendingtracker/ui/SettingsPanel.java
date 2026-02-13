package net.runelite.client.plugins.lendingtracker.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.lendingtracker.LendingTrackerPlugin;
import net.runelite.client.plugins.lendingtracker.model.GroupMember;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.Insets;
import java.util.List;

/**
 * SettingsPanel - Group management and plugin settings
 * Phase 3: Full implementation with invite code generation
 */
@Slf4j
public class SettingsPanel extends JPanel
{
	private final LendingTrackerPlugin plugin;
	private final GroupConfigStore groupConfigStore;

	// UI Components
	private JPanel groupInfoPanel;
	private JLabel groupNameLabel;
	private JLabel memberCountLabel;
	private JLabel roleLabel;
	private JLabel inviteCodeLabel;
	private JButton generateCodeButton;
	private JButton copyCodeButton;
	private JPanel membersListPanel;
	private JPanel permissionsPanel;
	private JCheckBox coOwnerKickCheckbox;
	private JCheckBox adminKickCheckbox;
	private JCheckBox modKickCheckbox;
	// ADDED: Invite code generation permission checkboxes
	private JCheckBox coOwnerInviteCheckbox;
	private JCheckBox adminInviteCheckbox;
	private JCheckBox modInviteCheckbox;

	// ADDED: Content panels for permission-based visibility
	private JPanel contentPanel;
	private JPanel notLoggedInPanel;
	private JPanel notStaffPanel;
	private JPanel staffContentPanel;
	private JScrollPane scrollPane;
	private JPanel dangerZonePanel;
	private JButton deleteGroupButton;
	private JButton leaveGroupButton;
	private JButton transferOwnershipButton;
	private JComboBox<String> transferOwnerDropdown;
	// Sections that need role-based visibility
	private JPanel inviteCodePanel;
	private JPanel membersSection;
	private JPanel webhookSection;
	private JPanel screenshotSection;

	public SettingsPanel(LendingTrackerPlugin plugin)
	{
		this.plugin = plugin;
		this.groupConfigStore = plugin.getGroupConfigStore();

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		// FIXED: Ensure panel doesn't have a fixed width that causes cutoff
		setMinimumSize(new Dimension(0, 0));

		// ADDED: Create "Not logged in" panel
		notLoggedInPanel = createNotLoggedInPanel();

		// ADDED: Create "Staff only" panel
		notStaffPanel = createNotStaffPanel();

		// FIXED: Use wrapper panel with BorderLayout to ensure content displays from top
		JPanel staffContentWrapper = new JPanel(new BorderLayout());
		staffContentWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create scrollable staff content
		staffContentPanel = new JPanel();
		staffContentPanel.setLayout(new BoxLayout(staffContentPanel, BoxLayout.Y_AXIS));
		staffContentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// FIXED: Minimal padding to maximize content space in narrow sidebar
		staffContentPanel.setBorder(new EmptyBorder(5, 3, 5, 3));

		// Add sections to staff content - REORGANIZED for better flow
		// Section 1: Group Overview (visible to all)
		staffContentPanel.add(createGroupInfoSection());
		staffContentPanel.add(Box.createVerticalStrut(10));

		// Section 2: Group Members (staff only - has kick/role controls)
		membersSection = createMembersSection();
		staffContentPanel.add(membersSection);
		staffContentPanel.add(Box.createVerticalStrut(10));

		// Section 3: Invites (staff only - generate codes)
		inviteCodePanel = createInviteCodeSection();
		staffContentPanel.add(inviteCodePanel);
		staffContentPanel.add(Box.createVerticalStrut(10));

		// Section 4: Notifications - Discord Webhook (all members can see)
		webhookSection = createDiscordWebhookSection();
		staffContentPanel.add(webhookSection);
		staffContentPanel.add(Box.createVerticalStrut(10));

		// Section 5: Screenshots folder access (all members can see)
		screenshotSection = createScreenshotSection();
		staffContentPanel.add(screenshotSection);
		staffContentPanel.add(Box.createVerticalStrut(10));

		// Section 6: Permissions (owner/co-owner only)
		staffContentPanel.add(createPermissionsSection());
		staffContentPanel.add(Box.createVerticalStrut(10));

		// Section 7: Danger Zone (leave/transfer/delete)
		staffContentPanel.add(createDangerZoneSection());

		// FIXED: Add staff content to NORTH of wrapper so sections display from top
		staffContentWrapper.add(staffContentPanel, BorderLayout.NORTH);

		scrollPane = new JScrollPane(staffContentWrapper);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
		scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		// FIXED: Ensure scroll speed is reasonable
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		// Main content panel that switches between views
		contentPanel = new JPanel(new BorderLayout());
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Initially show not logged in (will be updated by refresh)
		contentPanel.add(notLoggedInPanel, BorderLayout.CENTER);

		add(contentPanel, BorderLayout.CENTER);
	}

	/**
	 * ADDED: Create panel shown when user is not logged in
	 */
	private JPanel createNotLoggedInPanel()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(50, 20, 50, 20));

		JLabel messageLabel = new JLabel("<html><center>" +
			"<b style='color: #ff9900; font-size: 14px;'>Not Logged In</b><br><br>" +
			"Please log in to your OSRS account<br>" +
			"to access group settings.<br><br>" +
			"<i>Group management features require<br>" +
			"you to be logged in.</i>" +
			"</center></html>");
		messageLabel.setFont(FontManager.getRunescapeFont());
		messageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		messageLabel.setHorizontalAlignment(SwingConstants.CENTER);

		panel.add(messageLabel, BorderLayout.CENTER);

		return panel;
	}

	/**
	 * ADDED: Create panel shown when user is not staff
	 */
	private JPanel createNotStaffPanel()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(50, 20, 50, 20));

		JLabel messageLabel = new JLabel("<html><center>" +
			"<b style='color: #ff9900; font-size: 14px;'>Staff Only Section</b><br><br>" +
			"This section is only available to<br>" +
			"group staff members:<br><br>" +
			"&#8226; Owner<br>" +
			"&#8226; Co-Owner<br>" +
			"&#8226; Admin<br><br>" +
			"<i>Contact a group admin if you need<br>" +
			"access to these settings.</i>" +
			"</center></html>");
		messageLabel.setFont(FontManager.getRunescapeFont());
		messageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		messageLabel.setHorizontalAlignment(SwingConstants.CENTER);

		panel.add(messageLabel, BorderLayout.CENTER);

		return panel;
	}

	/**
	 * Create the group info section
	 */
	private JPanel createGroupInfoSection()
	{
		groupInfoPanel = createSectionPanel("Active Group");

		JPanel infoContent = new JPanel(new GridLayout(3, 2, 5, 5));
		infoContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		infoContent.add(createLabel("Name:", false));
		groupNameLabel = createLabel("No active group", true);
		infoContent.add(groupNameLabel);

		infoContent.add(createLabel("Members:", false));
		memberCountLabel = createLabel("0", true);
		infoContent.add(memberCountLabel);

		infoContent.add(createLabel("Your Role:", false));
		roleLabel = createLabel("-", true);
		infoContent.add(roleLabel);

		groupInfoPanel.add(infoContent, BorderLayout.CENTER);

		return groupInfoPanel;
	}

	/**
	 * Create the invite code section
	 * FIXED: Compact layout to prevent cutoff in narrow sidebar
	 */
	private JPanel createInviteCodeSection()
	{
		JPanel section = createSectionPanel("Invite Code");

		JPanel codePanel = new JPanel();
		codePanel.setLayout(new BoxLayout(codePanel, BoxLayout.Y_AXIS));
		codePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Info text - compact
		JLabel infoLabel = new JLabel("<html>Single-use code (void after 1 join)</html>");
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		codePanel.add(infoLabel);
		codePanel.add(Box.createVerticalStrut(5));

		// Code display - compact
		inviteCodeLabel = new JLabel("No active code");
		inviteCodeLabel.setFont(FontManager.getRunescapeFont());
		inviteCodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		inviteCodeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		inviteCodeLabel.setBorder(new CompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
			new EmptyBorder(5, 8, 5, 8)
		));
		codePanel.add(inviteCodeLabel);
		codePanel.add(Box.createVerticalStrut(5));

		// Button panel - horizontal with compact buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		generateCodeButton = new JButton("Generate");
		generateCodeButton.setFont(FontManager.getRunescapeSmallFont());
		generateCodeButton.setBackground(ColorScheme.BRAND_ORANGE);
		generateCodeButton.setForeground(Color.WHITE);
		generateCodeButton.setFocusPainted(false);
		generateCodeButton.setMargin(new Insets(2, 6, 2, 6));
		generateCodeButton.addActionListener(e -> generateInviteCode());
		buttonPanel.add(generateCodeButton);

		copyCodeButton = new JButton("Copy");
		copyCodeButton.setFont(FontManager.getRunescapeSmallFont());
		copyCodeButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
		copyCodeButton.setForeground(Color.WHITE);
		copyCodeButton.setFocusPainted(false);
		copyCodeButton.setEnabled(false);
		copyCodeButton.setMargin(new Insets(2, 6, 2, 6));
		copyCodeButton.addActionListener(e -> copyInviteCode());
		buttonPanel.add(copyCodeButton);

		codePanel.add(buttonPanel);

		section.add(codePanel, BorderLayout.CENTER);

		return section;
	}

	// UI Components for Discord webhook
	private JTextField webhookUrlField;
	private JButton saveWebhookButton;
	private JButton testWebhookButton;

	/**
	 * Create the Discord webhook configuration section
	 * Only visible to owners, co-owners, and admins
	 */
	private JPanel createDiscordWebhookSection()
	{
		JPanel section = createSectionPanel("Discord Webhook");

		JPanel webhookContent = new JPanel();
		webhookContent.setLayout(new BoxLayout(webhookContent, BoxLayout.Y_AXIS));
		webhookContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Info label
		JLabel infoLabel = new JLabel("<html>Webhook URL for notifications:</html>");
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		webhookContent.add(infoLabel);
		webhookContent.add(Box.createVerticalStrut(5));

		// URL text field
		webhookUrlField = new JTextField();
		webhookUrlField.setFont(FontManager.getRunescapeSmallFont());
		webhookUrlField.setBackground(ColorScheme.DARK_GRAY_COLOR);
		webhookUrlField.setForeground(Color.WHITE);
		webhookUrlField.setCaretColor(Color.WHITE);
		webhookUrlField.setAlignmentX(Component.LEFT_ALIGNMENT);
		webhookUrlField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
		webhookUrlField.setToolTipText("Discord webhook URL (from Server Settings > Integrations)");
		webhookContent.add(webhookUrlField);
		webhookContent.add(Box.createVerticalStrut(5));

		// Button panel
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
		buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		saveWebhookButton = new JButton("Save");
		saveWebhookButton.setFont(FontManager.getRunescapeSmallFont());
		saveWebhookButton.setBackground(new Color(88, 101, 242)); // Discord blurple
		saveWebhookButton.setForeground(Color.WHITE);
		saveWebhookButton.setFocusPainted(false);
		saveWebhookButton.setMargin(new Insets(2, 6, 2, 6));
		saveWebhookButton.setToolTipText("Save webhook URL");
		saveWebhookButton.addActionListener(e -> saveWebhookUrl());
		buttonPanel.add(saveWebhookButton);

		testWebhookButton = new JButton("Test");
		testWebhookButton.setFont(FontManager.getRunescapeSmallFont());
		testWebhookButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		testWebhookButton.setForeground(Color.WHITE);
		testWebhookButton.setFocusPainted(false);
		testWebhookButton.setMargin(new Insets(2, 6, 2, 6));
		testWebhookButton.setToolTipText("Send a test message to webhook");
		testWebhookButton.addActionListener(e -> testWebhook());
		buttonPanel.add(testWebhookButton);

		webhookContent.add(buttonPanel);
		webhookContent.add(Box.createVerticalStrut(3));

		// Help text
		JLabel helpLabel = new JLabel("<html><i>Server Settings > Integrations > Webhooks</i></html>");
		helpLabel.setFont(FontManager.getRunescapeSmallFont());
		helpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		helpLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		webhookContent.add(helpLabel);

		section.add(webhookContent, BorderLayout.CENTER);

		return section;
	}

	/**
	 * Save the webhook URL to config
	 */
	private void saveWebhookUrl()
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(this,
				"No active group selected!",
				"Error",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		String url = webhookUrlField.getText().trim();

		// Validate URL format
		if (!url.isEmpty() && !url.startsWith("https://discord.com/api/webhooks/"))
		{
			JOptionPane.showMessageDialog(this,
				"Invalid webhook URL.\nMust start with: https://discord.com/api/webhooks/",
				"Invalid URL",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		// Save to config
		groupConfigStore.setGroupWebhookUrl(activeGroup.getId(), url.isEmpty() ? null : url);

		JOptionPane.showMessageDialog(this,
			url.isEmpty() ? "Webhook URL cleared." : "Webhook URL saved!",
			"Success",
			JOptionPane.INFORMATION_MESSAGE);
	}

	/**
	 * Test the webhook by sending a test message
	 */
	private void testWebhook()
	{
		String url = webhookUrlField.getText().trim();

		if (url.isEmpty())
		{
			JOptionPane.showMessageDialog(this,
				"Please enter a webhook URL first.",
				"No URL",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		if (!url.startsWith("https://discord.com/api/webhooks/"))
		{
			JOptionPane.showMessageDialog(this,
				"Invalid webhook URL format.",
				"Invalid URL",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		String groupName = activeGroup != null ? activeGroup.getName() : "Unknown Group";

		// Send test message via plugin
		plugin.testDiscordWebhook(url, groupName);

		JOptionPane.showMessageDialog(this,
			"Test message sent!\nCheck your Discord channel.",
			"Test Sent",
			JOptionPane.INFORMATION_MESSAGE);
	}

	// UI Component for screenshot section
	private JButton openScreenshotFolderButton;

	/**
	 * Create the screenshot section with folder access
	 */
	private JPanel createScreenshotSection()
	{
		JPanel section = createSectionPanel("Screenshots");

		JPanel screenshotContent = new JPanel();
		screenshotContent.setLayout(new BoxLayout(screenshotContent, BoxLayout.Y_AXIS));
		screenshotContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Info label
		JLabel infoLabel = new JLabel("<html>Proof screenshots are saved locally:</html>");
		infoLabel.setFont(FontManager.getRunescapeSmallFont());
		infoLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		infoLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		screenshotContent.add(infoLabel);
		screenshotContent.add(Box.createVerticalStrut(5));

		// Open folder button
		openScreenshotFolderButton = new JButton("Open Screenshot Folder");
		openScreenshotFolderButton.setFont(FontManager.getRunescapeSmallFont());
		openScreenshotFolderButton.setBackground(ColorScheme.BRAND_ORANGE);
		openScreenshotFolderButton.setForeground(Color.WHITE);
		openScreenshotFolderButton.setFocusPainted(false);
		openScreenshotFolderButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		openScreenshotFolderButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		openScreenshotFolderButton.setMargin(new Insets(2, 6, 2, 6));
		openScreenshotFolderButton.setToolTipText("Open folder containing proof screenshots");
		openScreenshotFolderButton.addActionListener(e -> openScreenshotFolder());
		screenshotContent.add(openScreenshotFolderButton);
		screenshotContent.add(Box.createVerticalStrut(3));

		// Path info
		JLabel pathLabel = new JLabel("<html><i>~/LendingTracker/proof/</i></html>");
		pathLabel.setFont(FontManager.getRunescapeSmallFont());
		pathLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pathLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		screenshotContent.add(pathLabel);

		section.add(screenshotContent, BorderLayout.CENTER);

		return section;
	}

	/**
	 * Open the screenshot folder in file explorer
	 */
	private void openScreenshotFolder()
	{
		String username = getCurrentUsername();
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		String groupName = activeGroup != null ? activeGroup.getName() : "default";

		plugin.getProofScreenshot().openScreenshotFolder(username, groupName);
	}

	/**
	 * Create the permissions section for kick settings
	 * Only visible to owners and co-owners
	 * FIXED: Compact layout to prevent cutoff
	 */
	private JPanel createPermissionsSection()
	{
		permissionsPanel = createSectionPanel("Permissions");

		JPanel permContent = new JPanel();
		permContent.setLayout(new BoxLayout(permContent, BoxLayout.Y_AXIS));
		permContent.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// --- Kick Permissions ---
		JLabel kickLabel = new JLabel("<html>Who can kick:</html>");
		kickLabel.setFont(FontManager.getRunescapeSmallFont());
		kickLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		kickLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		permContent.add(kickLabel);
		permContent.add(Box.createVerticalStrut(4));

		coOwnerKickCheckbox = new JCheckBox("Co-Owners");
		coOwnerKickCheckbox.setFont(FontManager.getRunescapeSmallFont());
		coOwnerKickCheckbox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		coOwnerKickCheckbox.setForeground(Color.WHITE);
		coOwnerKickCheckbox.setSelected(true);
		coOwnerKickCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		coOwnerKickCheckbox.addActionListener(e -> updateKickPermission("co-owner", coOwnerKickCheckbox.isSelected()));
		permContent.add(coOwnerKickCheckbox);

		adminKickCheckbox = new JCheckBox("Admins");
		adminKickCheckbox.setFont(FontManager.getRunescapeSmallFont());
		adminKickCheckbox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		adminKickCheckbox.setForeground(Color.WHITE);
		adminKickCheckbox.setSelected(true);
		adminKickCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		adminKickCheckbox.addActionListener(e -> updateKickPermission("admin", adminKickCheckbox.isSelected()));
		permContent.add(adminKickCheckbox);

		modKickCheckbox = new JCheckBox("Mods");
		modKickCheckbox.setFont(FontManager.getRunescapeSmallFont());
		modKickCheckbox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		modKickCheckbox.setForeground(Color.WHITE);
		modKickCheckbox.setSelected(true);
		modKickCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		modKickCheckbox.addActionListener(e -> updateKickPermission("mod", modKickCheckbox.isSelected()));
		permContent.add(modKickCheckbox);

		permContent.add(Box.createVerticalStrut(3));

		JLabel kickNote = new JLabel("<html><i>Lower ranks only</i></html>");
		kickNote.setFont(FontManager.getRunescapeSmallFont());
		kickNote.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		kickNote.setAlignmentX(Component.LEFT_ALIGNMENT);
		permContent.add(kickNote);

		permContent.add(Box.createVerticalStrut(8));

		// --- Invite Code Permissions ---
		JLabel inviteLabel = new JLabel("<html>Who can generate invite codes:</html>");
		inviteLabel.setFont(FontManager.getRunescapeSmallFont());
		inviteLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		inviteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		permContent.add(inviteLabel);
		permContent.add(Box.createVerticalStrut(4));

		coOwnerInviteCheckbox = new JCheckBox("Co-Owners");
		coOwnerInviteCheckbox.setFont(FontManager.getRunescapeSmallFont());
		coOwnerInviteCheckbox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		coOwnerInviteCheckbox.setForeground(Color.WHITE);
		coOwnerInviteCheckbox.setSelected(true);
		coOwnerInviteCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		coOwnerInviteCheckbox.addActionListener(e -> updateInvitePermission("co-owner", coOwnerInviteCheckbox.isSelected()));
		permContent.add(coOwnerInviteCheckbox);

		adminInviteCheckbox = new JCheckBox("Admins");
		adminInviteCheckbox.setFont(FontManager.getRunescapeSmallFont());
		adminInviteCheckbox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		adminInviteCheckbox.setForeground(Color.WHITE);
		adminInviteCheckbox.setSelected(true);
		adminInviteCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		adminInviteCheckbox.addActionListener(e -> updateInvitePermission("admin", adminInviteCheckbox.isSelected()));
		permContent.add(adminInviteCheckbox);

		modInviteCheckbox = new JCheckBox("Mods");
		modInviteCheckbox.setFont(FontManager.getRunescapeSmallFont());
		modInviteCheckbox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		modInviteCheckbox.setForeground(Color.WHITE);
		modInviteCheckbox.setSelected(false); // Mods cannot invite by default
		modInviteCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
		modInviteCheckbox.addActionListener(e -> updateInvitePermission("mod", modInviteCheckbox.isSelected()));
		permContent.add(modInviteCheckbox);

		permissionsPanel.add(permContent, BorderLayout.CENTER);

		return permissionsPanel;
	}

	/**
	 * Update kick permission for a role
	 */
	private void updateKickPermission(String role, boolean canKick)
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null) return;

		String currentUser = getCurrentUsername();
		boolean success = groupConfigStore.setKickPermission(activeGroup.getId(), currentUser, role, canKick);
		if (success)
		{
			// FIXED: Refresh panel to show updated permissions to all viewers
			refresh();
		}
		if (!success)
		{
			// Revert checkbox if permission change failed
			switch (role.toLowerCase())
			{
				case "co-owner":
					coOwnerKickCheckbox.setSelected(!canKick);
					break;
				case "admin":
					adminKickCheckbox.setSelected(!canKick);
					break;
				case "mod":
					modKickCheckbox.setSelected(!canKick);
					break;
			}
			JOptionPane.showMessageDialog(this,
				"You don't have permission to change this setting.",
				"Permission Denied",
				JOptionPane.WARNING_MESSAGE);
		}
	}

	/**
	 * ADDED: Update invite code generation permission for a role
	 */
	private void updateInvitePermission(String role, boolean canInvite)
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null) return;

		String currentUser = getCurrentUsername();
		boolean success = groupConfigStore.setInvitePermission(activeGroup.getId(), currentUser, role, canInvite);
		if (success)
		{
			// FIXED: Refresh panel to show updated permissions to all viewers
			refresh();
		}
		if (!success)
		{
			// Revert checkbox if permission change failed
			switch (role.toLowerCase())
			{
				case "co-owner":
					coOwnerInviteCheckbox.setSelected(!canInvite);
					break;
				case "admin":
					adminInviteCheckbox.setSelected(!canInvite);
					break;
				case "mod":
					modInviteCheckbox.setSelected(!canInvite);
					break;
			}
			JOptionPane.showMessageDialog(this,
				"You don't have permission to change this setting.",
				"Permission Denied",
				JOptionPane.WARNING_MESSAGE);
		}
	}

	/**
	 * Create the members section
	 * CHANGED: Now shows only manageable members (lower-ranked) with a note to use Roster tab
	 */
	private JPanel createMembersSection()
	{
		JPanel section = createSectionPanel("Manage Members");

		// ADDED: Note pointing users to Roster tab for full list
		JLabel rosterNote = new JLabel("<html><i>Full member list on Roster tab. Only lower-ranked members shown here.</i></html>");
		rosterNote.setFont(FontManager.getRunescapeSmallFont());
		rosterNote.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		rosterNote.setBorder(new EmptyBorder(0, 5, 5, 5));
		section.add(rosterNote, BorderLayout.NORTH);

		membersListPanel = new JPanel();
		membersListPanel.setLayout(new BoxLayout(membersListPanel, BoxLayout.Y_AXIS));
		membersListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel placeholder = new JLabel("No members");
		placeholder.setFont(FontManager.getRunescapeSmallFont());
		placeholder.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		membersListPanel.add(placeholder);

		section.add(membersListPanel, BorderLayout.CENTER);

		return section;
	}

	/**
	 * ADDED: Create the danger zone section with delete/leave group buttons
	 * Only owners can delete groups; all members can leave
	 * FIXED: Compact layout to prevent cutoff
	 * ADDED: Transfer ownership feature for owners
	 */
	private JPanel createDangerZoneSection()
	{
		dangerZonePanel = new JPanel(new BorderLayout(3, 3))
		{
			@Override
			public Dimension getMaximumSize()
			{
				// Allow full width but let height be determined by content
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}
		};
		dangerZonePanel.setBackground(new Color(60, 30, 30)); // Dark red background
		dangerZonePanel.setBorder(new CompoundBorder(
			new LineBorder(new Color(180, 50, 50), 1),
			new EmptyBorder(6, 6, 6, 6)
		));

		JLabel titleLabel = new JLabel("Danger Zone");
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(new Color(255, 100, 100));
		dangerZonePanel.add(titleLabel, BorderLayout.NORTH);

		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
		buttonPanel.setBackground(new Color(60, 30, 30));

		// Leave Group button (available to all members except owner)
		leaveGroupButton = new JButton("Leave Group");
		leaveGroupButton.setFont(FontManager.getRunescapeSmallFont());
		leaveGroupButton.setBackground(new Color(150, 80, 30));
		leaveGroupButton.setForeground(Color.WHITE);
		leaveGroupButton.setFocusPainted(false);
		leaveGroupButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		leaveGroupButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		leaveGroupButton.setMargin(new Insets(2, 4, 2, 4));
		leaveGroupButton.setToolTipText("Leave group (rejoin with invite)");
		leaveGroupButton.addActionListener(e -> leaveGroup());
		buttonPanel.add(leaveGroupButton);
		buttonPanel.add(Box.createVerticalStrut(8));

		// Separator line for owner-only section
		JSeparator separator = new JSeparator();
		separator.setForeground(new Color(180, 50, 50));
		separator.setBackground(new Color(60, 30, 30));
		separator.setAlignmentX(Component.LEFT_ALIGNMENT);
		separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
		buttonPanel.add(separator);
		buttonPanel.add(Box.createVerticalStrut(5));

		// Owner-only label
		JLabel ownerOnlyLabel = new JLabel("Owner Only:");
		ownerOnlyLabel.setFont(FontManager.getRunescapeSmallFont());
		ownerOnlyLabel.setForeground(new Color(255, 150, 150));
		ownerOnlyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttonPanel.add(ownerOnlyLabel);
		buttonPanel.add(Box.createVerticalStrut(5));

		// Transfer Ownership section (owner only)
		JLabel transferLabel = new JLabel("Transfer Ownership:");
		transferLabel.setFont(FontManager.getRunescapeSmallFont());
		transferLabel.setForeground(new Color(255, 200, 150));
		transferLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttonPanel.add(transferLabel);
		buttonPanel.add(Box.createVerticalStrut(3));

		// Dropdown to select new owner
		transferOwnerDropdown = new JComboBox<>();
		transferOwnerDropdown.setFont(FontManager.getRunescapeSmallFont());
		transferOwnerDropdown.setAlignmentX(Component.LEFT_ALIGNMENT);
		transferOwnerDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		buttonPanel.add(transferOwnerDropdown);
		buttonPanel.add(Box.createVerticalStrut(3));

		// Transfer button
		transferOwnershipButton = new JButton("Transfer Ownership");
		transferOwnershipButton.setFont(FontManager.getRunescapeSmallFont());
		transferOwnershipButton.setBackground(new Color(180, 100, 50)); // Orange-red
		transferOwnershipButton.setForeground(Color.WHITE);
		transferOwnershipButton.setFocusPainted(false);
		transferOwnershipButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		transferOwnershipButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		transferOwnershipButton.setMargin(new Insets(2, 4, 2, 4));
		transferOwnershipButton.setToolTipText("Transfer ownership to selected member (you become Co-Owner)");
		transferOwnershipButton.addActionListener(e -> transferOwnership());
		buttonPanel.add(transferOwnershipButton);
		buttonPanel.add(Box.createVerticalStrut(5));

		// Delete Group button (owner only) - compact text
		deleteGroupButton = new JButton("Delete Group");
		deleteGroupButton.setFont(FontManager.getRunescapeSmallFont());
		deleteGroupButton.setBackground(new Color(180, 50, 50));
		deleteGroupButton.setForeground(Color.WHITE);
		deleteGroupButton.setFocusPainted(false);
		deleteGroupButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		deleteGroupButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		deleteGroupButton.setMargin(new Insets(2, 4, 2, 4));
		deleteGroupButton.setToolTipText("Owner only - permanently delete group");
		deleteGroupButton.addActionListener(e -> deleteGroup());
		buttonPanel.add(deleteGroupButton);
		buttonPanel.add(Box.createVerticalStrut(3));

		// Warning label - compact
		JLabel warningLabel = new JLabel("<html><i>Deletes all data!</i></html>");
		warningLabel.setFont(FontManager.getRunescapeSmallFont());
		warningLabel.setForeground(new Color(255, 150, 150));
		warningLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttonPanel.add(warningLabel);

		dangerZonePanel.add(buttonPanel, BorderLayout.CENTER);

		return dangerZonePanel;
	}

	/**
	 * Transfer ownership to another group member
	 * Current owner becomes co-owner
	 */
	private void transferOwnership()
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(this,
				"No active group selected!",
				"Error",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		String currentUser = getCurrentUsername();

		// Only owner can transfer
		if (!groupConfigStore.isOwner(activeGroup.getId(), currentUser))
		{
			JOptionPane.showMessageDialog(this,
				"Only the group owner can transfer ownership!",
				"Permission Denied",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		// Get selected member
		String selectedMember = (String) transferOwnerDropdown.getSelectedItem();
		if (selectedMember == null || selectedMember.isEmpty() || selectedMember.equals("Select member..."))
		{
			JOptionPane.showMessageDialog(this,
				"Please select a member to transfer ownership to.",
				"No Selection",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		// First confirmation
		int confirm = JOptionPane.showConfirmDialog(this,
			String.format("<html>Transfer ownership of <b>'%s'</b> to <b>%s</b>?<br><br>" +
				"<b>What will happen:</b><br>" +
				"• %s will become the new <b>Owner</b><br>" +
				"• You will become a <b>Co-Owner</b><br><br>" +
				"<font color='#ff6666'>This action cannot be easily undone!</font></html>",
				activeGroup.getName(), selectedMember, selectedMember),
			"Confirm Transfer Ownership",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}

		// Second confirmation - type member name
		String typedName = JOptionPane.showInputDialog(this,
			String.format("To confirm transfer, type the new owner's name:\n'%s'", selectedMember),
			"Confirm Transfer",
			JOptionPane.WARNING_MESSAGE);

		if (typedName == null || !typedName.equalsIgnoreCase(selectedMember))
		{
			JOptionPane.showMessageDialog(this,
				"Name did not match. Transfer cancelled.",
				"Cancelled",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		// Perform the transfer using dedicated method
		try
		{
			boolean success = groupConfigStore.transferOwnership(activeGroup.getId(), currentUser, selectedMember);
			if (success)
			{
				log.info("Transferred ownership of group '{}' from {} to {}",
					activeGroup.getName(), currentUser, selectedMember);

				JOptionPane.showMessageDialog(this,
					String.format("Ownership transferred to %s!\nYou are now a Co-Owner.", selectedMember),
					"Transfer Complete",
					JOptionPane.INFORMATION_MESSAGE);

				// Refresh UI to reflect changes
				plugin.refreshPanel();
			}
			else
			{
				JOptionPane.showMessageDialog(this,
					"Failed to transfer ownership. Check logs for details.",
					"Error",
					JOptionPane.ERROR_MESSAGE);
			}
		}
		catch (Exception e)
		{
			log.error("Failed to transfer ownership", e);
			JOptionPane.showMessageDialog(this,
				"Failed to transfer ownership: " + e.getMessage(),
				"Error",
				JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Leave the current group
	 */
	private void leaveGroup()
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(this,
				"No active group to leave!",
				"Error",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		String currentUser = getCurrentUsername();

		// Check if user is the owner - owners cannot leave, they must delete or transfer
		if (groupConfigStore.isOwner(activeGroup.getId(), currentUser))
		{
			JOptionPane.showMessageDialog(this,
				"As the owner, you cannot leave the group.\n\n" +
				"You must either:\n" +
				"- Transfer ownership to another member first\n" +
				"- Delete the group entirely",
				"Cannot Leave",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		int confirm = JOptionPane.showConfirmDialog(this,
			String.format("Are you sure you want to leave '%s'?\n\n" +
				"You will need a new invite code to rejoin.", activeGroup.getName()),
			"Confirm Leave Group",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (confirm == JOptionPane.YES_OPTION)
		{
			// Remove self from group
			groupConfigStore.removeMember(activeGroup.getId(), currentUser);
			log.info("{} left group {}", currentUser, activeGroup.getName());

			JOptionPane.showMessageDialog(this,
				String.format("You have left '%s'.", activeGroup.getName()),
				"Left Group",
				JOptionPane.INFORMATION_MESSAGE);

			// Trigger UI refresh via event bus
			plugin.refreshPanel();
		}
	}

	/**
	 * Delete the current group and all its data (owner only)
	 */
	private void deleteGroup()
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(this,
				"No active group to delete!",
				"Error",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		String currentUser = getCurrentUsername();

		// Only owner can delete
		if (!groupConfigStore.isOwner(activeGroup.getId(), currentUser))
		{
			JOptionPane.showMessageDialog(this,
				"Only the group owner can delete the group!",
				"Permission Denied",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		// First confirmation
		int confirm = JOptionPane.showConfirmDialog(this,
			String.format("Are you sure you want to DELETE '%s'?\n\n" +
				"This will permanently remove:\n" +
				"- All group members\n" +
				"- All lending records\n" +
				"- All item sets\n" +
				"- All history\n\n" +
				"This action CANNOT be undone!", activeGroup.getName()),
			"Confirm Delete Group",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (confirm != JOptionPane.YES_OPTION)
		{
			return;
		}

		// Second confirmation - type group name
		String typedName = JOptionPane.showInputDialog(this,
			String.format("To confirm deletion, type the group name:\n'%s'", activeGroup.getName()),
			"Confirm Deletion",
			JOptionPane.WARNING_MESSAGE);

		if (typedName == null || !typedName.equals(activeGroup.getName()))
		{
			JOptionPane.showMessageDialog(this,
				"Group name did not match. Deletion cancelled.",
				"Cancelled",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		// Delete all group data
		String groupId = activeGroup.getId();
		String groupName = activeGroup.getName();

		try
		{
			// Clear lending data from Recorder
			plugin.getRecorder().clearGroupData(groupId);

			// Clear item sets from ItemSetManager
			plugin.getItemSetManager().clearGroupData(groupId);

			// Clear marketplace data
			plugin.getMarketplaceManager().clearGroupOfferings(groupId);

			// Delete the group itself from GroupConfigStore
			groupConfigStore.deleteGroup(groupId);

			log.info("Deleted group '{}' ({}) and all associated data", groupName, groupId);

			JOptionPane.showMessageDialog(this,
				String.format("Group '%s' has been permanently deleted.", groupName),
				"Group Deleted",
				JOptionPane.INFORMATION_MESSAGE);

			// Trigger UI refresh
			plugin.refreshPanel();
		}
		catch (Exception e)
		{
			log.error("Failed to delete group", e);
			JOptionPane.showMessageDialog(this,
				"Failed to delete group: " + e.getMessage(),
				"Error",
				JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Create a styled section panel
	 * FIXED: Uses minimal padding to prevent cutoff in narrow sidebar
	 */
	private JPanel createSectionPanel(String title)
	{
		JPanel panel = new JPanel(new BorderLayout(3, 3))
		{
			@Override
			public Dimension getMaximumSize()
			{
				// Allow full width but let height be determined by content
				return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
			}

			@Override
			public Dimension getPreferredSize()
			{
				Dimension pref = super.getPreferredSize();
				// FIXED: Ensure preferred width doesn't exceed parent width
				Container parent = getParent();
				if (parent != null)
				{
					int maxWidth = parent.getWidth() - 10;
					if (maxWidth > 0 && pref.width > maxWidth)
					{
						pref.width = maxWidth;
					}
				}
				return pref;
			}
		};
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// FIXED: Reduced border padding to prevent horizontal overflow
		panel.setBorder(new CompoundBorder(
			new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1),
			new EmptyBorder(6, 6, 6, 6)
		));

		JLabel titleLabel = new JLabel(title);
		titleLabel.setFont(FontManager.getRunescapeBoldFont());
		titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
		panel.add(titleLabel, BorderLayout.NORTH);

		return panel;
	}

	/**
	 * Create a styled label
	 */
	private JLabel createLabel(String text, boolean isValue)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(isValue ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	/**
	 * Generate a new single-use invite code
	 */
	private void generateInviteCode()
	{
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			JOptionPane.showMessageDialog(this,
				"No active group selected!",
				"Error",
				JOptionPane.ERROR_MESSAGE);
			return;
		}

		// CHANGED: Use configurable invite permission instead of hardcoded role check
		String currentUser = getCurrentUsername();
		if (!groupConfigStore.canGenerateInviteCode(activeGroup.getId(), currentUser))
		{
			JOptionPane.showMessageDialog(this,
				"You don't have permission to generate invite codes for this group.",
				"Permission Denied",
				JOptionPane.WARNING_MESSAGE);
			return;
		}

		// Generate new code
		String code = groupConfigStore.generateSingleUseInviteCode(activeGroup.getId());
		if (code != null)
		{
			inviteCodeLabel.setText(code);
			inviteCodeLabel.setForeground(ColorScheme.BRAND_ORANGE);
			copyCodeButton.setEnabled(true);
			log.info("Generated new invite code: {}", code);
		}
	}

	/**
	 * Copy invite code to clipboard
	 */
	private void copyInviteCode()
	{
		String code = inviteCodeLabel.getText();
		if (code != null && !code.equals("No active code"))
		{
			StringSelection selection = new StringSelection(code);
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
			JOptionPane.showMessageDialog(this,
				"Invite code copied to clipboard!",
				"Copied",
				JOptionPane.INFORMATION_MESSAGE);
		}
	}

	/**
	 * Get current username - the player's DISPLAY NAME (not login email)
	 * FIXED: Use getLocalPlayer().getName() for display name matching
	 */
	private String getCurrentUsername()
	{
		try
		{
			// FIXED: Use player display name (not login username/email)
			// The group member names are stored using display names
			if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
			{
				String playerName = plugin.getClient().getLocalPlayer().getName();
				if (playerName != null && !playerName.isEmpty())
				{
					return playerName;
				}
			}

			// Fallback to username if display name not available
			String username = plugin.getClient().getUsername();
			if (username != null && !username.isEmpty())
			{
				return username;
			}
		}
		catch (Exception e)
		{
			log.debug("Could not get current username", e);
		}
		return "Unknown";
	}

	/**
	 * Create a member row with role management based on permissions
	 * Staff hierarchy: owner > co-owner > admin > mod > member
	 * @param member The group member
	 * @param groupId The group ID
	 * @param currentUser The current user's name
	 * @param isCurrentUserOwner Whether the current user is the owner (unused, kept for API compatibility)
	 * @return A panel representing the member row
	 */
	private JPanel createMemberRow(GroupMember member, String groupId, String currentUser, boolean isCurrentUserOwner)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(5, 5, 5, 5));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 35));

		// Left side: Name and role badge
		JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		namePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel nameLabel = new JLabel(member.getName());
		nameLabel.setFont(FontManager.getRunescapeSmallFont());
		nameLabel.setForeground(Color.WHITE);
		namePanel.add(nameLabel);

		// Role badge
		String role = member.getRole() != null ? member.getRole() : "member";
		JLabel roleTag = new JLabel(formatRoleName(role));
		roleTag.setFont(FontManager.getRunescapeSmallFont());
		roleTag.setOpaque(true);
		roleTag.setBorder(new EmptyBorder(2, 6, 2, 6));

		// Color-code by role (hierarchy: owner > co-owner > admin > mod > member)
		switch (role.toLowerCase())
		{
			case "owner":
				roleTag.setBackground(new Color(255, 215, 0)); // Gold
				roleTag.setForeground(Color.BLACK);
				break;
			case "co-owner":
				roleTag.setBackground(new Color(192, 192, 192)); // Silver
				roleTag.setForeground(Color.BLACK);
				break;
			case "admin":
				roleTag.setBackground(ColorScheme.BRAND_ORANGE);
				roleTag.setForeground(Color.WHITE);
				break;
			case "mod":
				roleTag.setBackground(new Color(100, 149, 237)); // Cornflower blue
				roleTag.setForeground(Color.WHITE);
				break;
			default:
				roleTag.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
				roleTag.setForeground(Color.WHITE);
				break;
		}
		namePanel.add(roleTag);

		row.add(namePanel, BorderLayout.WEST);

		// Right side: Controls based on permissions
		boolean canChangeRole = groupConfigStore.canChangeRole(groupId, currentUser, member.getName());
		boolean canKick = groupConfigStore.canKick(groupId, currentUser, member.getName());

		if (canChangeRole || canKick)
		{
			JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
			controlsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			// Role dropdown (only if user can change roles)
			if (canChangeRole)
			{
				String[] allRoles = GroupConfigStore.getAvailableRoles();
				// Filter roles based on who is changing
				// Co-owners can only assign admin, mod, member (not co-owner)
				boolean isOwner = groupConfigStore.isOwner(groupId, currentUser);
				String[] availableRoles;
				if (isOwner)
				{
					availableRoles = allRoles; // Owner can assign all roles
				}
				else
				{
					// Co-owner cannot promote to co-owner
					availableRoles = new String[] {"admin", "mod", "member"};
				}

				JComboBox<String> roleDropdown = new JComboBox<>(availableRoles);
				roleDropdown.setFont(FontManager.getRunescapeSmallFont());
				roleDropdown.setSelectedItem(role.toLowerCase());
				roleDropdown.setPreferredSize(new Dimension(85, 22));
				roleDropdown.addActionListener(e ->
				{
					String newRole = (String) roleDropdown.getSelectedItem();
					if (newRole != null && !newRole.equalsIgnoreCase(role))
					{
						boolean success = groupConfigStore.setMemberRole(groupId, currentUser, member.getName(), newRole);
						if (success)
						{
							log.info("Changed {}'s role to {}", member.getName(), newRole);
							// FIXED: Refresh ALL panels, not just settings - this updates Team tab too
							plugin.refreshPanel();
						}
						else
						{
							JOptionPane.showMessageDialog(this,
								"Failed to change role. You may not have permission.",
								"Error",
								JOptionPane.ERROR_MESSAGE);
							roleDropdown.setSelectedItem(role); // Revert
						}
					}
				});
				controlsPanel.add(roleDropdown);
			}

			// Kick button (only if user can kick this member)
			if (canKick)
			{
				JButton kickButton = new JButton("Kick");
				kickButton.setFont(FontManager.getRunescapeSmallFont());
				kickButton.setBackground(new Color(180, 50, 50));
				kickButton.setForeground(Color.WHITE);
				kickButton.setFocusPainted(false);
				kickButton.setBorderPainted(false);
				kickButton.setPreferredSize(new Dimension(45, 22));
				kickButton.setToolTipText("Kick from group");
				kickButton.addActionListener(e ->
				{
					int confirm = JOptionPane.showConfirmDialog(this,
						String.format("Kick %s from the group?\n\nThis will also remove their marketplace items.", member.getName()),
						"Confirm Kick",
						JOptionPane.YES_NO_OPTION,
						JOptionPane.WARNING_MESSAGE);

					if (confirm == JOptionPane.YES_OPTION)
					{
						boolean removed = groupConfigStore.removeMemberFromGroup(groupId, currentUser, member.getName());
						if (removed)
						{
							log.info("Kicked {} from group", member.getName());

							// FIXED: Also remove the kicked member's items from marketplace
							plugin.getMarketplaceManager().removeItemsForLender(groupId, member.getName());
							plugin.getRecorder().removeItemsForLender(groupId, member.getName());
							log.info("Removed marketplace items for kicked member: {}", member.getName());

							// FIXED: Refresh ALL panels, not just settings
							plugin.refreshPanel();
						}
						else
						{
							JOptionPane.showMessageDialog(this,
								"Failed to kick member. You may not have permission.",
								"Error",
								JOptionPane.ERROR_MESSAGE);
						}
					}
				});
				controlsPanel.add(kickButton);
			}

			row.add(controlsPanel, BorderLayout.EAST);
		}

		return row;
	}

	/**
	 * CHANGED: Delegates to shared utility in GroupConfigStore
	 */
	private String formatRoleName(String role)
	{
		return GroupConfigStore.formatRoleName(role);
	}

	/**
	 * Refresh the settings panel with current data
	 * FIXED: Now checks login status and staff permissions before showing content
	 */
	public void refresh()
	{
		SwingUtilities.invokeLater(() ->
		{
			// ADDED: Check login status first - use multiple methods
			String currentUser = null;
			boolean isLoggedIn = false;

			// Try to get player name from local player first
			try
			{
				if (plugin.getClient() != null && plugin.getClient().getLocalPlayer() != null)
				{
					String playerName = plugin.getClient().getLocalPlayer().getName();
					if (playerName != null && !playerName.isEmpty())
					{
						currentUser = playerName;
						isLoggedIn = true;
					}
				}
			}
			catch (Exception e)
			{
				log.debug("Could not get player name from local player", e);
			}

			// Fallback to username if player name not available
			if (!isLoggedIn)
			{
				String username = getCurrentUsername();
				if (username != null && !username.equals("Unknown") && !username.isEmpty())
				{
					currentUser = username;
					isLoggedIn = true;
				}
			}

			log.debug("Settings panel refresh - currentUser: {}, isLoggedIn: {}", currentUser, isLoggedIn);

			// FIXED: Use unchecked version since we've already determined login status
			// This avoids race conditions where isLoggedIn() returns false during transitions
			LendingGroup activeGroup = isLoggedIn ? groupConfigStore.getActiveGroupUnchecked() : null;

			// ADDED: Determine which panel to show
			contentPanel.removeAll();

			if (!isLoggedIn)
			{
				// Not logged in - show login message
				contentPanel.add(notLoggedInPanel, BorderLayout.CENTER);
				log.debug("Settings panel: showing not logged in panel");
			}
			else if (activeGroup == null)
			{
				// Logged in but no group selected - show staff content with "no group" state
				contentPanel.add(scrollPane, BorderLayout.CENTER);
				showNoGroupState();
			}
			else
			{
				// Check if user is staff (owner, co-owner, or admin)
				boolean isOwner = groupConfigStore.isOwner(activeGroup.getId(), currentUser);
				boolean isCoOwner = groupConfigStore.isCoOwner(activeGroup.getId(), currentUser);
				boolean isAdmin = groupConfigStore.isAdmin(activeGroup.getId(), currentUser);
				boolean isStaff = isOwner || isCoOwner || isAdmin;
				boolean isMod = groupConfigStore.isMod(activeGroup.getId(), currentUser);

				// CHANGED: Show settings to ALL members, but with role-based visibility
				// All members can see: Group Info, Discord Webhook (save only), Screenshots, Leave Group
				// Staff can additionally see: Invite codes, Test webhook, Members list with controls, Permissions
				// Owner can additionally see: Transfer ownership, Delete group
				contentPanel.add(scrollPane, BorderLayout.CENTER);
				showMemberContent(activeGroup, currentUser, isOwner, isCoOwner, isAdmin, isMod, isStaff);
			}

			contentPanel.revalidate();
			contentPanel.repaint();
			revalidate();
			repaint();
		});
	}

	/**
	 * ADDED: Show the "no group selected" state
	 */
	private void showNoGroupState()
	{
		groupNameLabel.setText("No active group");
		memberCountLabel.setText("0");
		roleLabel.setText("-");
		inviteCodeLabel.setText("No active code");
		inviteCodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		copyCodeButton.setEnabled(false);
		generateCodeButton.setEnabled(false);

		// Clear webhook URL field when no group
		if (webhookUrlField != null)
		{
			webhookUrlField.setText("");
			webhookUrlField.setEnabled(false);
		}
		if (saveWebhookButton != null) saveWebhookButton.setEnabled(false);
		if (testWebhookButton != null) testWebhookButton.setEnabled(false);

		// Hide permissions section when no group
		permissionsPanel.setVisible(false);

		// Hide danger zone when no group
		if (dangerZonePanel != null)
		{
			dangerZonePanel.setVisible(false);
		}

		// Clear transfer ownership dropdown
		if (transferOwnerDropdown != null)
		{
			transferOwnerDropdown.removeAllItems();
			transferOwnerDropdown.setEnabled(false);
		}
		if (transferOwnershipButton != null)
		{
			transferOwnershipButton.setEnabled(false);
		}

		membersListPanel.removeAll();
		JLabel placeholder = new JLabel("Select or create a group");
		placeholder.setFont(FontManager.getRunescapeSmallFont());
		placeholder.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		membersListPanel.add(placeholder);

		membersListPanel.revalidate();
		membersListPanel.repaint();
	}

	/**
	 * CHANGED: Show settings content with role-based visibility for ALL members
	 * All members can see: Group Info, Discord Webhook (save), Screenshots, Danger Zone (Leave)
	 * Staff (admin+) can see: Invite codes, Test webhook, Members list with controls
	 * Owner/Co-Owner can see: Permissions section
	 * Owner only can see: Transfer ownership, Delete group
	 */
	private void showMemberContent(LendingGroup activeGroup, String currentUser,
								   boolean isOwner, boolean isCoOwner, boolean isAdmin, boolean isMod, boolean isStaff)
	{
		groupNameLabel.setText(activeGroup.getName());
		memberCountLabel.setText(String.valueOf(activeGroup.getMembers().size()));

		// Get user's role
		String rawRole = groupConfigStore.getMemberRole(activeGroup.getId(), currentUser);
		String role = formatRoleName(rawRole != null ? rawRole : "member");
		roleLabel.setText(role);

		// CHANGED: Update invite code section - visible to anyone who can generate invites
		boolean canGenerateInvite = groupConfigStore.canGenerateInviteCode(activeGroup.getId(), currentUser);
		if (inviteCodePanel != null)
		{
			inviteCodePanel.setVisible(canGenerateInvite || isStaff);
		}
		if (canGenerateInvite || isStaff)
		{
			if (activeGroup.hasActiveInviteCode())
			{
				inviteCodeLabel.setText(activeGroup.getInviteCode());
				inviteCodeLabel.setForeground(ColorScheme.BRAND_ORANGE);
				copyCodeButton.setEnabled(true);
			}
			else
			{
				inviteCodeLabel.setText("No active code");
				inviteCodeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				copyCodeButton.setEnabled(false);
			}
			// CHANGED: Use configurable permission for generate button
			generateCodeButton.setEnabled(canGenerateInvite);
		}

		// Load and display webhook URL - ALL members can see and save
		if (webhookUrlField != null)
		{
			String existingUrl = groupConfigStore.getGroupWebhookUrl(activeGroup.getId());
			webhookUrlField.setText(existingUrl != null ? existingUrl : "");
			webhookUrlField.setEnabled(true); // All members can edit
		}
		if (saveWebhookButton != null) saveWebhookButton.setEnabled(true); // All members can save
		// Test button only for staff
		if (testWebhookButton != null) testWebhookButton.setEnabled(isStaff);

		// Update permissions section - only visible to owner/co-owner
		boolean canEditPerms = isOwner || isCoOwner;
		permissionsPanel.setVisible(canEditPerms);
		if (canEditPerms)
		{
			// Update kick checkbox states from group settings
			coOwnerKickCheckbox.setSelected(activeGroup.isCoOwnerCanKick());
			adminKickCheckbox.setSelected(activeGroup.isAdminCanKick());
			modKickCheckbox.setSelected(activeGroup.isModCanKick());

			// Co-owners can only change admin/mod permissions, not co-owner
			coOwnerKickCheckbox.setEnabled(isOwner);
			adminKickCheckbox.setEnabled(true);
			modKickCheckbox.setEnabled(true);

			// ADDED: Update invite checkbox states from group settings
			coOwnerInviteCheckbox.setSelected(activeGroup.isCoOwnerCanInvite());
			adminInviteCheckbox.setSelected(activeGroup.isAdminCanInvite());
			modInviteCheckbox.setSelected(activeGroup.isModCanInvite());

			// Co-owners can only change admin/mod invite permissions, not co-owner
			coOwnerInviteCheckbox.setEnabled(isOwner);
			adminInviteCheckbox.setEnabled(true);
			modInviteCheckbox.setEnabled(true);
		}

		// Members section - visible to staff OR mods with kick permission
		// FIXED: Mods with kick permission should also see members list
		if (membersSection != null)
		{
			boolean modCanSeeMembers = isMod && activeGroup.isModCanKick();
			membersSection.setVisible(isStaff || modCanSeeMembers);
		}

		// CHANGED: Only show lower-ranked members (manageable by current user)
		membersListPanel.removeAll();
		List<GroupMember> members = activeGroup.getMembers();
		int currentUserRank = GroupConfigStore.getRoleRank(rawRole);
		int manageable = 0;

		if (members.isEmpty())
		{
			JLabel placeholder = new JLabel("No members");
			placeholder.setFont(FontManager.getRunescapeSmallFont());
			placeholder.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			membersListPanel.add(placeholder);
		}
		else
		{
			for (GroupMember member : members)
			{
				// Only show members that the current user can manage (lower rank)
				int memberRank = GroupConfigStore.getRoleRank(member.getRole());
				if (memberRank < currentUserRank || isOwner)
				{
					JPanel memberRow = createMemberRow(member, activeGroup.getId(), currentUser, isOwner);
					membersListPanel.add(memberRow);
					membersListPanel.add(Box.createVerticalStrut(3));
					manageable++;
				}
			}

			if (manageable == 0)
			{
				JLabel noManageable = new JLabel("No members you can manage");
				noManageable.setFont(FontManager.getRunescapeSmallFont());
				noManageable.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				noManageable.setBorder(new EmptyBorder(5, 5, 5, 5));
				membersListPanel.add(noManageable);
			}
		}

		membersListPanel.revalidate();
		membersListPanel.repaint();

		// Update danger zone - visible to all, but different options based on role
		if (dangerZonePanel != null)
		{
			dangerZonePanel.setVisible(true);

			// Delete button - owner only
			deleteGroupButton.setEnabled(isOwner);
			deleteGroupButton.setVisible(isOwner);

			// Leave button - all except owner
			leaveGroupButton.setEnabled(!isOwner);
			leaveGroupButton.setVisible(!isOwner);

			// Transfer ownership controls (owner only)
			transferOwnershipButton.setEnabled(isOwner);
			transferOwnershipButton.setVisible(isOwner);
			transferOwnerDropdown.setEnabled(isOwner);
			transferOwnerDropdown.setVisible(isOwner);

			// Populate transfer dropdown with non-owner members
			if (isOwner && transferOwnerDropdown != null)
			{
				transferOwnerDropdown.removeAllItems();
				transferOwnerDropdown.addItem("Select member...");

				for (GroupMember member : members)
				{
					// Don't include self (current owner)
					if (!member.getName().equalsIgnoreCase(currentUser))
					{
						transferOwnerDropdown.addItem(member.getName());
					}
				}

				// Disable if no other members
				if (transferOwnerDropdown.getItemCount() <= 1)
				{
					transferOwnershipButton.setEnabled(false);
					transferOwnerDropdown.setEnabled(false);
				}
			}

		}
	}
}
