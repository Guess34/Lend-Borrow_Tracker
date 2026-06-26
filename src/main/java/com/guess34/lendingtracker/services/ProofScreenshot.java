package com.guess34.lendingtracker.services;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.LinkBrowser;
import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.model.LendingEntry;

/**
 * ProofScreenshot - Captures and saves proof screenshots for lending transactions
 * Supports capturing during trade screens and after completion
 */
@Slf4j
@Singleton
public class ProofScreenshot
{
	@Inject
	private LendingTrackerConfig config;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
	private static final SimpleDateFormat DISPLAY_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	// Base directory for all screenshots (inside .runelite per Plugin Hub rules)
	private static final Path BASE_DIR = Paths.get(System.getProperty("user.home"), ".runelite", "lending-tracker", "proof");

	/**
	 * Get the screenshot directory for a specific user and group
	 */
	private Path getScreenshotDirectory(String username, String groupName)
	{
		// Clean up names for file system safety
		String safeUsername = sanitizeFilename(username != null ? username : "unknown");
		String safeGroupName = sanitizeFilename(groupName != null ? groupName : "default");

		return BASE_DIR.resolve(safeUsername).resolve(safeGroupName);
	}

	/**
	 * Open the screenshot folder in the system file explorer
	 */
	public void openScreenshotFolder(String username, String groupName)
	{
		try
		{
			Path dir = getScreenshotDirectory(username, groupName);
			Files.createDirectories(dir);

			LinkBrowser.open(dir.toString());
		}
		catch (Exception e)
		{
			log.error("Failed to open screenshot folder: {}", e.getMessage());
		}
	}

	/**
	 * Capture screenshot during second accept screen
	 */
	public void captureSecondAcceptScreen(String username, String groupName, String tradePartner,
										   String eventType, LendingEntry entry)
	{
		captureTradeScreenshot(username, groupName, tradePartner, eventType, "second_accept", entry);
	}

	/**
	 * Capture screenshot after trade completion
	 */
	public void captureTradeCompletion(String username, String groupName, String tradePartner,
										String eventType, LendingEntry entry)
	{
		captureTradeScreenshot(username, groupName, tradePartner, eventType, "completed", entry);
	}

	/**
	 * Capture screenshot for return transaction
	 */
	public void captureReturnScreenshot(String username, String groupName, String returnedBy,
										 String phase, LendingEntry entry)
	{
		captureTradeScreenshot(username, groupName, returnedBy, "RETURN", phase, entry);
	}

	// Capture the game canvas with DrawManager, then overlay + save off-thread
	private void captureTradeScreenshot(String username, String groupName, String tradePartner,
										 String eventType, String phase, LendingEntry entry)
	{
		drawManager.requestNextFrameListener(image ->
			executor.submit(() ->
				saveScreenshot(image, username, groupName, tradePartner, eventType, phase, entry)));
	}

	// Overlay + write the PNG, off the client thread
	private void saveScreenshot(Image image, String username, String groupName, String tradePartner,
								 String eventType, String phase, LendingEntry entry)
	{
		try
		{
			BufferedImage screenshot = toBufferedImage(image);
			if (screenshot == null)
			{
				log.warn("Failed to capture game frame");
				return;
			}

			// Add overlay if enabled
			if (config != null && config.screenshotIncludeOverlay())
			{
				screenshot = addOverlayText(screenshot, eventType, phase, tradePartner, entry);
			}

			// Build filename: eventType_phase_partner_timestamp.png
			String timestamp = DATE_FORMAT.format(Date.from(Instant.now()));
			String safePartner = sanitizeFilename(tradePartner != null ? tradePartner : "unknown");
			String filename = String.format("%s_%s_%s_%s.png",
				eventType.toLowerCase(),
				phase,
				safePartner,
				timestamp);

			// Save to user/group specific directory
			Path outDir = getScreenshotDirectory(username, groupName);
			Files.createDirectories(outDir);

			File outFile = outDir.resolve(filename).toFile();
			ImageIO.write(screenshot, "png", outFile);
		}
		catch (Exception e)
		{
			log.error("Failed to save proof screenshot: {}", e.getMessage());
		}
	}

	private BufferedImage toBufferedImage(Image image)
	{
		if (image == null)
		{
			return null;
		}
		BufferedImage buffered = new BufferedImage(
			image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = buffered.getGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return buffered;
	}

	/**
	 * Add overlay text with trade details
	 */
	private BufferedImage addOverlayText(BufferedImage img, String eventType, String phase,
										  String tradePartner, LendingEntry entry)
	{
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			int boxWidth = Math.min(img.getWidth() - 20, 450);
			int boxHeight = 180;
			int boxX = 10;
			int boxY = 10;

			// Semi-transparent background
			g.setColor(new Color(0, 0, 0, 200));
			g.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 12, 12);

			// Border
			g.setColor(new Color(255, 152, 0)); // Orange border
			g.setStroke(new BasicStroke(2));
			g.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 12, 12);

			int y = boxY + 24;
			int x = boxX + 12;

			// Title
			g.setColor(new Color(255, 152, 0));
			g.setFont(new Font("Dialog", Font.BOLD, 16));
			g.drawString("LendingTracker Proof", x, y);
			y += 24;

			// Event info
			g.setColor(Color.WHITE);
			g.setFont(new Font("Dialog", Font.PLAIN, 13));

			String timestamp = DISPLAY_DATE_FORMAT.format(new Date());
			g.drawString("Time: " + timestamp, x, y);
			y += 18;

			if (eventType != null)
			{
				String phaseLabel = phase != null ? " (" + formatPhase(phase) + ")" : "";
				g.drawString("Event: " + eventType + phaseLabel, x, y);
				y += 18;
			}

			if (tradePartner != null)
			{
				g.drawString("Trade Partner: " + tradePartner, x, y);
				y += 18;
			}

			if (entry != null)
			{
				g.drawString("Lender: " + safe(entry.getLender()), x, y);
				y += 18;
				g.drawString("Borrower: " + safe(entry.getBorrower()), x, y);
				y += 18;
				g.drawString("Item: " + safe(entry.getItem()) + " x" + entry.getQuantity(), x, y);
				y += 18;

				// Collateral info
				String col = formatCollateral(entry);
				g.drawString("Collateral: " + col, x, y);
			}

			return img;
		}
		finally
		{
			g.dispose();
		}
	}

	/**
	 * Format the phase for display
	 */
	private String formatPhase(String phase)
	{
		if (phase == null) return "";
		switch (phase.toLowerCase())
		{
			case "second_accept": return "Second Accept Screen";
			case "completed": return "Trade Completed";
			default: return phase;
		}
	}

	/**
	 * Format collateral info for display
	 */
	private String formatCollateral(LendingEntry entry)
	{
		if (entry.getCollateralValue() != null && entry.getCollateralValue() > 0)
		{
			return formatGp(entry.getCollateralValue()) + " GP";
		}
		else if (entry.getCollateralItems() != null && !entry.getCollateralItems().isEmpty())
		{
			return "Items: " + entry.getCollateralItems();
		}
		else if (entry.isAgreedNoCollateral())
		{
			return "None (agreed)";
		}
		return "None";
	}

	/**
	 * Format GP value with commas
	 */
	private String formatGp(long value)
	{
		return String.format("%,d", value);
	}

	/**
	 * Null-safe string
	 */
	private static String safe(String s)
	{
		return s == null ? "" : s;
	}

	/**
	 * Sanitize filename for file system safety
	 */
	private String sanitizeFilename(String name)
	{
		if (name == null) return "unknown";
		// Replace invalid characters with underscore
		return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
	}

}
