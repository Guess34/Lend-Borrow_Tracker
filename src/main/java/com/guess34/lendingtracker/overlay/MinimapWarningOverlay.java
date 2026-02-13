package com.guess34.lendingtracker.overlay;

import net.runelite.api.Client;
import com.guess34.lendingtracker.LendingTrackerPlugin;
import com.guess34.lendingtracker.model.LendingEntry;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.PanelComponent;

import javax.inject.Inject;
import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Subtle warning overlay that appears near the minimap
 * Non-blocking popups for wilderness/trade/GE/drop warnings
 */
public class MinimapWarningOverlay extends Overlay {
    
    private static final int WARNING_DISPLAY_TIME = 8000; // 8 seconds
    private static final int WARNING_FADE_TIME = 2000; // 2 seconds fade
    private static final Color WARNING_BACKGROUND = new Color(139, 0, 0, 200); // Dark red with transparency
    private static final Color CAUTION_BACKGROUND = new Color(255, 165, 0, 200); // Orange with transparency
    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Font WARNING_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font DETAIL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    
    private final Client client;
    private final LendingTrackerPlugin plugin;
    private final List<WarningPopup> activeWarnings = new ArrayList<>();
    
    @Inject
    public MinimapWarningOverlay(Client client, LendingTrackerPlugin plugin) {
        this.client = client;
        this.plugin = plugin;
        
        setPosition(OverlayPosition.TOP_RIGHT);
        setPriority(OverlayPriority.HIGH);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }
    
    @Override
    public Dimension render(Graphics2D graphics) {
        long currentTime = Instant.now().toEpochMilli();
        
        // Clean up expired warnings
        Iterator<WarningPopup> iterator = activeWarnings.iterator();
        while (iterator.hasNext()) {
            WarningPopup warning = iterator.next();
            if (currentTime - warning.startTime > WARNING_DISPLAY_TIME + WARNING_FADE_TIME) {
                iterator.remove();
            }
        }
        
        // Render active warnings
        int yOffset = 0;
        for (WarningPopup warning : activeWarnings) {
            renderWarningPopup(graphics, warning, yOffset, currentTime);
            yOffset += warning.getHeight() + 5; // 5px spacing between warnings
        }
        
        return null;
    }
    
    private void renderWarningPopup(Graphics2D graphics, WarningPopup warning, int yOffset, long currentTime) {
        long elapsed = currentTime - warning.startTime;
        
        // Calculate alpha for fade effect
        float alpha = 1.0f;
        if (elapsed > WARNING_DISPLAY_TIME) {
            long fadeElapsed = elapsed - WARNING_DISPLAY_TIME;
            alpha = Math.max(0.0f, 1.0f - (float) fadeElapsed / WARNING_FADE_TIME);
        }
        
        // Set composite for transparency
        Composite originalComposite = graphics.getComposite();
        graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        
        // Position near minimap (offset from top-right)
        int x = -warning.getWidth() - 10; // 10px margin from right edge
        int y = 10 + yOffset; // 10px from top + offset for multiple warnings
        
        // Draw background with rounded corners
        graphics.setColor(warning.backgroundColor);
        graphics.fillRoundRect(x, y, warning.getWidth(), warning.getHeight(), 8, 8);
        
        // Draw border
        graphics.setColor(warning.backgroundColor.darker());
        graphics.setStroke(new BasicStroke(2));
        graphics.drawRoundRect(x, y, warning.getWidth(), warning.getHeight(), 8, 8);
        
        // Draw warning icon
        graphics.setColor(TEXT_COLOR);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        graphics.drawString("⚠", x + 8, y + 20);
        
        // Draw warning text
        graphics.setFont(WARNING_FONT);
        graphics.setColor(TEXT_COLOR);
        graphics.drawString(warning.title, x + 30, y + 20);
        
        // Draw details
        if (warning.details != null && !warning.details.isEmpty()) {
            graphics.setFont(DETAIL_FONT);
            String[] lines = warning.details.split("\n");
            for (int i = 0; i < Math.min(lines.length, 3); i++) { // Max 3 detail lines
                graphics.drawString(lines[i], x + 30, y + 35 + (i * 12));
            }
        }
        
        // Draw action buttons if interactive
        if (warning.isInteractive) {
            drawActionButtons(graphics, warning, x, y, alpha);
        }
        
        // Restore composite
        graphics.setComposite(originalComposite);
    }
    
