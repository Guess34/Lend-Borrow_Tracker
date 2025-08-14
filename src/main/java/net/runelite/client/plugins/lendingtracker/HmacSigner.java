package net.runelite.client.plugins.lendingtracker;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class HmacSigner
{
    static String sign(String secret, String message) throws Exception
    {
        // Expect raw text secret; encode directly (no URL-safe expectations here)
        SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(key);
        byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return "hmac-sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }
}
