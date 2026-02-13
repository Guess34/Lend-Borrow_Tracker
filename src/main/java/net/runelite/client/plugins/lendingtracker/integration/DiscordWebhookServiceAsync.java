package net.runelite.client.plugins.lendingtracker.integration;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.lendingtracker.LendingTrackerConfig;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.services.WebhookAuditLogger;
import net.runelite.client.plugins.lendingtracker.services.WebhookRateLimiter;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DiscordWebhookServiceAsync - Async Discord webhook integration
 * Phase 2: Refactored to use OkHttpClient for non-blocking execution
 * Supports both Standard Mode (simple embeds) and TNS Bot Mode (multipart uploads)
 */
@Slf4j
@Singleton
public class DiscordWebhookServiceAsync
{
	private static final String CONFIG_KEY_WEBHOOK_URL = "discord_webhook_url_";
	private static final String CONFIG_KEY_WEBHOOK_ENABLED = "discord_webhook_enabled_";
	private static final String CONFIG_KEY_WEBHOOK_EVENTS = "discord_webhook_events_";
	private static final String CONFIG_KEY_TNS_MODE_ENABLED = "discord_tns_mode_enabled_";

	@Inject
	private ConfigManager configManager;

	@Inject
	private GroupConfigStore groupConfigStore;

	@Inject
	private LendingTrackerConfig config;

	@Inject
	private WebhookRateLimiter rateLimiter;

	@Inject
	private WebhookAuditLogger auditLogger;

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	public DiscordWebhookServiceAsync()
	{
		// Create OkHttpClient with proper timeouts
		this.httpClient = new OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.writeTimeout(30, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build();

		this.gson = new Gson();
	}

	/**
	 * Set Discord webhook URL for a specific group
	 */
	public void setWebhookUrl(String groupId, String webhookUrl)
	{
		configManager.setConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_URL + groupId, webhookUrl);
	}