    private void drawActionButtons(Graphics2D graphics, WarningPopup warning, int x, int y, float alpha) {
        int buttonY = y + warning.getHeight() - 25;
        int buttonWidth = 60;
        int buttonHeight = 20;
        
        // Proceed button (right side)
        int proceedX = x + warning.getWidth() - buttonWidth - 8;
        Color proceedColor = new Color(0, 128, 0, (int)(200 * alpha)); // Green with alpha
        graphics.setColor(proceedColor);
        graphics.fillRoundRect(proceedX, buttonY, buttonWidth, buttonHeight, 4, 4);
        graphics.setColor(TEXT_COLOR);
        graphics.setFont(DETAIL_FONT);
        graphics.drawString("Proceed", proceedX + 15, buttonY + 14);
        
        // Cancel button (left of proceed)
        int cancelX = proceedX - buttonWidth - 5;
        Color cancelColor = new Color(128, 0, 0, (int)(200 * alpha)); // Red with alpha
        graphics.setColor(cancelColor);
        graphics.fillRoundRect(cancelX, buttonY, buttonWidth, buttonHeight, 4, 4);
        graphics.setColor(TEXT_COLOR);
        graphics.drawString("Cancel", cancelX + 18, buttonY + 14);
        
        // Store button bounds for click detection
        warning.proceedBounds = new Rectangle(proceedX, buttonY, buttonWidth, buttonHeight);
        warning.cancelBounds = new Rectangle(cancelX, buttonY, buttonWidth, buttonHeight);
    }
    
    /**
     * Show a wilderness warning for borrowed items
     */
    public void showWildernessWarning(List<LendingEntry> borrowedItems) {
        String title = "WILDERNESS RISK!";
        StringBuilder details = new StringBuilder();
        details.append(borrowedItems.size()).append(" borrowed item(s) at risk\n");
        
        long totalValue = borrowedItems.stream().mapToLong(LendingEntry::getValue).sum();
        if (totalValue > 0) {
            details.append("Total value: ").append(formatGP(totalValue)).append("\n");
        }
        
        details.append("Click to acknowledge risk");
        
        WarningPopup popup = new WarningPopup(
            title, 
            details.toString(), 
            WARNING_BACKGROUND, 
            true, // interactive
            () -> plugin.handleWildernessAcknowledgment(borrowedItems),
            () -> plugin.handleWildernessCancel(borrowedItems)
        );
        
        activeWarnings.add(popup);
        
        // Log the warning
        plugin.logWarning("WILDERNESS_POPUP", 
            "Minimap warning shown for " + borrowedItems.size() + " borrowed items", 
            "All lenders");
    }
    
    /**
     * Show a trade warning for borrowed items
     */
    public void showTradeWarning(List<LendingEntry> borrowedItems, String tradePartner) {
        String title = "TRADE WARNING!";
        StringBuilder details = new StringBuilder();
        details.append("Trading with ").append(tradePartner).append("\n");
        details.append(borrowedItems.size()).append(" borrowed item(s) involved\n");
        details.append("Lenders may need notification");
        
        WarningPopup popup = new WarningPopup(
            title, 
            details.toString(), 
            CAUTION_BACKGROUND, 
            true, // interactive
            () -> plugin.handleTradeAcknowledgment(borrowedItems, tradePartner),
            () -> plugin.handleTradeCancel(borrowedItems, tradePartner)
        );
        
        activeWarnings.add(popup);
        
        // Log the warning
        plugin.logWarning("TRADE_POPUP", 
            "Minimap warning shown for trade with " + tradePartner + " involving " + borrowedItems.size() + " borrowed items", 
            tradePartner);
    }
    
