package com.guess34.lendingtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("lendingtracker")
public interface LendingTrackerConfig extends Config
{
	/** How a guard reacts when a borrowed item is at risk. */
	enum GuardMode
	{
		OFF,
		WARN,
		BLOCK
	}

	// Sections

	@ConfigSection(
		name = "Notifications",
		description = "Configure how and when you receive notifications",
		position = 10,
		closedByDefault = false
	)
	String notificationSection = "notifications";

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
		name = "Borrowed Item Guards",
		description = "Warn or block risky actions while you hold someone else's items",
		position = 55,
		closedByDefault = false
	)
	String guardSection = "guards";

	@ConfigSection(
		name = "Sync",
		description = "Cross-machine group sync settings",
		position = 60,
		closedByDefault = true
	)
	String syncSection = "sync";

	@ConfigSection(
		name = "Discord",
		description = "Post loans to a Discord channel through a webhook",
		position = 65,
		closedByDefault = true
	)
	String discordSection = "discord";

	@ConfigSection(
		name = "Data & Storage",
		description = "Data retention and storage settings",
		position = 70,
		closedByDefault = true
	)
	String dataSection = "data";

	// Top-level Options

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

	// Notifications

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

	// Loan Defaults

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

	@ConfigItem(
		keyName = "showInviteMenuOption",
		name = "Right-click Invite Option",
		description = "Show \"Invite to Lending Group\" when you right-click another player. Only ever appears if you can actually invite.",
		position = 1,
		section = defaultsSection
	)
	default boolean showInviteMenuOption()
	{
		return true;
	}

	// Screenshots

	@ConfigItem(
		keyName = "enableTradeScreenshots",
		name = "Loan Trade Screenshots",
		description = "Save a proof screenshot when a trade you marked as a loan (or a return of a loan) completes. Saved locally under .runelite/lending-tracker/proof",
		position = 0,
		section = screenshotSection
	)
	default boolean enableTradeScreenshots()
	{
		return true;
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

	// Borrowed Item Guards

	@ConfigItem(
		keyName = "tradeGuard",
		name = "Trade Guard",
		description = "Warn or block when you try to offer an item you're borrowing in a trade. Hold Shift to override a block. Guards only know about loans recorded on (or synced to) this client.",
		position = 0,
		section = guardSection
	)
	default GuardMode tradeGuard()
	{
		return GuardMode.WARN;
	}

	@ConfigItem(
		keyName = "allowLendBorrowedToGroup",
		name = "Allow Trading to Group Members",
		description = "Only warn (never block) when offering a borrowed item to a member of your lending group, e.g. handing it back or passing it on",
		position = 1,
		section = guardSection
	)
	default boolean allowLendBorrowedToGroup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "wildernessGuard",
		name = "Wilderness Guard",
		description = "Warn when entering the Wilderness carrying borrowed items. BLOCK also stops the Wilderness ditch 'Cross' click (hold Shift to override). Teleports and other entrances can't be blocked - you still get the warning.",
		position = 2,
		section = guardSection
	)
	default GuardMode wildernessGuard()
	{
		return GuardMode.WARN;
	}

	@ConfigItem(
		keyName = "alertLenderWilderness",
		name = "Lender Wilderness Alerts",
		description = "Notify you when someone borrowing YOUR item has been in the Wilderness for 45+ seconds (requires the borrower to be online with the plugin)",
		position = 3,
		section = guardSection
	)
	default boolean alertLenderWilderness()
	{
		return true;
	}

	@ConfigItem(
		keyName = "promptListedLoans",
		name = "Detect Loans from Marketplace",
		description = "When an item you have listed for lending appears in your trade offer, ask once per trade whether to record the trade as a loan - no right-click marking needed",
		position = 4,
		section = guardSection
	)
	default boolean promptListedLoans()
	{
		return true;
	}

	// Sync

	@ConfigItem(
		keyName = "enableRelaySync",
		name = "Enable Cloud Sync",
		description = "Sync group data between members on different computers via relay server",
		// The first sentence is the canonical wording RuneLite requires verbatim for
		// any third-party server feature; additional disclosure goes AFTER it, never
		// spliced into the middle.
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers. It also sends your player name, current world, and online status; group members can see when you are online and what world you are on.",
		position = 0,
		section = syncSection
	)
	default boolean enableRelaySync()
	{
		return false;
	}

	@ConfigItem(
		keyName = "relayServerUrl",
		name = "Relay Server URL",
		description = "URL of the sync relay server (leave default unless self-hosting)",
		position = 1,
		section = syncSection
	)
	default String relayServerUrl()
	{
		return "wss://lending-tracker-relay.onrender.com";
	}

	// Discord

	@ConfigItem(
		keyName = "webhookEnabled",
		name = "Post loans to Discord",
		description = "Post your loans to your group's Discord channel. The channel is set by the group's staff in the group settings, so each group's loans only go to that group. Only the lender posts, so each loan shows once - everyone who lends should turn this on.",
		// Canonical third-party wording first, verbatim; extra disclosure after it.
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers. It posts your loans - player names, items, values, due dates and a picture of the trade window - to the Discord channel your group's staff have set up.",
		position = 0,
		section = discordSection
	)
	default boolean webhookEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "webhookLoans",
		name = "New loans",
		description = "Post when you lend something out",
		position = 2,
		section = discordSection
	)
	default boolean webhookLoans()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webhookReturns",
		name = "Returns",
		description = "Post when a loan is returned, forgiven or removed",
		position = 3,
		section = discordSection
	)
	default boolean webhookReturns()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webhookOverdue",
		name = "Overdue loans",
		description = "Post when a loan goes overdue (same days as your overdue reminders)",
		position = 4,
		section = discordSection
	)
	default boolean webhookOverdue()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webhookScreenshot",
		name = "Attach trade picture",
		description = "Add a picture of the trade window to loan and return posts - the trade window only, never your chat or inventory. Needs Loan Trade Screenshots on.",
		position = 5,
		section = discordSection
	)
	default boolean webhookScreenshot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "webhookPingMe",
		name = "Ping me in Discord",
		description = "Tag you in your groups' Discord posts about loans you're part of. Your Discord ID is picked up from the Discord app when it's open on this computer, or from the box below.",
		warning = "This shares your Discord user ID with the members of your groups so loan posts can tag you. It goes through the sync server locked with each group's key, so the server can't read it.",
		position = 6,
		section = discordSection
	)
	default boolean webhookPingMe()
	{
		return false;
	}

	@ConfigItem(
		keyName = "webhookDiscordId",
		name = "My Discord user ID",
		description = "Only needed if you use Discord in a browser or on your phone. In Discord: Settings > Advanced > turn on Developer Mode, then right-click your name > Copy User ID.",
		position = 7,
		section = discordSection
	)
	default String webhookDiscordId()
	{
		return "";
	}

	// Data & Storage

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
}
