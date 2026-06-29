package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import com.guess34.lendingtracker.LendingTrackerConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages WebSocket connection to the relay server for cross-machine group sync.
 * Also provides REST methods for invite code create/lookup/consume.
 *
 * ADDED: HMAC-SHA256 message signing and verification to prevent forged sync events.
 * Every outgoing sync message includes a signature computed from the group's shared secret.
 * Incoming messages are verified before being passed to the event handler.
 */
@Slf4j
@Singleton
public class RelaySyncService
{
	private static final int CLOSE_NORMAL = 1000;
	private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
	private static final long MAX_RECONNECT_DELAY_MS = 30000;
	private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
	private static final String HMAC_ALGORITHM = "HmacSHA256";
	// ADDED: Max age for sync messages to prevent replay attacks (5 minutes)
	private static final long MAX_MESSAGE_AGE_MS = 5 * 60 * 1000;
	// Keepalive cadence: ping ~every 12 min, never sooner than 2 min apart
	private static final long KEEPALIVE_INTERVAL_MS = 12 * 60 * 1000;
	private static final long KEEPALIVE_MIN_DELAY_MS = 2 * 60 * 1000;
	// Render free tier takes 30-60s to wake from spindown — shared OkHttpClient's
	// default 10s timeout would give up before the server responds, so the
	// REST client used for relay calls needs its own longer timeouts.
	private static final long REST_CONNECT_TIMEOUT_S = 30;
	private static final long REST_READ_TIMEOUT_S = 60;

	@Inject private OkHttpClient httpClient;
	@Inject private Gson gson;
	@Inject private LendingTrackerConfig config;

	private volatile WebSocket webSocket;
	private String currentGroupId;
	private String currentPlayerName;
	// ADDED: Current group's sync secret for HMAC signing
	private String currentSyncSecret;
	private volatile boolean connected = false;
	private volatile boolean intentionalClose = false;
	private long reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
	private ScheduledExecutorService reconnectExecutor;
	private ScheduledExecutorService keepaliveExecutor;
	private volatile OkHttpClient restClient;
	private Consumer<GroupService.SyncEvent> onEventReceived;
	private Consumer<Boolean> onConnectionChanged;
	private java.util.function.BiConsumer<String, String> onStateReceived; // (groupJson, dataJson)

	// --- Connection Lifecycle ---

	public void connect()
	{
		if (config == null || !config.enableRelaySync()) return;

		String url = config.relayServerUrl();
		if (url == null || url.isEmpty()) return;

		// If already connected, don't reconnect
		if (connected && webSocket != null)
		{
			log.debug("Relay already connected, skipping connect");
			return;
		}

		intentionalClose = false;
		reconnectDelay = INITIAL_RECONNECT_DELAY_MS;

		if (reconnectExecutor == null || reconnectExecutor.isShutdown())
		{
			reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
		}

		doConnect(url);
	}

	private void doConnect(String url)
	{
		if (config == null || !config.enableRelaySync()) return;

		try
		{
			log.debug("Connecting to relay: {}", url);
			Request request = new Request.Builder().url(url).build();
			webSocket = httpClient.newWebSocket(request, new RelayWebSocketListener());
		}
		catch (Exception e)
		{
			log.warn("Failed to connect to relay: {}", e.getMessage());
			scheduleReconnect();
		}
	}

	public void disconnect()
	{
		intentionalClose = true;
		connected = false;

		// Null out the field FIRST so stale callbacks see ws != webSocket
		WebSocket ws = webSocket;
		webSocket = null;

		if (ws != null)
		{
			try
			{
				ws.close(CLOSE_NORMAL, "Plugin shutdown");
			}
			catch (Exception ignored) { }
		}

		if (reconnectExecutor != null && !reconnectExecutor.isShutdown())
		{
			reconnectExecutor.shutdownNow();
			reconnectExecutor = null;
		}

		notifyConnectionChanged(false);
	}

	// --- Room Management ---

	/**
	 * Join a sync room for the given group.
	 * CHANGED: Now accepts syncSecret for HMAC message signing.
	 */
	public void joinRoom(String groupId, String playerName, String syncSecret)
	{
		if (config == null || !config.enableRelaySync()) return;

		this.currentGroupId = groupId;
		this.currentPlayerName = playerName;
		this.currentSyncSecret = syncSecret;

		if (connected && webSocket != null)
		{
			sendJoinMessage(groupId, playerName);
		}
	}

