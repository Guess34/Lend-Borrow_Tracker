package net.runelite.client.plugins.lendingtracker.overlay;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.plugins.lendingtracker.LendingTrackerConfig;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.model.RiskLevel;
import net.runelite.client.plugins.lendingtracker.services.Recorder;
import net.runelite.client.plugins.lendingtracker.services.RiskAnalyzer;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import java.awt.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

public class LendingSafetyOverlay extends Overlay {
    private static final Color TRUSTED_COLOR = new Color(0, 255, 0, 150);   // Green for trusted players (low risk)
    private static final Color CAUTION_COLOR = new Color(255, 255, 0, 150);   // Yellow for moderate risk 
    private static final Color DANGER_COLOR = new Color(255, 0, 0, 150);      // Red for high/critical risk
    private static final Color OVERDUE_COLOR = new Color(255, 100, 0, 200);   // Orange for overdue items
    
    private final Client client;
    private final LendingTrackerConfig config;
    private final Recorder recorder;
    private final RiskAnalyzer riskAnalyzer;
    private final PanelComponent panelComponent = new PanelComponent();
    
    @Inject
    public LendingSafetyOverlay(Client client, LendingTrackerConfig config, 
                                Recorder recorder, RiskAnalyzer riskAnalyzer) {
        this.client = client;
        this.config = config;
        this.recorder = recorder;
        this.riskAnalyzer = riskAnalyzer;
        
        setPosition(OverlayPosition.TOP_RIGHT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }
    
    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOverlay()) {
            return null;
        }
        
        // Render player risk indicators
        renderPlayerRiskIndicators(graphics);
        
        // Render overdue warnings
        renderOverdueWarnings(graphics);
        
        // Render summary panel if configured
        if (config.showSummaryPanel()) {
            return renderSummaryPanel(graphics);
        }
        
