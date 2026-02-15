package com.guess34.lendingtracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Varbits;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.guess34.lendingtracker.model.*;
import com.guess34.lendingtracker.overlay.LendingSafetyOverlay;
import com.guess34.lendingtracker.overlay.MinimapWarningOverlay;
import com.guess34.lendingtracker.overlay.BorrowRequestNotificationOverlay;
import com.guess34.lendingtracker.overlay.NotificationBubbleOverlay;
import com.guess34.lendingtracker.panel.LendingTrackerPanel;
import com.guess34.lendingtracker.ui.LendingPanel;
// REMOVED: LendingPartyService import as requested
import com.guess34.lendingtracker.services.*;
import com.guess34.lendingtracker.services.core.LendingManager;
import com.guess34.lendingtracker.services.core.MarketplaceManager;
import com.guess34.lendingtracker.services.core.ItemSetManager;
import com.guess34.lendingtracker.services.core.StorageService;
import com.guess34.lendingtracker.services.group.GroupConfigStore;
import com.guess34.lendingtracker.services.sync.GroupSyncService;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import com.google.gson.Gson;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@PluginDescriptor(
    name = "Lending Tracker",
    description = "Track items lent to other players with advanced features",
    tags = {"lending", "items", "tracker", "party", "group", "risk"}
)
public class LendingTrackerPlugin extends Plugin {
    private static final int LENDING_WIDGET_GROUP = 334;
    private static final int ACCEPT_BUTTON = 13;
    
    @Inject
    private Client client;
    
    @Inject
    private ClientThread clientThread;
    
    @Inject
    private ConfigManager configManager;
    
    @Inject
    private LendingTrackerConfig config;
    
    @Inject
    private ClientToolbar clientToolbar;
    
    @Inject
    private OverlayManager overlayManager;
    
    @Inject
    private ItemManager itemManager;
    
    @Inject
    private ScheduledExecutorService executor;
    
    @Inject
    private Notifier notifier;
    
    @Inject
    private EventBus eventBus;
    
    @Inject
    private ChatMessageManager chatMessageManager;
    
    // FIXED: All services now use proper @Inject dependency injection
    @Inject
    private Recorder recorder;
    
    @Inject
    private NotificationService notificationService;
    
    @Inject
    private RiskAnalyzer riskAnalyzer;
    
    @Inject
    private CollateralManager collateralManager;
    
    @Inject
    private GroupConfigStore groupConfigStore;
    
    @Inject
    private GroupSyncService groupSyncService;
    
    // REMOVED: LendingPartyService injection as requested
    
    @Inject
    private LendingSafetyOverlay safetyOverlay;
    
    @Inject
    private MinimapWarningOverlay minimapWarningOverlay;
    
    @Inject
    private DiscordWebhookService discordWebhookService;
    
    @Inject
    private LocalDataSyncService localDataSyncService;
    
    @Inject
    private PeerReviewService peerReviewService;
    
    @Inject
    private OnlineStatusService onlineStatusService;
    
    @Inject
    private BorrowRequestService borrowRequestService;

    // ADDED: Webhook security services
    @Inject
    private WebhookTokenService webhookTokenService;

    @Inject
    private WebhookRateLimiter webhookRateLimiter;

    @Inject
    private WebhookAuditLogger webhookAuditLogger;

    // ADDED: Missing service injections needed by UI panels
    @Inject
    private LendingManager lendingManager;

    @Inject
    private MarketplaceManager marketplaceManager;

    @Inject
    private ItemSetManager itemSetManager;

    @Inject
    private StorageService storageService;

    @Inject
    private ProofScreenshot proofScreenshot;

    @Inject
    private NotificationMessageService notificationMessageService;

    @Inject
    private BorrowRequestNotificationOverlay borrowRequestNotificationOverlay;

    @Inject
    private NotificationBubbleOverlay notificationBubbleOverlay;

    private LendingTrackerPanel panel;
    private LendingPanel newPanel;
    private NavigationButton navButton;
    @Inject
    private Gson gson;
    
    private volatile LendingEntry pendingLending = null;
    private volatile String lastLendingTarget = null;
    private volatile String currentTradePartner = null;
    private final Map<Integer, Integer> tradeItems = new ConcurrentHashMap<>(); // itemId -> quantity
    private final List<String> shownWarnings = new ArrayList<>(); // Track shown warning sessions
    private final List<RiskSession> activeSessions = Collections.synchronizedList(new ArrayList<>());
    private final List<WarningLogEntry> warningHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, CollateralAgreement> collateralAgreements = new ConcurrentHashMap<>();
    
    @Override
    protected void startUp() throws Exception {
        log.info("Starting Lending Tracker plugin...");
        try {
            log.info("Initializing services with proper dependency injection...");
                    // All services now use proper @Inject dependency injection
            // No manual initialization needed - Guice handles everything
            log.info("All services will be injected by Guice automatically");
            
            // Initialize core services that need initialization
            recorder.initialize();
            groupConfigStore.initialize();
            localDataSyncService.initialize();
            
            // Optional services created (no initialization needed)
            
            try {
                loadRiskLog();
            } catch (Exception e) {
                log.warn("Failed to load risk log, continuing anyway: {}", e.getMessage());
            }
            
            // Create UI components
            log.info("Starting UI component creation...");
            
            BufferedImage icon;
            try {
                log.info("Loading plugin icon...");
                icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
                log.info("Icon loaded successfully");
            } catch (Exception e) {
                log.warn("Failed to load panel icon, using default: {}", e.getMessage());
                // Create a simple default icon
                icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = icon.createGraphics();
                g.setColor(Color.ORANGE);
                g.fillOval(2, 2, 12, 12);
                g.setColor(Color.WHITE);
                g.drawString("L", 6, 12);
                g.dispose();
            }
            
            // FIXED: ProofScreenshot is now @Inject managed, no need to create manually

            // REMOVED: Old LendingTrackerPanel is no longer created.
            // The new LendingPanel (Phase 5) is the active UI. Keeping panel field null.
            log.info("Old panel creation skipped - using new LendingPanel");

            // ADDED: Create new LendingPanel (Phase 5 Material Design UI)
            log.info("Creating new LendingPanel...");
            newPanel = new LendingPanel(this, eventBus);
            log.info("New LendingPanel created successfully");

            log.info("Creating NavigationButton...");
            navButton = NavigationButton.builder()
                .tooltip("Lending Tracker")
                .icon(icon)
                .priority(5)
                .panel(newPanel)
                .build();
            log.info("NavigationButton created successfully");
            
            log.info("Checking ClientToolbar injection...");
            if (clientToolbar != null) {
                log.info("ClientToolbar is available, adding navigation button...");
                clientToolbar.addNavigation(navButton);
                log.info("NavigationButton added to toolbar successfully!");
            } else {
                log.error("CRITICAL: ClientToolbar is null! UI will not appear.");
                log.error("This indicates a dependency injection problem.");
            }
            overlayManager.add(safetyOverlay);
        overlayManager.add(minimapWarningOverlay);
            
            // RESTORED: Register OnlineStatusService for GameTick events
            eventBus.register(onlineStatusService);

            // ADDED: Wire sync callback so panel refreshes when group data changes from other members
            groupSyncService.setOnSyncCallback(() ->
            {
                log.info("Sync callback triggered - refreshing panel");
                if (newPanel != null) {
                    newPanel.refresh();
                }
            });

            // ADDED: If user is already logged in when plugin starts, trigger login flow immediately
            if (client.getGameState() == GameState.LOGGED_IN && client.getLocalPlayer() != null
                && client.getLocalPlayer().getName() != null) {
                String playerName = client.getLocalPlayer().getName();
                log.info("Plugin started while already logged in as: {}", playerName);
                groupConfigStore.onAccountLogin(playerName);
                localDataSyncService.onAccountLogin();
                configManager.setConfiguration("lendingtracker", "currentAccount", playerName);
                // FIXED: Start sync for the active group
                LendingGroup activeGroup = groupConfigStore.getActiveGroup();
                if (activeGroup != null) {
                    groupSyncService.startSync(activeGroup.getId(), playerName);
                }
                newPanel.refresh();
            }
            
            // Schedule periodic checks
            try {
                executor.scheduleAtFixedRate(this::checkOverdueLoans, 0, 1, TimeUnit.HOURS);

                // IMPLEMENTED: dataRetentionDays - Schedule daily cleanup of old records
                executor.scheduleAtFixedRate(this::cleanupOldRecords, 1, 24, TimeUnit.HOURS);
                executor.scheduleAtFixedRate(this::syncGroupData, 0, 5, TimeUnit.MINUTES);

                // ADDED: Update GE prices for available marketplace items twice per day (every 12 hours)
                executor.scheduleAtFixedRate(this::updateMarketplacePrices, 1, 12, TimeUnit.HOURS);
                
                if (config.enableDailyReports()) {
                    scheduleDailyReport();
                }
            } catch (Exception e) {
                log.warn("Failed to schedule periodic tasks: {}", e.getMessage());
            }
            
            log.info("Lending Tracker plugin started successfully");
            
        } catch (Exception e) {
            log.error("Failed to start Lending Tracker plugin", e);
            throw e;
        }
    }
    
    @Override
    protected void shutDown() throws Exception {
        // FIXED: Stop sync service on shutdown to clean up executor
        groupSyncService.stopSync();

        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }
        if (safetyOverlay != null) {
            overlayManager.remove(safetyOverlay);
        overlayManager.remove(minimapWarningOverlay);
        }
        
        // RESTORED: Unregister OnlineStatusService
        if (onlineStatusService != null) {
            eventBus.unregister(onlineStatusService);
        }
        
        if (localDataSyncService != null) {
            try {
                localDataSyncService.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down local data sync service: {}", e.getMessage());
            }
        }
        
        if (discordWebhookService != null) {
            try {
                discordWebhookService.shutdown();
            } catch (Exception e) {
                log.warn("Error shutting down discord webhook service: {}", e.getMessage());
            }
        }
        
        // Optional services don't need explicit shutdown
        