    /**
     * Show a Grand Exchange warning for borrowed items
     */
    public void showGrandExchangeWarning(List<LendingEntry> borrowedItems) {
        String title = "GE WARNING!";
        StringBuilder details = new StringBuilder();
        details.append("Using GE with borrowed items\n");
        details.append(borrowedItems.size()).append(" item(s) may be affected\n");
        details.append("Notify lenders first?");
        
        WarningPopup popup = new WarningPopup(
            title, 
            details.toString(), 
            CAUTION_BACKGROUND, 
            true, // interactive
            () -> plugin.handleGEAcknowledgment(borrowedItems),
            () -> plugin.handleGECancel(borrowedItems)
        );
        
        activeWarnings.add(popup);
        
        // Log the warning
        plugin.logWarning("GE_POPUP", 
            "Minimap warning shown for GE usage with " + borrowedItems.size() + " borrowed items", 
            "All lenders");
    }
    
    /**
     * Show a drop warning for borrowed items
     */
    public void showDropWarning(List<LendingEntry> borrowedItems) {
        String title = "DROP WARNING!";
        StringBuilder details = new StringBuilder();
        details.append("Dropping borrowed items!\n");
        details.append(borrowedItems.size()).append(" item(s) will be lost\n");
        details.append("This may breach lending agreements");
        
        WarningPopup popup = new WarningPopup(
            title, 
            details.toString(), 
            WARNING_BACKGROUND, 
            true, // interactive
            () -> plugin.handleDropAcknowledgment(borrowedItems),
            () -> plugin.handleDropCancel(borrowedItems)
        );
        
        activeWarnings.add(popup);
        
        // Log the warning
        plugin.logWarning("DROP_POPUP", 
            "Minimap warning shown for dropping " + borrowedItems.size() + " borrowed items", 
            "All lenders");
    }
    
    /**
     * Show a simple non-interactive notification
     */
    public void showNotification(String title, String message, boolean isWarning) {
        WarningPopup popup = new WarningPopup(
            title, 
            message, 
            isWarning ? WARNING_BACKGROUND : CAUTION_BACKGROUND, 
            false, // not interactive
            null, 
            null
        );
        
        activeWarnings.add(popup);
    }
    
    private String formatGP(long value) {
        if (value >= 1000000) {
            return String.format("%.1fM GP", value / 1000000.0);
        } else if (value >= 1000) {
            return String.format("%.1fK GP", value / 1000.0);
        } else {
            return value + " GP";
        }
    }
    
    /**
     * Warning popup data class
     */
    private static class WarningPopup {
        final String title;
        final String details;
        final Color backgroundColor;
        final boolean isInteractive;
        final Runnable onProceed;
        final Runnable onCancel;
        final long startTime;
        
        Rectangle proceedBounds;
        Rectangle cancelBounds;
        
        public WarningPopup(String title, String details, Color backgroundColor, 
                           boolean isInteractive, Runnable onProceed, Runnable onCancel) {
            this.title = title;
            this.details = details;
            this.backgroundColor = backgroundColor;
            this.isInteractive = isInteractive;
            this.onProceed = onProceed;
            this.onCancel = onCancel;
            this.startTime = Instant.now().toEpochMilli();
        }
        
        public int getWidth() {
            return 280; // Fixed width for consistency
        }
        
        public int getHeight() {
            int baseHeight = 50; // Title and basic content
            if (details != null && !details.isEmpty()) {
                int lines = Math.min(3, details.split("\n").length);
                baseHeight += lines * 12;
            }
            if (isInteractive) {
                baseHeight += 30; // Space for buttons
            }
            return baseHeight;
        }
    }
    
    /**
     * Handle mouse clicks for interactive warnings
     */
    public boolean handleClick(Point clickPoint) {
        for (WarningPopup warning : activeWarnings) {
            if (!warning.isInteractive) continue;
            
            if (warning.proceedBounds != null && warning.proceedBounds.contains(clickPoint)) {
                if (warning.onProceed != null) {
                    warning.onProceed.run();
                }
                activeWarnings.remove(warning);
                return true;
            }
            
            if (warning.cancelBounds != null && warning.cancelBounds.contains(clickPoint)) {
                if (warning.onCancel != null) {
                    warning.onCancel.run();
                }
                activeWarnings.remove(warning);
                return true;
            }
        }
        return false;
    }
    
    /**
     * Clear all active warnings
     */
    public void clearAll() {
        activeWarnings.clear();
    }
}