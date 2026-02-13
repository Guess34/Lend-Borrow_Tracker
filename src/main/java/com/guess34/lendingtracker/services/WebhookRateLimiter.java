package com.guess34.lendingtracker.services;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter for webhook calls to prevent spam and abuse.
 * Tracks per-user limits with sliding window algorithm.
 * Thread-safe for concurrent webhook attempts.
 */
@Slf4j
@Singleton
public class WebhookRateLimiter {

	// Rate limits (conservative to prevent abuse)
	private static final int MAX_CALLS_PER_MINUTE = 10;
	private static final int MAX_CALLS_PER_HOUR = 100;
	private static final int MAX_CALLS_PER_DAY = 500;
	private static final long DUPLICATE_COOLDOWN_MS = 5000; // 5 seconds between identical events

	// Per-user tracking
	private final Map<String, UserRateLimitData> userLimits = new ConcurrentHashMap<>();

	/**
	 * Check if a webhook call is allowed for a user.
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 * @param eventType The event type (for duplicate detection)
	 * @param eventData Hash/ID of event data (for duplicate detection)
	 * @return RateLimitResult with allow/deny and reason
	 */
	public RateLimitResult checkLimit(String groupId, String rsn, String eventType, String eventData) {
		if (groupId == null || rsn == null) {
			return new RateLimitResult(false, "Invalid group or RSN");
		}

		String userKey = buildUserKey(groupId, rsn);
		UserRateLimitData data = userLimits.computeIfAbsent(userKey, k -> new UserRateLimitData());

		long now = System.currentTimeMillis();

		synchronized (data) {
			// Clean old timestamps
			data.cleanOldTimestamps(now);

			// Check per-minute limit
			if (data.getCallsInLastMinute(now) >= MAX_CALLS_PER_MINUTE) {
				log.warn("Rate limit exceeded (per-minute) for {} in group {}", rsn, groupId);
				return new RateLimitResult(false, "Rate limit: Max " + MAX_CALLS_PER_MINUTE + " calls per minute");
			}

			// Check per-hour limit
			if (data.getCallsInLastHour(now) >= MAX_CALLS_PER_HOUR) {
				log.warn("Rate limit exceeded (per-hour) for {} in group {}", rsn, groupId);
				return new RateLimitResult(false, "Rate limit: Max " + MAX_CALLS_PER_HOUR + " calls per hour");
			}

			// Check per-day limit
			if (data.getCallsInLastDay(now) >= MAX_CALLS_PER_DAY) {
				log.warn("Rate limit exceeded (per-day) for {} in group {}", rsn, groupId);
				return new RateLimitResult(false, "Rate limit: Max " + MAX_CALLS_PER_DAY + " calls per day");
			}

			// Check duplicate event cooldown
			if (eventType != null && eventData != null) {
				String eventKey = eventType + ":" + eventData;
				Long lastEventTime = data.lastEventTimes.get(eventKey);

				if (lastEventTime != null && (now - lastEventTime) < DUPLICATE_COOLDOWN_MS) {
					log.debug("Duplicate event cooldown active for {} in group {}: {}", rsn, groupId, eventType);
					return new RateLimitResult(false, "Duplicate event cooldown (wait " +
						(DUPLICATE_COOLDOWN_MS - (now - lastEventTime)) / 1000 + " seconds)");
				}

				data.lastEventTimes.put(eventKey, now);
			}

			// Record this call
			data.recordCall(now);

			return new RateLimitResult(true, "OK");
		}
	}

	/**
	 * Reset rate limits for a user (admin function).
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 */
	public void resetLimits(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			return;
		}

