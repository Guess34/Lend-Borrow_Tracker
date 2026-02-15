package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Audit logger for all webhook attempts.
 * Tracks successful and failed webhook calls for security and debugging.
 * Provides accountability and helps detect abuse patterns.
 */
@Slf4j
@Singleton
public class WebhookAuditLogger {

	private static final String CONFIG_KEY_AUDIT_LOG = "webhook_audit_log";
	private static final int MAX_LOG_ENTRIES = 1000; // Keep last 1000 entries

	@Inject
	private ConfigManager configManager;
	@Inject
	private Gson gson;
	private final List<AuditEntry> auditLog = Collections.synchronizedList(new ArrayList<>());

	/**
	 * Log a webhook attempt.
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 * @param eventType The event type (lend, return, overdue, etc.)
	 * @param success Whether the webhook was sent successfully
	 * @param failureReason Reason for failure (null if success)
	 * @param itemInfo Brief item info for context
	 */
	public void logAttempt(String groupId, String rsn, String eventType, boolean success,
	                       String failureReason, String itemInfo) {
		AuditEntry entry = new AuditEntry(
			System.currentTimeMillis(),
			groupId,
			rsn,
			eventType,
			success,
			failureReason,
			itemInfo
		);

		synchronized (auditLog) {
			auditLog.add(entry);

			// Trim old entries if exceeding max
			if (auditLog.size() > MAX_LOG_ENTRIES) {
				auditLog.remove(0);
			}
		}

		// Log to console
		if (success) {
			log.info("Webhook sent successfully: {} - {} - {} - {}", rsn, groupId, eventType, itemInfo);
		} else {
			log.warn("Webhook failed: {} - {} - {} - Reason: {}", rsn, groupId, eventType, failureReason);
		}

		// Persist to config (async to avoid blocking)
		saveAuditLog();
	}

	/**
	 * Get audit entries for a specific user.
	 * @param groupId The group ID (null for all groups)
	 * @param rsn The user's RSN (null for all users)
	 * @param maxEntries Maximum entries to return
	 * @return List of audit entries
	 */
	public List<AuditEntry> getEntries(String groupId, String rsn, int maxEntries) {
		synchronized (auditLog) {
			return auditLog.stream()
				.filter(e -> groupId == null || e.groupId.equals(groupId))
				.filter(e -> rsn == null || e.rsn.equalsIgnoreCase(rsn))
				.sorted(Comparator.comparingLong(AuditEntry::getTimestamp).reversed())
				.limit(maxEntries)
				.collect(Collectors.toList());
		}
	}

	/**
	 * Get all recent failed attempts (for detecting abuse).
	 * @param sinceTimestamp Only get failures since this timestamp
	 * @return List of failed audit entries
	 */
	public List<AuditEntry> getFailedAttempts(long sinceTimestamp) {
		synchronized (auditLog) {
			return auditLog.stream()
				.filter(e -> !e.success && e.timestamp >= sinceTimestamp)
				.sorted(Comparator.comparingLong(AuditEntry::getTimestamp).reversed())
				.collect(Collectors.toList());
		}
	}

	/**
	 * Get statistics for a user.
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 * @return Usage statistics
	 */
	public AuditStats getStats(String groupId, String rsn) {
		List<AuditEntry> userEntries = getEntries(groupId, rsn, MAX_LOG_ENTRIES);

		long totalAttempts = userEntries.size();
		long successfulAttempts = userEntries.stream().filter(e -> e.success).count();
		long failedAttempts = totalAttempts - successfulAttempts;

		// Get last attempt time
		long lastAttemptTime = userEntries.isEmpty() ? 0 :
			userEntries.stream()
				.mapToLong(AuditEntry::getTimestamp)
				.max()
				.orElse(0);

		// Count by event type
		Map<String, Long> eventTypeCounts = userEntries.stream()
			.collect(Collectors.groupingBy(
				AuditEntry::getEventType,
				Collectors.counting()
			));

		return new AuditStats(
			totalAttempts,
			successfulAttempts,
			failedAttempts,
			lastAttemptTime,
			eventTypeCounts
		);
	}