	/**
	 * Get Discord webhook URL for a specific group
	 */
	public String getWebhookUrl(String groupId)
	{
		return configManager.getConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_URL + groupId);
	}

	/**
	 * Enable/disable webhook notifications for a group
	 */
	public void setWebhookEnabled(String groupId, boolean enabled)
	{
		configManager.setConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_ENABLED + groupId, String.valueOf(enabled));
	}

	/**
	 * Check if webhooks are enabled for a group
	 */
	public boolean isWebhookEnabled(String groupId)
	{
		String enabled = configManager.getConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_ENABLED + groupId);
		return "true".equals(enabled);
	}

	/**
	 * Enable/disable TNS Bot Mode for a group
	 */
	public void setTNSModeEnabled(String groupId, boolean enabled)
	{
		configManager.setConfiguration("lendingtracker", CONFIG_KEY_TNS_MODE_ENABLED + groupId, String.valueOf(enabled));
	}

	/**
	 * Check if TNS Bot Mode is enabled for a group
	 */
	public boolean isTNSModeEnabled(String groupId)
	{
		String enabled = configManager.getConfiguration("lendingtracker", CONFIG_KEY_TNS_MODE_ENABLED + groupId);
		return "true".equals(enabled);
	}

	/**
	 * Set which events trigger webhooks for a group
	 */
	public void setWebhookEvents(String groupId, String events)
	{
		configManager.setConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_EVENTS + groupId, events);
	}

	/**
	 * Get webhook events configuration for a group
	 */
	public String getWebhookEvents(String groupId)
	{
		String events = configManager.getConfiguration("lendingtracker", CONFIG_KEY_WEBHOOK_EVENTS + groupId);
		return events != null ? events : "lending,returning,overdue,risk"; // Default events
	}

	/**
	 * Send lending notification to Discord (async)
	 */
	public void sendLendingNotification(String groupId, LendingEntry entry)
	{
		sendLendingNotification(groupId, entry, null);
	}

	/**
	 * Send lending notification to Discord with optional screenshot (async)
	 */
	public void sendLendingNotification(String groupId, LendingEntry entry, File screenshot)
	{
		if (!shouldSendEvent(groupId, "lending"))
		{
			return;
		}

		LendingGroup group = groupConfigStore.getGroup(groupId);
		if (group == null)
		{
			return;
		}

		// Check if TNS Mode is enabled
		boolean tnsMode = isTNSModeEnabled(groupId);

		if (tnsMode && screenshot != null)
		{
			// TNS Bot Mode: Multipart upload with screenshot
			sendTNSBotNotification(groupId, group, entry, screenshot, "lending");
		}
		else
		{
			// Standard Mode: Simple embed
			DiscordEmbed embed = new DiscordEmbed()
				.setColor(0x00FF00) // Green
				.setTitle("📦 Item Lent")
				.addField("Group", group.getName(), true)
				.addField("Lender", entry.getLender(), true)
				.addField("Borrower", entry.getBorrower(), true)
				.addField("Item", entry.getItem() + " x" + entry.getQuantity(), false)
				.addField("Value", QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP", true)
				.addField("Due Date", formatDueDate(entry.getDueTime()), true)
				.setTimestamp(Instant.now());

			sendStandardWebhook(groupId, entry.getLender(), "lending", entry.getId(), embed);
		}
	}

	/**
	 * Send return notification to Discord (async)
	 */
	public void sendReturnNotification(String groupId, LendingEntry entry)
	{
		if (!shouldSendEvent(groupId, "returning"))
		{
			return;
		}

		LendingGroup group = groupConfigStore.getGroup(groupId);
		if (group == null)
		{
			return;
		}

		DiscordEmbed embed = new DiscordEmbed()
			.setColor(0x0099FF) // Blue
			.setTitle("✅ Item Returned")
			.addField("Group", group.getName(), true)
			.addField("Borrower", entry.getBorrower(), true)
			.addField("Lender", entry.getLender(), true)
			.addField("Item", entry.getItem() + " x" + entry.getQuantity(), false)
			.addField("Value", QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP", true)
			.addField("Loan Duration", calculateLoanDuration(entry), true)
			.setTimestamp(Instant.now());

		sendStandardWebhook(groupId, entry.getBorrower(), "returning", entry.getId(), embed);
	}

	/**
	 * Send overdue notification to Discord (async)
	 */
	public void sendOverdueNotification(String groupId, LendingEntry entry, int daysOverdue)
	{
		if (!shouldSendEvent(groupId, "overdue"))
		{
			return;
		}

		LendingGroup group = groupConfigStore.getGroup(groupId);
		if (group == null)
		{
			return;
		}

		DiscordEmbed embed = new DiscordEmbed()
			.setColor(0xFF9900) // Orange
			.setTitle("⚠️ Overdue Loan Alert")
			.addField("Group", group.getName(), true)
			.addField("Borrower", entry.getBorrower(), true)
			.addField("Lender", entry.getLender(), true)
			.addField("Item", entry.getItem() + " x" + entry.getQuantity(), false)
			.addField("Value", QuantityFormatter.quantityToStackSize(entry.getValue()) + " GP", true)
			.addField("Days Overdue", String.valueOf(daysOverdue), true)
			.addField("Due Date", formatDueDate(entry.getDueTime()), true)
			.setTimestamp(Instant.now());

		sendStandardWebhook(groupId, entry.getLender(), "overdue", entry.getId(), embed);
	}

	/**
	 * Send Standard Mode webhook (simple JSON embed) - ASYNC
	 */
	private void sendStandardWebhook(String groupId, String rsn, String eventType, String eventData, DiscordEmbed embed)
	{
		if (!config.enableDiscordWebhooks())
		{
			log.debug("Discord webhooks disabled in config, skipping notification");
			return;
		}

		// Check rate limit BEFORE making request
		WebhookRateLimiter.RateLimitResult rateLimitResult = rateLimiter.checkLimit(groupId, rsn, eventType, eventData);
		if (!rateLimitResult.isAllowed())
		{
			log.warn("Webhook rate limited for {} in group {}: {}", rsn, groupId, rateLimitResult.getReason());
			return;
		}

		String webhookUrl = getWebhookUrl(groupId);
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			log.warn("No webhook URL configured for group {}", groupId);
			return;
		}

		// Build JSON payload
		DiscordWebhook webhook = new DiscordWebhook().addEmbed(embed);
		String jsonPayload = gson.toJson(webhook);

		// Build request (OkHttp 3.x API: MediaType first, then content)
		MediaType JSON = MediaType.parse("application/json; charset=utf-8");
		RequestBody body = RequestBody.create(JSON, jsonPayload);
		Request request = new Request.Builder()
			.url(webhookUrl)
			.post(body)
			.build();

		// Execute async with callback
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("Discord webhook failed for group {}", groupId, e);
				auditLogger.logAttempt(groupId, rsn, eventType, false, e.getMessage(), eventData);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (response.isSuccessful())
					{
						log.debug("Discord webhook sent successfully for group {}", groupId);
						auditLogger.logAttempt(groupId, rsn, eventType, true, null, eventData);
					}
					else
					{
						String errorBody = response.body() != null ? response.body().string() : "No error body";
						log.warn("Discord webhook failed for group {} with status {}: {}",
							groupId, response.code(), errorBody);
						auditLogger.logAttempt(groupId, rsn, eventType, false, "HTTP " + response.code(), eventData);
					}
				}
			}
		});
	}

	/**
	 * Send TNS Bot Mode webhook (multipart with screenshot) - ASYNC
	 */
	private void sendTNSBotNotification(String groupId, LendingGroup group, LendingEntry entry,
		File screenshot, String eventType)
	{
		if (!config.enableDiscordWebhooks())
		{
			return;
		}

		String webhookUrl = getWebhookUrl(groupId);
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			log.warn("No webhook URL configured for group {}", groupId);
			return;
		}

		// Build TNS Bot JSON payload
		Map<String, Object> tnsPayload = new HashMap<>();
		tnsPayload.put("event_type", eventType);
		tnsPayload.put("group_id", groupId);
		tnsPayload.put("group_name", group.getName());
		tnsPayload.put("lender", entry.getLender());
		tnsPayload.put("borrower", entry.getBorrower());
		tnsPayload.put("item", entry.getItem());
		tnsPayload.put("quantity", entry.getQuantity());
		tnsPayload.put("value", entry.getValue());
		tnsPayload.put("due_time", entry.getDueTime());
		tnsPayload.put("timestamp", System.currentTimeMillis());

		String jsonPayload = gson.toJson(tnsPayload);

		// Build multipart body
		MultipartBody.Builder multipartBuilder = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("payload_json", jsonPayload);

		// Add screenshot if provided (OkHttp 3.x API: MediaType first, then file)
		if (screenshot != null && screenshot.exists())
		{
			MediaType PNG = MediaType.parse("image/png");
			RequestBody screenshotBody = RequestBody.create(PNG, screenshot);
			multipartBuilder.addFormDataPart("file", screenshot.getName(), screenshotBody);
		}

		RequestBody body = multipartBuilder.build();

		Request request = new Request.Builder()
			.url(webhookUrl)
			.post(body)
			.build();

		// Execute async with callback
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("TNS Bot webhook failed for group {}", groupId, e);
				auditLogger.logAttempt(groupId, entry.getLender(), eventType, false, e.getMessage(), entry.getId());
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					if (response.isSuccessful())
					{
						log.info("TNS Bot webhook sent successfully for group {}", groupId);
						auditLogger.logAttempt(groupId, entry.getLender(), eventType, true, null, entry.getId());
					}
					else
					{
						String errorBody = response.body() != null ? response.body().string() : "No error body";
						log.warn("TNS Bot webhook failed for group {} with status {}: {}",
							groupId, response.code(), errorBody);
						auditLogger.logAttempt(groupId, entry.getLender(), eventType, false,
							"HTTP " + response.code(), entry.getId());
					}
				}
			}
		});
	}

	/**
	 * Check if a specific event type should trigger webhook
	 */
	private boolean shouldSendEvent(String groupId, String eventType)
	{
		if (!isWebhookEnabled(groupId))
		{
			return false;
		}

		String webhookUrl = getWebhookUrl(groupId);
		if (webhookUrl == null || webhookUrl.trim().isEmpty())
		{
			return false;
		}

		String events = getWebhookEvents(groupId);
		return events.contains(eventType);
	}

	/**
	 * Format due date for Discord timestamp
	 */
	private String formatDueDate(long dueTime)
	{
		if (dueTime <= 0)
		{
			return "No due date";
		}
		return "<t:" + (dueTime / 1000) + ":R>"; // Discord relative timestamp
	}

	/**
	 * Calculate loan duration
	 */
	private String calculateLoanDuration(LendingEntry entry)
	{
		long lendTime = entry.getLendTime();
		long returnTime = Instant.now().toEpochMilli();
		long durationHours = (returnTime - lendTime) / (1000 * 60 * 60);

		if (durationHours < 24)
		{
			return durationHours + " hours";
		}
		else
		{
			long days = durationHours / 24;
			return days + " days";
		}
	}

	/**
	 * Shutdown HTTP client
	 */
	public void shutdown()
	{
		if (httpClient != null)
		{
			httpClient.dispatcher().executorService().shutdown();
			httpClient.connectionPool().evictAll();
		}
	}

	// ===== Discord payload structures =====

	private static class DiscordWebhook
	{
		private java.util.List<DiscordEmbed> embeds = new java.util.ArrayList<>();

		public DiscordWebhook addEmbed(DiscordEmbed embed)
		{
			embeds.add(embed);
			return this;
		}
	}

	private static class DiscordEmbed
	{
		private String title;
		private String description;
		private int color;
		private String timestamp;
		private java.util.List<DiscordField> fields = new java.util.ArrayList<>();

		public DiscordEmbed setTitle(String title)
		{
			this.title = title;
			return this;
		}

		public DiscordEmbed setDescription(String description)
		{
			this.description = description;
			return this;
		}

		public DiscordEmbed setColor(int color)
		{
			this.color = color;
			return this;
		}

		public DiscordEmbed setTimestamp(Instant timestamp)
		{
			this.timestamp = timestamp.toString();
			return this;
		}

		public DiscordEmbed addField(String name, String value, boolean inline)
		{
			fields.add(new DiscordField(name, value, inline));
			return this;
		}
	}

	private static class DiscordField
	{
		private final String name;
		private final String value;
		private final boolean inline;

		public DiscordField(String name, String value, boolean inline)
		{
			this.name = name;
			this.value = value;
			this.inline = inline;
		}
	}
}