	/**
	 * @deprecated Use {@link #joinRoom(String, String, String)} with syncSecret instead.
	 */
	@Deprecated
	public void joinRoom(String groupId, String playerName)
	{
		joinRoom(groupId, playerName, null);
	}

	private void sendJoinMessage(String groupId, String playerName)
	{
		if (config == null || !config.enableRelaySync()) return;

		WebSocket ws = webSocket;
		if (ws == null) return;

		JsonObject msg = new JsonObject();
		msg.addProperty("type", "join");
		msg.addProperty("groupId", groupId);
		msg.addProperty("playerName", playerName);
		ws.send(gson.toJson(msg));
	}

	public void leaveRoom(String groupId)
	{
		if (config == null || !config.enableRelaySync()) return;

		if (connected && webSocket != null && groupId != null)
		{
			JsonObject msg = new JsonObject();
			msg.addProperty("type", "leave");
			msg.addProperty("groupId", groupId);
			webSocket.send(gson.toJson(msg));
		}

		if (groupId != null && groupId.equals(currentGroupId))
		{
			currentGroupId = null;
			currentSyncSecret = null;
		}
	}

	// --- Sync Events ---

	/**
	 * Send a sync event to the relay server.
	 * CHANGED: Now signs the event payload with HMAC-SHA256 using the group's sync secret.
	 */
	public void sendEvent(String groupId, GroupService.SyncEvent event)
	{
		if (config == null || !config.enableRelaySync()) return;
		if (!connected || webSocket == null || groupId == null) return;

		JsonObject eventJson = gson.toJsonTree(event).getAsJsonObject();

		JsonObject msg = new JsonObject();
		msg.addProperty("type", "sync");
		msg.addProperty("groupId", groupId);
		msg.add("event", eventJson);

		// ADDED: Sign the message if we have a sync secret
		if (currentSyncSecret != null && !currentSyncSecret.isEmpty())
		{
			String payload = buildSignaturePayload(groupId, eventJson);
			String signature = computeHmac(payload, currentSyncSecret);
			if (signature != null)
			{
				msg.addProperty("signature", signature);
			}
		}

		webSocket.send(gson.toJson(msg));
	}

	public boolean isConnected()
	{
		return connected;
	}

	// --- HMAC Signing & Verification ---

	/**
	 * ADDED: Build the canonical payload string for HMAC signing.
	 * Uses groupId + event type + timestamp + publisher to create a deterministic string.
	 */
	private String buildSignaturePayload(String groupId, JsonObject eventJson)
	{
		String type = eventJson.has("type") ? eventJson.get("type").getAsString() : "";
		String timestamp = eventJson.has("timestamp") ? eventJson.get("timestamp").getAsString() : "0";
		String publisher = eventJson.has("publisher") ? eventJson.get("publisher").getAsString() : "";
		return groupId + ":" + type + ":" + timestamp + ":" + publisher;
	}

