package net.runelite.client.plugins.lendingtracker;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

class WebhookSink implements EventSink
{
    private static final Gson GSON = new Gson();
    private final String url;
    private final String hmacSecret;
    private final HttpClient http = HttpClient.newHttpClient();

    WebhookSink(String url, String hmacSecret)
    {
        this.url = url;
        this.hmacSecret = (hmacSecret == null || hmacSecret.isBlank()) ? null : hmacSecret;
    }

    @Override
    public void onTradeCompleted(TradeRecord rec)
    {
        try
        {
            String body = GSON.toJson(new Payload(rec));
            var b = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json");

            String finalBody = body;
            if (hmacSecret != null)
            {
                String sig = HmacSigner.sign(hmacSecret, body);
                // Send as JSON with signature field, or as a header.
                finalBody = GSON.toJson(new SignedPayload(rec, sig));
            }

            HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(finalBody)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        }
        catch (Exception ignored) { /* never crash client on webhook */ }
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
