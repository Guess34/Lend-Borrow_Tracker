package net.runelite.client.plugins.lendingtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("lendingtracker")
public interface LendingTrackerConfig extends Config
{
	// ============================================================
	// SECTIONS - Define collapsible groups for config panel
	// ============================================================

	@ConfigSection(
		name = "Notifications",
		description = "Configure how and when you receive notifications",
		position = 10,
		closedByDefault = false
	)
	String notificationSection = "notifications";

	@ConfigSection(
		name = "Overlay Settings",
		description = "Configure in-game overlay display options",
		position = 20,
		closedByDefault = false
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Risk & Safety",
		description = "Configure risk thresholds and safety warnings",
		position = 30,
		closedByDefault = true
	)
	String safetySection = "safety";

	@ConfigSection(
		name = "Loan Defaults",
		description = "Default values for new loans",
		position = 40,
		closedByDefault = true
	)
	String defaultsSection = "defaults";

	@ConfigSection(
		name = "Screenshots & Proof",
		description = "Automatic screenshot capture settings",
		position = 50,
		closedByDefault = true
	)
	String screenshotSection = "screenshots";

	@ConfigSection(
		name = "Marketplace",
		description = "Marketplace display and filtering options",
		position = 60,
		closedByDefault = true
	)
	String marketplaceSection = "marketplace";

	@ConfigSection(
		name = "Data & Storage",
		description = "Data retention and storage settings",
		position = 70,
		closedByDefault = true
	)
	String dataSection = "data";

	@ConfigSection(
		name = "Discord Webhooks",
		description = "Send notifications to Discord when lending events occur",
		position = 75,
		closedByDefault = false
	)
	String discordSection = "discord";

