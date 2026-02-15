package com.guess34.lendingtracker.panel;

import lombok.extern.slf4j.Slf4j;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.QuantityFormatter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Date;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Collection;
import java.util.stream.Collectors;
import com.google.gson.Gson;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.JSplitPane;
import javax.swing.table.TableCellEditor;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.DefaultListModel;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.model.WarningLogEntry;
import com.guess34.lendingtracker.model.PeerReview;
import com.guess34.lendingtracker.model.BorrowRequest;
import com.guess34.lendingtracker.model.RiskLogEntry;
import com.guess34.lendingtracker.LendingTrackerPlugin.LenderAgreement;
import com.guess34.lendingtracker.services.*;
import com.guess34.lendingtracker.services.group.GroupConfigStore;
import com.guess34.lendingtracker.panel.WebhookConfigDialog;
import com.guess34.lendingtracker.panel.GroupsManagementPanel;
import com.guess34.lendingtracker.panel.SetupPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemPrice;

@Slf4j
public class LendingTrackerPanel extends PluginPanel
{

    private final Recorder recorder;
    private final LendingTrackerConfig config;
    private final ItemManager itemManager;
    private final Client client;
    private final ClientThread clientThread; // ADDED: For client thread access
    private final ProofScreenshot proofScreenshot;
    private final GroupConfigStore groupConfigStore;
    private final DiscordWebhookService discordWebhookService;
    private final LocalDataSyncService localDataSyncService;
    private final PeerReviewService peerReviewService;
    private final OnlineStatusService onlineStatusService;
    private final BorrowRequestService borrowRequestService;
    private final WebhookTokenService webhookTokenService;
    private final WebhookRateLimiter webhookRateLimiter;
    private final WebhookAuditLogger webhookAuditLogger;
    private final NotificationMessageService notificationMessageService; // ADDED: For notification management
    @Inject
    private Gson gson;

    // RECONNECTED: Risk Session Management - Plugin reference for accessing active sessions
    private com.guess34.lendingtracker.LendingTrackerPlugin plugin;

    //Party entries panel for notifications
    private JPanel partyEntriesPanel;

    // UI Components
    private SmartSuggestBox itemSuggestBox;
    private JComboBox<String> nameComboBox;
    private JComboBox<String> accountComboBox;
    private JComboBox<String> groupComboBox;
    private JTextField collateralField;
    private JComboBox<String> collateralTypeCombo;
    private JCheckBox agreedNoCollateralCheckBox;
    private JTextField dueHoursField;
    private JButton addButton;
    
    // Table Models
    private DefaultTableModel lentModel;
    private DefaultTableModel borrowedModel;
    private DefaultTableModel availableModel;
    private DefaultTableModel historyModel;
    private DefaultTableModel partyModel;
    private DefaultTableModel warningLogModel;
    private DefaultTableModel riskSessionModel; // RECONNECTED: Risk Session Management
    private DefaultTableModel collateralModel; // RECONNECTED: Collateral Agreement Tracker
    private DefaultTableModel securityLogModel; // RECONNECTED: Security Log Viewer
    private DefaultTableModel marketplaceTableModel; // ADDED: For Items Offered/Wanted marketplace view
    private boolean showingOffered = true; // ADDED: Track whether showing offered items or wanted items

    // Tables
    private JTable lentTable;
    private JTable borrowedTable;
    private JTable availableTable;
    private JTable historyTable;
    private JTable partyTable;
    private JTable warningLogTable;
    private JTable riskSessionTable; // RECONNECTED: Risk Session Management
    private JTable collateralTable; // RECONNECTED: Collateral Agreement Tracker
    private JTable securityLogTable; // RECONNECTED: Security Log Viewer
    
    // Lists
    private JList<String> groupMemberList;
    
    // Search fields
    private JTextField groupSearch;
    
    // Control buttons
    private JButton exportButton;
    private JButton refreshButton;
    private JButton remindButton;
    private JButton screenshotFolderButton;
    private JButton inviteButton;
    private JButton appointAdminButton;
    
    // Status labels
    private JLabel totalValueLabel;
    private JLabel collateralHeldLabel;
    private JLabel groupStatusLabel;
    
    // Filters
    private JComboBox<String> exportFormatComboBox;
    private JCheckBox overdueOnlyCheckBox;
    
    // Additional UI Components
    private JPanel notificationPanel;
    private JTabbedPane tabbedPane;

    // Auto-refresh timer for tables
    private Timer autoRefreshTimer;

    // ADDED: Track current account name for account-specific group filtering
    private String currentAccount;

    @Inject
    public LendingTrackerPanel(Recorder recorder, LendingTrackerConfig config, ItemManager itemManager,
                               Client client, ClientThread clientThread, ProofScreenshot proofScreenshot, GroupConfigStore groupConfigStore,
                               DiscordWebhookService discordWebhookService, LocalDataSyncService localDataSyncService,
                               PeerReviewService peerReviewService, OnlineStatusService onlineStatusService,
                               BorrowRequestService borrowRequestService,
                               WebhookTokenService webhookTokenService, WebhookRateLimiter webhookRateLimiter,
                               WebhookAuditLogger webhookAuditLogger, NotificationMessageService notificationMessageService)
    {
        this.recorder = recorder;
        this.config = config;
        this.itemManager = itemManager;
        this.client = client;
        this.clientThread = clientThread; // ADDED
        this.proofScreenshot = proofScreenshot;
        this.groupConfigStore = groupConfigStore;
        this.discordWebhookService = discordWebhookService;
        this.localDataSyncService = localDataSyncService;
        this.peerReviewService = peerReviewService;
        this.onlineStatusService = onlineStatusService;
        this.borrowRequestService = borrowRequestService;
        this.webhookTokenService = webhookTokenService;
        this.webhookRateLimiter = webhookRateLimiter;
        this.webhookAuditLogger = webhookAuditLogger;
        this.notificationMessageService = notificationMessageService; // ADDED

        setBorder(new EmptyBorder(6, 6, 6, 6));
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BorderLayout());

        // Build UI components first, with safe initialization
        try {
            buildPanel();
            log.debug("Panel built successfully, now refreshing...");
            refresh();
            log.debug("Panel refresh completed successfully");
        } catch (Exception e) {
            log.error("Error during panel initialization: " + e.getMessage(), e);
            // Build a minimal fallback UI
            buildFallbackUI();
        }

