package com.guess34.lendingtracker.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.FontID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.LendingTrackerConfig.GuardMode;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingGroup;

/**
 * TradeLoanTracker - records loans and returns directly from the player-to-player
 * trade window, and guards borrowed items against being traded away.
 *
 * Flow (mirrors the old pendingLending two-phase pattern):
 *  1. Trade screen 1 (group 335) opens -> new session, partner name read from the
 *     "Trading with: X" title.
 *  2. The player right-clicks their offered items and picks "Mark as Loan".
 *  3. Confirm screen (group 334) opens -> final offers are snapshotted and, when
 *     relevant, a proof screenshot of the confirm screen is cached in memory.
 *  4. "Accepted trade." chat message -> marked items become loan records
 *     (partner = borrower, their offer = collateral); items the partner handed
 *     back that match loans they borrowed are marked returned. The cached
 *     screenshot is committed to disk. A decline discards everything.
 *
 * The guard checks run from menu clicks: offering an item you are borrowing (or
 * accepting a trade that contains one) warns or blocks per config, with Shift as
 * the override; the Wilderness ditch "Cross" click is optionally blocked while
 * carrying borrowed items.
 */
@Slf4j
@Singleton
public class TradeLoanTracker
{
	// The partner's copy of a shared container is id | 0x8000 (see the deprecated
	// net.runelite.api.InventoryID.TRADEOTHER)
	private static final int TRADEOFFER_OTHER = InventoryID.TRADEOFFER | 0x8000;

	private static final String MARK_OPTION = "Mark as Loan";
	private static final String UNMARK_OPTION = "Unmark Loan";
	private static final String TRADING_WITH_PREFIX = "Trading with:";
	private static final String ACCEPTED_TRADE_MESSAGE = "Accepted trade.";
	private static final String PARTNER_DECLINED_MESSAGE = "Other player declined trade.";

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ItemManager itemManager;
	@Inject private LendingTrackerConfig config;
	@Inject private DataService dataService;
	@Inject private GroupService groupService;
	@Inject private ProofScreenshot proofScreenshot;

	/** Runnable the plugin sets to refresh the side panel after a change. */
	private Runnable onLoanRecorded;

	// --- Trade session state (all accessed on the client thread) ---
	private boolean tradeOpen;
	private String partner;
	// Exact item ids marked "Lend" (NOT variation-base ids: matching by base would
	// let a different variant of the same item — e.g. a glory(4) offered after
	// unoffering a marked glory(6) — silently become a loan)
	private final Set<Integer> markedItemIds = new HashSet<>();
	private final Set<Integer> warnedBaseIds = new HashSet<>();
	private Item[] finalMyOffer;
	private Item[] finalTheirOffer;
	// True once the confirm screen (334) has loaded for this session. Used by the
	// walk-away cleanup — do NOT infer this from finalTheirOffer, which is
	// legitimately null when the partner offers nothing (a pure loan hand-over:
	// their container is never created client-side until they offer something).
	private boolean confirmLoaded;
	private boolean screenshotCached;
	// Incremented on every reset; lets delayed callbacks (partner-name reads)
	// detect that their session ended so they stop instead of retrying forever
	// or writing stale state into a newer session
	private int sessionId;

	// --- Auto-detection of loans from marketplace listings ---
	// When an item the player has listed for lending shows up in their trade
	// offer, a popup asks (once per trade) whether the trade is that loan — no
	// right-click marking needed for listed items.
	private boolean loanPromptShown;
	private Boolean autoLoanAccepted; // null until the popup is answered
	// True while a popup is on screen and unanswered — a second trade never opens
	// another popup on top of it (stacked identical modals invite wrong answers)
	private boolean promptOpen;
	// The "Loan" toggle button drawn on the trade window (group trades only).
	// When ON, EVERYTHING handed over in this trade is recorded as a loan — one
	// tap, no popup or right-click; the partner doesn't need the plugin at all.
	private boolean autoLoanAll;
	private Widget loanButton;
	private Widget loanButtonBg;
	// Trades that completed before their popup was answered, keyed by session id;
	// a late "yes" records from here. Cleared on logout/shutdown so a stale stash
	// can never record loans under a different account's session.
	private final Map<Integer, PendingLoanDecision> pendingDecisions = new HashMap<>();
	private static final int MAX_PENDING_DECISIONS = 4;

	/** Snapshot of everything needed to record loans after the trade ended. */
	private static final class PendingLoanDecision
	{
		final int session;
		final String partnerName;
		final String lenderName;
		final String groupId;
		final List<StashedLoan> loans = new ArrayList<>();
		long collateralGp;
		String collateralItems;
		String collateralItemIds;

		PendingLoanDecision(int session, String partnerName, String lenderName, String groupId)
		{
			this.session = session;
			this.partnerName = partnerName;
			this.lenderName = lenderName;
			this.groupId = groupId;
		}
	}

	private static final class StashedLoan
	{
		final int itemId;
		final int quantity;
		final String name;
		final long value;

