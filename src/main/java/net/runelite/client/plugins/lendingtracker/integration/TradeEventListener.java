package net.runelite.client.plugins.lendingtracker.integration;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.lendingtracker.LendingTrackerConfig;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.model.LendingGroup;
import net.runelite.client.plugins.lendingtracker.services.core.LendingManager;
import net.runelite.client.plugins.lendingtracker.services.core.MarketplaceManager;
import net.runelite.client.plugins.lendingtracker.services.group.GroupConfigStore;
import net.runelite.client.plugins.lendingtracker.services.ProofScreenshot;
import net.runelite.client.plugins.lendingtracker.services.DiscordWebhookService;
import net.runelite.client.plugins.lendingtracker.services.BorrowRequestService;
import net.runelite.client.plugins.lendingtracker.model.BorrowRequest;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TradeEventListener - Detects and processes lending/borrowing trades
 * Phase 2: Extracted from main plugin for clean separation of concerns
 * Now includes screenshot capture at second accept and completion
 */
@Slf4j
@Singleton
public class TradeEventListener
{
	// First trade screen (offer items)
	private static final int TRADE_MAIN_INTERFACE_ID = 335;
	// Second trade screen (confirmation)
	private static final int TRADE_CONFIRM_INTERFACE_ID = 334;
	private static final int TRADE_PARTNER_WIDGET_ID = 31;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private LendingTrackerConfig config;

	@Inject
	private LendingManager lendingManager;

	@Inject
	private MarketplaceManager marketplaceManager;

	@Inject
	private GroupConfigStore groupConfigStore;

	@Inject
	private ProofScreenshot proofScreenshot;

	@Inject
	private DiscordWebhookService discordWebhookService;

	@Inject
	private BorrowRequestService borrowRequestService;

	// Trade tracking state
	private volatile String currentTradePartner = null;
	private final Map<Integer, Integer> tradeItems = new ConcurrentHashMap<>(); // itemId -> quantity
	private volatile boolean tradeAccepted = false;
	private volatile boolean inSecondScreen = false;
	private volatile boolean secondScreenCaptured = false;
	private volatile LendingEntry pendingEntry = null; // Entry for screenshot context