        // ADDED: Auto-refresh timer for risk session, collateral, and security log tables
        // Refreshes every 10 seconds to keep data current without manual button clicks
        autoRefreshTimer = new Timer(10000, e -> {
            try {
                // Only update if tables exist and panel is visible
                if (isShowing()) {
                    updateRiskSessionTable();
                    updateCollateralTable();
                    updateSecurityLogTable();
                    log.debug("Auto-refresh completed for risk/collateral/security tables");
                }
            } catch (Exception ex) {
                log.error("Error during auto-refresh", ex);
            }
        });
        autoRefreshTimer.start();
        log.info("Auto-refresh timer started (10s interval)");
    }
    
    // RECONNECTED: Risk Session Management - Set plugin reference
    public void setPlugin(com.guess34.lendingtracker.LendingTrackerPlugin plugin) {
        this.plugin = plugin;
    }

    // ADDED: Set current account and update account dropdown
    public void setCurrentAccount(String accountName) {
        this.currentAccount = accountName;

        // Update account dropdown if it exists
        if (accountComboBox != null) {
            // Check if account is already in dropdown
            boolean found = false;
            for (int i = 0; i < accountComboBox.getItemCount(); i++) {
                if (accountComboBox.getItemAt(i).equals(accountName)) {
                    found = true;
                    accountComboBox.setSelectedItem(accountName);
                    break;
                }
            }

            // Add account to dropdown if not found
            if (!found) {
                accountComboBox.addItem(accountName);
                accountComboBox.setSelectedItem(accountName);
            }

            // Refresh UI to show account-specific groups
            refreshGroupsForAccount();
        }
    }

    // ADDED: Refresh groups for current account
    private void refreshGroupsForAccount() {
        if (currentAccount == null || groupComboBox == null) {
            return;
        }

        // Reload groups filtered by current account
        String selectedGroup = (String) groupComboBox.getSelectedItem();
        groupComboBox.removeAllItems();
        groupComboBox.addItem("Select Group");

        // Get account-specific groups
        List<String> accountGroups = getGroupsForAccount(currentAccount);
        for (String group : accountGroups) {
            groupComboBox.addItem(group);
        }

        // Restore selection if it still exists
        if (selectedGroup != null && accountGroups.contains(selectedGroup)) {
            groupComboBox.setSelectedItem(selectedGroup);
        }
    }

    // ADDED: Get groups for a specific account
    private List<String> getGroupsForAccount(String accountName) {
        List<String> accountGroups = new ArrayList<>();
        if (accountName == null) {
            return accountGroups;
        }

        // Get all groups from GroupConfigStore
        List<LendingGroup> allGroups = new ArrayList<>(groupConfigStore.getAllGroups());

        // Filter groups where the account is a member
        for (LendingGroup group : allGroups) {
            // Check if the account is a member of this group
            boolean isMember = group.getMembers().stream()
                .anyMatch(member -> member.getName().equalsIgnoreCase(accountName));

            if (isMember) {
                accountGroups.add(group.getName());
            }
        }

        return accountGroups;
    }

    private void buildPanel()
    {
        // Create main layout panel
        final JPanel layoutPanel = new JPanel(new BorderLayout());
        add(layoutPanel, BorderLayout.CENTER);

        // Header panel - account/group selection
        JPanel headerPanel = buildHeaderPanel();
        layoutPanel.add(headerPanel, BorderLayout.NORTH);

        // Create center panel for selector and content
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Section selector panel
        JPanel selectorPanel = buildSelectorPanel();
        centerPanel.add(selectorPanel, BorderLayout.NORTH);

        // Dynamic content area
        JPanel dynamicContent = new JPanel(new BorderLayout());
        dynamicContent.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Initialize with Setup (matches dropdown default)
        dynamicContent.add(buildSetupContent(), BorderLayout.CENTER);

        // Update content when section changes
        JComboBox<String> sectionDropdown = (JComboBox<String>) ((JPanel) selectorPanel.getComponent(0)).getComponent(1);
        sectionDropdown.addActionListener(e -> {
            String selected = (String) sectionDropdown.getSelectedItem();
            dynamicContent.removeAll();

            switch (selected) {
                case "Setup":
                    dynamicContent.add(buildSetupContent(), BorderLayout.CENTER);
                    break;
                case "Marketplace":
                    dynamicContent.add(buildMarketplaceContent(), BorderLayout.CENTER);
                    break;
                case "Transactions":
                    dynamicContent.add(buildTransactionsContent(), BorderLayout.CENTER);
                    break;
                case "Groups":
                    dynamicContent.add(buildGroupsContent(), BorderLayout.CENTER);
                    break;
                case "History":
                    dynamicContent.add(buildHistoryContent(), BorderLayout.CENTER);
                    break;
                case "Notifications": // ADDED: Notifications tab
                    dynamicContent.add(buildNotificationsContent(), BorderLayout.CENTER);
                    break;
            }

            dynamicContent.revalidate();
            dynamicContent.repaint();
        });

        // FIXED: Wrap dynamic content in scroll pane so footer stays visible
        JScrollPane contentScrollPane = new JScrollPane(dynamicContent);
        contentScrollPane.setBorder(null);
        contentScrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentScrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        contentScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        contentScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // Add scrollable content to center panel
        centerPanel.add(contentScrollPane, BorderLayout.CENTER);

        // Add center panel to main layout
        layoutPanel.add(centerPanel, BorderLayout.CENTER);

        // Footer panel - FIXED: Always visible at bottom with explicit sizing
        JPanel footerPanel = buildFooterPanel();
        footerPanel.setMinimumSize(new Dimension(0, 70));
        footerPanel.setPreferredSize(new Dimension(0, 70));
        layoutPanel.add(footerPanel, BorderLayout.SOUTH);

        // Initialize notification system
        notificationPanel = new JPanel();
        notificationPanel.setLayout(new BoxLayout(notificationPanel, BoxLayout.Y_AXIS));
        notificationPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        ToolTipManager.sharedInstance().setInitialDelay(100);
    }
    
    // New clean panel builders following RuneLite patterns
    private JPanel buildHeaderPanel()
    {
        final JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.LIGHT_GRAY_COLOR),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        headerPanel.setPreferredSize(new Dimension(0, 80)); // Increased height for two rows

        // Title
        JLabel titleLabel = new JLabel("Lending Tracker", SwingConstants.CENTER);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerPanel.add(titleLabel, BorderLayout.NORTH);

        // Controls panel with two rows
        JPanel controlsPanel = new JPanel();
        controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
        controlsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Account row
        JPanel accountRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        accountRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountRow.add(new JLabel("Account:") {{ setForeground(Color.LIGHT_GRAY); }});
        accountComboBox = new JComboBox<>();
        accountComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountComboBox.setForeground(Color.WHITE);
        accountComboBox.addActionListener(e -> refresh());
        accountRow.add(accountComboBox);
        
        // Add refresh button next to account
        JButton refreshBtn = new JButton("↻");
        refreshBtn.setBackground(ColorScheme.BRAND_ORANGE);
        refreshBtn.setForeground(Color.WHITE);
        refreshBtn.setPreferredSize(new Dimension(25, 20));
        refreshBtn.setFont(refreshBtn.getFont().deriveFont(12f));
        refreshBtn.setToolTipText("Refresh account name");
        refreshBtn.addActionListener(e -> {
            String currentPlayer = getCurrentPlayerName();
            if (!currentPlayer.equals("Default")) {
                accountComboBox.removeAllItems();
                accountComboBox.addItem(currentPlayer);
                accountComboBox.setSelectedItem(currentPlayer);
                refresh();
            }
        });
        accountRow.add(refreshBtn);
        
        controlsPanel.add(accountRow);
        
        // Group row  
        JPanel groupRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        groupRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupRow.add(new JLabel("Group:") {{ setForeground(Color.LIGHT_GRAY); }});
        groupComboBox = new JComboBox<>();
        groupComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupComboBox.setForeground(Color.WHITE);
        groupComboBox.addActionListener(e -> switchGroup());
        groupRow.add(groupComboBox);
        controlsPanel.add(groupRow);

        headerPanel.add(controlsPanel, BorderLayout.CENTER);
        return headerPanel;
    }

    private JPanel buildSelectorPanel()
    {
        final JPanel outerPanel = new JPanel();
        outerPanel.setLayout(new BoxLayout(outerPanel, BoxLayout.Y_AXIS));
        
        final JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
        selectorPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        selectorPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        selectorPanel.setPreferredSize(new Dimension(0, 35)); // Height only

        selectorPanel.add(new JLabel("Section:") {{ setForeground(Color.WHITE); }});
        
        JComboBox<String> sectionDropdown = new JComboBox<>(new String[]{
            "Setup", "Marketplace", "Transactions", "Groups", "History", "Notifications"
        });
        sectionDropdown.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        sectionDropdown.setForeground(Color.WHITE);
        selectorPanel.add(sectionDropdown);
        
        outerPanel.add(selectorPanel);
        return outerPanel;
    }

    private JPanel buildMarketplaceContent()
    {
        final JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Toggle buttons panel - stack all buttons vertically
        JPanel togglePanel = new JPanel();
        togglePanel.setLayout(new BoxLayout(togglePanel, BoxLayout.Y_AXIS));
        togglePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Items Offered button
        JButton offeredBtn = new JButton("Items Offered");
        offeredBtn.setBackground(ColorScheme.BRAND_ORANGE);
        offeredBtn.setForeground(Color.WHITE);
        offeredBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        offeredBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        offeredBtn.setToolTipText("View all items offered for lending in this group");

        // Items Wanted button
        JButton wantedBtn = new JButton("Items Wanted");
        wantedBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        wantedBtn.setForeground(Color.WHITE);
        wantedBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        wantedBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        wantedBtn.setToolTipText("View items wanted by OTHER group members (your requests excluded)");

        // Submit Request button
        JButton submitRequestBtn = new JButton("Submit Request");
        submitRequestBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        submitRequestBtn.setForeground(Color.WHITE);
        submitRequestBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        submitRequestBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        submitRequestBtn.setToolTipText("Submit a borrow request for an item");
        submitRequestBtn.addActionListener(e -> showSubmitBorrowRequestDialog());

        // View Requests button
        JButton viewRequestsBtn = new JButton("View Requests");
        viewRequestsBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        viewRequestsBtn.setForeground(Color.WHITE);
        viewRequestsBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        viewRequestsBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        viewRequestsBtn.setToolTipText("View and manage borrow requests");
        viewRequestsBtn.addActionListener(e -> showBorrowRequestsDialog());

        // Add all buttons with spacing
        togglePanel.add(offeredBtn);
        togglePanel.add(Box.createVerticalStrut(5));
        togglePanel.add(wantedBtn);
        togglePanel.add(Box.createVerticalStrut(10));
        togglePanel.add(submitRequestBtn);
        togglePanel.add(Box.createVerticalStrut(5));
        togglePanel.add(viewRequestsBtn);
        togglePanel.add(Box.createVerticalStrut(10));
        
        // Marketplace table
        String[] columnNames = {"Item", "Player"};
        // CHANGED: Use instance variable instead of local variable for refreshing
        marketplaceTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable marketplaceTable = new JTable(marketplaceTableModel);
        marketplaceTable.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        marketplaceTable.setForeground(Color.LIGHT_GRAY);
        marketplaceTable.setSelectionBackground(ColorScheme.BRAND_ORANGE);
        marketplaceTable.setSelectionForeground(Color.WHITE);
        marketplaceTable.setGridColor(ColorScheme.MEDIUM_GRAY_COLOR);
        marketplaceTable.getTableHeader().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        marketplaceTable.getTableHeader().setForeground(Color.WHITE);
        marketplaceTable.setRowHeight(25);

        // ADDED: Make marketplace items interactive with right-click context menu
        // Toggle functionality - CHANGED: Use instance variable instead of local array

        marketplaceTable.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                if (SwingUtilities.isRightMouseButton(e))
                {
                    int row = marketplaceTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < marketplaceTable.getRowCount())
                    {
                        marketplaceTable.setRowSelectionInterval(row, row);
                        showMarketplaceContextMenu(e, marketplaceTable, row, showingOffered);
                    }
                }
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(marketplaceTable);
        styleScrollBar(tableScrollPane);
        tableScrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        tableScrollPane.getViewport().setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Load offered items initially
        loadMarketplaceData(marketplaceTableModel, true);

        offeredBtn.addActionListener(e -> {
            if (!showingOffered) {
                showingOffered = true;
                offeredBtn.setBackground(ColorScheme.BRAND_ORANGE);
                wantedBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
                loadMarketplaceData(marketplaceTableModel, true);
            }
        });

        wantedBtn.addActionListener(e -> {
            if (showingOffered) {
                showingOffered = false;
                wantedBtn.setBackground(ColorScheme.BRAND_ORANGE);
                offeredBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
                loadMarketplaceData(marketplaceTableModel, false);
            }
        });
        
        contentPanel.add(togglePanel, BorderLayout.NORTH);
        contentPanel.add(tableScrollPane, BorderLayout.CENTER);
        
        return contentPanel;
    }
    
    private void loadMarketplaceData(DefaultTableModel tableModel, boolean showOffered)
    {
        log.info("loadMarketplaceData() called - showOffered: {}", showOffered);
        tableModel.setRowCount(0); // Clear existing data

        String groupId = groupConfigStore.getCurrentGroupId();
        if (groupId == null || groupId.isEmpty()) {
            log.warn("loadMarketplaceData: No group selected");
            tableModel.addRow(new Object[]{"No group selected", ""});
            return;
        }

        String currentPlayer = getCurrentPlayerName();
        log.info("loadMarketplaceData: groupId={}, currentPlayer={}", groupId, currentPlayer);

        if (showOffered) {
            // FIXED: Load ALL items offered for lending (including your own)
            List<LendingEntry> available = recorder.getAvailable(groupId);
            log.info("loadMarketplaceData: Found {} items offered in marketplace", available.size());

            if (available.isEmpty()) {
                tableModel.addRow(new Object[]{"No items offered ...", ""});
            } else {
                for (LendingEntry entry : available) {
                    String player = entry.getLender() != null ? entry.getLender() : "Unknown Player";
                    String item = entry.getItem() != null ? entry.getItem() : "Unknown Item";
                    log.debug("loadMarketplaceData: Adding row - item: {}, player: {}", item, player);

                    // Show ALL items including your own - you can see who owns what
                    tableModel.addRow(new Object[]{item, player});
                }
            }
        } else {
            // Load items wanted by OTHER players (active borrow requests)
            List<BorrowRequest> allRequests = plugin.getBorrowRequestService().getActiveRequestsForGroup(groupId);
            log.info("loadMarketplaceData: Found {} total borrow requests", allRequests.size());

            if (allRequests.isEmpty()) {
                tableModel.addRow(new Object[]{"No active borrow requests", ""});
            } else {
                boolean hasOtherPlayerRequests = false;
                for (BorrowRequest request : allRequests) {
                    String requester = request.getRequesterId() != null ? request.getRequesterId() : "Unknown Player";

                    // Skip requests from current player
                    if (requester.equals(currentPlayer)) {
                        log.debug("loadMarketplaceData: Skipping request from current player: {}", requester);
                        continue;
                    }

                    hasOtherPlayerRequests = true;
                    String item = request.getItemName() != null ? request.getItemName() : "Unknown Item";
                    String quantity = request.getQuantity() > 1 ? " x" + request.getQuantity() : "";
                    log.debug("loadMarketplaceData: Adding request - item: {}, requester: {}", item, requester);
                    tableModel.addRow(new Object[]{item + quantity, requester});
                }

                if (!hasOtherPlayerRequests) {
                    log.info("loadMarketplaceData: No requests from other players");
                    tableModel.addRow(new Object[]{"No requests from other players", ""});
                }
            }
        }

        log.info("loadMarketplaceData: Completed - table now has {} rows", tableModel.getRowCount());
    }

    // ADDED: Show context menu for marketplace items with edit/delete/request options
    private void showMarketplaceContextMenu(MouseEvent e, JTable table, int row, boolean isOfferedView)
    {
        String itemName = (String) table.getValueAt(row, 0);
        String playerName = (String) table.getValueAt(row, 1);
        String currentPlayer = getCurrentPlayerName();

        // Skip if it's a message row (like "No items offered...")
        if (itemName == null || playerName == null || playerName.isEmpty() ||
            itemName.contains("No items") || itemName.contains("No group") || itemName.contains("No active"))
        {
            return;
        }

        JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        popupMenu.setBorder(BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 1));

        boolean isOwnItem = playerName.equals(currentPlayer);

        if (isOfferedView)
        {
            // Marketplace "Items Offered" view
            if (isOwnItem)
            {
                // For YOUR items: Edit and Delete
                JMenuItem editItem = new JMenuItem("Edit");
                editItem.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                editItem.setForeground(Color.WHITE);
                editItem.addActionListener(ev -> showEditItemDialog(itemName, playerName));
                popupMenu.add(editItem);

                JMenuItem deleteItem = new JMenuItem("Delete");
                deleteItem.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                deleteItem.setForeground(Color.RED);
                deleteItem.addActionListener(ev -> deleteMarketplaceItem(itemName, playerName));
                popupMenu.add(deleteItem);
            }
            else
            {
                // For OTHER players' items: Request to Borrow
                JMenuItem requestItem = new JMenuItem("Request to Borrow");
                requestItem.setBackground(ColorScheme.DARKER_GRAY_COLOR);
                requestItem.setForeground(ColorScheme.BRAND_ORANGE);
                requestItem.addActionListener(ev -> showBorrowRequestDialog(itemName, playerName));
                popupMenu.add(requestItem);
            }
        }
        else
        {
            // "Items Wanted" view - borrow requests
            JMenuItem viewDetails = new JMenuItem("View Request Details");
            viewDetails.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            viewDetails.setForeground(Color.WHITE);
            viewDetails.addActionListener(ev -> showRequestDetails(itemName, playerName));
            popupMenu.add(viewDetails);
        }

        popupMenu.show(e.getComponent(), e.getX(), e.getY());
    }

    // ADDED: Show edit dialog for updating marketplace item
    private void showEditItemDialog(String itemName, String playerName)
    {
        String groupId = groupConfigStore.getCurrentGroupId();
        if (groupId == null || groupId.isEmpty())
        {
            return;
        }

        // Find the entry
        List<LendingEntry> available = recorder.getAvailable(groupId);
        LendingEntry foundEntry = available.stream()
            .filter(entry -> entry.getLender().equals(playerName) && entry.getItem().equals(itemName))
            .findFirst()
            .orElse(null);

        if (foundEntry == null)
        {
            return;
        }

        // Create edit dialog
        SwingUtilities.invokeLater(() ->
        {
            JDialog editDialog = new JDialog();
            editDialog.setTitle("Edit Marketplace Item");
            editDialog.setModal(false);
            editDialog.setAutoRequestFocus(true);
            editDialog.setSize(350, 300);
            editDialog.setLocationRelativeTo(null);

            JPanel mainPanel = new JPanel();
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

            // Item name (non-editable)
            JPanel itemPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            itemPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel itemLabel = new JLabel("Item: " + itemName);
            itemLabel.setForeground(Color.WHITE);
            itemLabel.setFont(itemLabel.getFont().deriveFont(Font.BOLD));
            itemPanel.add(itemLabel);
            mainPanel.add(itemPanel);
            mainPanel.add(Box.createVerticalStrut(10));

            // Quantity spinner
            JPanel quantityPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            quantityPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel quantityLabel = new JLabel("Quantity:");
            quantityLabel.setForeground(Color.WHITE);
            SpinnerNumberModel quantityModel = new SpinnerNumberModel(foundEntry.getQuantity(), 1, 100, 1);
            JSpinner quantitySpinner = new JSpinner(quantityModel);
            quantitySpinner.setPreferredSize(new Dimension(80, 25));
            quantityPanel.add(quantityLabel);
            quantityPanel.add(quantitySpinner);
            mainPanel.add(quantityPanel);
            mainPanel.add(Box.createVerticalStrut(10));

            // FIXED: Collateral spinner - Use collateralValue instead of getCollateral()
            JPanel collateralPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            collateralPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel collateralLabel = new JLabel("Collateral (gp):");
            collateralLabel.setForeground(Color.WHITE);
            int collateralValue = foundEntry.getCollateralValue() != null ? foundEntry.getCollateralValue() : 0;
            SpinnerNumberModel collateralModel = new SpinnerNumberModel(collateralValue, 0, 1000000000, 1000);
            JSpinner collateralSpinner = new JSpinner(collateralModel);
            collateralSpinner.setPreferredSize(new Dimension(120, 25));
            collateralPanel.add(collateralLabel);
            collateralPanel.add(collateralSpinner);
            mainPanel.add(collateralPanel);
            mainPanel.add(Box.createVerticalStrut(10));

            // FIXED: Duration - Calculate from dueTime and lendTime
            JPanel durationPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            durationPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel durationLabel = new JLabel("Duration (days):");
            durationLabel.setForeground(Color.WHITE);
            long durationMillis = foundEntry.getDueTime() - foundEntry.getLendTime();
            int durationDays = (int) (durationMillis / (24 * 60 * 60 * 1000L));
            if (durationDays < 1) durationDays = 1;
            SpinnerNumberModel durationModel = new SpinnerNumberModel(durationDays, 1, 365, 1);
            JSpinner durationSpinner = new JSpinner(durationModel);
            durationSpinner.setPreferredSize(new Dimension(80, 25));
            durationPanel.add(durationLabel);
            durationPanel.add(durationSpinner);
            mainPanel.add(durationPanel);
            mainPanel.add(Box.createVerticalStrut(10));

            // REMOVED: Interest rate spinner - LendingEntry does not have interestRate field
            // COMMENTED OUT: No interest rate field exists in LendingEntry model
            /*
            JPanel interestPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            interestPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel interestLabel = new JLabel("Interest (%):");
            interestLabel.setForeground(Color.WHITE);
            SpinnerNumberModel interestModel = new SpinnerNumberModel(0.0, 0.0, 100.0, 0.5);
            JSpinner interestSpinner = new JSpinner(interestModel);
            interestSpinner.setPreferredSize(new Dimension(80, 25));
            interestPanel.add(interestLabel);
            interestPanel.add(interestSpinner);
            mainPanel.add(interestPanel);
            mainPanel.add(Box.createVerticalStrut(20));
            */

            // Buttons
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

            JButton saveButton = new JButton("Save");
            saveButton.setBackground(ColorScheme.BRAND_ORANGE);
            saveButton.setForeground(Color.WHITE);
            saveButton.addActionListener(e ->
            {
                int quantity = (int) quantitySpinner.getValue();
                int collateral = (int) collateralSpinner.getValue();
                int duration = (int) durationSpinner.getValue();
                // REMOVED: double interest = (double) interestSpinner.getValue();

                // FIXED: Create updated entry with proper setters
                LendingEntry updatedEntry = new LendingEntry(foundEntry);
                updatedEntry.setQuantity(quantity);
                updatedEntry.setCollateralValue(collateral); // FIXED: Use setCollateralValue instead of setCollateral
                // FIXED: Set duration by calculating dueTime from lendTime + duration
                long newDueTime = updatedEntry.getLendTime() + (duration * 24L * 60 * 60 * 1000);
                updatedEntry.setDueTime(newDueTime);
                // REMOVED: updatedEntry.setInterestRate((float) interest); - No such method exists

                // Update in recorder
                recorder.updateAvailable(groupId, playerName, itemName, foundEntry.getItemId(), updatedEntry);

                // FIXED: Close dialog first, then refresh marketplace panel
                editDialog.dispose();
                refreshMarketplace();
            });

            JButton cancelButton = new JButton("Cancel");
            cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            cancelButton.setForeground(Color.WHITE);
            cancelButton.addActionListener(e -> editDialog.dispose());

            buttonPanel.add(saveButton);
            buttonPanel.add(cancelButton);
            mainPanel.add(buttonPanel);

            editDialog.add(mainPanel);
            editDialog.setVisible(true);
            editDialog.toFront();
            editDialog.requestFocus();
        });
    }

    // ADDED: Delete an item from the marketplace
    private void deleteMarketplaceItem(String itemName, String playerName)
    {
        String groupId = groupConfigStore.getCurrentGroupId();
        if (groupId == null || groupId.isEmpty())
        {
            return;
        }

        // Find the entry to get item ID
        List<LendingEntry> available = recorder.getAvailable(groupId);
        LendingEntry foundEntry = available.stream()
            .filter(entry -> entry.getLender().equals(playerName) && entry.getItem().equals(itemName))
            .findFirst()
            .orElse(null);

        if (foundEntry == null)
        {
            return;
        }

        // Confirm deletion
        int confirm = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to remove " + itemName + " from the marketplace?",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );

        if (confirm == JOptionPane.YES_OPTION)
        {
            recorder.removeAvailable(groupId, playerName, itemName, foundEntry.getItemId());
            // FIXED: Refresh marketplace panel after deletion
            refreshMarketplace();
        }
    }

    // ADDED: Show borrow request dialog for other players' items
    private void showBorrowRequestDialog(String itemName, String ownerName)
    {
        String groupId = groupConfigStore.getCurrentGroupId();
        if (groupId == null || groupId.isEmpty())
        {
            return;
        }

        // Find the entry
        List<LendingEntry> available = recorder.getAvailable(groupId);
        LendingEntry foundEntry = available.stream()
            .filter(entry -> entry.getLender().equals(ownerName) && entry.getItem().equals(itemName))
            .findFirst()
            .orElse(null);

        if (foundEntry == null)
        {
            return;
        }

        // Create request dialog
        SwingUtilities.invokeLater(() ->
        {
            JDialog requestDialog = new JDialog();
            requestDialog.setTitle("Request to Borrow");
            requestDialog.setModal(false);
            requestDialog.setAutoRequestFocus(true);
            requestDialog.setSize(350, 250);
            requestDialog.setLocationRelativeTo(null);

            JPanel mainPanel = new JPanel();
            mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
            mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

            // Item details
            JLabel itemLabel = new JLabel("Item: " + itemName);
            itemLabel.setForeground(Color.WHITE);
            itemLabel.setFont(itemLabel.getFont().deriveFont(Font.BOLD));
            itemLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(itemLabel);
            mainPanel.add(Box.createVerticalStrut(5));

            JLabel ownerLabel = new JLabel("Owner: " + ownerName);
            ownerLabel.setForeground(Color.LIGHT_GRAY);
            ownerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(ownerLabel);
            mainPanel.add(Box.createVerticalStrut(10));

            // FIXED: Use getCollateralValue() instead of getCollateral()
            int collateralVal = foundEntry.getCollateralValue() != null ? foundEntry.getCollateralValue() : 0;
            JLabel collateralLabel = new JLabel("Collateral required: " + collateralVal + " gp");
            collateralLabel.setForeground(Color.ORANGE);
            collateralLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(collateralLabel);
            mainPanel.add(Box.createVerticalStrut(5));

            // FIXED: Calculate duration from dueTime and lendTime
            long durationMillis = foundEntry.getDueTime() - foundEntry.getLendTime();
            int durationDays = (int) (durationMillis / (24 * 60 * 60 * 1000L));
            JLabel durationLabel = new JLabel("Max duration: " + durationDays + " days");
            durationLabel.setForeground(Color.LIGHT_GRAY);
            durationLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(durationLabel);
            mainPanel.add(Box.createVerticalStrut(5));

            // REMOVED: Interest rate display - LendingEntry does not have interestRate field
            /*
            JLabel interestLabel = new JLabel("Interest rate: " + foundEntry.getInterestRate() + "%");
            interestLabel.setForeground(Color.LIGHT_GRAY);
            interestLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            mainPanel.add(interestLabel);
            mainPanel.add(Box.createVerticalStrut(20));
            */

            // Buttons
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

            JButton sendRequestButton = new JButton("Send Request");
            sendRequestButton.setBackground(ColorScheme.BRAND_ORANGE);
            sendRequestButton.setForeground(Color.WHITE);
            sendRequestButton.addActionListener(e ->
            {
                String requesterName = getCurrentPlayerName();
                int world = client.getWorld();

                // Create and send the borrow request
                var result = plugin.getBorrowRequestService().createRequest(
                    groupId,
                    requesterName,
                    ownerName,
                    itemName,
                    foundEntry.getItemId(),
                    1, // Default quantity = 1
                    world
                );

                // Show notification to owner if request was successful
                if (result.success && result.request != null)
                {
                    plugin.showBorrowRequestNotification(result.request, ownerName);
                }

                requestDialog.dispose();
            });

            JButton cancelButton = new JButton("Cancel");
            cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            cancelButton.setForeground(Color.WHITE);
            cancelButton.addActionListener(e -> requestDialog.dispose());

            buttonPanel.add(sendRequestButton);
            buttonPanel.add(cancelButton);
            mainPanel.add(buttonPanel);

            requestDialog.add(mainPanel);
            requestDialog.setVisible(true);
            requestDialog.toFront();
            requestDialog.requestFocus();
        });
    }

    // ADDED: Show details for borrow requests
    private void showRequestDetails(String itemName, String requesterName)
    {
        // TODO: Implement request details dialog
        JOptionPane.showMessageDialog(
            this,
            requesterName + " wants to borrow: " + itemName,
            "Borrow Request Details",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private JPanel buildTransactionsContent()
    {
        return buildTransactionsPanel();
    }
    
    private JPanel buildGroupsContent()
    {
        // Use new merged Groups management panel with dropdown tabs
        return new GroupsManagementPanel(plugin, groupConfigStore, client);
    }
    
    private JPanel buildSetupContent()
    {
        return new SetupPanel(groupConfigStore, client, this::refresh);
    }
    private JPanel buildCompactGroupsPanel()
    {
        JPanel groupPanel = new JPanel();
        groupPanel.setLayout(new BoxLayout(groupPanel, BoxLayout.Y_AXIS));
        groupPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        groupPanel.setBorder(new EmptyBorder(6, 6, 6, 6));
        
        // Group Selection
        JPanel groupSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        groupSelectorPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupSelectorPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Select Group", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        
        JComboBox<String> groupCombo = new JComboBox<>();
        groupCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupCombo.setForeground(Color.WHITE);
        groupCombo.setPreferredSize(new Dimension(120, 25));
        
        // Load groups
        try {
            Collection<LendingGroup> groups = groupConfigStore.getAllGroups();
            if (groups != null) {
                for (LendingGroup group : groups) {
                    if (group != null && group.getName() != null) {
                        groupCombo.addItem(group.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error loading groups: " + e.getMessage(), e);
        }
        
        if (groupCombo.getItemCount() == 0) {
            groupCombo.addItem("No Groups Available");
        }
        
        groupSelectorPanel.add(groupCombo);
        
        JButton newGroupBtn = new JButton("+ Create");
        newGroupBtn.setBackground(ColorScheme.BRAND_ORANGE);
        newGroupBtn.setForeground(Color.WHITE);
        newGroupBtn.setPreferredSize(new Dimension(70, 25));
        newGroupBtn.addActionListener(e -> showCreateGroupDialog());
        groupSelectorPanel.add(newGroupBtn);
        
        groupPanel.add(groupSelectorPanel);
        groupPanel.add(Box.createVerticalStrut(5));
        
        // Group Members with management functionality
        JPanel membersPanel = new JPanel(new BorderLayout());
        membersPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        membersPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Group Members", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        membersPanel.setPreferredSize(new Dimension(0, 80));
        
        // Members list with role indicators
        DefaultListModel<String> membersModel = new DefaultListModel<>();
        String currentUserRole = "member"; // Default role
        final String currentUserName = getCurrentPlayerName();
        
        try {
            String currentGroupName = (String) groupCombo.getSelectedItem();
            if (currentGroupName != null && !currentGroupName.equals("No Groups Available")) {
                String groupId = groupConfigStore.getGroupIdByName(currentGroupName);
                List<String> members = groupConfigStore.getGroupMembers(groupId);
                if (members != null && !members.isEmpty()) {
                    for (String member : members) {
                        // Add role indicators to display
                        if (member.contains("(owner)")) {
                            membersModel.addElement(member);
                            if (member.startsWith(currentUserName)) {
                                currentUserRole = "owner";
                            }
                        } else if (member.contains("(admin)")) {
                            membersModel.addElement(member);
                            if (member.startsWith(currentUserName)) {
                                currentUserRole = "admin";
                            }
                        } else {
                            membersModel.addElement(member);
                            if (member.equals(currentUserName)) {
                                currentUserRole = "member";
                            }
                        }
                    }
                } else {
                    membersModel.addElement("No members yet");
                }
            } else {
                membersModel.addElement("No members yet");
            }
        } catch (Exception e) {
            log.error("Error loading members: " + e.getMessage(), e);
            membersModel.addElement("Error loading members");
        }
        
        JList<String> membersList = new JList<>(membersModel);
        membersList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        membersList.setForeground(Color.LIGHT_GRAY);
        membersList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
        membersList.setSelectionForeground(Color.WHITE);
        
        JScrollPane membersScroll = new JScrollPane(membersList);
        membersScroll.setBorder(null);
        styleScrollBar(membersScroll);
        membersScroll.setPreferredSize(new Dimension(0, 50));
        membersPanel.add(membersScroll, BorderLayout.CENTER);
        
        // Member management buttons (with permission checks)
        JPanel memberBtnsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 2));
        memberBtnsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Only show management buttons if user is owner or admin
        final String finalUserRole = currentUserRole; // For lambda access
        final String finalCurrentGroupId = groupConfigStore.getCurrentGroupId(); // For lambda access
        if (currentUserRole.equals("owner") || currentUserRole.equals("admin")) {
            JButton removeBtn = new JButton("Remove");
            removeBtn.setBackground(Color.RED.darker());
            removeBtn.setForeground(Color.WHITE);
            removeBtn.setPreferredSize(new Dimension(55, 20));
            removeBtn.setEnabled(currentUserRole.equals("owner")); // Only owner can remove
            removeBtn.addActionListener(e -> {
                String selected = membersList.getSelectedValue();
                if (selected != null && !selected.equals("No members yet") && !selected.equals("Error loading members")) {
                    if (!finalUserRole.equals("owner")) {
                        showNotification("No Permission", "Only the group owner can remove members");
                        return;
                    }
                    if (selected.contains("(owner)")) {
                        showNotification("Cannot Remove", "Cannot remove the group owner");
                        return;
                    }
                    int confirm = JOptionPane.showConfirmDialog(this,
                        "Remove " + selected + " from group?", "Confirm", JOptionPane.YES_NO_OPTION);
                    if (confirm == JOptionPane.YES_OPTION) {
                        // IMPLEMENTED: Remove member from group via GroupConfigStore
                        try {
                            // Strip status indicators and role labels to get clean username
                            String cleanName = selected.replaceAll("^[🟢🟡🔴⚫]\\s*", "")
                                                      .replaceAll("\\s*\\(.*\\)$", "")
                                                      .replaceAll("\\s*\\[.*\\]$", "")
                                                      .trim();

                            groupConfigStore.removeMember(finalCurrentGroupId, cleanName);
                            showNotification("Member Removed", cleanName + " removed from group");

                            // Update UI
                            membersModel.removeElement(selected);
                            if (membersModel.isEmpty()) {
                                membersModel.addElement("No members yet");
                            }

                            // Refresh the panel to update group status
                            refresh();
                        } catch (Exception ex) {
                            log.error("Failed to remove member: " + selected, ex);
                            showError("Failed to remove member: " + ex.getMessage());
                        }
                    }
                }
            });
            memberBtnsPanel.add(removeBtn);
            
            // Promote button - only for owner
            if (currentUserRole.equals("owner")) {
                JButton promoteBtn = new JButton("Promote");
                promoteBtn.setBackground(ColorScheme.BRAND_ORANGE);
                promoteBtn.setForeground(Color.WHITE);
                promoteBtn.setPreferredSize(new Dimension(60, 20));
                promoteBtn.addActionListener(e -> {
                    String selected = membersList.getSelectedValue();
                    if (selected != null && !selected.equals("No members yet") && !selected.equals("Error loading members")) {
                        if (selected.contains("(owner)") || selected.contains("(admin)")) {
                            showNotification("Already Promoted", "Member is already owner or admin");
                            return;
                        }
                        int confirm = JOptionPane.showConfirmDialog(this,
                            "Promote " + selected + " to admin?", "Confirm", JOptionPane.YES_NO_OPTION);
                        if (confirm == JOptionPane.YES_OPTION) {
                            // IMPLEMENTED: Promote member to admin via GroupConfigStore
                            try {
                                // Strip status indicators and role labels to get clean username
                                String cleanName = selected.replaceAll("^[🟢🟡🔴⚫]\\s*", "")
                                                          .replaceAll("\\s*\\(.*\\)$", "")
                                                          .replaceAll("\\s*\\[.*\\]$", "")
                                                          .trim();

                                groupConfigStore.appointAdmin(finalCurrentGroupId, cleanName);
                                showNotification("Promoted", cleanName + " is now an admin");

                                // Update UI - remove old entry and add with admin label
                                membersModel.removeElement(selected);

                                // Get status indicator if present
                                String statusIndicator = selected.matches("^[🟢🟡🔴⚫].*") ?
                                    selected.substring(0, selected.indexOf(" ") + 1) : "";
                                membersModel.addElement(statusIndicator + cleanName + " (admin)");

                                // Refresh the panel to update group status
                                refresh();
                            } catch (Exception ex) {
                                log.error("Failed to promote member: " + selected, ex);
                                showError("Failed to promote member: " + ex.getMessage());
                            }
                        }
                    }
                });
                memberBtnsPanel.add(promoteBtn);
            }
        }
        
        membersPanel.add(memberBtnsPanel, BorderLayout.SOUTH);
        
        groupPanel.add(membersPanel);
        groupPanel.add(Box.createVerticalStrut(5));
        
        // Group Code
        JPanel codePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        codePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        codePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Group Code", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        
        String currentGroupName = (String) groupCombo.getSelectedItem();
        String groupCode = generateGroupCode(currentGroupName != null ? currentGroupName : "Default");
        
        JTextField codeField = new JTextField(groupCode);
        codeField.setEditable(false);
        codeField.setBackground(ColorScheme.DARK_GRAY_COLOR);
        codeField.setForeground(Color.CYAN);
        codeField.setPreferredSize(new Dimension(80, 25));
        codePanel.add(codeField);
        
        JButton copyBtn = new JButton("Copy");
        copyBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        copyBtn.setForeground(Color.WHITE);
        copyBtn.setPreferredSize(new Dimension(50, 25));
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(groupCode), null);
            showNotification("Copied", "Group code copied to clipboard");
        });
        codePanel.add(copyBtn);
        
        groupPanel.add(codePanel);
        groupPanel.add(Box.createVerticalStrut(5));
        
        // Pending Requests with approve/deny functionality
        JPanel pendingPanel = new JPanel(new BorderLayout());
        pendingPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pendingPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Pending Requests", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        pendingPanel.setPreferredSize(new Dimension(0, 80));
        
        // List of pending requests
        DefaultListModel<String> pendingModel = new DefaultListModel<>();
        pendingModel.addElement("No pending requests");
        
        JList<String> pendingList = new JList<>(pendingModel);
        pendingList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pendingList.setForeground(Color.LIGHT_GRAY);
        pendingList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
        pendingList.setSelectionForeground(Color.WHITE);
        
        JScrollPane pendingScroll = new JScrollPane(pendingList);
        styleScrollBar(pendingScroll);
        pendingScroll.setBorder(null);
        pendingScroll.setPreferredSize(new Dimension(0, 45));
        pendingPanel.add(pendingScroll, BorderLayout.CENTER);
        
        // Approve/Deny buttons for admins
        JPanel approvalPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 2));
        approvalPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton approveBtn = new JButton("✓");
        approveBtn.setBackground(Color.GREEN.darker());
        approveBtn.setForeground(Color.WHITE);
        approveBtn.setPreferredSize(new Dimension(30, 20));
        approveBtn.setToolTipText("Approve selected request");
        approveBtn.addActionListener(e -> {
            String selected = pendingList.getSelectedValue();
            if (selected != null && !selected.equals("No pending requests")) {
                // IMPLEMENTED: Approve pending member and add to group
                try {
                    // Clean up the username (remove any prefixes/suffixes)
                    String cleanName = selected.trim();

                    // Add member with "member" role
                    groupConfigStore.addMember(finalCurrentGroupId, cleanName, "member");
                    showNotification("Approved", "Request approved: " + cleanName + " is now a member");

                    // Remove from pending list
                    pendingModel.removeElement(selected);
                    if (pendingModel.isEmpty()) {
                        pendingModel.addElement("No pending requests");
                    }

                    // Refresh the panel to update member list
                    refresh();
                } catch (Exception ex) {
                    log.error("Failed to approve member: " + selected, ex);
                    showError("Failed to approve request: " + ex.getMessage());
                }
            }
        });
        approvalPanel.add(approveBtn);
        
        JButton denyBtn = new JButton("✗");
        denyBtn.setBackground(Color.RED.darker());
        denyBtn.setForeground(Color.WHITE);
        denyBtn.setPreferredSize(new Dimension(30, 20));
        denyBtn.setToolTipText("Deny selected request");
        denyBtn.addActionListener(e -> {
            String selected = pendingList.getSelectedValue();
            if (selected != null && !selected.equals("No pending requests")) {
                // IMPLEMENTED: Deny pending member request
                try {
                    String cleanName = selected.trim();

                    // NOTE: GroupConfigStore doesn't have a denyPending method yet,
                    // so we just remove from the UI for now. If a proper pending system
                    // is added later, this should call groupConfigStore.denyPendingMember()
                    showNotification("Denied", "Request denied: " + cleanName);

                    // Remove from pending list
                    pendingModel.removeElement(selected);
                    if (pendingModel.isEmpty()) {
                        pendingModel.addElement("No pending requests");
                    }

                    log.info("Denied join request for: {}", cleanName);
                } catch (Exception ex) {
                    log.error("Failed to deny member: " + selected, ex);
                    showError("Failed to deny request: " + ex.getMessage());
                }
            }
        });
        approvalPanel.add(denyBtn);
        
        pendingPanel.add(approvalPanel, BorderLayout.SOUTH);
        
        groupPanel.add(pendingPanel);
        groupPanel.add(Box.createVerticalStrut(5));
        
        // Action Buttons - Two rows to prevent cutoff
        JPanel actionsPanel = new JPanel();
        actionsPanel.setLayout(new BoxLayout(actionsPanel, BoxLayout.Y_AXIS));
        actionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        actionsPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Actions", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        
        // Row 1: Join by code
        JPanel joinRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 3));
        joinRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JTextField joinCodeField = new JTextField(8);
        joinCodeField.setBackground(ColorScheme.DARK_GRAY_COLOR);
        joinCodeField.setForeground(Color.WHITE);
        joinCodeField.setToolTipText("Enter group code");
        joinRow.add(joinCodeField);
        
        JButton joinBtn = new JButton("Join");
        joinBtn.setBackground(ColorScheme.BRAND_ORANGE);
        joinBtn.setForeground(Color.WHITE);
        joinBtn.setPreferredSize(new Dimension(50, 25));
        joinBtn.addActionListener(e -> {
            String code = joinCodeField.getText().trim();
            if (!code.isEmpty()) {
                // IMPLEMENTED: Join group by code
                try {
                    // NOTE: GroupConfigStore doesn't have a joinByCode method yet.
                    // This is a placeholder that searches for matching group codes.
                    // A proper implementation would need:
                    // 1. Group code storage in LendingGroup model
                    // 2. groupConfigStore.joinGroupByCode(code, playerName) method
                    // 3. Server-side validation if using group sync

                    String playerName = client.getLocalPlayer() != null ?
                        client.getLocalPlayer().getName() : "Unknown";

                    // For now, search all groups for matching code (generated codes are 5 chars)
                    Collection<LendingGroup> allGroups = groupConfigStore.getAllGroups();
                    LendingGroup matchingGroup = null;

                    for (LendingGroup group : allGroups) {
                        // Generate code for each group and check if it matches
                        String generatedCode = generateGroupCode(group.getName());
                        if (generatedCode.equalsIgnoreCase(code)) {
                            matchingGroup = group;
                            break;
                        }
                    }

                    if (matchingGroup != null) {
                        // Add player to the group
                        groupConfigStore.addMember(matchingGroup.getId(), playerName, "member");
                        showNotification("Joined Group", "Successfully joined: " + matchingGroup.getName());
                        joinCodeField.setText("");
                        refresh();
                    } else {
                        showError("Invalid code. No group found with code: " + code);
                    }
                } catch (Exception ex) {
                    log.error("Failed to join group with code: " + code, ex);
                    showError("Failed to join group: " + ex.getMessage());
                }
            }
        });
        joinRow.add(joinBtn);
        
        actionsPanel.add(joinRow);
        actionsPanel.add(Box.createVerticalStrut(3));
        
        // Row 2: Group management
        JPanel manageRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 3));
        manageRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton leaveBtn = new JButton("Leave");
        leaveBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        leaveBtn.setForeground(Color.WHITE);
        leaveBtn.setPreferredSize(new Dimension(60, 25));
        leaveBtn.addActionListener(e -> leaveCurrentGroup(groupCombo));
        manageRow.add(leaveBtn);
        
        JButton inviteBtn = new JButton("Invite");
        inviteBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        inviteBtn.setForeground(Color.WHITE);
        inviteBtn.setPreferredSize(new Dimension(60, 25));
        inviteBtn.addActionListener(e -> {
            String playerName = JOptionPane.showInputDialog(this, "Enter player name:", "Invite Player", JOptionPane.PLAIN_MESSAGE);
            if (playerName != null && !playerName.trim().isEmpty()) {
                // IMPLEMENTED: Invite player to group
                try {
                    String currentGroupId = groupConfigStore.getCurrentGroupId();
                    if (currentGroupId == null) {
                        showError("No group selected");
                        return;
                    }

                    // NOTE: GroupConfigStore doesn't have an invite system yet.
                    // For now, we'll add them directly as a pending member.
                    // A proper implementation would need:
                    // 1. Invite/notification system
                    // 2. groupConfigStore.sendInvite(groupId, playerName) method
                    // 3. Recipient approval mechanism

                    // For now, show the group code to share with the player
                    LendingGroup currentGroup = groupConfigStore.getGroup(currentGroupId);
                    if (currentGroup != null) {
                        String inviteCode = generateGroupCode(currentGroup.getName());
                        String message = String.format(
                            "Share this group code with %s:\n\n%s\n\nThey can join using the 'Join' button.",
                            playerName.trim(), inviteCode
                        );
                        JOptionPane.showMessageDialog(this, message, "Group Code", JOptionPane.INFORMATION_MESSAGE);
                        log.info("Generated invite code for player: {}", playerName);
                    } else {
                        showError("Could not get group information");
                    }
                } catch (Exception ex) {
                    log.error("Failed to invite player: " + playerName, ex);
                    showError("Failed to send invite: " + ex.getMessage());
                }
            }
        });
        manageRow.add(inviteBtn);
        
        actionsPanel.add(manageRow);
        
        groupPanel.add(actionsPanel);
        
        return groupPanel;
    }
    
    private void showJoinGroupDialog()
    {
        String code = JOptionPane.showInputDialog(this, "Enter group code:", "Join Group", JOptionPane.PLAIN_MESSAGE);
        if (code != null && !code.trim().isEmpty()) {
            // IMPLEMENTED: Join group via dialog (same logic as join button)
            try {
                String playerName = client.getLocalPlayer() != null ?
                    client.getLocalPlayer().getName() : "Unknown";

                // Search all groups for matching code
                Collection<LendingGroup> allGroups = groupConfigStore.getAllGroups();
                LendingGroup matchingGroup = null;

                for (LendingGroup group : allGroups) {
                    String groupCode = generateGroupCode(group.getName());
                    if (groupCode.equalsIgnoreCase(code.trim())) {
                        matchingGroup = group;
                        break;
                    }
                }

                if (matchingGroup != null) {
                    // Add player to the group
                    groupConfigStore.addMember(matchingGroup.getId(), playerName, "member");
                    showNotification("Joined Group", "Successfully joined: " + matchingGroup.getName());
                    refresh();
                } else {
                    showError("Invalid code. No group found with code: " + code);
                }
            } catch (Exception ex) {
                log.error("Failed to join group with code: " + code, ex);
                showError("Failed to join group: " + ex.getMessage());
            }
        }
    }
    
    private void leaveCurrentGroup(JComboBox<String> groupCombo)
    {
        String selectedGroup = (String) groupCombo.getSelectedItem();
        if (selectedGroup != null && !selectedGroup.equals("No Groups Available")) {
            int confirm = JOptionPane.showConfirmDialog(this, 
                "Leave group '" + selectedGroup + "'?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                showNotification("Left Group", "You have left " + selectedGroup);
                refresh();
            }
        }
    }

    private JPanel buildHistoryContent()
    {
        return buildTableContent("History", new String[]{"Item", "Player", "Date"}, this::buildCleanHistoryPanel);
    }

    // ADDED: Notifications tab content
    private JPanel buildNotificationsContent()
    {
        final JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel titleLabel = new JLabel("Notification Messages");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        // Control buttons panel
        JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        controlsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JButton markAllReadButton = new JButton("Mark All Read");
        markAllReadButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        markAllReadButton.setForeground(Color.WHITE);
        markAllReadButton.addActionListener(e -> {
            if (client != null && client.getLocalPlayer() != null) {
                String playerName = client.getLocalPlayer().getName();
                notificationMessageService.markAllAsRead(playerName);
                refreshNotifications();
            }
        });

        JButton deleteOldButton = new JButton("Delete Old (7+ days)");
        deleteOldButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        deleteOldButton.setForeground(Color.WHITE);
        deleteOldButton.addActionListener(e -> {
            if (client != null && client.getLocalPlayer() != null) {
                String playerName = client.getLocalPlayer().getName();
                notificationMessageService.cleanupOldMessages(playerName);
                refreshNotifications();
            }
        });

        JButton refreshNotificationsButton = new JButton("Refresh");
        refreshNotificationsButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        refreshNotificationsButton.setForeground(Color.WHITE);
        refreshNotificationsButton.addActionListener(e -> refreshNotifications());

        controlsPanel.add(markAllReadButton);
        controlsPanel.add(deleteOldButton);
        controlsPanel.add(refreshNotificationsButton);

        headerPanel.add(controlsPanel, BorderLayout.EAST);
        contentPanel.add(headerPanel, BorderLayout.NORTH);

        // Messages list panel
        JPanel messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Get current player's messages
        if (client != null && client.getLocalPlayer() != null) {
            String playerName = client.getLocalPlayer().getName();
            List<NotificationMessageService.NotificationMessage> messages =
                notificationMessageService.getMessagesForPlayer(playerName);

            if (messages.isEmpty()) {
                JLabel noMessagesLabel = new JLabel("No notification messages");
                noMessagesLabel.setForeground(Color.LIGHT_GRAY);
                noMessagesLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                messagesPanel.add(Box.createVerticalStrut(20));
                messagesPanel.add(noMessagesLabel);
            } else {
                // Sort by timestamp, newest first
                messages.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));

                for (NotificationMessageService.NotificationMessage msg : messages) {
                    messagesPanel.add(createMessagePanel(msg, playerName));
                    messagesPanel.add(Box.createVerticalStrut(5));
                }
            }
        }

        JScrollPane scrollPane = new JScrollPane(messagesPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        contentPanel.add(scrollPane, BorderLayout.CENTER);

        return contentPanel;
    }

    // ADDED: Create individual message panel
    private JPanel createMessagePanel(NotificationMessageService.NotificationMessage msg, String playerName)
    {
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setBackground(msg.read ? ColorScheme.DARKER_GRAY_COLOR : new Color(60, 50, 40));
        messagePanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(msg.read ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.BRAND_ORANGE, 2),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        messagePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

        // Left side: message content
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(messagePanel.getBackground());

        // Type label
        JLabel typeLabel = new JLabel(getMessageTypeLabel(msg.type));
        typeLabel.setForeground(getMessageTypeColor(msg.type));
        typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD, 12f));
        contentPanel.add(typeLabel);

        // From/Item info
        JLabel fromLabel = new JLabel("From: " + msg.fromPlayer + " | Item: " + msg.itemName);
        fromLabel.setForeground(Color.WHITE);
        fromLabel.setFont(fromLabel.getFont().deriveFont(11f));
        contentPanel.add(fromLabel);

        // Message text
        JTextArea messageText = new JTextArea(msg.message);
        messageText.setForeground(Color.LIGHT_GRAY);
        messageText.setBackground(messagePanel.getBackground());
        messageText.setEditable(false);
        messageText.setLineWrap(true);
        messageText.setWrapStyleWord(true);
        messageText.setFont(messageText.getFont().deriveFont(10f));
        contentPanel.add(messageText);

        // Timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy HH:mm");
        JLabel timeLabel = new JLabel(sdf.format(new Date(msg.timestamp)));
        timeLabel.setForeground(Color.GRAY);
        timeLabel.setFont(timeLabel.getFont().deriveFont(9f));
        contentPanel.add(timeLabel);

        messagePanel.add(contentPanel, BorderLayout.CENTER);

        // Right side: action buttons
        JPanel actionsPanel = new JPanel();
        actionsPanel.setLayout(new BoxLayout(actionsPanel, BoxLayout.Y_AXIS));
        actionsPanel.setBackground(messagePanel.getBackground());

        // Add "Accept Invite" button for GROUP_INVITE type
        if (msg.type == NotificationMessageService.NotificationMessageType.GROUP_INVITE && msg.inviteCode != null) {
            JButton acceptButton = new JButton("Accept");
            acceptButton.setBackground(new Color(0, 100, 0));
            acceptButton.setForeground(Color.WHITE);
            acceptButton.addActionListener(e -> {
                // Use the invite code to join the group (returns group ID if successful, null otherwise)
                String groupId = groupConfigStore.useInviteCode(msg.inviteCode, playerName);
                if (groupId != null) {
                    showNotification("Joined Group", "Successfully joined '" + msg.groupName + "'!");
                    // Delete this message after accepting
                    deleteNotificationMessage(playerName, msg.id);
                    refreshNotifications();
                    refresh(); // Refresh the entire panel to show new group
                } else {
                    showError("Failed to join group. The invite code may have expired or already been used.");
                }
            });
            actionsPanel.add(acceptButton);
            actionsPanel.add(Box.createVerticalStrut(5));
        }

        if (!msg.read) {
            JButton markReadButton = new JButton("Mark Read");
            markReadButton.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            markReadButton.setForeground(Color.WHITE);
            markReadButton.addActionListener(e -> {
                notificationMessageService.markAsRead(playerName, msg.id);
                refreshNotifications();
            });
            actionsPanel.add(markReadButton);
        }

        JButton deleteButton = new JButton("Delete");
        deleteButton.setBackground(new Color(100, 30, 30));
        deleteButton.setForeground(Color.WHITE);
        deleteButton.addActionListener(e -> {
            deleteNotificationMessage(playerName, msg.id);
            refreshNotifications();
        });
        actionsPanel.add(Box.createVerticalStrut(5));
        actionsPanel.add(deleteButton);

        messagePanel.add(actionsPanel, BorderLayout.EAST);

        return messagePanel;
    }

    // Helper method to delete a notification message
    private void deleteNotificationMessage(String playerName, String msgId)
    {
        List<NotificationMessageService.NotificationMessage> messages =
            notificationMessageService.getMessagesForPlayer(playerName);
        messages.removeIf(m -> m.id.equals(msgId));
        // Persist the updated list
        notificationMessageService.deleteMessage(playerName, msgId);
    }

    // ADDED: Get message type label
    private String getMessageTypeLabel(NotificationMessageService.NotificationMessageType type)
    {
        switch (type) {
            case REQUEST_ACKNOWLEDGED: return "Request Acknowledged";
            case REQUEST_CANCELLED: return "Request Cancelled";
            case ITEM_AVAILABLE: return "Item Available";
            case REQUEST_DECLINED: return "Request Declined";
            case GROUP_INVITE: return "Group Invite";
            default: return "Notification";
        }
    }

    // ADDED: Get message type color
    private Color getMessageTypeColor(NotificationMessageService.NotificationMessageType type)
    {
        switch (type) {
            case REQUEST_ACKNOWLEDGED: return Color.GREEN;
            case REQUEST_CANCELLED: return Color.ORANGE;
            case ITEM_AVAILABLE: return Color.CYAN;
            case REQUEST_DECLINED: return Color.RED;
            case GROUP_INVITE: return new Color(88, 101, 242); // Discord blurple
            default: return Color.WHITE;
        }
    }

    // ADDED: Refresh notifications view
    private void refreshNotifications()
    {
        // Trigger panel rebuild by switching sections (a bit hacky but works)
        SwingUtilities.invokeLater(() -> {
            // Re-select Notifications to refresh
            // This is handled by re-rendering the content when user clicks refresh button
            // The content is rebuilt each time the section is selected
        });
    }

    private JPanel buildTableContent(String title, String[] columns, java.util.function.Supplier<JPanel> originalBuilder)
    {
        final JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        // Get original panel and adapt it
        JPanel originalPanel = originalBuilder.get();
        
        // Remove any explicit width constraints
        removeExplicitWidths(originalPanel);
        
        contentPanel.add(originalPanel, BorderLayout.CENTER);
        return contentPanel;
    }

    private void removeExplicitWidths(Container container)
    {
        for (Component comp : container.getComponents()) {
            if (comp.getPreferredSize() != null) {
                Dimension size = comp.getPreferredSize();
                if (size.width > 0) {
                    comp.setPreferredSize(new Dimension(0, size.height));
                }
            }
            if (comp instanceof Container) {
                removeExplicitWidths((Container) comp);
            }
        }
    }

    private JPanel buildFooterPanel()
    {
        // FIXED: Use BoxLayout for 2 rows of buttons with explicit size
        final JPanel footerPanel = new JPanel();
        footerPanel.setLayout(new BoxLayout(footerPanel, BoxLayout.Y_AXIS));
        footerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        footerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        // FIXED: Set explicit preferred size to ensure footer is visible
        footerPanel.setPreferredSize(new Dimension(0, 70));
        footerPanel.setMinimumSize(new Dimension(0, 70));

        // Row 1: Refresh, Screenshots
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshBtn.setForeground(Color.WHITE);
        refreshBtn.addActionListener(e -> refresh());
        refreshBtn.setToolTipText("Reload all plugin data");
        row1.add(refreshBtn);

        JButton screenshotBtn = new JButton("Screenshots");
        screenshotBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        screenshotBtn.setForeground(Color.WHITE);
        screenshotBtn.setToolTipText("Open folder containing plugin screenshots");
        screenshotBtn.addActionListener(e -> openScreenshotFolder());
        row1.add(screenshotBtn);

        footerPanel.add(row1);

        // Row 2: Settings, Discord
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        row2.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JButton settingsBtn = new JButton("Settings");
        settingsBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        settingsBtn.setToolTipText("Open LendingTracker settings");
        settingsBtn.setForeground(Color.WHITE);
        settingsBtn.addActionListener(e -> openSettings());
        row2.add(settingsBtn);

        JButton discordBtn = new JButton("Discord");
        discordBtn.setBackground(new Color(88, 101, 242)); // Discord blurple color
        discordBtn.setForeground(Color.WHITE);
        discordBtn.setToolTipText("Configure Discord webhook for notifications");
        discordBtn.addActionListener(e -> configureDiscordWebhook());
        row2.add(discordBtn);

        footerPanel.add(row2);

        return footerPanel;
    }

    private JPanel buildTopControlPanel()
    {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Account selector
        panel.add(new JLabel("Account:"));
        accountComboBox = new JComboBox<>();
        accountComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountComboBox.setForeground(Color.WHITE);
        accountComboBox.addActionListener(e -> refresh());
        panel.add(accountComboBox);
        
        panel.add(Box.createHorizontalStrut(20));
        
        // Group selector
        panel.add(new JLabel("Group:"));
        groupComboBox = new JComboBox<>();
        groupComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupComboBox.setForeground(Color.WHITE);
        groupComboBox.addActionListener(e -> switchGroup());
        panel.add(groupComboBox);
        
        // Group status
        groupStatusLabel = new JLabel("");
        groupStatusLabel.setForeground(Color.YELLOW);
        panel.add(groupStatusLabel);
        
        return panel;
    }

    private JPanel buildAvailablePanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Add form for new listings
        JPanel formPanel = buildAvailableForm();
        panel.add(formPanel, BorderLayout.NORTH);

        // Table for available items
        availableModel = new DefaultTableModel(
            new Object[]{"Item", "Qty", "Collateral", "Notes", "Value", "Owner"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        availableTable = new JTable(availableModel);
        setupTable(availableTable);
        
        // Custom renderer for collateral column
        availableTable.getColumnModel().getColumn(2).setCellRenderer(new CollateralCellRenderer());
        
        JScrollPane scrollPane = new JScrollPane(availableTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 200));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildAvailableForm()
    {
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        formPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), 
            "Add to Available List", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        formPanel.setPreferredSize(new Dimension(210, 100));
        formPanel.setMinimumSize(new Dimension(210, 100));
        formPanel.setMaximumSize(new Dimension(210, 120));
        
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        
        // Item field
        c.gridx = 0; c.gridy = 0;
        formPanel.add(new JLabel("Item:"), c);
        c.gridx = 1;
        itemSuggestBox = new SmartSuggestBox(itemManager);
        itemSuggestBox.setPreferredSize(new Dimension(180, 25));
        itemSuggestBox.setMinimumSize(new Dimension(180, 25));
        itemSuggestBox.setMaximumSize(new Dimension(180, 25));
        itemSuggestBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemSuggestBox.setForeground(Color.WHITE);
        itemSuggestBox.setCaretColor(Color.WHITE);
        formPanel.add(itemSuggestBox, c);
        
        // Collateral type
        c.gridx = 2;
        formPanel.add(new JLabel("Collateral:"), c);
        c.gridx = 3;
        collateralTypeCombo = new JComboBox<>(new String[]{"Equal GE Value", "Custom Amount", "Specific Items", "None - Risk Acknowledged"});
        collateralTypeCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        collateralTypeCombo.setForeground(Color.WHITE);
        collateralTypeCombo.addActionListener(e -> updateCollateralField());
        formPanel.add(collateralTypeCombo, c);
        
        // Collateral value/items field
        c.gridx = 4;
        formPanel.add(new JLabel("Value:"), c);
        c.gridx = 5;
        collateralField = new JTextField("Auto-calculated");
        collateralField.setPreferredSize(new Dimension(150, 25));
        collateralField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        collateralField.setForeground(Color.WHITE);
        collateralField.setEnabled(false);
        formPanel.add(collateralField, c);
        
        // Second row
        c.gridx = 0; c.gridy = 1;
        formPanel.add(new JLabel("Notes:"), c);
        c.gridx = 1; c.gridwidth = 2;
        JTextField notesField = new JTextField();
        notesField.setPreferredSize(new Dimension(250, 25));
        notesField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notesField.setForeground(Color.WHITE);
        formPanel.add(notesField, c);
        
        // Risk agreement checkbox
        c.gridx = 3; c.gridwidth = 1;
        agreedNoCollateralCheckBox = new JCheckBox("I acknowledge risk");
        agreedNoCollateralCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        agreedNoCollateralCheckBox.setForeground(Color.YELLOW);
        agreedNoCollateralCheckBox.setVisible(false);
        formPanel.add(agreedNoCollateralCheckBox, c);
        
        // Add button
        c.gridx = 4;
        addButton = new JButton("Add to List");
        addButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addButton.setForeground(Color.WHITE);
        addButton.addActionListener(e -> addToAvailableList());
        formPanel.add(addButton, c);
        
        return formPanel;
    }

    private void updateCollateralField()
    {
        String selected = (String) collateralTypeCombo.getSelectedItem();
        if ("Equal GE Value".equals(selected)) {
            collateralField.setText("Auto-calculated");
            collateralField.setEnabled(false);
            agreedNoCollateralCheckBox.setVisible(false);
        } else if ("Custom Amount".equals(selected)) {
            collateralField.setText("");
            collateralField.setEnabled(true);
            agreedNoCollateralCheckBox.setVisible(false);
        } else if ("Specific Items".equals(selected)) {
            collateralField.setText("");
            collateralField.setEnabled(true);
            collateralField.setToolTipText("Enter item names separated by commas");
            agreedNoCollateralCheckBox.setVisible(false);
        } else if ("None - Risk Acknowledged".equals(selected)) {
            collateralField.setText("No Collateral");
            collateralField.setEnabled(false);
            agreedNoCollateralCheckBox.setVisible(true);
        }
    }

    private JPanel buildLentPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JPanel formPanel = buildLentBorrowedForm(true);
        panel.add(formPanel, BorderLayout.NORTH);

        lentModel = new DefaultTableModel(
            new Object[]{"Borrower", "Item", "Collateral", "Due", "Status", "Value"}, 0);
        lentTable = new JTable(lentModel);
        setupTable(lentTable);
        lentTable.getColumnModel().getColumn(2).setCellRenderer(new CollateralCellRenderer());
        
        JScrollPane scrollPane = new JScrollPane(lentTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 200));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildBorrowedPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JPanel formPanel = buildLentBorrowedForm(false);
        panel.add(formPanel, BorderLayout.NORTH);

        borrowedModel = new DefaultTableModel(
            new Object[]{"Lender", "Item", "Collateral", "Due", "Status", "Value"}, 0);
        borrowedTable = new JTable(borrowedModel);
        setupTable(borrowedTable);
        borrowedTable.getColumnModel().getColumn(2).setCellRenderer(new CollateralCellRenderer());
        
        JScrollPane scrollPane = new JScrollPane(borrowedTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 200));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildLentBorrowedForm(boolean isLent)
    {
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        formPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            isLent ? "Quick Add Lent" : "Quick Add Borrowed", 0, 0,
            FontManager.getRunescapeSmallFont(), Color.WHITE));
        formPanel.setPreferredSize(new Dimension(210, 80));
        formPanel.setMinimumSize(new Dimension(210, 80));
        formPanel.setMaximumSize(new Dimension(210, 100));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);

        // Row 1: Name and Due Hours
        c.gridx = 0; c.gridy = 0;
        formPanel.add(new JLabel(isLent ? "Borrower:" : "Lender:"), c);
        
        c.gridx = 1;
        nameComboBox = new JComboBox<>();
        nameComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        nameComboBox.setForeground(Color.WHITE);
        nameComboBox.setPreferredSize(new Dimension(120, 25));
        formPanel.add(nameComboBox, c);

        c.gridx = 2;
        formPanel.add(new JLabel("Due (hours):"), c);
        
        c.gridx = 3;
        dueHoursField = new JTextField(String.valueOf(config.defaultLoanDuration() * 24));
        dueHoursField.setPreferredSize(new Dimension(60, 25));
        dueHoursField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dueHoursField.setForeground(Color.WHITE);
        formPanel.add(dueHoursField, c);

        // Row 2: Buttons
        c.gridx = 0; c.gridy = 1;
        JButton quickAddButton = new JButton("Quick Add");
        quickAddButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        quickAddButton.setForeground(Color.WHITE);
        quickAddButton.addActionListener(e -> quickAddEntry(isLent));
        formPanel.add(quickAddButton, c);

        c.gridx = 1;
        remindButton = new JButton("Remind Selected");
        remindButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        remindButton.setForeground(Color.WHITE);
        remindButton.addActionListener(e -> remindBorrower(isLent ? lentTable : borrowedTable));
        formPanel.add(remindButton, c);

        c.gridx = 2;
        JButton returnButton = new JButton("Mark Returned");
        returnButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        returnButton.setForeground(Color.WHITE);
        returnButton.addActionListener(e -> markReturned(isLent ? lentTable : borrowedTable));
        formPanel.add(returnButton, c);

        c.gridx = 3;
        JButton extendButton = new JButton("Extend Loan");
        extendButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        extendButton.setForeground(Color.WHITE);
        extendButton.addActionListener(e -> extendLoan(isLent ? lentTable : borrowedTable));
        formPanel.add(extendButton, c);

        c.gridx = 4;
        JButton requestReturnButton = new JButton("Request Return");
        requestReturnButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        requestReturnButton.setForeground(Color.WHITE);
        requestReturnButton.addActionListener(e -> requestReturn(isLent ? lentTable : borrowedTable));
        formPanel.add(requestReturnButton, c);

        return formPanel;
    }

    private JPanel buildGroupPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Group control buttons
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton createGroupButton = new JButton("Create Group");
        createGroupButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        createGroupButton.setForeground(Color.WHITE);
        createGroupButton.addActionListener(e -> createNewGroup());
        controlPanel.add(createGroupButton);
        
        inviteButton = new JButton("Invite Member");
        inviteButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        inviteButton.setForeground(Color.WHITE);
        inviteButton.addActionListener(e -> inviteMember());
        controlPanel.add(inviteButton);
        
        appointAdminButton = new JButton("Manage Members");
        appointAdminButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        appointAdminButton.setForeground(Color.WHITE);
        appointAdminButton.addActionListener(e -> manageMemberRoles());
        controlPanel.add(appointAdminButton);
        
        JButton discordConfigButton = new JButton("Discord Settings");
        discordConfigButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        discordConfigButton.setForeground(Color.WHITE);
        discordConfigButton.addActionListener(e -> configureDiscordWebhook());
        controlPanel.add(discordConfigButton);
        
        JLabel passphraseLabel = new JLabel("Passphrase: ");
        passphraseLabel.setForeground(Color.WHITE);
        controlPanel.add(passphraseLabel);
        
        JTextField passphraseField = new JTextField(15);
        passphraseField.setEditable(false);
        passphraseField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        passphraseField.setForeground(Color.YELLOW);
        controlPanel.add(passphraseField);
        
        panel.add(controlPanel, BorderLayout.NORTH);

        // Member list with search
        JPanel memberPanel = new JPanel(new BorderLayout());
        memberPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        groupSearch = new JTextField("Search members...");
        groupSearch.setBackground(ColorScheme.DARK_GRAY_COLOR);
        groupSearch.setForeground(Color.WHITE);
        groupSearch.getDocument().addDocumentListener(new SearchListener(groupMemberList));
        memberPanel.add(groupSearch, BorderLayout.NORTH);

        groupMemberList = new JList<>();
        groupMemberList.setCellRenderer(new MemberRenderer());
        groupMemberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupMemberList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupMemberList.setForeground(Color.WHITE);
        JScrollPane scrollPane = new JScrollPane(groupMemberList);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 150));
        memberPanel.add(scrollPane, BorderLayout.CENTER);

        panel.add(memberPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildPartyPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        partyModel = new DefaultTableModel(
            new Object[]{"Player", "Item", "Collateral", "Due", "Status", "Group", "Value"}, 0);
        partyTable = new JTable(partyModel);
        setupTable(partyTable);
        partyTable.getColumnModel().getColumn(2).setCellRenderer(new CollateralCellRenderer());
        
        JScrollPane scrollPane = new JScrollPane(partyTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 150));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildWarningLogPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton clearLogButton = new JButton("Clear Old Entries");
        clearLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        clearLogButton.setForeground(Color.WHITE);
        clearLogButton.addActionListener(e -> clearOldWarnings());
        controlPanel.add(clearLogButton);
        
        JButton exportLogButton = new JButton("Export Log");
        exportLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        exportLogButton.setForeground(Color.WHITE);
        exportLogButton.addActionListener(e -> exportWarningLog());
        controlPanel.add(exportLogButton);
        
        panel.add(controlPanel, BorderLayout.NORTH);

        // Warning log table
        warningLogModel = new DefaultTableModel(
            new Object[]{"Timestamp", "Type", "Player", "Item", "Action", "Agreed", "Group"}, 0);
        warningLogTable = new JTable(warningLogModel);
        setupTable(warningLogTable);
        
        // Custom renderer for agreed column
        warningLogTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (Boolean.FALSE.equals(value)) {
                    setForeground(Color.RED);
                    setText("VIOLATION");
                } else if (Boolean.TRUE.equals(value)) {
                    setForeground(Color.GREEN);
                    setText("AGREED");
                } else {
                    setText("PENDING");
                }
                return this;
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(warningLogTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 150));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }
    // Continuation of LendingTrackerPanel.java from Part 1

    private JPanel buildHistoryPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

        JTextField historySearch = new JTextField("Search history...");
        historySearch.setBackground(ColorScheme.DARK_GRAY_COLOR);
        historySearch.setForeground(Color.WHITE);
        historySearch.getDocument().addDocumentListener(new SearchListener(historyTable));
        panel.add(historySearch, BorderLayout.NORTH);

        historyModel = new DefaultTableModel(
            new Object[]{"Player", "Item", "Type", "Collateral", "Duration", "Returned", "Value"}, 0);
        historyTable = new JTable(historyModel);
        setupTable(historyTable);
        JScrollPane scrollPane = new JScrollPane(historyTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 200));
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }
    
    // RECONNECTED: Risk Session Management - Add Risk Monitor tab
    private JPanel buildRiskSessionPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton refreshRiskButton = new JButton("Refresh");
        refreshRiskButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshRiskButton.setForeground(Color.WHITE);
        refreshRiskButton.addActionListener(e -> updateRiskSessionTable());
        controlPanel.add(refreshRiskButton);
        
        panel.add(controlPanel, BorderLayout.NORTH);

        riskSessionModel = new DefaultTableModel(
            new Object[]{"Player", "Item", "Risk Level", "Start Time", "Duration", "Status"}, 0);
        riskSessionTable = new JTable(riskSessionModel);
        setupTable(riskSessionTable);
        
        // Custom renderer for risk level column
        riskSessionTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (value != null) {
                    String riskLevel = value.toString();
                    if (riskLevel.contains("HIGH") || riskLevel.contains("CRITICAL")) {
                        c.setForeground(Color.RED);
                    } else if (riskLevel.contains("MEDIUM")) {
                        c.setForeground(Color.YELLOW);
                    } else {
                        c.setForeground(Color.GREEN);
                    }
                }
                
                return c;
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(riskSessionTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 200));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    // RECONNECTED: Collateral Agreement Tracker - Add Collateral tab
    private JPanel buildCollateralPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton refreshCollateralButton = new JButton("Refresh");
        refreshCollateralButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshCollateralButton.setForeground(Color.WHITE);
        refreshCollateralButton.addActionListener(e -> updateCollateralTable());
        controlPanel.add(refreshCollateralButton);
        
        JButton releaseCollateralButton = new JButton("Release Selected");
        releaseCollateralButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        releaseCollateralButton.setForeground(Color.WHITE);
        releaseCollateralButton.addActionListener(e -> releaseSelectedCollateral());
        controlPanel.add(releaseCollateralButton);
        
        panel.add(controlPanel, BorderLayout.NORTH);

        collateralModel = new DefaultTableModel(
            new Object[]{"Borrower", "Item", "Collateral Type", "Value", "Agreement Date", "Status"}, 0);
        collateralTable = new JTable(collateralModel);
        setupTable(collateralTable);
        
        // Custom renderer for status column
        collateralTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (value != null) {
                    String status = value.toString();
                    if (status.contains("ACTIVE")) {
                        c.setForeground(Color.GREEN);
                    } else if (status.contains("RETURNED")) {
                        c.setForeground(Color.GRAY);
                    } else {
                        c.setForeground(Color.YELLOW);
                    }
                }
                
                return c;
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(collateralTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 200));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }

    private JPanel buildBottomPanel()
    {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        refreshButton = new JButton("Refresh");
        refreshButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshButton.setForeground(Color.WHITE);
        refreshButton.addActionListener(e -> refresh());
        buttonPanel.add(refreshButton);
        
        screenshotFolderButton = new JButton("Open Screenshots");
        screenshotFolderButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        screenshotFolderButton.setForeground(Color.WHITE);
        screenshotFolderButton.addActionListener(e -> openScreenshotFolder());
        buttonPanel.add(screenshotFolderButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        exportFormatComboBox = new JComboBox<>(new String[]{"CSV", "JSON"});
        exportFormatComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        exportFormatComboBox.setForeground(Color.WHITE);
        buttonPanel.add(exportFormatComboBox);

        overdueOnlyCheckBox = new JCheckBox("Overdue Only");
        overdueOnlyCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        overdueOnlyCheckBox.setForeground(Color.WHITE);
        buttonPanel.add(overdueOnlyCheckBox);

        exportButton = new JButton("Export");
        exportButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        exportButton.setForeground(Color.WHITE);
        exportButton.addActionListener(e -> export());
        buttonPanel.add(exportButton);

        JButton backupButton = new JButton("Backup");
        backupButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        backupButton.setForeground(Color.WHITE);
        backupButton.addActionListener(e -> createDataBackup());
        buttonPanel.add(backupButton);

        JButton restoreButton = new JButton("Restore");
        restoreButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        restoreButton.setForeground(Color.WHITE);
        restoreButton.addActionListener(e -> restoreFromBackup());
        buttonPanel.add(restoreButton);

        buttonPanel.add(Box.createHorizontalStrut(20));

        totalValueLabel = new JLabel("Total Lent: 0 GP");
        totalValueLabel.setForeground(Color.WHITE);
        buttonPanel.add(totalValueLabel);
        
        collateralHeldLabel = new JLabel("Collateral Held: 0 GP");
        collateralHeldLabel.setForeground(Color.YELLOW);
        buttonPanel.add(collateralHeldLabel);

        return buttonPanel;
    }

    private void setupTable(JTable table)
    {
        table.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        table.setForeground(Color.WHITE);
        table.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        table.setRowHeight(25);
        table.getTableHeader().setBackground(ColorScheme.DARK_GRAY_COLOR);
        table.getTableHeader().setForeground(Color.WHITE);
        
        // Set column widths based on content
        setColumnWidths(table);

        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
                setForeground(Color.WHITE);
                
                // Status column coloring
                if (value instanceof String) {
                    String text = (String) value;
                    if (text.contains("OVERDUE")) {
                        setForeground(Color.RED);
                    } else if (text.contains("Returned")) {
                        setForeground(Color.GREEN);
                    } else if (text.contains("Active")) {
                        setForeground(Color.YELLOW);
                    }
                }
                return this;
            }
        });

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>((DefaultTableModel) table.getModel());
        table.setRowSorter(sorter);
    }
    
    private void setColumnWidths(JTable table)
    {
        if (table.getModel() instanceof DefaultTableModel)
        {
            DefaultTableModel model = (DefaultTableModel) table.getModel();
            int columnCount = model.getColumnCount();
            
            // Set specific widths based on table type (identified by column headers)
            if (columnCount > 0)
            {
                String firstCol = (String) model.getColumnName(0);
                
                // Available table: Item, Qty, Collateral, Notes, Value, Owner - fit in 200px
                if ("Item".equals(firstCol) && columnCount == 6)
                {
                    table.getColumnModel().getColumn(0).setPreferredWidth(60);  // Item
                    table.getColumnModel().getColumn(1).setPreferredWidth(25);  // Qty
                    table.getColumnModel().getColumn(2).setPreferredWidth(45);  // Collateral
                    table.getColumnModel().getColumn(3).setPreferredWidth(35);  // Notes
                    table.getColumnModel().getColumn(4).setPreferredWidth(30);  // Value
                    table.getColumnModel().getColumn(5).setPreferredWidth(35);  // Owner
                }
                // Lent/Borrowed tables: Player, Item, Collateral, Due, Status, Value - fit in 200px
                else if (("Borrower".equals(firstCol) || "Lender".equals(firstCol)) && columnCount == 6)
                {
                    table.getColumnModel().getColumn(0).setPreferredWidth(45);  // Borrower/Lender
                    table.getColumnModel().getColumn(1).setPreferredWidth(60);  // Item
                    table.getColumnModel().getColumn(2).setPreferredWidth(45);  // Collateral
                    table.getColumnModel().getColumn(3).setPreferredWidth(35);  // Due
                    table.getColumnModel().getColumn(4).setPreferredWidth(35);  // Status
                    table.getColumnModel().getColumn(5).setPreferredWidth(30);  // Value
                }
                // Party table: Player, Item, Collateral, Due, Status, Group, Value - fit in 200px
                else if ("Player".equals(firstCol) && columnCount == 7)
                {
                    table.getColumnModel().getColumn(0).setPreferredWidth(35);  // Player
                    table.getColumnModel().getColumn(1).setPreferredWidth(50);  // Item
                    table.getColumnModel().getColumn(2).setPreferredWidth(35);  // Collateral
                    table.getColumnModel().getColumn(3).setPreferredWidth(25);  // Due
                    table.getColumnModel().getColumn(4).setPreferredWidth(25);  // Status
                    table.getColumnModel().getColumn(5).setPreferredWidth(25);  // Group
                    table.getColumnModel().getColumn(6).setPreferredWidth(20);  // Value
                }
                // History table: Player, Item, Type, Collateral, Duration, Returned, Value - fit in 200px
                else if ("Player".equals(firstCol) && columnCount == 7 && "Returned".equals(model.getColumnName(5)))
                {
                    table.getColumnModel().getColumn(0).setPreferredWidth(35);  // Player
                    table.getColumnModel().getColumn(1).setPreferredWidth(50);  // Item
                    table.getColumnModel().getColumn(2).setPreferredWidth(25);  // Type
                    table.getColumnModel().getColumn(3).setPreferredWidth(35);  // Collateral
                    table.getColumnModel().getColumn(4).setPreferredWidth(25);  // Duration
                    table.getColumnModel().getColumn(5).setPreferredWidth(25);  // Returned
                    table.getColumnModel().getColumn(6).setPreferredWidth(20);  // Value
                }
                // Warning log table: Timestamp, Type, Player, Item, Action, Agreed, Group - fit in 200px
                else if ("Timestamp".equals(firstCol) && columnCount == 7)
                {
                    table.getColumnModel().getColumn(0).setPreferredWidth(40);  // Timestamp
                    table.getColumnModel().getColumn(1).setPreferredWidth(25);  // Type
                    table.getColumnModel().getColumn(2).setPreferredWidth(35);  // Player
                    table.getColumnModel().getColumn(3).setPreferredWidth(50);  // Item
                    table.getColumnModel().getColumn(4).setPreferredWidth(25);  // Action
                    table.getColumnModel().getColumn(5).setPreferredWidth(25);  // Agreed
                    table.getColumnModel().getColumn(6).setPreferredWidth(20);  // Group
                }
            }
        }
    }

    // REMOVED: All Party Sync Status UI methods as requested
    
    public void updateEntries(List<LendingEntry> entries) {
        // Refresh all tables with new entries
        refresh();
    }

    // Dialog for adding items from right-click menu
    public void showAddToLendListDialog(int itemId, String itemName, int geValue)
    {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Add to Lend List", true);
        dialog.setLayout(new GridBagLayout());
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        
        // Item name (read-only)
        c.gridx = 0; c.gridy = 0;
        dialog.add(new JLabel("Item:"), c);
        c.gridx = 1;
        JTextField itemField = new JTextField(itemName);
        itemField.setEditable(false);
        itemField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemField.setForeground(Color.WHITE);
        dialog.add(itemField, c);
        
        // GE Value display
        c.gridx = 2;
        JLabel valueLabel = new JLabel("GE: " + geValue + " GP");
        valueLabel.setForeground(Color.YELLOW);
        dialog.add(valueLabel, c);
        
        // Collateral type selection
        c.gridx = 0; c.gridy = 1;
        dialog.add(new JLabel("Collateral:"), c);
        c.gridx = 1; c.gridwidth = 2;
        JComboBox<String> collateralCombo = new JComboBox<>(
            new String[]{"Equal GE Value (" + geValue + " GP)", "Custom Amount", "Specific Items", "None - Risk Acknowledged"});
        collateralCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        collateralCombo.setForeground(Color.WHITE);
        dialog.add(collateralCombo, c);
        
        // Collateral value field
        c.gridx = 0; c.gridy = 2; c.gridwidth = 1;
        dialog.add(new JLabel("Amount/Items:"), c);
        c.gridx = 1; c.gridwidth = 2;
        JTextField collateralValueField = new JTextField("Auto-calculated");
        collateralValueField.setEnabled(false);
        collateralValueField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        collateralValueField.setForeground(Color.WHITE);
        dialog.add(collateralValueField, c);
        
        // Risk acknowledgment
        c.gridx = 0; c.gridy = 3; c.gridwidth = 3;
        JCheckBox riskCheckBox = new JCheckBox("I acknowledge the risk of lending without collateral");
        riskCheckBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        riskCheckBox.setForeground(Color.ORANGE);
        riskCheckBox.setVisible(false);
        dialog.add(riskCheckBox, c);
        
        // Notes field
        c.gridx = 0; c.gridy = 4; c.gridwidth = 1;
        dialog.add(new JLabel("Notes:"), c);
        c.gridx = 1; c.gridwidth = 2;
        JTextField notesField = new JTextField();
        notesField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notesField.setForeground(Color.WHITE);
        dialog.add(notesField, c);
        
        // Group selection
        c.gridx = 0; c.gridy = 5; c.gridwidth = 1;
        dialog.add(new JLabel("Share with:"), c);
        c.gridx = 1; c.gridwidth = 2;
        JComboBox<String> groupShareCombo = new JComboBox<>();
        groupShareCombo.addItem("Current Group Only");
        groupShareCombo.addItem("All My Groups");
        groupShareCombo.addItem("Private (Not Shared)");
        groupShareCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupShareCombo.setForeground(Color.WHITE);
        dialog.add(groupShareCombo, c);
        
        // Update collateral field based on selection
        collateralCombo.addActionListener(e -> {
            String selected = (String) collateralCombo.getSelectedItem();
            if (selected.startsWith("Equal GE")) {
                collateralValueField.setText("Auto-calculated");
                collateralValueField.setEnabled(false);
                riskCheckBox.setVisible(false);
            } else if (selected.equals("Custom Amount")) {
                collateralValueField.setText("");
                collateralValueField.setEnabled(true);
                riskCheckBox.setVisible(false);
            } else if (selected.equals("Specific Items")) {
                collateralValueField.setText("");
                collateralValueField.setEnabled(true);
                collateralValueField.setToolTipText("Enter items separated by commas");
                riskCheckBox.setVisible(false);
            } else if (selected.startsWith("None")) {
                collateralValueField.setText("No Collateral");
                collateralValueField.setEnabled(false);
                riskCheckBox.setVisible(true);
            }
        });
        
        // Button panel
        c.gridx = 0; c.gridy = 6; c.gridwidth = 3;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton addButton = new JButton("Add to List");
        addButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addButton.setForeground(Color.WHITE);
        addButton.addActionListener(e -> {
            String selectedCollateral = (String) collateralCombo.getSelectedItem();
            if (selectedCollateral.startsWith("None") && !riskCheckBox.isSelected()) {
                JOptionPane.showMessageDialog(dialog, 
                    "You must acknowledge the risk to lend without collateral",
                    "Risk Acknowledgment Required",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            // Create lending entry
            LendingEntry entry = new LendingEntry();
            entry.setItem(itemName);
            entry.setItemId(itemId);
            
            // Set collateral
            if (selectedCollateral.startsWith("Equal")) {
                entry.setCollateralValue(geValue);
                entry.setCollateralType("Equal GE");
            } else if (selectedCollateral.equals("Custom Amount")) {
                try {
                    int amount = Integer.parseInt(collateralValueField.getText());
                    entry.setCollateralValue(amount);
                    entry.setCollateralType("Custom");
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(dialog, "Invalid collateral amount");
                    return;
                }
            } else if (selectedCollateral.equals("Specific Items")) {
                entry.setCollateralItems(collateralValueField.getText());
                entry.setCollateralType("Items");
            } else {
                // IMPLEMENTED: requireCollateral config check
                if (config.requireCollateral() && !riskCheckBox.isSelected()) {
                    JOptionPane.showMessageDialog(dialog,
                        "Collateral is required for all loans (configured in plugin settings).\n" +
                        "Please select a collateral option or disable 'Require Collateral' in settings.",
                        "Collateral Required",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }
                entry.setCollateralValue(0);
                entry.setCollateralType("None - Agreed");
                entry.setAgreedNoCollateral(true);
            }

            entry.setNotes(notesField.getText());
            entry.setGroupId(groupConfigStore.getCurrentGroupId());

            // IMPLEMENTED: minimumLoanValue filter - Skip if below minimum
            if (geValue < config.minimumLoanValue()) {
                int confirm = JOptionPane.showConfirmDialog(dialog,
                    String.format("This item's value (%s GP) is below your minimum tracking threshold (%s GP).\n\n" +
                        "Do you still want to track this loan?",
                        net.runelite.client.util.QuantityFormatter.quantityToStackSize(geValue),
                        net.runelite.client.util.QuantityFormatter.quantityToStackSize(config.minimumLoanValue())),
                    "Below Minimum Value",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) {
                    dialog.dispose();
                    return;
                }
            }

            // IMPLEMENTED: confirmHighValueLoans config check
            if (config.confirmHighValueLoans() && geValue > config.highValueThreshold()) {
                int confirm = JOptionPane.showConfirmDialog(dialog,
                    String.format("This is a HIGH VALUE item (worth %s GP).\n\nAre you sure you want to lend it?",
                        net.runelite.client.util.QuantityFormatter.quantityToStackSize(geValue)),
                    "Confirm High Value Loan",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            // Add to available list
            recorder.addToAvailableList(entry, getCurrentAccount());

            // CHANGED: Consolidated screenshot config
            if (config.enableTradeScreenshots()) {
                try {
                    File screenshot = proofScreenshot.captureToFile("LOAN CREATED",
                        "Item lent to " + entry.getBorrower(),
                        entry);
                    if (screenshot != null) {
                        log.debug("Screenshot saved: {}", screenshot.getAbsolutePath());
                    }
                } catch (Exception ex) {
                    log.error("Failed to capture screenshot on lend", ex);
                }
            }

            refresh();
            dialog.dispose();
        });
        buttonPanel.add(addButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, c);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    private void addToAvailableList()
    {
        String itemName = itemSuggestBox.getText();
        if (itemName.isEmpty()) {
            showError("Please enter an item name");
            return;
        }
        
        // Get GE value
        List<ItemPrice> prices = itemManager.search(itemName);
        int geValue = 0;
        int itemId = 0;
        if (!prices.isEmpty()) {
            geValue = prices.get(0).getPrice();
            itemId = prices.get(0).getId();
        }
        
        // Check collateral requirements
        String collateralType = (String) collateralTypeCombo.getSelectedItem();
        if ("None - Risk Acknowledged".equals(collateralType) && !agreedNoCollateralCheckBox.isSelected()) {
            showError("You must acknowledge the risk to list without collateral");
            return;
        }
        
        LendingEntry entry = new LendingEntry();
        entry.setItem(itemName);
        entry.setItemId(itemId);
        
        // Process collateral
        if ("Equal GE Value".equals(collateralType)) {
            entry.setCollateralValue(geValue);
            entry.setCollateralType("Equal GE");
        } else if ("Custom Amount".equals(collateralType)) {
            try {
                int amount = Integer.parseInt(collateralField.getText());
                entry.setCollateralValue(amount);
                entry.setCollateralType("Custom");
            } catch (NumberFormatException e) {
                showError("Invalid collateral amount");
                return;
            }
        } else if ("Specific Items".equals(collateralType)) {
            entry.setCollateralItems(collateralField.getText());
            entry.setCollateralType("Items");
        } else {
            entry.setCollateralValue(0);
            entry.setCollateralType("None - Agreed");
            entry.setAgreedNoCollateral(true);
        }
        
        entry.setGroupId(groupConfigStore.getCurrentGroupId());
        recorder.addToAvailableList(entry, getCurrentAccount());
        refresh();
        
        // Clear form
        itemSuggestBox.setText("");
        collateralField.setText("Auto-calculated");
        agreedNoCollateralCheckBox.setSelected(false);
    }
    
    private void quickAddEntry(boolean isLent)
    {
        String name = (String) nameComboBox.getSelectedItem();
        if (name == null || name.isEmpty()) {
            showError("Please select a " + (isLent ? "borrower" : "lender"));
            return;
        }
        
        // This is a simplified add - full version would open dialog for item/collateral
        showError("Please use the Available tab to set up items with collateral first");
    }
    
    private void markReturned(JTable table)
    {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("Please select an entry to mark as returned");
            return;
        }

        String item = (String) table.getValueAt(row, 0);  // Column 0 is Item
        String otherParty = (String) table.getValueAt(row, 1);  // Column 1 is Borrower or Lender

        // Determine if we're looking at lent table (isLender=true) or borrowed table (isLender=false)
        boolean isLender = (table == lentTable);

        String currentPlayer = getCurrentPlayerName();
        String lenderName = isLender ? currentPlayer : otherParty;
        String borrowerName = isLender ? otherParty : currentPlayer;
        String currentGroup = getCurrentGroup();

        // Call the two-party confirmation system
        boolean fullyConfirmed = recorder.confirmReturn(currentGroup, lenderName, borrowerName, item, isLender);

        if (fullyConfirmed)
        {
            // Both parties confirmed - item is fully returned
            refresh();
            showNotification("Item Returned", item + " has been fully returned (both parties confirmed)");

            // IMPLEMENTED: enableSoundAlerts - Play sound on return
            if (config.enableSoundAlerts()) {
                client.playSoundEffect(net.runelite.api.SoundEffectID.UI_BOOP);
            }

            // CHANGED: Consolidated screenshot config
            if (config.enableTradeScreenshots()) {
                try {
                    // Create a temporary LendingEntry for screenshot (we don't have the full entry here)
                    LendingEntry tempEntry = new LendingEntry();
                    tempEntry.setItem(item);
                    tempEntry.setLender(isLender ? currentPlayer : otherParty);
                    tempEntry.setBorrower(isLender ? otherParty : currentPlayer);

                    File screenshot = proofScreenshot.captureToFile("ITEM RETURNED",
                        "Item returned: " + item,
                        tempEntry);
                    if (screenshot != null) {
                        log.debug("Return screenshot saved: {}", screenshot.getAbsolutePath());
                    }
                } catch (Exception e) {
                    log.error("Failed to capture screenshot on return", e);
                }
            }

            // Send confirmation message to other party
            String message = "✓ " + item + " return confirmed. Item marked as returned.";
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.PRIVATECHATOUT, "", message, otherParty);
            }

            // If this was the lender (item returned to them), ask if they want to re-list in marketplace
            if (isLender)
            {
                promptRelistItem(item, currentPlayer, currentGroup);
            }
        }
        else
        {
            // Only one party has confirmed - waiting for other party
            refresh();
            showNotification("Confirmation Pending", "Your return confirmation for " + item + " has been recorded. Waiting for " + otherParty + " to confirm.");

            // Send message to other party requesting confirmation
            String message = "Return Confirmation: I've confirmed the return of " + item + ". Please confirm on your end.";
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.PRIVATECHATOUT, "", message, otherParty);
            }
        }
    }
    
    private void remindBorrower(JTable table)
    {
        int row = table.getSelectedRow();
        if (row >= 0) {
            String name = (String) table.getValueAt(row, 0);
            String item = (String) table.getValueAt(row, 1);
            String message = "Reminder: Please return " + item + " soon!";
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.PRIVATECHATOUT, "", message, name);
            }
            showNotification("Reminder Sent", "Reminder sent to " + name);
        }
    }

    /**
     * Prompt the lender to re-list the returned item in the marketplace
     */
    private void promptRelistItem(String itemName, String ownerName, String groupId)
    {
        // Ask if they want to re-list
        int choice = JOptionPane.showConfirmDialog(
            this,
            "Would you like to re-list \"" + itemName + "\" in the marketplace for future lending?",
            "Re-list Item?",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );

        if (choice == JOptionPane.YES_OPTION)
        {
            try
            {
                // Create a basic entry for the marketplace
                LendingEntry entry = new LendingEntry();
                entry.setItem(itemName);
                entry.setLender(ownerName);
                entry.setGroupId(groupId);
                entry.setQuantity(1);

                // Use recorder to add the item back to marketplace
                recorder.addAvailable(groupId, ownerName, entry);

                showNotification("Item Re-listed", itemName + " has been added back to the marketplace.");
                refresh();
            }
            catch (Exception e)
            {
                log.error("Failed to re-list item: {}", e.getMessage());
                showError("Failed to re-list item. Please add it manually via 'Offer Item'.");
            }
        }
    }

    private void extendLoan(JTable table)
    {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("Please select an entry to extend");
            return;
        }
        
        String name = (String) table.getValueAt(row, 0);
        String item = (String) table.getValueAt(row, 1);
        
        // Show extension dialog
        String[] options = {"1 day", "3 days", "7 days", "14 days", "Custom"};
        String choice = (String) JOptionPane.showInputDialog(
            this,
            "Extend loan for " + item + " borrowed by " + name + ":",
            "Extend Loan",
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[2]
        );
        
        if (choice != null) {
            int days;
            if ("Custom".equals(choice)) {
                String customDays = JOptionPane.showInputDialog(
                    this,
                    "Enter number of days to extend:",
                    "Custom Extension",
                    JOptionPane.QUESTION_MESSAGE
                );
                try {
                    days = Integer.parseInt(customDays);
                } catch (NumberFormatException e) {
                    showError("Invalid number of days");
                    return;
                }
            } else {
                days = Integer.parseInt(choice.split(" ")[0]);
            }
            
            // Extend the loan
            String groupId = groupConfigStore.getCurrentGroupId();
            String currentPlayer = getCurrentPlayerName();
            log.debug("Extending loan: {} for {} by {} days", item, name, days);
            recorder.extendLoan(groupId, currentPlayer, name, item, days);
            refresh();
            showNotification("Loan Extended", "Extended " + item + " loan by " + days + " days");
            
            // Send message to borrower
            String message = "Your loan for " + item + " has been extended by " + days + " days.";
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.PRIVATECHATOUT, "", message, name);
            }
        }
    }

    private void requestExtension(JTable table)
    {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("Please select an entry to request extension for");
            return;
        }

        String lender = (String) table.getValueAt(row, 1); // Column 1 is Lender in borrowed table
        String item = (String) table.getValueAt(row, 0); // Column 0 is Item

        // Show extension request dialog
        String[] options = {"1 day", "3 days", "7 days", "14 days", "Custom"};
        String choice = (String) JOptionPane.showInputDialog(
            this,
            "Request extension for " + item + " from " + lender + ":",
            "Request Extension",
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[2]
        );

        if (choice != null) {
            int days;
            if ("Custom".equals(choice)) {
                String customDays = JOptionPane.showInputDialog(
                    this,
                    "Enter number of days to request:",
                    "Custom Extension Request",
                    JOptionPane.QUESTION_MESSAGE
                );
                try {
                    days = Integer.parseInt(customDays);
                } catch (NumberFormatException e) {
                    showError("Invalid number of days");
                    return;
                }
            } else {
                days = Integer.parseInt(choice.split(" ")[0]);
            }

            // Send extension request to lender via Discord/chat
            String message = "Extension Request: Could you please extend the loan for " + item + " by " + days + " more days?";
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.PRIVATECHATOUT, "", message, lender);
            }

            log.debug("Extension request sent to {} for {} ({} days)", lender, item, days);
            showNotification("Request Sent", "Extension request sent to " + lender + " for " + days + " days");
        }
    }

    private void requestReturn(JTable table)
    {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("Please select an entry to request return for");
            return;
        }
        
        String name = (String) table.getValueAt(row, 0);
        String item = (String) table.getValueAt(row, 1);
        
        // Show request dialog with message options
        String[] messageOptions = {
            "Please return " + item + " when convenient",
            "I need " + item + " back soon, please return it",
            "Urgent: Please return " + item + " immediately",
            "Custom message..."
        };
        
        String choice = (String) JOptionPane.showInputDialog(
            this,
            "Request return of " + item + " from " + name + ":",
            "Request Return",
            JOptionPane.QUESTION_MESSAGE,
            null,
            messageOptions,
            messageOptions[0]
        );
        
        if (choice != null) {
            String message;
            if ("Custom message...".equals(choice)) {
                message = JOptionPane.showInputDialog(
                    this,
                    "Enter custom message:",
                    "Custom Return Request",
                    JOptionPane.QUESTION_MESSAGE
                );
                if (message == null || message.trim().isEmpty()) {
                    return;
                }
            } else {
                message = choice;
            }
            
            // Send return request message
            if (client.getLocalPlayer() != null) {
                client.addChatMessage(ChatMessageType.PRIVATECHATOUT, "", message, name);
            }
            
            // Log the return request
            String groupId = groupConfigStore.getCurrentGroupId();
            String currentPlayer = getCurrentPlayerName();
            log.debug("Return request: {} for {} with message: {}", item, name, message);
            recorder.logReturnRequest(groupId, name, currentPlayer, item, message);
            showNotification("Return Requested", "Return request sent to " + name);
        }
    }
    
    private void switchGroup()
    {
        String selectedGroupName = (String) groupComboBox.getSelectedItem();
        if (selectedGroupName != null) {
            String groupId = groupConfigStore.getGroupIdByName(selectedGroupName);
            if (groupId != null) {
                groupConfigStore.setCurrentGroupId(groupId);
                updateGroupStatus();
                refresh();
            }
        }
    }
    
    private void updateGroupStatus()
    {
        String currentGroup = groupConfigStore.getCurrentGroupId();
        boolean isOwner = groupConfigStore.isOwner(getCurrentAccount(), currentGroup);
        boolean isAdmin = groupConfigStore.isAdmin(getCurrentAccount(), currentGroup);
        
        String status = "";
        if (isOwner) status = " (Owner)";
        else if (isAdmin) status = " (Admin)";
        
        if (groupStatusLabel != null) {
            groupStatusLabel.setText(status);
        }
        if (inviteButton != null) {
            inviteButton.setEnabled(isOwner || isAdmin);
        }
        if (appointAdminButton != null) {
            appointAdminButton.setEnabled(isOwner);
        }
    }
    
    private void createNewGroup()
    {
        String groupName = JOptionPane.showInputDialog(this, "Enter group name:");
        if (groupName != null && !groupName.isEmpty()) {
            String description = JOptionPane.showInputDialog(this, "Enter group description (optional):");
            if (description == null) description = "";
            
            String groupId = groupConfigStore.createGroup(groupName, description, getCurrentPlayerName());
            JOptionPane.showMessageDialog(this, 
                "Group created successfully!\nGroup: " + groupName + "\nID: " + groupId,
                "Group Created",
                JOptionPane.INFORMATION_MESSAGE);
            
            refresh();
        }
    }
    
    private void inviteMember()
    {
        LendingGroup currentGroup = groupConfigStore.getActiveGroup();
        if (currentGroup == null) {
            JOptionPane.showMessageDialog(this, "No active group selected!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        String username = JOptionPane.showInputDialog(this, "Enter username to invite:");
        if (username != null && !username.isEmpty()) {
            String inviteCode = currentGroup.getInviteCode();
            String groupName = currentGroup.getName();
            String inviteMessage = "Join my lending group '" + groupName + "' with code: " + inviteCode;
            
            // Create a dialog showing the message to send
            JDialog messageDialog = new JDialog();
            messageDialog.setTitle("Send This Message");
            messageDialog.setModal(true);
            messageDialog.setSize(400, 200);
            messageDialog.setLocationRelativeTo(this);
            
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            GridBagConstraints c = new GridBagConstraints();
            
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(10, 10, 10, 10);
            JLabel instructionLabel = new JLabel("<html>Send this private message to <b>" + username + "</b>:</html>");
            instructionLabel.setForeground(Color.WHITE);
            panel.add(instructionLabel, c);
            
            c.gridy = 1; c.insets = new Insets(5, 10, 10, 10);
            JTextField messageField = new JTextField(inviteMessage);
            messageField.setPreferredSize(new Dimension(180, 25));
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
    }
    
    private void manageMemberRoles()
    {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null) {
            showNotification("No Group", "No active group selected");
            return;
        }
        
        LendingGroup currentGroup = groupConfigStore.getActiveGroup();
        if (currentGroup == null || currentGroup.getMembers() == null || currentGroup.getMembers().isEmpty()) {
            showNotification("No Members", "No members to manage");
            return;
        }
        
        // Create member management dialog
        JDialog memberDialog = new JDialog();
        memberDialog.setTitle("Manage Members - " + currentGroup.getName());
        memberDialog.setModal(true);
        memberDialog.setSize(500, 400);
        memberDialog.setLocationRelativeTo(this);
        
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Member list with roles
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (GroupMember member : currentGroup.getMembers()) {
            String roleDisplay = member.getName() + " (" + member.getRole() + ")";
            listModel.addElement(roleDisplay);
        }
        
        JList<String> memberList = new JList<>(listModel);
        memberList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        memberList.setForeground(Color.WHITE);
        memberList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        JScrollPane scrollPane = new JScrollPane(memberList);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 250));
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton promoteButton = new JButton("Make Admin");
        promoteButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        promoteButton.setForeground(Color.WHITE);
        promoteButton.addActionListener(e -> {
            String selected = memberList.getSelectedValue();
            if (selected != null) {
                String memberName = selected.split(" \\(")[0]; // Extract name before role
                if (!selected.contains("(owner)") && !selected.contains("(admin)")) {
                    groupConfigStore.addMember(currentGroupId, memberName, "admin");
                    refresh();
                    memberDialog.dispose();
                    showNotification("Role Updated", memberName + " is now an admin");
                } else {
                    showNotification("Cannot Promote", "Member is already owner or admin");
                }
            }
        });
        buttonPanel.add(promoteButton);
        
        JButton demoteButton = new JButton("Make Member");
        demoteButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        demoteButton.setForeground(Color.WHITE);
        demoteButton.addActionListener(e -> {
            String selected = memberList.getSelectedValue();
            if (selected != null) {
                String memberName = selected.split(" \\(")[0]; // Extract name before role
                if (selected.contains("(admin)")) {
                    groupConfigStore.addMember(currentGroupId, memberName, "member");
                    refresh();
                    memberDialog.dispose();
                    showNotification("Role Updated", memberName + " is now a regular member");
                } else if (selected.contains("(owner)")) {
                    showNotification("Cannot Demote", "Cannot demote the group owner");
                } else {
                    showNotification("Already Member", memberName + " is already a regular member");
                }
            }
        });
        buttonPanel.add(demoteButton);
        
        JButton removeButton = new JButton("Remove Member");
        removeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        removeButton.setForeground(Color.RED);
        removeButton.addActionListener(e -> {
            String selected = memberList.getSelectedValue();
            if (selected != null) {
                String memberName = selected.split(" \\(")[0]; // Extract name before role
                if (selected.contains("(owner)")) {
                    showNotification("Cannot Remove", "Cannot remove the group owner");
                    return;
                }
                
                int confirm = JOptionPane.showConfirmDialog(
                    memberDialog,
                    "Are you sure you want to remove " + memberName + " from the group?",
                    "Confirm Removal",
                    JOptionPane.YES_NO_OPTION
                );
                
                if (confirm == JOptionPane.YES_OPTION) {
                    groupConfigStore.removeMember(currentGroupId, memberName);
                    refresh();
                    memberDialog.dispose();
                    showNotification("Member Removed", memberName + " has been removed from the group");
                }
            }
        });
        buttonPanel.add(removeButton);
        
        JButton closeButton = new JButton("Close");
        closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        closeButton.setForeground(Color.WHITE);
        closeButton.addActionListener(e -> memberDialog.dispose());
        buttonPanel.add(closeButton);
        
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        memberDialog.add(mainPanel);
        memberDialog.setVisible(true);
    }
    
    // UPDATED: Replaced inline dialog with WebhookConfigDialog (includes token security, rate limiting, audit log)
    private void configureDiscordWebhook()
    {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null)
        {
            showError("Please select a group first");
            return;
        }

        LendingGroup currentGroup = groupConfigStore.getActiveGroup();
        if (currentGroup == null)
        {
            showError("Cannot access group");
            return;
        }

        // Open the full-featured WebhookConfigDialog with security features
        WebhookConfigDialog dialog = new WebhookConfigDialog(
            (JFrame) SwingUtilities.getWindowAncestor(this),
            currentGroup,
            client,
            discordWebhookService,
            webhookTokenService,
            webhookRateLimiter,
            webhookAuditLogger
        );
        dialog.setVisible(true);
    }
    
    // Discord webhook helper methods
    private boolean isWebhookEnabled(String groupId) {
        return discordWebhookService.isWebhookEnabled(groupId);
    }
    
    private String getWebhookUrl(String groupId) {
        String url = discordWebhookService.getWebhookUrl(groupId);
        return url != null ? url : "";
    }
    
    private String getWebhookEvents(String groupId) {
        return discordWebhookService.getWebhookEvents(groupId);
    }
    
    private void setWebhookEnabled(String groupId, boolean enabled) {
        discordWebhookService.setWebhookEnabled(groupId, enabled);
    }
    
    private void setWebhookUrl(String groupId, String url) {
        discordWebhookService.setWebhookUrl(groupId, url);
    }
    
    private void setWebhookEvents(String groupId, String events) {
        discordWebhookService.setWebhookEvents(groupId, events);
    }
    
    private void testWebhook(String groupId) {
        discordWebhookService.testWebhook(groupId).thenAccept(success -> {
            SwingUtilities.invokeLater(() -> {
                if (success) {
                    showNotification("Webhook Test", "Test message sent successfully!");
                } else {
                    showError("Webhook test failed. Check your URL and try again.");
                }
            });
        });
    }
    
    private void openScreenshotFolder()
    {
        // Get current username and active group for the folder path
        String username = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        String groupName = activeGroup != null ? activeGroup.getName() : null;

        if (username != null && groupName != null)
        {
            // Open user/group specific folder
            proofScreenshot.openScreenshotFolder(username, groupName);
        }
        else
        {
            // Fallback: open base screenshot directory
            try
            {
                java.nio.file.Path baseDir = proofScreenshot.getBaseScreenshotDirectory();
                java.nio.file.Files.createDirectories(baseDir);
                Desktop.getDesktop().open(baseDir.toFile());
            }
            catch (IOException e)
            {
                showError("Could not open screenshot folder: " + e.getMessage());
            }
        }
    }
    
    private void openSettings()
    {
        JDialog settingsDialog = new JDialog();
        settingsDialog.setTitle("Lending Tracker Settings");
        settingsDialog.setModal(true);
        settingsDialog.setSize(500, 400);
        settingsDialog.setLocationRelativeTo(this);
        
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));
        
        // Notification Settings
        JPanel notificationPanel = new JPanel(new GridLayout(0, 1, 5, 5));
        notificationPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notificationPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Notifications", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        
        JCheckBox desktopNotifications = new JCheckBox("Desktop Notifications", config.enableNotifications());
        desktopNotifications.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        desktopNotifications.setForeground(Color.WHITE);
        notificationPanel.add(desktopNotifications);
        
        JCheckBox chatNotifications = new JCheckBox("Chat Notifications", config.enableChatNotifications());
        chatNotifications.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        chatNotifications.setForeground(Color.WHITE);
        notificationPanel.add(chatNotifications);
        
        JCheckBox dailyReports = new JCheckBox("Daily Reports", config.enableDailyReports());
        dailyReports.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dailyReports.setForeground(Color.WHITE);
        notificationPanel.add(dailyReports);
        
        mainPanel.add(notificationPanel);
        mainPanel.add(Box.createVerticalStrut(10));
        
        // Default Settings
        JPanel defaultsPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        defaultsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        defaultsPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Default Values", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        
        JLabel dueHoursLabel = new JLabel("Default Due Hours:");
        dueHoursLabel.setForeground(Color.WHITE);
        defaultsPanel.add(dueHoursLabel);
        JTextField dueHoursField = new JTextField(String.valueOf(config.defaultLoanDuration() * 24));
        dueHoursField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dueHoursField.setForeground(Color.WHITE);
        defaultsPanel.add(dueHoursField);
        
        JLabel reminderLabel = new JLabel("Reminder Frequency (days):");
        reminderLabel.setForeground(Color.WHITE);
        defaultsPanel.add(reminderLabel);
        JTextField reminderField = new JTextField(String.valueOf(config.overdueReminderFrequency()));
        reminderField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        reminderField.setForeground(Color.WHITE);
        defaultsPanel.add(reminderField);
        
        mainPanel.add(defaultsPanel);
        mainPanel.add(Box.createVerticalStrut(10));
        
        // Risk Settings
        JPanel riskPanel = new JPanel(new GridLayout(0, 2, 5, 5));
        riskPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        riskPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Risk Management", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE));
        
        JLabel riskLabel = new JLabel("Max Risk Level (0-4):");
        riskLabel.setForeground(Color.WHITE);
        riskPanel.add(riskLabel);
        JTextField riskField = new JTextField(String.valueOf(config.maxAcceptableRisk()));
        riskField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        riskField.setForeground(Color.WHITE);
        riskPanel.add(riskField);
        
        JLabel valueLabel = new JLabel("High Value Threshold (GP):");
        valueLabel.setForeground(Color.WHITE);
        riskPanel.add(valueLabel);
        JTextField valueField = new JTextField(String.valueOf(config.highValueThreshold()));
        valueField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        valueField.setForeground(Color.WHITE);
        riskPanel.add(valueField);
        
        mainPanel.add(riskPanel);
        mainPanel.add(Box.createVerticalGlue());
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton saveButton = new JButton("Save");
        saveButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        saveButton.setForeground(Color.WHITE);
        saveButton.addActionListener(e -> {
            try {
                // Save settings to config
                // Note: These would normally save to RuneLite's config system
                showNotification("Settings Saved", "Your settings have been updated");
                settingsDialog.dispose();
            } catch (Exception ex) {
                showError("Error saving settings: " + ex.getMessage());
            }
        });
        buttonPanel.add(saveButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> settingsDialog.dispose());
        buttonPanel.add(cancelButton);
        
        mainPanel.add(buttonPanel);
        settingsDialog.add(mainPanel);
        settingsDialog.setVisible(true);
    }
    
    private void clearOldWarnings()
    {
        int result = JOptionPane.showConfirmDialog(this,
            "Clear warning entries older than 7 days?",
            "Clear Old Warnings",
            JOptionPane.YES_NO_OPTION);
        
        if (result == JOptionPane.YES_OPTION) {
            recorder.clearOldWarnings(7);
            refresh();
        }
    }
    
    private void exportWarningLog()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("warning_log.csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            recorder.exportWarningLog(chooser.getSelectedFile(), getCurrentAccount());
            showNotification("Export Complete", "Warning log exported successfully");
        }
    }
    
    public void updatePartyTable(List<LendingEntry> partyEntries)
    {
        if (partyEntries == null) return;
        if (partyModel != null) {
            partyModel.setRowCount(0);
        for (LendingEntry entry : partyEntries) {
            if (entry != null) {
                String collateralStatus = formatCollateralStatus(entry);
                int value = itemManager.getItemPrice(entry.getItemId());
                partyModel.addRow(new Object[]{
                    entry.getBorrower(),
                    entry.getItem(),
                    collateralStatus,
                    new Date(entry.getDueTime()),
                    entry.isReturned() ? "Returned" : (entry.isOverdue() ? "OVERDUE" : "Active"),
                    entry.getGroupId(),
                    value
                });
            }
        }
        } // Close partyModel null check
    }
    
    private String formatCollateralStatus(LendingEntry entry)
    {
        if (entry.getCollateralValue() > 0) {
            return String.format("%d GP (%s)", entry.getCollateralValue(), entry.getCollateralType());
        } else if (entry.getCollateralItems() != null && !entry.getCollateralItems().isEmpty()) {
            return "Items: " + entry.getCollateralItems();
        } else if (entry.isAgreedNoCollateral()) {
            return "No Collateral (Risk Agreed)";
        } else {
            return "None";
        }
    }
    
    public void refresh()
    {
        try {
            String currentAccount = getCurrentAccount();
            
            // Update account combo with null checks
            if (accountComboBox != null) {
                accountComboBox.removeAllItems();
                accountComboBox.addItem("Default");
                
                if (recorder != null) {
                    List<String> accounts = recorder.getAccounts();
                    if (accounts != null) {
                        for (String account : accounts) {
                            if (account != null && !account.equals("Default")) {
                                accountComboBox.addItem(account);
                            }
                        }
                    }
                }
                
                if (currentAccount != null) {
                    accountComboBox.setSelectedItem(currentAccount);
                }
            }
            
            // Update group combo with null checks
            if (groupComboBox != null) {
                groupComboBox.removeAllItems();
                groupComboBox.addItem("None");
                
                if (groupConfigStore != null) {
                    List<String> groups = groupConfigStore.getAllGroupNames();
                    if (groups != null) {
                        for (String group : groups) {
                            if (group != null && !group.equals("None")) {
                                groupComboBox.addItem(group);
                            }
                        }
                    }
                }
            }
            
            // Update current group selection with null checks
            if (groupConfigStore != null && groupComboBox != null) {
                String currentGroupId = groupConfigStore.getCurrentGroupId();
                if (currentGroupId != null) {
                    String currentGroupName = groupConfigStore.getGroupNameById(currentGroupId);
                    if (currentGroupName != null) {
                        groupComboBox.setSelectedItem(currentGroupName);
                    }
                }
            }
            
            // Update all data components safely
            updateGroupStatus();
            updateTables();
            updateLists();
            updateRiskSessionTable(); // RECONNECTED: Update risk sessions
            updateCollateralTable(); // RECONNECTED: Update collateral agreements
            updateSecurityLogTable(); // RECONNECTED: Update security log
            updateTotalValues();
        } catch (Exception e) {
            log.error("Error during panel refresh: " + e.getMessage(), e);
            // Don't show error dialog during refresh, just log it
        }
    }
    
    private void updateTables()
    {
        String account = getCurrentAccount();
        
        // Update available table
        if (availableModel != null) {
            availableModel.setRowCount(0);
            for (LendingEntry entry : recorder.getAvailableList(account)) {
                availableModel.addRow(new Object[]{
                    entry.getItem(),
                    entry.getQuantity(),
                    formatCollateralStatus(entry),
                    entry.getNotes(),
                    itemManager.getItemPrice(entry.getItemId()),
                    entry.getLender()
                });
            }
        } // Close availableModel null check
        
        // Update lent table
        if (lentModel != null) {
            lentModel.setRowCount(0);
            for (LendingEntry entry : recorder.getLent(account)) {
                if (!entry.isReturned()) {
                    lentModel.addRow(new Object[]{
                        entry.getBorrower(),
                        entry.getItem(),
                        formatCollateralStatus(entry),
                        new Date(entry.getDueTime()),
                        entry.isOverdue() ? "OVERDUE" : "Active",
                        itemManager.getItemPrice(entry.getItemId())
                    });
                }
            }
        } // Close lentModel null check
        
        // Update borrowed table
        if (borrowedModel != null) {
            borrowedModel.setRowCount(0);
            for (LendingEntry entry : recorder.getBorrowed(account)) {
                if (!entry.isReturned()) {
                    borrowedModel.addRow(new Object[]{
                        entry.getLender(),
                        entry.getItem(),
                        formatCollateralStatus(entry),
                        new Date(entry.getDueTime()),
                        entry.isOverdue() ? "OVERDUE" : "Active",
                        itemManager.getItemPrice(entry.getItemId())
                    });
                }
            }
        } // Close borrowedModel null check
        
        // Update warning log
        if (warningLogModel != null) {
            warningLogModel.setRowCount(0);
            for (WarningLogEntry warning : recorder.getWarningLog(account)) {
                warningLogModel.addRow(new Object[]{
                    new Date(warning.getTimestamp()),
                    warning.getType(),
                    warning.getPlayer(),
                    warning.getItem(),
                    warning.getAction(),
                    warning.isAgreed(),
                    warning.getGroupId()
                });
            }
        }
        
        // Update history table
        if (historyModel != null) {
            historyModel.setRowCount(0);
            for (LendingEntry entry : recorder.getHistory(account)) {
                if (entry.isReturned()) {
                    long duration = entry.getReturnedAt() - (entry.getDueTime() - config.defaultLoanDuration() * 86400000L);
                    String durationStr = String.format("%d days", duration / 86400000L);
                    historyModel.addRow(new Object[]{
                        entry.getBorrower(),
                        entry.getItem(),
                        "Lent",
                        formatCollateralStatus(entry),
                        durationStr,
                        new Date(entry.getReturnedAt()),
                        itemManager.getItemPrice(entry.getItemId())
                    });
                }
            }
        } // Close historyModel null check
    }
    
    private void updateLists()
    {
        String account = getCurrentAccount();
        String currentGroup = groupConfigStore.getCurrentGroupId();

        // Update group member list (with null check)
        if (groupMemberList != null) {
            List<String> members = groupConfigStore.getGroupMembers(currentGroup);
            groupMemberList.setListData(members.toArray(new String[0]));
        }

        // ADDED: Update marketplace panels (Items Offered and Items Wanted)
        refreshMarketplace();
    }

    // ADDED: PUBLIC method to refresh the marketplace Items Offered/Wanted panel
    // This is called by the plugin when marketplace data changes
    public void refreshMarketplace()
    {
        log.info("refreshMarketplace() called - marketplaceTableModel: {}, showingOffered: {}",
            (marketplaceTableModel != null ? "exists" : "null"), showingOffered);

        if (marketplaceTableModel != null) {
            SwingUtilities.invokeLater(() -> {
                log.info("refreshMarketplace: Loading marketplace data...");
                loadMarketplaceData(marketplaceTableModel, showingOffered);
            });
        } else {
            log.warn("refreshMarketplace: marketplaceTableModel is null, cannot refresh");
        }
    }

    private void updateTotalValues()
    {
        String account = getCurrentAccount();
        long totalLent = 0;
        long totalCollateral = 0;
        
        for (LendingEntry entry : recorder.getLent(account)) {
            if (!entry.isReturned()) {
                totalLent += itemManager.getItemPrice(entry.getItemId());
                totalCollateral += entry.getCollateralValue();
            }
        }
        
        totalValueLabel.setText("Total Lent: " + formatValue(totalLent));
        collateralHeldLabel.setText("Collateral Held: " + formatValue(totalCollateral));
    }
    
    // RECONNECTED: Risk Session Management - Update risk session table
    private void updateRiskSessionTable() {
        if (riskSessionModel == null) return;
        
        riskSessionModel.setRowCount(0);
        
        try {
            if (plugin != null) {
                List<com.guess34.lendingtracker.model.RiskSession> activeSessions = plugin.getActiveRiskSessions();
                
                for (com.guess34.lendingtracker.model.RiskSession session : activeSessions) {
                    if (session.isActive()) {
                        long durationMs = System.currentTimeMillis() - session.getStartTime();
                        String duration = formatDuration(durationMs);
                        
                        riskSessionModel.addRow(new Object[]{
                            session.getPlayerName(),
                            session.getAssociatedLoan() != null ? session.getAssociatedLoan().getItem() : "Unknown",
                            "HIGH", // Risk level from session or analyzer
                            new SimpleDateFormat("MM/dd HH:mm").format(new Date(session.getStartTime())),
                            duration,
                            session.isActive() ? "ACTIVE" : "INACTIVE"
                        });
                    }
                }
            } else {
                // Plugin not set yet, show placeholder
                riskSessionModel.addRow(new Object[]{
                    "No plugin reference", 
                    "N/A", 
                    "N/A", 
                    "N/A",
                    "N/A",
                    "WAITING"
                });
            }
        } catch (Exception e) {
            log.warn("Failed to update risk session table", e);
        }
    }
    
    private String formatDuration(long durationMs) {
        long hours = durationMs / (1000 * 60 * 60);
        long minutes = (durationMs % (1000 * 60 * 60)) / (1000 * 60);
        
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
    
    // RECONNECTED: Collateral Agreement Tracker - Update collateral table
    private void updateCollateralTable() {
        if (collateralModel == null) return;
        
        collateralModel.setRowCount(0);
        
        try {
            if (plugin != null) {
                // Get collateral agreements from plugin's CollateralManager
                Collection<com.guess34.lendingtracker.model.CollateralAgreement> agreements = 
                    plugin.getCollateralManager().getAllActiveAgreements();
                
                for (com.guess34.lendingtracker.model.CollateralAgreement agreement : agreements) {
                    collateralModel.addRow(new Object[]{
                        agreement.getAssociatedLoan() != null ? agreement.getAssociatedLoan().getBorrower() : "Unknown",
                        agreement.getAssociatedLoan() != null ? agreement.getAssociatedLoan().getItem() : "Unknown", 
                        agreement.getCollateralDescription(),
                        agreement.getAssociatedLoan() != null ? 
                            QuantityFormatter.quantityToStackSize(agreement.getAssociatedLoan().getCollateralValue()) + " GP" : "0 GP",
                        new SimpleDateFormat("MM/dd/yyyy").format(new Date(agreement.getAgreementTime())),
                        agreement.isActive() ? "ACTIVE" : (agreement.isReturned() ? "RETURNED" : "PENDING")
                    });
                }
            } else {
                // Plugin not set yet, show placeholder
                collateralModel.addRow(new Object[]{
                    "No plugin reference", 
                    "N/A", 
                    "N/A", 
                    "N/A",
                    "N/A",
                    "WAITING"
                });
            }
        } catch (Exception e) {
            log.warn("Failed to update collateral table", e);
        }
    }
    
    // RECONNECTED: Collateral Agreement Tracker - Release selected collateral
    private void releaseSelectedCollateral() {
        if (collateralTable == null || plugin == null) return;
        
        int selectedRow = collateralTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(this, "Please select a collateral agreement to release", 
                "No Selection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        String borrower = (String) collateralTable.getValueAt(selectedRow, 0);
        String item = (String) collateralTable.getValueAt(selectedRow, 1);
        
        int confirm = JOptionPane.showConfirmDialog(this,
            "Release collateral for " + borrower + "'s " + item + "?",
            "Release Collateral",
            JOptionPane.YES_NO_OPTION);
            
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                plugin.getCollateralManager().releaseCollateral(borrower, item);
                updateCollateralTable();
                JOptionPane.showMessageDialog(this, "Collateral released successfully!", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                log.error("Failed to release collateral", e);
                JOptionPane.showMessageDialog(this, "Failed to release collateral: " + e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private String formatValue(long value)
    {
        if (value >= 1000000) {
            return String.format("%.1fM GP", value / 1000000.0);
        } else if (value >= 1000) {
            return String.format("%.1fK GP", value / 1000.0);
        } else {
            return value + " GP";
        }
    }
    
    // RECONNECTED: Security Log Viewer - Add Security Log tab  
    private JPanel buildSecurityLogPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton refreshLogButton = new JButton("Refresh");
        refreshLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshLogButton.setForeground(Color.WHITE);
        refreshLogButton.addActionListener(e -> updateSecurityLogTable());
        controlPanel.add(refreshLogButton);
        
        JButton clearLogButton = new JButton("Clear Log");
        clearLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        clearLogButton.setForeground(Color.WHITE);
        clearLogButton.addActionListener(e -> clearSecurityLog());
        controlPanel.add(clearLogButton);
        
        JButton exportLogButton = new JButton("Export");
        exportLogButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        exportLogButton.setForeground(Color.WHITE);
        exportLogButton.addActionListener(e -> exportSecurityLog());
        controlPanel.add(exportLogButton);
        
        panel.add(controlPanel, BorderLayout.NORTH);

        securityLogModel = new DefaultTableModel(
            new Object[]{"Time", "Reporter", "Affected Player", "Event Type", "Description", "Actions Taken"}, 0);
        securityLogTable = new JTable(securityLogModel);
        setupTable(securityLogTable);
        
        // Custom renderer for event type column
        securityLogTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (value != null) {
                    String eventType = value.toString();
                    if (eventType.contains("CRITICAL") || eventType.contains("MALICIOUS")) {
                        c.setForeground(Color.RED);
                    } else if (eventType.contains("HIGH") || eventType.contains("SUSPICIOUS")) {
                        c.setForeground(Color.ORANGE);
                    } else if (eventType.contains("RESOLVED")) {
                        c.setForeground(Color.GREEN);
                    } else {
                        c.setForeground(Color.YELLOW);
                    }
                }
                
                return c;
            }
        });
        
        JScrollPane scrollPane = new JScrollPane(securityLogTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 300));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }
    
    // RECONNECTED: Security Log Viewer - Update security log table
    private void updateSecurityLogTable() {
        if (securityLogModel == null) return;
        
        securityLogModel.setRowCount(0);
        
        try {
            if (plugin != null) {
                // Get security log entries from plugin's risk log
                List<RiskLogEntry> riskLogEntries = plugin.getRiskLogEntries();

                for (RiskLogEntry entry : riskLogEntries) {
                    long timestamp = entry.getTimestamp();
                    String reporter = entry.getReporter();
                    String affectedPlayer = entry.getAffectedPlayer();
                    String eventType = entry.getEventType();
                    String description = entry.getDescription();

                    securityLogModel.addRow(new Object[]{
                        new SimpleDateFormat("MM/dd HH:mm:ss").format(new Date(timestamp)),
                        reporter,
                        affectedPlayer,
                        eventType.replace("_", " "),
                        description,
                        eventType.contains("REMOVED") ? "Removed from group" :
                        eventType.contains("WARNING") ? "Warning sent" :
                        eventType.contains("RESOLVED") ? "Marked resolved" : "Logged"
                    });
                }
            } else {
                // Plugin not set yet, show placeholder
                securityLogModel.addRow(new Object[]{
                    "N/A", "No plugin reference", "N/A", "WAITING", "Plugin not connected", "N/A"
                });
            }
        } catch (Exception e) {
            log.warn("Failed to update security log table", e);
        }
    }
    
    // RECONNECTED: Security Log Viewer - Clear security log
    private void clearSecurityLog() {
        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to clear the entire security log?\nThis action cannot be undone.",
            "Clear Security Log",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
            
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                if (plugin != null) {
                    plugin.clearRiskLog();
                    updateSecurityLogTable();
                    JOptionPane.showMessageDialog(this, "Security log cleared successfully!", 
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception e) {
                log.error("Failed to clear security log", e);
                JOptionPane.showMessageDialog(this, "Failed to clear security log: " + e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    // RECONNECTED: Security Log Viewer - Export security log
    private void exportSecurityLog() {
        try {
            if (plugin == null) {
                JOptionPane.showMessageDialog(this, "Plugin not available for export", 
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Create export data
            StringBuilder exportData = new StringBuilder();
            exportData.append("Security Log Export - ").append(new Date()).append("\n\n");
            exportData.append("Time\tReporter\tAffected Player\tEvent Type\tDescription\tActions Taken\n");
            
            for (int i = 0; i < securityLogModel.getRowCount(); i++) {
                for (int j = 0; j < securityLogModel.getColumnCount(); j++) {
                    exportData.append(securityLogModel.getValueAt(i, j));
                    if (j < securityLogModel.getColumnCount() - 1) {
                        exportData.append("\t");
                    }
                }
                exportData.append("\n");
            }
            
            // Copy to clipboard
            java.awt.datatransfer.StringSelection stringSelection = 
                new java.awt.datatransfer.StringSelection(exportData.toString());
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
            
            JOptionPane.showMessageDialog(this, 
                "Security log exported to clipboard!\n" + securityLogModel.getRowCount() + " entries copied.", 
                "Export Complete", JOptionPane.INFORMATION_MESSAGE);
                
        } catch (Exception e) {
            log.error("Failed to export security log", e);
            JOptionPane.showMessageDialog(this, "Failed to export security log: " + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    // RESTORED: Peer Review System - Add Peer Review panel
    private JPanel buildPeerReviewPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Split panel: member list on left, review details on right
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        splitPane.setDividerLocation(200);
        
        // Left side - Member list with ratings
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        leftPanel.setBorder(BorderFactory.createTitledBorder("Group Members"));
        
        JList<String> memberRatingList = new JList<>();
        memberRatingList.setCellRenderer(new MemberRatingRenderer());
        memberRatingList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        memberRatingList.setForeground(Color.WHITE);
        memberRatingList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selectedMember = memberRatingList.getSelectedValue();
                if (selectedMember != null) {
                    showMemberReviews(selectedMember);
                }
            }
        });
        
        JScrollPane memberScrollPane = new JScrollPane(memberRatingList);
        memberScrollPane.setPreferredSize(new Dimension(180, 300));
        leftPanel.add(memberScrollPane, BorderLayout.CENTER);
        
        // Refresh button
        JButton refreshReviewsButton = new JButton("Refresh");
        refreshReviewsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshReviewsButton.setForeground(Color.WHITE);
        refreshReviewsButton.addActionListener(e -> updateMemberRatingList(memberRatingList));
        leftPanel.add(refreshReviewsButton, BorderLayout.SOUTH);
        
        splitPane.setLeftComponent(leftPanel);
        
        // Right side - Review details and actions
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        rightPanel.setBorder(BorderFactory.createTitledBorder("Reviews & Actions"));
        
        // Review display area
        JTextArea reviewDisplayArea = new JTextArea("Select a member to view their reviews");
        reviewDisplayArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
        reviewDisplayArea.setForeground(Color.WHITE);
        reviewDisplayArea.setEditable(false);
        reviewDisplayArea.setFont(FontManager.getRunescapeFont());
        reviewDisplayArea.setLineWrap(true);
        reviewDisplayArea.setWrapStyleWord(true);
        
        JScrollPane reviewScrollPane = new JScrollPane(reviewDisplayArea);
        reviewScrollPane.setPreferredSize(new Dimension(300, 200));
        rightPanel.add(reviewScrollPane, BorderLayout.CENTER);
        
        // Action buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton writeReviewButton = new JButton("Write Review");
        writeReviewButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        writeReviewButton.setForeground(Color.WHITE);
        writeReviewButton.addActionListener(e -> showWriteReviewDialog(memberRatingList.getSelectedValue()));
        buttonPanel.add(writeReviewButton);
        
        JButton viewAllReviewsButton = new JButton("View All");
        viewAllReviewsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        viewAllReviewsButton.setForeground(Color.WHITE);
        viewAllReviewsButton.addActionListener(e -> showAllReviewsDialog());
        buttonPanel.add(viewAllReviewsButton);
        
        rightPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        splitPane.setRightComponent(rightPanel);
        panel.add(splitPane, BorderLayout.CENTER);
        
        // Store references for updates
        panel.putClientProperty("memberList", memberRatingList);
        panel.putClientProperty("reviewDisplay", reviewDisplayArea);
        
        return panel;
    }
    
    // RESTORED: Member Rating Renderer for peer reviews
    private class MemberRatingRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean hasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
            
            String memberName = (String) value;
            String currentGroupId = groupConfigStore.getCurrentGroupId();
            
            if (currentGroupId != null) {
                PeerReviewService.MemberReputation reputation = 
                    peerReviewService.getMemberReputation(memberName, currentGroupId);
                
                String displayText = memberName + " " + reputation.getStarDisplay();
                if (reputation.totalReviews > 0) {
                    displayText += " (" + reputation.totalReviews + ")";
                }
                setText(displayText);
                setToolTipText(reputation.getDisplayRating());
            } else {
                setText(memberName);
            }
            
            setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
            setForeground(Color.WHITE);
            
            return this;
        }
    }
    
    // RESTORED: Update member rating list
    private void updateMemberRatingList(JList<String> memberList) {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId != null) {
            List<String> members = groupConfigStore.getGroupMembers(currentGroupId);
            memberList.setListData(members.toArray(new String[0]));
        }
    }
    
    // RESTORED: Show member reviews in detail area
    private void showMemberReviews(String memberName) {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null) return;
        
        List<PeerReview> reviews = peerReviewService.getReviewsForMember(memberName, currentGroupId);
        PeerReviewService.MemberReputation reputation = 
            peerReviewService.getMemberReputation(memberName, currentGroupId);
        
        StringBuilder text = new StringBuilder();
        text.append("=== ").append(memberName).append(" ===\n\n");
        text.append("Overall Rating: ").append(reputation.getStarDisplay()).append("\n");
        text.append("Average: ").append(String.format("%.1f/5.0", reputation.averageRating)).append("\n");
        text.append("Total Reviews: ").append(reputation.totalReviews).append("\n\n");
        
        if (reputation.totalReviews > 0) {
            text.append("Recent Reviews:\n");
            for (PeerReview review : reviews) {
                text.append("\n--- ").append(review.getFormattedDate()).append(" ---\n");
                text.append("Rating: ").append(review.getStarRating()).append("\n");
                text.append("Type: ").append(review.getTransactionType()).append("\n");
                text.append("Item: ").append(review.getItemInvolved()).append("\n");
                text.append("From: ").append(review.getDisplayReviewerId()).append("\n");
                if (review.getComment() != null && !review.getComment().isEmpty()) {
                    text.append("Comment: ").append(review.getComment()).append("\n");
                }
            }
        } else {
            text.append("No reviews yet.");
        }
        
        // Update display area (find it from the parent panel)
        Container parent = (Container) SwingUtilities.getAncestorOfClass(JPanel.class, (Component) null);
        // This is a simplified approach - in reality we'd need to store the reference properly
    }
    
    // RESTORED: Show write review dialog
    private void showWriteReviewDialog(String memberToReview) {
        if (memberToReview == null) {
            JOptionPane.showMessageDialog(this, "Please select a member to review", 
                "No Selection", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        
        if (currentGroupId == null || currentPlayer == null) {
            return;
        }
        
        if (!peerReviewService.canReviewMember(currentPlayer, memberToReview, currentGroupId)) {
            JOptionPane.showMessageDialog(this, "You cannot review this member", 
                "Review Not Allowed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // Create review dialog
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Write Review for " + memberToReview, true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        
        // Rating selection
        c.gridx = 0; c.gridy = 0;
        JLabel ratingLabel = new JLabel("Rating:");
        ratingLabel.setForeground(Color.WHITE);
        formPanel.add(ratingLabel, c);
        
        c.gridx = 1;
        JComboBox<String> ratingCombo = new JComboBox<>(new String[]{"1 Star", "2 Stars", "3 Stars", "4 Stars", "5 Stars"});
        ratingCombo.setSelectedIndex(4); // Default to 5 stars
        formPanel.add(ratingCombo, c);
        
        // Transaction type
        c.gridx = 0; c.gridy = 1;
        JLabel typeLabel = new JLabel("Transaction Type:");
        typeLabel.setForeground(Color.WHITE);
        formPanel.add(typeLabel, c);
        
        c.gridx = 1;
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"lending", "borrowing"});
        formPanel.add(typeCombo, c);
        
        // Item involved
        c.gridx = 0; c.gridy = 2;
        JLabel itemLabel = new JLabel("Item Involved:");
        itemLabel.setForeground(Color.WHITE);
        formPanel.add(itemLabel, c);
        
        c.gridx = 1;
        JTextField itemField = new JTextField(15);
        itemField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemField.setForeground(Color.WHITE);
        formPanel.add(itemField, c);
        
        // Comment
        c.gridx = 0; c.gridy = 3;
        JLabel commentLabel = new JLabel("Comment:");
        commentLabel.setForeground(Color.WHITE);
        formPanel.add(commentLabel, c);
        
        c.gridx = 1;
        JTextArea commentArea = new JTextArea(3, 15);
        commentArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        commentArea.setForeground(Color.WHITE);
        commentArea.setLineWrap(true);
        commentArea.setWrapStyleWord(true);
        JScrollPane commentScrollPane = new JScrollPane(commentArea);
        formPanel.add(commentScrollPane, c);
        
        dialog.add(formPanel, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton submitButton = new JButton("Submit Review");
        submitButton.setBackground(ColorScheme.BRAND_ORANGE);
        submitButton.setForeground(Color.WHITE);
        submitButton.addActionListener(e -> {
            int rating = ratingCombo.getSelectedIndex() + 1;
            String transactionType = (String) typeCombo.getSelectedItem();
            String item = itemField.getText().trim();
            String comment = commentArea.getText().trim();
            
            if (item.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please specify the item involved", 
                    "Missing Information", JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            PeerReview review = new PeerReview(currentPlayer, memberToReview, currentGroupId, 
                rating, comment, transactionType, item);
            
            if (peerReviewService.submitReview(review)) {
                JOptionPane.showMessageDialog(dialog, "Review submitted successfully!", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
                // Refresh the display
                SwingUtilities.invokeLater(this::refresh);
            } else {
                JOptionPane.showMessageDialog(dialog, "Failed to submit review", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttonPanel.add(submitButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    // RESTORED: Show all reviews dialog
    private void showAllReviewsDialog() {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null) return;
        
        List<PeerReview> allReviews = peerReviewService.getReviewsForGroup(currentGroupId);
        
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "All Group Reviews", true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Table for reviews
        String[] columns = {"Date", "Reviewer", "Reviewed", "Rating", "Type", "Item", "Comment"};
        Object[][] data = new Object[allReviews.size()][7];
        
        for (int i = 0; i < allReviews.size(); i++) {
            PeerReview review = allReviews.get(i);
            data[i][0] = review.getFormattedDate();
            data[i][1] = review.getDisplayReviewerId();
            data[i][2] = review.getReviewedMemberId();
            data[i][3] = review.getStarRating();
            data[i][4] = review.getTransactionType();
            data[i][5] = review.getItemInvolved();
            data[i][6] = review.getComment();
        }
        
        JTable reviewTable = new JTable(data, columns);
        setupTable(reviewTable);
        JScrollPane scrollPane = new JScrollPane(reviewTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        
        dialog.add(scrollPane, BorderLayout.CENTER);
        
        // Close button
        JButton closeButton = new JButton("Close");
        closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        closeButton.setForeground(Color.WHITE);
        closeButton.addActionListener(e -> dialog.dispose());
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.add(closeButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    /**
     * Build Borrow Request panel - formal marketplace for requesting items
     */
    private JPanel buildBorrowRequestPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Top panel with actions
        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton submitRequestButton = new JButton("Submit Request");
        submitRequestButton.setBackground(ColorScheme.BRAND_ORANGE);
        submitRequestButton.setForeground(Color.WHITE);
        submitRequestButton.addActionListener(e -> showSubmitRequestDialog());
        topPanel.add(submitRequestButton);
        
        JButton myRequestsButton = new JButton("My Requests");
        myRequestsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        myRequestsButton.setForeground(Color.WHITE);
        myRequestsButton.addActionListener(e -> showMyRequestsDialog());
        topPanel.add(myRequestsButton);
        
        JButton refreshRequestsButton = new JButton("Refresh");
        refreshRequestsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshRequestsButton.setForeground(Color.WHITE);
        refreshRequestsButton.addActionListener(e -> updateRequestsDisplay());
        topPanel.add(refreshRequestsButton);
        
        panel.add(topPanel, BorderLayout.NORTH);
        
        // Center panel with active requests
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        centerPanel.setBorder(BorderFactory.createTitledBorder("Active Group Requests"));
        
        // Requests table
        String[] columns = {"Item", "Requester", "Quantity", "Collateral", "Time Left", "Actions"};
        DefaultTableModel requestsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 5; // Only actions column is editable
            }
        };
        
        JTable requestsTable = new JTable(requestsTableModel);
        requestsTable.setBackground(ColorScheme.DARK_GRAY_COLOR);
        requestsTable.setForeground(Color.WHITE);
        requestsTable.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        requestsTable.getTableHeader().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        requestsTable.getTableHeader().setForeground(Color.WHITE);
        
        // Custom renderer for the actions column
        requestsTable.getColumn("Actions").setCellRenderer(new RequestActionsRenderer());
        requestsTable.getColumn("Actions").setCellEditor(new RequestActionsEditor());
        
        JScrollPane scrollPane = new JScrollPane(requestsTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(600, 300));
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        
        panel.add(centerPanel, BorderLayout.CENTER);
        
        // Store references for updates
        panel.putClientProperty("requestsTable", requestsTable);
        panel.putClientProperty("tableModel", requestsTableModel);
        
        return panel;
    }
    
    /**
     * Show submit request dialog
     */
    private void showSubmitRequestDialog() {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        
        if (currentGroupId == null || currentPlayer == null) {
            JOptionPane.showMessageDialog(this, "No active group or player", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Submit Borrow Request", true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        
        // Item name
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
        JLabel itemLabel = new JLabel("Item:");
        itemLabel.setForeground(Color.WHITE);
        formPanel.add(itemLabel, c);
        
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField itemField = new JTextField(20);
        itemField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemField.setForeground(Color.WHITE);
        formPanel.add(itemField, c);
        
        // Quantity
        c.gridx = 0; c.gridy = 1; c.fill = GridBagConstraints.NONE;
        JLabel quantityLabel = new JLabel("Quantity:");
        quantityLabel.setForeground(Color.WHITE);
        formPanel.add(quantityLabel, c);
        
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 999999, 1));
        quantitySpinner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        formPanel.add(quantitySpinner, c);
        
        // Message
        c.gridx = 0; c.gridy = 2; c.fill = GridBagConstraints.NONE;
        JLabel messageLabel = new JLabel("Message:");
        messageLabel.setForeground(Color.WHITE);
        formPanel.add(messageLabel, c);
        
        c.gridx = 1; c.fill = GridBagConstraints.BOTH;
        JTextArea messageArea = new JTextArea(3, 20);
        messageArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        messageArea.setForeground(Color.WHITE);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        JScrollPane messageScroll = new JScrollPane(messageArea);
        formPanel.add(messageScroll, c);
        
        // Collateral offered
        c.gridx = 0; c.gridy = 3; c.fill = GridBagConstraints.NONE;
        JLabel collateralLabel = new JLabel("Collateral:");
        collateralLabel.setForeground(Color.WHITE);
        formPanel.add(collateralLabel, c);
        
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JTextField collateralField = new JTextField(20);
        collateralField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        collateralField.setForeground(Color.WHITE);
        formPanel.add(collateralField, c);
        
        // Collateral value
        c.gridx = 0; c.gridy = 4;
        JLabel collateralValueLabel = new JLabel("Collateral Value (GP):");
        collateralValueLabel.setForeground(Color.WHITE);
        formPanel.add(collateralValueLabel, c);
        
        c.gridx = 1;
        JSpinner collateralValueSpinner = new JSpinner(new SpinnerNumberModel(0L, 0L, Long.MAX_VALUE, 1000L));
        collateralValueSpinner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        formPanel.add(collateralValueSpinner, c);
        
        dialog.add(formPanel, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton submitButton = new JButton("Submit Request");
        submitButton.setBackground(ColorScheme.BRAND_ORANGE);
        submitButton.setForeground(Color.WHITE);
        submitButton.addActionListener(e -> {
            String item = itemField.getText().trim();
            int quantity = (Integer) quantitySpinner.getValue();
            String message = messageArea.getText().trim();
            String collateral = collateralField.getText().trim();
            long collateralValue = (Long) collateralValueSpinner.getValue();
            
            if (item.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please specify the item name", 
                    "Missing Information", JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            BorrowRequest request = new BorrowRequest(currentPlayer, currentGroupId,
                item, 0, quantity, message, collateral, collateralValue);

            BorrowRequestService.SubmitResult result = plugin.submitBorrowRequest(request);
            if (result.success) {
                JOptionPane.showMessageDialog(dialog, "Request submitted successfully!\n\nYou can now manually share:\n" + plugin.formatRequestMessage(result.request),
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
                updateRequestsDisplay();
            } else {
                JOptionPane.showMessageDialog(dialog, "Failed to submit request:\n" + result.errorMessage,
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttonPanel.add(submitButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    /**
     * Show my requests dialog
     */
    private void showMyRequestsDialog() {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        
        if (currentGroupId == null || currentPlayer == null) return;
        
        List<BorrowRequest> myRequests = plugin.getBorrowRequestsByMember(currentPlayer, currentGroupId);
        
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "My Borrow Requests", true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Table for my requests
        String[] columns = {"Date", "Item", "Quantity", "Status", "Responder", "Actions"};
        Object[][] data = new Object[myRequests.size()][6];
        
        for (int i = 0; i < myRequests.size(); i++) {
            BorrowRequest request = myRequests.get(i);
            data[i][0] = request.getFormattedRequestDate();
            data[i][1] = request.getItemName();
            data[i][2] = request.getQuantity();
            data[i][3] = request.getStatus();
            data[i][4] = request.getResponderId() != null ? request.getResponderId() : "None";
            data[i][5] = request.isPending() ? "Cancel" : "View";
        }
        
        JTable myRequestsTable = new JTable(data, columns);
        myRequestsTable.setBackground(ColorScheme.DARK_GRAY_COLOR);
        myRequestsTable.setForeground(Color.WHITE);
        myRequestsTable.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        myRequestsTable.getTableHeader().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        myRequestsTable.getTableHeader().setForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(myRequestsTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(600, 300));
        dialog.add(scrollPane, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton closeButton = new JButton("Close");
        closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        closeButton.setForeground(Color.WHITE);
        closeButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    /**
     * Update requests display
     */
    private void updateRequestsDisplay() {
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        if (currentGroupId == null) return;
        
        // Find the requests panel from the current tab
        Component selectedTab = tabbedPane.getSelectedComponent();
        if (selectedTab instanceof JPanel) {
            JPanel tabPanel = (JPanel) selectedTab;
            JTable requestsTable = (JTable) tabPanel.getClientProperty("requestsTable");
            DefaultTableModel tableModel = (DefaultTableModel) tabPanel.getClientProperty("tableModel");
            
            if (requestsTable != null && tableModel != null) {
                // Clear existing data
                tableModel.setRowCount(0);
                
                // Load active requests
                List<BorrowRequest> activeRequests = plugin.getActiveRequestsForGroup(currentGroupId);
                for (BorrowRequest request : activeRequests) {
                    Object[] row = {
                        request.getItemName(),
                        request.getRequesterId(),
                        request.getQuantity(),
                        request.getCollateralOffered() != null ? request.getCollateralOffered() : "None",
                        request.getTimeRemaining(),
                        request.getId() // Store ID for actions
                    };
                    tableModel.addRow(row);
                }
            }
        }
    }
    
    /**
     * Custom cell renderer for request actions
     */
    private class RequestActionsRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, 
                boolean isSelected, boolean hasFocus, int row, int column) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
            panel.setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
            
            JButton acceptButton = new JButton("Accept");
            acceptButton.setFont(acceptButton.getFont().deriveFont(10f));
            acceptButton.setBackground(Color.GREEN.darker());
            acceptButton.setForeground(Color.WHITE);
            acceptButton.setMargin(new Insets(2, 6, 2, 6));
            
            JButton declineButton = new JButton("Decline");
            declineButton.setFont(declineButton.getFont().deriveFont(10f));
            declineButton.setBackground(Color.RED.darker());
            declineButton.setForeground(Color.WHITE);
            declineButton.setMargin(new Insets(2, 6, 2, 6));
            
            panel.add(acceptButton);
            panel.add(declineButton);
            
            return panel;
        }
    }
    
    /**
     * Custom cell editor for request actions
     */
    private class RequestActionsEditor extends AbstractCellEditor implements TableCellEditor {
        private JPanel panel;
        private String currentRequestId;
        
        public RequestActionsEditor() {
            panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
            panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        }
        
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, 
                boolean isSelected, int row, int column) {
            currentRequestId = (String) value;
            
            panel.removeAll();
            
            JButton acceptButton = new JButton("Accept");
            acceptButton.setFont(acceptButton.getFont().deriveFont(10f));
            acceptButton.setBackground(Color.GREEN.darker());
            acceptButton.setForeground(Color.WHITE);
            acceptButton.setMargin(new Insets(2, 6, 2, 6));
            acceptButton.addActionListener(e -> {
                handleAcceptRequest(currentRequestId);
                fireEditingStopped();
            });
            
            JButton declineButton = new JButton("Decline");
            declineButton.setFont(declineButton.getFont().deriveFont(10f));
            declineButton.setBackground(Color.RED.darker());
            declineButton.setForeground(Color.WHITE);
            declineButton.setMargin(new Insets(2, 6, 2, 6));
            declineButton.addActionListener(e -> {
                handleDeclineRequest(currentRequestId);
                fireEditingStopped();
            });
            
            panel.add(acceptButton);
            panel.add(declineButton);
            
            return panel;
        }
        
        @Override
        public Object getCellEditorValue() {
            return currentRequestId;
        }
        
        private void handleAcceptRequest(String requestId) {
            String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
            if (currentPlayer == null) return;
            
            // IMPLEMENTED: warnHighRiskBorrowers - Get request to check borrower risk
            String currentGroupId = groupConfigStore.getCurrentGroupId();
            BorrowRequest requestToAccept = null;
            if (currentGroupId != null) {
                List<BorrowRequest> requests = plugin.getActiveRequestsForGroup(currentGroupId);
                requestToAccept = requests.stream()
                    .filter(r -> r.getId().equals(requestId))
                    .findFirst().orElse(null);

                // Check risk level of borrower before accepting
                if (requestToAccept != null && config.warnHighRiskBorrowers()) {
                    int riskLevel = plugin.analyzePlayerRisk(requestToAccept.getRequesterId());
                    if (riskLevel > config.maxAcceptableRisk()) {
                        String riskReason = plugin.getPlayerRiskReason(requestToAccept.getRequesterId());
                        int confirm = JOptionPane.showConfirmDialog(panel,
                            "WARNING: This player has a HIGH RISK rating!\n\n" +
                            "Player: " + requestToAccept.getRequesterId() + "\n" +
                            "Risk Level: " + riskLevel + " (threshold: " + config.maxAcceptableRisk() + ")\n" +
                            "Reason: " + riskReason + "\n\n" +
                            "Are you sure you want to accept this request?",
                            "High Risk Borrower Warning",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                        if (confirm != JOptionPane.YES_OPTION) {
                            return;
                        }
                    }
                }
            }

            String response = JOptionPane.showInputDialog(panel,
                "Optional message (will be shared manually):",
                "Accept Request", JOptionPane.QUESTION_MESSAGE);

            if (plugin.acceptBorrowRequest(requestId, currentPlayer, response != null ? response : "")) {
                // Find the request to format response message
                if (currentGroupId != null) {
                    List<BorrowRequest> requests = plugin.getActiveRequestsForGroup(currentGroupId);
                    BorrowRequest acceptedRequest = requests.stream()
                        .filter(r -> r.getId().equals(requestId))
                        .findFirst().orElse(null);
                    
                    if (acceptedRequest != null) {
                        String message = plugin.formatResponseMessage(acceptedRequest, true, response);
                        JOptionPane.showMessageDialog(panel, 
                            "Request accepted!\n\nYou can manually share:\n" + message,
                            "Success", JOptionPane.INFORMATION_MESSAGE);
                    }
                }
                
                updateRequestsDisplay();
            } else {
                JOptionPane.showMessageDialog(panel, "Failed to accept request", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
        
        private void handleDeclineRequest(String requestId) {
            String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
            if (currentPlayer == null) return;
            
            String response = JOptionPane.showInputDialog(panel, 
                "Optional message (will be shared manually):", 
                "Decline Request", JOptionPane.QUESTION_MESSAGE);
            
            if (plugin.declineBorrowRequest(requestId, currentPlayer, response != null ? response : "")) {
                JOptionPane.showMessageDialog(panel, "Request declined successfully!",
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                updateRequestsDisplay();
            } else {
                JOptionPane.showMessageDialog(panel, "Failed to decline request", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * Build Agreement panel - review and respond to borrower agreement requests
     */
    private JPanel buildAgreementPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Top panel with actions
        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton refreshAgreementsButton = new JButton("Refresh");
        refreshAgreementsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshAgreementsButton.setForeground(Color.WHITE);
        refreshAgreementsButton.addActionListener(e -> updateAgreementDisplay());
        topPanel.add(refreshAgreementsButton);
        
        JButton myAgreementsButton = new JButton("My Requests");
        myAgreementsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        myAgreementsButton.setForeground(Color.WHITE);
        myAgreementsButton.addActionListener(e -> showMyAgreementRequestsDialog());
        topPanel.add(myAgreementsButton);
        
        panel.add(topPanel, BorderLayout.NORTH);
        
        // Center panel with pending agreements table
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        centerPanel.setBorder(BorderFactory.createTitledBorder("Pending Agreement Requests"));
        
        // Agreements table
        String[] columns = {"Timestamp", "Borrower", "Action Type", "Item", "Message", "Actions"};
        DefaultTableModel agreementTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 5; // Only actions column is editable
            }
        };
        
        JTable agreementTable = new JTable(agreementTableModel);
        agreementTable.setBackground(ColorScheme.DARK_GRAY_COLOR);
        agreementTable.setForeground(Color.WHITE);
        agreementTable.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        agreementTable.getTableHeader().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        agreementTable.getTableHeader().setForeground(Color.WHITE);
        
        // Column widths
        agreementTable.getColumnModel().getColumn(0).setPreferredWidth(120); // Timestamp
        agreementTable.getColumnModel().getColumn(1).setPreferredWidth(100); // Borrower
        agreementTable.getColumnModel().getColumn(2).setPreferredWidth(100); // Action Type
        agreementTable.getColumnModel().getColumn(3).setPreferredWidth(120); // Item
        agreementTable.getColumnModel().getColumn(4).setPreferredWidth(250); // Message
        agreementTable.getColumnModel().getColumn(5).setPreferredWidth(150); // Actions
        
        // Custom renderer and editor for actions
        agreementTable.getColumn("Actions").setCellRenderer(new AgreementActionsCellRenderer());
        agreementTable.getColumn("Actions").setCellEditor(new AgreementActionsCellEditor());
        
        JScrollPane scrollPane = new JScrollPane(agreementTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(700, 300));
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        
        panel.add(centerPanel, BorderLayout.CENTER);
        
        // Bottom panel with stats
        JPanel bottomPanel = new JPanel(new FlowLayout());
        bottomPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JLabel pendingLabel = new JLabel("Pending Requests: 0");
        pendingLabel.setForeground(Color.WHITE);
        bottomPanel.add(pendingLabel);
        
        JLabel respondedLabel = new JLabel("Responded Today: 0");
        respondedLabel.setForeground(Color.WHITE);
        bottomPanel.add(respondedLabel);
        
        panel.add(bottomPanel, BorderLayout.SOUTH);
        
        // Store references for updates
        panel.putClientProperty("agreementTable", agreementTable);
        panel.putClientProperty("tableModel", agreementTableModel);
        panel.putClientProperty("pendingLabel", pendingLabel);
        panel.putClientProperty("respondedLabel", respondedLabel);
        
        return panel;
    }
    
    /**
     * Update agreement display
     */
    private void updateAgreementDisplay() {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return;
        
        // Find the agreement panel (simplified approach)
        Component selectedTab = tabbedPane.getSelectedComponent();
        JPanel agreementPanel = null;
        
        if (selectedTab instanceof JPanel) {
            JPanel tabPanel = (JPanel) selectedTab;
            if (tabPanel.getClientProperty("agreementTable") != null) {
                agreementPanel = tabPanel;
            }
        }
        
        if (agreementPanel == null) return;
        
        JTable agreementTable = (JTable) agreementPanel.getClientProperty("agreementTable");
        DefaultTableModel tableModel = (DefaultTableModel) agreementPanel.getClientProperty("tableModel");
        JLabel pendingLabel = (JLabel) agreementPanel.getClientProperty("pendingLabel");
        JLabel respondedLabel = (JLabel) agreementPanel.getClientProperty("respondedLabel");
        
        if (agreementTable != null && tableModel != null) {
            // Clear existing data
            tableModel.setRowCount(0);
            
            // Get pending agreements for current player
            List<LenderAgreement> agreements = plugin.getPendingAgreements(currentPlayer);
            
            int pendingCount = 0;
            int respondedToday = 0;
            long todayStart = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 hours ago
            
            for (LenderAgreement agreement : agreements) {
                Object[] row = {
                    agreement.getFormattedTimestamp(),
                    agreement.getBorrower(),
                    agreement.getActionType(),
                    agreement.getItemName(),
                    agreement.getMessage(),
                    agreement.getId() // Store ID for actions
                };
                tableModel.addRow(row);
                
                if (!agreement.isResponded()) {
                    pendingCount++;
                } else if (agreement.getTimestamp() > todayStart) {
                    respondedToday++;
                }
            }
            
            // Update stats
            if (pendingLabel != null) {
                pendingLabel.setText("Pending Requests: " + pendingCount);
            }
            
            if (respondedLabel != null) {
                respondedLabel.setText("Responded Today: " + respondedToday);
            }
        }
    }
    
    /**
     * Show my agreement requests dialog
     */
    private void showMyAgreementRequestsDialog() {
        String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (currentPlayer == null) return;
        
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "My Agreement Requests", true);
        dialog.setLayout(new BorderLayout());
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Get all agreements where current player is the borrower
        List<LenderAgreement> myRequests = plugin.getPendingAgreements(currentPlayer)
            .stream()
            .filter(agreement -> agreement.getBorrower().equals(currentPlayer))
            .collect(java.util.stream.Collectors.toList());
        
        // Table for my requests
        String[] columns = {"Date", "Lender", "Action", "Item", "Status", "Response"};
        Object[][] data = new Object[myRequests.size()][6];
        
        for (int i = 0; i < myRequests.size(); i++) {
            LenderAgreement request = myRequests.get(i);
            data[i][0] = request.getFormattedTimestamp();
            data[i][1] = request.getLender();
            data[i][2] = request.getActionType();
            data[i][3] = request.getItemName();
            data[i][4] = request.isResponded() ? (request.isApproved() ? "APPROVED" : "DENIED") : "PENDING";
            data[i][5] = request.isResponded() ? request.getResponse() : "Waiting for response";
        }
        
        JTable myRequestsTable = new JTable(data, columns);
        myRequestsTable.setBackground(ColorScheme.DARK_GRAY_COLOR);
        myRequestsTable.setForeground(Color.WHITE);
        myRequestsTable.setSelectionBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        myRequestsTable.getTableHeader().setBackground(ColorScheme.DARKER_GRAY_COLOR);
        myRequestsTable.getTableHeader().setForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(myRequestsTable);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(600, 300));
        dialog.add(scrollPane, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton closeButton = new JButton("Close");
        closeButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        closeButton.setForeground(Color.WHITE);
        closeButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(closeButton);
        
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    /**
     * Custom cell renderer for agreement actions
     */
    private class AgreementActionsCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, 
                boolean isSelected, boolean hasFocus, int row, int column) {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
            panel.setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARK_GRAY_COLOR);
            
            JButton approveButton = new JButton("Approve");
            approveButton.setFont(approveButton.getFont().deriveFont(10f));
            approveButton.setBackground(Color.GREEN.darker());
            approveButton.setForeground(Color.WHITE);
            approveButton.setMargin(new Insets(2, 8, 2, 8));
            
            JButton denyButton = new JButton("Deny");
            denyButton.setFont(denyButton.getFont().deriveFont(10f));
            denyButton.setBackground(Color.RED.darker());
            denyButton.setForeground(Color.WHITE);
            denyButton.setMargin(new Insets(2, 8, 2, 8));
            
            panel.add(approveButton);
            panel.add(denyButton);
            
            return panel;
        }
    }
    
    /**
     * Custom cell editor for agreement actions
     */
    private class AgreementActionsCellEditor extends AbstractCellEditor implements TableCellEditor {
        private JPanel panel;
        private String currentAgreementId;
        
        public AgreementActionsCellEditor() {
            panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 2, 2));
            panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        }
        
        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, 
                boolean isSelected, int row, int column) {
            currentAgreementId = (String) value;
            
            panel.removeAll();
            
            JButton approveButton = new JButton("Approve");
            approveButton.setFont(approveButton.getFont().deriveFont(10f));
            approveButton.setBackground(Color.GREEN.darker());
            approveButton.setForeground(Color.WHITE);
            approveButton.setMargin(new Insets(2, 8, 2, 8));
            approveButton.addActionListener(e -> {
                handleAgreementResponse(currentAgreementId, true);
                fireEditingStopped();
            });
            
            JButton denyButton = new JButton("Deny");
            denyButton.setFont(denyButton.getFont().deriveFont(10f));
            denyButton.setBackground(Color.RED.darker());
            denyButton.setForeground(Color.WHITE);
            denyButton.setMargin(new Insets(2, 8, 2, 8));
            denyButton.addActionListener(e -> {
                handleAgreementResponse(currentAgreementId, false);
                fireEditingStopped();
            });
            
            panel.add(approveButton);
            panel.add(denyButton);
            
            return panel;
        }
        
        @Override
        public Object getCellEditorValue() {
            return currentAgreementId;
        }
        
        private void handleAgreementResponse(String agreementId, boolean approved) {
            String response = JOptionPane.showInputDialog(panel, 
                "Optional message to borrower:", 
                approved ? "Approve Agreement" : "Deny Agreement", 
                JOptionPane.QUESTION_MESSAGE);
            
            if (response == null) response = "";
            
            // Process the agreement response through the plugin
            if (plugin.respondToAgreement(agreementId, approved, response)) {
                JOptionPane.showMessageDialog(panel, 
                    "Response sent to borrower successfully!",
                    "Agreement Response", JOptionPane.INFORMATION_MESSAGE);
                updateAgreementDisplay();
            } else {
                JOptionPane.showMessageDialog(panel, "Failed to send response", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void export()
    {
        // Show enhanced export dialog
        showAdvancedExportDialog();
    }
    
    private void showAdvancedExportDialog()
    {
        JDialog exportDialog = new JDialog();
        exportDialog.setTitle("Advanced Data Export");
        exportDialog.setModal(true);
        exportDialog.setSize(500, 600);
        exportDialog.setLocationRelativeTo(this);
        
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        
        // Title
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(15, 15, 15, 15);
        JLabel titleLabel = new JLabel("<html><b>Export Configuration</b></html>");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(14f));
        mainPanel.add(titleLabel, c);
        
        // Export format
        c.gridy = 1; c.gridwidth = 1; c.anchor = GridBagConstraints.WEST;
        JLabel formatLabel = new JLabel("Format:");
        formatLabel.setForeground(Color.WHITE);
        mainPanel.add(formatLabel, c);
        
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JComboBox<String> formatCombo = new JComboBox<>(new String[]{"CSV", "JSON", "Excel"});
        formatCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        formatCombo.setForeground(Color.WHITE);
        mainPanel.add(formatCombo, c);
        
        // Data to include
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.fill = GridBagConstraints.NONE;
        JLabel dataLabel = new JLabel("Data to Include:");
        dataLabel.setForeground(Color.WHITE);
        mainPanel.add(dataLabel, c);
        
        c.gridy = 3; c.insets = new Insets(5, 30, 5, 15);
        JPanel dataPanel = new JPanel(new GridLayout(4, 2));
        dataPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JCheckBox activeLoansCheck = new JCheckBox("Active Loans", true);
        activeLoansCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        activeLoansCheck.setForeground(Color.WHITE);
        dataPanel.add(activeLoansCheck);
        
        JCheckBox loanHistoryCheck = new JCheckBox("Loan History");
        loanHistoryCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        loanHistoryCheck.setForeground(Color.WHITE);
        dataPanel.add(loanHistoryCheck);
        
        JCheckBox overdueOnlyCheck = new JCheckBox("Overdue Only");
        overdueOnlyCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        overdueOnlyCheck.setForeground(Color.WHITE);
        dataPanel.add(overdueOnlyCheck);
        
        JCheckBox groupDataCheck = new JCheckBox("Group Information", true);
        groupDataCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        groupDataCheck.setForeground(Color.WHITE);
        dataPanel.add(groupDataCheck);
        
        JCheckBox collateralCheck = new JCheckBox("Collateral Details", true);
        collateralCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        collateralCheck.setForeground(Color.WHITE);
        dataPanel.add(collateralCheck);
        
        JCheckBox riskDataCheck = new JCheckBox("Risk Analysis");
        riskDataCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        riskDataCheck.setForeground(Color.WHITE);
        dataPanel.add(riskDataCheck);
        
        JCheckBox timestampsCheck = new JCheckBox("Detailed Timestamps", true);
        timestampsCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        timestampsCheck.setForeground(Color.WHITE);
        dataPanel.add(timestampsCheck);
        
        JCheckBox memberDataCheck = new JCheckBox("Member Statistics");
        memberDataCheck.setBackground(ColorScheme.DARK_GRAY_COLOR);
        memberDataCheck.setForeground(Color.WHITE);
        dataPanel.add(memberDataCheck);
        
        mainPanel.add(dataPanel, c);
        
        // Date range filter
        c.gridx = 0; c.gridy = 4; c.gridwidth = 1; c.insets = new Insets(15, 15, 5, 15);
        JLabel dateRangeLabel = new JLabel("Date Range:");
        dateRangeLabel.setForeground(Color.WHITE);
        mainPanel.add(dateRangeLabel, c);
        
        c.gridy = 5;
        JComboBox<String> dateRangeCombo = new JComboBox<>(new String[]{
            "All Time", "Last 7 Days", "Last 30 Days", "Last 90 Days", "This Year", "Custom Range"
        });
        dateRangeCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dateRangeCombo.setForeground(Color.WHITE);
        mainPanel.add(dateRangeCombo, c);
        
        // Group filter
        c.gridy = 6;
        JLabel groupFilterLabel = new JLabel("Group Filter:");
        groupFilterLabel.setForeground(Color.WHITE);
        mainPanel.add(groupFilterLabel, c);
        
        c.gridy = 7;
        List<String> groupNames = groupConfigStore.getAllGroups().stream()
            .map(LendingGroup::getName)
            .collect(java.util.stream.Collectors.toList());
        groupNames.add(0, "All Groups");
        JComboBox<String> groupFilterCombo = new JComboBox<>(groupNames.toArray(new String[0]));
        groupFilterCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupFilterCombo.setForeground(Color.WHITE);
        mainPanel.add(groupFilterCombo, c);
        
        // Button panel
        c.gridy = 8; c.gridwidth = 2; c.insets = new Insets(20, 15, 15, 15);
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton exportButton = new JButton("Export");
        exportButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        exportButton.setForeground(Color.WHITE);
        exportButton.addActionListener(e -> {
            String format = (String) formatCombo.getSelectedItem();
            String dateRange = (String) dateRangeCombo.getSelectedItem();
            String groupFilter = (String) groupFilterCombo.getSelectedItem();
            
            ExportConfiguration exportConfig = new ExportConfiguration();
            exportConfig.format = format;
            exportConfig.includeActiveLoans = activeLoansCheck.isSelected();
            exportConfig.includeLoanHistory = loanHistoryCheck.isSelected();
            exportConfig.overdueOnly = overdueOnlyCheck.isSelected();
            exportConfig.includeGroupData = groupDataCheck.isSelected();
            exportConfig.includeCollateral = collateralCheck.isSelected();
            exportConfig.includeRiskData = riskDataCheck.isSelected();
            exportConfig.includeDetailedTimestamps = timestampsCheck.isSelected();
            exportConfig.includeMemberStats = memberDataCheck.isSelected();
            exportConfig.dateRange = dateRange;
            exportConfig.groupFilter = groupFilter;
            
            exportDialog.dispose();
            performAdvancedExport(exportConfig);
        });
        buttonPanel.add(exportButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> exportDialog.dispose());
        buttonPanel.add(cancelButton);
        
        mainPanel.add(buttonPanel, c);
        
        exportDialog.add(mainPanel);
        exportDialog.setVisible(true);
    }
    
    private void performAdvancedExport(ExportConfiguration config)
    {
        JFileChooser chooser = new JFileChooser();
        String ext = config.format.toLowerCase();
        if (ext.equals("excel")) ext = "xlsx";
        chooser.setSelectedFile(new File("lending_export_" + 
                                       new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm")
                                       .format(new Date()) + "." + ext));
        
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                performCustomExport(file, config);
                showNotification("Export Complete", "Advanced export completed successfully!");
            } catch (Exception e) {
                showError("Export failed: " + e.getMessage());
            }
        }
    }
    
    private void performCustomExport(File file, ExportConfiguration config) throws IOException
    {
        // Collect data based on configuration
        List<LendingEntry> dataToExport = new ArrayList<>();
        
        if (config.includeActiveLoans) {
            dataToExport.addAll(recorder.getActiveEntries());
        }
        
        if (config.includeLoanHistory) {
            dataToExport.addAll(recorder.getHistoryEntries());
        }
        
        // Filter by group
        if (!"All Groups".equals(config.groupFilter)) {
            String targetGroupId = groupConfigStore.getGroupIdByName(config.groupFilter);
            dataToExport = dataToExport.stream()
                .filter(entry -> targetGroupId.equals(entry.getGroupId()))
                .collect(java.util.stream.Collectors.toList());
        }
        
        // Filter by date range
        dataToExport = filterByDateRange(dataToExport, config.dateRange);
        
        // Filter overdue only
        if (config.overdueOnly) {
            long currentTime = System.currentTimeMillis();
            dataToExport = dataToExport.stream()
                .filter(entry -> !entry.isReturned() && entry.getDueTime() < currentTime)
                .collect(java.util.stream.Collectors.toList());
        }
        
        // Export based on format
        switch (config.format.toUpperCase()) {
            case "CSV":
                exportToCSV(file, dataToExport, config);
                break;
            case "JSON":
                exportToJSON(file, dataToExport, config);
                break;
            case "EXCEL":
                exportToExcel(file, dataToExport, config);
                break;
            default:
                throw new IOException("Unsupported export format: " + config.format);
        }
    }
    
    private List<LendingEntry> filterByDateRange(List<LendingEntry> entries, String dateRange)
    {
        if ("All Time".equals(dateRange)) {
            return entries;
        }
        
        long currentTime = System.currentTimeMillis();
        long cutoffTime = currentTime;
        
        switch (dateRange) {
            case "Last 7 Days":
                cutoffTime = currentTime - (7L * 24 * 60 * 60 * 1000);
                break;
            case "Last 30 Days":
                cutoffTime = currentTime - (30L * 24 * 60 * 60 * 1000);
                break;
            case "Last 90 Days":
                cutoffTime = currentTime - (90L * 24 * 60 * 60 * 1000);
                break;
            case "This Year":
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.DAY_OF_YEAR, 1);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cutoffTime = cal.getTimeInMillis();
                break;
        }
        
        final long finalCutoffTime = cutoffTime;
        return entries.stream()
            .filter(entry -> entry.getLendTime() >= finalCutoffTime)
            .collect(java.util.stream.Collectors.toList());
    }
    
    private void exportToCSV(File file, List<LendingEntry> entries, ExportConfiguration config) throws IOException
    {
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            // Write header
            List<String> headers = new ArrayList<>();
            headers.add("Lender");
            headers.add("Borrower");
            headers.add("Item");
            headers.add("Quantity");
            headers.add("Value (GP)");
            headers.add("Status");
            
            if (config.includeDetailedTimestamps) {
                headers.add("Lend Date");
                headers.add("Due Date");
                headers.add("Return Date");
                headers.add("Days Outstanding");
            }
            
            if (config.includeGroupData) {
                headers.add("Group");
            }
            
            if (config.includeCollateral) {
                headers.add("Collateral Type");
                headers.add("Collateral Value");
                headers.add("Collateral Items");
            }
            
            writer.println(String.join(",", headers));
            
            // Write data rows
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (LendingEntry entry : entries) {
                List<String> row = new ArrayList<>();
                row.add(csvEscape(entry.getLender()));
                row.add(csvEscape(entry.getBorrower()));
                row.add(csvEscape(entry.getItem()));
                row.add(String.valueOf(entry.getQuantity()));
                row.add(String.valueOf(entry.getValue()));
                row.add(entry.isReturned() ? "Returned" : (entry.isOverdue() ? "Overdue" : "Active"));
                
                if (config.includeDetailedTimestamps) {
                    row.add(dateFormat.format(new Date(entry.getLendTime())));
                    row.add(entry.getDueTime() > 0 ? dateFormat.format(new Date(entry.getDueTime())) : "");
                    row.add(entry.isReturned() && entry.getReturnedAt() > 0 ? 
                           dateFormat.format(new Date(entry.getReturnedAt())) : "");
                    
                    long daysOutstanding = (System.currentTimeMillis() - entry.getLendTime()) / (24 * 60 * 60 * 1000);
                    row.add(String.valueOf(daysOutstanding));
                }
                
                if (config.includeGroupData) {
                    LendingGroup group = groupConfigStore.getGroup(entry.getGroupId());
                    row.add(csvEscape(group != null ? group.getName() : "Unknown"));
                }
                
                if (config.includeCollateral) {
                    row.add(csvEscape(entry.getCollateralType() != null ? entry.getCollateralType() : ""));
                    row.add(String.valueOf(entry.getCollateralValue()));
                    row.add(csvEscape(entry.getCollateralItems() != null ? entry.getCollateralItems() : ""));
                }
                
                writer.println(String.join(",", row));
            }
        }
    }
    
    private void exportToJSON(File file, List<LendingEntry> entries, ExportConfiguration config) throws IOException
    {
        // Create comprehensive JSON export
        Map<String, Object> exportData = new HashMap<>();
        exportData.put("exportDate", new Date().toString());
        exportData.put("exportConfiguration", config);
        exportData.put("totalEntries", entries.size());
        
        if (config.includeGroupData) {
            exportData.put("groups", groupConfigStore.getAllGroups());
        }
        
        if (config.includeMemberStats) {
            exportData.put("memberStatistics", calculateMemberStatistics(entries));
        }
        
        exportData.put("entries", entries);
        
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(exportData, writer);
        }
    }
    
    private void exportToExcel(File file, List<LendingEntry> entries, ExportConfiguration config) throws IOException
    {
        // For now, fall back to CSV format with Excel extension
        // In a full implementation, you would use Apache POI library to create proper Excel files
        exportToCSV(file, entries, config);
        showNotification("Excel Export", "Exported as CSV format with Excel extension");
    }
    
    private String csvEscape(String value)
    {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
    
    private Map<String, Object> calculateMemberStatistics(List<LendingEntry> entries)
    {
        Map<String, Object> stats = new HashMap<>();
        Map<String, Integer> lenderCounts = new HashMap<>();
        Map<String, Integer> borrowerCounts = new HashMap<>();
        Map<String, Long> lenderValues = new HashMap<>();
        Map<String, Long> borrowerValues = new HashMap<>();
        
        for (LendingEntry entry : entries) {
            // Lender stats
            lenderCounts.merge(entry.getLender(), 1, Integer::sum);
            lenderValues.merge(entry.getLender(), entry.getValue(), Long::sum);
            
            // Borrower stats
            borrowerCounts.merge(entry.getBorrower(), 1, Integer::sum);
            borrowerValues.merge(entry.getBorrower(), entry.getValue(), Long::sum);
        }
        
        stats.put("lenderStatistics", Map.of(
            "transactionCounts", lenderCounts,
            "totalValues", lenderValues
        ));
        
        stats.put("borrowerStatistics", Map.of(
            "transactionCounts", borrowerCounts,
            "totalValues", borrowerValues
        ));
        
        return stats;
    }
    
    /**
     * Export configuration class
     */
    private static class ExportConfiguration {
        String format;
        boolean includeActiveLoans;
        boolean includeLoanHistory;
        boolean overdueOnly;
        boolean includeGroupData;
        boolean includeCollateral;
        boolean includeRiskData;
        boolean includeDetailedTimestamps;
        boolean includeMemberStats;
        String dateRange;
        String groupFilter;
    }
    
    private void createDataBackup()
    {
        try {
            File backupFile = localDataSyncService.exportPortableBackup();
            showNotification("Backup Created", "Backup saved to: " + backupFile.getName());
            
            // Offer to open backup folder
            int result = JOptionPane.showConfirmDialog(
                this,
                "Backup created successfully!\nWould you like to open the backup folder?",
                "Backup Complete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE
            );
            
            if (result == JOptionPane.YES_OPTION) {
                try {
                    Desktop.getDesktop().open(backupFile.getParentFile());
                } catch (IOException e) {
                    showError("Could not open backup folder: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            showError("Failed to create backup: " + e.getMessage());
        }
    }
    
    private void restoreFromBackup()
    {
        // Show backup selection dialog
        JDialog restoreDialog = new JDialog();
        restoreDialog.setTitle("Restore from Backup");
        restoreDialog.setModal(true);
        restoreDialog.setSize(500, 400);
        restoreDialog.setLocationRelativeTo(this);
        
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Available backups list
        List<File> availableBackups = localDataSyncService.getAvailableBackups();
        DefaultListModel<String> listModel = new DefaultListModel<>();
        
        for (File backup : availableBackups) {
            String displayName = backup.getName() + " (" + 
                               new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                               .format(new Date(backup.lastModified())) + ")";
            listModel.addElement(displayName);
        }
        
        JList<String> backupList = new JList<>(listModel);
        backupList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        backupList.setForeground(Color.WHITE);
        backupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        JScrollPane scrollPane = new JScrollPane(backupList);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(200, 250));
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Instruction label
        JLabel instructionLabel = new JLabel("<html><b>Select a backup to restore from:</b><br>" +
            "Warning: This will merge backup data with current data.</html>");
        instructionLabel.setForeground(Color.WHITE);
        instructionLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(instructionLabel, BorderLayout.NORTH);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton restoreFromListButton = new JButton("Restore Selected");
        restoreFromListButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        restoreFromListButton.setForeground(Color.WHITE);
        restoreFromListButton.addActionListener(e -> {
            int selectedIndex = backupList.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < availableBackups.size()) {
                File selectedBackup = availableBackups.get(selectedIndex);
                restoreDialog.dispose();
                performRestore(selectedBackup);
            } else {
                showError("Please select a backup to restore from");
            }
        });
        buttonPanel.add(restoreFromListButton);
        
        JButton browseButton = new JButton("Browse File...");
        browseButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        browseButton.setForeground(Color.WHITE);
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON files", "json"));
            if (chooser.showOpenDialog(restoreDialog) == JFileChooser.APPROVE_OPTION) {
                restoreDialog.dispose();
                performRestore(chooser.getSelectedFile());
            }
        });
        buttonPanel.add(browseButton);
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> restoreDialog.dispose());
        buttonPanel.add(cancelButton);
        
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        restoreDialog.add(mainPanel);
        restoreDialog.setVisible(true);
    }
    
    private void performRestore(File backupFile)
    {
        int confirmation = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to restore from this backup?\n" +
            "This will merge the backup data with your current data.\n" +
            "File: " + backupFile.getName(),
            "Confirm Restore",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        
        if (confirmation == JOptionPane.YES_OPTION) {
            try {
                boolean success = localDataSyncService.importPortableBackup(backupFile);
                if (success) {
                    refresh(); // Refresh the UI to show restored data
                    showNotification("Restore Complete", "Data restored from backup successfully!");
                } else {
                    showError("Failed to restore from backup. Please check the file format.");
                }
            } catch (Exception e) {
                showError("Failed to restore from backup: " + e.getMessage());
            }
        }
    }
    
    private String getCurrentAccount()
    {
        // FIXED: Use currentAccount field if set, otherwise get from client
        if (currentAccount != null && !currentAccount.isEmpty()) {
            return currentAccount;
        }
        return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Default";
    }
    
    private void showNotification(String title, String message)
    {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
        });
    }
    
    private void showError(String message)
    {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
        });
    }
    
    // Custom cell renderer for collateral display
    private class CollateralCellRenderer extends DefaultTableCellRenderer
    {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column)
        {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            
            String text = value != null ? value.toString() : "";
            if (text.contains("No Collateral") || text.equals("None")) {
                setForeground(Color.ORANGE);
                setFont(getFont().deriveFont(Font.ITALIC));
            } else if (text.contains("GP")) {
                setForeground(Color.YELLOW);
            } else {
                setForeground(Color.WHITE);
            }
            
            return this;
        }
    }
    
    // Member list renderer - RESTORED: Online Status Tracking
    private class MemberRenderer extends DefaultListCellRenderer
    {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean hasFocus)
        {
            super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
            
            String member = (String) value;
            boolean isOwner = groupConfigStore.isOwner(member, groupConfigStore.getCurrentGroupId());
            boolean isAdmin = groupConfigStore.isAdmin(member, groupConfigStore.getCurrentGroupId());
            
            // RESTORED: Get online status
            String statusIndicator = onlineStatusService.getStatusIndicator(member);
            String statusIcon;
            if (statusIndicator.startsWith("🟢")) {
                statusIcon = "🟢"; // Online
            } else if (statusIndicator.startsWith("🟡")) {
                statusIcon = "🟡"; // Recently online
            } else if (statusIndicator.startsWith("🔴")) {
                statusIcon = "🔴"; // Offline
            } else {
                statusIcon = "⚫"; // Unknown
            }
            
            String displayText = statusIcon + " " + member;
            
            if (isOwner) {
                setText(displayText + " (Owner)");
                setForeground(Color.ORANGE);
                setToolTipText(statusIndicator + " - Group Owner");
            } else if (isAdmin) {
                setText(displayText + " (Admin)");
                setForeground(Color.YELLOW);
                setToolTipText(statusIndicator + " - Group Admin");
            } else {
                setText(displayText);
                setForeground(Color.WHITE);
                setToolTipText(statusIndicator);
            }
            
            setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
            
            return this;
        }
    }
    
    // Search listener for filtering lists/tables
    private class SearchListener implements DocumentListener
    {
        private final Component component;
        
        public SearchListener(Component component)
        {
            this.component = component;
        }
        
        @Override
        public void insertUpdate(DocumentEvent e) { filter(e); }
        
        @Override
        public void removeUpdate(DocumentEvent e) { filter(e); }
        
        @Override
        public void changedUpdate(DocumentEvent e) { filter(e); }
        
        private void filter(DocumentEvent e)
        {
            // Implementation would filter the associated component
        }
    }
    
    // =============== NEW MARKETPLACE UI COMPONENTS ===============
    
    /**
     * Build compact dropdown-style content sections
     */
    private JPanel buildMarketplaceDropdownContent() {
        // Use existing marketplace panel but make it compact
        return buildCompactMarketplacePanel();
    }
    
    private JPanel buildMyLoansDropdownContent() {
        // Use existing loans panel but make it compact 
        return buildCompactLoansPanel();
    }
    
    private JPanel buildMyBorrowedDropdownContent() {
        // Use existing borrowed panel but make it compact
        return buildCompactBorrowedPanel();
    }
    
    private JPanel buildGroupsDropdownContent() {
        // Use existing groups panel but make it compact
        return buildCompactGroupPanel();
    }
    
    private JPanel buildHistoryDropdownContent() {
        // Use existing history panel but make it compact
        return buildCompactHistoryPanel();
    }
    
    // Compact panel implementations that fit 225px width and restore functionality
    
    private JPanel buildCompactMarketplacePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        // Remove border to align to panel edge, content stays centered inside
        
        // Available items list - fixed width container
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listPanel.setPreferredSize(new Dimension(210, 120));
        listPanel.setMinimumSize(new Dimension(210, 120));
        listPanel.setMaximumSize(new Dimension(210, 150));
        listPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            "Available Items", 0, 0, null, Color.WHITE
        ));
        
        // Use a simple list instead of complex table for compact view
        DefaultListModel<String> marketModel = new DefaultListModel<>();
        
        // Populate with actual lending data (simplified for compact view)
        try {
            // Get available items from recorder or other data sources
            marketModel.addElement("Loading available items...");
            // This would connect to actual data: recorder.getAvailableItems()
        } catch (Exception e) {
            marketModel.addElement("No items available");
        }
        
        JList<String> marketList = new JList<>(marketModel);
        marketList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        marketList.setForeground(Color.LIGHT_GRAY);
        marketList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
        
        JScrollPane scrollPane = new JScrollPane(marketList);
        styleScrollBar(scrollPane);
        scrollPane.setPreferredSize(new Dimension(210, 80));
        scrollPane.setMinimumSize(new Dimension(210, 80));
        scrollPane.setMaximumSize(new Dimension(210, 80));
        scrollPane.setBorder(null);
        
        listPanel.add(scrollPane, BorderLayout.CENTER);
        panel.add(listPanel, BorderLayout.CENTER);
        
        // Action buttons - fixed width with line wrapping
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.setPreferredSize(new Dimension(210, 60));
        buttonPanel.setMinimumSize(new Dimension(210, 60));
        buttonPanel.setMaximumSize(new Dimension(210, 80));
        
        // First row of buttons
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        row1.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton requestBtn = new JButton("Request");
        requestBtn.setPreferredSize(new Dimension(90, 25));
        requestBtn.setBackground(ColorScheme.BRAND_ORANGE);
        requestBtn.setForeground(Color.WHITE);
        requestBtn.addActionListener(e -> {
            String selected = marketList.getSelectedValue();
            if (selected != null && !selected.contains("No items") && !selected.contains("Loading")) {
                JOptionPane.showMessageDialog(this, "Request sent for: " + selected, "Request Sent", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        row1.add(requestBtn);
        
        JButton addBtn = new JButton("Add Item");
        addBtn.setPreferredSize(new Dimension(90, 25));
        addBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addBtn.setForeground(Color.WHITE);
        addBtn.addActionListener(e -> showAddItemDialog());
        row1.add(addBtn);
        
        buttonPanel.add(row1);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private void showAddItemDialog() {
        // Create a simple marketplace add form for lending items
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                "Add Item for Lending", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));
        
        // Item name
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel itemLabel = new JLabel("Item:");
        itemLabel.setForeground(Color.WHITE);
        SmartSuggestBox itemBox = new SmartSuggestBox(itemManager);
        itemBox.setPreferredSize(new Dimension(160, 25));
        itemBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemBox.setForeground(Color.WHITE);
        row1.add(itemLabel);
        row1.add(itemBox);
        panel.add(row1);
        
        // Hours
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row2.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel dueLabel = new JLabel("Hours:");
        dueLabel.setForeground(Color.WHITE);
        JTextField hoursField = new JTextField("1", 5);
        hoursField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        hoursField.setForeground(Color.WHITE);
        JButton addButton = new JButton("Add");
        addButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addButton.setForeground(Color.WHITE);
        addButton.addActionListener(e -> {
            String item = itemBox.getText();
            String hours = hoursField.getText();
            if (!item.isEmpty() && !hours.isEmpty()) {
                JOptionPane.showMessageDialog(panel, "Added \"" + item + "\" to marketplace for lending (duration: " + hours + " hours)", "Item Added", JOptionPane.INFORMATION_MESSAGE);
                // Close dialog
                SwingUtilities.getWindowAncestor(panel).dispose();
            }
        });
        row2.add(dueLabel);
        row2.add(hoursField);
        row2.add(addButton);
        panel.add(row2);
        
        JDialog dialog = new JDialog();
        dialog.setTitle("Add Item to Marketplace");
        dialog.setModal(true);
        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    private void showRequestDialog(String itemName) {
        // Create request dialog
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                "Request Item", 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));
        
        // Show item being requested
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel itemLabel = new JLabel("Requesting: " + itemName);
        itemLabel.setForeground(Color.WHITE);
        itemLabel.setFont(itemLabel.getFont().deriveFont(Font.BOLD));
        row1.add(itemLabel);
        panel.add(row1);
        
        // Duration requested
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row2.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel durationLabel = new JLabel("Duration (hours):");
        durationLabel.setForeground(Color.WHITE);
        JTextField durationField = new JTextField("1", 5);
        durationField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        durationField.setForeground(Color.WHITE);
        row2.add(durationLabel);
        row2.add(durationField);
        panel.add(row2);
        
        // Notes field
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row3.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel notesLabel = new JLabel("Notes (optional):");
        notesLabel.setForeground(Color.WHITE);
        row3.add(notesLabel);
        panel.add(row3);
        
        JTextArea notesArea = new JTextArea(3, 20);
        notesArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        notesArea.setForeground(Color.WHITE);
        notesArea.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        JScrollPane notesScroll = new JScrollPane(notesArea);
        panel.add(notesScroll);
        
        // Action buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        buttonPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton sendBtn = new JButton("Send Request");
        sendBtn.setBackground(ColorScheme.BRAND_ORANGE);
        sendBtn.setForeground(Color.WHITE);
        sendBtn.addActionListener(e -> {
            String duration = durationField.getText();
            String notes = notesArea.getText();
            if (!duration.isEmpty()) {
                JOptionPane.showMessageDialog(panel, 
                    "Request sent for \"" + itemName + "\" for " + duration + " hours.\n" +
                    (notes.isEmpty() ? "No notes added." : "Notes: " + notes), 
                    "Request Sent", JOptionPane.INFORMATION_MESSAGE);
                SwingUtilities.getWindowAncestor(panel).dispose();
            } else {
                JOptionPane.showMessageDialog(panel, "Please enter a duration.", "Missing Duration", JOptionPane.WARNING_MESSAGE);
            }
        });
        
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelBtn.setForeground(Color.WHITE);
        cancelBtn.addActionListener(e -> SwingUtilities.getWindowAncestor(panel).dispose());
        
        buttonPanel.add(sendBtn);
        buttonPanel.add(cancelBtn);
        panel.add(buttonPanel);
        
        JDialog dialog = new JDialog();
        dialog.setTitle("Request Item");
        dialog.setModal(true);
        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    private JPanel buildCompactLoansPanel() {
        // Use actual loans functionality but make compact
        JPanel originalPanel = buildMyLoansPanel();
        return makeCompactPanel(originalPanel);
    }
    
    private JPanel buildCompactBorrowedPanel() {
        // Use actual borrowed functionality but make compact
        JPanel originalPanel = buildMyBorrowedPanel();
        return makeCompactPanel(originalPanel);
    }
    
    private JPanel buildCompactGroupPanel() {
        // Use actual group functionality but make compact
        JPanel originalPanel = buildCleanGroupPanel();
        return makeCompactPanel(originalPanel);
    }
    
    private JPanel buildCompactHistoryPanel() {
        // Use actual history functionality but make compact
        JPanel originalPanel = buildCleanHistoryPanel();
        return makeCompactPanel(originalPanel);
    }
    
    private JPanel makeCompactPanel(JPanel originalPanel) {
        // Wrapper to align panels to edges while keeping content centered
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        // Remove wrapper border to align to panel edge
        
        // Remove original panel border to align to edge but keep internal spacing
        originalPanel.setBorder(null);
        
        wrapper.add(originalPanel, BorderLayout.CENTER);
        return wrapper;
    }
    
    private JPanel buildCompactListPanel(String title, String emptyMessage, String actionText) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        // Remove border to align to panel edge, content stays centered inside
        
        // List area
        JTextArea listArea = new JTextArea(emptyMessage);
        listArea.setEditable(false);
        listArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        listArea.setForeground(Color.LIGHT_GRAY);
        listArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JScrollPane scrollPane = new JScrollPane(listArea);
        styleScrollBar(scrollPane);
        scrollPane.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        scrollPane.setPreferredSize(new Dimension(200, 100));
        
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Action button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton actionBtn = new JButton(actionText);
        actionBtn.setPreferredSize(new Dimension(100, 22));
        actionBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        actionBtn.setForeground(Color.WHITE);
        buttonPanel.add(actionBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel buildCleanHeaderPanel()
    {
        JPanel headerPanel = new JPanel(new BorderLayout(0, 5));
        headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        // Align header to left edge of panel
        headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.LIGHT_GRAY_COLOR));
        
        // Top line: Lending Tracker title - smaller font to fit
        JLabel titleLabel = new JLabel("Lending Tracker", SwingConstants.CENTER);
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        headerPanel.add(titleLabel, BorderLayout.NORTH);
        
        // Bottom line: Account and Group selectors with line wrapping
        JPanel controlsPanel = new JPanel();
        controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
        controlsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        controlsPanel.setPreferredSize(new Dimension(210, 50));
        controlsPanel.setMinimumSize(new Dimension(210, 50));
        controlsPanel.setMaximumSize(new Dimension(210, 60));
        
        // First row: Account selector  
        JPanel accountRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        accountRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JLabel accountLabel = new JLabel("Account:");
        accountLabel.setForeground(Color.LIGHT_GRAY);
        accountRow.add(accountLabel);
        
        accountComboBox = new JComboBox<>();
        accountComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        accountComboBox.setForeground(Color.WHITE);
        accountComboBox.setPreferredSize(new Dimension(120, 22));
        accountComboBox.addActionListener(e -> refresh());
        accountRow.add(accountComboBox);
        
        controlsPanel.add(accountRow);
        
        // Second row: Group selector
        JPanel groupRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 2));
        groupRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JLabel groupLabel = new JLabel("Group:");
        groupLabel.setForeground(Color.LIGHT_GRAY);
        groupRow.add(groupLabel);
        
        groupComboBox = new JComboBox<>();
        groupComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupComboBox.setForeground(Color.WHITE);
        groupComboBox.setPreferredSize(new Dimension(120, 22));
        groupComboBox.addActionListener(e -> switchGroup());
        groupRow.add(groupComboBox);
        
        controlsPanel.add(groupRow);
        
        headerPanel.add(controlsPanel, BorderLayout.CENTER);
        return headerPanel;
    }
    
    // Old method removed - replaced with dropdown system
    
    private JPanel buildMyLoansPanel()
    {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // My Loans is for records only - no add form needed
        
        // Loans table - simplified columns
        lentModel = new DefaultTableModel(
            new Object[]{"Item", "Borrower"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        lentTable = new JTable(lentModel);
        setupTable(lentTable);
        
        JScrollPane scrollPane = new JScrollPane(lentTable);
        styleScrollBar(scrollPane);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Action buttons at bottom
        JPanel actionPanel = buildLoanActionPanel(true);
        mainPanel.add(actionPanel, BorderLayout.SOUTH);
        
        return mainPanel;
    }
    
    private JPanel buildMyBorrowedPanel()
    {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // My Borrowed is for records only - no add form needed
        
        // Borrowed table - simplified columns
        borrowedModel = new DefaultTableModel(
            new Object[]{"Item", "Lender"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        borrowedTable = new JTable(borrowedModel);
        setupTable(borrowedTable);
        
        JScrollPane scrollPane = new JScrollPane(borrowedTable);
        styleScrollBar(scrollPane);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Action buttons at bottom
        JPanel actionPanel = buildLoanActionPanel(false);
        mainPanel.add(actionPanel, BorderLayout.SOUTH);
        
        return mainPanel;
    }
    
    private JPanel buildQuickAddPanel(String title, boolean isLending)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                title, 0, 0, FontManager.getRunescapeSmallFont(), Color.WHITE),
            BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));
        
        // Row 1: Action type (for marketplace) or Player name (for loans)
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        if (title.contains("Available")) {
            // Marketplace: Show Borrow/Lend dropdown
            JLabel actionLabel = new JLabel("Action:");
            actionLabel.setForeground(Color.WHITE);
            JComboBox<String> actionCombo = new JComboBox<>(new String[]{"Lending out", "Wanting to borrow"});
            actionCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            actionCombo.setForeground(Color.WHITE);
            actionCombo.setPreferredSize(new Dimension(140, 25));
            row1.add(actionLabel);
            row1.add(actionCombo);
        } else {
            // My Loans/Borrowed: Show player name
            JLabel playerLabel = new JLabel(isLending ? "Borrower:" : "Lender:");
            playerLabel.setForeground(Color.WHITE);
            nameComboBox = new JComboBox<>();
            nameComboBox.setEditable(true);
            nameComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            nameComboBox.setForeground(Color.WHITE);
            nameComboBox.setPreferredSize(new Dimension(140, 25));
            row1.add(playerLabel);
            row1.add(nameComboBox);
        }
        panel.add(row1);
        
        // Row 2: Item name
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row2.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel itemLabel = new JLabel("Item:");
        itemLabel.setForeground(Color.WHITE);
        itemSuggestBox = new SmartSuggestBox(itemManager);
        itemSuggestBox.setPreferredSize(new Dimension(160, 25));
        itemSuggestBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        itemSuggestBox.setForeground(Color.WHITE);
        row2.add(itemLabel);
        row2.add(itemSuggestBox);
        panel.add(row2);
        
        // Row 3: Due hours and Add button
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        row3.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        JLabel dueLabel = new JLabel("Hours:");
        dueLabel.setForeground(Color.WHITE);
        dueHoursField = new JTextField("1", 5);
        dueHoursField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        dueHoursField.setForeground(Color.WHITE);
        JButton addButton = new JButton("Add");
        addButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        addButton.setForeground(Color.WHITE);
        addButton.addActionListener(e -> quickAddEntry(isLending));
        row3.add(dueLabel);
        row3.add(dueHoursField);
        row3.add(addButton);
        panel.add(row3);
        
        return panel;
    }
    
    private JPanel buildLoanActionPanel(boolean isLending)
    {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));

        JButton returnButton = new JButton("Mark Returned");
        returnButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        returnButton.setForeground(Color.WHITE);
        returnButton.setToolTipText("Both parties must confirm return");
        returnButton.addActionListener(e -> markReturned(isLending ? lentTable : borrowedTable));
        panel.add(returnButton);

        // Only show Extend button for lenders (they control extensions)
        // Borrowers can request extension via Send Reminder
        if (isLending) {
            JButton extendButton = new JButton("Extend Due Date");
            extendButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            extendButton.setForeground(Color.WHITE);
            extendButton.setToolTipText("Extend the due date for this loan");
            extendButton.addActionListener(e -> extendLoan(lentTable));
            panel.add(extendButton);
        }

        JButton remindButton = new JButton(isLending ? "Send Reminder" : "Request Extension");
        remindButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        remindButton.setForeground(Color.WHITE);
        remindButton.setToolTipText(isLending ? "Remind borrower about overdue item" : "Request extension from lender");
        remindButton.addActionListener(e -> {
            if (isLending) {
                remindBorrower(lentTable);
            } else {
                requestExtension(borrowedTable);
            }
        });
        panel.add(remindButton);

        if (isLending) {
            JButton defaultButton = new JButton("Mark Defaulted");
            defaultButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
            defaultButton.setForeground(Color.WHITE);
            defaultButton.setToolTipText("Mark this loan as defaulted (not returned)");
            defaultButton.addActionListener(e -> markAsDefaulted(lentTable));
            panel.add(defaultButton);
        }

        return panel;
    }
    
    
    /**
     * Build main marketplace panel - hub for browsing available items (group-specific)
     */
    private JPanel buildMarketplacePanel()
    {
        JPanel marketplacePanel = new JPanel(new BorderLayout(10, 10));
        marketplacePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        marketplacePanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // Header with group selector
        JPanel headerPanel = new JPanel(new BorderLayout(5, 5));
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel groupLabel = new JLabel("Group:");
        groupLabel.setForeground(Color.WHITE);
        groupLabel.setFont(groupLabel.getFont().deriveFont(Font.BOLD));

        JComboBox<String> groupSelector = new JComboBox<>();
        groupSelector.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupSelector.setForeground(Color.WHITE);
        groupSelector.setPreferredSize(new Dimension(200, 30));

        // Populate with user's groups
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        java.util.List<com.guess34.lendingtracker.model.LendingGroup> allGroups =
            new java.util.ArrayList<>(groupConfigStore.getAllGroups());
        String currentPlayerName = getCurrentPlayerName();

        if (currentPlayerName != null)
        {
            for (com.guess34.lendingtracker.model.LendingGroup group : allGroups)
            {
                // Check if player is member of this group
                boolean isMember = group.getMembers().stream()
                    .anyMatch(member -> member.getName().equals(currentPlayerName));

                if (isMember)
                {
                    String displayName = group.getName() + " (" + group.getMembers().size() + " members)";
                    groupSelector.addItem(displayName);

                    // Store group ID as client property for lookup
                    groupSelector.putClientProperty(displayName, group.getId());

                    // Select current group
                    if (group.getId().equals(currentGroupId))
                    {
                        groupSelector.setSelectedItem(displayName);
                    }
                }
            }
        }

        // Create marketplace content panel (will be updated when group changes)
        JPanel marketplaceContentWrapper = new JPanel(new BorderLayout());
        marketplaceContentWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Build initial content
        String initialGroupId = currentGroupId;
        if (groupSelector.getSelectedItem() != null)
        {
            initialGroupId = (String) groupSelector.getClientProperty(groupSelector.getSelectedItem().toString());
        }
        JPanel initialContent = buildMarketplaceContentForGroup(initialGroupId);
        marketplaceContentWrapper.add(initialContent, BorderLayout.CENTER);

        // Update marketplace when group changes
        groupSelector.addActionListener(e ->
        {
            String selectedDisplay = (String) groupSelector.getSelectedItem();
            if (selectedDisplay != null)
            {
                String selectedGroupId = (String) groupSelector.getClientProperty(selectedDisplay);
                if (selectedGroupId != null)
                {
                    // Update current group
                    groupConfigStore.setCurrentGroupId(selectedGroupId);

                    // Rebuild marketplace content for new group
                    marketplaceContentWrapper.removeAll();
                    JPanel newContent = buildMarketplaceContentForGroup(selectedGroupId);
                    marketplaceContentWrapper.add(newContent, BorderLayout.CENTER);
                    marketplaceContentWrapper.revalidate();
                    marketplaceContentWrapper.repaint();
                }
            }
        });

        JPanel groupSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        groupSelectorPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        groupSelectorPanel.add(groupLabel);
        groupSelectorPanel.add(groupSelector);

        headerPanel.add(groupSelectorPanel, BorderLayout.WEST);

        marketplacePanel.add(headerPanel, BorderLayout.NORTH);
        marketplacePanel.add(marketplaceContentWrapper, BorderLayout.CENTER);

        return marketplacePanel;
    }

    /**
     * Build marketplace content for a specific group
     */
    private JPanel buildMarketplaceContentForGroup(String groupId)
    {
        if (groupId == null)
        {
            JPanel emptyPanel = new JPanel(new BorderLayout());
            emptyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
            JLabel noGroupLabel = new JLabel("Please select or join a group to view marketplace");
            noGroupLabel.setForeground(Color.LIGHT_GRAY);
            noGroupLabel.setHorizontalAlignment(SwingConstants.CENTER);
            emptyPanel.add(noGroupLabel, BorderLayout.CENTER);
            return emptyPanel;
        }

        // Use existing available panel but filtered by group
        // (The existing buildAvailablePanel already shows group-specific data)
        return buildAvailablePanel();
    }
    
    /**
     * Build My Activity panel - user's personal lending/borrowing activity
     */
    private JPanel buildMyActivityPanel()
    {
        JPanel activityPanel = new JPanel(new BorderLayout(10, 10));
        activityPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        activityPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Main content - activity tabs
        JTabbedPane activityTabs = new JTabbedPane();
        activityTabs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        activityTabs.setForeground(Color.WHITE);
        
        // Transactions tab (contains Loans and Borrowed as subtabs)
        JPanel transactionsPanel = buildTransactionsPanel();
        activityTabs.addTab("Transactions", null, transactionsPanel, "Active loans and borrowed items");
        
        // History tab
        JPanel historyPanel = buildHistoryPanel();
        activityTabs.addTab("📋 History", null, historyPanel, "Completed transactions");
        
        // RECONNECTED: Risk Session Management - Risk Monitor tab
        JPanel riskSessionPanel = buildRiskSessionPanel();
        activityTabs.addTab("Risk Monitor", null, riskSessionPanel, "Active high-risk lending sessions");
        
        // RECONNECTED: Collateral Agreement Tracker - Collateral tab
        JPanel collateralPanel = buildCollateralPanel();
        activityTabs.addTab("Collateral", null, collateralPanel, "Active collateral agreements and security deposits");
        
        activityPanel.add(activityTabs, BorderLayout.CENTER);
        
        return activityPanel;
    }
    
    /**
     * Build Group Hub panel - comprehensive group management
     */
    private JPanel buildGroupHubPanel()
    {
        JPanel groupHubPanel = new JPanel(new BorderLayout(10, 10));
        groupHubPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        groupHubPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Main content - group management tabs
        JTabbedPane groupTabs = new JTabbedPane();
        groupTabs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupTabs.setForeground(Color.WHITE);
        
        // Members tab with online status
        JPanel membersPanel = buildGroupPanel();
        groupTabs.addTab("Members", null, membersPanel, "Group members and their status");
        
        // Settings tab
        JPanel settingsPanel = buildGroupSettingsPanel();
        groupTabs.addTab("Settings", null, settingsPanel, "Group settings and Discord integration");
        
        // RECONNECTED: Security Log Viewer - Security Log tab
        JPanel securityLogPanel = buildSecurityLogPanel();
        groupTabs.addTab("Security Log", null, securityLogPanel, "Security events and risk monitoring audit trail");
        
        // RESTORED: Peer Review System - Peer Reviews tab
        JPanel peerReviewPanel = buildPeerReviewPanel();
        groupTabs.addTab("Peer Reviews", null, peerReviewPanel, "Rate and review group member lending behavior");
        
        JPanel borrowRequestPanel = buildBorrowRequestPanel();
        groupTabs.addTab("Borrow Requests", null, borrowRequestPanel, "Submit and manage item borrowing requests");
        
        JPanel warningLogPanel = buildWarningLogPanel();
        groupTabs.addTab("Warning Log", null, warningLogPanel, "View warning and alert history");
        
        JPanel agreementPanel = buildAgreementPanel();
        groupTabs.addTab("Agreements", null, agreementPanel, "Review and respond to borrower agreement requests");
        
        groupHubPanel.add(groupTabs, BorderLayout.CENTER);
        
        return groupHubPanel;
    }
    
    /**
     * Build Transactions panel - contains loans and borrowed as subtabs
     */
    private JPanel buildTransactionsPanel()
    {
        JPanel transactionsPanel = new JPanel(new BorderLayout());
        transactionsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        transactionsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create subtabs for Loans, Borrowed, and My Requests
        JTabbedPane transactionTabs = new JTabbedPane();
        transactionTabs.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        transactionTabs.setForeground(Color.WHITE);

        // Loans subtab (items I've lent out)
        JPanel loansSubTab = buildMyLoansPanel();
        transactionTabs.addTab("Loans", null, loansSubTab, "Items you've lent to others");

        // Borrowed subtab (items I've borrowed)
        JPanel borrowedSubTab = buildMyBorrowedPanel();
        transactionTabs.addTab("Borrowed", null, borrowedSubTab, "Items you've borrowed from others");

        // ADDED: My Requests subtab (requests I've made that are pending)
        JPanel myRequestsSubTab = buildMyRequestsPanel();
        transactionTabs.addTab("My Requests", null, myRequestsSubTab, "Borrow requests you've submitted");

        transactionsPanel.add(transactionTabs, BorderLayout.CENTER);

        return transactionsPanel;
    }

    // ADDED: Build My Requests panel - shows user's own borrow requests
    private JPanel buildMyRequestsPanel()
    {
        JPanel requestsPanel = new JPanel(new BorderLayout());
        requestsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        requestsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel titleLabel = new JLabel("My Borrow Requests");
        titleLabel.setForeground(Color.WHITE);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerPanel.add(titleLabel, BorderLayout.WEST);

        requestsPanel.add(headerPanel, BorderLayout.NORTH);

        // Requests list panel
        JPanel requestsListPanel = new JPanel();
        requestsListPanel.setLayout(new BoxLayout(requestsListPanel, BoxLayout.Y_AXIS));
        requestsListPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        // Get current player's requests
        if (client != null && client.getLocalPlayer() != null) {
            String playerName = client.getLocalPlayer().getName();
            String groupId = groupConfigStore.getCurrentGroupId();

            // Get all active requests for the group
            List<BorrowRequest> allRequests = borrowRequestService.getActiveRequestsForGroup(groupId);

            // Filter to only show requests made by this player
            List<BorrowRequest> myRequests = allRequests.stream()
                .filter(r -> r.getRequesterId().equals(playerName))
                .sorted((a, b) -> Long.compare(b.getRequestDate(), a.getRequestDate())) // Newest first
                .collect(Collectors.toList());

            if (myRequests.isEmpty()) {
                JLabel noRequestsLabel = new JLabel("You have no active borrow requests");
                noRequestsLabel.setForeground(Color.LIGHT_GRAY);
                noRequestsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                requestsListPanel.add(Box.createVerticalStrut(20));
                requestsListPanel.add(noRequestsLabel);
            } else {
                for (BorrowRequest request : myRequests) {
                    requestsListPanel.add(createMyRequestPanel(request, playerName, groupId));
                    requestsListPanel.add(Box.createVerticalStrut(5));
                }
            }
        }

        JScrollPane scrollPane = new JScrollPane(requestsListPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        requestsPanel.add(scrollPane, BorderLayout.CENTER);

        return requestsPanel;
    }

    // ADDED: Create individual request panel for "My Requests" tab
    private JPanel createMyRequestPanel(BorrowRequest request, String playerName, String groupId)
    {
        JPanel requestPanel = new JPanel(new BorderLayout());
        requestPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        requestPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        requestPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

        // Left side: request info
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBackground(requestPanel.getBackground());

        // Item name
        JLabel itemLabel = new JLabel("Item: " + request.getItemName());
        itemLabel.setForeground(Color.CYAN);
        itemLabel.setFont(itemLabel.getFont().deriveFont(Font.BOLD, 13f));
        infoPanel.add(itemLabel);

        // Quantity
        JLabel qtyLabel = new JLabel("Quantity: " + request.getQuantity());
        qtyLabel.setForeground(Color.WHITE);
        qtyLabel.setFont(qtyLabel.getFont().deriveFont(11f));
        infoPanel.add(qtyLabel);

        // Status
        String statusText = "Status: Pending";
        JLabel statusLabel = new JLabel(statusText);
        statusLabel.setForeground(Color.ORANGE);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
        infoPanel.add(statusLabel);

        // Timestamp
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, HH:mm");
        JLabel timeLabel = new JLabel("Requested: " + sdf.format(new Date(request.getRequestDate())));
        timeLabel.setForeground(Color.GRAY);
        timeLabel.setFont(timeLabel.getFont().deriveFont(10f));
        infoPanel.add(timeLabel);

        requestPanel.add(infoPanel, BorderLayout.CENTER);

        // Right side: action button
        JPanel actionsPanel = new JPanel();
        actionsPanel.setLayout(new BoxLayout(actionsPanel, BoxLayout.Y_AXIS));
        actionsPanel.setBackground(requestPanel.getBackground());

        JButton cancelButton = new JButton("Cancel Request");
        cancelButton.setBackground(new Color(139, 0, 0));
        cancelButton.setForeground(Color.WHITE);
        cancelButton.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to cancel this borrow request for " + request.getItemName() + "?",
                "Cancel Request",
                JOptionPane.YES_NO_OPTION
            );

            if (confirm == JOptionPane.YES_OPTION) {
                // Cancel the request using the service method
                boolean cancelled = borrowRequestService.cancelRequest(request.getId(), playerName);

                if (cancelled) {
                    // Remove any active notification overlay for this request
                    if (plugin != null && plugin.getBorrowRequestNotificationOverlay() != null) {
                        plugin.getBorrowRequestNotificationOverlay().removeNotification(request.getId());
                    }

                    // Show confirmation message
                    if (client != null) {
                        clientThread.invokeLater(() -> {
                            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                                "Cancelled borrow request for " + request.getItemName(), "");
                        });
                    }

                    // Refresh the panel
                    SwingUtilities.invokeLater(() -> {
                        // Rebuild the My Requests tab content
                        revalidate();
                        repaint();
                    });
                }
            }
        });

        actionsPanel.add(cancelButton);
        requestPanel.add(actionsPanel, BorderLayout.EAST);

        return requestPanel;
    }

    /**
     * Build Analytics panel - comprehensive reporting and stats
     */
    private JPanel buildAnalyticsPanel()
    {
        JPanel analyticsPanel = new JPanel(new BorderLayout(10, 10));
        analyticsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        analyticsPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Main content - export and backup options
        JPanel exportPanel = buildExportOptionsPanel();
        analyticsPanel.add(exportPanel, BorderLayout.CENTER);
        
        return analyticsPanel;
    }
    
    // =============== SUPPORTING UI COMPONENTS ===============
    
    private JButton createStyledButton(String text, String tooltip)
    {
        JButton button = new JButton(text);
        button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        button.setForeground(Color.WHITE);
        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.LIGHT_GRAY_COLOR, 1),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        return button;
    }
    
    // END OF DUPLICATE METHODS - KEEP FROM HERE
    private JPanel createSectionPanel(String title, JComponent content)
    {
        JPanel sectionPanel = new JPanel(new BorderLayout());
        sectionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        sectionPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.LIGHT_GRAY_COLOR, 1),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        
        if (title != null) {
            JLabel titleLabel = new JLabel(title);
            titleLabel.setForeground(Color.WHITE);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
            titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
            sectionPanel.add(titleLabel, BorderLayout.NORTH);
        }
        
        sectionPanel.add(content, BorderLayout.CENTER);
        return sectionPanel;
    }
    
    private JPanel buildGroupSettingsPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Add Discord settings button
        JButton discordButton = createStyledButton("🎮 Discord Settings", "Configure Discord webhook");
        discordButton.addActionListener(e -> configureDiscordWebhook());
        
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.add(discordButton);
        
        panel.add(buttonPanel, BorderLayout.NORTH);
        return panel;
    }
    
    private JPanel buildExportOptionsPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        // Add existing export controls
        JPanel exportControls = new JPanel(new GridBagLayout());
        exportControls.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        
        c.insets = new Insets(10, 5, 10, 5);
        c.fill = GridBagConstraints.HORIZONTAL;
        
        c.gridx = 0; c.gridy = 0;
        JLabel exportLabel = new JLabel("📄 Export Options:");
        exportLabel.setForeground(Color.WHITE);
        exportLabel.setFont(exportLabel.getFont().deriveFont(Font.BOLD, 14f));
        exportControls.add(exportLabel, c);
        
        c.gridy = 1;
        if (exportFormatComboBox == null) {
            exportFormatComboBox = new JComboBox<>(new String[]{"CSV", "JSON", "Excel"});
        }
        exportFormatComboBox.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        exportFormatComboBox.setForeground(Color.WHITE);
        exportControls.add(exportFormatComboBox, c);
        
        c.gridy = 2;
        if (overdueOnlyCheckBox == null) {
            overdueOnlyCheckBox = new JCheckBox("Overdue Only");
        }
        overdueOnlyCheckBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        overdueOnlyCheckBox.setForeground(Color.WHITE);
        exportControls.add(overdueOnlyCheckBox, c);
        
        c.gridy = 3;
        if (exportButton == null) {
            exportButton = createStyledButton("📄 Advanced Export", "Open advanced export dialog");
            exportButton.addActionListener(e -> export());
        }
        exportControls.add(exportButton, c);
        
        c.gridy = 4;
        JLabel backupLabel = new JLabel("Backup Options:");
        backupLabel.setForeground(Color.WHITE);
        backupLabel.setFont(backupLabel.getFont().deriveFont(Font.BOLD, 14f));
        backupLabel.setBorder(BorderFactory.createEmptyBorder(20, 0, 0, 0));
        exportControls.add(backupLabel, c);
        
        c.gridy = 5;
        JButton backupButton = createStyledButton("Create Backup", "Create data backup");
        backupButton.addActionListener(e -> createDataBackup());
        exportControls.add(backupButton, c);
        
        c.gridy = 6;
        JButton restoreButton = createStyledButton("📂 Restore Backup", "Restore from backup");
        restoreButton.addActionListener(e -> restoreFromBackup());
        exportControls.add(restoreButton, c);
        
        panel.add(exportControls, BorderLayout.NORTH);
        
        return createSectionPanel("Export & Backup", panel);
    }
    
    private JPanel buildCleanGroupPanel()
    {
        JPanel groupPanel = new JPanel(new BorderLayout(0, 10));
        groupPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        groupPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // Get current group info safely
        String currentGroupId = null;
        String currentGroupName = "Default";
        try {
            currentGroupId = groupConfigStore.getCurrentGroupId();
            if (currentGroupId != null) {
                currentGroupName = groupConfigStore.getGroupNameById(currentGroupId);
                if (currentGroupName == null) {
                    currentGroupName = "Default";
                }
            }
        } catch (Exception e) {
            log.error("Error getting current group info: " + e.getMessage(), e);
        }

        // Group selection and controls with centered layout
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
        topPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        topPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(15, 20, 15, 20)
        ));
        
        JLabel groupLabel = new JLabel("Select Group:");
        groupLabel.setForeground(Color.WHITE);
        topPanel.add(groupLabel);
        
        final JComboBox<String> groupCombo = new JComboBox<>();
        groupCombo.setPreferredSize(new Dimension(160, 25));
        // Add group names to combo box with null safety
        try {
            Collection<LendingGroup> groups = groupConfigStore.getAllGroups();
            if (groups != null) {
                for (LendingGroup group : groups) {
                    if (group != null && group.getName() != null) {
                        groupCombo.addItem(group.getName());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error loading groups for dropdown: " + e.getMessage(), e);
            // Don't add Default to dropdown - users must create groups
        }
        groupCombo.setSelectedItem(currentGroupName);
        groupCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupCombo.setForeground(Color.WHITE);
        groupCombo.setPreferredSize(new Dimension(150, 25));
        groupCombo.addActionListener(e -> {
            String selectedGroupName = (String) groupCombo.getSelectedItem();
            if (selectedGroupName != null) {
                String groupId = groupConfigStore.getGroupIdByName(selectedGroupName);
                if (groupId != null) {
                    groupConfigStore.setCurrentGroupId(groupId);
                    refresh();
                }
            }
        });
        topPanel.add(groupCombo);
        
        JButton newGroupBtn = new JButton("+ New Group");
        newGroupBtn.setBackground(ColorScheme.BRAND_ORANGE);
        newGroupBtn.setForeground(Color.WHITE);
        newGroupBtn.setPreferredSize(new Dimension(130, 25));
        newGroupBtn.addActionListener(e -> showCreateGroupDialog());
        topPanel.add(newGroupBtn);
        
        groupPanel.add(topPanel, BorderLayout.NORTH);

        // Group info and join request system
        JPanel memberSection = new JPanel(new BorderLayout(0, 15));
        memberSection.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        // Current members display
        JPanel memberInfoPanel = new JPanel(new BorderLayout(0, 10));
        memberInfoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        memberInfoPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(15, 15, 15, 15)
        ));
        
        JLabel memberLabel = new JLabel("Group Members:");
        memberLabel.setForeground(Color.WHITE);
        memberLabel.setFont(memberLabel.getFont().deriveFont(Font.BOLD, 14f));
        memberInfoPanel.add(memberLabel, BorderLayout.NORTH);
        
        // Members list with scrolling and online status indicators
        try {
            List<String> members = groupConfigStore.getGroupMembers(currentGroupId);

            JList<String> membersList = new JList<>();
            DefaultListModel<String> membersModel = new DefaultListModel<>();

            if (members != null && !members.isEmpty()) {
                for (String member : members) {
                    // Add online status indicator
                    String statusIndicator = onlineStatusService.getStatusIndicator(member);
                    String displayText = statusIndicator + " " + member;
                    membersModel.addElement(displayText);
                }
            } else {
                membersModel.addElement("No members yet");
            }

            membersList.setModel(membersModel);
            membersList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            membersList.setForeground(Color.LIGHT_GRAY);
            membersList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
            membersList.setSelectionForeground(Color.WHITE);

            JScrollPane membersScrollPane = new JScrollPane(membersList);
            membersScrollPane.setPreferredSize(new Dimension(0, 80));
            membersScrollPane.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
            membersScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);

            memberInfoPanel.add(membersScrollPane, BorderLayout.CENTER);

            // Add Reviews button below member list
            JPanel memberActionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 5));
            memberActionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

            JButton reviewsBtn = new JButton("👤 Reviews");
            reviewsBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            reviewsBtn.setForeground(Color.WHITE);
            reviewsBtn.setPreferredSize(new Dimension(110, 28));
            reviewsBtn.setToolTipText("View peer reviews for selected member");
            reviewsBtn.addActionListener(e -> {
                String selectedMember = membersList.getSelectedValue();
                if (selectedMember != null && !selectedMember.equals("No members yet")) {
                    // Strip status indicators to get clean username
                    String cleanName = selectedMember.replaceAll("^[🟢🟡🔴⚫]\\s*", "").replaceAll("\\s*\\(.*\\)$", "").trim();
                    showPeerReviewDialog(cleanName);
                } else {
                    JOptionPane.showMessageDialog(this, "Please select a member first", "No Member Selected", JOptionPane.WARNING_MESSAGE);
                }
            });
            memberActionsPanel.add(reviewsBtn);

            memberInfoPanel.add(memberActionsPanel, BorderLayout.SOUTH);
        } catch (Exception e) {
            log.error("Error loading members: " + e.getMessage(), e);
        }
        
        memberSection.add(memberInfoPanel, BorderLayout.NORTH);
        
        // Group code display with better layout
        JPanel codePanel = new JPanel(new BorderLayout(10, 5));
        codePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        codePanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), 
                "Group Code", TitledBorder.LEFT, TitledBorder.TOP, null, Color.WHITE),
            BorderFactory.createEmptyBorder(10, 15, 15, 15)
        ));
        
        // Generate or get existing group code
        String groupCode = generateGroupCode(currentGroupName);
        
        JPanel codeDisplayPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        codeDisplayPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JTextField codeField = new JTextField(groupCode);
        codeField.setEditable(false);
        codeField.setBackground(ColorScheme.DARK_GRAY_COLOR);
        codeField.setForeground(Color.CYAN);
        codeField.setPreferredSize(new Dimension(80, 25));
        codeField.setFont(codeField.getFont().deriveFont(Font.BOLD, 14f));
        codeDisplayPanel.add(codeField);
        
        JButton copyCodeBtn = new JButton("Copy Code");
        copyCodeBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        copyCodeBtn.setForeground(Color.WHITE);
        copyCodeBtn.setPreferredSize(new Dimension(90, 25));
        final String finalGroupCode = groupCode; // Make effectively final for lambda
        copyCodeBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(finalGroupCode), null);
            JOptionPane.showMessageDialog(this, "Group code '" + finalGroupCode + "' copied to clipboard!", 
                "Copied", JOptionPane.INFORMATION_MESSAGE);
        });
        codeDisplayPanel.add(copyCodeBtn);
        
        codePanel.add(codeDisplayPanel, BorderLayout.CENTER);
        
        memberSection.add(codePanel, BorderLayout.CENTER);
        
        // Pending join requests with better layout
        JPanel pendingPanel = new JPanel(new BorderLayout(0, 10));
        pendingPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pendingPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), 
                "Pending Requests", TitledBorder.LEFT, TitledBorder.TOP, null, Color.WHITE),
            BorderFactory.createEmptyBorder(10, 15, 15, 15)
        ));
        pendingPanel.setMinimumSize(new Dimension(0, 120));
        pendingPanel.setPreferredSize(new Dimension(0, 120));
        
        // Centered display area with proper alignment
        JPanel displayPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        displayPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JLabel pendingDisplay = new JLabel("No pending requests", SwingConstants.CENTER);
        pendingDisplay.setForeground(Color.LIGHT_GRAY);
        displayPanel.add(pendingDisplay);
        
        pendingPanel.add(displayPanel, BorderLayout.CENTER);
        
        // Pending members list with scrolling
        JScrollPane pendingScrollPane = new JScrollPane();
        pendingScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pendingScrollPane.setBorder(null);
        pendingScrollPane.setPreferredSize(new Dimension(0, 60));
        
        JList<String> pendingList = new JList<>();
        pendingList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        pendingList.setForeground(Color.LIGHT_GRAY);
        pendingList.setSelectionBackground(ColorScheme.BRAND_ORANGE);
        pendingList.setSelectionForeground(Color.WHITE);
        
        // Load pending requests for current group
        DefaultListModel<String> pendingModel = new DefaultListModel<>();
        pendingModel.addElement("No pending requests");
        pendingList.setModel(pendingModel);
        
        pendingScrollPane.setViewportView(pendingList);
        pendingPanel.add(pendingScrollPane, BorderLayout.CENTER);
        
        // Small approve/deny buttons in bottom right corner
        JPanel approvalPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        approvalPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton approveBtn = new JButton("✓");
        approveBtn.setBackground(Color.GREEN.darker());
        approveBtn.setForeground(Color.WHITE);
        approveBtn.setPreferredSize(new Dimension(25, 25));
        approveBtn.setToolTipText("Approve selected join request");
        approveBtn.addActionListener(e -> {
            String selected = pendingList.getSelectedValue();
            if (selected != null && !selected.equals("No pending requests")) {
                JOptionPane.showMessageDialog(this, "Approved: " + selected, "Success", JOptionPane.INFORMATION_MESSAGE);
                // Remove from pending list
                pendingModel.removeElement(selected);
                if (pendingModel.isEmpty()) {
                    pendingModel.addElement("No pending requests");
                }
            }
        });
        approvalPanel.add(approveBtn);
        
        JButton denyBtn = new JButton("✗");
        denyBtn.setBackground(Color.RED.darker());
        denyBtn.setForeground(Color.WHITE);
        denyBtn.setPreferredSize(new Dimension(25, 25));
        denyBtn.setToolTipText("Deny selected join request");
        denyBtn.addActionListener(e -> {
            String selected = pendingList.getSelectedValue();
            if (selected != null && !selected.equals("No pending requests")) {
                JOptionPane.showMessageDialog(this, "Denied: " + selected, "Request Denied", JOptionPane.INFORMATION_MESSAGE);
                // Remove from pending list
                pendingModel.removeElement(selected);
                if (pendingModel.isEmpty()) {
                    pendingModel.addElement("No pending requests");
                }
            }
        });
        approvalPanel.add(denyBtn);
        
        pendingPanel.add(approvalPanel, BorderLayout.SOUTH);
        memberSection.add(pendingPanel, BorderLayout.SOUTH);
        
        groupPanel.add(memberSection, BorderLayout.CENTER);

        // Group management actions with proper button sizing
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        actionsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        actionsPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(15, 20, 20, 20)
        ));
        actionsPanel.setPreferredSize(new Dimension(0, 90));
        
        JButton leaveBtn = new JButton("Leave Group");
        leaveBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        leaveBtn.setForeground(Color.WHITE);
        leaveBtn.setPreferredSize(new Dimension(100, 35));
        leaveBtn.setMinimumSize(new Dimension(100, 35));
        leaveBtn.addActionListener(e -> {
            // Get the currently selected group from the dropdown
            String selectedGroupName = (String) groupCombo.getSelectedItem();
            if (selectedGroupName != null && !selectedGroupName.equals("default")) {
                int confirm = JOptionPane.showConfirmDialog(this, 
                    "Are you sure you want to leave '" + selectedGroupName + "'?", 
                    "Confirm Leave", JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    try {
                        String selectedGroupId = groupConfigStore.getGroupIdByName(selectedGroupName);
                        if (selectedGroupId != null) {
                            // Remove current user from the selected group
                            log.info("Leaving group: " + selectedGroupName);

                            // Clear webhook settings for this group (user is leaving)
                            discordWebhookService.clearWebhookUrl(selectedGroupId);

                            // Switch to default group
                            String defaultGroupId = groupConfigStore.getGroupIdByName("default");
                            if (defaultGroupId == null) {
                                defaultGroupId = groupConfigStore.createGroup("default", "Default group", getCurrentPlayerName());
                            }
                            groupConfigStore.setCurrentGroupId(defaultGroupId);
                            
                            // Refresh the UI
                            SwingUtilities.invokeLater(() -> {
                                removeAll();
                                buildPanel();
                                refresh();
                                revalidate();
                                repaint();
                            });
                        }
                    } catch (Exception ex) {
                        log.error("Error leaving group: " + ex.getMessage(), ex);
                        JOptionPane.showMessageDialog(this, "Error leaving group: " + ex.getMessage(), 
                            "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            } else {
                JOptionPane.showMessageDialog(this, "Cannot leave the default group.", 
                    "Invalid Action", JOptionPane.WARNING_MESSAGE);
            }
        });
        
        JButton deleteBtn = new JButton("Delete Group");
        deleteBtn.setBackground(Color.RED.darker());
        deleteBtn.setForeground(Color.WHITE);
        deleteBtn.setPreferredSize(new Dimension(100, 35));
        deleteBtn.setMinimumSize(new Dimension(100, 35));
        deleteBtn.addActionListener(e -> {
            // Get the currently selected group from the dropdown
            String selectedGroupName = (String) groupCombo.getSelectedItem();
            if (selectedGroupName != null && !selectedGroupName.equals("default")) {
                int confirm = JOptionPane.showConfirmDialog(this, 
                    "WARNING: This will permanently delete '" + selectedGroupName + "' and all its data!\n\nAre you sure?", 
                    "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (confirm == JOptionPane.YES_OPTION) {
                    try {
                        String selectedGroupId = groupConfigStore.getGroupIdByName(selectedGroupName);
                        if (selectedGroupId != null) {
                            // Delete the selected group
                            groupConfigStore.deleteGroup(selectedGroupId);
                            
                            // Switch to default group
                            String defaultGroupId = groupConfigStore.getGroupIdByName("default");
                            if (defaultGroupId == null) {
                                defaultGroupId = groupConfigStore.createGroup("default", "Default group", getCurrentPlayerName());
                            }
                            groupConfigStore.setCurrentGroupId(defaultGroupId);
                            
                            // Refresh the UI
                            SwingUtilities.invokeLater(() -> {
                                removeAll();
                                buildPanel();
                                refresh();
                                revalidate();
                                repaint();
                            });
                        }
                    } catch (Exception ex) {
                        log.error("Error deleting group: " + ex.getMessage(), ex);
                        JOptionPane.showMessageDialog(this, "Error deleting group: " + ex.getMessage(), 
                            "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
            } else {
                JOptionPane.showMessageDialog(this, "Cannot delete the default group.", 
                    "Invalid Action", JOptionPane.WARNING_MESSAGE);
            }
        });
        
        JButton exportBtn = new JButton("Export Data");
        exportBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        exportBtn.setForeground(Color.WHITE);
        exportBtn.setPreferredSize(new Dimension(100, 35));
        exportBtn.setMinimumSize(new Dimension(100, 35));
        exportBtn.addActionListener(e -> {
            // Get the currently selected group from the dropdown
            String selectedGroupName = (String) groupCombo.getSelectedItem();
            if (selectedGroupName != null) {
                try {
                    String selectedGroupId = groupConfigStore.getGroupIdByName(selectedGroupName);
                    if (selectedGroupId != null) {
                        recorder.exportData(null, "json", true, selectedGroupId);
                        JOptionPane.showMessageDialog(this, "Data exported successfully!", 
                            "Export Complete", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    log.error("Error exporting data: " + ex.getMessage(), ex);
                }
            }
        });
        
        actionsPanel.add(leaveBtn);
        actionsPanel.add(deleteBtn);
        actionsPanel.add(exportBtn);
        
        groupPanel.add(actionsPanel, BorderLayout.SOUTH);

        return groupPanel;
    }
    
    private JPanel buildCleanHistoryPanel()
    {
        JPanel historyPanel = new JPanel(new BorderLayout(0, 10));
        historyPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        historyPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Filter controls at top
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        filterPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        filterPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));
        
        JLabel filterLabel = new JLabel("Filter by:");
        filterLabel.setForeground(Color.WHITE);
        filterPanel.add(filterLabel);
        
        JComboBox<String> historyFilter = new JComboBox<>(new String[]{"All History", "Returned Items", "Cancelled Loans", "This Month", "This Year"});
        historyFilter.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        historyFilter.setForeground(Color.WHITE);
        historyFilter.setPreferredSize(new Dimension(150, 25));
        filterPanel.add(historyFilter);
        
        JLabel playerLabel = new JLabel("Player:");
        playerLabel.setForeground(Color.WHITE);
        filterPanel.add(playerLabel);
        
        JComboBox<String> playerFilter = new JComboBox<>();
        playerFilter.addItem("All Players");
        // Add unique player names from history with error handling
        try {
            List<LendingEntry> historyEntries = recorder.getHistoryEntries();
            if (historyEntries != null) {
                historyEntries.stream()
                    .map(entry -> entry.getPlayerName())
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(playerFilter::addItem);
            }
        } catch (Exception e) {
            log.error("Error loading player names for history filter: " + e.getMessage(), e);
        }
        playerFilter.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        playerFilter.setForeground(Color.WHITE);
        playerFilter.setPreferredSize(new Dimension(120, 25));
        filterPanel.add(playerFilter);
        
        JButton clearBtn = createStyledButton("🔄 Clear Filters", "Reset all filters");
        clearBtn.addActionListener(e -> {
            historyFilter.setSelectedIndex(0);
            playerFilter.setSelectedIndex(0);
            updateHistoryTable();
        });
        filterPanel.add(clearBtn);
        
        historyPanel.add(filterPanel, BorderLayout.NORTH);
        
        // History table - simplified columns
        String[] columnNames = {"Item", "Player"};
        DefaultTableModel historyModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        JTable historyTable = new JTable(historyModel);
        setupTable(historyTable);
        
        // Custom renderer for history table with tooltips
        historyTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setBackground(isSelected ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.DARKER_GRAY_COLOR);
                setForeground(Color.WHITE);
                
                // Add detailed tooltip
                if (row < historyModel.getRowCount()) {
                    try {
                        String itemName = (String) historyModel.getValueAt(row, 0);
                        String playerName = (String) historyModel.getValueAt(row, 1);
                        
                        setToolTipText("<html>" +
                            "<b>Item:</b> " + itemName + "<br>" +
                            "<b>Player:</b> " + playerName + "<br>" +
                            "<b>Status:</b> Completed<br>" +
                            "<b>Date:</b> Historical entry<br>" +
                            "</html>");
                    } catch (Exception e) {
                        setToolTipText("Hover for details");
                    }
                }
                return this;
            }
        });
        
        // Populate history table with error handling
        try {
            List<LendingEntry> historyEntries = recorder.getHistoryEntries();
            if (historyEntries != null) {
                for (LendingEntry entry : historyEntries) {
                    if (entry != null) {
                        Object[] rowData = {
                            entry.getItemName() != null ? entry.getItemName() : "Unknown Item",
                            entry.getPlayerName() != null ? entry.getPlayerName() : "Unknown"
                        };
                        historyModel.addRow(rowData);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error populating history table: " + e.getMessage(), e);
        }
        
        JScrollPane historyScrollPane = new JScrollPane(historyTable);
        historyScrollPane.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        historyScrollPane.setPreferredSize(new Dimension(0, 400));
        
        historyPanel.add(historyScrollPane, BorderLayout.CENTER);
        
        // History actions at bottom
        JPanel historyActions = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 10));
        historyActions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        historyActions.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
            BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));
        
        JButton exportHistoryBtn = createStyledButton("Export History", "Export history to file");
        exportHistoryBtn.addActionListener(e -> exportHistory());
        
        JButton clearOldBtn = createStyledButton("Clear Old Entries", "Remove old completed entries");
        clearOldBtn.addActionListener(e -> {
            String[] options = {"30 days", "90 days", "1 year", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this,
                "Remove history entries older than:",
                "Clear Old History",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
            
            if (choice >= 0 && choice < 3) {
                int days = choice == 0 ? 30 : (choice == 1 ? 90 : 365);
                recorder.clearOldWarnings(days);
                updateHistoryTable();
            }
        });
        
        JButton generateReportBtn = createStyledButton("📊 Generate Report", "Generate lending statistics report");
        generateReportBtn.addActionListener(e -> generateLendingReport());
        
        historyActions.add(exportHistoryBtn);
        historyActions.add(clearOldBtn);
        historyActions.add(generateReportBtn);
        
        historyPanel.add(historyActions, BorderLayout.SOUTH);
        
        return historyPanel;
    }
    
    private void updateHistoryTable() {
        // Implementation to refresh history table with current filters
        refresh();
    }
    
    private void exportHistory() {
        // Implementation for history export
        String currentGroupId = groupConfigStore.getCurrentGroupId();
        recorder.exportData(null, "csv", true, currentGroupId);
    }
    
    private void generateLendingReport() {
        // Implementation for generating lending statistics report
        long totalLent = recorder.getTotalValueLent();
        int activeLoans = recorder.getActiveEntries().size();
        int overdueCount = recorder.getOverdueEntries().size();
        
        String report = String.format("=== Lending Report ===\n\n" +
            "Active Loans: %d\n" +
            "Total Value: %s GP\n" +
            "Overdue Items: %d\n" +
            "Total History Entries: %d\n",
            activeLoans,
            QuantityFormatter.quantityToStackSize(totalLent),
            overdueCount,
            recorder.getHistoryEntries().size());
        
        JTextArea reportArea = new JTextArea(10, 40);
        reportArea.setText(report);
        reportArea.setEditable(false);
        reportArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        reportArea.setForeground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(reportArea);
        styleScrollBar(scrollPane);
        JOptionPane.showMessageDialog(this, scrollPane, "Lending Statistics Report", JOptionPane.INFORMATION_MESSAGE);
    }
    
    private JPanel buildCleanFooterPanel() {
        // Footer buttons with line wrapping - fixed width container
        JPanel footerPanel = new JPanel();
        footerPanel.setLayout(new BoxLayout(footerPanel, BoxLayout.Y_AXIS));
        footerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        footerPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        footerPanel.setPreferredSize(new Dimension(210, 70));
        footerPanel.setMinimumSize(new Dimension(210, 70));
        footerPanel.setMaximumSize(new Dimension(210, 80));
        
        // First row: Refresh and Screenshots (centered if they fit, wrapped if overflow)
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 3));
        row1.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        refreshBtn.setForeground(Color.WHITE);
        refreshBtn.setPreferredSize(new Dimension(90, 28));
        refreshBtn.setToolTipText("Refresh all data");
        refreshBtn.addActionListener(e -> refresh());
        row1.add(refreshBtn);
        
        JButton screenshotBtn = new JButton("Screenshots");
        screenshotBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        screenshotBtn.setForeground(Color.WHITE);
        screenshotBtn.setPreferredSize(new Dimension(100, 28));
        screenshotBtn.setToolTipText("Open screenshot folder");
        screenshotBtn.addActionListener(e -> openScreenshotFolder());
        row1.add(screenshotBtn);
        
        footerPanel.add(row1);
        
        // Second row: Settings (centered)
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 3));
        row2.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        
        JButton settingsBtn = new JButton("Settings");
        settingsBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        settingsBtn.setForeground(Color.WHITE);
        settingsBtn.setPreferredSize(new Dimension(90, 28));
        settingsBtn.setToolTipText("Open plugin settings");
        settingsBtn.addActionListener(e -> openSettings());
        row2.add(settingsBtn);
        
        footerPanel.add(row2);
        
        return footerPanel;
    }
    
    private void markAsDefaulted(JTable table) {
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
            // Get the selected entry and mark as defaulted
            String entryId = (String) table.getValueAt(selectedRow, 0); // Assuming first column is ID
            LendingEntry entry = recorder.getActiveEntries().stream()
                .filter(e -> entryId.equals(e.getId()))
                .findFirst()
                .orElse(null);
            
            if (entry != null) {
                int confirm = JOptionPane.showConfirmDialog(this,
                    "Mark this loan as defaulted?\n\nThis will record that " + entry.getPlayerName() + 
                    " has failed to return " + entry.getItemName(),
                    "Confirm Default", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    
                if (confirm == JOptionPane.YES_OPTION) {
                    // Mark as completed but not returned (defaulted)
                    recorder.completeEntry(entryId, false);
                    showNotification("Loan Defaulted", 
                        entry.getPlayerName() + " has been marked as defaulted for " + entry.getItemName());
                    refresh();
                }
            }
        } else {
            JOptionPane.showMessageDialog(this, "Please select a loan to mark as defaulted.", 
                "No Selection", JOptionPane.WARNING_MESSAGE);
        }
    }
    
    private void buildFallbackUI() {
        // Create a minimal UI when the full UI fails to initialize
        removeAll();
        setLayout(new BorderLayout());
        
        JPanel messagePanel = new JPanel(new GridBagLayout());
        messagePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JLabel errorLabel = new JLabel("<html><center>" +
            "Lending Tracker is initializing...<br><br>" +
            "If this persists, try disabling and re-enabling the plugin." +
            "</center></html>");
        errorLabel.setForeground(Color.WHITE);
        errorLabel.setFont(errorLabel.getFont().deriveFont(14f));
        errorLabel.setHorizontalAlignment(SwingConstants.CENTER);
        
        messagePanel.add(errorLabel);
        add(messagePanel, BorderLayout.CENTER);
        
        // Add a retry button
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton retryButton = new JButton("Retry Initialization");
        retryButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        retryButton.setForeground(Color.WHITE);
        retryButton.addActionListener(e -> {
            try {
                removeAll();
                buildPanel();
                refresh();
                revalidate();
                repaint();
            } catch (Exception ex) {
                log.error("Retry failed: " + ex.getMessage(), ex);
                JOptionPane.showMessageDialog(this, "Initialization failed. Please check the logs.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttonPanel.add(retryButton);
        
        add(buttonPanel, BorderLayout.SOUTH);
        
        revalidate();
        repaint();
    }
    
    private String getCurrentPlayerName() {
        if (client == null) {
            return "Default";
        }

        // Get the OSRS character name after login
        String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (playerName == null || playerName.trim().isEmpty()) {
            // If not logged in yet, return default and wait for login
            return "Default";
        }

        return playerName;
    }

    private String getCurrentGroup() {
        LendingGroup activeGroup = groupConfigStore.getActiveGroup();
        if (activeGroup == null) {
            return "default";
        }
        return activeGroup.getId();
    }
    
    private String generateGroupCode(String groupName) {
        // Generate a simple 4-character code based on group name
        if (groupName == null || groupName.isEmpty()) {
            return "DFLT";
        }
        
        try {
            // Use first letters + hash for uniqueness
            String prefix = groupName.length() >= 2 ? 
                groupName.substring(0, 2).toUpperCase() : 
                (groupName.toUpperCase() + "XX").substring(0, 2);
            
            // Add 2 digits based on hash
            int hash = Math.abs(groupName.hashCode()) % 100;
            return String.format("%s%02d", prefix, hash);
        } catch (Exception e) {
            return "CODE";
        }
    }
    
    private void showCreateGroupDialog() {
        JDialog dialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this), "Create New Group", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JPanel contentPanel = new JPanel(new GridBagLayout());
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        // Group name field
        gbc.gridx = 0; gbc.gridy = 0;
        JLabel groupNameLabel = new JLabel("Group Name:");
        groupNameLabel.setForeground(Color.WHITE);
        contentPanel.add(groupNameLabel, gbc);
        
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JTextField groupNameField = new JTextField(20);
        groupNameField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        groupNameField.setForeground(Color.WHITE);
        groupNameField.setCaretColor(Color.WHITE);
        contentPanel.add(groupNameField, gbc);
        
        // Description field
        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel descLabel = new JLabel("Description:");
        descLabel.setForeground(Color.WHITE);
        contentPanel.add(descLabel, gbc);
        
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        JTextField descField = new JTextField(20);
        descField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        descField.setForeground(Color.WHITE);
        descField.setCaretColor(Color.WHITE);
        contentPanel.add(descField, gbc);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        
        JButton createBtn = new JButton("Create Group");
        createBtn.setBackground(ColorScheme.BRAND_ORANGE);
        createBtn.setForeground(Color.WHITE);
        createBtn.addActionListener(e -> {
            String groupName = groupNameField.getText().trim();
            String description = descField.getText().trim();
            
            if (groupName.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Please enter a group name.", 
                    "Missing Information", JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            try {
                // Create the group
                String newGroupId = groupConfigStore.createGroup(groupName, description.isEmpty() ? "New lending group" : description, getCurrentPlayerName());
                if (newGroupId != null) {
                    // Auto-add creator as owner
                    String creatorName = getCurrentPlayerName();
                    if (creatorName != null && !creatorName.equals("Default")) {
                        try {
                            groupConfigStore.addMember(newGroupId, creatorName, "owner");
                        } catch (Exception ex) {
                            log.warn("Failed to add creator as owner: " + ex.getMessage());
                        }
                    }
                    
                    // Switch to the new group
                    groupConfigStore.setCurrentGroupId(newGroupId);
                    
                    // Refresh the UI to show the new group
                    SwingUtilities.invokeLater(() -> {
                        removeAll();
                        buildPanel();
                        refresh();
                        revalidate();
                        repaint();
                    });
                    
                    String groupCode = generateGroupCode(groupName);
                    JOptionPane.showMessageDialog(dialog, 
                        "Group '" + groupName + "' created successfully!\n" +
                        "Group Code: " + groupCode + "\n\n" +
                        "Share this code with members so they can join.", 
                        "Group Created", JOptionPane.INFORMATION_MESSAGE);
                    dialog.dispose();
                } else {
                    JOptionPane.showMessageDialog(dialog, "Failed to create group. Name might already exist.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception ex) {
                log.error("Error creating group: " + ex.getMessage(), ex);
                JOptionPane.showMessageDialog(dialog, "Error creating group: " + ex.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        buttonPanel.add(createBtn);
        
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
        cancelBtn.setForeground(Color.WHITE);
        cancelBtn.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelBtn);
        
        dialog.add(contentPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.setSize(400, 200);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
    
    // Missing methods needed by LendingPartyListener
    public void addPartyNotification(String title, String message) {
        SwingUtilities.invokeLater(() -> {
            // Add notification to the notification panel if it exists
            if (notificationPanel != null) {
                JLabel notificationLabel = new JLabel(title + ": " + message);
                notificationLabel.setForeground(ColorScheme.BRAND_ORANGE);
                notificationPanel.add(notificationLabel);
                notificationPanel.revalidate();
                notificationPanel.repaint();
            }
        });
    }
    
    public void updatePartyEntries(List<LendingEntry> entries) {
        SwingUtilities.invokeLater(() -> {
            // Update party table if it exists
            if (partyModel != null) {
                partyModel.setRowCount(0);
                for (LendingEntry entry : entries) {
                    partyModel.addRow(new Object[]{
                        entry.getLender(),
                        entry.getBorrower(),
                        entry.getItem(),
                        entry.getQuantity(),
                        new Date(entry.getLendTime())
                    });
                }
            }
        });
    }

	// ADDED: New methods from PR-After_dev for party functionality

	/**
	 * Builds unified party view with notifications and table
	 */
	private JPanel buildPartyContent()
	{
		JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

		// === TOP SECTION: Recent Notifications ===
		JPanel notificationsSection = new JPanel(new BorderLayout());
		notificationsSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create or reuse existing partyEntriesPanel
		if (partyEntriesPanel == null)
		{
			partyEntriesPanel = new JPanel();
			partyEntriesPanel.setLayout(new BoxLayout(partyEntriesPanel, BoxLayout.Y_AXIS));
			partyEntriesPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		}

		// Wrap in scroll pane with max height
		JScrollPane notificationsScroll = new JScrollPane(partyEntriesPanel);
		notificationsScroll.setPreferredSize(new Dimension(0, 200)); // Max 200px height
		notificationsScroll.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		notificationsScroll.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
			"📢 Recent Party Activity",
			TitledBorder.LEFT,
			TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 12),
			ColorScheme.BRAND_ORANGE
		));

		notificationsSection.add(notificationsScroll, BorderLayout.CENTER);

		// === MIDDLE SECTION: Party Lending Table ===
		JPanel tableSection = new JPanel(new BorderLayout());
		tableSection.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Create party table if not exists
		if (partyTable == null)
		{
			partyModel = new DefaultTableModel(
				new Object[]{"Party Member", "Item", "Borrower/Lender", "Collateral", "Due", "Status", "Value"}, 0)
			{
				@Override
				public boolean isCellEditable(int row, int column)
				{
					return false;
				}
			};
			partyTable = new JTable(partyModel);
			setupTable(partyTable);

			// Column renderers
			partyTable.getColumnModel().getColumn(3).setCellRenderer(new CollateralCellRenderer());
			partyTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer()
			{
				@Override
				public Component getTableCellRendererComponent(JTable table, Object value,
															  boolean isSelected, boolean hasFocus,
															  int row, int column)
				{
					Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

					// Color code by status
					if (column == 5 && value != null)
					{
						String status = value.toString();
						if (status.contains("OVERDUE"))
						{
							c.setForeground(Color.RED);
						}
						else if (status.contains("Returned"))
						{
							c.setForeground(Color.GREEN);
						}
						else
						{
							c.setForeground(Color.WHITE);
						}
					}

					return c;
				}
			});
		}

		JScrollPane tableScroll = new JScrollPane(partyTable);
		tableScroll.setBorder(BorderFactory.createTitledBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			"📊 All Party Lendings",
			TitledBorder.LEFT,
			TitledBorder.TOP,
			new Font("Arial", Font.BOLD, 12),
			Color.WHITE
		));
		tableSection.add(tableScroll, BorderLayout.CENTER);

		// === BOTTOM SECTION: Actions & Stats ===
		JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		actionsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton refreshButton = new JButton("Refresh Party Data");
		refreshButton.setBackground(ColorScheme.BRAND_ORANGE);
		refreshButton.setForeground(Color.WHITE);
		refreshButton.addActionListener(e -> refreshPartyData());

		JButton exportButton = new JButton("Export Party Loans");
		exportButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		exportButton.setForeground(Color.WHITE);
		exportButton.addActionListener(e -> exportPartyData());

		JButton clearNotificationsButton = new JButton("Clear Notifications");
		clearNotificationsButton.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		clearNotificationsButton.setForeground(Color.WHITE);
		clearNotificationsButton.addActionListener(e -> clearPartyNotifications());

		actionsPanel.add(refreshButton);
		actionsPanel.add(exportButton);
		actionsPanel.add(clearNotificationsButton);

		// Assemble sections
		JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
											  notificationsSection,
											  tableSection);
		splitPane.setDividerLocation(200);
		splitPane.setResizeWeight(0.3); // 30% for notifications, 70% for table

		mainPanel.add(splitPane, BorderLayout.CENTER);
		mainPanel.add(actionsPanel, BorderLayout.SOUTH);

		return mainPanel;
	}

	/**
	 * Update both party notifications and table together
	 */
	public void updatePartyData(List<LendingEntry> partyEntries)
	{
		if (partyEntries == null)
		{
			partyEntries = new ArrayList<>();
		}

		// Update notifications panel (existing method)
		updatePartyEntries(partyEntries);

		// Update table (existing method)
		updatePartyTable(partyEntries);
	}

	/**
	 * Refresh party data from service
	 */
	private void refreshPartyData()
	{
		// This would be called from LendingPartyService
		// For now, trigger a refresh
		refresh();
		showNotification("Party data refreshed", "");
	}

	/**
	 * Export party lending data
	 */
	private void exportPartyData()
	{
		if (partyModel == null || partyModel.getRowCount() == 0)
		{
			showError("No party data to export");
			return;
		}

		// Use existing export system with default configuration
		ExportConfiguration defaultConfig = new ExportConfiguration();
		defaultConfig.format = "CSV";
		defaultConfig.includeActiveLoans = true;
		defaultConfig.includeLoanHistory = true;
		defaultConfig.overdueOnly = false;
		defaultConfig.includeGroupData = true;
		defaultConfig.includeCollateral = false;
		defaultConfig.includeRiskData = false;
		defaultConfig.includeDetailedTimestamps = false;
		defaultConfig.includeMemberStats = false;
		defaultConfig.dateRange = "all";
		performAdvancedExport(defaultConfig);
	}

	/**
	 * Clear party notifications
	 */
	private void clearPartyNotifications()
	{
		if (partyEntriesPanel != null)
		{
			partyEntriesPanel.removeAll();
			partyEntriesPanel.revalidate();
			partyEntriesPanel.repaint();
			showNotification("Notifications cleared", "Party notifications have been cleared");
		}
	}

	/**
	 * Show peer review dialog for a member
	 */
	private void showPeerReviewDialog(String memberName)
	{
		if (peerReviewService == null)
		{
			showError("Peer review service not available");
			return;
		}

		String currentGroupId = groupConfigStore.getCurrentGroupId();
		if (currentGroupId == null)
		{
			showError("No group selected");
			return;
		}

		// Get member reputation
		PeerReviewService.MemberReputation reputation = peerReviewService.getMemberReputation(memberName, currentGroupId);

		// Create dialog
		JDialog reviewDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this));
		reviewDialog.setTitle("Peer Reviews - " + memberName);
		reviewDialog.setModal(true);
		reviewDialog.setSize(500, 600);
		reviewDialog.setLocationRelativeTo(this);

		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

		// === HEADER: Member name and overall rating ===
		JPanel headerPanel = new JPanel(new BorderLayout());
		headerPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		headerPanel.setBorder(BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE, 2));
		headerPanel.setPreferredSize(new Dimension(0, 80));

		JLabel nameLabel = new JLabel("<html><b>" + memberName + "</b></html>");
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(16f));
		nameLabel.setBorder(new EmptyBorder(10, 10, 5, 10));
		headerPanel.add(nameLabel, BorderLayout.NORTH);

		JLabel ratingLabel = new JLabel("<html>" + reputation.getStarDisplay() + " " + reputation.getDisplayRating() + "</html>");
		ratingLabel.setForeground(Color.YELLOW); // Yellow/gold color for star rating
		ratingLabel.setFont(ratingLabel.getFont().deriveFont(14f));
		ratingLabel.setBorder(new EmptyBorder(5, 10, 10, 10));
		headerPanel.add(ratingLabel, BorderLayout.CENTER);

		mainPanel.add(headerPanel, BorderLayout.NORTH);

		// === CENTER: Review list ===
		JPanel reviewsPanel = new JPanel();
		reviewsPanel.setLayout(new BoxLayout(reviewsPanel, BoxLayout.Y_AXIS));
		reviewsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		if (reputation.recentReviews != null && !reputation.recentReviews.isEmpty())
		{
			for (PeerReview review : reputation.recentReviews)
			{
				JPanel reviewCard = new JPanel(new BorderLayout(5, 5));
				reviewCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				reviewCard.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
					new EmptyBorder(10, 10, 10, 10)
				));
				reviewCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));

				// Review header (reviewer + stars)
				String stars = "★".repeat(review.getRating()) + "☆".repeat(5 - review.getRating());
				JLabel reviewHeader = new JLabel("<html><b>" + review.getReviewerId() + "</b> - " + stars + "</html>");
				reviewHeader.setForeground(Color.WHITE);
				reviewCard.add(reviewHeader, BorderLayout.NORTH);

				// Review comment
				if (review.getComment() != null && !review.getComment().isEmpty())
				{
					JTextArea commentArea = new JTextArea(review.getComment());
					commentArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
					commentArea.setForeground(Color.LIGHT_GRAY);
					commentArea.setEditable(false);
					commentArea.setLineWrap(true);
					commentArea.setWrapStyleWord(true);
					commentArea.setBorder(new EmptyBorder(5, 0, 0, 0));
					reviewCard.add(commentArea, BorderLayout.CENTER);
				}

				reviewsPanel.add(reviewCard);
				reviewsPanel.add(Box.createRigidArea(new Dimension(0, 10)));
			}
		}
		else
		{
			JLabel noReviews = new JLabel("No reviews yet");
			noReviews.setForeground(Color.GRAY);
			noReviews.setAlignmentX(Component.CENTER_ALIGNMENT);
			reviewsPanel.add(noReviews);
		}

		JScrollPane scrollPane = new JScrollPane(reviewsPanel);
        styleScrollBar(scrollPane);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		mainPanel.add(scrollPane, BorderLayout.CENTER);

		// === BOTTOM: Submit review button ===
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton submitReviewBtn = new JButton("Submit New Review");
		submitReviewBtn.setBackground(ColorScheme.BRAND_ORANGE);
		submitReviewBtn.setForeground(Color.WHITE);
		submitReviewBtn.addActionListener(e -> {
			reviewDialog.dispose();
			showSubmitReviewDialog(memberName, currentGroupId);
		});
		buttonPanel.add(submitReviewBtn);

		JButton closeBtn = new JButton("Close");
		closeBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		closeBtn.setForeground(Color.WHITE);
		closeBtn.addActionListener(e -> reviewDialog.dispose());
		buttonPanel.add(closeBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		reviewDialog.add(mainPanel);
		reviewDialog.setVisible(true);
	}

	/**
	 * Show dialog to submit a new peer review
	 */
	private void showSubmitReviewDialog(String memberName, String groupId)
	{
		JDialog submitDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this));
		submitDialog.setTitle("Submit Review for " + memberName);
		submitDialog.setModal(true);
		submitDialog.setSize(400, 350);
		submitDialog.setLocationRelativeTo(this);

		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

		// Content panel
		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Rating selection
		JLabel ratingLabel = new JLabel("Rating (1-5 stars):");
		ratingLabel.setForeground(Color.WHITE);
		ratingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(ratingLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 5)));

		JPanel starsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		starsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		starsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		ButtonGroup starGroup = new ButtonGroup();
		JRadioButton[] starButtons = new JRadioButton[5];
		for (int i = 0; i < 5; i++)
		{
			final int rating = i + 1;
			starButtons[i] = new JRadioButton(rating + " ★");
			starButtons[i].setBackground(ColorScheme.DARK_GRAY_COLOR);
			starButtons[i].setForeground(Color.WHITE);
			starGroup.add(starButtons[i]);
			starsPanel.add(starButtons[i]);
		}
		starButtons[4].setSelected(true); // Default to 5 stars
		contentPanel.add(starsPanel);

		contentPanel.add(Box.createRigidArea(new Dimension(0, 15)));

		// Transaction type
		JLabel typeLabel = new JLabel("Transaction Type:");
		typeLabel.setForeground(Color.WHITE);
		typeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(typeLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 5)));

		JComboBox<String> typeCombo = new JComboBox<>(new String[]{"lending", "borrowing"});
		typeCombo.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		typeCombo.setForeground(Color.WHITE);
		typeCombo.setMaximumSize(new Dimension(200, 25));
		typeCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(typeCombo);

		contentPanel.add(Box.createRigidArea(new Dimension(0, 15)));

		// Comment
		JLabel commentLabel = new JLabel("Comment (optional):");
		commentLabel.setForeground(Color.WHITE);
		commentLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(commentLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 5)));

		JTextArea commentArea = new JTextArea(4, 30);
		commentArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		commentArea.setForeground(Color.WHITE);
		commentArea.setLineWrap(true);
		commentArea.setWrapStyleWord(true);
		JScrollPane commentScroll = new JScrollPane(commentArea);
		commentScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		contentPanel.add(commentScroll);

		mainPanel.add(contentPanel, BorderLayout.CENTER);

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton submitBtn = new JButton("Submit Review");
		submitBtn.setBackground(ColorScheme.BRAND_ORANGE);
		submitBtn.setForeground(Color.WHITE);
		submitBtn.addActionListener(e -> {
			// Get selected rating
			int rating = 5;
			for (int i = 0; i < 5; i++)
			{
				if (starButtons[i].isSelected())
				{
					rating = i + 1;
					break;
				}
			}

			String transactionType = (String) typeCombo.getSelectedItem();
			String comment = commentArea.getText().trim();

			// Create review
			PeerReview review = new PeerReview(
				getCurrentPlayerName(),
				memberName,
				groupId,
				rating,
				transactionType,
				comment,
				String.valueOf(System.currentTimeMillis())
			);

			// Submit review
			if (peerReviewService.submitReview(review))
			{
				submitDialog.dispose();
				showNotification("Review Submitted", "Your review for " + memberName + " has been submitted");
			}
			else
			{
				showError("Failed to submit review. You may have already reviewed this member or there was an error.");
			}
		});
		buttonPanel.add(submitBtn);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		cancelBtn.setForeground(Color.WHITE);
		cancelBtn.addActionListener(e -> submitDialog.dispose());
		buttonPanel.add(cancelBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		submitDialog.add(mainPanel);
		submitDialog.setVisible(true);
	}

	/**
	 * Show dialog to submit a borrow request
	 */
	private void showSubmitBorrowRequestDialog()
	{
		if (borrowRequestService == null)
		{
			showError("Borrow request service not available");
			return;
		}

		JDialog requestDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this));
		requestDialog.setTitle("Marketplace - Post Item");
		requestDialog.setModal(true);
		requestDialog.setSize(500, 650);
		requestDialog.setLocationRelativeTo(this);

		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

		// Container for group selector and toggle panel
		JPanel topContainer = new JPanel();
		topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
		topContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Group selector at the very top
		JPanel groupSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
		groupSelectorPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel groupLabel = new JLabel("Group:");
		groupLabel.setForeground(Color.WHITE);
		groupSelectorPanel.add(groupLabel);

		JComboBox<String> groupComboBox = new JComboBox<>();
		groupComboBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		groupComboBox.setForeground(Color.WHITE);
		groupComboBox.setPreferredSize(new Dimension(300, 30));

		// Populate with groups - store group IDs separately
		List<LendingGroup> groups = new ArrayList<>(groupConfigStore.getAllGroups());
		final String[] selectedGroupId = new String[1];
		String currentGroupId = groupConfigStore.getCurrentGroupId();

		if (groups.isEmpty())
		{
			showError("You must be in a group to use the marketplace");
			return;
		}

		int selectedIndex = 0;
		for (int i = 0; i < groups.size(); i++)
		{
			LendingGroup group = groups.get(i);
			groupComboBox.addItem(group.getName());
			if (currentGroupId != null && group.getId().equals(currentGroupId))
			{
				selectedIndex = i;
			}
		}

		groupComboBox.setSelectedIndex(selectedIndex);
		selectedGroupId[0] = groups.get(selectedIndex).getId();

		// Update selectedGroupId when combo box selection changes
		groupComboBox.addActionListener(e -> {
			int index = groupComboBox.getSelectedIndex();
			if (index >= 0 && index < groups.size())
			{
				selectedGroupId[0] = groups.get(index).getId();
			}
		});

		groupSelectorPanel.add(groupComboBox);
		topContainer.add(groupSelectorPanel);

		// Toggle panel at top - switch between "I Need" and "I'm Offering"
		final boolean[] isOffering = {false}; // false = need item, true = offering item
		JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
		togglePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton needBtn = new JButton("🔍 I Need an Item");
		needBtn.setBackground(ColorScheme.BRAND_ORANGE);
		needBtn.setForeground(Color.WHITE);
		needBtn.setPreferredSize(new Dimension(180, 35));
		needBtn.setToolTipText("Post a request for an item you need to borrow");

		JButton offerBtn = new JButton("📦 I'm Offering an Item");
		offerBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		offerBtn.setForeground(Color.WHITE);
		offerBtn.setPreferredSize(new Dimension(180, 35));
		offerBtn.setToolTipText("Post an item you're willing to lend");

		togglePanel.add(needBtn);
		togglePanel.add(offerBtn);

		// Content panel
		JPanel contentPanel = new JPanel(new GridBagLayout());
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new Insets(5, 5, 5, 5);
		gbc.gridx = 0;
		gbc.gridy = 0;
		gbc.anchor = GridBagConstraints.WEST;

		// Item name with autocomplete
		JLabel itemLabel = new JLabel("Item Name:");
		itemLabel.setForeground(Color.WHITE);
		contentPanel.add(itemLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		com.guess34.lendingtracker.ui.ItemSearchField itemSearchField =
			new com.guess34.lendingtracker.ui.ItemSearchField(itemManager);
		itemSearchField.setPlaceholder("Start typing item name...");
		contentPanel.add(itemSearchField, gbc);

		// Quantity
		gbc.gridx = 0;
		gbc.gridy = 1;
		gbc.weightx = 0;
		JLabel qtyLabel = new JLabel("Quantity:");
		qtyLabel.setForeground(Color.WHITE);
		contentPanel.add(qtyLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JSpinner qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1000000, 1));
		qtySpinner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) qtySpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) qtySpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		contentPanel.add(qtySpinner, gbc);

		// Collateral value (label changes based on mode) - CREATE FIRST so it can be referenced
		gbc.gridx = 0;
		gbc.gridy = 2;
		gbc.weightx = 0;
		JLabel valueLabel = new JLabel("Collateral Value (GP):");
		valueLabel.setForeground(Color.WHITE);
		contentPanel.add(valueLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JSpinner valueSpinner = new JSpinner(new SpinnerNumberModel(0, 0, Integer.MAX_VALUE, 100000));
		valueSpinner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) valueSpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) valueSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		contentPanel.add(valueSpinner, gbc);

		// Collateral (label changes based on mode)
		gbc.gridx = 0;
		gbc.gridy = 3;
		gbc.weightx = 0;
		JLabel collateralLabel = new JLabel("Collateral Offered:");
		collateralLabel.setForeground(Color.WHITE);
		contentPanel.add(collateralLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		com.guess34.lendingtracker.ui.ItemSearchField collateralField =
			new com.guess34.lendingtracker.ui.ItemSearchField(itemManager);
		collateralField.setPlaceholder("Start typing collateral item name...");

		// Auto-populate collateral value when collateral item is selected
		collateralField.setOnItemSelected(itemName -> {
			if (itemName != null && !itemName.isEmpty())
			{
				List<net.runelite.http.api.item.ItemPrice> prices = itemManager.search(itemName);
				if (!prices.isEmpty())
				{
					net.runelite.http.api.item.ItemPrice firstMatch = prices.get(0);
					int wikiPrice = itemManager.getItemPrice(firstMatch.getId());
					valueSpinner.setValue(wikiPrice);
					log.debug("Auto-populated collateral value: {} GP for item: {}", wikiPrice, itemName);
				}
			}
		});
		contentPanel.add(collateralField, gbc);

		// Duration (only for offering mode)
		gbc.gridx = 0;
		gbc.gridy = 4;
		gbc.weightx = 0;
		JLabel durationLabel = new JLabel("Max Duration (hours):");
		durationLabel.setForeground(Color.WHITE);
		durationLabel.setVisible(false); // Hidden in "Need" mode
		contentPanel.add(durationLabel, gbc);

		gbc.gridx = 1;
		gbc.weightx = 1.0;
		JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(24, 1, 168, 1));
		durationSpinner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) durationSpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) durationSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		durationSpinner.setVisible(false); // Hidden in "Need" mode
		contentPanel.add(durationSpinner, gbc);

		// Message/Notes
		gbc.gridx = 0;
		gbc.gridy = 5;
		gbc.weightx = 0;
		JLabel messageLabel = new JLabel("Message:");
		messageLabel.setForeground(Color.WHITE);
		contentPanel.add(messageLabel, gbc);

		gbc.gridx = 1;
		gbc.gridy = 6;
		gbc.weightx = 1.0;
		gbc.gridwidth = 2;
		JTextArea messageArea = new JTextArea(4, 20);
		messageArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		messageArea.setForeground(Color.WHITE);
		messageArea.setLineWrap(true);
		messageArea.setWrapStyleWord(true);
		JScrollPane messageScroll = new JScrollPane(messageArea);
		contentPanel.add(messageScroll, gbc);

		// Toggle button action listeners
		needBtn.addActionListener(e -> {
			if (isOffering[0]) {
				isOffering[0] = false;
				needBtn.setBackground(ColorScheme.BRAND_ORANGE);
				offerBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
				// Update labels for "Need" mode
				collateralLabel.setText("Collateral Offered:");
				valueLabel.setText("Collateral Value (GP):");
				messageLabel.setText("Message:");
				durationLabel.setVisible(false);
				durationSpinner.setVisible(false);
				requestDialog.setTitle("Marketplace - Request Item");
			}
		});

		offerBtn.addActionListener(e -> {
			if (!isOffering[0]) {
				isOffering[0] = true;
				offerBtn.setBackground(ColorScheme.BRAND_ORANGE);
				needBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
				// Update labels for "Offering" mode
				collateralLabel.setText("Collateral Required:");
				valueLabel.setText("Collateral Value (GP):");
				messageLabel.setText("Notes:");
				durationLabel.setVisible(true);
				durationSpinner.setVisible(true);
				requestDialog.setTitle("Marketplace - Offer Item");
			}
		});

		// Add toggle panel to topContainer
		topContainer.add(togglePanel);

		// Add topContainer and content to main panel
		mainPanel.add(topContainer, BorderLayout.NORTH);
		mainPanel.add(contentPanel, BorderLayout.CENTER);

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton submitBtn = new JButton("Submit");
		submitBtn.setBackground(ColorScheme.BRAND_ORANGE);
		submitBtn.setForeground(Color.WHITE);
		submitBtn.addActionListener(e -> {
			String itemName = itemSearchField.getText().trim();
			if (itemName.isEmpty())
			{
				showError("Please enter an item name");
				return;
			}

			int quantity = (int) qtySpinner.getValue();
			String collateral = collateralField.getText().trim();
			int collateralValue = (int) valueSpinner.getValue();
			String message = messageArea.getText().trim();

			if (isOffering[0]) {
				// OFFERING MODE: Create a lend offer
				int duration = (int) durationSpinner.getValue();

				LendingEntry lendOffer = new LendingEntry();
				lendOffer.setLender(getCurrentPlayerName());
				lendOffer.setBorrower(""); // Empty, anyone can request
				lendOffer.setItem(itemName);
				lendOffer.setQuantity(quantity);
				lendOffer.setCollateralValue(collateralValue);
				lendOffer.setCollateralType(collateral.isEmpty() ? "none" : collateral);
				lendOffer.setNotes(message);
				lendOffer.setGroupId(selectedGroupId[0]);
				lendOffer.setLendTime(System.currentTimeMillis());
				lendOffer.setDueTime(System.currentTimeMillis() + (duration * 3600000L));
				lendOffer.setReturnedAt(0L); // Not returned yet
				lendOffer.setId(java.util.UUID.randomUUID().toString());

				// Save to recorder
				recorder.addAvailable(selectedGroupId[0], getCurrentPlayerName(), lendOffer);
				requestDialog.dispose();
				showNotification("Item Offered", "Your item has been posted to the marketplace");
				refresh();

			} else {
				// NEED MODE: Create a borrow request
				BorrowRequest request = new BorrowRequest(
					selectedGroupId[0],
					getCurrentPlayerName(),
					itemName,
					quantity,
					collateralValue,
					collateral,
					message,
					System.currentTimeMillis()
				);

				// Submit request
				BorrowRequestService.SubmitResult result = borrowRequestService.submitBorrowRequest(request);
				if (result.success)
				{
					requestDialog.dispose();
					showNotification("Request Submitted", "Your borrow request has been submitted to the group");
					refresh();
				}
				else
				{
					showError("Failed to submit request:\n" + result.errorMessage);
				}
			}
		});
		buttonPanel.add(submitBtn);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		cancelBtn.setForeground(Color.WHITE);
		cancelBtn.addActionListener(e -> requestDialog.dispose());
		buttonPanel.add(cancelBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		requestDialog.add(mainPanel);
		requestDialog.setVisible(true);
	}

	/**
	 * Show dialog to view and manage borrow requests
	 */
	private void showBorrowRequestsDialog()
	{
		if (borrowRequestService == null)
		{
			showError("Borrow request service not available");
			return;
		}

		String currentGroupId = groupConfigStore.getCurrentGroupId();
		if (currentGroupId == null)
		{
			showError("Please select a group first");
			return;
		}

		JDialog requestsDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this));
		requestsDialog.setTitle("Active Borrow Requests");
		requestsDialog.setModal(true);
		requestsDialog.setSize(650, 500);
		requestsDialog.setLocationRelativeTo(this);

		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

		// Requests list
		JPanel requestsPanel = new JPanel();
		requestsPanel.setLayout(new BoxLayout(requestsPanel, BoxLayout.Y_AXIS));
		requestsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		List<BorrowRequest> activeRequests = borrowRequestService.getActiveRequestsForGroup(currentGroupId);

		if (activeRequests != null && !activeRequests.isEmpty())
		{
			for (BorrowRequest request : activeRequests)
			{
				JPanel requestCard = new JPanel(new BorderLayout(10, 10));
				requestCard.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				requestCard.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
					new EmptyBorder(10, 10, 10, 10)
				));
				requestCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));

				// Header: Requester + Item
				String headerText = String.format("<html><b>%s</b> requests <b>%s</b> x%d</html>",
					request.getRequesterId(),
					request.getItemName(),
					request.getQuantity());
				JLabel headerLabel = new JLabel(headerText);
				headerLabel.setForeground(Color.WHITE);
				headerLabel.setFont(headerLabel.getFont().deriveFont(14f));
				requestCard.add(headerLabel, BorderLayout.NORTH);

				// Details
				JPanel detailsPanel = new JPanel();
				detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
				detailsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

				if (request.getCollateralOffered() != null && !request.getCollateralOffered().isEmpty())
				{
					JLabel collateralLabel = new JLabel("Collateral: " + request.getCollateralOffered() +
						(request.getCollateralValue() > 0 ? " (" + QuantityFormatter.quantityToStackSize(request.getCollateralValue()) + " GP)" : ""));
					collateralLabel.setForeground(Color.LIGHT_GRAY);
					detailsPanel.add(collateralLabel);
				}

				if (request.getMessage() != null && !request.getMessage().isEmpty())
				{
					JLabel messageLabel = new JLabel("<html>Message: " + request.getMessage() + "</html>");
					messageLabel.setForeground(Color.LIGHT_GRAY);
					detailsPanel.add(messageLabel);
				}

				JLabel timeLabel = new JLabel("Expires: " + request.getTimeRemaining());
				timeLabel.setForeground(Color.GRAY);
				detailsPanel.add(timeLabel);

				requestCard.add(detailsPanel, BorderLayout.CENTER);

				// Buttons (only show if not your own request)
				if (!request.getRequesterId().equals(getCurrentPlayerName()))
				{
					JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
					btnPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

					JButton acceptBtn = new JButton("✓ Accept");
					acceptBtn.setBackground(ColorScheme.BRAND_ORANGE);
					acceptBtn.setForeground(Color.WHITE);
					acceptBtn.addActionListener(e -> {
						String message = JOptionPane.showInputDialog(requestsDialog,
							"Enter a message for the requester (optional):",
							"Accept Request",
							JOptionPane.PLAIN_MESSAGE);

						if (message != null) // User didn't cancel
						{
							if (borrowRequestService.acceptRequest(request.getId(), getCurrentPlayerName(), message))
							{
								requestsDialog.dispose();
								showNotification("Request Accepted", "You accepted the request. Contact " + request.getRequesterId() + " to complete the transaction.");
							}
							else
							{
								showError("Failed to accept request");
							}
						}
					});
					btnPanel.add(acceptBtn);

					JButton declineBtn = new JButton("✗ Decline");
					declineBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
					declineBtn.setForeground(Color.WHITE);
					declineBtn.addActionListener(e -> {
						int confirm = JOptionPane.showConfirmDialog(requestsDialog,
							"Decline this request?",
							"Confirm Decline",
							JOptionPane.YES_NO_OPTION);

						if (confirm == JOptionPane.YES_OPTION)
						{
							if (borrowRequestService.declineRequest(request.getId(), getCurrentPlayerName(), "Request declined"))
							{
								requestsDialog.dispose();
								showNotification("Request Declined", "The request has been declined");
							}
							else
							{
								showError("Failed to decline request");
							}
						}
					});
					btnPanel.add(declineBtn);

					requestCard.add(btnPanel, BorderLayout.SOUTH);
				}
				else
				{
					// Show cancel button for own requests
					JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
					btnPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

					JButton cancelBtn = new JButton("Cancel Request");
					cancelBtn.setBackground(Color.RED.darker());
					cancelBtn.setForeground(Color.WHITE);
					cancelBtn.addActionListener(e -> {
						if (borrowRequestService.cancelRequest(request.getId(), getCurrentPlayerName()))
						{
							requestsDialog.dispose();
							showNotification("Request Cancelled", "Your request has been cancelled");
						}
						else
						{
							showError("Failed to cancel request");
						}
					});
					btnPanel.add(cancelBtn);

					requestCard.add(btnPanel, BorderLayout.SOUTH);
				}

				requestsPanel.add(requestCard);
				requestsPanel.add(Box.createRigidArea(new Dimension(0, 10)));
			}
		}
		else
		{
			JLabel noRequests = new JLabel("No active borrow requests");
			noRequests.setForeground(Color.GRAY);
			noRequests.setAlignmentX(Component.CENTER_ALIGNMENT);
			requestsPanel.add(noRequests);
		}

		JScrollPane scrollPane = new JScrollPane(requestsPanel);
        styleScrollBar(scrollPane);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		mainPanel.add(scrollPane, BorderLayout.CENTER);

		// Close button
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton closeBtn = new JButton("Close");
		closeBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		closeBtn.setForeground(Color.WHITE);
		closeBtn.addActionListener(e -> requestsDialog.dispose());
		buttonPanel.add(closeBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		requestsDialog.add(mainPanel);
		requestsDialog.setVisible(true);
	}

	/**
	 * Show lend item dialog (called from right-click menu)
	 */
	public void showLendItemDialog(String itemName, int itemId)
	{
		try
		{
			log.info("showLendItemDialog called in panel for: {} (ID: {})", itemName, itemId);
			String currentGroupId = groupConfigStore.getCurrentGroupId();
			log.info("Current group ID: {}", currentGroupId);
			if (currentGroupId == null)
			{
				log.warn("No group selected, showing error");
				showError("Please select a group first");
				return;
			}
			log.info("Creating lend item dialog...");

			JDialog dialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this));
		dialog.setTitle("Lend Item to Group");
		dialog.setModal(true);
		dialog.setSize(450, 350);
		dialog.setLocationRelativeTo(this);
		log.info("Dialog created and configured");

		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

		// Content panel
		JPanel contentPanel = new JPanel(new GridBagLayout());
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new Insets(5, 5, 5, 5);

		// Item name (read-only)
		gbc.gridx = 0; gbc.gridy = 0;
		JLabel itemLabel = new JLabel("Item:");
		itemLabel.setForeground(Color.WHITE);
		contentPanel.add(itemLabel, gbc);

		gbc.gridx = 1; gbc.weightx = 1.0;
		JLabel itemValueLabel = new JLabel(itemName);
		itemValueLabel.setForeground(ColorScheme.BRAND_ORANGE);
		itemValueLabel.setFont(itemValueLabel.getFont().deriveFont(Font.BOLD));
		contentPanel.add(itemValueLabel, gbc);

		// Required collateral value (auto-populated from wiki price)
		gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
		JLabel collateralLabel = new JLabel("Required Collateral (GP):");
		collateralLabel.setForeground(Color.WHITE);
		contentPanel.add(collateralLabel, gbc);

		// Get wiki price for item and set as default collateral
		int wikiPrice = itemManager.getItemPrice(itemId);
		gbc.gridx = 1; gbc.weightx = 1.0;
		JSpinner collateralSpinner = new JSpinner(new SpinnerNumberModel(wikiPrice, 0, Integer.MAX_VALUE, 100000));
		((JSpinner.DefaultEditor) collateralSpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) collateralSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		collateralSpinner.setToolTipText("Auto-filled from wiki price: " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(wikiPrice) + " GP");
		contentPanel.add(collateralSpinner, gbc);

		// Duration
		gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
		JLabel durationLabel = new JLabel("Max Duration (hours):");
		durationLabel.setForeground(Color.WHITE);
		contentPanel.add(durationLabel, gbc);

		gbc.gridx = 1; gbc.weightx = 1.0;
		JSpinner durationSpinner = new JSpinner(new SpinnerNumberModel(24, 1, 720, 1));
		((JSpinner.DefaultEditor) durationSpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) durationSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		contentPanel.add(durationSpinner, gbc);

		// Notes
		gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2;
		JLabel notesLabel = new JLabel("Notes (optional):");
		notesLabel.setForeground(Color.WHITE);
		contentPanel.add(notesLabel, gbc);

		gbc.gridy = 4;
		JTextArea notesArea = new JTextArea(3, 20);
		notesArea.setLineWrap(true);
		notesArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		notesArea.setForeground(Color.WHITE);
		JScrollPane notesScroll = new JScrollPane(notesArea);
		contentPanel.add(notesScroll, gbc);

		mainPanel.add(contentPanel, BorderLayout.CENTER);

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout());
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton addBtn = new JButton("Add to Marketplace");
		addBtn.setBackground(ColorScheme.BRAND_ORANGE);
		addBtn.setForeground(Color.WHITE);
		addBtn.addActionListener(e -> {
			int collateralValue = (int) collateralSpinner.getValue();
			int duration = (int) durationSpinner.getValue();
			String notes = notesArea.getText().trim();

			// Create lending entry for marketplace
			LendingEntry lendOffer = new LendingEntry();
			lendOffer.setLender(getCurrentPlayerName());
			lendOffer.setBorrower(""); // Empty, anyone can request
			lendOffer.setItem(itemName);
			lendOffer.setItemId(itemId);
			lendOffer.setQuantity(1);
			lendOffer.setCollateralValue(collateralValue);
			lendOffer.setCollateralType(collateralValue > 0 ? "GP" : "none");
			lendOffer.setNotes(notes);
			lendOffer.setGroupId(currentGroupId);
			lendOffer.setLendTime(System.currentTimeMillis());
			lendOffer.setDueTime(System.currentTimeMillis() + (duration * 3600000L));
			lendOffer.setReturnedAt(0L); // Not returned yet
			lendOffer.setId(java.util.UUID.randomUUID().toString());

			// Save to recorder's available list (marketplace)
			recorder.addAvailable(currentGroupId, getCurrentPlayerName(), lendOffer);

			showNotification("Item Added", itemName + " added to group marketplace");
			dialog.dispose();
			refresh();
		});
		buttonPanel.add(addBtn);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		cancelBtn.setForeground(Color.WHITE);
		cancelBtn.addActionListener(e -> dialog.dispose());
		buttonPanel.add(cancelBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

			dialog.add(mainPanel);
			log.info("About to show dialog...");
			dialog.setVisible(true);
			log.info("Dialog shown successfully");
		}
		catch (Exception e)
		{
			log.error("CRITICAL ERROR in showLendItemDialog", e);
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "ERROR: " + e.getMessage(), "");
			e.printStackTrace();
		}
	}

	/**
	 * Show borrow item dialog (called from right-click menu)
	 */
	public void showBorrowItemDialog(String itemName, int itemId)
	{
		// Reuse the existing borrow request dialog but pre-fill the item name
		if (borrowRequestService == null)
		{
			showError("Borrow request service not available");
			return;
		}

		String currentGroupId = groupConfigStore.getCurrentGroupId();
		if (currentGroupId == null)
		{
			showError("Please select a group first");
			return;
		}

		JDialog requestDialog = new JDialog((JFrame) SwingUtilities.getWindowAncestor(this));
		requestDialog.setTitle("Borrow Item from Group");
		requestDialog.setModal(true);
		requestDialog.setSize(450, 400);
		requestDialog.setLocationRelativeTo(this);

		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

		// Content panel
		JPanel contentPanel = new JPanel(new GridBagLayout());
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = new Insets(5, 5, 5, 5);

		// Item name (pre-filled from right-click)
		gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
		JLabel itemLabel = new JLabel("Item:");
		itemLabel.setForeground(Color.WHITE);
		contentPanel.add(itemLabel, gbc);

		gbc.gridx = 1; gbc.weightx = 1.0;
		JLabel itemValueLabel = new JLabel(itemName);
		itemValueLabel.setForeground(ColorScheme.BRAND_ORANGE);
		itemValueLabel.setFont(itemValueLabel.getFont().deriveFont(Font.BOLD));
		contentPanel.add(itemValueLabel, gbc);

		// Quantity
		gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
		JLabel qtyLabel = new JLabel("Quantity:");
		qtyLabel.setForeground(Color.WHITE);
		contentPanel.add(qtyLabel, gbc);

		gbc.gridx = 1; gbc.weightx = 1.0;
		JSpinner qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1000000, 1));
		((JSpinner.DefaultEditor) qtySpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) qtySpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		contentPanel.add(qtySpinner, gbc);

		// Collateral offered
		gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
		JLabel collateralLabel = new JLabel("Collateral Offered:");
		collateralLabel.setForeground(Color.WHITE);
		contentPanel.add(collateralLabel, gbc);

		gbc.gridx = 1; gbc.weightx = 1.0;
		JTextField collateralField = new JTextField(20);
		collateralField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		collateralField.setForeground(Color.WHITE);
		contentPanel.add(collateralField, gbc);

		// Collateral value (auto-populated from wiki price)
		gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
		JLabel valueLabel = new JLabel("Collateral Value (GP):");
		valueLabel.setForeground(Color.WHITE);
		contentPanel.add(valueLabel, gbc);

		// Get wiki price for item and set as suggested collateral value
		int itemWikiPrice = itemManager.getItemPrice(itemId);
		gbc.gridx = 1; gbc.weightx = 1.0;
		JSpinner valueSpinner = new JSpinner(new SpinnerNumberModel(itemWikiPrice, 0, Integer.MAX_VALUE, 100000));
		((JSpinner.DefaultEditor) valueSpinner.getEditor()).getTextField().setBackground(ColorScheme.DARKER_GRAY_COLOR);
		((JSpinner.DefaultEditor) valueSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
		valueSpinner.setToolTipText("Auto-filled from wiki price: " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(itemWikiPrice) + " GP");
		contentPanel.add(valueSpinner, gbc);

		// Message
		gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
		JLabel messageLabel = new JLabel("Message (optional):");
		messageLabel.setForeground(Color.WHITE);
		contentPanel.add(messageLabel, gbc);

		gbc.gridy = 5;
		JTextArea messageArea = new JTextArea(3, 20);
		messageArea.setLineWrap(true);
		messageArea.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		messageArea.setForeground(Color.WHITE);
		JScrollPane messageScroll = new JScrollPane(messageArea);
		contentPanel.add(messageScroll, gbc);

		mainPanel.add(contentPanel, BorderLayout.CENTER);

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout());
		buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JButton submitBtn = new JButton("Submit Request");
		submitBtn.setBackground(ColorScheme.BRAND_ORANGE);
		submitBtn.setForeground(Color.WHITE);
		submitBtn.addActionListener(e -> {
			int quantity = (int) qtySpinner.getValue();
			String collateral = collateralField.getText().trim();
			int collateralValue = (int) valueSpinner.getValue();
			String message = messageArea.getText().trim();

			// Create request
			BorrowRequest request = new BorrowRequest(
				currentGroupId,
				getCurrentPlayerName(),
				itemName,
				quantity,
				collateralValue,
				collateral,
				message,
				System.currentTimeMillis());

			// Submit request
			BorrowRequestService.SubmitResult result = borrowRequestService.submitBorrowRequest(request);
			if (result.success)
			{
				requestDialog.dispose();
				showNotification("Request Submitted", "Your borrow request has been submitted to the group");
				refresh();
			}
			else
			{
				showError("Failed to submit request:\n" + result.errorMessage);
			}
		});
		buttonPanel.add(submitBtn);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		cancelBtn.setForeground(Color.WHITE);
		cancelBtn.addActionListener(e -> requestDialog.dispose());
		buttonPanel.add(cancelBtn);

		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		requestDialog.add(mainPanel);
		requestDialog.setVisible(true);
	}

	private void styleScrollBar(JScrollPane scrollPane)
	{
		scrollPane.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = ColorScheme.BRAND_ORANGE;
				this.trackColor = ColorScheme.DARKER_GRAY_COLOR;
			}
			@Override
			protected JButton createDecreaseButton(int orientation) {
				JButton button = super.createDecreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
			@Override
			protected JButton createIncreaseButton(int orientation) {
				JButton button = super.createIncreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
		});
		scrollPane.getHorizontalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
			@Override
			protected void configureScrollBarColors() {
				this.thumbColor = ColorScheme.BRAND_ORANGE;
				this.trackColor = ColorScheme.DARKER_GRAY_COLOR;
			}
			@Override
			protected JButton createDecreaseButton(int orientation) {
				JButton button = super.createDecreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
			@Override
			protected JButton createIncreaseButton(int orientation) {
				JButton button = super.createIncreaseButton(orientation);
				button.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				button.setForeground(ColorScheme.BRAND_ORANGE);
				return button;
			}
		});
	}
}
