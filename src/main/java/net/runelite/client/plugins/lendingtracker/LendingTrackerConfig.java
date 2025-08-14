package net.runelite.client.plugins.lendingtracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("lendingtracker")
public interface LendingTrackerConfig extends Config
{
    @ConfigItem(
        keyName = "enabled",
        name = "Enable tracking",
        description = "When enabled, Lending Tracker records completed trades and lists them in the side panel."
    )
    default boolean enabled() { return true; }

    @ConfigItem(
        keyName = "promptRole",
        name = "Prompt for role on trade",
        description = "When the first trade window opens, you’ll see a small notice in the panel to pick Lend / Borrow / Not a loan. "
                    + "Tip: If you forget, the trade still logs, you can set the role after."
    )
    default boolean promptRole() { return true; }

    @ConfigItem(
        keyName = "defaultRole",
        name = "Default role for next trade",
        description = "If you rarely borrow, set this to LENDER and you won’t need to pick as often. Options: NONE, LENDER, BORROWER"
    )
    default Role defaultRole() { return Role.NONE; }

    @ConfigItem(
        keyName = "maxEntries",
        name = "Max panel entries",
        description = "How many recent records to show in the side panel. Older ones remain in the file."
    )
    default int maxEntries() { return 50; }

    // --- Optional webhook integration (OFF by default) ---

    @ConfigItem(
        keyName = "enableWebhook",
        name = "Enable webhook (optional)",
        description = "OFF by default. Turn on to POST completed-trade events to your own webhook (Discord or any HTTP endpoint). "
                    + "If you don’t know what this is, leave it off."
    )
    default boolean enableWebhook() { return false; }

    @ConfigItem(
        keyName = "webhookUrl",
        name = "Webhook URL",
        description = "Paste your own webhook URL here (e.g., your clan’s discord bot). Nothing is sent unless 'Enable webhook' is ON. "
                    + "See README for payload format."
    )
    default String webhookUrl() { return ""; }

    @ConfigItem(
        keyName = "hmacSecret",
        name = "HMAC secret (optional)",
        description = "Optional signing key for verifying messages. Use only if required. (Advanced: adds an HMAC-SHA256 signature.)"
    )
    default String hmacSecret() { return ""; }

    enum Role { NONE, LENDER, BORROWER }
}
