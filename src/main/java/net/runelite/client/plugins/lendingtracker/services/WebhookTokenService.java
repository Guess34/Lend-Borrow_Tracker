package net.runelite.client.plugins.lendingtracker.services;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Manages secure tokens for Discord webhook authentication.
 * Each user gets a unique token per group to prevent unauthorized webhook usage.
 * Tokens are cryptographically secure and single-use (show once, then hidden).
 */
@Slf4j
@Singleton
public class WebhookTokenService {

	private static final String CONFIG_PREFIX = "webhook_token_";
	private static final String CONFIG_TOKEN_SHOWN = "webhook_token_shown_";
	private static final int TOKEN_LENGTH = 32; // 256 bits of entropy

	@Inject
	private ConfigManager configManager;
	private final SecureRandom secureRandom;

	public WebhookTokenService() {
		this.secureRandom = new SecureRandom();
	}

	/**
	 * Get or generate a token for a specific group.
	 * @param groupId The group ID
	 * @param rsn The player's RuneScape name
	 * @return The token (never null)
	 */
	public String getOrCreateToken(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			throw new IllegalArgumentException("Group ID and RSN cannot be null");
		}

		String configKey = buildConfigKey(groupId, rsn);
		String existingToken = configManager.getConfiguration("lendingtracker", configKey);

		if (existingToken != null && !existingToken.isEmpty()) {
			return existingToken;
		}

		// Generate new token
		String newToken = generateSecureToken();
		configManager.setConfiguration("lendingtracker", configKey, newToken);
		log.info("Generated new webhook token for RSN {} in group {}", rsn, groupId);

		return newToken;
	}

	/**
	 * Get an existing token without creating a new one.
	 * @param groupId The group ID
	 * @param rsn The player's RSN
	 * @return The token or null if not exists
	 */
	public String getToken(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			return null;
		}

		String configKey = buildConfigKey(groupId, rsn);
		return configManager.getConfiguration("lendingtracker", configKey);
	}

	/**
	 * Check if user has already seen their token (for one-time display).
	 * @param groupId The group ID
	 * @param rsn The player's RSN
	 * @return true if token was already shown to user
	 */
	public boolean hasTokenBeenShown(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			return false;
		}

		String shownKey = CONFIG_TOKEN_SHOWN + groupId + "_" + rsn;
		String shown = configManager.getConfiguration("lendingtracker", shownKey);
		return "true".equals(shown);
	}

	/**
	 * Mark token as shown to the user.
	 * @param groupId The group ID
	 * @param rsn The player's RSN
	 */
	public void markTokenAsShown(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			return;
		}

		String shownKey = CONFIG_TOKEN_SHOWN + groupId + "_" + rsn;
		configManager.setConfiguration("lendingtracker", shownKey, "true");
	}

	/**
	 * Regenerate a token (revokes old one).
	 * @param groupId The group ID
	 * @param rsn The player's RSN
	 * @return The new token
	 */
	public String regenerateToken(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			throw new IllegalArgumentException("Group ID and RSN cannot be null");
		}

		String configKey = buildConfigKey(groupId, rsn);
		String newToken = generateSecureToken();
		configManager.setConfiguration("lendingtracker", configKey, newToken);

		// Reset "shown" flag so user can see new token
		String shownKey = CONFIG_TOKEN_SHOWN + groupId + "_" + rsn;
		configManager.unsetConfiguration("lendingtracker", shownKey);

		log.warn("Regenerated webhook token for RSN {} in group {} (old token revoked)", rsn, groupId);
		return newToken;
	}

	/**
	 * Revoke a token (delete it).
	 * @param groupId The group ID
	 * @param rsn The player's RSN
	 */
	public void revokeToken(String groupId, String rsn) {
		if (groupId == null || rsn == null) {
			return;
		}

		String configKey = buildConfigKey(groupId, rsn);
		configManager.unsetConfiguration("lendingtracker", configKey);

		String shownKey = CONFIG_TOKEN_SHOWN + groupId + "_" + rsn;
		configManager.unsetConfiguration("lendingtracker", shownKey);

		log.info("Revoked webhook token for RSN {} in group {}", rsn, groupId);
	}

	/**
	 * Generate a cryptographically secure random token.
	 * Format: Base64-encoded random bytes (URL-safe)
	 * @return A secure token string
	 */
	private String generateSecureToken() {
		byte[] randomBytes = new byte[TOKEN_LENGTH];
		secureRandom.nextBytes(randomBytes);

		// Use URL-safe Base64 encoding (no padding)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
	}

	/**
	 * Build config key for token storage.
	 * Format ensures tokens are isolated per group AND per user.
	 */
	private String buildConfigKey(String groupId, String rsn) {
		// Sanitize inputs to prevent config key collisions
		String sanitizedGroupId = groupId.replaceAll("[^a-zA-Z0-9_-]", "");
		String sanitizedRsn = rsn.replaceAll("[^a-zA-Z0-9_-]", "");

		return CONFIG_PREFIX + sanitizedGroupId + "_" + sanitizedRsn;
	}

	/**
	 * Get a masked version of the token for display (shows first/last 4 chars).
	 * @param token The full token
	 * @return Masked token like "abc1...xyz9"
	 */
	public String maskToken(String token) {
		if (token == null || token.length() < 8) {
			return "****";
		}

		return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
	}

	/**
	 * Validate token format (basic check before sending).
	 * @param token The token to validate
	 * @return true if token appears valid
	 */
	public boolean isValidTokenFormat(String token) {
		if (token == null || token.isEmpty()) {
			return false;
		}

		// Check length (Base64 encoding of 32 bytes = 43 chars without padding)
		if (token.length() < 40 || token.length() > 50) {
			return false;
		}

		// Check for valid Base64 URL-safe characters
		return token.matches("^[A-Za-z0-9_-]+$");
	}
}