	/**
	 * Detect suspicious activity patterns.
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 * @return List of detected issues
	 */
	public List<String> detectSuspiciousActivity(String groupId, String rsn) {
		List<String> issues = new ArrayList<>();
		List<AuditEntry> recentEntries = getEntries(groupId, rsn, 100);

		if (recentEntries.isEmpty()) {
			return issues;
		}

		// Check for high failure rate
		long failures = recentEntries.stream().filter(e -> !e.success).count();
		double failureRate = (double) failures / recentEntries.size();

		if (failureRate > 0.5 && recentEntries.size() > 10) {
			issues.add("High failure rate (" + String.format("%.1f%%", failureRate * 100) + ")");
		}

		// Check for rapid-fire attempts (more than 5 in last minute)
		long oneMinuteAgo = System.currentTimeMillis() - 60000;
		long recentCount = recentEntries.stream()
			.filter(e -> e.timestamp >= oneMinuteAgo)
			.count();

		if (recentCount > 5) {
			issues.add("Rapid-fire attempts (" + recentCount + " in last minute)");
		}

		// Check for repeated failures with same reason
		Map<String, Long> failureReasons = recentEntries.stream()
			.filter(e -> !e.success && e.failureReason != null)
			.collect(Collectors.groupingBy(
				AuditEntry::getFailureReason,
				Collectors.counting()
			));

		failureReasons.forEach((reason, count) -> {
			if (count > 10) {
				issues.add("Repeated failures: " + reason + " (" + count + " times)");
			}
		});

		return issues;
	}

	/**
	 * Clear audit log for a specific user (admin function).
	 * @param groupId The group ID
	 * @param rsn The user's RSN
	 */
	public void clearUserLog(String groupId, String rsn) {
		synchronized (auditLog) {
			auditLog.removeIf(e -> e.groupId.equals(groupId) && e.rsn.equalsIgnoreCase(rsn));
		}
		saveAuditLog();
		log.info("Cleared audit log for {} in group {}", rsn, groupId);
	}

	/**
	 * Clear all old entries (older than specified days).
	 * @param daysToKeep Number of days to keep
	 */
	public void clearOldEntries(int daysToKeep) {
		long cutoffTime = System.currentTimeMillis() - (daysToKeep * 86400000L);

		synchronized (auditLog) {
			int sizeBefore = auditLog.size();
			auditLog.removeIf(e -> e.timestamp < cutoffTime);
			int removed = sizeBefore - auditLog.size();

			if (removed > 0) {
				log.info("Cleared {} old audit entries (older than {} days)", removed, daysToKeep);
			}
		}

		saveAuditLog();
	}

	/**
	 * Save audit log to config storage.
	 */
	private void saveAuditLog() {
		try {
			synchronized (auditLog) {
				String jsonLog = gson.toJson(auditLog);
				if (configManager != null) {
					configManager.setConfiguration("lendingtracker", CONFIG_KEY_AUDIT_LOG, jsonLog);
				}
			}
		} catch (Exception e) {
			log.error("Failed to save webhook audit log", e);
		}
	}

	/**
	 * Load audit log from config storage.
	 */
	public void loadAuditLog() {
		try {
			if (configManager == null) {
				log.warn("ConfigManager not initialized, cannot load audit log");
				return;
			}

			String jsonLog = configManager.getConfiguration("lendingtracker", CONFIG_KEY_AUDIT_LOG);
			if (jsonLog != null && !jsonLog.isEmpty()) {
				AuditEntry[] entries = gson.fromJson(jsonLog, AuditEntry[].class);
				if (entries != null) {
					synchronized (auditLog) {
						auditLog.clear();
						auditLog.addAll(Arrays.asList(entries));
					}
					log.info("Loaded {} webhook audit entries", entries.length);
				}
			}
		} catch (Exception e) {
			log.error("Failed to load webhook audit log", e);
		}
	}

	/**
	 * Audit entry data class.
	 */
	@Data
	public static class AuditEntry {
		private long timestamp;
		private String groupId;
		private String rsn;
		private String eventType;
		private boolean success;
		private String failureReason;
		private String itemInfo;

		public AuditEntry(long timestamp, String groupId, String rsn, String eventType,
		                  boolean success, String failureReason, String itemInfo) {
			this.timestamp = timestamp;
			this.groupId = groupId;
			this.rsn = rsn;
			this.eventType = eventType;
			this.success = success;
			this.failureReason = failureReason;
			this.itemInfo = itemInfo;
		}

		public String getFormattedTimestamp() {
			return Instant.ofEpochMilli(timestamp).toString();
		}
	}

	/**
	 * Audit statistics data class.
	 */
	@Data
	public static class AuditStats {
		private long totalAttempts;
		private long successfulAttempts;
		private long failedAttempts;
		private long lastAttemptTime;
		private Map<String, Long> eventTypeCounts;

		public AuditStats(long totalAttempts, long successfulAttempts, long failedAttempts,
		                  long lastAttemptTime, Map<String, Long> eventTypeCounts) {
			this.totalAttempts = totalAttempts;
			this.successfulAttempts = successfulAttempts;
			this.failedAttempts = failedAttempts;
			this.lastAttemptTime = lastAttemptTime;
			this.eventTypeCounts = eventTypeCounts;
		}

		public double getSuccessRate() {
			return totalAttempts == 0 ? 0 : (double) successfulAttempts / totalAttempts * 100;
		}
	}
}