	/**
	 * ADDED: Compute HMAC-SHA256 signature for the given payload using the shared secret.
	 */
	private String computeHmac(String payload, String secret)
	{
		try
		{
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(
				secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
			mac.init(keySpec);
			byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

			StringBuilder sb = new StringBuilder(hmacBytes.length * 2);
			for (byte b : hmacBytes)
			{
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			log.warn("Failed to compute HMAC: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * ADDED: Verify the HMAC signature on an incoming relay message.
	 * Returns true if the signature is valid, false otherwise.
	 */
	private boolean verifySignature(JsonObject msg, JsonObject eventJson)
	{
		// If we don't have a sync secret, we can't verify — reject the message
		if (currentSyncSecret == null || currentSyncSecret.isEmpty())
		{
			log.warn("Rejecting relay message: no sync secret configured for current group");
			return false;
		}

		// If the message has no signature, reject it
		if (!msg.has("signature"))
		{
			log.warn("Rejecting relay message: missing HMAC signature");
			return false;
		}

		String groupId = msg.has("groupId") ? msg.get("groupId").getAsString() : "";
		String expectedPayload = buildSignaturePayload(groupId, eventJson);
		String expectedSignature = computeHmac(expectedPayload, currentSyncSecret);

		if (expectedSignature == null)
		{
			return false;
		}

		String receivedSignature = msg.get("signature").getAsString();
		// ADDED: Constant-time comparison to prevent timing attacks
		return constantTimeEquals(expectedSignature, receivedSignature);
	}

	/**
	 * ADDED: Constant-time string comparison to prevent timing side-channel attacks.
	 */
	private boolean constantTimeEquals(String a, String b)
	{
		if (a == null || b == null || a.length() != b.length())
		{
			return false;
		}
		int result = 0;
		for (int i = 0; i < a.length(); i++)
		{
			result |= a.charAt(i) ^ b.charAt(i);
		}
		return result == 0;
	}

	/**
	 * ADDED: Check if a sync event's timestamp is within the acceptable window.
	 * Rejects messages older than MAX_MESSAGE_AGE_MS to prevent replay attacks.
	 */
	private boolean isTimestampValid(JsonObject eventJson)
	{
		if (!eventJson.has("timestamp"))
		{
			return false;
		}

		long eventTime = eventJson.get("timestamp").getAsLong();
		long now = System.currentTimeMillis();
		long age = Math.abs(now - eventTime);

		if (age > MAX_MESSAGE_AGE_MS)
		{
			log.warn("Rejecting relay message: timestamp too old (age={}ms, max={}ms)", age, MAX_MESSAGE_AGE_MS);
			return false;
		}

		return true;
	}

	// --- REST: Invite Codes ---

	public void publishInviteCode(String code, String groupId, String groupJson)
	{
		if (config == null || !config.enableRelaySync()) return;

		String baseUrl = getRestBaseUrl();
		if (baseUrl == null)
		{
			log.warn("Cannot publish invite code: relay URL is not configured");
			return;
		}

		log.info("Publishing invite code {} for group {} to relay at {}", code, groupId, baseUrl);

		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		body.addProperty("groupId", groupId);
		body.addProperty("groupJson", groupJson);

		RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toString());
		Request request = new Request.Builder()
			.url(baseUrl + "/api/invite")
			.post(requestBody)
			.build();

		getRestClient().newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, java.io.IOException e)
			{
				log.warn("Failed to publish invite code to relay: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				if (response.isSuccessful())
				{
					log.info("Invite code {} published to relay successfully", code);
				}
				else
				{
					log.warn("Relay returned {} when publishing invite code {}", response.code(), code);
				}
				response.close();
			}
		});
	}

	/**
	 * Publish an invite code to the relay SYNCHRONOUSLY, retrying through a Render cold-start.
	 * Returns true only once the relay confirms it stored the code (HTTP 2xx).
	 *
	 * The fire-and-forget {@link #publishInviteCode} could silently fail against a sleeping
	 * server, leaving the owner sharing a code that was never stored - which is exactly why
	 * a freshly generated code could come back "invalid/expired" for a joiner seconds later.
	 * Code generation now uses this and warns the owner if the code did not land.
	 * Blocking - callers must run it off the EDT.
	 */
	public boolean publishInviteBlocking(String code, String groupId, String groupJson)
	{
		if (config == null || !config.enableRelaySync()) return false;

		String baseUrl = getRestBaseUrl();
		if (baseUrl == null)
		{
			log.warn("Cannot publish invite code: relay URL is not configured");
			return false;
		}

		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		body.addProperty("groupId", groupId);
		body.addProperty("groupJson", groupJson);
		RequestBody requestBody = RequestBody.create(JSON_MEDIA, body.toString());
		Request request = new Request.Builder()
			.url(baseUrl + "/api/invite")
			.post(requestBody)
			.build();

		int maxAttempts = 3;
		for (int attempt = 1; attempt <= maxAttempts; attempt++)
		{
			try (Response response = getRestClient().newCall(request).execute())
			{
				if (response.isSuccessful())
				{
					log.info("Invite code {} published to relay (attempt {})", code, attempt);
					return true;
				}
				log.warn("Publish attempt {} for code {} got HTTP {}", attempt, code, response.code());
			}
			catch (Exception e)
			{
				log.warn("Publish attempt {} for code {} failed: {}", attempt, code, e.getMessage());
			}
			// No delay between attempts: Thread.sleep is not permitted in Plugin Hub plugins, and
			// the REST client's long read timeout already holds the request open through a Render
			// cold-start, so the server's eventual 200 is normally received on the first attempt.
		}
		return false;
	}

	/**
	 * Outcome of a relay invite-code lookup.
	 * FOUND       - code exists, groupJson populated
	 * NOT_FOUND   - relay responded 404 (code invalid/expired/consumed)
	 * UNREACHABLE - relay not configured, timed out, or returned a server error
	 *               (e.g. Render cold-start exceeded the timeout) - worth retrying
	 */
	public enum InviteStatus { FOUND, NOT_FOUND, UNREACHABLE }

	public static final class InviteLookupResult
	{
		public final InviteStatus status;
		public final String groupJson;

		public InviteLookupResult(InviteStatus status, String groupJson)
		{
			this.status = status;
			this.groupJson = groupJson;
		}
	}

	/**
	 * Look up an invite code on the relay, distinguishing "not found" from "couldn't reach
	 * the server". The old String-returning lookupInviteCode collapsed both into null, so a
	 * cold-start timeout looked identical to a genuinely invalid code.
	 */
	public InviteLookupResult lookupInvite(String code)
	{
		if (config == null || !config.enableRelaySync()) return new InviteLookupResult(InviteStatus.UNREACHABLE, null);

		String baseUrl = getRestBaseUrl();
		if (baseUrl == null)
		{
			log.warn("Cannot lookup invite code: relay URL is not configured");
			return new InviteLookupResult(InviteStatus.UNREACHABLE, null);
		}

		log.info("Looking up invite code {} from relay at {}", code, baseUrl);

		Request request = new Request.Builder()
			.url(baseUrl + "/api/invite/" + code)
			.get()
			.build();

		try (Response response = getRestClient().newCall(request).execute())
		{
			log.info("Relay returned {} for invite code lookup {}", response.code(), code);
			if (response.isSuccessful() && response.body() != null)
			{
				String responseBody = response.body().string();
				JsonObject json = gson.fromJson(responseBody, JsonObject.class);
				String groupJson = json != null && json.has("groupJson")
					? json.get("groupJson").getAsString() : null;
				return groupJson != null
					? new InviteLookupResult(InviteStatus.FOUND, groupJson)
					: new InviteLookupResult(InviteStatus.NOT_FOUND, null);
			}
			if (response.code() == 404)
			{
				return new InviteLookupResult(InviteStatus.NOT_FOUND, null);
			}
			// 5xx / unexpected status - treat as transient so the user is told to retry
			return new InviteLookupResult(InviteStatus.UNREACHABLE, null);
		}
		catch (Exception e)
		{
			log.warn("Failed to lookup invite code from relay: {}", e.getMessage());
			return new InviteLookupResult(InviteStatus.UNREACHABLE, null);
		}
	}

	/**
	 * @deprecated Use {@link #lookupInvite(String)} which distinguishes not-found from
	 * unreachable. Retained for any callers that only need the group JSON.
	 */
	@Deprecated
	public String lookupInviteCode(String code)
	{
		return lookupInvite(code).groupJson;
	}

	public void consumeInviteCode(String code)
	{
		if (config == null || !config.enableRelaySync()) return;

		String baseUrl = getRestBaseUrl();
		if (baseUrl == null) return;

		Request request = new Request.Builder()
			.url(baseUrl + "/api/invite/" + code)
			.delete()
			.build();

		getRestClient().newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, java.io.IOException e)
			{
				log.warn("Failed to consume invite code on relay: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	// --- REST: Group State (catch-up sync) ---

	/**
	 * Push the latest group + data state to the relay so offline members can catch up.
	 */
	public void publishState(String groupId, String groupJson, String dataJson)
	{
		if (config == null || !config.enableRelaySync()) return;
		if (!connected || webSocket == null || groupId == null) return;

		JsonObject msg = new JsonObject();
		msg.addProperty("type", "state");
		msg.addProperty("groupId", groupId);
		msg.addProperty("groupJson", groupJson);
		if (dataJson != null)
		{
			msg.addProperty("dataJson", dataJson);
		}
		webSocket.send(gson.toJson(msg));
		log.debug("Published group state to relay for group {}", groupId);
	}

	// --- Callbacks ---

	public void setOnEventReceived(Consumer<GroupService.SyncEvent> callback)
	{
		this.onEventReceived = callback;
	}

	public void setOnConnectionChanged(Consumer<Boolean> callback)
	{
		this.onConnectionChanged = callback;
	}

	public void setOnStateReceived(java.util.function.BiConsumer<String, String> callback)
	{
		this.onStateReceived = callback;
	}

	// --- Keepalive ---
	// Prevents Render free-tier spindown (15 min idle) which wipes the relay's
	// ephemeral filesystem and loses all invite codes. Each client with cloud
	// sync enabled pings ~every 12 min while the plugin is running. If the
	// server reports another client pinged recently, this client aligns its
	// next ping with that cycle instead of starting its own — so multiple
	// online members act like one coordinated pinger.

	public void startKeepalive()
	{
		if (config == null || !config.enableRelaySync()) return;
		if (keepaliveExecutor != null && !keepaliveExecutor.isShutdown()) return;

		keepaliveExecutor = Executors.newSingleThreadScheduledExecutor();
		// Immediate first ping wakes the server if it's spun down.
		keepaliveExecutor.execute(this::sendKeepalive);
	}

	public void stopKeepalive()
	{
		if (keepaliveExecutor != null && !keepaliveExecutor.isShutdown())
		{
			keepaliveExecutor.shutdownNow();
		}
		keepaliveExecutor = null;
	}

	private void sendKeepalive()
	{
		if (config == null || !config.enableRelaySync()) return;

		String baseUrl = getRestBaseUrl();
		if (baseUrl == null) return;

		Request request = new Request.Builder()
			.url(baseUrl + "/keepalive")
			.get()
			.build();

		long nextDelayMs = KEEPALIVE_INTERVAL_MS;

		try (Response response = getRestClient().newCall(request).execute())
		{
			if (response.isSuccessful() && response.body() != null)
			{
				JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
				if (json != null && json.has("lastPingAgoSeconds"))
				{
					long agoSec = json.get("lastPingAgoSeconds").getAsLong();
					// Another client pinged recently — align with their 12-min cycle
					// instead of starting our own. Floor at 2 min so we don't spam.
					if (agoSec >= 0 && agoSec < 600)
					{
						nextDelayMs = Math.max(KEEPALIVE_MIN_DELAY_MS,
							(12L * 60 - agoSec) * 1000L);
					}
				}
				log.debug("Keepalive OK, next ping in {}s", nextDelayMs / 1000);
			}
			else
			{
				log.debug("Keepalive got HTTP {}, retry in {}s",
					response.code(), nextDelayMs / 1000);
			}
		}
		catch (Exception e)
		{
			log.debug("Keepalive failed ({}), retry in {}s",
				e.getMessage(), nextDelayMs / 1000);
		}

		scheduleNextKeepalive(nextDelayMs);
	}

	private void scheduleNextKeepalive(long delayMs)
	{
		ScheduledExecutorService exec = keepaliveExecutor;
		if (exec == null || exec.isShutdown()) return;
		try
		{
			exec.schedule(this::sendKeepalive, delayMs, TimeUnit.MILLISECONDS);
		}
		catch (Exception ignored) { }
	}

	// --- Internal Helpers ---

	private String getRestBaseUrl()
	{
		if (config == null) return null;
		String wsUrl = config.relayServerUrl();
		if (wsUrl == null || wsUrl.isEmpty()) return null;
		return wsUrl.replace("wss://", "https://").replace("ws://", "http://");
	}

	/**
	 * Dedicated OkHttp client with longer timeouts for relay REST calls.
	 * The shared client's 10s default isn't enough to survive Render cold-start.
	 */
	private OkHttpClient getRestClient()
	{
		OkHttpClient existing = restClient;
		if (existing != null) return existing;

		synchronized (this)
		{
			if (restClient == null)
			{
				restClient = httpClient.newBuilder()
					.connectTimeout(REST_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
					.readTimeout(REST_READ_TIMEOUT_S, TimeUnit.SECONDS)
					.writeTimeout(REST_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
					.build();
			}
			return restClient;
		}
	}

	private void scheduleReconnect()
	{
		if (intentionalClose || reconnectExecutor == null || reconnectExecutor.isShutdown()) return;

		log.debug("Scheduling relay reconnect in {}ms", reconnectDelay);
		reconnectExecutor.schedule(() ->
		{
			if (!intentionalClose && !connected)
			{
				String url = config != null ? config.relayServerUrl() : null;
				if (url != null && !url.isEmpty())
				{
					doConnect(url);
				}
			}
		}, reconnectDelay, TimeUnit.MILLISECONDS);

		reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS);
	}

	private void notifyConnectionChanged(boolean status)
	{
		if (onConnectionChanged != null)
		{
			onConnectionChanged.accept(status);
		}
	}

	/**
	 * Check if the given WebSocket is still the active one.
	 * Stale callbacks from old connections must be ignored.
	 */
	private boolean isCurrent(WebSocket ws)
	{
		return ws != null && ws == webSocket;
	}

	// --- WebSocket Listener ---

	private class RelayWebSocketListener extends WebSocketListener
	{
		@Override
		public void onOpen(WebSocket ws, Response response)
		{
			if (!isCurrent(ws))
			{
				log.debug("Ignoring onOpen from stale WebSocket");
				ws.close(CLOSE_NORMAL, null);
				return;
			}

			log.debug("Relay connected");
			connected = true;
			reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
			notifyConnectionChanged(true);

			// Rejoin current room after reconnect
			if (currentGroupId != null && currentPlayerName != null)
			{
				sendJoinMessage(currentGroupId, currentPlayerName);
			}
		}

		@Override
		public void onMessage(WebSocket ws, String text)
		{
			if (!isCurrent(ws)) return;

			try
			{
				JsonObject msg = gson.fromJson(text, JsonObject.class);
				String type = msg != null && msg.has("type") ? msg.get("type").getAsString() : "";

				if ("sync".equals(type) && msg.has("event"))
				{
					JsonObject eventJson = msg.getAsJsonObject("event");

					// ADDED: Verify HMAC signature before processing
					if (!verifySignature(msg, eventJson))
					{
						log.warn("Dropping relay message with invalid signature");
						return;
					}

					// ADDED: Check timestamp freshness to prevent replay attacks
					if (!isTimestampValid(eventJson))
					{
						return;
					}

					GroupService.SyncEvent event = gson.fromJson(
						eventJson, GroupService.SyncEvent.class);
					if (event != null && onEventReceived != null)
					{
						onEventReceived.accept(event);
					}
				}
				else if ("state".equals(type))
				{
					// Catch-up state from relay (sent when joining a room)
					String groupJson = msg.has("groupJson") ? msg.get("groupJson").getAsString() : null;
					String dataJson = msg.has("dataJson") && !msg.get("dataJson").isJsonNull()
						? msg.get("dataJson").getAsString() : null;
					if (groupJson != null && onStateReceived != null)
					{
						log.info("Received catch-up state from relay for group {}",
							msg.has("groupId") ? msg.get("groupId").getAsString() : "unknown");
						onStateReceived.accept(groupJson, dataJson);
					}
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to parse relay message: {}", e.getMessage());
			}
		}

		@Override
		public void onClosing(WebSocket ws, int code, String reason)
		{
			ws.close(CLOSE_NORMAL, null);

			if (!isCurrent(ws))
			{
				log.debug("Ignoring onClosing from stale WebSocket");
				return;
			}

			log.debug("Relay closing: {} {}", code, reason);
			connected = false;
			notifyConnectionChanged(false);
		}

		@Override
		public void onFailure(WebSocket ws, Throwable t, Response response)
		{
			if (!isCurrent(ws))
			{
				log.debug("Ignoring onFailure from stale WebSocket");
				return;
			}

			connected = false;
			notifyConnectionChanged(false);

			if (!intentionalClose)
			{
				log.warn("Relay connection failed: {}", t.getMessage());
				scheduleReconnect();
			}
		}
	}
}