	/**
	 * Listen for first trade interface opening
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == TRADE_MAIN_INTERFACE_ID)
		{
			clientThread.invokeLater(this::handleTradeInterfaceOpened);
		}
		else if (event.getGroupId() == TRADE_CONFIRM_INTERFACE_ID)
		{
			// Second trade screen opened - capture screenshot
			inSecondScreen = true;
			secondScreenCaptured = false;
			log.debug("Second trade screen opened");
			clientThread.invokeLater(this::captureSecondScreenshot);
		}
	}

	/**
	 * Listen for trade interface closing
	 */
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == TRADE_MAIN_INTERFACE_ID)
		{
			// First screen closed - if second screen is open, this is normal
			if (!inSecondScreen)
			{
				handleTradeInterfaceClosed();
			}
		}
		else if (event.getGroupId() == TRADE_CONFIRM_INTERFACE_ID)
		{
			// Second screen closed - trade is complete or cancelled
			inSecondScreen = false;
			handleTradeInterfaceClosed();
		}
	}

	/**
	 * Monitor items being traded
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.TRADE.getId())
		{
			handleTradeContainerChange(event);
		}
	}

	/**
	 * Detect "Trade Accepted" chat message
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = event.getMessage();

		// Detect trade completion messages
		if (message.contains("Accepted trade") || message.contains("Other player has accepted"))
		{
			tradeAccepted = true;
			log.debug("Trade accepted detected");
		}
	}

	/**
	 * Capture screenshot on second accept screen
	 */
	private void captureSecondScreenshot()
	{
		if (secondScreenCaptured || currentTradePartner == null)
		{
			return;
		}

		// Check if screenshots are enabled
		// CHANGED: Consolidated screenshot config - single toggle controls all trade screenshots
		if (!config.enableTradeScreenshots())
		{
			return;
		}

		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			return;
		}

		// Only capture if trading with group member
		if (!activeGroup.hasMember(currentTradePartner))
		{
			return;
		}

		String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (currentPlayer == null)
		{
			return;
		}

		// Determine event type
		boolean isLending = determineLendingDirection(activeGroup, currentPlayer);
		String eventType = isLending ? "LEND" : "BORROW";

		// Create a temporary entry for context if we have trade items
		LendingEntry contextEntry = createContextEntry(activeGroup, currentPlayer, isLending);

		// Capture the screenshot
		File screenshot = proofScreenshot.captureSecondAcceptScreen(
			currentPlayer,
			activeGroup.getName(),
			currentTradePartner,
			eventType,
			contextEntry
		);

		if (screenshot != null)
		{
			secondScreenCaptured = true;
			log.info("Captured second accept screen screenshot: {}", screenshot.getName());
		}
	}

	/**
	 * Handle trade interface opening - capture trade partner
	 */
	private void handleTradeInterfaceOpened()
	{
		Widget tradePartnerWidget = client.getWidget(TRADE_MAIN_INTERFACE_ID, TRADE_PARTNER_WIDGET_ID);
		if (tradePartnerWidget != null)
		{
			String partnerText = tradePartnerWidget.getText();
			if (partnerText != null && !partnerText.isEmpty())
			{
				// Clean up the partner name (remove "Trading with: " prefix if present)
				currentTradePartner = partnerText.replace("Trading with:", "").trim();
				tradeItems.clear();
				tradeAccepted = false;
				inSecondScreen = false;
				secondScreenCaptured = false;
				pendingEntry = null;
				log.debug("Trade opened with: {}", currentTradePartner);
			}
		}
	}

	/**
	 * Handle trade container changes - track items
	 */
	private void handleTradeContainerChange(ItemContainerChanged event)
	{
		if (currentTradePartner == null)
		{
			return;
		}

		ItemContainer container = event.getItemContainer();
		if (container != null)
		{
			tradeItems.clear();
			for (Item item : container.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					tradeItems.put(item.getId(), item.getQuantity());
					log.debug("Trade item tracked: {} x{}", item.getId(), item.getQuantity());
				}
			}
		}
	}

	/**
	 * Handle trade interface closing - process potential lending
	 */
	private void handleTradeInterfaceClosed()
	{
		if (currentTradePartner != null && !tradeItems.isEmpty() && tradeAccepted)
		{
			processPotentialLendingTrade();
		}

		// Reset state
		currentTradePartner = null;
		tradeItems.clear();
		tradeAccepted = false;
		inSecondScreen = false;
		secondScreenCaptured = false;
		pendingEntry = null;
	}

	/**
	 * Process potential lending trade between group members
	 */
	private void processPotentialLendingTrade()
	{
		if (currentTradePartner == null || tradeItems.isEmpty())
		{
			return;
		}

		// Get active group
		LendingGroup activeGroup = groupConfigStore.getActiveGroup();
		if (activeGroup == null)
		{
			log.debug("No active group - skipping trade processing");
			return;
		}

		// Check if trade partner is in the active group
		if (!activeGroup.hasMember(currentTradePartner))
		{
			log.debug("Trade partner {} not in active group - skipping", currentTradePartner);
			return;
		}

		// Get current player
		String currentPlayer = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (currentPlayer == null)
		{
			log.warn("Cannot determine current player name");
			return;
		}

		log.info("Processing potential lending trade: {} <-> {} ({} items)",
			currentPlayer, currentTradePartner, tradeItems.size());

		// Determine lending direction
		boolean isLending = determineLendingDirection(activeGroup, currentPlayer);

		if (isLending)
		{
			processLending(activeGroup, currentPlayer, currentTradePartner);
		}
		else
		{
			processBorrowing(activeGroup, currentPlayer, currentTradePartner);
		}
	}

	/**
	 * Determine if this is a lending (true) or borrowing (false) transaction
	 * Logic: Check if traded items are on the current player's "available" list
	 */
	private boolean determineLendingDirection(LendingGroup group, String currentPlayer)
	{
		// Get player's available offerings
		java.util.List<LendingEntry> playerOfferings = marketplaceManager.getOfferingsByOwner(group.getId(), currentPlayer);

		// Check if any traded items match available offerings
		for (Map.Entry<Integer, Integer> tradeItem : tradeItems.entrySet())
		{
			int itemId = tradeItem.getKey();
			for (LendingEntry offering : playerOfferings)
			{
				if (offering.getItemId() == itemId)
				{
					log.debug("Matched trade item {} with offering - this is a LENDING", itemId);
					return true; // Found matching offering = lending
				}
			}
		}

		log.debug("No matching offerings found - this is a BORROWING");
		return false; // No matching offerings = borrowing
	}

	/**
	 * Create a context entry for screenshot overlay (before trade completes)
	 */
	private LendingEntry createContextEntry(LendingGroup group, String currentPlayer, boolean isLending)
	{
		if (tradeItems.isEmpty())
		{
			return null;
		}

		// Get first item for context
		Map.Entry<Integer, Integer> firstItem = tradeItems.entrySet().iterator().next();
		int itemId = firstItem.getKey();
		int quantity = firstItem.getValue();

		String itemName = itemManager.getItemComposition(itemId).getName();
		long itemValue = calculateItemValue(itemId, quantity);

		LendingEntry entry = new LendingEntry();
		entry.setItem(itemName);
		entry.setItemId(itemId);
		entry.setQuantity(quantity);
		entry.setValue(itemValue);
		entry.setGroupId(group.getId());

		if (isLending)
		{
			entry.setLender(currentPlayer);
			entry.setBorrower(currentTradePartner);
		}
		else
		{
			entry.setLender(currentTradePartner);
			entry.setBorrower(currentPlayer);
		}

		return entry;
	}

	/**
	 * Process lending transaction (current player is the lender)
	 */
	private void processLending(LendingGroup group, String lender, String borrower)
	{
		log.info("Processing LENDING: {} lends to {}", lender, borrower);

		for (Map.Entry<Integer, Integer> tradeItem : tradeItems.entrySet())
		{
			int itemId = tradeItem.getKey();
			int quantity = tradeItem.getValue();

			String itemName = itemManager.getItemComposition(itemId).getName();
			long itemValue = calculateItemValue(itemId, quantity);

			// Create lending entry
			LendingEntry entry = new LendingEntry();
			entry.setId(UUID.randomUUID().toString());
			entry.setGroupId(group.getId());
			entry.setLender(lender);
			entry.setBorrower(borrower);
			entry.setItem(itemName);
			entry.setItemId(itemId);
			entry.setQuantity(quantity);
			entry.setValue(itemValue);

			// Calculate due date (default: 7 days)
			long dueTime = Instant.now().plusSeconds(7L * 24 * 60 * 60).toEpochMilli();
			entry.setDueTime(dueTime);

			try
			{
				// Add to lending manager
				lendingManager.addLoan(group.getId(), lender, borrower, entry, dueTime);
				log.info("Lending recorded: {} lent {} x{} to {}",
					lender, itemName, quantity, borrower);

				// Remove from marketplace if it was listed
				marketplaceManager.removeOffering(group.getId(), lender, itemName, itemId);

				// Capture completion screenshot if enabled
				// CHANGED: Consolidated screenshot config
			if (config.enableTradeScreenshots())
				{
					File screenshot = proofScreenshot.captureTradeCompletion(
						lender,
						group.getName(),
						borrower,
						"LEND",
						entry
					);
					if (screenshot != null)
					{
						log.info("Captured trade completion screenshot: {}", screenshot.getName());
					}
				}

				// Send Discord notification (if enabled)
				if (config.enableDiscordWebhooks())
				{
					discordWebhookService.sendLendingNotification(group.getId(), entry);
				}
			}
			catch (Exception e)
			{
				log.error("Failed to process lending for item: {}", itemName, e);
			}
		}
	}

	/**
	 * Process borrowing transaction (current player is the borrower)
	 */
	private void processBorrowing(LendingGroup group, String borrower, String lender)
	{
		log.info("Processing BORROWING: {} borrows from {}", borrower, lender);

		// Note: The lender's client will handle recording the loan
		// Borrower just logs for awareness
		for (Map.Entry<Integer, Integer> tradeItem : tradeItems.entrySet())
		{
			int itemId = tradeItem.getKey();
			int quantity = tradeItem.getValue();
			String itemName = itemManager.getItemComposition(itemId).getName();

			log.info("Borrowing detected: {} borrowed {} x{} from {}",
				borrower, itemName, quantity, lender);

			// Clear any pending borrow requests for this item by this borrower
			clearMatchingBorrowRequests(group.getId(), borrower, itemName, itemId);

			// Capture completion screenshot for borrower if enabled
			// CHANGED: Consolidated screenshot config
		if (config.enableTradeScreenshots())
			{
				LendingEntry contextEntry = new LendingEntry();
				contextEntry.setItem(itemName);
				contextEntry.setItemId(itemId);
				contextEntry.setQuantity(quantity);
				contextEntry.setLender(lender);
				contextEntry.setBorrower(borrower);
				contextEntry.setGroupId(group.getId());

				File screenshot = proofScreenshot.captureTradeCompletion(
					borrower,
					group.getName(),
					lender,
					"BORROW",
					contextEntry
				);
				if (screenshot != null)
				{
					log.info("Captured borrow completion screenshot: {}", screenshot.getName());
				}
			}
		}
	}

	/**
	 * Clear any pending borrow requests that match the completed trade
	 * Called when a borrower receives an item they requested
	 */
	private void clearMatchingBorrowRequests(String groupId, String borrower, String itemName, int itemId)
	{
		try
		{
			List<BorrowRequest> requests = borrowRequestService.getRequestsByMember(borrower, groupId);
			for (BorrowRequest request : requests)
			{
				// Match by item name or item ID
				boolean matches = (request.getItemId() == itemId) ||
					(itemName != null && itemName.equalsIgnoreCase(request.getItemName()));

				if (matches && request.isPending())
				{
					// Complete the request since they received the item
					borrowRequestService.completeRequest(request.getId());
					log.info("Auto-completed borrow request {} for {} - item received via trade",
						request.getId(), itemName);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to clear borrow requests after trade: {}", e.getMessage());
		}
	}

	/**
	 * Capture return screenshots - called by LendingManager when item is returned
	 */
	public void captureReturnScreenshots(String currentPlayer, String groupName, String returnedTo,
										  LendingEntry entry, boolean captureSecondScreen)
	{
		// CHANGED: Consolidated screenshot config - single toggle for all trade screenshots
		if (!config.enableTradeScreenshots())
		{
			return;
		}

		if (captureSecondScreen)
		{
			File screenshot = proofScreenshot.captureReturnScreenshot(
				currentPlayer,
				groupName,
				returnedTo,
				"second_accept",
				entry
			);
			if (screenshot != null)
			{
				log.info("Captured return second accept screenshot: {}", screenshot.getName());
			}
		}

		// Always capture completion screenshot when trade screenshots are enabled
		{
			File screenshot = proofScreenshot.captureReturnScreenshot(
				currentPlayer,
				groupName,
				returnedTo,
				"completed",
				entry
			);
			if (screenshot != null)
			{
				log.info("Captured return completion screenshot: {}", screenshot.getName());
			}
		}
	}

	/**
	 * Calculate item value
	 */
	private long calculateItemValue(int itemId, int quantity)
	{
		try
		{
			int gePrice = itemManager.getItemPrice(itemId);
			return (long) gePrice * quantity;
		}
		catch (Exception e)
		{
			log.warn("Failed to get price for item {}, using 0", itemId);
			return 0;
		}
	}

	/**
	 * Reset trade state (useful for cleanup or manual reset)
	 */
	public void resetTradeState()
	{
		currentTradePartner = null;
		tradeItems.clear();
		tradeAccepted = false;
		inSecondScreen = false;
		secondScreenCaptured = false;
		pendingEntry = null;
		log.debug("Trade state reset");
	}
}
