package net.runelite.client.plugins.lendingtracker.services;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.lendingtracker.LendingTrackerConfig;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;

/**
 * ProofScreenshot - Captures and saves proof screenshots for lending transactions
 * Supports capturing during trade screens and after completion
 */
@Slf4j
@Singleton
public class ProofScreenshot
{
	private final Client client;
	private final ItemManager itemManager;

	@Inject
	private LendingTrackerConfig config;

	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
	private static final SimpleDateFormat DISPLAY_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	// Base directory for all screenshots
	private static final Path BASE_DIR = Paths.get(System.getProperty("user.home"), "LendingTracker", "proof");

	@Inject
	public ProofScreenshot(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	/**
	 * Get the screenshot directory for a specific user and group
	 */
	public Path getScreenshotDirectory(String username, String groupName)
	{
		// Clean up names for file system safety
		String safeUsername = sanitizeFilename(username != null ? username : "unknown");
		String safeGroupName = sanitizeFilename(groupName != null ? groupName : "default");

		return BASE_DIR.resolve(safeUsername).resolve(safeGroupName);
	}

	/**
	 * Get the base screenshot directory
	 */
	public Path getBaseScreenshotDirectory()
	{
		return BASE_DIR;
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

			if (Desktop.isDesktopSupported())
			{
				Desktop.getDesktop().open(dir.toFile());
				log.info("Opened screenshot folder: {}", dir);
			}
			else
			{
				log.warn("Desktop not supported - cannot open folder");
			}
		}
		catch (Exception e)
		{
			log.error("Failed to open screenshot folder: {}", e.getMessage());
		}
	}

	/**
	 * Capture screenshot during second accept screen
	 */
	public File captureSecondAcceptScreen(String username, String groupName, String tradePartner,
										   String eventType, LendingEntry entry)
	{
		String phase = "second_accept";
		return captureTradeScreenshot(username, groupName, tradePartner, eventType, phase, entry);
	}

	/**
	 * Capture screenshot after trade completion
	 */
	public File captureTradeCompletion(String username, String groupName, String tradePartner,
										String eventType, LendingEntry entry)
	{
		String phase = "completed";
		return captureTradeScreenshot(username, groupName, tradePartner, eventType, phase, entry);
	}

	/**
	 * Capture screenshot for return transaction
	 */
	public File captureReturnScreenshot(String username, String groupName, String returnedBy,
										 String phase, LendingEntry entry)
	{
		return captureTradeScreenshot(username, groupName, returnedBy, "RETURN", phase, entry);
	}

	/**
	 * Internal method to capture trade screenshots with full context
	 */
	private File captureTradeScreenshot(String username, String groupName, String tradePartner,
										 String eventType, String phase, LendingEntry entry)
	{
		try
		{
			BufferedImage screenshot = captureGameWindow();
			if (screenshot == null)
			{
				log.warn("Failed to capture game window");
				return null;
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

			log.info("Saved proof screenshot: {}", outFile.getAbsolutePath());
			return outFile;
		}
		catch (Exception e)
		{
			log.error("Failed to capture proof screenshot: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Legacy method for backward compatibility
	 */
	public File captureToFile(String eventType, String details, LendingEntry entry)
	{
		try
		{
			BufferedImage screenshot = captureGameWindow();
			if (screenshot == null) return null;

			screenshot = addLegacyOverlayText(screenshot, eventType, details, entry);

			String timestamp = DATE_FORMAT.format(Date.from(Instant.now()));
			Files.createDirectories(BASE_DIR);
			File out = BASE_DIR.resolve("proof_" + timestamp + ".png").toFile();
			ImageIO.write(screenshot, "png", out);
			return out;
		}
		catch (Exception e)
		{
			log.error("Failed to capture proof screenshot: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Capture the game window
	 */
	private BufferedImage captureGameWindow()
	{
		try
		{
			Rectangle rect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
			return new Robot().createScreenCapture(rect);
		}
		catch (Exception e)
		{
			log.error("Failed to capture screen: {}", e.getMessage());
			return null;
		}
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
	 * Legacy overlay format for backward compatibility
	 */
	private BufferedImage addLegacyOverlayText(BufferedImage img, String eventType, String details, LendingEntry entry)
	{
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			int y = 24;

			g.setColor(new Color(0, 0, 0, 170));
			g.fillRoundRect(10, 10, Math.min(img.getWidth() - 20, 900), 160, 12, 12);

			g.setColor(Color.WHITE);
			g.setFont(new Font("Dialog", Font.BOLD, 16));
			g.drawString("LendingTracker Proof", 20, y);
			y += 22;

			g.setFont(new Font("Dialog", Font.PLAIN, 14));
			if (eventType != null) { g.drawString("Event: " + eventType, 20, y); y += 18; }
			if (details != null)    { g.drawString("Details: " + details, 20, y); y += 18; }

			if (entry != null)
			{
				g.drawString("Borrower: " + safe(entry.getBorrower()), 20, y); y += 18;
				g.drawString("Lender: " + safe(entry.getLender()), 20, y);     y += 18;
				g.drawString("Item: " + safe(entry.getItem()) + " x" + entry.getQuantity(), 20, y); y += 18;
				g.drawString("Collateral: " + formatCollateral(entry), 20, y);
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

	/**
	 * Capture to bytes for Discord/webhook upload
	 */
	public byte[] captureToBytes(String eventType, String details, LendingEntry entry)
	{
		try
		{
			BufferedImage screenshot = captureGameWindow();
			if (screenshot == null) return null;

			screenshot = addLegacyOverlayText(screenshot, eventType, details, entry);

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ImageIO.write(screenshot, "png", baos);
			return baos.toByteArray();
		}
		catch (Exception e)
		{
			log.error("Failed to capture screenshot bytes: {}", e.getMessage());
			return null;
		}
	}
}
