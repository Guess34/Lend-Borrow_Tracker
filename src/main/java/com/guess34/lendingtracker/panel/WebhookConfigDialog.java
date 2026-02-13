package com.guess34.lendingtracker.panel;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.DiscordWebhookService;
import com.guess34.lendingtracker.services.WebhookAuditLogger;
import com.guess34.lendingtracker.services.WebhookRateLimiter;
import com.guess34.lendingtracker.services.WebhookTokenService;
import net.runelite.client.ui.ColorScheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Dialog for configuring Discord webhooks with security features.
 * Allows users to view/copy tokens, configure webhook settings, and view audit logs.
 */
@Slf4j
public class WebhookConfigDialog extends JDialog {

	private final LendingGroup group;
	private final Client client;
	private final DiscordWebhookService webhookService;
	private final WebhookTokenService tokenService;
	private final WebhookRateLimiter rateLimiter;
	private final WebhookAuditLogger auditLogger;

	private JTextField webhookUrlField;
	private JCheckBox enabledCheckBox;
	private JCheckBox securityEnabledCheckBox;
	private JLabel tokenLabel;
	private JButton copyTokenButton;
	private JButton regenerateTokenButton;
	private JButton testWebhookButton;
	private JLabel usageStatsLabel;
	private JTable auditTable;

	private String currentToken;
	private boolean tokenAlreadyShown;

	public WebhookConfigDialog(JFrame parent, LendingGroup group, Client client,
	                            DiscordWebhookService webhookService,
	                            WebhookTokenService tokenService,
	                            WebhookRateLimiter rateLimiter,
	                            WebhookAuditLogger auditLogger) {
		super(parent, "Discord Webhook Configuration - " + group.getName(), true);
		this.group = group;
		this.client = client;
		this.webhookService = webhookService;
		this.tokenService = tokenService;
		this.rateLimiter = rateLimiter;
		this.auditLogger = auditLogger;

		setSize(700, 600);
		setLocationRelativeTo(parent);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		buildUI();
		loadSettings();
	}

	private void buildUI() {
		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create tabbed pane
		JTabbedPane tabbedPane = new JTabbedPane();
		tabbedPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabbedPane.setForeground(Color.WHITE);

		tabbedPane.addTab("Settings", buildSettingsPanel());
		tabbedPane.addTab("Token & Security", buildTokenPanel());
		tabbedPane.addTab("Statistics", buildStatsPanel());
		tabbedPane.addTab("Audit Log", buildAuditPanel());

		mainPanel.add(tabbedPane, BorderLayout.CENTER);
		mainPanel.add(buildButtonPanel(), BorderLayout.SOUTH);

		add(mainPanel);
	}

	// =============== SETTINGS TAB ===============

	private JPanel buildSettingsPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(5, 5, 5, 5);

		// Webhook URL
		c.gridx = 0; c.gridy = 0; c.weightx = 0;
		JLabel urlLabel = new JLabel("Webhook URL:");
		urlLabel.setForeground(Color.WHITE);
		panel.add(urlLabel, c);

		c.gridx = 1; c.weightx = 1;
		webhookUrlField = new JTextField();
		webhookUrlField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		webhookUrlField.setForeground(Color.WHITE);
		webhookUrlField.setToolTipText("Paste your Discord webhook URL here");
		panel.add(webhookUrlField, c);

		// Enable checkbox
		c.gridx = 0; c.gridy = 1; c.gridwidth = 2;
		enabledCheckBox = new JCheckBox("Enable Webhook Notifications");
		enabledCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		enabledCheckBox.setForeground(Color.WHITE);
		panel.add(enabledCheckBox, c);

		// Security enabled checkbox
		c.gridy = 2;
		securityEnabledCheckBox = new JCheckBox("Enable Security Features (Recommended)");
		securityEnabledCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
		securityEnabledCheckBox.setForeground(Color.GREEN);
		securityEnabledCheckBox.setSelected(true);
		securityEnabledCheckBox.addActionListener(e -> {
			if (!securityEnabledCheckBox.isSelected()) {
				showSecurityWarning();
			}
		});
		panel.add(securityEnabledCheckBox, c);

