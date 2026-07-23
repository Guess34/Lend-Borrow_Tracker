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
	// Max age for sync messages, a coarse replay/ancient-message bound. Kept
	// generous (24h) because timestamps are the sender's wall clock: a tight
	// window silently dropped ALL live sync between members whose clocks differed
	// by more than a few minutes. Fine-grained replay of state is additionally
	// blocked by the per-publisher monotonic timestamp gate below.
	private static final long MAX_MESSAGE_AGE_MS = 24L * 60 * 60 * 1000;

	// Newest state timestamp APPLIED per "groupId:publisher". A state strictly older
	// than this is dropped as a replay — the timestamp is inside the signed payload,
	// so a captured old signed snapshot can't be re-injected to revert a publisher's
	// marketplace/lent rows (applyPublisherRows adopts the publisher's rows
	// wholesale, so it has no per-row recency of its own).
	private final java.util.concurrent.ConcurrentHashMap<String, Long> lastStateTs =
		new java.util.concurrent.ConcurrentHashMap<>();
	// Content hash of the last state applied per "groupId:publisher", so an IDENTICAL
	// repeat (the re-announce burst when a member joins, or the periodic heartbeat)
	// is skipped without reprocessing. Recorded only AFTER a best-effort apply so a
	// failed apply doesn't permanently dedup the publisher's retry.
	private final java.util.concurrent.ConcurrentHashMap<String, Integer> lastStateHash =
		new java.util.concurrent.ConcurrentHashMap<>();
	// Keepalive cadence: ping ~every 12 min, never sooner than 2 min apart
	private static final long KEEPALIVE_INTERVAL_MS = 12 * 60 * 1000;
	private static final long KEEPALIVE_MIN_DELAY_MS = 2 * 60 * 1000;
	// Websocket ping cadence. Keeps the relay socket warm (so it isn't idle-closed)
	// and lets OkHttp detect a dead socket and fail over to a reconnect.
	private static final long WS_PING_INTERVAL_S = 25;
	// Render free tier takes 30-60s to wake from spindown — shared OkHttpClient's
	// default 10s timeout would give up before the server responds, so the
	// REST client used for relay calls needs its own longer timeouts.
	private static final long REST_CONNECT_TIMEOUT_S = 30;
	private static final long REST_READ_TIMEOUT_S = 60;

	@Inject private OkHttpClient httpClient;
	@Inject private Gson gson;
	@Inject private LendingTrackerConfig config;

	// Serializes every transition of webSocket / connected / intentionalClose /
	// reconnectExecutor / reconnectDelay. These are touched from the EDT
	// (connect/disconnect), the OkHttp ws-callback thread (onOpen/onClosing/
	// onFailure), and the reconnect executor thread; without a single lock they
	// race — e.g. a ws-thread close could null a socket a concurrent reconnect just
	// created, or a reconnect could resurrect a socket after an intentional close.
	private final Object connLock = new Object();
	private volatile WebSocket webSocket;
	// The one scheduled reconnect attempt, if any. Guarded by connLock. Cancelled
	// when connect() opens a socket directly so a stale timer can't spawn a second
	// concurrent connection attempt.
	private java.util.concurrent.ScheduledFuture<?> pendingReconnect;
	// Room identity: written by joinRoom/leaveRoom/disconnect (EDT or sync executor)
	// and read on the ws thread (onOpen rejoin, signature verification) — volatile
	// so a join is immediately visible to a concurrently-opening socket.
	private volatile String currentGroupId;
	private volatile String currentPlayerName;
	// Current group's sync secret for HMAC signing
	private volatile String currentSyncSecret;
	private volatile boolean connected = false;
	private volatile boolean intentionalClose = false;
	private long reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
	private ScheduledExecutorService reconnectExecutor;
	private ScheduledExecutorService keepaliveExecutor;
	private volatile OkHttpClient restClient;
	private volatile OkHttpClient wsClient;
	private Consumer<GroupService.SyncEvent> onEventReceived;
	private Consumer<Boolean> onConnectionChanged;
	private StateHandler onStateReceived;
	private volatile Runnable onConnected;
	private volatile Consumer<java.util.Map<String, Integer>> onPresenceReceived;
	// Supplies the local player's current world so the relay can report it to peers
	// as part of presence. Read lazily at each join so a world hop (which triggers a
	// reconnect + rejoin) reports the fresh world.
	private volatile java.util.function.IntSupplier localWorldSupplier;

	/** Callback for relay state messages (join catch-up or live broadcast). */
	@FunctionalInterface
	public interface StateHandler
	{
		void accept(String groupJson, String dataJson, String publisher);
	}

	// --- Connection Lifecycle ---

	public void connect()
	{
		if (config == null || !config.enableRelaySync()) return;

		String url = config.relayServerUrl();
		if (url == null || url.isEmpty()) return;

		synchronized (connLock)
		{
			// A non-null socket means connected OR a handshake already in flight
			// (onClosing/onFailure/disconnect all null the field under this lock).
			// Skipping the in-flight case matters: at login two triggers call
			// startSync back-to-back, and connecting again during the first
			// attempt's handshake would orphan a socket and churn the relay.
			if (webSocket != null)
			{
				log.debug("Relay already connected/connecting, skipping connect");
				return;
			}

			intentionalClose = false;
			reconnectDelay = INITIAL_RECONNECT_DELAY_MS;

			// We're connecting right now — a previously scheduled retry would only
			// race this attempt and orphan one of the two sockets.
			if (pendingReconnect != null)
			{
				pendingReconnect.cancel(false);
				pendingReconnect = null;
			}

			if (reconnectExecutor == null || reconnectExecutor.isShutdown())
			{
				reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
			}

			doConnectLocked(url);
		}
	}

	/** Open a websocket. MUST be called while holding {@link #connLock}. */
	private void doConnectLocked(String url)
	{
		if (config == null || !config.enableRelaySync()) return;
		// Don't resurrect a socket after an intentional close (a concurrent
		// disconnect() may have set this while a reconnect task was queued).
		if (intentionalClose) return;

		try
		{
			log.debug("Connecting to relay: {}", url);
			Request request = new Request.Builder().url(url).build();
			webSocket = getWsClient().newWebSocket(request, new RelayWebSocketListener());
		}
		catch (Exception e)
		{
			log.warn("Failed to connect to relay: {}", e.getMessage());
			scheduleReconnectLocked();
		}
	}

	public void disconnect()
	{
		WebSocket ws;
		synchronized (connLock)
		{
			intentionalClose = true;
			connected = false;

			// Null the field so any in-flight/stale callback sees ws != webSocket.
			ws = webSocket;
			webSocket = null;
			// Clear the room AND its secret so a socket that somehow (re)opens can't
			// rejoin the group we just left, and a late response for the old group
			// (e.g. an in-flight catch-up fetch) fails signature verification instead
			// of being applied after we've moved on.
			currentGroupId = null;
			currentSyncSecret = null;

			pendingReconnect = null; // shutdownNow below cancels the task itself

			if (reconnectExecutor != null && !reconnectExecutor.isShutdown())
			{
				reconnectExecutor.shutdownNow();
				reconnectExecutor = null;
			}
		}

		// Full teardown (logout / group switch) — the per-publisher dedup floors
		// belong to the room we just left; drop them so they can't grow unbounded
		// across group switches. (The auto-reconnect path never comes through here,
		// so a mid-session reconnect keeps its replay floors.)
		lastStateTs.clear();
		lastStateHash.clear();

		if (ws != null)
		{
			try
			{
				ws.close(CLOSE_NORMAL, "Plugin shutdown");
			}
			catch (Exception ignored) { }
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

		// Under connLock so this serializes with onOpen: without it, joinRoom could
		// set the room just after onOpen read a null room, while onOpen flips
		// connected just after we read false — neither side sends the join and the
		// socket sits connected but never in the room (no presence in or out).
		synchronized (connLock)
		{
			this.currentGroupId = groupId;
			this.currentPlayerName = playerName;
			this.currentSyncSecret = syncSecret;

			if (connected && webSocket != null)
			{
				sendJoinMessage(groupId, playerName);
			}
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
		// Report our current world so peers can show it next to our name. Read lazily
		// here so a reconnect after a world hop reports the new world.
		int world = 0;
		try
		{
			java.util.function.IntSupplier supplier = localWorldSupplier;
			if (supplier != null) { world = supplier.getAsInt(); }
		}
		catch (Exception ignored) { /* world stays 0 */ }
		msg.addProperty("world", world);
		ws.send(gson.toJson(msg));
	}

	public void leaveRoom(String groupId)
	{
		if (config == null || !config.enableRelaySync()) return;

		// Under connLock like joinRoom: an unlocked leave could race onOpen's
		// rejoin and re-enter the room being left. ws.send is a non-blocking
		// enqueue, safe under the lock.
		synchronized (connLock)
		{
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
	}

	// --- Sync Events ---

	/**
	 * Send a sync event to the relay server.
	 * CHANGED: Now signs the event payload with HMAC-SHA256 using the group's sync secret.
	 */
	public void sendEvent(String groupId, GroupService.SyncEvent event)
	{
		if (config == null || !config.enableRelaySync()) return;
		// Cache the volatile field — see leaveRoom for why.
		WebSocket ws = webSocket;
		if (!connected || ws == null || groupId == null) return;

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

		ws.send(gson.toJson(msg));
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
		String publisher = eventJson.has("publisher") && !eventJson.get("publisher").isJsonNull()
			? eventJson.get("publisher").getAsString() : "";
		// dataId drives targeted mutations (e.g. archiving a loan by id), so it must
		// be signed too — otherwise a tampered id would pass verification.
		String dataId = eventJson.has("dataId") && !eventJson.get("dataId").isJsonNull()
			? eventJson.get("dataId").getAsString() : "";
		return groupId + ":" + type + ":" + timestamp + ":" + publisher + ":" + dataId;
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

	// --- Group State (catch-up sync) ---

	/**
	 * Push the latest group + data state to the relay. The relay stores it for
	 * catch-up (served by GET /api/state) AND broadcasts it live to other members.
	 *
	 * The live broadcast is HMAC-signed with the group secret so peers can verify
	 * it really came from a member before applying it — without this a client that
	 * only knows the groupId could push a forged state and wipe everyone's data.
	 *
	 * @param publisher this client's player name; receivers treat the publisher as
	 *                  authoritative for their own rows when merging the snapshot
	 */
	public void publishState(String groupId, String groupJson, String dataJson, String publisher)
	{
		if (config == null || !config.enableRelaySync()) return;
		// Cache the volatile field — see leaveRoom for why.
		WebSocket ws = webSocket;
		if (!connected || ws == null || groupId == null) return;

		long timestamp = System.currentTimeMillis();

		JsonObject msg = new JsonObject();
		msg.addProperty("type", "state");
		msg.addProperty("groupId", groupId);
		msg.addProperty("groupJson", groupJson);
		if (dataJson != null)
		{
			msg.addProperty("dataJson", dataJson);
		}
		if (publisher != null)
		{
			msg.addProperty("publisher", publisher);
		}
		msg.addProperty("timestamp", timestamp);

		if (currentSyncSecret != null && !currentSyncSecret.isEmpty())
		{
			String signature = computeHmac(
				buildStateSignaturePayload(groupId, publisher, timestamp, groupJson, dataJson),
				currentSyncSecret);
			if (signature != null)
			{
				msg.addProperty("signature", signature);
			}
		}

		ws.send(gson.toJson(msg));
		log.debug("Published group state to relay for group {}", groupId);
	}

	/**
	 * Fetch the stored catch-up snapshot for a group over REST and hand it to the
	 * state handler with a null publisher (authoritative full-state catch-up).
	 * This is a request the client initiates to the configured relay, so the
	 * response is trusted without a per-message signature. Blocking — run off the EDT.
	 *
	 * @return true when the fetch completed (snapshot applied, or the relay
	 *         definitively has no/invalid state for this group — nothing to retry);
	 *         false on a transport failure (timeout, cold-start, non-2xx) that the
	 *         caller should retry with backoff.
	 */
	public boolean fetchStateSnapshot(String groupId)
	{
		if (config == null || !config.enableRelaySync() || groupId == null) return true;

		String baseUrl = getRestBaseUrl();
		if (baseUrl == null) return true;

		Request request = new Request.Builder()
			.url(baseUrl + "/api/state/" + groupId)
			.get()
			.build();

		try (Response response = getRestClient().newCall(request).execute())
		{
			if (response.code() == 404)
			{
				// Relay answered: it has no stored state for this group (new group,
				// or storage wiped). Nothing to catch up on — don't retry.
				return true;
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				return false;
			}
			JsonObject json = gson.fromJson(response.body().string(), JsonObject.class);
			if (json == null) return true;

			// Verify the stored snapshot was signed by a member holding the group
			// secret. The relay stores whatever it's sent (and anyone who knows the
			// groupId could try to seed a forged snapshot), so we must not apply an
			// unsigned or tampered catch-up. Freshness is NOT enforced here — stored
			// catch-up state is legitimately old — but the signature and the merge's
			// last-write-wins still prevent forgery and stale-data damage.
			JsonObject stateMsg = new JsonObject();
			stateMsg.addProperty("groupId", groupId);
			copyIfPresent(json, stateMsg, "groupJson");
			copyIfPresent(json, stateMsg, "dataJson");
			copyIfPresent(json, stateMsg, "publisher");
			copyIfPresent(json, stateMsg, "timestamp");
			copyIfPresent(json, stateMsg, "signature");
			if (!verifyStateSignature(stateMsg))
			{
				// A snapshot the relay HAS but we can't verify won't get better on
				// retry (e.g. stored by an old client before signing was rolled out).
				log.warn("Dropping catch-up state for group {}: invalid or missing signature", groupId);
				return true;
			}

			String groupJson = json.has("groupJson") && !json.get("groupJson").isJsonNull()
				? json.get("groupJson").getAsString() : null;
			String dataJson = json.has("dataJson") && !json.get("dataJson").isJsonNull()
				? json.get("dataJson").getAsString() : null;
			if (groupJson != null && onStateReceived != null)
			{
				onStateReceived.accept(groupJson, dataJson, null);

				// The catch-up snapshot can be marginally older than a live state we
				// already applied (it was read from the store before that push), and
				// applying it can briefly revert a peer's rows. Drop the per-publisher
				// content hashes so the peer's next identical heartbeat/announce is
				// NOT deduped and re-applies their newer state — otherwise the revert
				// would stick until their content actually changed.
				String prefix = groupId + ":";
				lastStateHash.keySet().removeIf(k -> k.startsWith(prefix));
			}
			return true;
		}
		catch (Exception e)
		{
			log.warn("Failed to fetch catch-up state from relay: {}", e.getMessage());
			return false;
		}
	}

	private void copyIfPresent(JsonObject from, JsonObject to, String key)
	{
		if (from.has(key) && !from.get(key).isJsonNull())
		{
			to.add(key, from.get(key));
		}
	}

	/**
	 * Canonical string signed for a state message. Includes the full group and
	 * data JSON so neither can be tampered with in transit.
	 */
	private String buildStateSignaturePayload(String groupId, String publisher, long timestamp,
		String groupJson, String dataJson)
	{
		return groupId + ":" + (publisher == null ? "" : publisher) + ":" + timestamp
			+ ":" + (groupJson == null ? "" : groupJson)
			+ ":" + (dataJson == null ? "" : dataJson);
	}

	/**
	 * Verify the HMAC signature on an incoming live state broadcast.
	 */
	private boolean verifyStateSignature(JsonObject msg)
	{
		if (currentSyncSecret == null || currentSyncSecret.isEmpty())
		{
			log.warn("Rejecting relay state: no sync secret configured for current group");
			return false;
		}
		if (!msg.has("signature") || msg.get("signature").isJsonNull())
		{
			log.warn("Rejecting relay state: missing HMAC signature");
			return false;
		}

		String groupId = msg.has("groupId") ? msg.get("groupId").getAsString() : "";
		String publisher = msg.has("publisher") && !msg.get("publisher").isJsonNull()
			? msg.get("publisher").getAsString() : null;
		long timestamp = msg.has("timestamp") && !msg.get("timestamp").isJsonNull()
			? msg.get("timestamp").getAsLong() : 0;
		String groupJson = msg.has("groupJson") && !msg.get("groupJson").isJsonNull()
			? msg.get("groupJson").getAsString() : null;
		String dataJson = msg.has("dataJson") && !msg.get("dataJson").isJsonNull()
			? msg.get("dataJson").getAsString() : null;

		String expected = computeHmac(
			buildStateSignaturePayload(groupId, publisher, timestamp, groupJson, dataJson),
			currentSyncSecret);
		if (expected == null) return false;
		return constantTimeEquals(expected, msg.get("signature").getAsString());
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

	public void setOnStateReceived(StateHandler callback)
	{
		this.onStateReceived = callback;
	}

	/**
	 * Called on the websocket thread right after we (re)connect and rejoin our
	 * room. Used to announce our current state immediately so other members see
	 * us and our roster without waiting for the periodic push.
	 */
	public void setOnConnected(Runnable callback)
	{
		this.onConnected = callback;
	}

	/**
	 * Called with the authoritative set of players currently connected to our room
	 * (lower-cased name -> world, world 0 if unknown), whenever the relay broadcasts
	 * presence. This is the source of truth for who is online — no friends-list
	 * relationship required.
	 */
	public void setOnPresenceReceived(Consumer<java.util.Map<String, Integer>> callback)
	{
		this.onPresenceReceived = callback;
	}

	/** Supplies the local player's current world for presence reporting. */
	public void setLocalWorldSupplier(java.util.function.IntSupplier supplier)
	{
		this.localWorldSupplier = supplier;
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

	/**
	 * Websocket client with an application-level ping interval. OkHttp sends WS ping
	 * frames on this cadence, which (a) keeps the connection warm so the relay/proxy
	 * doesn't idle it out, and (b) surfaces a half-open/dead socket as an onFailure
	 * — the path that actually reconnects. The REST /keepalive pinger only warms the
	 * HTTP dyno; it sends no WS frames, so without this the socket still goes stale.
	 */
	private OkHttpClient getWsClient()
	{
		OkHttpClient existing = wsClient;
		if (existing != null) return existing;

		synchronized (this)
		{
			if (wsClient == null)
			{
				wsClient = httpClient.newBuilder()
					.pingInterval(WS_PING_INTERVAL_S, TimeUnit.SECONDS)
					.build();
			}
			return wsClient;
		}
	}

	private void scheduleReconnect()
	{
		synchronized (connLock)
		{
			scheduleReconnectLocked();
		}
	}

	/** Schedule a reconnect. MUST be called while holding {@link #connLock}. */
	private void scheduleReconnectLocked()
	{
		// Cache the executor into a local: reading the field twice lets a concurrent
		// disconnect() null it between the guard and the schedule() call (NPE), or
		// shut it down (RejectedExecutionException).
		ScheduledExecutorService exec = reconnectExecutor;
		if (intentionalClose || exec == null || exec.isShutdown()) return;

		final long delay = reconnectDelay;
		log.debug("Scheduling relay reconnect in {}ms", delay);
		try
		{
			pendingReconnect = exec.schedule(() ->
			{
				synchronized (connLock)
				{
					pendingReconnect = null;
					if (!intentionalClose && !connected)
					{
						String url = config != null ? config.relayServerUrl() : null;
						if (url != null && !url.isEmpty())
						{
							doConnectLocked(url);
						}
					}
				}
			}, delay, TimeUnit.MILLISECONDS);

			reconnectDelay = Math.min(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS);
		}
		catch (java.util.concurrent.RejectedExecutionException ignored)
		{
			// Executor was shut down concurrently (teardown in progress) — nothing to do.
		}
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
			synchronized (connLock)
			{
				// Ignore a socket that's no longer current OR one that opened after we
				// intentionally closed (a reconnect that raced a disconnect): close it.
				if (!isCurrent(ws) || intentionalClose)
				{
					log.debug("Ignoring onOpen from stale/closed WebSocket");
					ws.close(CLOSE_NORMAL, null);
					return;
				}

				log.debug("Relay connected");

				// Enqueue the room join BEFORE flipping connected: any other thread
				// that observes connected==true and sends (event/state push) will
				// enqueue AFTER the join, so the server always sees us join the room
				// before our first message to it. ws.send is a non-blocking enqueue,
				// safe under the lock.
				String gid = currentGroupId;
				String pn = currentPlayerName;
				if (gid != null && pn != null)
				{
					sendJoinMessage(gid, pn);
				}

				connected = true;
				reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
			}

			notifyConnectionChanged(true);

			// Announce our state immediately so other members see us (and our
			// roster) the moment we connect, instead of waiting for the periodic
			// 5-minute push. Fires on first join AND on reconnect. Also flushes any
			// changes made while we were briefly disconnected.
			Runnable oc = onConnected;
			if (oc != null)
			{
				oc.run();
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
					// Live state broadcast pushed when another member's data changed.
					// (Join catch-up is fetched over REST via fetchStateSnapshot, not here.)
					// Verify the HMAC and freshness before applying — an unsigned or
					// forged push must never be able to overwrite local data.
					if (!verifyStateSignature(msg))
					{
						log.warn("Dropping relay state with invalid signature");
						return;
					}
					if (!isTimestampValid(msg))
					{
						return;
					}

					String groupJson = msg.has("groupJson") ? msg.get("groupJson").getAsString() : null;
					String dataJson = msg.has("dataJson") && !msg.get("dataJson").isJsonNull()
						? msg.get("dataJson").getAsString() : null;
					String publisher = msg.has("publisher") && !msg.get("publisher").isJsonNull()
						? msg.get("publisher").getAsString() : null;
					String groupId = msg.has("groupId") ? msg.get("groupId").getAsString() : null;
					long timestamp = msg.has("timestamp") && !msg.get("timestamp").isJsonNull()
						? msg.get("timestamp").getAsLong() : 0;

					String key = (groupId != null && publisher != null) ? groupId + ":" + publisher : null;
					int hash = java.util.Objects.hash(groupJson, dataJson);
					if (key != null)
					{
						// Replay guard: drop a state strictly OLDER than the newest we've
						// applied from this publisher (signed timestamp, can't be forged).
						Long prevTs = lastStateTs.get(key);
						if (prevTs != null && timestamp < prevTs)
						{
							log.debug("Dropping stale relay state from {} ({} < {})", publisher, timestamp, prevTs);
							return;
						}
						// Throttle: skip an identical repeat (re-announce / heartbeat).
						Integer prevHash = lastStateHash.get(key);
						if (prevHash != null && prevHash == hash)
						{
							return;
						}
					}

					if (groupJson != null && publisher != null && onStateReceived != null)
					{
						log.debug("Received live state from relay for group {} (publisher: {})",
							groupId != null ? groupId : "unknown", publisher);
						onStateReceived.accept(groupJson, dataJson, publisher);
						// Record AFTER apply so a failed apply doesn't dedup the retry.
						if (key != null)
						{
							Long prevTs = lastStateTs.get(key);
							long floor = Math.max(timestamp, prevTs != null ? prevTs : timestamp);
							// Clamp the replay floor to OUR clock (+1 min): a publisher
							// whose clock was ahead would otherwise latch a future floor
							// and have every later legitimate push dropped for the whole
							// session. Old replays stay blocked; a future-latch self-heals
							// within a minute of receiver time.
							lastStateTs.put(key, Math.min(floor, System.currentTimeMillis() + 60_000L));
							lastStateHash.put(key, hash);
						}
					}
				}
				else if ("presence".equals(type))
				{
					// Authoritative online list for the room: everyone with an open ws
					// here, independent of friends chat / friends list. No signature is
					// needed — presence carries no group data, only who is connected, and
					// the relay is the sole authority on its own socket set.
					// Guard: only apply presence for the room we're currently in (a
					// stale-socket broadcast for a previous group must not leak through).
					String presenceGroup = msg.has("groupId") && !msg.get("groupId").isJsonNull()
						? msg.get("groupId").getAsString() : null;
					String activeGroup = currentGroupId;
					if (presenceGroup != null && activeGroup != null && !presenceGroup.equals(activeGroup))
					{
						return;
					}
					java.util.Map<String, Integer> present = new java.util.HashMap<>();
					if (msg.has("players") && msg.get("players").isJsonArray())
					{
						for (com.google.gson.JsonElement el : msg.getAsJsonArray("players"))
						{
							if (el == null || !el.isJsonObject()) continue;
							JsonObject p = el.getAsJsonObject();
							if (!p.has("name") || p.get("name").isJsonNull()) continue;
							String name = p.get("name").getAsString();
							if (name.isEmpty()) continue;
							int w = p.has("world") && !p.get("world").isJsonNull() ? p.get("world").getAsInt() : 0;
							present.put(name.toLowerCase(), w);
						}
					}
					Consumer<java.util.Map<String, Integer>> cb = onPresenceReceived;
					if (cb != null) { cb.accept(present); }
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

			boolean doReconnect = false;
			synchronized (connLock)
			{
				if (!isCurrent(ws))
				{
					log.debug("Ignoring onClosing from stale WebSocket");
					return;
				}

				log.debug("Relay closing: {} {}", code, reason);
				connected = false;

				// A GRACEFUL close from the server/proxy (Render free-tier idle timeout,
				// dyno cycle, load-balancer idle cutoff) lands here — NOT in onFailure.
				// Previously we stopped at notify and the socket stayed dead until the
				// next startSync, so live sync AND presence silently halted until a
				// RuneLite restart. Treat it like any other drop and reconnect, unless
				// we closed it ourselves. isCurrent(ws) confirmed ws == webSocket while
				// holding the lock, so this null only clears THIS socket.
				if (!intentionalClose)
				{
					webSocket = null;
					doReconnect = true;
				}
			}

			notifyConnectionChanged(false);
			if (doReconnect)
			{
				log.debug("Relay closed by server; scheduling reconnect");
				scheduleReconnect();
			}
		}

		@Override
		public void onFailure(WebSocket ws, Throwable t, Response response)
		{
			boolean doReconnect = false;
			synchronized (connLock)
			{
				if (!isCurrent(ws))
				{
					log.debug("Ignoring onFailure from stale WebSocket");
					return;
				}

				connected = false;
				if (!intentionalClose)
				{
					webSocket = null;
					doReconnect = true;
				}
			}

			notifyConnectionChanged(false);
			if (doReconnect)
			{
				log.warn("Relay connection failed: {}", t.getMessage());
				scheduleReconnect();
			}
		}
	}
}