        return null;
    }
    
    private void renderPlayerRiskIndicators(Graphics2D graphics) {
        if (!config.showRiskIndicators()) {
            return;
        }
        
        // Get all players in view
        List<Player> players = client.getPlayers();
        
        for (Player player : players) {
            if (player == null || player == client.getLocalPlayer()) {
                continue;
            }
            
            String playerName = player.getName();
            if (playerName == null) {
                continue;
            }
            
            // Check if we have active loans with this player
            List<LendingEntry> playerLoans = recorder.getEntriesForPlayer(playerName);
            if (!playerLoans.isEmpty()) {
                // Get risk level
                int riskLevel = riskAnalyzer.analyzePlayer(playerName);
                Color riskColor = getRiskColor(riskLevel);
                
                // Draw indicator above player
                Point playerPoint = player.getCanvasTextLocation(graphics, player.getName(), 100);
                if (playerPoint != null) {
                    // Draw risk indicator
                    graphics.setColor(riskColor);
                    graphics.fillOval(playerPoint.getX() - 5, playerPoint.getY() - 20, 10, 10);
                    
                    // Draw loan count
                    graphics.setColor(Color.WHITE);
                    graphics.setFont(new Font("Arial", Font.BOLD, 12));
                    graphics.drawString(String.valueOf(playerLoans.size()), 
                                      playerPoint.getX() - 3, playerPoint.getY() - 12);
                    
                    // Draw total value if high
                    long totalValue = playerLoans.stream()
                        .mapToLong(LendingEntry::getValue)
                        .sum();
                    
                    if (totalValue > config.highValueThreshold()) {
                        graphics.setColor(Color.YELLOW);
                        graphics.drawString(QuantityFormatter.quantityToStackSize(totalValue) + " gp",
                                           playerPoint.getX() - 20, playerPoint.getY() - 25);
                    }
                }
            }
        }
    }
    
    private void renderOverdueWarnings(Graphics2D graphics) {
        if (!config.showOverdueWarnings()) {
            return;
        }
        
        List<LendingEntry> overdueEntries = recorder.getOverdueEntries();
        if (overdueEntries.isEmpty()) {
            return;
        }
        
        // Check if any overdue players are visible
        List<Player> players = client.getPlayers();
        
        for (Player player : players) {
            if (player == null || player.getName() == null) {
                continue;
            }
            
            List<LendingEntry> playerOverdue = overdueEntries.stream()
                .filter(entry -> entry.getPlayerName().equalsIgnoreCase(player.getName()))
                .collect(Collectors.toList());  // Fixed: Changed from .toList()
            
            if (!playerOverdue.isEmpty()) {
                Point playerPoint = player.getCanvasTextLocation(graphics, player.getName(), 150);
                if (playerPoint != null) {
                    // Draw overdue warning
                    graphics.setColor(OVERDUE_COLOR);
                    graphics.setFont(new Font("Arial", Font.BOLD, 14));
                    
                    long daysOverdue = ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(playerOverdue.get(0).getDueDate()),
                        Instant.now()
                    );
                    
                    String warningText = String.format("⚠ OVERDUE %d days", daysOverdue);
                    graphics.drawString(warningText, playerPoint.getX() - 40, playerPoint.getY() + 20);
                    
                    // Draw items list
                    graphics.setFont(new Font("Arial", Font.PLAIN, 11));
                    int yOffset = 35;
                    for (LendingEntry entry : playerOverdue) {
                        graphics.drawString("• " + entry.getItemName(), 
                                          playerPoint.getX() - 30, playerPoint.getY() + yOffset);
                        yOffset += 15;
                    }
                }
            }
        }
    }
    
    private Dimension renderSummaryPanel(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        
        // Title
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Lending Tracker")
            .color(Color.YELLOW)
            .build());
        
        // Active loans count
        List<LendingEntry> activeEntries = recorder.getActiveEntries();
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Active Loans:")
            .right(String.valueOf(activeEntries.size()))
            .build());
        
        // Total value
        long totalValue = recorder.getTotalValueLent();
        panelComponent.getChildren().add(LineComponent.builder()
            .left("Total Value:")
            .right(QuantityFormatter.quantityToStackSize(totalValue) + " gp")
            .build());
        
        // Overdue count
        List<LendingEntry> overdueEntries = recorder.getOverdueEntries();
        if (!overdueEntries.isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Overdue:")
                .leftColor(DANGER_COLOR)
                .right(String.valueOf(overdueEntries.size()))
                .rightColor(DANGER_COLOR)
                .build());
        }
        
        // High risk loans
        List<LendingEntry> highRiskLoans = activeEntries.stream()
            .filter(entry -> riskAnalyzer.analyzePlayer(entry.getPlayerName()) >= RiskLevel.HIGH.getValue())
            .collect(Collectors.toList());  // Fixed: Changed from .toList()
        
        if (!highRiskLoans.isEmpty()) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("High Risk:")
                .leftColor(CAUTION_COLOR)
                .right(String.valueOf(highRiskLoans.size()))
                .rightColor(CAUTION_COLOR)
                .build());
        }
        
        // Recent activity indicator
        long recentActivityCount = activeEntries.stream()
            .filter(entry -> {
                long hoursSinceLoan = ChronoUnit.HOURS.between(
                    Instant.ofEpochMilli(entry.getLendDate()),
                    Instant.now()
                );
                return hoursSinceLoan <= 1;
            })
            .count();
        
        if (recentActivityCount > 0) {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("Recent (1h):")
                .leftColor(TRUSTED_COLOR)
                .right(String.valueOf(recentActivityCount))
                .rightColor(TRUSTED_COLOR)
                .build());
        }
        
        return panelComponent.render(graphics);
    }
    
    private Color getRiskColor(int riskLevel) {
        // Lower numbers = better trust/lower risk = green
        // Higher numbers = worse trust/higher risk = red
        if (riskLevel >= RiskLevel.CRITICAL.getValue()) { // 4 = Critical Risk
            return DANGER_COLOR;    // Red
        } else if (riskLevel >= RiskLevel.HIGH.getValue()) { // 3 = High Risk
            return CAUTION_COLOR;   // Yellow  
        } else { // 0-2 = Excellent to Medium Trust
            return TRUSTED_COLOR;   // Green
        }
    }
}