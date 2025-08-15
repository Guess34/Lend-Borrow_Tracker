package net.runelite.client.plugins.lendingtracker;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


interface EventSink
{
    void onTradeCompleted(TradeRecord record);
}


class NoopSink implements EventSink
{
    @Override
    public void onTradeCompleted(TradeRecord record)
    {
        // intentionally no-op
    }
}


class WebhookSink implements EventSink
{
    private final Gson gson;
    private final String url;
    private final String hmacSecret;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    WebhookSink(final Gson gson, final String url, final String hmacSecret)
    {
        this.gson = gson;
        this.url = url;
        this.hmacSecret = (hmacSecret != null && !hmacSecret.isBlank()) ? hmacSecret : null;
    }

    @Override
    public void onTradeCompleted(final TradeRecord record)
    {
        if (url == null || url.isBlank() || record == null)
        {
            return; // nothing to do
        }

        // Build payload
        String body;
        if (hmacSecret == null)
        {
            body = gson.toJson(new Payload(record));
        }
        else
        {
            String unsigned = gson.toJson(new Payload(record));
            String signature = hmacSha256Hex(unsigned, hmacSecret);
            body = gson.toJson(new SignedPayload(record, signature));
        }

        // Fire-and-forget POST
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
            .exceptionally(ex -> null); // swallow errors (log-free to avoid client spam)
    }

    private static String hmacSha256Hex(String data, String key)
    {
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw)
            {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (Exception e)
        {
            // If HMAC fails, fall back to empty signature
            return "";
        }
    }

    // Minimal payload structs
    static class Payload {
        final String type = "trade_completed";
        final TradeRecord record;
        Payload(TradeRecord r){ this.record = r; }
    }
    static class SignedPayload {
        final String type = "trade_completed";
        final TradeRecord record;
        final String signature;
        SignedPayload(TradeRecord r, String s){ this.record = r; this.signature = s; }
    }
}