		// Info panel
		c.gridy = 3;
		JPanel infoPanel = new JPanel(new BorderLayout());
		infoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		infoPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2),
			new EmptyBorder(10, 10, 10, 10)
		));

		JTextArea infoText = new JTextArea();
		infoText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		infoText.setForeground(Color.WHITE);
		infoText.setEditable(false);
		infoText.setLineWrap(true);
		infoText.setWrapStyleWord(true);
		infoText.setText(
			"SECURITY FEATURES:\n\n" +
			"• Token Authentication - Your unique token validates all webhook posts\n" +
			"• Rate Limiting - Prevents spam (10/min, 100/hour, 500/day)\n" +
			"• Audit Logging - All webhook attempts are logged\n" +
			"• Group Isolation - Data only goes to this group's webhook\n\n" +
			"Your Discord bot must validate tokens! See the 'Token & Security' tab for your token."
		);
		infoPanel.add(infoText, BorderLayout.CENTER);
		panel.add(infoPanel, c);

		return panel;
	}

	// =============== TOKEN TAB ===============

	private JPanel buildTokenPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(5, 5, 5, 5);

		// Token display
		c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
		JLabel tokenTitleLabel = new JLabel("Your Authentication Token:");
		tokenTitleLabel.setForeground(Color.WHITE);
		tokenTitleLabel.setFont(tokenTitleLabel.getFont().deriveFont(Font.BOLD, 14f));
		panel.add(tokenTitleLabel, c);

		c.gridy = 1;
		tokenLabel = new JLabel("Loading...");
		tokenLabel.setForeground(Color.YELLOW);
		tokenLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
		tokenLabel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2),
			new EmptyBorder(10, 10, 10, 10)
		));
		tokenLabel.setOpaque(true);
		tokenLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.add(tokenLabel, c);

		// Token buttons
		c.gridy = 2; c.gridwidth = 1; c.weightx = 0.5;
		copyTokenButton = new JButton("Copy Token");
		copyTokenButton.setBackground(ColorScheme.BRAND_ORANGE);
		copyTokenButton.setForeground(Color.WHITE);
		copyTokenButton.addActionListener(e -> copyToken());
		panel.add(copyTokenButton, c);

		c.gridx = 1;
		regenerateTokenButton = new JButton("Regenerate Token");
		regenerateTokenButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		regenerateTokenButton.setForeground(Color.WHITE);
		regenerateTokenButton.addActionListener(e -> regenerateToken());
		panel.add(regenerateTokenButton, c);

		// Warning panel
		c.gridx = 0; c.gridy = 3; c.gridwidth = 2;
		JPanel warningPanel = new JPanel(new BorderLayout());
		warningPanel.setBackground(new Color(139, 0, 0));
		warningPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JTextArea warningText = new JTextArea();
		warningText.setBackground(new Color(139, 0, 0));
		warningText.setForeground(Color.WHITE);
		warningText.setEditable(false);
		warningText.setLineWrap(true);
		warningText.setWrapStyleWord(true);
		warningText.setFont(warningText.getFont().deriveFont(Font.BOLD));
		warningText.setText(
			"⚠️ IMPORTANT: Keep this token SECRET!\n\n" +
			"This token is shown only ONCE. Copy it now and register it with your Discord bot.\n" +
			"Anyone with this token can send webhooks on your behalf!"
		);
		warningPanel.add(warningText, BorderLayout.CENTER);
		panel.add(warningPanel, c);

		// Discord bot setup instructions
		c.gridy = 4;
		JPanel instructionsPanel = new JPanel(new BorderLayout());
		instructionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		instructionsPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		JTextArea instructionsText = new JTextArea();
		instructionsText.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		instructionsText.setForeground(Color.WHITE);
		instructionsText.setEditable(false);
		instructionsText.setLineWrap(true);
		instructionsText.setWrapStyleWord(true);
		instructionsText.setText(
			"DISCORD BOT SETUP:\n\n" +
			"1. Copy the token above\n" +
			"2. In your Discord server, use the bot command:\n" +
			"   /register-token <your-rsn> <token>\n" +
			"3. The bot will link your token to your RSN\n" +
			"4. Test the webhook using the 'Settings' tab\n\n" +
			"Your Discord bot MUST validate tokens before posting to prevent abuse!"
		);
		instructionsPanel.add(instructionsText, BorderLayout.CENTER);
		panel.add(instructionsPanel, c);

		return panel;
	}

	// =============== STATISTICS TAB ===============

	private JPanel buildStatsPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.insets = new Insets(5, 5, 5, 5);

		// Usage statistics
		c.gridx = 0; c.gridy = 0; c.weightx = 1; c.weighty = 0;
		usageStatsLabel = new JLabel("<html>Loading statistics...</html>");
		usageStatsLabel.setForeground(Color.WHITE);
		usageStatsLabel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(10, 10, 10, 10)
		));
		usageStatsLabel.setOpaque(true);
		usageStatsLabel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.add(usageStatsLabel, c);

		// Refresh button
		c.gridy = 1; c.weighty = 0;
		JButton refreshButton = new JButton("Refresh Statistics");
		refreshButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		refreshButton.setForeground(Color.WHITE);
		refreshButton.addActionListener(e -> loadStatistics());
		panel.add(refreshButton, c);

		return panel;
	}

	// =============== AUDIT LOG TAB ===============

	private JPanel buildAuditPanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Table
		String[] columns = {"Time", "Event Type", "Success", "Reason", "Item"};
		DefaultTableModel model = new DefaultTableModel(columns, 0) {
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};

		auditTable = new JTable(model);
		auditTable.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		auditTable.setForeground(Color.WHITE);
		auditTable.setSelectionBackground(ColorScheme.BRAND_ORANGE);
		auditTable.setGridColor(ColorScheme.MEDIUM_GRAY_COLOR);
		auditTable.getTableHeader().setBackground(ColorScheme.DARK_GRAY_COLOR);
		auditTable.getTableHeader().setForeground(Color.WHITE);

		JScrollPane scrollPane = new JScrollPane(auditTable);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.add(scrollPane, BorderLayout.CENTER);

		// Refresh button
		JButton refreshButton = new JButton("Refresh Audit Log");
		refreshButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		refreshButton.setForeground(Color.WHITE);
		refreshButton.addActionListener(e -> loadAuditLog());
		panel.add(refreshButton, BorderLayout.SOUTH);

		return panel;
	}

	// =============== BUTTON PANEL ===============

	private JPanel buildButtonPanel() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		testWebhookButton = new JButton("Test Webhook");
		testWebhookButton.setBackground(ColorScheme.BRAND_ORANGE);
		testWebhookButton.setForeground(Color.WHITE);
		testWebhookButton.addActionListener(e -> testWebhook());
		panel.add(testWebhookButton);

		JButton saveButton = new JButton("Save");
		saveButton.setBackground(ColorScheme.BRAND_ORANGE);
		saveButton.setForeground(Color.WHITE);
		saveButton.addActionListener(e -> saveSettings());
		panel.add(saveButton);

		JButton closeButton = new JButton("Close");
		closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		closeButton.setForeground(Color.WHITE);
		closeButton.addActionListener(e -> dispose());
		panel.add(closeButton);

		return panel;
	}

	// =============== ACTIONS ===============

	private void loadSettings() {
		String webhookUrl = webhookService.getWebhookUrl(group.getId());
		webhookUrlField.setText(webhookUrl != null ? webhookUrl : "");

		boolean enabled = webhookService.isWebhookEnabled(group.getId());
		enabledCheckBox.setSelected(enabled);

		boolean securityEnabled = webhookService.isSecurityEnabled(group.getId());
		securityEnabledCheckBox.setSelected(securityEnabled);

		loadToken();
		loadStatistics();
		loadAuditLog();
	}

	private void loadToken() {
		String rsn = getCurrentPlayerName();
		if (rsn == null) {
			tokenLabel.setText("ERROR: Player name not available");
			tokenLabel.setForeground(Color.RED);
			copyTokenButton.setEnabled(false);
			return;
		}

		// Check if token was already shown
		tokenAlreadyShown = tokenService.hasTokenBeenShown(group.getId(), rsn);

		if (tokenAlreadyShown) {
			// Token already shown, display masked version
			String token = tokenService.getToken(group.getId(), rsn);
			if (token != null) {
				String masked = tokenService.maskToken(token);
				tokenLabel.setText("Token: " + masked + " (hidden for security)");
				tokenLabel.setForeground(Color.GRAY);
				currentToken = token;
				copyTokenButton.setEnabled(true);
			} else {
				tokenLabel.setText("No token generated yet");
				tokenLabel.setForeground(Color.YELLOW);
				copyTokenButton.setEnabled(false);
			}
		} else {
			// First time viewing token, show it fully
			currentToken = tokenService.getOrCreateToken(group.getId(), rsn);
			tokenLabel.setText(currentToken);
			tokenLabel.setForeground(Color.GREEN);
			copyTokenButton.setEnabled(true);

			// Mark as shown
			tokenService.markTokenAsShown(group.getId(), rsn);

			// Show warning
			JOptionPane.showMessageDialog(this,
				"This is your authentication token.\n\n" +
				"Copy it NOW and register it with your Discord bot!\n" +
				"You won't be able to see it again (only masked version).",
				"Token Generated",
				JOptionPane.WARNING_MESSAGE);
		}
	}

	private void copyToken() {
		if (currentToken != null) {
			StringSelection selection = new StringSelection(currentToken);
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);

			JOptionPane.showMessageDialog(this,
				"Token copied to clipboard!\n\n" +
				"Register it with your Discord bot using:\n" +
				"/register-token <your-rsn> <token>",
				"Token Copied",
				JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void regenerateToken() {
		int confirm = JOptionPane.showConfirmDialog(this,
			"Are you sure you want to regenerate your token?\n\n" +
			"This will REVOKE your old token and you'll need to re-register\n" +
			"the new token with your Discord bot!",
			"Regenerate Token",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (confirm == JOptionPane.YES_OPTION) {
			String rsn = getCurrentPlayerName();
			if (rsn == null) {
				JOptionPane.showMessageDialog(this,
					"Cannot regenerate token: Player name not available",
					"Error",
					JOptionPane.ERROR_MESSAGE);
				return;
			}

			currentToken = tokenService.regenerateToken(group.getId(), rsn);
			tokenLabel.setText(currentToken);
			tokenLabel.setForeground(Color.GREEN);

			JOptionPane.showMessageDialog(this,
				"Token regenerated successfully!\n\n" +
				"COPY IT NOW and register it with your Discord bot!",
				"Token Regenerated",
				JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private void loadStatistics() {
		String rsn = getCurrentPlayerName();
		if (rsn == null) {
			usageStatsLabel.setText("<html>ERROR: Player name not available</html>");
			return;
		}

		// Get rate limiter stats
		WebhookRateLimiter.UsageStats usageStats = rateLimiter.getUsageStats(group.getId(), rsn);

		// Get audit logger stats
		WebhookAuditLogger.AuditStats auditStats = auditLogger.getStats(group.getId(), rsn);

		StringBuilder html = new StringBuilder("<html><body style='padding:5px;'>");
		html.append("<h3>Webhook Usage Statistics</h3>");
		html.append("<table cellpadding='5'>");

		html.append("<tr><td><b>Player:</b></td><td>").append(rsn).append("</td></tr>");
		html.append("<tr><td><b>Group:</b></td><td>").append(group.getName()).append("</td></tr>");
		html.append("<tr><td colspan='2'><hr></td></tr>");

		html.append("<tr><td><b>Calls (Last Minute):</b></td><td>")
			.append(usageStats.getCallsLastMinute()).append(" / 10</td></tr>");
		html.append("<tr><td><b>Calls (Last Hour):</b></td><td>")
			.append(usageStats.getCallsLastHour()).append(" / 100</td></tr>");
		html.append("<tr><td><b>Calls (Last Day):</b></td><td>")
			.append(usageStats.getCallsLastDay()).append(" / 500</td></tr>");
		html.append("<tr><td colspan='2'><hr></td></tr>");

		html.append("<tr><td><b>Total Attempts:</b></td><td>").append(auditStats.getTotalAttempts()).append("</td></tr>");
		html.append("<tr><td><b>Successful:</b></td><td style='color:green;'>")
			.append(auditStats.getSuccessfulAttempts()).append("</td></tr>");
		html.append("<tr><td><b>Failed:</b></td><td style='color:red;'>")
			.append(auditStats.getFailedAttempts()).append("</td></tr>");
		html.append("<tr><td><b>Success Rate:</b></td><td>")
			.append(String.format("%.1f%%", auditStats.getSuccessRate())).append("</td></tr>");

		html.append("</table></body></html>");

		usageStatsLabel.setText(html.toString());
	}

	private void loadAuditLog() {
		String rsn = getCurrentPlayerName();
		if (rsn == null) {
			return;
		}

		List<WebhookAuditLogger.AuditEntry> entries = auditLogger.getEntries(group.getId(), rsn, 100);

		DefaultTableModel model = (DefaultTableModel) auditTable.getModel();
		model.setRowCount(0);

		for (WebhookAuditLogger.AuditEntry entry : entries) {
			model.addRow(new Object[]{
				entry.getFormattedTimestamp(),
				entry.getEventType(),
				entry.isSuccess() ? "✓" : "✗",
				entry.getFailureReason() != null ? entry.getFailureReason() : "N/A",
				entry.getItemInfo()
			});
		}
	}

	private void testWebhook() {
		testWebhookButton.setEnabled(false);
		testWebhookButton.setText("Testing...");

		webhookService.testWebhook(group.getId()).thenAccept(success -> {
			SwingUtilities.invokeLater(() -> {
				testWebhookButton.setEnabled(true);
				testWebhookButton.setText("Test Webhook");

				if (success) {
					JOptionPane.showMessageDialog(this,
						"Webhook test successful!\n\nCheck your Discord channel for the test message.",
						"Test Successful",
						JOptionPane.INFORMATION_MESSAGE);
				} else {
					JOptionPane.showMessageDialog(this,
						"Webhook test failed!\n\nCheck:\n" +
						"• Webhook URL is correct\n" +
						"• Token is registered with Discord bot\n" +
						"• Security settings match bot expectations\n" +
						"• Check the Audit Log tab for details",
						"Test Failed",
						JOptionPane.ERROR_MESSAGE);
				}

				loadAuditLog();
				loadStatistics();
			});
		});
	}

	private void saveSettings() {
		String webhookUrl = webhookUrlField.getText().trim();
		webhookService.setWebhookUrl(group.getId(), webhookUrl);

		boolean enabled = enabledCheckBox.isSelected();
		webhookService.setWebhookEnabled(group.getId(), enabled);

		boolean securityEnabled = securityEnabledCheckBox.isSelected();
		webhookService.setSecurityEnabled(group.getId(), securityEnabled);

		JOptionPane.showMessageDialog(this,
			"Settings saved successfully!",
			"Saved",
			JOptionPane.INFORMATION_MESSAGE);
	}

	private void showSecurityWarning() {
		int confirm = JOptionPane.showConfirmDialog(this,
			"⚠️ WARNING: Disabling security is NOT RECOMMENDED!\n\n" +
			"Without security:\n" +
			"• Anyone with your webhook URL can post messages\n" +
			"• No rate limiting (risk of spam)\n" +
			"• No authentication (no audit trail)\n" +
			"• Risk of webhook abuse\n\n" +
			"Only disable for PRIVATE Discord bots you fully control.\n\n" +
			"Keep security disabled?",
			"Security Warning",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE);

		if (confirm != JOptionPane.YES_OPTION) {
			securityEnabledCheckBox.setSelected(true);
		}
	}

	private String getCurrentPlayerName() {
		if (client == null || client.getLocalPlayer() == null) {
			return null;
		}
		return client.getLocalPlayer().getName();
	}
}
