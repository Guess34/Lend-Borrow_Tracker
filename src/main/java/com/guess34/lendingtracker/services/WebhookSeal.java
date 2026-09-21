package com.guess34.lendingtracker.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Locks a group's Discord webhook link with the group's own key, so it can travel
 * inside the group's synced data without the relay - or anyone who fetches the
 * group's stored record - being able to read it.
 *
 * The key is derived from the group's syncSecret, which only members hold (it
 * reaches a joiner through their invite). AES-GCM, so a tampered value fails to
 * open instead of opening to something else.
 */
final class WebhookSeal
{
	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	private WebhookSeal()
	{
	}

	/** The sealed form of url, or null if it can't be sealed. */
	static String seal(String url, String syncSecret)
	{
		if (url == null || syncSecret == null || syncSecret.isEmpty())
		{
			return null;
		}
		try
		{
			byte[] iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key(syncSecret), new GCMParameterSpec(TAG_BITS, iv));
			byte[] sealed = cipher.doFinal(url.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
			return PREFIX + Base64.getEncoder().encodeToString(out);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/** The url inside, or null if it is empty, damaged, or sealed with another key. */
	static String open(String sealed, String syncSecret)
	{
		if (sealed == null || !sealed.startsWith(PREFIX) || syncSecret == null || syncSecret.isEmpty())
		{
			return null;
		}
		try
		{
			byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
			if (all.length <= IV_BYTES)
			{
				return null;
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(syncSecret), new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
			byte[] url = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
			return new String(url, StandardCharsets.UTF_8);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	// A key of its own, derived from the group secret, so the webhook seal and
	// the message signatures never share one.
	private static SecretKeySpec key(String syncSecret) throws Exception
	{
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(syncSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] derived = mac.doFinal("lending-tracker/discord-webhook".getBytes(StandardCharsets.UTF_8));
		return new SecretKeySpec(derived, "AES");
	}
}
