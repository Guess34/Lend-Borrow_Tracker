package net.runelite.client.plugins.lendingtracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
    name = "Lending Tracker",
    description = "Track completed trades locally, optionally tag with Lend/Borrow and (optionally) emit webhook events.",
    tags = {"lending","borrow","loan","trade","clan"}
)
public class LendingTrackerPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(LendingTrackerPlugin.class);
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<TradeRecord>>() {}.getType();
    private static final Path DATA_FILE = RuneLite.RUNELITE_DIR.toPath()
        .resolve("lendingtracker").resolve("trades.json");

    @Inject private Client client;
    @Inject private ClientToolbar clientToolbar;
    @Inject private LendingTrackerPanel panel;
    @Inject private LendingTrackerConfig config;

    private NavigationButton navButton;
    private final Deque<TradeRecord> buffer = new ArrayDeque<>();

    // Soft state for the "current" trade
    private String currentPartner;
    private LendingTrackerConfig.Role selectedRole;
    private String pendingNote;

    // Event sink (noop or webhook)
    private EventSink sink = new NoopSink();

    @Provides
    LendingTrackerConfig provideConfig(ConfigManager cm) { return cm.getConfig(LendingTrackerConfig.class); }

    @Override
    protected void startUp()
    {
        // Side panel + quick role pick buttons
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
        navButton = NavigationButton.builder()
            .tooltip("Lending Tracker")
            .icon(icon)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        panel.lendBtn.addActionListener(e -> selectedRole = LendingTrackerConfig.Role.LENDER);
        panel.borrowBtn.addActionListener(e -> selectedRole = LendingTrackerConfig.Role.BORROWER);
        panel.noneBtn.addActionListener(e -> selectedRole = LendingTrackerConfig.Role.NONE);

        // Load prior
        try
        {
            Files.createDirectories(DATA_FILE.getParent());
            if (Files.exists(DATA_FILE))
            {
                List<TradeRecord> prev = GSON.fromJson(Files.readString(DATA_FILE), LIST_TYPE);
                if (prev != null)
                {
                    for (int i = prev.size() - 1; i >= 0 && i >= prev.size() - 50; i--)
                    {
                        panel.addLine(prev.get(i).toString());
                    }
                }
            }
        }
        catch (IOException e) { log.warn("Unable to load history", e); }

        configureSink();

        // Initialize default role ready for first trade
        selectedRole = config.defaultRole();
        pendingNote = "";
    }

    @Override
    protected void shutDown()
    {
        if (navButton != null)
        {
            clientToolbar.removeNavigation(navButton);
            navButton = null;
        }
        flush();
        currentPartner = null;
        selectedRole = null;
        pendingNote = null;
        sink = new NoopSink();
    }

    private void configureSink()
    {
        if (config.enableWebhook() && config.webhookUrl() != null && !config.webhookUrl().isBlank())
            sink = new WebhookSink(config.webhookUrl(), config.hmacSecret());
        else
            sink = new NoopSink();
    }

    // If user toggles webhook settings at runtime, the plugin can be reconfigured by toggling it off/on.

    // --- Trade detection (simple, robust via chat messages) ---

    @Subscribe
    public void onChatMessage(ChatMessage ev)
    {
        if (!config.enabled()) return;

        final String raw = Text.removeTags(ev.getMessage());

        if (ev.getType() == ChatMessageType.GAMEMESSAGE && raw.startsWith("Sending trade offer to "))
        {
            currentPartner = raw.substring("Sending trade offer to ".length()).replace(".", "");
            pendingNote = panel.consumeNote();
            if (config.promptRole() && (selectedRole == null || selectedRole == LendingTrackerConfig.Role.NONE))
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "LendingTracker: pick Lend/Borrow/Not a loan in the panel (top).", null);
            }
        }

        if (ev.getType() == ChatMessageType.GAMEMESSAGE &&
            (raw.equalsIgnoreCase("The trade has been completed.") || raw.equalsIgnoreCase("The trade has been accepted.")))
        {
            recordCompletedTrade();
        }

        if (ev.getType() == ChatMessageType.GAMEMESSAGE &&
            (raw.equalsIgnoreCase("Other player declined trade.") || raw.equalsIgnoreCase("Trade declined.") ||
             raw.equalsIgnoreCase("Trade was declined.")))
        {
            currentPartner = null;
            pendingNote = "";
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e)
    {
    }

    private void recordCompletedTrade()
    {
        String partner = (currentPartner == null || currentPartner.isBlank()) ? "unknown" : currentPartner;
        LendingTrackerConfig.Role role = (selectedRole == null) ? LendingTrackerConfig.Role.NONE : selectedRole;
        String note = (pendingNote == null) ? "" : pendingNote;

        TradeRecord rec = new TradeRecord(
            UUID.randomUUID().toString(),
            partner,
            role,
            note,
            client.getWorld(),
            Instant.now()
        );

        buffer.addLast(rec);
        panel.addLine(rec.toString());
        int max = Math.max(5, config.maxEntries());
        while (buffer.size() > max) buffer.removeFirst();

        flush();
        sink.onTradeCompleted(rec);

        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "LendingTracker: trade completed with " + partner +
            (role != LendingTrackerConfig.Role.NONE ? " (" + role.name() + ")" : ""), null);

        currentPartner = null;
        pendingNote = "";
    }

    private void flush()
    {
        try
        {
            List<TradeRecord> out = new ArrayList<>(buffer);
            Files.createDirectories(DATA_FILE.getParent());
            Files.writeString(DATA_FILE, GSON.toJson(out));
        }
        catch (IOException e) { log.warn("Unable to write history", e); }
    }
}