        panel = null;
        newPanel = null;
        navButton = null;
        pendingLending = null;
        activeSessions.clear();
        collateralAgreements.clear();
    }

    // FIXED: Removed all reflection-based dependency injection methods
    // All services now use proper @Inject annotations handled by Guice
    
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() == LENDING_WIDGET_GROUP) {
            clientThread.invokeLater(this::processLendingInterface);
        }
        // Detect when trade interface opens
        if (event.getGroupId() == 335) { // Trade interface group ID
            handleTradeInterfaceOpened();
        }
    }
    
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        String target = event.getTarget();

        // Add custom menu entries for items (only on "Examine" to avoid duplicates)
        if (target != null && !target.isEmpty() &&
            (event.getOption().equals("Examine") &&
             (event.getType() == MenuAction.EXAMINE_ITEM.getId() ||
              event.getType() == MenuAction.EXAMINE_ITEM_GROUND.getId()))) {

            // Add "Add to Lending List" menu option for items
            client.createMenuEntry(-1)
                .setOption("Add to Lending List")
                .setTarget(event.getTarget())
                .setType(MenuAction.RUNELITE)
                .setParam0(event.getActionParam0())
                .setParam1(event.getActionParam1())
                .setIdentifier(event.getIdentifier());
        }

        // Add "Lend to Group" menu entry for inventory items
        if (target != null && !target.isEmpty() && event.getOption().equals("Drop"))
        {
            // This is an inventory item (Drop option exists)
            client.createMenuEntry(-1)
                .setOption("Lend to Group")
                .setTarget(event.getTarget())
                .setType(MenuAction.RUNELITE)
                .setParam0(event.getActionParam0())
                .setParam1(event.getActionParam1())
                .setIdentifier(event.getIdentifier());
        }

        // Add "Borrow from Marketplace" menu entry for GE/searchable items
        if (event.getOption().equals("Examine") && event.getType() == MenuAction.EXAMINE_ITEM.getId())
        {
            // Add borrow option for items that can be searched
            client.createMenuEntry(-1)
                .setOption("Borrow from Marketplace")
                .setTarget(event.getTarget())
                .setType(MenuAction.RUNELITE)
                .setParam0(event.getActionParam0())
                .setParam1(event.getActionParam1())
                .setIdentifier(event.getIdentifier());
        }
        
        // Add custom menu entries for players
        if (target != null && !target.isEmpty() && 
            (event.getType() == MenuAction.PLAYER_FIRST_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_SECOND_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_THIRD_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_FOURTH_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_FIFTH_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_SIXTH_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_SEVENTH_OPTION.getId() ||
             event.getType() == MenuAction.PLAYER_EIGHTH_OPTION.getId())) {
            
            // Only add invite option if current user has permission to invite
            if (canCurrentUserInvite()) {
                client.createMenuEntry(-1)
                    .setOption("Invite to Lending Group")
                    .setTarget(event.getTarget())
                    .setType(MenuAction.RUNELITE)
                    .setParam0(event.getActionParam0())
                    .setParam1(event.getActionParam1())
                    .setIdentifier(event.getIdentifier());
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (event.getMenuOption().equals("Add to Lending List")) {
            handleAddToAvailableList(event);
        } else if (event.getMenuOption().equals("Lend to Group")) {
            handleLendToGroup(event);
        } else if (event.getMenuOption().equals("Borrow from Marketplace")) {
            handleBorrowFromMarketplace(event);
        } else if (event.getMenuOption().equals("Invite to Lending Group")) {
            handlePlayerInvite(event);
        } else if (event.getMenuOption().contains("Lend")) {
            lastLendingTarget = event.getMenuTarget();

            // Extract player name from menu target
            String playerName = extractPlayerName(event.getMenuTarget());
            if (playerName != null) {
                checkPlayerRisk(playerName);
            }
        } else if (event.getMenuOption().equals("Drop")) {
            // Detect drop actions with borrowed items
            handleDropAction(event);
        } else if (event.getMenuOption().equals("Trade with")) {
            // Update current trade partner
            String tradePartner = extractPlayerName(event.getMenuTarget());
            if (tradePartner != null) {
                currentTradePartner = tradePartner;
            }
        }
    }
    
    @Subscribe
    public void onChatMessage(ChatMessage event) {
        String message = event.getMessage();
        
        // Handle private messages for group invites
        if (event.getType() == ChatMessageType.PRIVATECHAT || event.getType() == ChatMessageType.PRIVATECHATOUT) {
            handlePrivateMessage(event.getName(), message);
        }
        
        if (event.getType() != ChatMessageType.GAMEMESSAGE) {
            return;
        }
        
        // Check for lending confirmation messages
        if (message.contains("You lend") && message.contains("to")) {
            handleLendingConfirmation(message);
        }
        
        // Check for return messages
        if (message.contains("returned your") || message.contains("gives you back")) {
            handleReturnConfirmation(message);
        }
        
        // Check for collateral messages
        if (message.contains("collateral") || message.contains("security deposit")) {
            handleCollateralMessage(message);
        }
    }
    
    @Subscribe
    public void onGameTick(GameTick event) {
        // Check for overdue notifications
        if (client.getGameCycle() % 100 == 0) { // Every 100 ticks
            checkAndNotifyOverdue();
        }
        
        // Update risk sessions
        updateRiskSessions();
        
        // Monitor high-risk activities every 50 ticks
        if (client.getGameCycle() % 50 == 0) {
            monitorGrandExchangeUsage();
            monitorTradeActivity();
        }
        
        // Monitor drop actions every tick (more frequent for quick detection)
        monitorDropActions();
    }
    
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // Monitor trade container for lending/borrowing detection
        if (event.getContainerId() == InventoryID.TRADE.getId()) {
            handleTradeContainerChange(event);
        }
    }
    
    // Duplicate onWidgetLoaded removed - trade interface handling moved to existing method
    
    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        // Detect when trade interface closes
        if (event.getGroupId() == 335) { // Trade interface group ID
            handleTradeInterfaceClosed();
        }
    }
    
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            checkWildernessStatus();
            // FIXED: Trigger account login and panel refresh on login
            // Use clientThread to safely get the player name after login completes
            clientThread.invokeLater(() ->
            {
                if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) {
                    return false; // Not ready yet, retry next tick
                }
                String playerName = client.getLocalPlayer().getName();
                log.info("Login detected for player: {} - loading account data", playerName);

                // Load account data into all services
                groupConfigStore.onAccountLogin(playerName);
                localDataSyncService.onAccountLogin();

                // Store current account for config lookups
                configManager.setConfiguration("lendingtracker", "currentAccount", playerName);

                // ADDED: Wire sync callback to refresh panel when group data changes
                groupSyncService.setOnSyncCallback(() ->
                {
                    log.info("Sync callback triggered - refreshing panel");
                    if (newPanel != null) {
                        newPanel.refresh();
                    }
                });

                // FIXED: Start sync for the active group so publishEvent() calls work
                LendingGroup activeGroup = groupConfigStore.getActiveGroup();
                if (activeGroup != null) {
                    groupSyncService.startSync(activeGroup.getId(), playerName);
                    log.info("Started sync for active group: {} ({})", activeGroup.getName(), activeGroup.getId());
                }

                // Refresh the panel with loaded data
                if (newPanel != null) {
                    newPanel.refresh();
                }
                return true; // Done
            });
        }
        else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            // FIXED: Stop sync on logout
            groupSyncService.stopSync();
            // ADDED: Handle logout - clear cached account in GroupControlPanel
            if (newPanel != null) {
                newPanel.refresh();
            }
        }
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event) {
        // ADDED: Handle account switch (e.g., switching RuneScape profiles)
        // Re-trigger the login flow to reload data for the new profile
        clientThread.invokeLater(() ->
        {
            if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) {
                return false;
            }
            String playerName = client.getLocalPlayer().getName();
            log.info("RuneScape profile changed for player: {} - reloading data", playerName);
            groupConfigStore.onAccountLogin(playerName);
            localDataSyncService.onAccountLogin();
            configManager.setConfiguration("lendingtracker", "currentAccount", playerName);
            // FIXED: Restart sync for the new profile's active group
            LendingGroup activeGroup = groupConfigStore.getActiveGroup();
            if (activeGroup != null) {
                groupSyncService.startSync(activeGroup.getId(), playerName);
            }
            if (newPanel != null) {
                newPanel.refresh();
            }
            return true;
        });
    }
    
    @Subscribe
    public void onVarbitChanged(VarbitChanged event) {
        // Monitor wilderness level changes
        if (event.getVarbitId() == Varbits.IN_WILDERNESS) {
            handleWildernessChange();
        }
    }
    
    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!event.getGroup().equals("lendingtracker")) {
            return;
        }
        
        if (event.getKey().equals("enableDailyReports")) {
            if (config.enableDailyReports()) {
                scheduleDailyReport();
            }
        }
    }
    
    private void processLendingInterface() {
        if (client == null) {
            log.warn("Client is null in processLendingInterface");
            return;
        }
        
        Widget acceptButton = client.getWidget(LENDING_WIDGET_GROUP, ACCEPT_BUTTON);
        if (acceptButton == null) {
            return;
        }
        
        // Extract item information from the lending interface
        String itemName = extractItemFromInterface();
        int itemId = extractItemIdFromInterface();
        int quantity = extractQuantityFromInterface();
        String playerName = extractPlayerFromInterface();
        
        if (itemName != null && !itemName.isEmpty() && playerName != null && !playerName.isEmpty()) {
            long value = calculateItemValue(itemId, quantity);
            long dueDate = calculateDueDate();
            
            pendingLending = new LendingEntry();
            pendingLending.setId(UUID.randomUUID().toString());
            pendingLending.setBorrower(playerName);
            pendingLending.setItem(itemName);
            pendingLending.setItemId(itemId);
            pendingLending.setQuantity(quantity);
            pendingLending.setValue(value);
            pendingLending.setLendTime(Instant.now().toEpochMilli());
            pendingLending.setDueTime(dueDate);
            
            // Perform risk check before allowing the lending
            if (riskAnalyzer != null) {
                int riskLevel = riskAnalyzer.analyzePlayer(playerName);
                if (riskLevel > config.maxAcceptableRisk()) {
                    showRiskWarning(playerName, riskLevel);
                }
            }
        }
    }
    
    private void handleLendingConfirmation(String message) {
        if (pendingLending != null) {
            recorder.addEntry(pendingLending);
            if (newPanel != null) { newPanel.refresh(); }
            
            notificationService.sendLendingNotification(pendingLending, 
                "Lent " + pendingLending.getItemName() + " to " + pendingLending.getPlayerName());
            
            // REMOVED: Party sync sharing as requested
            
            // Sync with group if active
            LendingGroup activeGroup = groupConfigStore.getActiveGroup();
            if (activeGroup != null) {
                groupSyncService.syncLending(activeGroup.getId(), pendingLending);
                
                // Send Discord notification
                discordWebhookService.sendLendingNotification(activeGroup.getId(), pendingLending);
            }
            
            pendingLending = null;
        }
    }
    
    private void handleReturnConfirmation(String message) {
        // Parse the return message to identify which item was returned
        String playerName = extractPlayerFromReturnMessage(message);
        String itemName = extractItemFromReturnMessage(message);
        
        if (playerName != null && itemName != null) {
            List<LendingEntry> entries = recorder.getEntriesForPlayer(playerName).stream()
                .filter(entry -> entry.getItemName().equals(itemName) && !entry.isReturned())
                .collect(Collectors.toList());  // Fixed: Changed from .toList()
            
            if (!entries.isEmpty()) {
                LendingEntry entry = entries.get(0);
                recorder.completeEntry(entry.getId(), true);
                if (newPanel != null) { newPanel.refresh(); }
                
                notificationService.sendReturnNotification(entry);
                
                // Update risk analysis
                riskAnalyzer.recordReturn(playerName, true);
                
                // REMOVED: Party sync sharing as requested
                
                // Send Discord notification
                LendingGroup activeGroup = groupConfigStore.getActiveGroup();
                if (activeGroup != null) {
                    discordWebhookService.sendReturnNotification(activeGroup.getId(), entry);
                }
            }
        }
    }
    
    private void handleCollateralMessage(String message) {
        String playerName = extractPlayerFromMessage(message);
        String collateralInfo = extractCollateralInfo(message);
        
        if (playerName != null && collateralInfo != null) {
            List<LendingEntry> activeLoans = recorder.getEntriesForPlayer(playerName).stream()
                .filter(entry -> !entry.isReturned())
                .collect(Collectors.toList());  // Fixed: Changed from .toList()
            
            if (!activeLoans.isEmpty()) {
                CollateralAgreement agreement = new CollateralAgreement(
                    activeLoans.get(0),
                    collateralInfo,
                    Instant.now().toEpochMilli(),
                    false,  // isReturned
                    true    // isActive
                );
                
                collateralAgreements.put(playerName.toLowerCase(), agreement);
                collateralManager.recordCollateral(agreement);
                
                notificationService.sendCollateralNotification(playerName, collateralInfo);
            }
        }
    }
    
    private void checkAndNotifyOverdue() {
        List<LendingEntry> overdueEntries = recorder.getOverdueEntries();
        
        for (LendingEntry entry : overdueEntries) {
            // Only notify once per day per overdue item
            if (shouldNotifyOverdue(entry)) {
                notificationService.sendOverdueNotification(entry);
                markOverdueNotified(entry);
            }
        }
    }
    
    private void checkOverdueLoans() {
        List<LendingEntry> overdueEntries = recorder.getOverdueEntries();
        
        if (!overdueEntries.isEmpty() && config.enableNotifications()) {
            for (LendingEntry entry : overdueEntries) {
                long daysOverdue = ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(entry.getDueDate()),
                    Instant.now()
                );
                
                if (daysOverdue > 0 && daysOverdue % config.overdueReminderFrequency() == 0) {
                    notificationService.sendOverdueNotification(entry);

                    // IMPLEMENTED: enableSoundAlerts - Play sound on overdue
                    if (config.enableSoundAlerts()) {
                        client.playSoundEffect(net.runelite.api.SoundEffectID.UI_BOOP);
                    }

                    // Send Discord notification
                    LendingGroup activeGroup = groupConfigStore.getActiveGroup();
                    if (activeGroup != null) {
                        discordWebhookService.sendOverdueNotification(activeGroup.getId(), entry, (int) daysOverdue);
                    }
                }
            }
        }
    }
    
    private void syncGroupData() {
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        if (activeGroup != null) {
            List<LendingEntry> entries = recorder.getActiveEntries();
            groupSyncService.syncAllEntries(activeGroup.getId(), entries);
        }
    }

    /**
     * IMPLEMENTED: dataRetentionDays - Cleanup old returned loan records
     */
    private void cleanupOldRecords() {
        int retentionDays = config.dataRetentionDays();
        if (retentionDays <= 0) {
            return; // 0 means keep forever
        }

        long cutoffTime = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        int deletedCount = recorder.deleteOldReturnedEntries(cutoffTime);

        if (deletedCount > 0) {
            log.info("Data retention cleanup: Deleted {} returned loan records older than {} days", deletedCount, retentionDays);
        }
    }
    
    // ADDED: Update GE prices for available (not lent/borrowed) marketplace items
    // Runs on executor thread, uses clientThread for ItemManager access
    private void updateMarketplacePrices() {
        try {
            String groupId = groupConfigStore.getCurrentGroupIdUnchecked();
            if (groupId == null || groupId.isEmpty()) {
                return;
            }

            List<LendingEntry> available = recorder.getAvailable(groupId);
            if (available == null || available.isEmpty()) {
                return;
            }

            // Only update items that are available (no borrower = not lent out)
            List<LendingEntry> toUpdate = new java.util.ArrayList<>();
            for (LendingEntry entry : available) {
                if (entry.getBorrower() == null || entry.getBorrower().isEmpty()) {
                    toUpdate.add(entry);
                }
            }

            if (toUpdate.isEmpty()) {
                return;
            }

            log.info("Updating GE prices for {} available marketplace items", toUpdate.size());

            clientThread.invokeLater(() -> {
                int updated = 0;
                for (LendingEntry entry : toUpdate) {
                    if (entry.getItemId() > 0) {
                        int newPrice = itemManager.getItemPrice(entry.getItemId());
                        if (newPrice > 0 && newPrice != entry.getValue()) {
                            entry.setValue(newPrice);
                            updated++;
                        }
                    }
                }

                if (updated > 0) {
                    // Save updated prices
                    String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
                    if (currentPlayer != null) {
                        for (LendingEntry entry : toUpdate) {
                            recorder.updateAvailable(groupId, entry.getLender(), entry.getItem(), entry.getItemId(), entry);
                        }
                    }

                    final int count = updated;
                    log.info("Updated GE prices for {} items in group {}", count, groupId);

                    // Refresh panel to show updated prices
                    if (newPanel != null) {
                        SwingUtilities.invokeLater(() -> newPanel.refresh());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Failed to update marketplace prices: {}", e.getMessage());
        }
    }

    private void scheduleDailyReport() {
        executor.scheduleAtFixedRate(() -> {
            List<LendingEntry> activeEntries = recorder.getActiveEntries();
            List<LendingEntry> overdueEntries = recorder.getOverdueEntries();
            long totalValue = recorder.getTotalValueLent();
            
            notificationService.sendDailyReport(
                activeEntries.size(),
                totalValue,
                overdueEntries.size()
            );
        }, calculateDelayToNextReport(), 24, TimeUnit.HOURS);
    }
    
    private void checkPlayerRisk(String playerName) {
        int riskLevel = riskAnalyzer.analyzePlayer(playerName);
        
        if (riskLevel >= RiskLevel.HIGH.getValue()) {
            String reason = riskAnalyzer.getRiskReason(playerName);
            notificationService.sendRiskWarning(playerName, riskLevel, reason);
            
            // Create risk session for tracking
            LendingEntry currentLoan = pendingLending != null ? pendingLending : 
                                      recorder.getEntriesForPlayer(playerName).stream()
                                          .filter(e -> !e.isReturned())
                                          .findFirst()
                                          .orElse(null);
            
            if (currentLoan != null) {
                RiskSession session = new RiskSession(
                    UUID.randomUUID().toString(),
                    playerName,
                    currentLoan,
                    Instant.now().toEpochMilli(),
                    true  // isActive - Fixed: Added missing parameter
                );
                activeSessions.add(session);
            }
        }
    }
    
    private void updateRiskSessions() {
        Iterator<RiskSession> iterator = activeSessions.iterator();
        while (iterator.hasNext()) {
            RiskSession session = iterator.next();
            
            // Check if the loan associated with this session has been returned
            LendingEntry entry = session.getAssociatedLoan();
            if (entry != null && entry.isReturned()) {
                session.setActive(false);
                iterator.remove();
            }
        }
    }
    
    private void showRiskWarning(String playerName, int riskLevel) {
        // Log the warning event
        String warningMessage = String.format("High risk player detected: %s (Risk Level: %d). Reason: %s", 
            playerName, riskLevel, riskAnalyzer.getRiskReason(playerName));
        logWarning("RISK_ALERT", warningMessage, playerName);
        
        SwingUtilities.invokeLater(() -> {
            String message = String.format(
                "Warning: %s has a risk level of %d.\n" +
                "Previous issues: %s\n" +
                "Do you still want to proceed with the loan?",
                playerName,
                riskLevel,
                riskAnalyzer.getRiskReason(playerName)
            );
            
            int result = JOptionPane.showConfirmDialog(
                null,
                message,
                "High Risk Player",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );
            
            if (result == JOptionPane.NO_OPTION) {
                pendingLending = null;
                logWarning("LOAN_CANCELLED", "Loan cancelled by user due to risk warning", playerName);
            } else {
                logWarning("RISK_ACCEPTED", "User proceeded with loan despite risk warning", playerName);
            }
        });
    }
    
    // Helper methods for parsing messages and extracting information
    
    private String extractPlayerName(String menuTarget) {
        if (menuTarget == null) {
            return null;
        }
        
        // Remove color codes and formatting
        String cleaned = menuTarget.replaceAll("<[^>]+>", "");
        
        // Extract player name (usually after "to" or similar)
        if (cleaned.contains("to ")) {
            return cleaned.substring(cleaned.indexOf("to ") + 3).trim();
        }
        
        return cleaned.trim();
    }
    
    private String extractItemFromInterface() {
        // Extract item name from the lending widget interface
        try {
            Widget itemWidget = client.getWidget(LENDING_WIDGET_GROUP, 2);
            if (itemWidget != null) {
                return itemWidget.getText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract item from interface", e);
        }
        return null;
    }
    
    private int extractItemIdFromInterface() {
        try {
            Widget itemWidget = client.getWidget(LENDING_WIDGET_GROUP, 3);
            if (itemWidget != null) {
                return itemWidget.getItemId();
            }
        } catch (Exception e) {
            log.debug("Failed to extract item ID from interface", e);
        }
        return -1;
    }
    
    private int extractQuantityFromInterface() {
        try {
            Widget quantityWidget = client.getWidget(LENDING_WIDGET_GROUP, 4);
            if (quantityWidget != null) {
                return quantityWidget.getItemQuantity();  // Fixed: Changed from itemWidget
            }
        } catch (Exception e) {
            log.debug("Failed to extract quantity from interface", e);
        }
        return 1;
    }
    
    private String extractPlayerFromInterface() {
        try {
            Widget playerWidget = client.getWidget(LENDING_WIDGET_GROUP, 5);
            if (playerWidget != null) {
                return playerWidget.getText();
            }
        } catch (Exception e) {
            log.debug("Failed to extract player from interface", e);
        }
        return lastLendingTarget;
    }
    
    private String extractPlayerFromReturnMessage(String message) {
        // Parse return messages like "PlayerName returned your ItemName"
        if (message.contains(" returned your ")) {
            return message.substring(0, message.indexOf(" returned your ")).trim();
        }
        if (message.contains(" gives you back ")) {
            return message.substring(0, message.indexOf(" gives you back ")).trim();
        }
        return null;
    }
    
    private String extractItemFromReturnMessage(String message) {
        if (message.contains(" returned your ")) {
            String after = message.substring(message.indexOf(" returned your ") + 15);
            return after.replaceAll("[.]", "").trim();
        }
        if (message.contains(" gives you back ")) {
            String after = message.substring(message.indexOf(" gives you back ") + 16);
            return after.replaceAll("[.]", "").trim();
        }
        return null;
    }
    
    private String extractPlayerFromMessage(String message) {
        // Generic player extraction from various message types
        String[] words = message.split(" ");
        if (words.length > 0) {
            // Usually the first word is the player name in RuneScape messages
            return words[0].replaceAll("[^a-zA-Z0-9 _-]", "").trim();
        }
        return null;
    }
    
    private String extractCollateralInfo(String message) {
        if (message.toLowerCase().contains("collateral")) {
            // Extract everything after "collateral"
            int index = message.toLowerCase().indexOf("collateral");
            if (index != -1 && index + 10 < message.length()) {
                return message.substring(index + 10).trim();
            }
        }
        return message;
    }
    
    private long calculateItemValue(int itemId, int quantity) {
        if (itemId <= 0) {
            return 0;
        }
        
        ItemComposition itemComposition = itemManager.getItemComposition(itemId);
        if (itemComposition != null) {
            int price = itemManager.getItemPrice(itemId);
            return (long) price * quantity;
        }
        
        return 0;
    }
    
    private long calculateDueDate() {
        int daysToAdd = config.defaultLoanDuration();
        return Instant.now().plus(daysToAdd, ChronoUnit.DAYS).toEpochMilli();
    }
    
    private long calculateDelayToNextReport() {
        // Calculate delay until next report time (e.g., 9 AM)
        Instant now = Instant.now();
        Instant nextReport = now.truncatedTo(ChronoUnit.DAYS)
            .plus(1, ChronoUnit.DAYS)
            .plus(9, ChronoUnit.HOURS); // 9 AM
        
        return Duration.between(now, nextReport).toMillis();
    }
    
    private boolean shouldNotifyOverdue(LendingEntry entry) {
        String key = "overdue_notified_" + entry.getId();
        String lastNotified = configManager.getConfiguration("lendingtracker", key);
        
        if (lastNotified == null) {
            return true;
        }
        
        try {
            long lastNotifiedTime = Long.parseLong(lastNotified);
            long hoursSinceNotified = ChronoUnit.HOURS.between(
                Instant.ofEpochMilli(lastNotifiedTime),
                Instant.now()
            );
            
            return hoursSinceNotified >= 24; // Notify once per day
        } catch (NumberFormatException e) {
            return true;
        }
    }
    
    private void markOverdueNotified(LendingEntry entry) {
        String key = "overdue_notified_" + entry.getId();
        configManager.setConfiguration("lendingtracker", key, 
            String.valueOf(Instant.now().toEpochMilli()));
    }
    
    private void handleAddToAvailableList(MenuOptionClicked event) {
        String target = event.getMenuTarget();
        
        // Extract item name from target (remove color codes and formatting)
        String itemName = target.replaceAll("<[^>]*>", "").trim();
        
        // Get item ID from the event
        int itemId = event.getId();
        
        if (itemName.isEmpty()) {
            return;
        }
        
        // Create a dialog to get additional info
        SwingUtilities.invokeLater(() -> {
            String quantity = JOptionPane.showInputDialog(
                null, 
                "How many " + itemName + " are you lending?", 
                "Add to Available List", 
                JOptionPane.QUESTION_MESSAGE
            );
            
            if (quantity != null && !quantity.isEmpty()) {
                try {
                    int qty = Integer.parseInt(quantity);
                    long value = calculateItemValue(itemId, qty);
                    
                    LendingEntry entry = new LendingEntry();
                    entry.setId(UUID.randomUUID().toString());
                    entry.setItem(itemName);
                    entry.setItemId(itemId);
                    entry.setQuantity(qty);
                    entry.setValue(value);
                    
                    String lenderName = (client != null && client.getLocalPlayer() != null) ? 
                        client.getLocalPlayer().getName() : "Unknown";
                    entry.setLender(lenderName);
                    entry.setLendTime(Instant.now().toEpochMilli());
                    
                    recorder.addToAvailableList(entry, groupConfigStore.getCurrentGroupId());
                    if (newPanel != null) { newPanel.refresh(); }
                    
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(
                        null, 
                        "Please enter a valid number for quantity.", 
                        "Invalid Input", 
                        JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        });
    }
    
    // Public methods for panel interaction
    
    public void manuallyAddEntry(String playerName, String itemName, int quantity, long value, int durationDays) {
        LendingEntry entry = new LendingEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setBorrower(playerName);
        entry.setItem(itemName);
        entry.setItemId(-1); // No item ID for manual entries
        entry.setQuantity(quantity);
        entry.setValue(value);
        entry.setLendTime(Instant.now().toEpochMilli());
        entry.setDueTime(Instant.now().plus(durationDays, ChronoUnit.DAYS).toEpochMilli());
        
        recorder.addEntry(entry);
        if (newPanel != null) { newPanel.refresh(); }

        // REMOVED: Party sync sharing as requested
    }

    public void markAsReturned(String entryId) {
        recorder.completeEntry(entryId, true);
        if (newPanel != null) { newPanel.refresh(); }
        
        // Find the entry and update risk analysis
        List<LendingEntry> allEntries = recorder.getActiveEntries();
        allEntries.addAll(recorder.getHistoryEntries());
        
        allEntries.stream()
            .filter(e -> e.getId().equals(entryId))
            .findFirst()
            .ifPresent(entry -> {
                riskAnalyzer.recordReturn(entry.getPlayerName(), true);
                
                // REMOVED: Party sync sharing as requested
            });
    }
    
    public void markAsDefaulted(String entryId) {
        recorder.completeEntry(entryId, false);
        if (newPanel != null) { newPanel.refresh(); }
        
        // Find the entry and update risk analysis
        List<LendingEntry> allEntries = recorder.getActiveEntries();
        allEntries.addAll(recorder.getHistoryEntries());
        
        allEntries.stream()
            .filter(e -> e.getId().equals(entryId))
            .findFirst()
            .ifPresent(entry -> {
                riskAnalyzer.recordDefault(entry.getPlayerName(), entry.getValue());
                
                // REMOVED: Party sync sharing as requested
            });
    }
    
    public void deleteEntry(String entryId) {
        recorder.removeEntry(entryId);
        if (newPanel != null) { newPanel.refresh(); }
    }
    
    public void exportData() {
        recorder.exportData();
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(null,
                "Data exported successfully!",
                "Export Complete",
                JOptionPane.INFORMATION_MESSAGE)
        );
    }

    public void importData(String jsonData) {
        boolean success = recorder.importData(jsonData);

        SwingUtilities.invokeLater(() -> {
            if (success) {
                if (newPanel != null) { newPanel.refresh(); }
                JOptionPane.showMessageDialog(null,
                    "Data imported successfully!",
                    "Import Complete",
                    JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(null,
                    "Failed to import data. Please check the format.",
                    "Import Failed",
                    JOptionPane.ERROR_MESSAGE);
            }
        });
    }
    
    public List<LendingEntry> getFilteredEntries(String playerFilter, boolean showOverdueOnly) {
        List<LendingEntry> entries = recorder.getActiveEntries();
        
        if (playerFilter != null && !playerFilter.isEmpty()) {
            entries = entries.stream()
                .filter(e -> e.getPlayerName().toLowerCase().contains(playerFilter.toLowerCase()))
                .collect(Collectors.toList());  // Fixed: Changed from .toList()
        }
        
        if (showOverdueOnly) {
            long currentTime = Instant.now().toEpochMilli();
            entries = entries.stream()
                .filter(e -> !e.isReturned() && e.getDueDate() < currentTime)
                .collect(Collectors.toList());  // Fixed: Changed from .toList()
        }
        
        return entries;
    }
    
    // RECONNECTED: Risk Session Management - Provide access to active sessions
    public List<RiskSession> getActiveRiskSessions() {
        return new ArrayList<>(activeSessions);
    }
    
    // RECONNECTED: Collateral Agreement Tracker - Provide access to collateral manager
    public CollateralManager getCollateralManager() {
        return collateralManager;
    }

    // Provide access to borrow request service for marketplace
    public BorrowRequestService getBorrowRequestService() {
        return borrowRequestService;
    }

    // RECONNECTED: Security Log Viewer - Provide access to risk log
    public List<RiskLogEntry> getRiskLogEntries() {
        return new ArrayList<>(riskLog);
    }
    
    public void clearRiskLog() {
        riskLog.clear();
        saveRiskLog();
    }
    
    /**
     * Check if the current user has permission to invite players to the group
     */
    private boolean canCurrentUserInvite() {
        if (client == null || client.getLocalPlayer() == null) {
            return false;
        }
        
        String currentPlayerName = client.getLocalPlayer().getName();
        if (currentPlayerName == null || currentPlayerName.isEmpty()) {
            return false;
        }
        
        if (groupConfigStore == null) {
            return false;
        }
        
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        
        if (activeGroup == null) {
            return false; // No active group
        }
        
        // Check if player is owner or admin
        return groupConfigStore.isOwner(activeGroup.getId(), currentPlayerName) ||
               groupConfigStore.isAdmin(activeGroup.getId(), currentPlayerName);
    }
    
    /**
     * Handle right-click invite to lending group
     */
    private void handlePlayerInvite(MenuOptionClicked event) {
        String target = event.getMenuTarget();
        if (target == null || target.isEmpty()) {
            return;
        }
        
        // Extract player name from the target (remove level and formatting)
        String playerName = extractPlayerNameFromTarget(target);
        if (playerName == null || playerName.isEmpty()) {
            return;
        }
        
        // Check if player has permission to invite
        if (!canCurrentUserInvite()) {
            showNotification("Permission Denied", "Only group owners and admins can send invites");
            return;
        }
        
        LendingGroup currentGroup = groupConfigStore.getActiveGroup();
        if (currentGroup == null) {
            showNotification("No Group", "No active lending group selected");
            return;
        }
        
        // Create invite message
        String inviteCode = currentGroup.getInviteCode();
        String groupName = currentGroup.getName();
        String inviteMessage = "Join my lending group '" + groupName + "' with code: " + inviteCode;
        
        // Show invite dialog (reuse the existing invite dialog from the panel)
        SwingUtilities.invokeLater(() -> {
            showInviteDialog(playerName, inviteMessage);
        });
    }
    
    /**
     * Extract clean player name from menu target
     */
    private String extractPlayerNameFromTarget(String target) {
        if (target == null) {
            return null;
        }
        
        // Remove HTML tags and level info (e.g., "<col=ffffff>PlayerName<col=40ff00>  (level-3)")
        String cleaned = target.replaceAll("<[^>]*>", ""); // Remove HTML tags
        
        // Split on spaces and take first part (player name)
        String[] parts = cleaned.trim().split("\\s+");
        if (parts.length > 0) {
            return parts[0];
        }
        
        return null;
    }

    /**
     * Handle right-click "Lend to Group" on inventory item
     */
    private void handleLendToGroup(MenuOptionClicked event)
    {
        log.info("handleLendToGroup called");

        String target = event.getMenuTarget();

        if (target == null || target.isEmpty())
        {
            log.warn("Target is null or empty, aborting");
            return;
        }

        // Extract item name from target (remove HTML tags and quantity)
        String itemName = Text.removeTags(target);

        // Get the actual item ID from inventory - event.getId() returns widget slot, not item ID
        int itemId = -1;
        int slot = event.getParam0(); // Inventory slot index

        try
        {
            ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
            if (inventory != null)
            {
                Item[] items = inventory.getItems();
                if (slot >= 0 && slot < items.length)
                {
                    itemId = items[slot].getId();
                }
            }
        }
        catch (Exception ex)
        {
            log.error("Could not get item ID from inventory", ex);
        }

        log.info("Attempting to lend item: {} (ID: {})", itemName, itemId);

        if (itemId == -1)
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Error: Could not determine item ID", "");
            return;
        }

        // FIXED: Do all client thread checks BEFORE invoking on EDT

        // Get group ID on client thread
        final String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null || currentGroupId.isEmpty())
        {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Error: You must be in a group to lend items", "");
            log.warn("Cannot lend - no group selected");
            return;
        }

        // Check if item is tradeable on client thread (itemManager requires client thread)
        boolean canLend = true;
        int itemPrice = 0;
        try
        {
            itemPrice = itemManager.getItemPrice(itemId);
            boolean gameIsTradeable = itemManager.getItemComposition(itemId).isTradeable();

            log.info("Item check - Wiki price: {}, Game tradeable: {}", itemPrice, gameIsTradeable);

            if (!gameIsTradeable)
            {
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                    "Cannot lend " + itemName + " - This item is untradeable", "");
                log.info("Blocked lending: {} (ID: {}) - Game marks as untradeable", itemName, itemId);
                canLend = false;
            }
        }
        catch (Exception ex)
        {
            log.warn("Could not check if item is tradeable: {}", itemId, ex);
            // If check fails, allow lending (don't block)
        }

        if (!canLend)
        {
            return;
        }

        // Now invoke dialog creation on EDT with all the data we gathered
        final String finalItemName = itemName;
        final int finalItemId = itemId;
        final String finalGroupId = currentGroupId;
        final int finalItemPrice = itemPrice;

        try
        {
            SwingUtilities.invokeLater(() -> {
                try
                {
                    showLendItemDialog(finalItemName, finalItemId, finalGroupId, finalItemPrice);
                }
                catch (Exception ex)
                {
                    log.error("Error in showLendItemDialog on EDT", ex);
                    clientThread.invokeLater(() ->
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "ERROR: Could not open lend dialog - " + ex.getMessage(), "")
                    );
                }
            });
        }
        catch (Exception ex)
        {
            log.error("Error invoking showLendItemDialog", ex);
        }
    }

    /**
     * Handle right-click "Borrow from Marketplace" on item
     */
    private void handleBorrowFromMarketplace(MenuOptionClicked event)
    {
        String target = event.getMenuTarget();
        if (target == null || target.isEmpty())
        {
            return;
        }

        // Extract item name from target (remove HTML tags)
        String itemName = Text.removeTags(target);
        int itemId = event.getId();

        // Show borrow dialog on EDT
        SwingUtilities.invokeLater(() -> showBorrowItemDialog(itemName, itemId));
    }

    /**
     * Show lend item dialog (right-click from inventory)
     * MUST be called on EDT (via SwingUtilities.invokeLater)
     * All client thread checks (itemManager, etc) should be done BEFORE calling this
     */
    private void showLendItemDialog(String itemName, int itemId, String currentGroupId, int itemPrice)
    {
        log.info("showLendItemDialog called for: {} (ID: {}) in group: {}", itemName, itemId, currentGroupId);

        try
        {
                log.info("Creating dialog for {} with price {}", itemName, itemPrice);

                // FIXED: Use non-modal dialog with proper focus handling
                // This allows game to continue running while dialog is open
                JDialog dialog = new JDialog((Frame) null, "Lend " + itemName + " to Group", false);
                dialog.setSize(500, 500);
                dialog.setLocationRelativeTo(null); // Center on screen
                dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                dialog.setAutoRequestFocus(true); // Ensure dialog receives focus when shown

                JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
                mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
                mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

                // Content panel with form fields
                JPanel contentPanel = new JPanel();
                contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
                contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

                // Item name label
                JLabel itemLabel = new JLabel("Item: " + itemName);
                itemLabel.setForeground(Color.WHITE);
                itemLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(itemLabel);
                contentPanel.add(Box.createVerticalStrut(15));

                // Collateral value
                JPanel collateralPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                collateralPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
                JLabel collateralLabel = new JLabel("Collateral (GP):");
                collateralLabel.setForeground(Color.WHITE);

                // Use the item price that was fetched on client thread
                SpinnerNumberModel collateralModel = new SpinnerNumberModel(itemPrice, 0, Integer.MAX_VALUE, 100000);
                JSpinner collateralSpinner = new JSpinner(collateralModel);
                collateralSpinner.setPreferredSize(new Dimension(150, 25));

                collateralPanel.add(collateralLabel);
                collateralPanel.add(collateralSpinner);
                collateralPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(collateralPanel);
                contentPanel.add(Box.createVerticalStrut(5));

                // Percentage multiplier for collateral
                JPanel percentagePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                percentagePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
                JLabel percentageLabel = new JLabel("Collateral % (100% = item price):");
                percentageLabel.setForeground(Color.WHITE);
                SpinnerNumberModel percentageModel = new SpinnerNumberModel(100, 0, 1000, 10);
                JSpinner percentageSpinner = new JSpinner(percentageModel);
                percentageSpinner.setPreferredSize(new Dimension(80, 25));

                // Update collateral when percentage changes
                percentageSpinner.addChangeListener(e -> {
                    int percentage = (int) percentageSpinner.getValue();
                    int newCollateral = (int) (itemPrice * (percentage / 100.0));
                    collateralSpinner.setValue(newCollateral);
                    log.info("Percentage changed to {}%, new collateral: {}", percentage, newCollateral);
                });

                percentagePanel.add(percentageLabel);
                percentagePanel.add(percentageSpinner);
                percentagePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(percentagePanel);
                contentPanel.add(Box.createVerticalStrut(10));

                // Duration with "No Duration" checkbox
                JPanel durationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
                durationPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
                JLabel durationLabel = new JLabel("Duration (hours):");
                durationLabel.setForeground(Color.WHITE);
                SpinnerNumberModel durationModel = new SpinnerNumberModel(24, 1, 168, 1);
                JSpinner durationSpinner = new JSpinner(durationModel);
                durationSpinner.setPreferredSize(new Dimension(100, 25));

                JCheckBox noDurationCheckbox = new JCheckBox("No Duration (Keep Until Removed)");
                noDurationCheckbox.setBackground(ColorScheme.DARK_GRAY_COLOR);
                noDurationCheckbox.setForeground(Color.WHITE);
                noDurationCheckbox.addActionListener(e -> {
                    durationSpinner.setEnabled(!noDurationCheckbox.isSelected());
                });

                durationPanel.add(durationLabel);
                durationPanel.add(durationSpinner);
                durationPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(durationPanel);
                contentPanel.add(Box.createVerticalStrut(5));

                // Add checkbox on its own line
                noDurationCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(noDurationCheckbox);
                contentPanel.add(Box.createVerticalStrut(10));

                // Notes
                JLabel notesLabel = new JLabel("Notes (optional):");
                notesLabel.setForeground(Color.WHITE);
                notesLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(notesLabel);
                contentPanel.add(Box.createVerticalStrut(5));

                JTextArea notesArea = new JTextArea(3, 30);
                notesArea.setLineWrap(true);
                notesArea.setWrapStyleWord(true);
                JScrollPane notesScroll = new JScrollPane(notesArea);
                notesScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
                contentPanel.add(notesScroll);

                mainPanel.add(contentPanel, BorderLayout.CENTER);

                // Buttons
                JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
                buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

                JButton addButton = new JButton("Add to Marketplace");
                // FIXED: Ensure button can receive input events properly
                addButton.setFocusable(true);
                addButton.setEnabled(true);
                addButton.setRequestFocusEnabled(true);

                addButton.addActionListener(e -> {
                    try
                    {
                        log.info("=== Add to Marketplace button clicked ===");

                        // Verify group still exists
                        if (currentGroupId == null || currentGroupId.isEmpty())
                        {
                            log.error("ERROR: No group ID available");
                            return;
                        }

                        int collateralValue = (int) collateralSpinner.getValue();
                        int duration = noDurationCheckbox.isSelected() ? 0 : (int) durationSpinner.getValue();
                        String notes = notesArea.getText().trim();

                        log.info("Values - Collateral: {}, Duration: {}, Notes: {}", collateralValue, duration, notes);

                        // FIXED: Show confirmation dialog before adding to marketplace
                        int gePrice = itemPrice > 0 ? itemPrice : itemManager.getItemPrice(itemId);
                        StringBuilder lendSummary = new StringBuilder();
                        lendSummary.append("Item: ").append(itemName).append("\n");
                        lendSummary.append("GE Value: ").append(net.runelite.client.util.QuantityFormatter.quantityToStackSize(gePrice)).append(" GP\n");
                        if (collateralValue > 0) {
                            lendSummary.append("Collateral: ").append(collateralValue).append(" GP\n");
                        }
                        if (noDurationCheckbox.isSelected()) {
                            lendSummary.append("Duration: No limit\n");
                        } else {
                            lendSummary.append("Duration: ").append(duration).append(" hours\n");
                        }
                        if (!notes.isEmpty()) {
                            lendSummary.append("Notes: ").append(notes).append("\n");
                        }
                        lendSummary.append("\nAdd this item to the marketplace?");

                        int lendConfirm = javax.swing.JOptionPane.showConfirmDialog(dialog,
                            lendSummary.toString(),
                            "Confirm Marketplace Listing",
                            javax.swing.JOptionPane.YES_NO_OPTION,
                            javax.swing.JOptionPane.QUESTION_MESSAGE);

                        if (lendConfirm != javax.swing.JOptionPane.YES_OPTION) {
                            return; // User cancelled
                        }

                        // Get current player name
                        String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
                        log.info("Player name: {}", playerName);

                        // Create lending entry
                        log.info("Creating lending entry...");
                        LendingEntry lendOffer = new LendingEntry();
                        lendOffer.setLender(playerName);
                        lendOffer.setBorrower("");
                        lendOffer.setItem(itemName);
                        lendOffer.setItemId(itemId);
                        lendOffer.setQuantity(1);
                        lendOffer.setCollateralValue(collateralValue);
                        lendOffer.setCollateralType(collateralValue > 0 ? "GP" : "none");
                        lendOffer.setNotes(notes);
                        // FIXED: Set GE price from ItemManager so marketplace shows actual value
                        lendOffer.setValue(itemPrice > 0 ? itemPrice : itemManager.getItemPrice(itemId));
                        lendOffer.setGroupId(currentGroupId);
                        lendOffer.setLendTime(System.currentTimeMillis());
                        lendOffer.setDueTime(noDurationCheckbox.isSelected() ?
                            Long.MAX_VALUE : System.currentTimeMillis() + (duration * 3600000L));
                        lendOffer.setReturnedAt(0L);
                        lendOffer.setId(java.util.UUID.randomUUID().toString());

                        log.info("Created lending entry ID: {}", lendOffer.getId());

                        // Save to marketplace
                        log.info("Calling recorder.addAvailable...");
                        recorder.addAvailable(currentGroupId, playerName, lendOffer);
                        log.info("Successfully saved to recorder - item added to marketplace!");

                        dialog.dispose();
                        log.info("Dialog disposed");

                        // FIXED: Refresh the new LendingPanel (not the old null panel)
                        if (newPanel != null)
                        {
                            log.info("Refreshing newPanel after right-click add...");
                            SwingUtilities.invokeLater(() -> newPanel.refresh());
                        }

                        // Show success message on client thread
                        clientThread.invokeLater(() ->
                            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Added " + itemName + " to group marketplace", "")
                        );
                    }
                    catch (Exception ex)
                    {
                        log.error("ERROR in Add to Marketplace button", ex);
                        ex.printStackTrace();
                    }
                });

                JButton cancelButton = new JButton("Cancel");
                cancelButton.addActionListener(e -> dialog.dispose());

                buttonPanel.add(addButton);
                buttonPanel.add(cancelButton);

                mainPanel.add(buttonPanel, BorderLayout.SOUTH);

                dialog.add(mainPanel);

                // FIXED: Set default button for Enter key support
                dialog.getRootPane().setDefaultButton(addButton);

                log.info("Showing dialog...");
                dialog.setVisible(true);

                // FIXED: Request focus for the dialog after it's visible
                dialog.toFront();
                dialog.requestFocus();

                log.info("Dialog shown successfully");
        }
        catch (Exception e)
        {
            log.error("CRITICAL ERROR creating lend dialog", e);
            final String errorMsg = "ERROR: Could not open lend dialog - " + e.getMessage();
            clientThread.invokeLater(() ->
                client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", errorMsg, "")
            );
        }
    }

    /**
     * Show borrow item dialog (right-click from GE/examine)
     */
    private void showBorrowItemDialog(String itemName, int itemId)
    {
        // Delegate to panel to show the dialog
        if (panel != null)
        {
            panel.showBorrowItemDialog(itemName, itemId);
        }
    }

    /**
     * Show invite dialog for player
     */
    private void showInviteDialog(String playerName, String inviteMessage) {
        JDialog messageDialog = new JDialog();
        messageDialog.setTitle("Send Invite to " + playerName);
        messageDialog.setModal(true);
        messageDialog.setSize(400, 200);
        messageDialog.setLocationRelativeTo(null);
        
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(10, 10, 10, 10);
        JLabel instructionLabel = new JLabel("<html>Send this private message to <b>" + playerName + "</b>:</html>");
        instructionLabel.setForeground(Color.WHITE);
        panel.add(instructionLabel, c);
        
        c.gridy = 1; c.insets = new Insets(5, 10, 10, 10);
        JTextField messageField = new JTextField(inviteMessage);
        messageField.setPreferredSize(new Dimension(350, 25));
        messageField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        messageField.setForeground(Color.WHITE);
        messageField.setEditable(false);
        messageField.selectAll();
        panel.add(messageField, c);
        
        c.gridy = 2; c.gridwidth = 1; c.insets = new Insets(10, 10, 10, 5);
        JButton copyButton = new JButton("Copy Message");
        copyButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        copyButton.setForeground(Color.WHITE);
        copyButton.addActionListener(e -> {
            java.awt.datatransfer.StringSelection stringSelection = new java.awt.datatransfer.StringSelection(inviteMessage);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
            JOptionPane.showMessageDialog(messageDialog, "Message copied to clipboard!", "Copied", JOptionPane.INFORMATION_MESSAGE);
        });
        panel.add(copyButton, c);
        
        c.gridx = 1; c.insets = new Insets(10, 5, 10, 10);
        JButton closeButton = new JButton("Close");
        closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        closeButton.setForeground(Color.WHITE);
        closeButton.addActionListener(e -> messageDialog.dispose());
        panel.add(closeButton, c);
        
        messageDialog.add(panel);
        messageDialog.setVisible(true);
    }
    
    private void showNotification(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE);
        });
    }
    
    /**
     * Handle private messages for group invite processing
     */
    private void handlePrivateMessage(String sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        
        // Check if message contains an invite code pattern
        if (message.toLowerCase().contains("join") && message.toLowerCase().contains("code:")) {
            processInviteRequest(sender, message);
        }
    }
    
    /**
     * Process incoming group invite requests
     */
    private void processInviteRequest(String sender, String message) {
        // Extract invite code from message (format: "Join my lending group 'GroupName' with code: XXXXXXXX")
        String inviteCode = extractInviteCodeFromMessage(message);
        if (inviteCode == null || inviteCode.length() != 8) {
            return; // Invalid invite code format
        }
        
        // Find group with matching invite code
        LendingGroup targetGroup = null;
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            if (inviteCode.equals(group.getInviteCode())) {
                targetGroup = group;
                break;
            }
        }
        
        if (targetGroup == null) {
            return; // No group found with this invite code
        }
        
        // Check if current user is owner or admin of the target group
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) {
            return;
        }
        
        boolean canAcceptInvites = groupConfigStore.isOwner(targetGroup.getId(), currentPlayer) ||
                                  groupConfigStore.isAdmin(targetGroup.getId(), currentPlayer);
        
        if (!canAcceptInvites) {
            return; // User doesn't have permission to accept invites for this group
        }
        
        // Check if sender is already a member
        if (targetGroup.hasMember(sender)) {
            showNotification("Already Member", sender + " is already a member of " + targetGroup.getName());
            return;
        }
        
        // Show invite acceptance dialog
        final LendingGroup finalTargetGroup = targetGroup;
        SwingUtilities.invokeLater(() -> {
            showInviteAcceptanceDialog(sender, finalTargetGroup);
        });
    }
    
    /**
     * Extract invite code from private message
     */
    private String extractInviteCodeFromMessage(String message) {
        // Look for pattern "code: XXXXXXXX" 
        String[] parts = message.split("code:");
        if (parts.length >= 2) {
            String codePart = parts[1].trim();
            // Extract first 8 characters (assuming invite codes are 8 chars)
            String[] codeWords = codePart.split("\\s+");
            if (codeWords.length > 0 && codeWords[0].length() >= 8) {
                return codeWords[0].substring(0, 8);
            }
        }
        return null;
    }
    
    /**
     * Show dialog to accept or deny group invite request
     */
    private void showInviteAcceptanceDialog(String playerName, LendingGroup group) {
        int result = JOptionPane.showConfirmDialog(
            null,
            "Player '" + playerName + "' wants to join group '" + group.getName() + "'.\n\nAccept invite?",
            "Group Invite Request",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            // Add player to group as regular member
            groupConfigStore.addMember(group.getId(), playerName, "member");
            showNotification("Member Added", playerName + " has been added to " + group.getName());
            
            // FIXED: Refresh the new LendingPanel to show updated member list
            if (newPanel != null) {
                newPanel.refresh();
            }
        }
    }

    /**
     * Check wilderness status for borrowed items protection
     */
    private void checkWildernessStatus() {
        if (client.getVarbitValue(Varbits.IN_WILDERNESS) == 1) {
            checkBorrowedItemsInWilderness();
        }
    }
    
    /**
     * Handle wilderness state changes
     */
    private void handleWildernessChange() {
        boolean inWilderness = client.getVarbitValue(Varbits.IN_WILDERNESS) == 1;
        
        if (inWilderness) {
            checkBorrowedItemsInWilderness();
        }
    }
    
    /**
     * Check for borrowed items when entering wilderness
     */
    private void checkBorrowedItemsInWilderness() {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return;
        
        // Get all active borrowed items for current player
        List<LendingEntry> borrowedItems = new ArrayList<>();
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            List<LendingEntry> groupBorrowed = recorder.getBorrowed(group.getId());
            borrowedItems.addAll(groupBorrowed.stream()
                .filter(entry -> currentPlayer.equals(entry.getBorrower()) && !entry.isReturned())
                .collect(Collectors.toList()));
        }
        
        if (!borrowedItems.isEmpty()) {
            showWildernessRiskWarning(borrowedItems);
        }
    }
    
    /**
     * Show wilderness risk warning for borrowed items
     */
    private void showWildernessRiskWarning(List<LendingEntry> borrowedItems) {
        // Log the wilderness warning event
        StringBuilder itemList = new StringBuilder();
        long totalValue = 0;
        for (LendingEntry item : borrowedItems) {
            itemList.append(item.getItem()).append(" (from ").append(item.getLender()).append("), ");
            totalValue += item.getValue();
        }
        
        String warningDetails = String.format("Entered wilderness with %d borrowed items: %s Total value: %s GP", 
            borrowedItems.size(), itemList.toString(), QuantityFormatter.quantityToStackSize(totalValue));
        logWarning("WILDERNESS_ALERT", warningDetails, "All lenders");
        
        // Show subtle minimap warning instead of modal dialog
        if (minimapWarningOverlay != null) {
            minimapWarningOverlay.showWildernessWarning(borrowedItems);
        }
    }
    
    /**
     * Monitor Grand Exchange usage with borrowed items
     */
    private void monitorGrandExchangeUsage() {
        // Check if GE interface is open
        Widget geWidget = client.getWidget(465, 2); // GE main interface
        if (geWidget != null && !geWidget.isHidden()) {
            List<LendingEntry> borrowedItems = getBorrowedItemsInInventory();
            if (!borrowedItems.isEmpty()) {
                // Show warning once per GE session
                String sessionKey = "ge_session_" + System.currentTimeMillis() / 30000; // 30 second sessions
                if (!shownWarnings.contains(sessionKey)) {
                    shownWarnings.add(sessionKey);
                    
                    // Use subtle minimap warning instead of modal dialog
                    if (minimapWarningOverlay != null) {
                        minimapWarningOverlay.showGrandExchangeWarning(borrowedItems);
                    }
                }
            }
        }
    }
    
    /**
     * Monitor trade activity with borrowed items
     */
    private void monitorTradeActivity() {
        // Check if trade interface is open
        Widget tradeWidget = client.getWidget(335, 3); // Trade interface
        if (tradeWidget != null && !tradeWidget.isHidden()) {
            List<LendingEntry> borrowedItems = getBorrowedItemsInInventory();
            if (!borrowedItems.isEmpty()) {
                // Get trade partner name
                String tradePartner = currentTradePartner;
                if (tradePartner == null) {
                    tradePartner = "Unknown Player";
                }
                
                // Show warning once per trade session
                String sessionKey = "trade_session_" + tradePartner + "_" + System.currentTimeMillis() / 30000;
                if (!shownWarnings.contains(sessionKey)) {
                    shownWarnings.add(sessionKey);
                    
                    // Use subtle minimap warning
                    if (minimapWarningOverlay != null) {
                        minimapWarningOverlay.showTradeWarning(borrowedItems, tradePartner);
                    }
                }
            }
        }
    }
    
    /**
     * Monitor drop actions with borrowed items
     */
    private void monitorDropActions() {
        // This monitors for drop menu actions via MenuOptionClicked
        // Implementation will be enhanced in the MenuOptionClicked subscriber
    }
    
    /**
     * Get borrowed items currently in player's inventory
     */
    private List<LendingEntry> getBorrowedItemsInInventory() {
        List<LendingEntry> borrowedItems = new ArrayList<>();
        
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return borrowedItems;
        
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) return borrowedItems;
        
        // Check each inventory item against borrowed items
        for (Item invItem : inventory.getItems()) {
            if (invItem.getId() <= 0) continue;
            
            // Check all groups for borrowed items
            for (LendingGroup group : groupConfigStore.getAllGroups()) {
                List<LendingEntry> borrowed = recorder.getBorrowed(group.getId());
                for (LendingEntry entry : borrowed) {
                    if (entry.getItemId() == invItem.getId() && 
                        currentPlayer.equals(entry.getBorrower()) && 
                        !entry.isReturned()) {
                        borrowedItems.add(entry);
                        break; // Found this item, no need to check more
                    }
                }
            }
        }
        
        return borrowedItems;
    }
    
    /**
     * Handle drop action detection
     */
    private void handleDropAction(MenuOptionClicked event) {
        // Check if any borrowed items are being dropped
        List<LendingEntry> borrowedItems = getBorrowedItemsInInventory();
        if (!borrowedItems.isEmpty()) {
            // Show immediate warning for drop actions
            if (minimapWarningOverlay != null) {
                // Filter for items that might be the one being dropped
                // This is a simplified check - more sophisticated item tracking could be added
                minimapWarningOverlay.showDropWarning(borrowedItems);
            }
        }
    }
    
    /**
     * Check for borrowed items in inventory during high-risk activities
     */
    private void checkBorrowedItemsInInventory(String activity) {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return;
        
        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        if (inventory == null) return;
        
        List<LendingEntry> riskyBorrowedItems = new ArrayList<>();
        
        // Check each inventory item against borrowed items
        for (Item invItem : inventory.getItems()) {
            if (invItem.getId() <= 0) continue;
            
            for (LendingGroup group : groupConfigStore.getAllGroups()) {
                List<LendingEntry> borrowed = recorder.getBorrowed(group.getId());
                
                borrowed.stream()
                    .filter(entry -> currentPlayer.equals(entry.getBorrower()) && 
                                   !entry.isReturned() && 
                                   entry.getItemId() == invItem.getId())
                    .forEach(riskyBorrowedItems::add);
            }
        }
        
        if (!riskyBorrowedItems.isEmpty()) {
            showActivityRiskWarning(activity, riskyBorrowedItems);
        }
    }
    
    /**
     * Show risk warning for high-risk activities with borrowed items
     */
    private void showActivityRiskWarning(String activity, List<LendingEntry> borrowedItems) {
        String activityName = activity.replace("_", " ");
        
        StringBuilder message = new StringBuilder();
        message.append("ALERT: BORROWED ITEM DETECTED!\n\n");
        message.append("You are attempting to use ").append(activityName)
               .append(" with borrowed items:\n\n");
        
        for (LendingEntry item : borrowedItems) {
            message.append("• ").append(item.getItem())
                   .append(" (borrowed from ").append(item.getLender()).append(")\n");
        }
        
        message.append("\nUsing borrowed items in ").append(activityName)
               .append(" may violate lending agreements.\n")
               .append("Contact the lender before proceeding!");
        
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                null,
                message.toString(),
                "Borrowed Item Alert",
                JOptionPane.WARNING_MESSAGE
            );
            
            // Log the risk event
            logRiskEvent(borrowedItems.get(0).getLender(), activity + "_RISK", 
                "Player attempted " + activityName + " with " + borrowedItems.size() + " borrowed items");
        });
    }
    
    /**
     * Handle trade interface opening
     */
    private void handleTradeInterfaceOpened() {
        // Get trade partner name
        Widget tradePartnerWidget = client.getWidget(335, 31); // Trade partner name widget
        if (tradePartnerWidget != null) {
            currentTradePartner = tradePartnerWidget.getText();
            tradeItems.clear();
            log.debug("Trade opened with: {}", currentTradePartner);
        }
    }
    
    /**
     * Handle trade interface closing
     */
    private void handleTradeInterfaceClosed() {
        if (currentTradePartner != null && !tradeItems.isEmpty()) {
            processPotentialLendingTrade();
        }
        currentTradePartner = null;
        tradeItems.clear();
    }
    
    /**
     * Handle changes in trade container
     */
    private void handleTradeContainerChange(ItemContainerChanged event) {
        if (currentTradePartner == null) {
            return;
        }
        
        // Track items being traded
        ItemContainer container = event.getItemContainer();
        if (container != null) {
            tradeItems.clear();
            for (Item item : container.getItems()) {
                if (item.getId() > 0 && item.getQuantity() > 0) {
                    tradeItems.put(item.getId(), item.getQuantity());
                }
            }
        }
    }
    
    /**
     * Process potential lending trade between group members
     */
    private void processPotentialLendingTrade() {
        if (currentTradePartner == null || tradeItems.isEmpty()) {
            return;
        }
        
        // Check if trade partner is in any of the user's groups
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        if (activeGroup == null || !activeGroup.hasMember(currentTradePartner)) {
            return; // Not trading with a group member
        }
        
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) {
            return;
        }
        
        // Check if any of the traded items are on the lending lists
        List<String> potentialLendings = new ArrayList<>();
        List<String> potentialBorrowings = new ArrayList<>();
        
        for (Map.Entry<Integer, Integer> tradeItem : tradeItems.entrySet()) {
            String itemName = itemManager.getItemComposition(tradeItem.getKey()).getName();
            
            // Check if item is on available list (potential lending)
            List<LendingEntry> availableItems = recorder.getAvailable(activeGroup.getId());
            boolean isOnAvailableList = availableItems.stream()
                .anyMatch(entry -> entry.getItemId() == tradeItem.getKey() && 
                         currentPlayer.equals(entry.getLender()));
            
            if (isOnAvailableList) {
                potentialLendings.add(itemName + " x" + tradeItem.getValue());
            }
            
            // Check if item is lent to this player (potential return)
            List<LendingEntry> lentItems = recorder.getLent(activeGroup.getId());
            boolean isLentToPartner = lentItems.stream()
                .anyMatch(entry -> entry.getItemId() == tradeItem.getKey() && 
                         currentTradePartner.equals(entry.getBorrower()) &&
                         currentPlayer.equals(entry.getLender()));
            
            if (isLentToPartner) {
                potentialBorrowings.add(itemName + " x" + tradeItem.getValue());
            }
        }
        
        // Show lending/borrowing confirmation dialog
        if (!potentialLendings.isEmpty() || !potentialBorrowings.isEmpty()) {
            SwingUtilities.invokeLater(() -> {
                showLendingTradeConfirmationDialog(currentTradePartner, potentialLendings, potentialBorrowings);
            });
        }
    }
    
    /**
     * Show dialog to confirm lending/borrowing trade
     */
    private void showLendingTradeConfirmationDialog(String partner, List<String> lendings, List<String> borrowings) {
        StringBuilder message = new StringBuilder();
        message.append("Trade detected with group member ").append(partner).append(":\n\n");
        
        if (!lendings.isEmpty()) {
            message.append("POTENTIAL LENDINGS:\n");
            for (String item : lendings) {
                message.append("• ").append(item).append("\n");
            }
            message.append("\n");
        }
        
        if (!borrowings.isEmpty()) {
            message.append("POTENTIAL RETURNS:\n");
            for (String item : borrowings) {
                message.append("• ").append(item).append("\n");
            }
            message.append("\n");
        }
        
        message.append("Is this a lending/borrowing transaction?");
        
        int result = JOptionPane.showConfirmDialog(
            null,
            message.toString(),
            "Lending Transaction Detected",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            // Check collateral requirements first
            if (!lendings.isEmpty()) {
                verifyCollateralForLending(partner, lendings, borrowings);
            } else {
                processConfirmedLendingTrade(partner, lendings, borrowings);
            }
        }
    }
    
    /**
     * Verify collateral requirements for lending transaction
     */
    private void verifyCollateralForLending(String partner, List<String> lendings, List<String> borrowings) {
        // Calculate total lending value
        long totalLendingValue = 0;
        for (String lendingStr : lendings) {
            String[] parts = lendingStr.split(" x");
            if (parts.length == 2) {
                String itemName = parts[0];
                try {
                    int quantity = Integer.parseInt(parts[1]);
                    // Get item value from ItemManager
                    List<ItemPrice> prices = itemManager.search(itemName);
                    if (!prices.isEmpty()) {
                        totalLendingValue += prices.get(0).getPrice() * quantity;
                    }
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse quantity from: {}", lendingStr);
                }
            }
        }
        
        if (totalLendingValue == 0) {
            processConfirmedLendingTrade(partner, lendings, borrowings);
            return;
        }
        
        // Show collateral verification dialog
        showCollateralVerificationDialog(partner, lendings, borrowings, totalLendingValue);
    }
    
    /**
     * Show collateral verification dialog
     */
    private void showCollateralVerificationDialog(String partner, List<String> lendings, List<String> borrowings, long totalValue) {
        JDialog collateralDialog = new JDialog();
        collateralDialog.setTitle("Collateral Verification");
        collateralDialog.setModal(true);
        collateralDialog.setSize(500, 300);
        collateralDialog.setLocationRelativeTo(null);
        
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        
        // Title
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(10, 10, 10, 10);
        JLabel titleLabel = new JLabel("<html><b>Lending " + QuantityFormatter.quantityToStackSize(totalValue) + " GP worth of items to " + partner + "</b></html>");
        titleLabel.setForeground(Color.WHITE);
        mainPanel.add(titleLabel, c);
        
        // Collateral options
        c.gridy = 1; c.insets = new Insets(5, 10, 5, 10);
        JLabel collateralLabel = new JLabel("Select collateral requirement:");
        collateralLabel.setForeground(Color.WHITE);
        mainPanel.add(collateralLabel, c);
        
        c.gridy = 2;
        String[] collateralOptions = {
            "Equal GE Value (" + QuantityFormatter.quantityToStackSize(totalValue) + " GP)",
            "Custom Amount",
            "Items/Trust",
            "No Collateral (HIGH RISK)"
        };
        JComboBox<String> collateralCombo = new JComboBox<>(collateralOptions);
        collateralCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        collateralCombo.setForeground(Color.WHITE);
        mainPanel.add(collateralCombo, c);
        
        // Custom amount field
        c.gridy = 3;
        JTextField customAmountField = new JTextField();
        customAmountField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        customAmountField.setForeground(Color.WHITE);
        customAmountField.setPreferredSize(new Dimension(200, 25));
        customAmountField.setVisible(false);
        mainPanel.add(customAmountField, c);
        
        // Risk acknowledgment checkbox
        c.gridy = 4;
        JCheckBox riskCheckBox = new JCheckBox("I acknowledge the risk of lending without adequate collateral");
        riskCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        riskCheckBox.setForeground(Color.YELLOW);
        riskCheckBox.setVisible(false);
        mainPanel.add(riskCheckBox, c);
        
        // Collateral combo listener
        collateralCombo.addActionListener(e -> {
            String selected = (String) collateralCombo.getSelectedItem();
            customAmountField.setVisible("Custom Amount".equals(selected));
            riskCheckBox.setVisible(selected != null && selected.contains("No Collateral"));
            collateralDialog.revalidate();
        });
        
        // Button panel
        c.gridy = 5; c.insets = new Insets(20, 10, 10, 10);
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton proceedButton = new JButton("Proceed with Lending");
        proceedButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        proceedButton.setForeground(Color.WHITE);
        proceedButton.addActionListener(e -> {
            String selectedCollateral = (String) collateralCombo.getSelectedItem();
            
            // Validate risk acknowledgment
            if (selectedCollateral != null && selectedCollateral.contains("No Collateral") && !riskCheckBox.isSelected()) {
                JOptionPane.showMessageDialog(collateralDialog, 
                    "You must acknowledge the risk to proceed without collateral", 
                    "Risk Acknowledgment Required", 
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            // Process the lending with collateral info
            processLendingWithCollateral(partner, lendings, borrowings, selectedCollateral, customAmountField.getText());
            collateralDialog.dispose();
        });
        buttonPanel.add(proceedButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> collateralDialog.dispose());
        buttonPanel.add(cancelButton);
        
        mainPanel.add(buttonPanel, c);
        
        collateralDialog.add(mainPanel);
        collateralDialog.setVisible(true);
    }
    
    /**
     * Process lending with collateral information
     */
    private void processLendingWithCollateral(String partner, List<String> lendings, List<String> borrowings, String collateralType, String customAmount) {
        // Store collateral agreement
        CollateralAgreement agreement = new CollateralAgreement();
        // Create associated loan entry for the agreement
        LendingEntry loanEntry = new LendingEntry();
        loanEntry.setBorrower(partner);
        agreement.setAssociatedLoan(loanEntry);
        agreement.setCollateralDescription(collateralType);
        agreement.setAgreementTime(Instant.now().toEpochMilli());
        
        if ("Custom Amount".equals(collateralType) && !customAmount.isEmpty()) {
            try {
                long amount = Long.parseLong(customAmount.replaceAll("[^0-9]", ""));
                agreement.setCollateralDescription(collateralType + " (" + amount + " GP)");
            } catch (NumberFormatException e) {
                log.warn("Failed to parse custom collateral amount: {}", customAmount);
            }
        }
        
        collateralAgreements.put(partner.toLowerCase(), agreement);
        
        // Process the lending transaction
        processConfirmedLendingTrade(partner, lendings, borrowings);
        
        showNotification("Collateral Recorded", "Collateral agreement with " + partner + " has been recorded");
    }
    
    /**
     * Process confirmed lending trade
     */
    private void processConfirmedLendingTrade(String partner, List<String> lendings, List<String> borrowings) {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return;
        
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        if (activeGroup == null) return;
        
        // Process lendings
        for (String lendingStr : lendings) {
            // Extract item details and create lending entry
            String[] parts = lendingStr.split(" x");
            if (parts.length == 2) {
                String itemName = parts[0];
                try {
                    int quantity = Integer.parseInt(parts[1]);
                    
                    LendingEntry entry = new LendingEntry();
                    entry.setId(UUID.randomUUID().toString());
                    entry.setItem(itemName);
                    entry.setQuantity(quantity);
                    entry.setLender(currentPlayer);
                    entry.setBorrower(partner);
                    entry.setLendTime(Instant.now().toEpochMilli());
                    entry.setGroupId(activeGroup.getId());
                    
                    recorder.markAsLent(activeGroup.getId(), currentPlayer, partner, entry, 
                        Instant.now().plus(7, ChronoUnit.DAYS).toEpochMilli()); // Default 7 days
                    
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse quantity from: {}", lendingStr);
                }
            }
        }
        
        // Process returns
        for (String returnStr : borrowings) {
            String[] parts = returnStr.split(" x");
            if (parts.length == 2) {
                String itemName = parts[0];
                recorder.recordReturn(activeGroup.getId(), partner, itemName);
            }
        }
        
        // FIXED: Refresh the new LendingPanel
        if (newPanel != null) {
            newPanel.refresh();
        }

        showNotification("Trade Processed", "Lending transaction has been recorded");
    }
    
    // =============== RISK MONITORING & LOGGING SYSTEM ===============
    
    private final List<RiskLogEntry> riskLog = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN = 300000; // 5 minutes
    
    /**
     * Log risk events and notify group owners/admins
     */
    private void logRiskEvent(String affectedPlayer, String eventType, String description) {
        RiskLogEntry logEntry = new RiskLogEntry(
            System.currentTimeMillis(),
            client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown",
            affectedPlayer,
            eventType,
            description
        );
        
        riskLog.add(logEntry);
        
        // Keep only last 1000 entries
        if (riskLog.size() > 1000) {
            riskLog.remove(0);
        }
        
        // Save to config
        saveRiskLog();
        
        // Notify group owners/admins if this is a suspicious event
        if (isSuspiciousEvent(eventType)) {
            notifyGroupOwnersOfRisk(logEntry);
        }
        
        log.info("Risk event logged: {} - {}", eventType, description);
    }
    
    /**
     * Check if an event is suspicious and requires owner/admin notification
     */
    private boolean isSuspiciousEvent(String eventType) {
        return eventType.contains("UNAUTHORIZED") || 
               eventType.contains("VIOLATION") || 
               eventType.contains("MALICIOUS") ||
               eventType.contains("GRAND_EXCHANGE_RISK");
    }
    
    /**
     * Notify group owners and admins of suspicious activity
     */
    private void notifyGroupOwnersOfRisk(RiskLogEntry logEntry) {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return;
        
        // Check alert cooldown to prevent spam
        String cooldownKey = logEntry.getAffectedPlayer() + "_" + logEntry.getEventType();
        Long lastAlert = lastAlertTime.get(cooldownKey);
        if (lastAlert != null && (System.currentTimeMillis() - lastAlert) < ALERT_COOLDOWN) {
            return; // Still in cooldown period
        }
        
        lastAlertTime.put(cooldownKey, System.currentTimeMillis());
        
        // Find groups where current player is owner/admin and affected player is a member
        for (LendingGroup group : groupConfigStore.getAllGroups()) {
            boolean currentPlayerIsOwnerOrAdmin = groupConfigStore.isOwner(group.getId(), currentPlayer) ||
                                                 groupConfigStore.isAdmin(group.getId(), currentPlayer);
            
            if (currentPlayerIsOwnerOrAdmin && group.hasMember(logEntry.getAffectedPlayer())) {
                SwingUtilities.invokeLater(() -> {
                    showMaliciousActivityAlert(group, logEntry);
                });
                break; // Only show one alert per event
            }
        }
    }
    
    /**
     * Show malicious activity alert to group owners/admins
     */
    private void showMaliciousActivityAlert(LendingGroup group, RiskLogEntry logEntry) {
        JDialog alertDialog = new JDialog();
        alertDialog.setTitle("WARNING: SUSPICIOUS ACTIVITY DETECTED");
        alertDialog.setModal(true);
        alertDialog.setSize(500, 300);
        alertDialog.setLocationRelativeTo(null);
        alertDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        
        // Alert header
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(15, 15, 10, 15);
        JLabel headerLabel = new JLabel("WARNING: SUSPICIOUS ACTIVITY ALERT");
        headerLabel.setForeground(Color.RED);
        headerLabel.setFont(headerLabel.getFont().deriveFont(16f));
        mainPanel.add(headerLabel, c);
        
        // Group info
        c.gridy = 1; c.insets = new Insets(5, 15, 5, 15);
        JLabel groupLabel = new JLabel("Group: " + group.getName());
        groupLabel.setForeground(Color.WHITE);
        mainPanel.add(groupLabel, c);
        
        // Affected player
        c.gridy = 2;
        JLabel playerLabel = new JLabel("Player: " + logEntry.getAffectedPlayer());
        playerLabel.setForeground(Color.YELLOW);
        mainPanel.add(playerLabel, c);
        
        // Event type
        c.gridy = 3;
        JLabel eventLabel = new JLabel("Event: " + logEntry.getEventType().replace("_", " "));
        eventLabel.setForeground(Color.ORANGE);
        mainPanel.add(eventLabel, c);
        
        // Description
        c.gridy = 4; c.insets = new Insets(10, 15, 10, 15);
        JTextArea descArea = new JTextArea(logEntry.getDescription());
        descArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        descArea.setForeground(Color.WHITE);
        descArea.setEditable(false);
        descArea.setLineWrap(true);
        descArea.setWrapStyleWord(true);
        descArea.setRows(3);
        JScrollPane scrollPane = new JScrollPane(descArea);
        mainPanel.add(scrollPane, c);
        
        // Timestamp
        c.gridy = 5; c.insets = new Insets(5, 15, 10, 15);
        JLabel timeLabel = new JLabel("Time: " + new Date(logEntry.getTimestamp()));
        timeLabel.setForeground(Color.LIGHT_GRAY);
        mainPanel.add(timeLabel, c);
        
        // Button panel
        c.gridy = 6; c.insets = new Insets(10, 15, 15, 15);
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton takeActionButton = new JButton("Take Action");
        takeActionButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        takeActionButton.setForeground(Color.WHITE);
        takeActionButton.addActionListener(e -> {
            showActionDialog(group, logEntry);
            alertDialog.dispose();
        });
        buttonPanel.add(takeActionButton);
        
        JButton viewLogButton = new JButton("View Full Log");
        viewLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        viewLogButton.setForeground(Color.WHITE);
        viewLogButton.addActionListener(e -> {
            showRiskLogDialog(group);
            alertDialog.dispose();
        });
        buttonPanel.add(viewLogButton);
        
        JButton dismissButton = new JButton("Dismiss");
        dismissButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        dismissButton.setForeground(Color.WHITE);
        dismissButton.addActionListener(e -> alertDialog.dispose());
        buttonPanel.add(dismissButton);
        
        mainPanel.add(buttonPanel, c);
        
        alertDialog.add(mainPanel);
        alertDialog.setVisible(true);
    }
    
    /**
     * Show action dialog for handling suspicious activity
     */
    private void showActionDialog(LendingGroup group, RiskLogEntry logEntry) {
        String[] options = {
            "Remove from Group",
            "Send Warning Message",
            "Mark as Resolved",
            "Cancel"
        };
        
        int choice = JOptionPane.showOptionDialog(
            null,
            "What action would you like to take against " + logEntry.getAffectedPlayer() + "?",
            "Take Action",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[3]
        );
        
        switch (choice) {
            case 0: // Remove from group
                groupConfigStore.removeMember(group.getId(), logEntry.getAffectedPlayer());
                logRiskEvent(logEntry.getAffectedPlayer(), "REMOVED_FROM_GROUP", 
                    "Player removed from group due to suspicious activity: " + logEntry.getEventType());
                showNotification("Action Taken", logEntry.getAffectedPlayer() + " has been removed from " + group.getName());
                break;
                
            case 1: // Send warning
                String warningMessage = "Warning: Suspicious activity detected in lending group '" + 
                                       group.getName() + "'. Please contact group admin.";
                showNotification("Warning Sent", "Warning message prepared for " + logEntry.getAffectedPlayer() + 
                               ":\n" + warningMessage);
                logRiskEvent(logEntry.getAffectedPlayer(), "WARNING_SENT", 
                    "Warning sent for suspicious activity: " + logEntry.getEventType());
                break;
                
            case 2: // Mark resolved
                logRiskEvent(logEntry.getAffectedPlayer(), "RESOLVED", 
                    "Suspicious activity marked as resolved by group admin");
                showNotification("Resolved", "Activity marked as resolved");
                break;
                
            default: // Cancel
                break;
        }
    }
    
    /**
     * Show full risk log dialog
     */
    private void showRiskLogDialog(LendingGroup group) {
        JDialog logDialog = new JDialog();
        logDialog.setTitle("Risk Activity Log - " + group.getName());
        logDialog.setModal(true);
        logDialog.setSize(700, 500);
        logDialog.setLocationRelativeTo(null);
        
        // Create table with risk log entries
        String[] columnNames = {"Time", "Player", "Event", "Description"};
        Object[][] data = riskLog.stream()
            .filter(entry -> group.hasMember(entry.getAffectedPlayer()))
            .map(entry -> new Object[]{
                new SimpleDateFormat("MM/dd HH:mm").format(new Date(entry.getTimestamp())),
                entry.getAffectedPlayer(),
                entry.getEventType().replace("_", " "),
                entry.getDescription()
            })
            .toArray(Object[][]::new);
        
        JTable logTable = new JTable(data, columnNames);
        logTable.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        logTable.setForeground(Color.WHITE);
        logTable.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        logTable.getTableHeader().setBackground(ColorScheme.DARK_GRAY_COLOR);
        logTable.getTableHeader().setForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(logTable);
        logDialog.add(scrollPane, BorderLayout.CENTER);
        
        JButton closeButton = new JButton("Close");
        closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        closeButton.setForeground(Color.WHITE);
        closeButton.addActionListener(e -> logDialog.dispose());
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.add(closeButton);
        
        logDialog.add(buttonPanel, BorderLayout.SOUTH);
        logDialog.setVisible(true);
    }
    
    /**
     * Save risk log to config
     */
    private void saveRiskLog() {
        try {
            String jsonLog = gson.toJson(riskLog);
            configManager.setConfiguration("lendingtracker", "riskLog", jsonLog);
        } catch (Exception e) {
            log.error("Failed to save risk log", e);
        }
    }
    
    /**
     * Load risk log from config
     */
    private void loadRiskLog() {
        try {
            String jsonLog = configManager.getConfiguration("lendingtracker", "riskLog");
            if (jsonLog != null && !jsonLog.isEmpty()) {
                RiskLogEntry[] entries = gson.fromJson(jsonLog, RiskLogEntry[].class);
                if (entries != null) {
                    riskLog.clear();
                    riskLog.addAll(Arrays.asList(entries));
                }
            }
        } catch (Exception e) {
            log.error("Failed to load risk log", e);
        }
    }
    
    
    /**
     * Get active borrow requests for a group
     */
    public List<BorrowRequest> getActiveRequestsForGroup(String groupId) {
        return borrowRequestService.getActiveRequestsForGroup(groupId);
    }
    
    /**
     * Submit a new borrow request
     */
    public BorrowRequestService.SubmitResult submitBorrowRequest(BorrowRequest request) {
        return borrowRequestService.submitBorrowRequest(request);
    }
    
    /**
     * Accept a borrow request
     */
    public boolean acceptBorrowRequest(String requestId, String responderId, String responseMessage) {
        return borrowRequestService.acceptRequest(requestId, responderId, responseMessage);
    }
    
    /**
     * Decline a borrow request
     */
    public boolean declineBorrowRequest(String requestId, String responderId, String responseMessage) {
        return borrowRequestService.declineRequest(requestId, responderId, responseMessage);
    }
    
    /**
     * Cancel a borrow request
     */
    public boolean cancelBorrowRequest(String requestId, String requesterId) {
        return borrowRequestService.cancelRequest(requestId, requesterId);
    }
    
    /**
     * Complete a borrow request
     */
    public boolean completeBorrowRequest(String requestId) {
        return borrowRequestService.completeRequest(requestId);
    }
    
    /**
     * Get borrow requests by member
     */
    public List<BorrowRequest> getBorrowRequestsByMember(String memberId, String groupId) {
        return borrowRequestService.getRequestsByMember(memberId, groupId);
    }
    
    /**
     * Get formatted request message
     */
    public String formatRequestMessage(BorrowRequest request) {
        return borrowRequestService.formatRequestMessage(request);
    }
    
    /**
     * Get formatted response message
     */
    public String formatResponseMessage(BorrowRequest request, boolean accepted, String customMessage) {
        return borrowRequestService.formatResponseMessage(request, accepted, customMessage);
    }
    
    /**
     * Log a warning event
     */
    public void logWarning(String type, String message, String affectedPlayer) {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
        WarningLogEntry entry = new WarningLogEntry(
            Instant.now().toEpochMilli(),
            currentPlayer,
            affectedPlayer,
            type,
            message
        );
        
        synchronized (warningHistory) {
            warningHistory.add(entry);
            
            // Keep only last 1000 warning entries to prevent memory issues
            if (warningHistory.size() > 1000) {
                warningHistory.subList(0, warningHistory.size() - 1000).clear();
            }
        }
        
        log.info("Warning logged: {} - {}", type, message);
    }
    
    /**
     * Get warning log history
     */
    public List<WarningLogEntry> getWarningHistory() {
        synchronized (warningHistory) {
            return new ArrayList<>(warningHistory);
        }
    }
    
    /**
     * Clear warning log history
     */
    public void clearWarningHistory() {
        synchronized (warningHistory) {
            warningHistory.clear();
        }
        log.info("Warning history cleared");
    }
    
    /**
     * Warning log entry class
     */
    public static class WarningLogEntry {
        private final long timestamp;
        private final String reporter;
        private final String affectedPlayer;
        private final String warningType;
        private final String message;
        
        public WarningLogEntry(long timestamp, String reporter, String affectedPlayer, String warningType, String message) {
            this.timestamp = timestamp;
            this.reporter = reporter;
            this.affectedPlayer = affectedPlayer;
            this.warningType = warningType;
            this.message = message;
        }
        
        public long getTimestamp() { return timestamp; }
        public String getReporter() { return reporter; }
        public String getAffectedPlayer() { return affectedPlayer; }
        public String getWarningType() { return warningType; }
        public String getMessage() { return message; }
        
        public String getFormattedTimestamp() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(timestamp));
        }
    }
    
    /**
     * Handler methods for minimap warning overlay
     */
    public void handleWildernessAcknowledgment(List<LendingEntry> borrowedItems) {
        logRiskEvent(borrowedItems.get(0).getLender(), "WILDERNESS_RISK_ACKNOWLEDGED", 
            "Player acknowledged wilderness risk with " + borrowedItems.size() + " borrowed items");
        logWarning("WILDERNESS_ACCEPTED", "Player acknowledged wilderness risk and continued", "All lenders");
        
        // Create formal agreement with lenders
        requestLenderAgreement(borrowedItems, "WILDERNESS", 
            "Borrower has entered wilderness with your items. Continue lending?");
    }
    
    public void handleWildernessCancel(List<LendingEntry> borrowedItems) {
        logWarning("WILDERNESS_CANCELLED", "Player cancelled action after wilderness warning", "All lenders");
        // Could implement actual cancellation logic here if needed
    }
    
    public void handleTradeAcknowledgment(List<LendingEntry> borrowedItems, String tradePartner) {
        logWarning("TRADE_ACCEPTED", "Player proceeding with trade involving borrowed items", tradePartner);
        
        // Create formal agreement with lenders
        requestLenderAgreement(borrowedItems, "TRADE", 
            "Borrower is trading with " + tradePartner + ". Allow this transaction?");
    }
    
    public void handleTradeCancel(List<LendingEntry> borrowedItems, String tradePartner) {
        logWarning("TRADE_CANCELLED", "Player cancelled trade after warning", tradePartner);
        // Could implement trade cancellation logic here if needed
    }
    
    public void handleGEAcknowledgment(List<LendingEntry> borrowedItems) {
        logWarning("GE_ACCEPTED", "Player using Grand Exchange with borrowed items", "All lenders");
        
        // Create formal agreement with lenders
        requestLenderAgreement(borrowedItems, "GRAND_EXCHANGE", 
            "Borrower is using Grand Exchange with your items. Permit this?");
    }
    
    public void handleGECancel(List<LendingEntry> borrowedItems) {
        logWarning("GE_CANCELLED", "Player cancelled GE usage after warning", "All lenders");
        // Could implement GE interface closing logic here if needed
    }
    
    public void handleDropAcknowledgment(List<LendingEntry> borrowedItems) {
        logWarning("DROP_ACCEPTED", "Player acknowledged drop warning and proceeding", "All lenders");
        
        // This is more serious - immediately alert group admins
        alertGroupAdminsOfViolation(borrowedItems, "DROP", 
            "CRITICAL: Borrower is dropping borrowed items!");
        
        // Also create agreement request (though dropping is usually not acceptable)
        requestLenderAgreement(borrowedItems, "DROP", 
            "CRITICAL: Borrower wants to drop your items. This will result in permanent loss!");
    }
    
    public void handleDropCancel(List<LendingEntry> borrowedItems) {
        logWarning("DROP_CANCELLED", "Player cancelled drop action after warning", "All lenders");
        // Could implement drop cancellation logic here if needed
    }
    
    /**
     * Request formal agreement from lenders for risky actions
     */
    private void requestLenderAgreement(List<LendingEntry> borrowedItems, String actionType, String message) {
        for (LendingEntry item : borrowedItems) {
            String lender = item.getLender();
            if (lender != null) {
                // Create agreement request (this would be stored and shown to lender)
                LenderAgreement agreement = new LenderAgreement(
                    item.getId(),
                    lender,
                    client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown",
                    actionType,
                    message,
                    item.getItem(),
                    System.currentTimeMillis()
                );
                
                // Store agreement for lender to review
                storePendingAgreement(agreement);
                
                // Log the agreement request
                logWarning("AGREEMENT_REQUESTED", 
                    "Agreement requested from " + lender + " for " + actionType + " action", 
                    lender);
            }
        }
    }
    
    /**
     * Alert group admins of serious violations
     */
    private void alertGroupAdminsOfViolation(List<LendingEntry> borrowedItems, String violationType, String message) {
        // Get current group
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null) return;
        
        // Get group admins
        var group = groupConfigStore.getGroup(currentGroupId);
        if (group == null) return;
        
        String borrower = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
        
        // Log admin alert
        logWarning("ADMIN_ALERT", 
            "Group admins alerted of " + violationType + " violation by " + borrower, 
            "Group Admins");
        
        // This would typically send notifications to group admins
        // For now, we'll log it and the group admin can see it in the warning log
        log.warn("ADMIN ALERT: {} is attempting {} with borrowed items in group {}", 
            borrower, violationType, currentGroupId);
    }
    
    /**
     * Store pending agreement for lender review
     */
    private final Map<String, LenderAgreement> pendingAgreements = new ConcurrentHashMap<>();
    
    private void storePendingAgreement(LenderAgreement agreement) {
        pendingAgreements.put(agreement.getId(), agreement);
        log.info("Agreement stored for lender {} regarding {} action", 
            agreement.getLender(), agreement.getActionType());
    }
    
    /**
     * Get pending agreements for a lender
     */
    public List<LenderAgreement> getPendingAgreements(String lenderName) {
        return pendingAgreements.values().stream()
            .filter(agreement -> agreement.getLender().equals(lenderName))
            .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Respond to a lender agreement
     */
    public boolean respondToAgreement(String agreementId, boolean approved, String responseMessage) {
        LenderAgreement agreement = pendingAgreements.get(agreementId);
        if (agreement == null) {
            return false;
        }
        
        // Set the response
        agreement.setResponse(approved, responseMessage);
        
        // Log the response
        logWarning("AGREEMENT_RESPONSE", 
            String.format("Agreement %s by %s for %s action: %s", 
                approved ? "APPROVED" : "DENIED", 
                agreement.getLender(),
                agreement.getActionType(),
                responseMessage.isEmpty() ? "No message" : responseMessage),
            agreement.getBorrower());
        
        // If denied and it's a critical action, alert group admins
        if (!approved && (agreement.getActionType().equals("DROP") || agreement.getActionType().equals("WILDERNESS"))) {
            alertGroupAdminsOfViolation(
                java.util.Arrays.asList(new LendingEntry()), // Simplified - in real implementation would get the actual item
                "AGREEMENT_DENIED", 
                String.format("Lender %s denied %s request from %s", 
                    agreement.getLender(), agreement.getActionType(), agreement.getBorrower()));
        }
        
        log.info("Agreement {} responded to by {}: {}", agreementId, agreement.getLender(), 
            approved ? "APPROVED" : "DENIED");
        
        return true;
    }
    
    /**
     * Lender Agreement data class
     */
    public static class LenderAgreement {
        private final String id;
        private final String lender;
        private final String borrower;
        private final String actionType;
        private final String message;
        private final String itemName;
        private final long timestamp;
        private boolean responded = false;
        private boolean approved = false;
        private String response = "";
        
        public LenderAgreement(String id, String lender, String borrower, String actionType, 
                              String message, String itemName, long timestamp) {
            this.id = id;
            this.lender = lender;
            this.borrower = borrower;
            this.actionType = actionType;
            this.message = message;
            this.itemName = itemName;
            this.timestamp = timestamp;
        }
        
        // Getters
        public String getId() { return id; }
        public String getLender() { return lender; }
        public String getBorrower() { return borrower; }
        public String getActionType() { return actionType; }
        public String getMessage() { return message; }
        public String getItemName() { return itemName; }
        public long getTimestamp() { return timestamp; }
        public boolean isResponded() { return responded; }
        public boolean isApproved() { return approved; }
        public String getResponse() { return response; }
        
        // Setters for response
        public void setResponse(boolean approved, String response) {
            this.responded = true;
            this.approved = approved;
            this.response = response;
        }
        
        public String getFormattedTimestamp() {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(timestamp));
        }
    }

    /**
     * Analyze player risk level (for warnHighRiskBorrowers config)
     */
    public int analyzePlayerRisk(String playerName) {
        return riskAnalyzer.analyzePlayer(playerName);
    }

    /**
     * Get risk reason for player (for warnHighRiskBorrowers config)
     */
    public String getPlayerRiskReason(String playerName) {
        return riskAnalyzer.getRiskReason(playerName);
    }

    // =============== GETTER METHODS FOR UI PANELS ===============
    // ADDED: These getters are required by DashboardPanel, RosterPanel, HistoryPanel,
    // SettingsPanel, GroupControlPanel, GroupsManagementPanel, LendingTrackerPanel,
    // BorrowRequestNotificationOverlay, and NotificationBubbleOverlay.

    public Client getClient() {
        return client;
    }

    public ClientThread getClientThread() {
        return clientThread;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LendingTrackerConfig getConfig() {
        return config;
    }

    public LendingTrackerConfig getLendingConfig() {
        return config;
    }

    public ItemManager getItemManager() {
        return itemManager;
    }

    public Recorder getRecorder() {
        return recorder;
    }

    public GroupConfigStore getGroupConfigStore() {
        return groupConfigStore;
    }

    public LendingManager getLendingManager() {
        return lendingManager;
    }

    public MarketplaceManager getMarketplaceManager() {
        return marketplaceManager;
    }

    public ItemSetManager getItemSetManager() {
        return itemSetManager;
    }

    public GroupSyncService getGroupSyncService() {
        return groupSyncService;
    }

    public OnlineStatusService getOnlineStatusService() {
        return onlineStatusService;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public ProofScreenshot getProofScreenshot() {
        return proofScreenshot;
    }

    public StorageService getStorageService() {
        return storageService;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public BorrowRequestNotificationOverlay getBorrowRequestNotificationOverlay() {
        return borrowRequestNotificationOverlay;
    }

    /**
     * Get the current player name, or null if not logged in
     */
    public String getCurrentPlayerName() {
        if (client != null && client.getLocalPlayer() != null) {
            return client.getLocalPlayer().getName();
        }
        // Fallback: check stored config
        String stored = configManager.getConfiguration("lendingtracker", "currentAccount");
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        return null;
    }

    /**
     * Refresh the new LendingPanel UI
     */
    public void refreshPanel() {
        if (newPanel != null) {
            SwingUtilities.invokeLater(() -> newPanel.refresh());
        }
    }

    /**
     * Open the panel to the notifications/history tab
     */
    public void openPanelToNotifications() {
        if (newPanel != null) {
            SwingUtilities.invokeLater(() -> {
                // Refresh panel which includes notification display
                newPanel.refresh();
            });
        }
    }

    /**
     * Test a Discord webhook by sending a test message
     */
    public void testDiscordWebhook(String webhookUrl, String groupName) {
        if (discordWebhookService != null && webhookUrl != null && !webhookUrl.isEmpty()) {
            executor.submit(() -> {
                try {
                    discordWebhookService.sendTestMessage(webhookUrl, groupName);
                    log.info("Discord webhook test sent to: {}", webhookUrl);
                } catch (Exception e) {
                    log.error("Failed to send Discord webhook test", e);
                }
            });
        }
    }

    /**
     * Show borrow request notification overlay
     */
    public void showBorrowRequestNotification(BorrowRequest request, String ownerName) {
        if (borrowRequestNotificationOverlay != null && request != null) {
            borrowRequestNotificationOverlay.showBorrowRequest(request);
        }
    }

    /**
     * Handle notification dismissal from overlay
     */
    public void onNotificationDismissed(String requestId, String requesterName) {
        log.info("Notification dismissed for request {} from {}", requestId, requesterName);
        // Optional: mark notification as read in service
        if (notificationService != null) {
            String currentPlayer = getCurrentPlayerName();
            if (currentPlayer != null) {
                notificationService.markAsRead(currentPlayer, requestId);
            }
        }
    }

    /**
     * Send a borrow request to a lender
     */
    public void sendBorrowRequest(String borrower, String lender, String itemName, int itemId, int quantity, int durationDays) {
        if (borrowRequestService == null) {
            log.warn("BorrowRequestService not available");
            return;
        }

        BorrowRequest request = new BorrowRequest();
        request.setId(UUID.randomUUID().toString());
        request.setRequesterId(borrower);
        request.setItemName(itemName);
        request.setItemId(itemId);
        request.setQuantity(quantity);
        request.setRequestDate(System.currentTimeMillis());
        request.setExpirationDate(System.currentTimeMillis() + (durationDays * 24L * 60L * 60L * 1000L));

        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        String groupId = null;
        String groupName = null;
        if (activeGroup != null) {
            groupId = activeGroup.getId();
            groupName = activeGroup.getName();
            request.setGroupId(groupId);
        }

        BorrowRequestService.SubmitResult result = borrowRequestService.submitBorrowRequest(request);
        if (result.success) {
            log.info("Borrow request sent from {} to {} for {} x{}", borrower, lender, itemName, quantity);

            // Create notification for lender using StoredNotification
            if (notificationService != null && groupId != null) {
                StoredNotification notification = StoredNotification.createBorrowRequest(
                    groupId, groupName != null ? groupName : "", borrower, lender,
                    itemName, itemId, quantity, durationDays);
                notificationService.storeBorrowRequestNotification(notification);
            }
        } else {
            log.warn("Failed to submit borrow request: {}", result.errorMessage);
        }
    }

    /**
     * Send a lend offer in response to a borrow request
     */
    public void sendLendOffer(String lender, String borrower, String itemName, int quantity, int durationDays, String message, String durationDisplay) {
        log.info("Lend offer from {} to {} for {} x{} ({})", lender, borrower, itemName, quantity, durationDisplay);

        // Create notification for the borrower
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        if (notificationService != null && activeGroup != null) {
            StoredNotification notification = StoredNotification.createLendOffer(
                activeGroup.getId(), activeGroup.getName(), lender, borrower,
                itemName, 0, quantity, durationDays, message != null ? message : "");
            notificationService.storeLendOfferNotification(notification);
        }

        // Notify via Discord if configured
        if (activeGroup != null && discordWebhookService != null) {
            notificationService.broadcastLendOffer(activeGroup.getName(), lender, borrower, itemName, quantity, durationDisplay);
        }
    }

    /**
     * Send a borrow request for an item set
     */
    public void sendSetBorrowRequest(String borrower, String owner, String setName, long totalValue, int itemCount, int durationDays, String groupId) {
        log.info("Set borrow request from {} to {} for set '{}' ({} items, {} GP) for {} days",
            borrower, owner, setName, itemCount, totalValue, durationDays);

        // Create notification for the set owner using StoredNotification
        if (notificationService != null && groupId != null) {
            LendingGroup group = groupConfigStore.getGroup(groupId);
            String groupName = group != null ? group.getName() : "";
            StoredNotification notification = StoredNotification.createBorrowRequest(
                groupId, groupName, borrower, owner,
                setName, 0, itemCount, durationDays);
            notification.setMessage("Item set with " + itemCount + " items, total value " +
                QuantityFormatter.quantityToStackSize(totalValue) + " GP");
            notificationService.storeBorrowRequestNotification(notification);
        }

        // Notify via Discord if configured
        if (groupId != null && discordWebhookService != null) {
            LendingGroup group = groupConfigStore.getGroup(groupId);
            if (group != null) {
                notificationService.broadcastBorrowRequest(group.getName(), borrower, owner, setName, itemCount, durationDays);
            }
        }
    }

    @Provides
    LendingTrackerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LendingTrackerConfig.class);
    }
}
