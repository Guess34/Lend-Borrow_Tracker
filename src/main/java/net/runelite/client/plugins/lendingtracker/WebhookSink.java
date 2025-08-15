package net.runelite.client.plugins.lendingtracker;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class WebhookSink implements EventSink
{
    private final Gson gson;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String url;
    private final String secret; // optional; use if you sign payloads

    WebhookSink(Gson gson, String url, String secret)
    {
        this.gson = gson;
        this.url = url;
        this.secret = secret;
    }

    @Override
    public void push(TradeRecord rec)
    {
        try
        {
            if (url == null || url.isBlank())
            {
                return;
            }

            var payload = new Payload(rec);
            var json = gson.toJson(payload);

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");

            // If you sign requests, compute header here with `secret`:
            // if (secret != null && !secret.isBlank()) {
            //     String sig = Hmac.sha256Hex(secret, json);
            //     b.header("X-Signature", sig);
            // }

            HttpRequest req = b.POST(HttpRequest.BodyPublishers.ofString(json)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        }
        catch (Exception e)
        {
            // swallow/log; never crash the client
        }
    }

    static final class Payload
    {
        final String type = "trade_completed";
        final TradeRecord record;

        Payload(TradeRecord r)
        {
            this.record = r;
        }
    }
}