		String userKey = buildUserKey(groupId, rsn);
		userLimits.remove(userKey);
		log.info("Reset rate limits for {} in group {}", rsn, groupId);
	}

	/**
	 * Get current usage stats for a user.
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 * @return Usage statistics
	 */
	public UsageStats getUsageStats(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			return new UsageStats(0, 0, 0);
		}

		String userKey = buildUserKey(groupId, rsn);
		UserRateLimitData data = userLimits.get(userKey);

		if (data == null) {
			return new UsageStats(0, 0, 0);
		}

		long now = System.currentTimeMillis();
		synchronized (data) {
			data.cleanOldTimestamps(now);
			return new UsageStats(
				data.getCallsInLastMinute(now),
				data.getCallsInLastHour(now),
				data.getCallsInLastDay(now)
			);
		}
	}

	/**
	 * Build a unique key for user rate limit tracking.
	 * Isolates limits per group to prevent cross-group interference.
	 */
	private String buildUserKey(String groupId, String rsn) {
		return groupId + ":" + rsn;
	}

	/**
	 * Clean up old rate limit data (call periodically).
	 */
	public void cleanup() {
		long now = System.currentTimeMillis();
		int cleaned = 0;

		Iterator<Map.Entry<String, UserRateLimitData>> iterator = userLimits.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, UserRateLimitData> entry = iterator.next();
			UserRateLimitData data = entry.getValue();

			synchronized (data) {
				data.cleanOldTimestamps(now);

				// Remove if no recent activity (older than 1 day)
				if (data.callTimestamps.isEmpty() ||
					(now - data.callTimestamps.getLast()) > 86400000) {
					iterator.remove();
					cleaned++;
				}
			}
		}

		if (cleaned > 0) {
			log.debug("Cleaned up {} inactive rate limit entries", cleaned);
		}
	}

	/**
	 * Rate limit check result.
	 */
	@Data
	public static class RateLimitResult {
		private final boolean allowed;
		private final String reason;

		public RateLimitResult(boolean allowed, String reason) {
			this.allowed = allowed;
			this.reason = reason;
		}
	}

	/**
	 * Usage statistics for a user.
	 */
	@Data
	public static class UsageStats {
		private final int callsLastMinute;
		private final int callsLastHour;
		private final int callsLastDay;

		public UsageStats(int callsLastMinute, int callsLastHour, int callsLastDay) {
			this.callsLastMinute = callsLastMinute;
			this.callsLastHour = callsLastHour;
			this.callsLastDay = callsLastDay;
		}
	}

	/**
	 * Per-user rate limit tracking data.
	 */
	private static class UserRateLimitData {
		// Sliding window of call timestamps
		private final LinkedList<Long> callTimestamps = new LinkedList<>();

		// Track last time for each event type (for duplicate detection)
		private final Map<String, Long> lastEventTimes = new HashMap<>();

		/**
		 * Record a webhook call.
		 */
		void recordCall(long timestamp) {
			callTimestamps.add(timestamp);
		}

		/**
		 * Get number of calls in last minute.
		 */
		int getCallsInLastMinute(long now) {
			long oneMinuteAgo = now - 60000;
			return (int) callTimestamps.stream()
				.filter(ts -> ts >= oneMinuteAgo)
				.count();
		}

		/**
		 * Get number of calls in last hour.
		 */
		int getCallsInLastHour(long now) {
			long oneHourAgo = now - 3600000;
			return (int) callTimestamps.stream()
				.filter(ts -> ts >= oneHourAgo)
				.count();
		}

		/**
		 * Get number of calls in last day.
		 */
		int getCallsInLastDay(long now) {
			long oneDayAgo = now - 86400000;
			return (int) callTimestamps.stream()
				.filter(ts -> ts >= oneDayAgo)
				.count();
		}

		/**
		 * Remove timestamps older than 24 hours.
		 */
		void cleanOldTimestamps(long now) {
			long oneDayAgo = now - 86400000;
			callTimestamps.removeIf(ts -> ts < oneDayAgo);

			// Clean old event times too
			lastEventTimes.entrySet().removeIf(entry ->
				(now - entry.getValue()) > 86400000);
		}
	}
}
