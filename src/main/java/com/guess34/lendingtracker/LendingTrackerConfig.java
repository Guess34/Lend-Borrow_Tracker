package com.guess34.lendingtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("lendingtracker")
public interface LendingTrackerConfig extends Config
{
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

	// Screenshots

	@ConfigItem(
		keyName = "enableTradeScreenshots",
		name = "Trade Screenshots",
		description = "Automatically capture screenshots during lending and return trades",
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