		StashedLoan(int itemId, int quantity, String name, long value)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.name = name;
			this.value = value;
		}
	}

	public void setOnLoanRecorded(Runnable callback)
	{
		this.onLoanRecorded = callback;
	}

	public boolean isTradeOpen()
	{
		return tradeOpen;
	}

	// --- Session lifecycle ---

	/** Trade screen 1 (group 335) loaded. */
	public void onTradeMainLoaded()
	{
		reset();
		tradeOpen = true;
		schedulePartnerRead(InterfaceID.Trademain.TITLE);
	}

	/**
	 * The trade screen 1 build script (TRADE_MAIN_INIT, 755) fired — the interface
	 * is fully populated and any rebuild that wiped a previous custom child just
	 * finished. Canonical, rebuild-safe point to (re)create the Loan button.
	 */
	public void onTradeMainBuilt()
	{
		// The build script can fire BEFORE our WidgetLoaded handler runs (observed
		// in-game). Don't start the session here — onTradeMainLoaded's reset would
		// wipe whatever we set and orphan a just-created button. Rebuilds mid-trade
		// (offer changes) arrive with tradeOpen already true and re-create safely.
		if (!tradeOpen)
		{
			return;
		}
		ensureLoanButton();
	}

	/** Confirm screen (group 334) loaded — snapshot the final agreed offers. */
	public void onTradeConfirmLoaded()
	{
		if (!tradeOpen)
		{
			// e.g. plugin was enabled mid-trade; treat the confirm screen as a session
			tradeOpen = true;
		}
		if (partner == null)
		{
			// Same tick-timing hazard as screen 1: the text may not be populated yet
			schedulePartnerRead(InterfaceID.Tradeconfirm.TRADEOPPONENT);
		}

		confirmLoaded = true;
		finalMyOffer = copyContainerItems(InventoryID.TRADEOFFER);
		finalTheirOffer = copyContainerItems(TRADEOFFER_OTHER);

		// Cache a proof screenshot while the confirm screen is still visible —
		// the "Accepted trade." message only arrives after the window closes
		if (config.enableTradeScreenshots() && screenshotRelevant())
		{
			proofScreenshot.cacheTradeFrame(ProofScreenshot.PHASE_CONFIRM_SCREEN);
			screenshotCached = true;
		}
	}

	/**
	 * Either trade-offer container changed while screen 1 is up. When the trade
	 * looks loan-relevant, (re)capture the first-screen proof frame — each
	 * re-capture overwrites the last, so the saved shot shows the FINAL offers
	 * with both usernames visible.
	 */
	public void onTradeOfferUpdated()
	{
		maybeCacheFirstScreen();
	}

	private void maybeCacheFirstScreen()
	{
		if (!tradeOpen || confirmLoaded || !config.enableTradeScreenshots() || !firstScreenRelevant())
		{
			return;
		}
		proofScreenshot.cacheTradeFrame(ProofScreenshot.PHASE_FIRST_SCREEN);
		screenshotCached = true;
	}

	/** Does the current screen-1 state look like a loan/return in progress? */
	private boolean firstScreenRelevant()
	{
		if (autoLoanAll || !markedItemIds.isEmpty())
		{
			return true;
		}
		// A listed item in my live offer (unless the popup was answered "no")
		if (!Boolean.FALSE.equals(autoLoanAccepted))
		{
			Item[] myOffer = copyContainerItems(InventoryID.TRADEOFFER);
			if (myOffer != null)
			{
				for (Item item : myOffer)
				{
					if (item != null && item.getId() > 0 && isListedForLending(item.getId()))
					{
						return true;
					}
				}
			}
		}
		// A potential return: the partner currently borrows something from me
		String me = localPlayerName();
		if (me != null && partner != null)
		{
			for (LendingEntry e : dataService.getActiveEntries())
			{
				if (!e.isReturned() && me.equalsIgnoreCase(e.getLender())
					&& partner.equalsIgnoreCase(e.getBorrower()))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Will this trade plausibly produce a loan or return worth screenshotting? */
	private boolean screenshotRelevant()
	{
		if (!markedItemIds.isEmpty() || !findReturnedLoans().isEmpty())
		{
			return true;
		}
		if (autoLoanAll && finalMyOffer != null)
		{
			for (Item item : finalMyOffer)
			{
				if (item != null && item.getId() > 0)
				{
					return true;
				}
			}
		}
		// Auto-detected loans count unless the popup was explicitly answered "no"
		return loanPromptShown && !Boolean.FALSE.equals(autoLoanAccepted)
			&& !listedItemIdsInFinalOffer().isEmpty();
	}

	/**
	 * My trade offer changed (ItemContainerChanged for the TRADEOFFER container).
	 * If it now contains items I have listed for lending and I haven't been asked
	 * yet this trade, pop the "is this a loan?" dialog — the listing already
	 * declared the intent, so no right-click marking should be needed.
	 */
	public void onMyOfferChanged(Item[] offerItems)
	{
		// No popup needed when the Loan button already covers the whole trade
		if (!tradeOpen || autoLoanAll || loanPromptShown || promptOpen || offerItems == null
			|| !config.promptListedLoans())
		{
			return;
		}

		// Only prompt for trades with a member of the lending group — selling a
		// listed item to a stranger is a sale, not a loan. If the partner name
		// hasn't resolved yet, DON'T burn the once-per-trade prompt: the resolved
		// name triggers a re-check (see schedulePartnerRead).
		if (partner == null || !isPartnerInActiveGroup())
		{
			return;
		}

		List<String> listedNames = new ArrayList<>();
		for (Item item : offerItems)
		{
			if (item != null && item.getId() > 0 && isListedForLending(item.getId()))
			{
				listedNames.add(itemName(item.getId()));
			}
		}
		if (listedNames.isEmpty())
		{
			return;
		}

		loanPromptShown = true;
		promptOpen = true;
		final int session = sessionId;
		final String partnerName = partner;
		final String itemsText = String.join(", ", listedNames);
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			int result = javax.swing.JOptionPane.showConfirmDialog(null,
				"You're offering item(s) you have listed for lending:\n" + itemsText
					+ "\n\nRecord this trade as a loan to " + partnerName + "?",
				"Lending Tracker", javax.swing.JOptionPane.YES_NO_OPTION);
			boolean accepted = result == javax.swing.JOptionPane.YES_OPTION;
			clientThread.invokeLater(() -> onLoanPromptAnswered(session, accepted));
		});
	}

	/**
	 * Add a "Loan: OFF/ON" toggle button to the middle of the trade window, for
	 * trades with a group member. One tap marks the whole trade as a loan — no
	 * popup, no right-click — and only THIS client needs the plugin, so it works
	 * when the clan mate is on mobile or vanilla.
	 */
	/**
	 * Create (or re-create) the "Loan" toggle button on the trade window. Safe to
	 * call repeatedly — it no-ops if a live button already exists, and re-builds
	 * one if the interface was rebuilt and wiped the old child. Called both when
	 * the partner name resolves and on every trade-screen build script (755), so
	 * it survives offer changes.
	 */
	public void ensureLoanButton()
	{
		if (!tradeOpen)
		{
			return;
		}

		// Resolve the partner now if the title is populated (it is, by build time)
		if (partner == null)
		{
			partner = readPartnerName(InterfaceID.Trademain.TITLE);
		}
		if (partner == null)
		{
			return;
		}

		// The button works for ANY trade partner — the loan is recorded entirely on
		// this (the lender's) client, so the borrower needn't be a group member or
		// even have the plugin. It only needs a group to file the loan under.
		LendingGroup group = groupService.getActiveGroup();
		if (group == null)
		{
			return;
		}

		// Still have a live button attached to the interface? Keep it.
		if (loanButton != null && loanButton.getParent() != null)
		{
			return;
		}
		loanButton = null;
		loanButtonBg = null;

		// Prefer the middle column layer (holds Accept/Decline); fall back outward
		Widget parent = firstNonNull(
			client.getWidget(InterfaceID.TRADEMAIN, 5),
			client.getWidget(InterfaceID.TRADEMAIN, 2),
			client.getWidget(InterfaceID.TRADEMAIN, 0));
		if (parent == null)
		{
			return;
		}

		int pw = parent.getWidth();
		int ph = parent.getHeight();
		int width = Math.max(90, Math.min(pw > 0 ? pw - 4 : 120, 120));
		int height = 16;
		int x = Math.max(0, (pw - width) / 2);
		int y = Math.max(2, ph - height - 4);

		// Filled background rectangle so it clearly reads as a button
		Widget bg = parent.createChild(-1, WidgetType.RECTANGLE);
		bg.setOriginalX(x);
		bg.setOriginalY(y);
		bg.setOriginalWidth(width);
		bg.setOriginalHeight(height);
		bg.setFilled(true);
		bg.setTextColor(0x1e1e1e);
		bg.setOpacity(70);
		bg.setAction(0, "Toggle");
		bg.setHasListener(true);
		bg.setOnOpListener((JavaScriptCallback) ev -> toggleLoanMode());
		bg.revalidate();

		Widget btn = parent.createChild(-1, WidgetType.TEXT);
		btn.setText(loanButtonText(false));
		btn.setTextColor(0xffff00);
		btn.setFontId(FontID.BOLD_12);
		btn.setTextShadowed(true);
		btn.setXTextAlignment(1);
		btn.setYTextAlignment(1);
		btn.setOriginalX(x);
		btn.setOriginalY(y);
		btn.setOriginalWidth(width);
		btn.setOriginalHeight(height);
		btn.setAction(0, "Toggle");
		btn.setHasListener(true);
		btn.setOnOpListener((JavaScriptCallback) ev -> toggleLoanMode());
		btn.revalidate();

		loanButtonBg = bg;
		loanButton = btn;
	}

	private static Widget firstNonNull(Widget... widgets)
	{
		for (Widget w : widgets)
		{
			if (w != null)
			{
				return w;
			}
		}
		return null;
	}

	private void toggleLoanMode()
	{
		// The widget's listener can fire once more after a same-tick reset
		// (decline + click race) — never toggle a dead session
		if (!tradeOpen || partner == null)
		{
			return;
		}
		autoLoanAll = !autoLoanAll;
		if (loanButton != null)
		{
			loanButton.setText(loanButtonText(autoLoanAll));
			loanButton.setTextColor(autoLoanAll ? 0x00ff00 : 0xffff00);
		}
		addGameMessage(autoLoanAll
			? "Loan mode ON — everything you hand over in this trade will be recorded as a loan to " + partner + "."
			: "Loan mode off for this trade.");
		if (autoLoanAll)
		{
			maybeCacheFirstScreen();
		}
	}

	private static String loanButtonText(boolean on)
	{
		return on ? "Loan: ON" : "Loan: OFF";
	}

	/** Popup answered — during the trade if possible, after it via the stash. */
	private void onLoanPromptAnswered(int session, boolean accepted)
	{
		promptOpen = false;

		if (session == sessionId && tradeOpen)
		{
			autoLoanAccepted = accepted;
			addGameMessage(accepted
				? "Listed items in this trade will be recorded as loans when it completes."
				: "Okay — this trade won't be recorded as a loan.");
			if (accepted)
			{
				maybeCacheFirstScreen();
			}
			return;
		}

		// The trade already completed while the dialog was open
		PendingLoanDecision stash = pendingDecisions.remove(session);
		if (stash != null && accepted)
		{
			recordStashedLoans(stash);
		}
	}

	/**
	 * Forget any stashed loan decisions and open-popup state. Called on logout and
	 * plugin shutdown so a late dialog answer can't record loans under a different
	 * account or long after the fact.
	 */
	public void clearPendingDecisions()
	{
		pendingDecisions.clear();
		promptOpen = false;
	}

	/** Is this item (or a variant of it) listed by me on the group marketplace? */
	private boolean isListedForLending(int itemId)
	{
		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || group == null)
		{
			return false;
		}
		int baseId = ItemVariationMapping.map(itemId);
		for (LendingEntry offering : dataService.getOfferingsByOwner(group.getId(), me))
		{
			if (ItemVariationMapping.map(offering.getItemId()) == baseId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Read the partner name from a trade widget, retrying for a bounded number of
	 * ticks (the text is often unpopulated on the load tick). The session id stops
	 * the retry loop the moment the session ends, so a failed read can neither
	 * retry forever nor write a stale name into a later session.
	 */
	private void schedulePartnerRead(int componentId)
	{
		final int session = sessionId;
		final int[] attempts = {0};
		clientThread.invokeLater(() ->
		{
			if (session != sessionId)
			{
				return true; // session ended — stop
			}
			String name = readPartnerName(componentId);
			if (name != null)
			{
				partner = name;
				// If the confirm screen was reached before the name resolved, the
				// screenshot-relevance check ran with partner == null (returns
				// couldn't be matched). Re-evaluate now so a return-only trade
				// still gets its proof frame while the screen is visible.
				if (confirmLoaded && !screenshotCached
					&& config.enableTradeScreenshots() && screenshotRelevant())
				{
					proofScreenshot.cacheTradeFrame(ProofScreenshot.PHASE_CONFIRM_SCREEN);
					screenshotCached = true;
				}
				// The loan popup and Loan button are gated on knowing the partner —
				// now that the name resolved, create the button and re-check the offer
				ensureLoanButton();
				onMyOfferChanged(copyContainerItems(InventoryID.TRADEOFFER));
				return true;
			}
			return ++attempts[0] >= 10; // give up after ~10 ticks
		});
	}

	/**
	 * A trade widget closed. Both accept and cancel paths fire this, and for a
	 * completed trade the "Accepted trade." message arrives AFTER the close — so
	 * don't reset immediately. Instead schedule a short-delay check: if the
	 * session is still open and neither the confirm screen loaded (335 closed by
	 * walking away / Esc) nor the completion message arrived (334 closed the same
	 * way), the trade was cancelled — clear the session so marks, partner and the
	 * cached proof frame don't linger.
	 */
	public void onTradeWidgetClosed(int groupId)
	{
		if (!tradeOpen)
		{
			return;
		}
		final int session = sessionId;
		final boolean closedConfirm = groupId == InterfaceID.TRADECONFIRM;
		final int[] ticks = {0};
		clientThread.invokeLater(() ->
		{
			if (session != sessionId)
			{
				return true; // completed, declined, or a new trade started — done
			}
			// 335 advancing to 334 loads the confirm screen within a tick or two;
			// a completed 334 produces "Accepted trade." within a few ticks.
			int waitTicks = closedConfirm ? 10 : 3;
			if (++ticks[0] < waitTicks)
			{
				return false;
			}
			if (!closedConfirm && confirmLoaded)
			{
				return true; // 335 closed because we advanced to the confirm screen
			}
			reset();
			return true;
		});
	}

	/** ChatMessageType.TRADE message — completion or partner decline. */
	public void onTradeMessage(String message)
	{
		if (ACCEPTED_TRADE_MESSAGE.equals(message))
		{
			completeTrade();
		}
		else if (PARTNER_DECLINED_MESSAGE.equals(message))
		{
			reset();
		}
	}

	public void reset()
	{
		sessionId++;
		tradeOpen = false;
		partner = null;
		markedItemIds.clear();
		warnedBaseIds.clear();
		finalMyOffer = null;
		finalTheirOffer = null;
		confirmLoaded = false;
		loanPromptShown = false;
		autoLoanAccepted = null;
		autoLoanAll = false;
		loanButton = null; // the widget dies with the trade interface
		loanButtonBg = null;
		// pendingDecisions deliberately survives reset() — it's how a popup answered
		// after its trade ended still records the loans. It IS cleared on logout and
		// shutdown via clearPendingDecisions().
		if (screenshotCached)
		{
			proofScreenshot.discardCachedFrame();
			screenshotCached = false;
		}
	}

	// --- Menu integration ---

	/**
	 * Add "Mark as Loan" / "Unmark Loan" to the player's own offered items on
	 * trade screen 1.
	 */
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!tradeOpen || event.getActionParam1() != InterfaceID.Trademain.YOUR_OFFER)
		{
			return;
		}

		// The engine fires one MenuEntryAdded per vanilla option on the hovered
		// slot (Remove-1/-5/-All/...) — only add our entry once per menu build, on
		// the first of them, so it can't stack up or become the left-click default.
		for (net.runelite.api.MenuEntry existing : client.getMenuEntries())
		{
			String opt = existing.getOption();
			if ((MARK_OPTION.equals(opt) || UNMARK_OPTION.equals(opt))
				&& existing.getParam0() == event.getActionParam0()
				&& existing.getParam1() == event.getActionParam1())
			{
				return;
			}
		}

		int itemId = resolveOfferItemId(event.getActionParam0());
		if (itemId <= 0)
		{
			return;
		}

		boolean marked = markedItemIds.contains(itemId);
		client.createMenuEntry(-1)
			.setOption(marked ? UNMARK_OPTION : MARK_OPTION)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setParam0(event.getActionParam0())
			.setParam1(event.getActionParam1())
			.setIdentifier(itemId);
	}

	/**
	 * Handle a menu click: loan mark toggles, decline detection, and the borrowed
	 * item guards. Returns true when the event was fully handled (consumed).
	 */
	public boolean onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = event.getMenuOption();
		if (option == null)
		{
			return false;
		}

		// Toggle loan marking (our own RUNELITE entries)
		if (MARK_OPTION.equals(option) || UNMARK_OPTION.equals(option))
		{
			int itemId = event.getId();
			if (MARK_OPTION.equals(option))
			{
				markedItemIds.add(itemId);
				addGameMessage("Marked " + itemName(itemId) + " as a loan. It will be recorded when the trade completes.");
				maybeCacheFirstScreen();
			}
			else
			{
				markedItemIds.remove(itemId);
				addGameMessage("Unmarked " + itemName(itemId) + ".");
			}
			event.consume();
			return true;
		}

		int widgetGroup = event.getParam1() >> 16;

		// Self-decline ends the session (no chat message is produced for it)
		if ("Decline".equals(option)
			&& (widgetGroup == InterfaceID.TRADEMAIN || widgetGroup == InterfaceID.TRADECONFIRM))
		{
			reset();
			return false;
		}

		// Trade guard: offering an item you're borrowing
		if (tradeOpen && widgetGroup == InterfaceID.TRADESIDE && option.startsWith("Offer"))
		{
			return guardOfferClick(event);
		}

		// Trade guard: accepting a trade whose offer contains a borrowed item
		if (tradeOpen && "Accept".equals(option)
			&& (event.getParam1() == InterfaceID.Trademain.ACCEPT
				|| event.getParam1() == InterfaceID.Tradeconfirm.TRADE2ACCEPT))
		{
			return guardAcceptClick(event);
		}

		// Wilderness guard: crossing the ditch while carrying borrowed items
		if ("Cross".equals(option) && config.wildernessGuard() == GuardMode.BLOCK
			&& Text.removeTags(event.getMenuTarget()).contains("Wilderness ditch"))
		{
			return guardWildernessCross(event);
		}

		return false;
	}

	// --- Guards ---

	private boolean guardOfferClick(MenuOptionClicked event)
	{
		if (config.tradeGuard() == GuardMode.OFF)
		{
			return false;
		}

		int itemId = event.getMenuEntry().getItemId();
		if (itemId <= 0)
		{
			itemId = resolveInventoryItemId(event.getParam0());
		}
		if (itemId <= 0)
		{
			return false;
		}

		LendingEntry borrowed = findBorrowedEntry(itemId);
		if (borrowed == null)
		{
			return false;
		}

		int baseId = ItemVariationMapping.map(itemId);

		// Giving the item back to its lender is the trade we WANT — never impede it
		if (partner != null && partner.equalsIgnoreCase(borrowed.getLender()))
		{
			if (warnedBaseIds.add(baseId))
			{
				addGameMessage("Returning " + itemName(itemId) + " to " + borrowed.getLender()
					+ " — the loan will be marked returned when the trade completes.");
			}
			return false;
		}

		boolean partnerInGroup = isPartnerInActiveGroup();

		if (partnerInGroup && config.allowLendBorrowedToGroup())
		{
			if (warnedBaseIds.add(baseId))
			{
				addGameMessage(warnPrefix() + itemName(itemId) + " is borrowed from "
					+ borrowed.getLender() + " — " + partner + " is in your lending group, so this is allowed.");
			}
			return false;
		}

		if (config.tradeGuard() == GuardMode.BLOCK && !client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			event.consume();
			addGameMessage(warnPrefix() + "Blocked: " + itemName(itemId) + " is borrowed from "
				+ borrowed.getLender() + ". Hold Shift and click again to offer it anyway.");
			return true;
		}

		if (warnedBaseIds.add(baseId))
		{
			addGameMessage(warnPrefix() + itemName(itemId) + " is borrowed from "
				+ borrowed.getLender() + " — are you sure you want to trade it away?");
		}
		return false;
	}

	private boolean guardAcceptClick(MenuOptionClicked event)
	{
		if (config.tradeGuard() != GuardMode.BLOCK || client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return false;
		}
		if (isPartnerInActiveGroup() && config.allowLendBorrowedToGroup())
		{
			return false;
		}

		Item[] myOffer = copyContainerItems(InventoryID.TRADEOFFER);
		if (myOffer == null)
		{
			return false;
		}

		for (Item item : myOffer)
		{
			LendingEntry borrowed = item != null ? findBorrowedEntry(item.getId()) : null;
			if (borrowed != null)
			{
				// Returning to the lender is fine — don't block the Accept
				if (partner != null && partner.equalsIgnoreCase(borrowed.getLender()))
				{
					continue;
				}
				event.consume();
				addGameMessage(warnPrefix() + "Blocked: your offer contains " + itemName(item.getId())
					+ " borrowed from " + borrowed.getLender() + ". Remove it, or hold Shift and click Accept to override.");
				return true;
			}
		}
		return false;
	}

	private boolean guardWildernessCross(MenuOptionClicked event)
	{
		// Only guard crossings INTO the wilderness. Blocking the same click while
		// the player is already inside would delay their escape — the opposite of
		// protecting the borrowed items.
		if (client.getVarbitValue(net.runelite.api.Varbits.IN_WILDERNESS) == 1)
		{
			return false;
		}
		if (client.isKeyPressed(KeyCode.KC_SHIFT))
		{
			return false;
		}

		List<LendingEntry> borrowed = carriedBorrowedEntries();
		List<LendingEntry> collateral = carriedCollateralEntries();
		if (borrowed.isEmpty() && collateral.isEmpty())
		{
			return false;
		}

		event.consume();
		StringBuilder what = new StringBuilder();
		if (!borrowed.isEmpty())
		{
			long total = borrowed.stream().mapToLong(LendingEntry::getValue).sum();
			what.append(borrowed.size()).append(" borrowed item(s) worth ")
				.append(QuantityFormatter.quantityToStackSize(total)).append(" GP");
		}
		if (!collateral.isEmpty())
		{
			if (what.length() > 0)
			{
				what.append(" and ");
			}
			what.append("collateral you're holding for ").append(collateral.size()).append(" loan(s)");
		}
		addGameMessage(warnPrefix() + "Blocked: you are carrying " + what
			+ ". Hold Shift and click again to cross anyway.");
		return true;
	}

	/**
	 * Borrowed items currently in the player's inventory or equipment.
	 * Used by the wilderness guards (both the ditch block and the entry warning).
	 */
	public List<LendingEntry> carriedBorrowedEntries()
	{
		String me = localPlayerName();
		if (me == null)
		{
			return new ArrayList<>();
		}

		Set<Integer> carriedBaseIds = new HashSet<>();
		collectBaseIds(carriedBaseIds, InventoryID.INV);
		collectBaseIds(carriedBaseIds, InventoryID.WORN);

		List<LendingEntry> result = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isReturned() && me.equalsIgnoreCase(e.getBorrower())
				&& carriedBaseIds.contains(ItemVariationMapping.map(e.getItemId())))
			{
				result.add(e);
			}
		}
		return result;
	}

	/**
	 * Active loans I LENT whose item-collateral I'm currently carrying (inventory
	 * or equipment). The lender is responsible for the borrower's collateral until
	 * the loan settles, so the wilderness guards treat it like borrowed gear.
	 */
	public List<LendingEntry> carriedCollateralEntries()
	{
		String me = localPlayerName();
		if (me == null)
		{
			return new ArrayList<>();
		}

		Set<Integer> carriedBaseIds = new HashSet<>();
		collectBaseIds(carriedBaseIds, InventoryID.INV);
		collectBaseIds(carriedBaseIds, InventoryID.WORN);

		List<LendingEntry> result = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (e.isReturned() || !me.equalsIgnoreCase(e.getLender()))
			{
				continue;
			}
			for (int collateralId : parseCollateralItemIds(e))
			{
				if (carriedBaseIds.contains(ItemVariationMapping.map(collateralId)))
				{
					result.add(e);
					break;
				}
			}
		}
		return result;
	}

	/** Parse the "itemId:qty,itemId:qty" collateral field into item ids. */
	private static List<Integer> parseCollateralItemIds(LendingEntry e)
	{
		List<Integer> ids = new ArrayList<>();
		String raw = e.getCollateralItemIds();
		if (raw == null || raw.isEmpty())
		{
			return ids;
		}
		for (String pair : raw.split(","))
		{
			try
			{
				int idx = pair.indexOf(':');
				ids.add(Integer.parseInt(idx > 0 ? pair.substring(0, idx) : pair));
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return ids;
	}

	// --- Trade completion ---

	private void completeTrade()
	{
		try
		{
			if (partner == null)
			{
				if (!markedItemIds.isEmpty())
				{
					addGameMessage(warnPrefix() + "Trade completed but the partner's name couldn't be read — the loan was NOT recorded. Add it manually from the panel.");
				}
				return;
			}
			if (finalMyOffer == null && finalTheirOffer == null)
			{
				return;
			}

			List<LendingEntry> returned = findReturnedLoans();

			// Loans = everything I offered (Loan button ON) ∪ explicitly marked
			// items ∪ (listed items, if the popup was answered yes). An unanswered
			// popup stashes the listed items so a late "yes" can still record them.
			Set<Integer> loanItemIds = new HashSet<>(markedItemIds);
			if (autoLoanAll && finalMyOffer != null)
			{
				for (Item item : finalMyOffer)
				{
					if (item != null && item.getId() > 0)
					{
						loanItemIds.add(item.getId());
					}
				}
			}
			if (Boolean.TRUE.equals(autoLoanAccepted))
			{
				loanItemIds.addAll(listedItemIdsInFinalOffer());
			}
			List<LendingEntry> newLoans = recordLoans(loanItemIds, !returned.isEmpty());

			PendingLoanDecision stashedNow = null;
			if (loanPromptShown && autoLoanAccepted == null)
			{
				stashedNow = stashPendingDecision(loanItemIds, !returned.isEmpty());
			}

			for (LendingEntry e : returned)
			{
				dataService.completeEntry(e.getId(), true);
				relistReturnedItem(e);
				addGameMessage("Return recorded: " + e.getItemName() + " from " + partner + ".");
			}

			notePartialReturns(returned);

			if (!newLoans.isEmpty() || !returned.isEmpty())
			{
				// commitCachedTrade prefers the frame cached on the confirm screen;
				// if none was cached (e.g. the partner name resolved too late) it
				// falls back to capturing the next frame — weaker evidence, but
				// better than nothing
				if (config.enableTradeScreenshots())
				{
					LendingEntry subject = !newLoans.isEmpty() ? newLoans.get(0) : returned.get(0);
					String eventType = !newLoans.isEmpty() ? "LOAN" : "RETURN";
					proofScreenshot.commitCachedTrade(localPlayerName(), activeGroupName(), partner, eventType, subject);
				}
				else if (screenshotCached)
				{
					proofScreenshot.discardCachedFrame();
				}
			}
			else if (stashedNow != null && config.enableTradeScreenshots())
			{
				// THIS trade's popup hasn't been answered yet: commit the confirm-screen
				// frame now (a stray screenshot is harmless if they answer no; waiting
				// would lose the frame to the next trade)
				LendingEntry provisional = provisionalEntryFromStash(stashedNow);
				if (provisional != null)
				{
					proofScreenshot.commitCachedTrade(localPlayerName(), activeGroupName(), partner, "LOAN", provisional);
				}
				else if (screenshotCached)
				{
					proofScreenshot.discardCachedFrame();
				}
			}
			else if (screenshotCached)
			{
				proofScreenshot.discardCachedFrame();
			}
			screenshotCached = false;

			if ((!newLoans.isEmpty() || !returned.isEmpty()) && onLoanRecorded != null)
			{
				onLoanRecorded.run();
			}
		}
		finally
		{
			reset();
		}
	}

	/**
	 * Active loans I lent to this partner that their final offer fully hands back.
	 *
	 * Quantity-aware: each loan consumes its quantity from what was offered, oldest
	 * loan first, so one returned whip closes one whip loan (not every whip loan),
	 * and a PARTIAL return (3 of 10 lent brews) closes nothing — the loan stays
	 * open and the user is told to settle it manually.
	 */
	private List<LendingEntry> findReturnedLoans()
	{
		List<LendingEntry> result = new ArrayList<>();
		String me = localPlayerName();
		if (me == null || partner == null || finalTheirOffer == null)
		{
			return result;
		}

		// Quantity offered per variation-base id
		Map<Integer, Integer> offeredByBase = new HashMap<>();
		for (Item item : finalTheirOffer)
		{
			if (item != null && item.getId() > 0)
			{
				offeredByBase.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		if (offeredByBase.isEmpty())
		{
			return result;
		}

		List<LendingEntry> candidates = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isReturned()
				&& me.equalsIgnoreCase(e.getLender())
				&& partner.equalsIgnoreCase(e.getBorrower())
				&& offeredByBase.containsKey(ItemVariationMapping.map(e.getItemId())))
			{
				candidates.add(e);
			}
		}
		candidates.sort((a, b) -> Long.compare(a.getLendTime(), b.getLendTime()));

		for (LendingEntry e : candidates)
		{
			int baseId = ItemVariationMapping.map(e.getItemId());
			int available = offeredByBase.getOrDefault(baseId, 0);
			int needed = Math.max(1, e.getQuantity());
			if (available >= needed)
			{
				offeredByBase.put(baseId, available - needed);
				result.add(e);
			}
		}
		return result;
	}

	/**
	 * Tell the user when the partner handed back SOME of a loan but not enough to
	 * close it (e.g. 3 of 10 lent brews) — the loan stays open, and pretending
	 * nothing happened would be confusing.
	 */
	private void notePartialReturns(List<LendingEntry> fullyReturned)
	{
		String me = localPlayerName();
		if (me == null || partner == null || finalTheirOffer == null)
		{
			return;
		}

		// Quantity offered per base, MINUS what the completed returns consumed —
		// only genuinely leftover quantity indicates a partial return. Without this,
		// an exact full return would flag any second same-base loan as "partial".
		Map<Integer, Integer> remainingByBase = new HashMap<>();
		for (Item item : finalTheirOffer)
		{
			if (item != null && item.getId() > 0)
			{
				remainingByBase.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		Set<String> returnedIds = new HashSet<>();
		for (LendingEntry e : fullyReturned)
		{
			returnedIds.add(e.getId());
			remainingByBase.merge(ItemVariationMapping.map(e.getItemId()), -Math.max(1, e.getQuantity()), Integer::sum);
		}

		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isReturned()
				&& !returnedIds.contains(e.getId())
				&& me.equalsIgnoreCase(e.getLender())
				&& partner.equalsIgnoreCase(e.getBorrower())
				&& remainingByBase.getOrDefault(ItemVariationMapping.map(e.getItemId()), 0) > 0)
			{
				addGameMessage(warnPrefix() + "Partial return of " + e.getItemName()
					+ " detected — the loan (x" + e.getQuantity() + ") stays open. Mark it returned in the panel once settled.");
			}
		}
	}

	/** Exact ids of items in my final offer that I have listed for lending. */
	private Set<Integer> listedItemIdsInFinalOffer()
	{
		Set<Integer> result = new HashSet<>();
		if (finalMyOffer == null)
		{
			return result;
		}
		for (Item item : finalMyOffer)
		{
			if (item != null && item.getId() > 0 && isListedForLending(item.getId()))
			{
				result.add(item.getId());
			}
		}
		return result;
	}

	/** Create loan records for the given item ids present in my final offer. */
	private List<LendingEntry> recordLoans(Set<Integer> loanItemIds, boolean hasReturns)
	{
		List<LendingEntry> created = new ArrayList<>();
		String me = localPlayerName();
		if (me == null || loanItemIds.isEmpty() || finalMyOffer == null)
		{
			return created;
		}

		LendingGroup group = groupService.getActiveGroup();
		if (group == null)
		{
			addGameMessage(warnPrefix() + "Trade completed but no lending group is active — the loan was NOT recorded. Create or select a group first.");
			return created;
		}

		// Aggregate loaned offer items by exact id (an offer can hold several stacks)
		Map<Integer, Integer> lentQuantities = new HashMap<>();
		for (Item item : finalMyOffer)
		{
			if (item != null && item.getId() > 0 && loanItemIds.contains(item.getId()))
			{
				lentQuantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		if (lentQuantities.isEmpty())
		{
			return created;
		}

		// The partner's side of a loan trade is collateral. Coins and platinum
		// tokens count as GP. If this trade is ALSO returning loans to me, their
		// other items are most likely those returns, so they aren't listed as
		// collateral in that case.
		long collateralGp = 0;
		List<String> collateralItems = new ArrayList<>();
		List<String> collateralIdPairs = new ArrayList<>();
		if (finalTheirOffer != null)
		{
			for (Item item : finalTheirOffer)
			{
				if (item == null || item.getId() <= 0)
				{
					continue;
				}
				if (item.getId() == ItemID.COINS_995)
				{
					collateralGp += item.getQuantity();
				}
				else if (item.getId() == ItemID.PLATINUM_TOKEN)
				{
					collateralGp += item.getQuantity() * 1000L;
				}
				else if (!hasReturns)
				{
					collateralItems.add(itemName(item.getId())
						+ (item.getQuantity() > 1 ? " x" + item.getQuantity() : ""));
					collateralIdPairs.add(item.getId() + ":" + item.getQuantity());
				}
			}
		}

		long dueTime = System.currentTimeMillis() + config.defaultLoanDuration() * 86400000L;
		boolean first = true;
		for (Map.Entry<Integer, Integer> lent : lentQuantities.entrySet())
		{
			int itemId = lent.getKey();
			int quantity = lent.getValue();

			LendingEntry entry = new LendingEntry();
			entry.setId(UUID.randomUUID().toString());
			entry.setItem(itemName(itemId));
			entry.setItemId(itemId);
			entry.setQuantity(quantity);
			entry.setValue((long) itemManager.getItemPrice(itemId) * quantity);
			// Collateral covers the whole trade — attach it to the FIRST loan record
			// only, so several loans from one trade don't multiply the recorded total
			if (first)
			{
				if (collateralGp > 0)
				{
					entry.setCollateralValue((int) Math.min(collateralGp, Integer.MAX_VALUE));
					entry.setCollateralType("GP");
				}
				if (!collateralItems.isEmpty())
				{
					entry.setCollateralItems(String.join(", ", collateralItems));
					entry.setCollateralItemIds(String.join(",", collateralIdPairs));
				}
			}
			dataService.addLoan(group.getId(), me, partner, entry, dueTime);
			adjustListingForLoan(group.getId(), me, itemId, quantity);
			created.add(entry);

			addGameMessage("Loan recorded: " + entry.getItem()
				+ (quantity > 1 ? " x" + quantity : "") + " to " + partner
				+ " (" + config.defaultLoanDuration() + " days"
				+ (first && collateralGp > 0 ? ", collateral " + QuantityFormatter.quantityToStackSize(collateralGp) + " GP" : "")
				+ ").");
			first = false;
		}
		return created;
	}

	/**
	 * A loan came home — put the item (back) on the group marketplace. Restocks a
	 * decremented listing, and items that were lent WITHOUT ever being listed join
	 * the marketplace pool here, so everything the player has ever lent out ends
	 * up visible to the group. addAvailable merges quantity into an existing
	 * listing of the same item, so this never creates duplicates.
	 */
	private void relistReturnedItem(LendingEntry returned)
	{
		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || group == null || returned.getItemId() <= 0)
		{
			return;
		}

		LendingEntry listing = new LendingEntry();
		listing.setId(UUID.randomUUID().toString());
		listing.setItem(returned.getItem());
		listing.setItemId(returned.getItemId());
		listing.setQuantity(Math.max(1, returned.getQuantity()));
		listing.setValue(returned.getValue());
		listing.setBorrower("");
		listing.setLendTime(System.currentTimeMillis());
		listing.setDueTime(Long.MAX_VALUE);
		dataService.addAvailable(group.getId(), me, listing);
		addGameMessage(returned.getItemName() + " re-listed on the group marketplace.");
	}

	/**
	 * A listed item just went out on loan — reduce or remove the marketplace
	 * listing so it doesn't still show as available while it's lent out.
	 */
	private void adjustListingForLoan(String groupId, String me, int itemId, int quantity)
	{
		int baseId = ItemVariationMapping.map(itemId);
		for (LendingEntry offering : dataService.getOfferingsByOwner(groupId, me))
		{
			if (ItemVariationMapping.map(offering.getItemId()) == baseId)
			{
				String ownerKey = offering.getLender() != null ? offering.getLender() : me;
				if (offering.getQuantity() > quantity)
				{
					LendingEntry updated = new LendingEntry(offering);
					updated.setQuantity(offering.getQuantity() - quantity);
					dataService.updateAvailable(groupId, ownerKey, offering.getItem(), offering.getItemId(), updated);
				}
				else
				{
					dataService.removeAvailable(groupId, ownerKey, offering.getItem(), offering.getItemId());
				}
				return;
			}
		}
	}

	/**
	 * The trade completed while the "is this a loan?" popup was still open — stash
	 * everything needed so a late "yes" can still record the loans.
	 * Already-recorded ids (explicitly marked ones) are excluded.
	 */
	private PendingLoanDecision stashPendingDecision(Set<Integer> alreadyRecorded, boolean hasReturns)
	{
		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || partner == null || group == null || finalMyOffer == null)
		{
			return null;
		}

		PendingLoanDecision stash = new PendingLoanDecision(sessionId, partner, me, group.getId());
		Map<Integer, Integer> quantities = new HashMap<>();
		for (Item item : finalMyOffer)
		{
			if (item != null && item.getId() > 0 && !alreadyRecorded.contains(item.getId())
				&& isListedForLending(item.getId()))
			{
				quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		if (quantities.isEmpty())
		{
			return null;
		}
		for (Map.Entry<Integer, Integer> q : quantities.entrySet())
		{
			stash.loans.add(new StashedLoan(q.getKey(), q.getValue(), itemName(q.getKey()),
				(long) itemManager.getItemPrice(q.getKey()) * q.getValue()));
		}

		// Collateral snapshot (same rules as recordLoans)
		long collateralGp = 0;
		List<String> collateralItems = new ArrayList<>();
		List<String> collateralIdPairs = new ArrayList<>();
		if (finalTheirOffer != null)
		{
			for (Item item : finalTheirOffer)
			{
				if (item == null || item.getId() <= 0)
				{
					continue;
				}
				if (item.getId() == ItemID.COINS_995)
				{
					collateralGp += item.getQuantity();
				}
				else if (item.getId() == ItemID.PLATINUM_TOKEN)
				{
					collateralGp += item.getQuantity() * 1000L;
				}
				else if (!hasReturns)
				{
					collateralItems.add(itemName(item.getId())
						+ (item.getQuantity() > 1 ? " x" + item.getQuantity() : ""));
					collateralIdPairs.add(item.getId() + ":" + item.getQuantity());
				}
			}
		}
		stash.collateralGp = collateralGp;
		stash.collateralItems = collateralItems.isEmpty() ? null : String.join(", ", collateralItems);
		stash.collateralItemIds = collateralIdPairs.isEmpty() ? null : String.join(",", collateralIdPairs);

		// Bound the stash map: evict the oldest if a user somehow leaves several
		// popups unanswered
		if (pendingDecisions.size() >= MAX_PENDING_DECISIONS)
		{
			Integer oldest = pendingDecisions.keySet().stream().min(Integer::compare).orElse(null);
			if (oldest != null)
			{
				pendingDecisions.remove(oldest);
			}
		}
		pendingDecisions.put(stash.session, stash);
		return stash;
	}

	/** The popup was answered "yes" after the trade ended — record from the stash. */
	private void recordStashedLoans(PendingLoanDecision stash)
	{
		long dueTime = System.currentTimeMillis() + config.defaultLoanDuration() * 86400000L;
		boolean first = true;
		for (StashedLoan loan : stash.loans)
		{
			LendingEntry entry = new LendingEntry();
			entry.setId(UUID.randomUUID().toString());
			entry.setItem(loan.name);
			entry.setItemId(loan.itemId);
			entry.setQuantity(loan.quantity);
			entry.setValue(loan.value);
			if (first)
			{
				if (stash.collateralGp > 0)
				{
					entry.setCollateralValue((int) Math.min(stash.collateralGp, Integer.MAX_VALUE));
					entry.setCollateralType("GP");
				}
				if (stash.collateralItems != null)
				{
					entry.setCollateralItems(stash.collateralItems);
					entry.setCollateralItemIds(stash.collateralItemIds);
				}
			}
			dataService.addLoan(stash.groupId, stash.lenderName, stash.partnerName, entry, dueTime);
			adjustListingForLoan(stash.groupId, stash.lenderName, loan.itemId, loan.quantity);

			addGameMessage("Loan recorded: " + loan.name
				+ (loan.quantity > 1 ? " x" + loan.quantity : "") + " to " + stash.partnerName
				+ " (" + config.defaultLoanDuration() + " days"
				+ (first && stash.collateralGp > 0 ? ", collateral " + QuantityFormatter.quantityToStackSize(stash.collateralGp) + " GP" : "")
				+ ").");
			first = false;
		}

		if (!stash.loans.isEmpty() && onLoanRecorded != null)
		{
			onLoanRecorded.run();
		}
	}

	/** A display-only entry describing the stashed loans, for the screenshot overlay. */
	private LendingEntry provisionalEntryFromStash(PendingLoanDecision stash)
	{
		if (stash.loans.isEmpty())
		{
			return null;
		}
		StashedLoan loan = stash.loans.get(0);
		LendingEntry entry = new LendingEntry();
		entry.setItem(loan.name);
		entry.setItemId(loan.itemId);
		entry.setQuantity(loan.quantity);
		entry.setValue(loan.value);
		entry.setLender(stash.lenderName);
		entry.setBorrower(stash.partnerName);
		if (stash.collateralGp > 0)
		{
			entry.setCollateralValue((int) Math.min(stash.collateralGp, Integer.MAX_VALUE));
			entry.setCollateralType("GP");
		}
		return entry;
	}

	// --- Helpers ---

	/** Find an active loan where the local player borrowed an item matching itemId. */
	private LendingEntry findBorrowedEntry(int itemId)
	{
		String me = localPlayerName();
		if (me == null)
		{
			return null;
		}
		int baseId = ItemVariationMapping.map(itemId);
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isReturned() && me.equalsIgnoreCase(e.getBorrower())
				&& ItemVariationMapping.map(e.getItemId()) == baseId)
			{
				return e;
			}
		}
		return null;
	}

	private boolean isPartnerInActiveGroup()
	{
		if (partner == null)
		{
			return false;
		}
		LendingGroup group = groupService.getActiveGroup();
		return group != null && group.hasMember(partner);
	}

	private String readPartnerName(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		String text = Text.removeTags(widget.getText()).replace((char) 0xA0, ' ');
		// The game renders both "Trading with:" and "Trading With:" (capital W)
		// depending on the screen — match case-insensitively, like the RuneWatch
		// plugin does for the same widgets
		int idx = text.toLowerCase().indexOf(TRADING_WITH_PREFIX.toLowerCase());
		if (idx < 0)
		{
			return null;
		}
		String name = text.substring(idx + TRADING_WITH_PREFIX.length()).trim();
		return name.isEmpty() ? null : name;
	}

	private Item[] copyContainerItems(int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		return container != null ? container.getItems().clone() : null;
	}

	private int resolveOfferItemId(int slot)
	{
		ItemContainer offer = client.getItemContainer(InventoryID.TRADEOFFER);
		if (offer == null || slot < 0 || slot >= offer.getItems().length)
		{
			return -1;
		}
		return offer.getItems()[slot].getId();
	}

	private int resolveInventoryItemId(int slot)
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null || slot < 0 || slot >= inv.getItems().length)
		{
			return -1;
		}
		return inv.getItems()[slot].getId();
	}

	private void collectBaseIds(Set<Integer> into, int containerId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0)
			{
				into.add(ItemVariationMapping.map(item.getId()));
			}
		}
	}

	private String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			return "item " + itemId;
		}
	}

	private String localPlayerName()
	{
		return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
	}

	private String activeGroupName()
	{
		LendingGroup group = groupService.getActiveGroup();
		return group != null ? group.getName() : null;
	}

	private String warnPrefix()
	{
		return "[Lending Tracker] ";
	}

	private void addGameMessage(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, "");
	}
}