	// ============================================================
	// TOP-LEVEL OPTIONS (Most Important - Not in any section)
	// ============================================================

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Show lending tracker overlay in game",
		position = 1
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableNotifications",
		name = "Enable Notifications",
		description = "Enable desktop notifications for lending events",
		position = 2
	)
	default boolean enableNotifications()
	{
		return true;
	}

	// ============================================================
	// NOTIFICATIONS SECTION
	// ============================================================

	@ConfigItem(
		keyName = "enableChatNotifications",
		name = "Chat Notifications",
		description = "Show lending notifications in game chat",
		position = 0,
		section = notificationSection
	)
	default boolean enableChatNotifications()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableSoundAlerts",
		name = "Sound Alerts",
		description = "Play sound when items become overdue or are returned",
		position = 1,
		section = notificationSection
	)
	default boolean enableSoundAlerts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableGroupAnnouncements",
		name = "Group Announcements",
		description = "Announce borrow requests and lend offers to online group members in game chat",
		position = 2,
		section = notificationSection
	)
	default boolean enableGroupAnnouncements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNotificationOverlay",
		name = "Notification Bubble",
		description = "Show notification count bubble near minimap when you have unread notifications",
		position = 3,
		section = notificationSection
	)
	default boolean showNotificationOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableDailyReports",
		name = "Daily Reports",
		description = "Show daily lending summary reports",
		position = 4,
		section = notificationSection
	)
	default boolean enableDailyReports()
	{
		return true;
	}

	@ConfigItem(
		keyName = "overdueReminderFrequency",
		name = "Overdue Reminder Frequency",
		description = "Days between overdue reminders (1 = daily)",
		position = 5,
		section = notificationSection
	)
	default int overdueReminderFrequency()
	{
		return 1;
	}

	// ============================================================
	// OVERLAY SECTION
	// ============================================================

	@ConfigItem(
		keyName = "showSummaryPanel",
		name = "Summary Panel",
		description = "Show lending summary panel in overlay (loans count, total value)",
		position = 0,
		section = overlaySection
	)
	default boolean showSummaryPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRiskIndicators",
		name = "Risk Indicators",
		description = "Show risk indicators above players you have active loans with",
		position = 1,
		section = overlaySection
	)
	default boolean showRiskIndicators()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverdueWarnings",
		name = "Overdue Warnings",
		description = "Show visual warnings for overdue items above players",
		position = 2,
		section = overlaySection
	)
	default boolean showOverdueWarnings()
	{
		return true;
	}

	// ============================================================
	// RISK & SAFETY SECTION
	// ============================================================

	@ConfigItem(
		keyName = "maxAcceptableRisk",
		name = "Max Acceptable Risk",
		description = "Risk level threshold for warnings (0=Excellent, 1=Good, 2=Moderate, 3=High, 4=Critical). " +
					 "Players above this level trigger warnings.",
		position = 0,
		section = safetySection
	)
	default int maxAcceptableRisk()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "warnHighRiskBorrowers",
		name = "Warn High Risk Borrowers",
		description = "Show warning dialog when lending to high-risk borrowers",
		position = 1,
		section = safetySection
	)
	default boolean warnHighRiskBorrowers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highValueThreshold",
		name = "High Value Threshold",
		description = "GP value considered high value (triggers confirmations)",
		position = 2,
		section = safetySection
	)
	default int highValueThreshold()
	{
		return 1000000;
	}

	@ConfigItem(
		keyName = "confirmHighValueLoans",
		name = "Confirm High Value Loans",
		description = "Show confirmation dialog for loans above the high value threshold",
		position = 3,
		section = safetySection
	)
	default boolean confirmHighValueLoans()
	{
		return true;
	}

	@ConfigItem(
		keyName = "requireCollateral",
		name = "Require Collateral",
		description = "Require collateral for all loans before lending items",
		position = 4,
		section = safetySection
	)
	default boolean requireCollateral()
	{
		return false;
	}

	@ConfigItem(
		keyName = "minimumLoanValue",
		name = "Minimum Value to Track",
		description = "Minimum GP value to automatically track (0 = track everything)",
		position = 5,
		section = safetySection
	)
	default int minimumLoanValue()
	{
		return 10000;
	}

	// ============================================================
	// LOAN DEFAULTS SECTION
	// ============================================================

	@ConfigItem(
		keyName = "defaultLoanDuration",
		name = "Default Duration (Days)",
		description = "Default duration for new loans in days",
		position = 0,
		section = defaultsSection
	)
	default int defaultLoanDuration()
	{
		return 7;
	}

	// ============================================================
	// SCREENSHOTS SECTION
	// ============================================================

	@ConfigItem(
		keyName = "enableTradeScreenshots",
		name = "Trade Screenshots",
		description = "Automatically capture screenshots during lending and return trades (second accept + completion)",
		position = 0,
		section = screenshotSection
	)
	default boolean enableTradeScreenshots()
	{
		return false;
	}

	@ConfigItem(
		keyName = "screenshotIncludeOverlay",
		name = "Include Info Overlay",
		description = "Add lending details overlay text to screenshots",
		position = 1,
		section = screenshotSection
	)
	default boolean screenshotIncludeOverlay()
	{
		return true;
	}

	// ============================================================
	// MARKETPLACE SECTION
	// ============================================================

	@ConfigItem(
		keyName = "showOnlineStatus",
		name = "Show Online Status",
		description = "Show online/offline status for group members in the marketplace",
		position = 0,
		section = marketplaceSection
	)
	default boolean showOnlineStatus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideOfflineOfferings",
		name = "Hide Offline Offerings",
		description = "Hide marketplace offerings from offline users (when disabled, they're shown but dimmed)",
		position = 1,
		section = marketplaceSection
	)
	default boolean hideOfflineOfferings()
	{
		return false;
	}

	// ============================================================
	// DATA & STORAGE SECTION
	// ============================================================

	@ConfigItem(
		keyName = "dataRetentionDays",
		name = "Data Retention (Days)",
		description = "How long to keep returned loan records (0 = keep forever)",
		position = 0,
		section = dataSection
	)
	default int dataRetentionDays()
	{
		return 90;
	}

	// ============================================================
	// DISCORD WEBHOOKS SECTION
	// ============================================================

	@ConfigItem(
		keyName = "enableDiscordWebhooks",
		name = "Enable Discord Webhooks",
		description = "Send notifications to a Discord channel when lending events occur",
		position = 0,
		section = discordSection
	)
	default boolean enableDiscordWebhooks()
	{
		return false;
	}

	// NOTE: Webhook URLs are stored PER-GROUP using ConfigManager, not here.
	// This allows different groups to have different webhooks, and URLs are
	// automatically cleared when a user leaves or is kicked from a group.
	// Configure webhook URL via the "Discord Settings" button in the group panel.

	@ConfigItem(
		keyName = "webhookTransactionEvents",
		name = "Transaction Events",
		description = "Send notifications for lending and return events",
		position = 2,
		section = discordSection
	)
	default boolean webhookTransactionEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webhookAlertEvents",
		name = "Alert Events",
		description = "Send notifications for overdue loans and risk warnings",
		position = 3,
		section = discordSection
	)
	default boolean webhookAlertEvents()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webhookActivityEvents",
		name = "Activity Events",
		description = "Send notifications for member joins/leaves and borrow requests",
		position = 4,
		section = discordSection
	)
	default boolean webhookActivityEvents()
	{
		return false;
	}

	@ConfigItem(
		keyName = "webhookRequireRole",
		name = "Require Role to Send",
		description = "Controls who sends webhooks to prevent duplicates. " +
					  "HIGHEST_ONLINE (recommended): Only the highest-ranked online member sends. " +
					  "Role options: Only members with that role or higher can send.",
		position = 8,
		section = discordSection
	)
	default WebhookSenderRole webhookRequireRole()
	{
		return WebhookSenderRole.HIGHEST_ONLINE;
	}

	/**
	 * Enum for webhook sender role requirement
	 */
	enum WebhookSenderRole
	{
		HIGHEST_ONLINE("Highest Rank Online (recommended)"),
		OWNER_ONLY("Owner Only"),
		ADMIN("Admin+"),
		MODERATOR("Moderator+"),
		MEMBER("Any Member"),
		ANYONE("Anyone (duplicates possible)");

		private final String description;

		WebhookSenderRole(String description)
		{
			this.description = description;
		}

		@Override
		public String toString()
		{
			return description;
		}
	}

}
