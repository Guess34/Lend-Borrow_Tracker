package com.guess34.lendingtracker.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
	@Inject private DiscordWebhook discordWebhook;
	@Inject private ClanRoster clanRoster;
	@Inject private net.runelite.client.Notifier notifier;

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
	// The button cycles OFF -> LIST -> 1-TIME: LIST relists the item on the group
	// marketplace when it comes home, 1-TIME records the loan but never lists it
	// (a friend borrowing your axe once shouldn't become a standing offer).
	private boolean autoLoanAll;
	private boolean loanOneTime;
	private Widget loanButton;
	private Widget loanButtonBg;
	// The "Collat" toggle button, drawn under the Loan button. Pressed by the
	// BORROWER side of a trade: everything I hand over is my collateral deposit
	// for a borrow — never a lend — so none of my items may be recorded as loans
	// or trip the marketplace popup. The loan itself is recorded by the lender's
	// client (single writer), exactly like every other loan.
	private boolean collatMode;
	private Widget collatButton;
	private Widget collatButtonBg;
	// How long a loan recorded from THIS trade runs, picked with the Days button in
	// the trade window. 0 = the default from the plugin settings, NO_LIMIT = no due
	// date at all (never overdue). Mostly for trades with mobile and other
	// no-plugin players, where the trade window is the only place to say it.
	private int loanDays;
	private Widget daysButton;
	private Widget daysButtonBg;
	private static final int NO_LIMIT = -1;
	private static final int[] DAY_PRESETS = { 1, 2, 3, 4, 5, 6, 7, NO_LIMIT };
	// Last-known bank contents per variation-base id, for the fungible-duplicate
	// guard logic (see atRiskCarrying). Only populated once the bank has been
	// opened this session; cleared on logout so one account's bank can never
	// vouch for another's collateral.
	private final Map<Integer, Integer> bankQtyByBase = new HashMap<>();
	private boolean bankKnown;

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
		int days;

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

	// Which item family a listing belongs to - the same as following the bank
	// uses, so a noted or kitted loan finds the listing it came from. Client
	// thread only.
	private java.util.function.IntUnaryOperator familyOf = ItemVariationMapping::map;

	public void setFamilyOf(java.util.function.IntUnaryOperator familyOf)
	{
		this.familyOf = familyOf;
	}

	// Did the owner tell this listing to keep its number rather than follow the bank?
	private java.util.function.IntPredicate keptAmount;

	public void setKeptAmount(java.util.function.IntPredicate check)
	{
		this.keptAmount = check;
	}

	// Told after each completed trade what of ours left and what of ours came home
	// (item id -> quantity). Before the bank has been seen this session, that's
	// the only way a listing following the bank can know.
	private java.util.function.BiConsumer<Map<Integer, Integer>, Map<Integer, Integer>> onTradeSettled;

	public void setOnTradeSettled(java.util.function.BiConsumer<Map<Integer, Integer>, Map<Integer, Integer>> callback)
	{
		this.onTradeSettled = callback;
	}

	/** Collateral this player holds for others, per item base. */
	public Map<Integer, Integer> collateralHeldByBase()
	{
		Map<Integer, Integer> held = new HashMap<>();
		String me = localPlayerName();
		if (me == null)
		{
			return held;
		}
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (me.equalsIgnoreCase(e.getLender()))
			{
				for (int[] pair : parseIdQtyPairs(e.outstandingCollateralIds()))
				{
					held.merge(ItemVariationMapping.map(pair[0]), pair[1], Integer::sum);
				}
			}
		}
		return held;
	}

	/** What this player owes per item base: things borrowed, and collateral held for others. */
	public Map<Integer, Integer> owedByBase()
	{
		return totalOwedByBase();
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
			proofScreenshot.cacheTradeFrame(ProofScreenshot.PHASE_CONFIRM_SCREEN, InterfaceID.Tradeconfirm.UNIVERSE);
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
		proofScreenshot.cacheTradeFrame(ProofScreenshot.PHASE_FIRST_SCREEN, InterfaceID.Trademain.UNIVERSE);
		screenshotCached = true;
	}

	/** Does the current screen-1 state look like a loan/return in progress? */
	private boolean firstScreenRelevant()
	{
		if (autoLoanAll || collatMode || !markedItemIds.isEmpty())
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
		if (!markedItemIds.isEmpty() || collatMode || hasOpenLoanWithPartner())
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
		// No popup needed when the Loan button already covers the whole trade, and
		// never in Collat mode — those items are a deposit, not a lend.
		if (!tradeOpen || autoLoanAll || collatMode || loanPromptShown || promptOpen || offerItems == null
			|| !config.promptListedLoans())
		{
			return;
		}

		// Only prompt for trades with a member of the lending group, or of the
		// in-game clan the group is linked to (clan-mates on mobile can't join the
		// group) — selling a listed item to a stranger is a sale, not a loan. If the
		// partner name hasn't resolved yet, DON'T burn the once-per-trade prompt:
		// the resolved name triggers a re-check (see schedulePartnerRead).
		if (partner == null || !(isPartnerInActiveGroup() || isPartnerInLinkedClan()))
		{
			return;
		}

		// Items I owe back to THIS partner (I'm their borrower) are returns, not
		// new lends — a return trade must never trip the loan popup. This is the
		// role fix for "traded back and it asked/recorded like I was lending".
		Set<Integer> owedBases = baseIdsIOweTo(partner);

		List<String> listedNames = new ArrayList<>();
		for (Item item : offerItems)
		{
			if (item != null && item.getId() > 0 && isListedForLending(item.getId())
				&& !owedBases.contains(familyOf.applyAsInt(item.getId())))
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

		// Still have live buttons attached to the interface? Keep them. (A null
		// collatButton with a live loanButton means it was deliberately skipped
		// for lack of room — don't loop trying to recreate it.)
		if (loanButton != null && loanButton.getParent() != null
			&& (collatButton == null || collatButton.getParent() != null)
			&& (daysButton == null || daysButton.getParent() != null))
		{
			return;
		}
		loanButton = null;
		loanButtonBg = null;
		collatButton = null;
		collatButtonBg = null;
		daysButton = null;
		daysButtonBg = null;

		// Prefer the middle column layer (holds Accept/Decline); fall back outward
		Widget parent = firstNonNull(
			client.getWidget(InterfaceID.Trademain.MIDDLE),
			client.getWidget(InterfaceID.Trademain.UNIVERSE),
			client.getWidget(InterfaceID.Trademain.WHOLESCREEN));
		if (parent == null)
		{
			return;
		}

		int pw = parent.getWidth();
		int ph = parent.getHeight();
		int width = Math.max(90, Math.min(pw > 0 ? pw - 4 : 120, 120));
		int height = 16;
		int x = Math.max(0, (pw - width) / 2);
		// The middle column has two clear gaps: above Accept (under the free-slots
		// box) and between Accept and Decline. Loan goes between Accept and Decline,
		// Days above Accept, Collat at the very bottom. (Second from the bottom is
		// where "Other player has accepted." appears, so nothing sits there.) With
		// no usable gap: the old stack, Loan at the bottom and Collat just above.
		int bottomY = Math.max(2, ph - height - 4);
		int[] gaps = middleGaps(parent, height);
		int loanY = gaps[1] >= 0 ? gaps[1] : bottomY;
		int collatY = gaps[1] >= 0 ? bottomY : Math.max(2, bottomY - height - 2);

		Widget[] loan = createTradeButton(parent, x, loanY, width, height,
			loanButtonText(), loanButtonColor(), this::toggleLoanMode);
		loanButtonBg = loan[0];
		loanButton = loan[1];

		// Only add the Collat button when it can't overlap the Loan button - in a
		// too-short fallback layer the two would collide, which is worse than the
		// borrower using the popup flow.
		if (Math.abs(collatY - loanY) >= height + 2)
		{
			Widget[] collat = createTradeButton(parent, x, collatY, width, height,
				collatButtonText(collatMode), collatMode ? 0x00ff00 : 0xffff00, this::toggleCollatMode);
			collatButtonBg = collat[0];
			collatButton = collat[1];

			// Days above Accept; failing that, the first other clear spot. Never on
			// top of Accept or Decline.
			int daysY = gaps[0] >= 0 ? gaps[0] : freeSlot(parent, Math.min(loanY, collatY), height);
			if (daysY >= 0)
			{
				Widget[] days = createTradeButton(parent, x, daysY, width, height,
					daysButtonText(), 0x00ffff, this::cycleLoanDays);
				daysButtonBg = days[0];
				daysButton = days[1];
			}
		}
	}

	/** Build one background+text pseudo-button pair on the trade interface. */
	private Widget[] createTradeButton(Widget parent, int x, int y, int width, int height,
		String text, int color, Runnable onClick)
	{
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
		bg.setOnOpListener((JavaScriptCallback) ev -> onClick.run());
		bg.revalidate();

		Widget btn = parent.createChild(-1, WidgetType.TEXT);
		btn.setText(text);
		btn.setTextColor(color);
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
		btn.setOnOpListener((JavaScriptCallback) ev -> onClick.run());
		btn.revalidate();

		return new Widget[] { bg, btn };
	}

	/**
	 * Centred y positions (relative to parent) for a button of this height in the
	 * middle column's two gaps: [0] above Accept, below the free-slots box; [1]
	 * between Accept and Decline. -1 where the gap is too small or can't be seen.
	 */
	private int[] middleGaps(Widget parent, int height)
	{
		int[] out = { -1, -1 };
		int[] free = span(parent, InterfaceID.Trademain.FREESPACE_CONTAINER);
		int[] accept = span(parent, InterfaceID.Trademain.ACCEPT);
		int[] decline = span(parent, InterfaceID.Trademain.DECLINE);
		if (accept != null)
		{
			int top = free != null && free[1] <= accept[0] ? free[1] : 0;
			int gap = accept[0] - top;
			if (gap >= height + 4)
			{
				out[0] = top + (gap - height) / 2;
			}
		}
		if (accept != null && decline != null)
		{
			int gap = decline[0] - accept[1];
			if (gap >= height + 4)
			{
				out[1] = accept[1] + (gap - height) / 2;
			}
		}
		return out;
	}

	/** {top, bottom} of a widget relative to parent, or null if it isn't showing. */
	private int[] span(Widget parent, int id)
	{
		Widget w = client.getWidget(id);
		net.runelite.api.Point origin = parent.getCanvasLocation();
		if (w == null || w.isHidden() || origin == null || w.getCanvasLocation() == null)
		{
			return null;
		}
		int y = w.getCanvasLocation().getY() - origin.getY();
		return new int[] { y, y + w.getHeight() };
	}

	/**
	 * A y (relative to parent) where a button of this height fits in the trade
	 * window's middle column without covering Accept or Decline: just above
	 * `below` if that's clear of both, else above both.
	 * -1 when there's no such room - the loan then just uses the default length.
	 */
	private int freeSlot(Widget parent, int below, int height)
	{
		net.runelite.api.Point origin = parent.getCanvasLocation();
		int top = Integer.MAX_VALUE;
		int bottom = Integer.MIN_VALUE;
		for (int id : new int[] { InterfaceID.Trademain.ACCEPT, InterfaceID.Trademain.DECLINE })
		{
			Widget w = client.getWidget(id);
			if (w == null || w.isHidden() || origin == null || w.getCanvasLocation() == null)
			{
				continue;
			}
			int y = w.getCanvasLocation().getY() - origin.getY();
			top = Math.min(top, y);
			bottom = Math.max(bottom, y + w.getHeight());
		}
		int under = below - height - 2;
		if (top == Integer.MAX_VALUE)
		{
			// Can't see them - only use the lower half, where they never are
			return under >= parent.getHeight() / 2 ? under : -1;
		}
		if (under >= bottom + 2)
		{
			return under;
		}
		int over = top - height - 2;
		return over >= 2 ? over : -1;
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
		// Cycle OFF -> LIST -> 1-TIME -> OFF, so the lender picks per loan whether
		// the item goes (back) on the marketplace when it's returned.
		if (!autoLoanAll)
		{
			autoLoanAll = true;
			loanOneTime = false;
		}
		else if (!loanOneTime)
		{
			loanOneTime = true;
		}
		else
		{
			autoLoanAll = false;
			loanOneTime = false;
		}
		// You're either the lender or the borrower in a trade — not both
		if (autoLoanAll && collatMode)
		{
			collatMode = false;
			refreshCollatButton();
			addGameMessage("Collat mode off — you're lending in this trade.");
		}
		refreshLoanButton();
		if (autoLoanAll)
		{
			addGameMessage(loanOneTime
				? "One-time loan mode — everything you hand over is recorded as a loan to " + partner
					+ ", and will NOT be listed on the marketplace when returned."
				: "Loan mode ON — everything you hand over is recorded as a loan to " + partner
					+ " and relists on the group marketplace when returned.");
			maybeCacheFirstScreen();
		}
		else
		{
			addGameMessage("Loan mode off for this trade.");
		}
	}

	private void toggleCollatMode()
	{
		if (!tradeOpen || partner == null)
		{
			return;
		}
		collatMode = !collatMode;
		if (collatMode && autoLoanAll)
		{
			autoLoanAll = false;
			loanOneTime = false;
			refreshLoanButton();
			addGameMessage("Loan mode off — you're borrowing in this trade.");
		}
		refreshCollatButton();
		addGameMessage(collatMode
			? "Collat mode ON — items you hand over in this trade are your collateral deposit for a borrow from "
				+ partner + ", not a loan. They will never appear on your marketplace."
			: "Collat mode off for this trade.");
		if (collatMode)
		{
			maybeCacheFirstScreen();
		}
	}

	private void refreshLoanButton()
	{
		if (loanButton != null)
		{
			loanButton.setText(loanButtonText());
			loanButton.setTextColor(loanButtonColor());
		}
	}

	private void refreshCollatButton()
	{
		if (collatButton != null)
		{
			collatButton.setText(collatButtonText(collatMode));
			collatButton.setTextColor(collatMode ? 0x00ff00 : 0xffff00);
		}
	}

	private String loanButtonText()
	{
		if (!autoLoanAll)
		{
			return "Loan: OFF";
		}
		return loanOneTime ? "Loan: 1-TIME" : "Loan: LIST";
	}

	private int loanButtonColor()
	{
		if (!autoLoanAll)
		{
			return 0xffff00;
		}
		return loanOneTime ? 0xff9900 : 0x00ff00;
	}

	/** Loan length for this trade: the Days button's pick, else the configured default. */
	private int effectiveLoanDays()
	{
		return loanDays != 0 ? loanDays : Math.max(1, config.defaultLoanDuration());
	}

	/** Due time for a loan of this many days, or 0 (no due date) for no limit. */
	private static long dueTimeFor(int days)
	{
		return days == NO_LIMIT ? 0L : System.currentTimeMillis() + Math.max(1, days) * 86400000L;
	}

	/** "3 days" / "1 day" / "no time limit", for chat messages. */
	private static String daysLabel(int days)
	{
		return days == NO_LIMIT ? "no time limit" : days + (days == 1 ? " day" : " days");
	}

	private String daysButtonText()
	{
		int days = effectiveLoanDays();
		return days == NO_LIMIT ? "Days: No limit" : "Days: " + days;
	}

	/** Step to the next length: 1 to 7 one at a time, then No limit, then back to 1. */
	private void cycleLoanDays()
	{
		int current = effectiveLoanDays();
		int next = DAY_PRESETS[0];
		for (int i = 0; i < DAY_PRESETS.length; i++)
		{
			if (DAY_PRESETS[i] == current)
			{
				next = DAY_PRESETS[(i + 1) % DAY_PRESETS.length];
				break;
			}
		}
		loanDays = next;
		if (daysButton != null)
		{
			daysButton.setText(daysButtonText());
		}
	}

	private static String collatButtonText(boolean on)
	{
		return on ? "Collat: ON" : "Collat: OFF";
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
		// Logout hook: forget the bank snapshot too — guards fall back to cautious
		// until this (possibly different) account opens its own bank.
		bankQtyByBase.clear();
		bankKnown = false;
	}

	/** The bank container changed (it's open) — snapshot quantities per base id. */
	public void onBankUpdated(Item[] items)
	{
		bankQtyByBase.clear();
		bankKnown = true;
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (item != null && item.getId() > 0)
			{
				bankQtyByBase.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
	}

	/** Is this item (or a variant of it) listed by me on the group marketplace? */
	private boolean isListedForLending(int itemId)
	{
		// Raw GP can never be a lendable item, even when someone listed "Coins" on
		// the marketplace — treating offered coins as a loan would double-count GP
		// the return tally already consumed as collateral coming home.
		if (itemId == ItemID.COINS_995 || itemId == ItemID.PLATINUM_TOKEN)
		{
			return false;
		}
		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || group == null)
		{
			return false;
		}
		// Grouped the way listings are, so notes of a listed item count as listed.
		int family = familyOf.applyAsInt(itemId);
		for (LendingEntry offering : dataService.getOfferingsByOwner(group.getId(), me))
		{
			if (familyOf.applyAsInt(offering.getItemId()) == family)
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
					proofScreenshot.cacheTradeFrame(ProofScreenshot.PHASE_CONFIRM_SCREEN, InterfaceID.Tradeconfirm.UNIVERSE);
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
		loanOneTime = false;
		collatMode = false;
		loanButton = null; // the widgets die with the trade interface
		loanButtonBg = null;
		collatButton = null;
		collatButtonBg = null;
		daysButton = null;
		daysButtonBg = null;
		loanDays = 0;
		scamCheckedFor = null;
		scamWarning = null;
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
			if (guardScamAccept(event))
			{
				return true;
			}
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

	// --- Lookalike names ---

	/** A trade partner whose name only looks like someone this player knows. */
	public static final class ScamWarning
	{
		public final String partnerName;        // as the trade window shows it
		public final String realName;           // who they look like
		public final boolean loanParty;         // the real one is someone you have a loan with
		public final boolean borrowedFromReal;  // ...and you borrowed from them
		public final String detail;             // one line on who the real one is
		public final String difference;         // how the names differ, in words, or null

		ScamWarning(String partnerName, String realName, boolean loanParty, boolean borrowedFromReal, String detail)
		{
			this.partnerName = partnerName;
			this.realName = realName;
			this.loanParty = loanParty;
			this.borrowedFromReal = borrowedFromReal;
			this.detail = detail;
			this.difference = LookalikeNames.describeDifference(partnerName, realName);
		}
	}

	private String scamCheckedFor;
	private ScamWarning scamWarning;

	/**
	 * The lookalike warning for the trade that's open, or null. Worked out once
	 * per partner, and announced once - in chat, and as a RuneLite notification
	 * so the client flashes. Client thread only: the overlay and menu clicks.
	 */
	public ScamWarning currentScamWarning()
	{
		if (!tradeOpen || partner == null || !config.lookalikeWarning())
		{
			return null;
		}
		if (partner.equals(scamCheckedFor))
		{
			return scamWarning;
		}
		scamCheckedFor = partner;
		scamWarning = findLookalike(partner);
		if (scamWarning != null)
		{
			addGameMessage("POSSIBLE SCAM: " + scamWarning.partnerName + " is NOT " + scamWarning.realName
				+ (scamWarning.difference != null ? " - " + scamWarning.difference : "")
				+ " (" + scamWarning.detail + "). Check carefully before you hand anything over.");
			notifier.notify("Lending Tracker: possible scam trade - " + scamWarning.partnerName
				+ " is not " + scamWarning.realName);
		}
		return scamWarning;
	}

	private ScamWarning findLookalike(String name)
	{
		// Someone you have a loan with comes first, and counts even when the
		// partner is a member too - handing Guess34's gear to Guess 34 is the scam.
		String me = localPlayerName();
		if (me != null)
		{
			ScamWarning lentTo = null;
			for (LendingEntry e : dataService.getActiveEntries())
			{
				boolean iBorrowed = me.equalsIgnoreCase(e.getBorrower());
				boolean iLent = me.equalsIgnoreCase(e.getLender());
				String other = iBorrowed ? e.getLender() : iLent ? e.getBorrower() : null;
				if (other == null || !LookalikeNames.looksLike(name, other))
				{
					continue;
				}
				if (iBorrowed)
				{
					return new ScamWarning(name, other, true, true, other + " lent you " + e.getItemName());
				}
				if (lentTo == null)
				{
					lentTo = new ScamWarning(name, other, true, false, "you lent " + e.getItemName() + " to " + other);
				}
			}
			if (lentTo != null)
			{
				return lentTo;
			}
		}

		// Otherwise only a partner who ISN'T a member themselves, looking like one.
		List<String> groupNames = new ArrayList<>();
		LendingGroup group = groupService.getActiveGroup();
		if (group != null && group.getMembers() != null)
		{
			for (com.guess34.lendingtracker.model.GroupMember m : group.getMembers())
			{
				if (m != null && m.getName() != null)
				{
					groupNames.add(m.getName());
				}
			}
		}
		clanRoster.refresh();
		Set<String> clan = clanRoster.memberKeys();
		if (clan.contains(LookalikeNames.accountKey(name)))
		{
			return null;
		}
		for (String n : groupNames)
		{
			if (LookalikeNames.sameAccount(name, n))
			{
				return null;
			}
		}
		for (String n : groupNames)
		{
			if (LookalikeNames.looksLike(name, n))
			{
				return new ScamWarning(name, n, false, false, n + " is in your lending group");
			}
		}
		for (String k : clan)
		{
			if (LookalikeNames.looksLike(name, k))
			{
				return new ScamWarning(name, k, false, false, k + " is in your clan");
			}
		}
		return null;
	}

	/**
	 * Accepting while handing items you borrowed from X to someone whose name only
	 * looks like X: stopped every time, whatever the guard setting, because that
	 * is exactly how a lookalike account walks off with borrowed gear. Shift lets
	 * a player through who has checked the name.
	 */
	private boolean guardScamAccept(MenuOptionClicked event)
	{
		ScamWarning scam = currentScamWarning();
		if (scam == null || !scam.borrowedFromReal || client.isKeyPressed(KeyCode.KC_SHIFT))
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
			String real = item != null && item.getId() > 0 ? realLenderOfOfferedItem(item.getId()) : null;
			if (real != null)
			{
				event.consume();
				String difference = LookalikeNames.describeDifference(partner, real);
				addGameMessage("BLOCKED - possible scam: " + partner + " is NOT " + real
					+ (difference != null ? " - " + difference : "")
					+ ". " + real + " lent you " + itemName(item.getId())
					+ ". Hold Shift and click Accept only if you are sure.");
				return true;
			}
		}
		return false;
	}

	/**
	 * The lender you borrowed this item from whose name the partner only LOOKS
	 * like - or null if there's none, or if the partner really did lend you one of
	 * these. Every open loan of the item counts, and each lender is compared with
	 * the partner directly, so an impostor who resembles two of your lenders can't
	 * slip one lender's item past a check made against the other.
	 */
	private String realLenderOfOfferedItem(int itemId)
	{
		String me = localPlayerName();
		if (me == null || partner == null)
		{
			return null;
		}
		int baseId = ItemVariationMapping.map(itemId);
		String real = null;
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (e.isReturned() || !me.equalsIgnoreCase(e.getBorrower()) || ItemVariationMapping.map(e.getItemId()) != baseId)
			{
				continue;
			}
			if (LookalikeNames.sameAccount(e.getLender(), partner))
			{
				return null; // the partner really did lend you one of these
			}
			if (real == null && LookalikeNames.looksLike(partner, e.getLender()))
			{
				real = e.getLender();
			}
		}
		return real;
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

		// Handing the real lender's item to a name that only looks like theirs -
		// say so loudly. The Accept is what gets blocked (guardScamAccept).
		ScamWarning scam = currentScamWarning();
		String realLender = scam != null && scam.borrowedFromReal ? realLenderOfOfferedItem(itemId) : null;
		if (realLender != null)
		{
			String difference = LookalikeNames.describeDifference(partner, realLender);
			addGameMessage("STOP: " + partner + " is NOT " + realLender
				+ (difference != null ? " - " + difference : "")
				+ ". " + realLender + " lent you " + itemName(itemId)
				+ ". Accepting this trade will be blocked - hold Shift only if you are sure.");
			// Nothing reassuring after that - the Accept guard does the blocking.
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

		// Fungible duplicates: if the bank + what you'd still hold after this
		// trade covers everything you owe of this item, you're trading your OWN
		// spare — no warning needed.
		if (tradeAwayCovered(baseId))
		{
			if (warnedBaseIds.add(baseId))
			{
				addGameMessage(itemName(itemId) + " matches a borrowed item, but your remaining copies still cover what you owe — trading your spare is fine.");
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
				// Trading a covered spare of your own is fine too
				if (tradeAwayCovered(ItemVariationMapping.map(item.getId())))
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

		Map<Integer, Integer> carried = carriedQuantitiesByBase();
		Map<Integer, Integer> owedByBase = totalOwedByBase();

		List<LendingEntry> result = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isReturned() && me.equalsIgnoreCase(e.getBorrower())
				&& e.outstandingLentQty() > 0
				&& atRiskCarrying(carried, owedByBase, ItemVariationMapping.map(e.getItemId())))
			{
				result.add(e);
			}
		}
		return result;
	}

	/**
	 * Items are fungible: what matters is never WHICH copy you hold, only that
	 * you can still cover what you owe. Carrying a base item is AT RISK only when
	 * the bank no longer covers the TOTAL owed of that base across ALL open
	 * obligations — so someone with a spare in the bank can freely carry their
	 * own copy, but one banked spare can never vouch for two debts at once.
	 * Bank contents are only known after the bank has been opened this session;
	 * until then banked counts as 0 (the cautious old behavior).
	 */
	private boolean atRiskCarrying(Map<Integer, Integer> carried, Map<Integer, Integer> owedByBase, int baseId)
	{
		int owed = owedByBase.getOrDefault(baseId, 0);
		if (owed <= 0)
		{
			return false;
		}
		int carriedQty = carried.getOrDefault(baseId, 0);
		if (carriedQty <= 0)
		{
			return false;
		}
		int banked = bankKnown ? bankQtyByBase.getOrDefault(baseId, 0) : 0;
		return banked < owed;
	}

	/**
	 * TOTAL quantity owed per variation-base id across ALL my open obligations:
	 * items I borrowed (owed back to their lenders) plus collateral I hold (owed
	 * back to borrowers). Aggregated so coverage checks can't double-count one
	 * banked spare against several debts of the same item.
	 */
	private Map<Integer, Integer> totalOwedByBase()
	{
		Map<Integer, Integer> owed = new HashMap<>();
		String me = localPlayerName();
		if (me == null)
		{
			return owed;
		}
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (me.equalsIgnoreCase(e.getBorrower()))
			{
				int q = e.outstandingLentQty();
				if (q > 0)
				{
					owed.merge(ItemVariationMapping.map(e.getItemId()), q, Integer::sum);
				}
			}
			if (me.equalsIgnoreCase(e.getLender()))
			{
				for (int[] pair : parseIdQtyPairs(e.outstandingCollateralIds()))
				{
					owed.merge(ItemVariationMapping.map(pair[0]), pair[1], Integer::sum);
				}
			}
		}
		return owed;
	}

	/**
	 * May I trade a copy of this base item AWAY (to a non-lender) without going
	 * below what I owe? True when the bank plus what I'd still be carrying after
	 * the trade covers the total debt of that base. Offered items have already
	 * left the inventory container, so carried here is the post-trade remainder.
	 */
	private boolean tradeAwayCovered(int baseId)
	{
		if (!bankKnown)
		{
			return false;
		}
		int owed = totalOwedByBase().getOrDefault(baseId, 0);
		if (owed <= 0)
		{
			return true;
		}
		int banked = bankQtyByBase.getOrDefault(baseId, 0);
		int carried = carriedQuantitiesByBase().getOrDefault(baseId, 0);
		return banked + carried >= owed;
	}

	/** Carried (inventory + worn) quantities per variation-base id. */
	private Map<Integer, Integer> carriedQuantitiesByBase()
	{
		Map<Integer, Integer> carried = new HashMap<>();
		collectBaseQuantities(carried, InventoryID.INV);
		collectBaseQuantities(carried, InventoryID.WORN);
		return carried;
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

		Map<Integer, Integer> carried = carriedQuantitiesByBase();
		Map<Integer, Integer> owedByBase = totalOwedByBase();

		List<LendingEntry> result = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (e.isReturned() || !me.equalsIgnoreCase(e.getLender()))
			{
				continue;
			}
			for (int[] pair : parseIdQtyPairs(e.outstandingCollateralIds()))
			{
				if (atRiskCarrying(carried, owedByBase, ItemVariationMapping.map(pair[0])))
				{
					result.add(e);
					break;
				}
			}
		}
		return result;
	}

	/** Parse an "itemId:qty,itemId:qty" string into [id, qty] pairs (qty >= 1). */
	private static List<int[]> parseIdQtyPairs(String raw)
	{
		List<int[]> pairs = new ArrayList<>();
		if (raw == null || raw.isEmpty())
		{
			return pairs;
		}
		for (String pair : raw.split(","))
		{
			try
			{
				int idx = pair.indexOf(':');
				int id = Integer.parseInt(idx > 0 ? pair.substring(0, idx) : pair);
				int qty = idx > 0 ? Integer.parseInt(pair.substring(idx + 1)) : 1;
				pairs.add(new int[] { id, Math.max(1, qty) });
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		return pairs;
	}

	/** Join [id, qty] pairs back into the "itemId:qty,..." wire format. */
	private static String joinIdQtyPairs(List<int[]> pairs)
	{
		StringBuilder sb = new StringBuilder();
		for (int[] p : pairs)
		{
			if (p[1] <= 0)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(p[0]).append(':').append(p[1]);
		}
		return sb.toString();
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

			// Match this trade against open loans FIRST (running tally, both sides):
			// what came home is a return, and returns are excluded from everything
			// below so they can never be re-recorded as new lends or collateral.
			ReturnTally tally = applyReturnTallies();

			// What of mine left and what of mine came home, for listings following
			// the bank - before anything below relists a return, which would
			// otherwise be counted twice. Handing back something that was never
			// mine (a borrowed item, someone's collateral) is neither, and nor is
			// getting it: the tally counts the loans this client records, and the
			// loans the lender records are added here.
			if (onTradeSettled != null)
			{
				String me = localPlayerName();
				Map<Integer, Integer> notMine = new HashMap<>(tally.consumedMyQtyByBase);
				Map<Integer, Integer> mine = new HashMap<>(tally.consumedTheirQtyByBase);
				for (LendingEntry e : dataService.getActiveEntries())
				{
					if (e.isKeptByBorrower() || me == null || !me.equalsIgnoreCase(e.getBorrower())
						|| !partner.equalsIgnoreCase(e.getLender()))
					{
						continue;
					}
					if (e.outstandingLentQty() > 0)
					{
						notMine.merge(ItemVariationMapping.map(e.getItemId()), e.outstandingLentQty(), Integer::sum);
					}
					for (int[] pair : parseIdQtyPairs(e.outstandingCollateralIds()))
					{
						mine.merge(ItemVariationMapping.map(pair[0]), pair[1], Integer::sum);
					}
				}
				Map<Integer, Integer> left = offerCounts(finalMyOffer, notMine, false);
				Map<Integer, Integer> cameHome = offerCounts(finalTheirOffer, mine, true);
				if (!left.isEmpty() || !cameHome.isEmpty())
				{
					try
					{
						onTradeSettled.accept(left, cameHome);
					}
					catch (Exception ex)
					{
						// Listings catch up when the bank is next opened; the loans
						// and returns below must still be recorded.
						log.warn("Couldn't move listings for a trade", ex);
					}
				}
			}

			// Loans = everything I offered (Loan button ON) ∪ explicitly marked
			// items ∪ (listed items, if the popup was answered yes). An unanswered
			// popup stashes the listed items so a late "yes" can still record them.
			// Raw GP is NEVER a lendable item — coins/platinum in a loan trade are
			// collateral (or its return, or payment); recording a "Coins loan" would
			// double-count the same GP the tally just consumed.
			Set<Integer> loanItemIds = new HashSet<>(markedItemIds);
			loanItemIds.remove(ItemID.COINS_995);
			loanItemIds.remove(ItemID.PLATINUM_TOKEN);
			if (autoLoanAll && finalMyOffer != null)
			{
				for (Item item : finalMyOffer)
				{
					if (item != null && item.getId() > 0
						&& item.getId() != ItemID.COINS_995 && item.getId() != ItemID.PLATINUM_TOKEN)
					{
						loanItemIds.add(item.getId());
					}
				}
			}
			if (Boolean.TRUE.equals(autoLoanAccepted))
			{
				loanItemIds.addAll(listedItemIdsInFinalOffer());
			}

			// Collat mode: everything I handed over is my deposit for a borrow —
			// nothing of mine may be recorded as a loan.
			if (collatMode && !loanItemIds.isEmpty())
			{
				loanItemIds.clear();
			}
			// Items I owe back to this partner are returns in progress, never new
			// lends — the lender's client records the tally, mine just stays quiet.
			if (!loanItemIds.isEmpty())
			{
				Set<Integer> owedBases = baseIdsIOweTo(partner);
				if (!owedBases.isEmpty())
				{
					loanItemIds.removeIf(id -> owedBases.contains(familyOf.applyAsInt(id)));
				}
			}

			// Each group chooses whose loans it tracks (clan / group / anyone).
			// Returns above still settle - an existing loan must always be closable.
			LendingGroup activeGroup = groupService.getActiveGroup();
			String outsideWhy = activeGroup != null ? outsideLoanScope(activeGroup.getId(), partner) : null;
			boolean keepingBorrow = collatMode && !isPartnerInActiveGroup();
			if (outsideWhy != null && (!loanItemIds.isEmpty() || keepingBorrow))
			{
				addGameMessage(warnPrefix() + outsideWhy + " - this trade was NOT recorded as a loan.");
				loanItemIds.clear();
				keepingBorrow = false;
			}

			List<LendingEntry> newLoans = recordLoans(loanItemIds, tally);
			// Collat mode with someone outside the group: they can't record it, so we do.
			List<LendingEntry> keptLoans = keepingBorrow ? recordKeptLoans() : new ArrayList<>();

			PendingLoanDecision stashedNow = null;
			if (!collatMode && loanPromptShown && autoLoanAccepted == null)
			{
				stashedNow = stashPendingDecision(loanItemIds, tally);
			}

			// One Discord post per trade, whatever number of items changed hands.
			// Taken before the screenshot commit/discard below, which clear it.
			java.awt.image.BufferedImage tradePicture = discordWebhook.wantsScreenshot()
				? proofScreenshot.takeProofCrop() : null;
			discordWebhook.postBatch(DiscordWebhook.Event.LOAN, newLoans, localPlayerName(), null, tradePicture);
			discordWebhook.postBatch(DiscordWebhook.Event.LOAN, keptLoans, localPlayerName(), null, tradePicture);
			// Loans I lent and loans I kept as the borrower have opposite lender and
			// borrower, so they are separate posts even when one trade settles both.
			List<LendingEntry> closedLent = new ArrayList<>();
			List<LendingEntry> closedKept = new ArrayList<>();
			for (LendingEntry e : tally.closed)
			{
				(e.isKeptByBorrower() ? closedKept : closedLent).add(e);
			}
			discordWebhook.postBatch(DiscordWebhook.Event.RETURNED, closedLent, localPlayerName(), null, tradePicture);
			discordWebhook.postBatch(DiscordWebhook.Event.RETURNED, closedKept, localPlayerName(), null, tradePicture);
			// Anything a trade settled only part of gets its own post listing what is
			// still out. Stamped per trade, so each part-return posts once.
			List<LendingEntry> partLent = new ArrayList<>();
			List<LendingEntry> partKept = new ArrayList<>();
			for (LendingEntry e : tally.progressed)
			{
				(e.isKeptByBorrower() ? partKept : partLent).add(e);
			}
			String tradeStamp = String.valueOf(System.currentTimeMillis());
			discordWebhook.postBatch(DiscordWebhook.Event.PARTIAL, partLent, localPlayerName(), tradeStamp, tradePicture);
			discordWebhook.postBatch(DiscordWebhook.Event.PARTIAL, partKept, localPlayerName(), tradeStamp, tradePicture);

			Set<String> relistedNow = new HashSet<>();
			for (LendingEntry e : tally.closed)
			{
				dataService.completeEntry(e.getId(), true);
				// A loan I kept as the borrower went home to its owner - it was
				// never mine to put on the marketplace.
				if (!e.isKeptByBorrower())
				{
					relistReturnedItem(e, relistedNow);
				}
				String collat = collateralDescription(e);
				addGameMessage("Return complete: " + e.getItemName() + " — loan with " + partner + " fully settled."
					+ (collat != null ? " Collateral returned in full: " + collat + "." : ""));
			}
			for (LendingEntry e : tally.progressed)
			{
				dataService.updateEntryProgress(e);
				// Not a return until EVERYTHING is back. Say exactly what's missing;
				// the rest can come in any later trade, even one where the other
				// side hands over nothing, and the loan completes on its own then.
				addGameMessage(warnPrefix() + "Return NOT complete - the " + e.getItemName() + " loan with "
					+ partner + " stays open. Missing: " + missingSummary(e)
					+ ". Trade the rest any time and it completes automatically.");
			}

			if (collatMode)
			{
				if (keptLoans.isEmpty())
				{
					addGameMessage("Collateral deposit noted — the loan record comes from " + partner + "'s client.");
				}
				// The DEPOSITOR deserves their own on-disk proof of what they handed
				// over — the lender's screenshot is no help to the borrower in a
				// dispute against that lender.
				if (config.enableTradeScreenshots())
				{
					LendingEntry deposit = provisionalDepositEntry();
					if (deposit != null)
					{
						proofScreenshot.commitCachedTrade(localPlayerName(), activeGroupName(), partner, "DEPOSIT", deposit);
						screenshotCached = false;
					}
				}
			}

			if (!newLoans.isEmpty() || tally.any())
			{
				// commitCachedTrade prefers the frame cached on the confirm screen;
				// if none was cached (e.g. the partner name resolved too late) it
				// falls back to capturing the next frame — weaker evidence, but
				// better than nothing
				if (config.enableTradeScreenshots())
				{
					LendingEntry subject = !newLoans.isEmpty() ? newLoans.get(0)
						: !tally.closed.isEmpty() ? tally.closed.get(0) : tally.progressed.get(0);
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

			if ((!newLoans.isEmpty() || !keptLoans.isEmpty() || tally.any()) && onLoanRecorded != null)
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
	 * An offer's items by id, coins and platinum left out. Per variation base,
	 * `limits` says how many to keep (keepOnly) or how many to leave out; it is
	 * used up as it goes.
	 */
	private static Map<Integer, Integer> offerCounts(Item[] offer, Map<Integer, Integer> limits, boolean keepOnly)
	{
		Map<Integer, Integer> out = new HashMap<>();
		if (offer == null)
		{
			return out;
		}
		for (Item item : offer)
		{
			if (item == null || item.getId() <= 0
				|| item.getId() == ItemID.COINS_995 || item.getId() == ItemID.PLATINUM_TOKEN)
			{
				continue;
			}
			int base = ItemVariationMapping.map(item.getId());
			int matched = Math.min(Math.max(0, limits.getOrDefault(base, 0)), item.getQuantity());
			limits.merge(base, -matched, Integer::sum);
			int count = keepOnly ? matched : item.getQuantity() - matched;
			if (count > 0)
			{
				out.merge(item.getId(), count, Integer::sum);
			}
		}
		return out;
	}

	/** Outcome of matching one completed trade against the open loans with this partner. */
	private static final class ReturnTally
	{
		final List<LendingEntry> closed = new ArrayList<>();      // fully settled, both sides home
		final List<LendingEntry> progressed = new ArrayList<>();  // partial — still open, tallies updated
		// What this trade consumed as returns, per variation-base id — used to keep
		// those quantities out of new-loan / collateral aggregation.
		final Map<Integer, Integer> consumedTheirQtyByBase = new HashMap<>();
		final Map<Integer, Integer> consumedMyQtyByBase = new HashMap<>();

		boolean any()
		{
			return !closed.isEmpty() || !progressed.isEmpty();
		}
	}

	/**
	 * Collat mode with a partner who isn't in the group - a clan-mate on mobile,
	 * typically. They can't record the loan, so without this it existed nowhere.
	 * This client records it instead, as the borrower, and becomes its keeper:
	 * what I received is what I borrowed, what I handed over is my collateral.
	 * The keeper flag is what stops anyone else ever tallying or posting it too.
	 */
	private List<LendingEntry> recordKeptLoans()
	{
		List<LendingEntry> created = new ArrayList<>();
		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || partner == null || group == null || finalTheirOffer == null)
		{
			return created;
		}

		// What they handed me. Raw GP is never a lendable item - coins in a
		// borrow trade are theirs to count, not a loan of coins.
		Map<Integer, Integer> borrowed = new LinkedHashMap<>();
		for (Item item : finalTheirOffer)
		{
			if (item != null && item.getId() > 0
				&& item.getId() != ItemID.COINS_995 && item.getId() != ItemID.PLATINUM_TOKEN)
			{
				borrowed.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		if (borrowed.isEmpty())
		{
			return created;
		}

		// What I handed them: my collateral
		long collateralGp = 0;
		List<String> collateralItems = new ArrayList<>();
		List<String> collateralIdPairs = new ArrayList<>();
		if (finalMyOffer != null)
		{
			for (Item item : finalMyOffer)
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
				else
				{
					collateralItems.add(itemName(item.getId()) + (item.getQuantity() > 1 ? " x" + item.getQuantity() : ""));
					collateralIdPairs.add(item.getId() + ":" + item.getQuantity());
				}
			}
		}

		long dueTime = dueTimeFor(effectiveLoanDays());
		boolean first = true;
		for (Map.Entry<Integer, Integer> got : borrowed.entrySet())
		{
			int itemId = got.getKey();
			int quantity = got.getValue();

			LendingEntry entry = new LendingEntry();
			entry.setId(UUID.randomUUID().toString());
			entry.setItem(itemName(itemId));
			entry.setItemId(itemId);
			entry.setQuantity(quantity);
			entry.setValue((long) itemManager.getItemPrice(itemId) * quantity);
			entry.setLentOutstanding(quantity);
			entry.setCollateralOutstandingIds("");
			entry.setCollateralGpOutstanding(0L);
			entry.setKeptByBorrower(true);
			// Collateral covers the whole trade - on the FIRST record only, as the
			// lender side does, so it isn't counted once per item.
			if (first)
			{
				if (collateralGp > 0)
				{
					entry.setCollateralValue((int) Math.min(collateralGp, Integer.MAX_VALUE));
					entry.setCollateralType("GP");
					entry.setCollateralGpOutstanding(collateralGp);
				}
				if (!collateralItems.isEmpty())
				{
					entry.setCollateralItems(String.join(", ", collateralItems));
					entry.setCollateralItemIds(String.join(",", collateralIdPairs));
					entry.setCollateralOutstandingIds(String.join(",", collateralIdPairs));
				}
			}
			dataService.addLoan(group.getId(), partner, me, entry, dueTime);
			created.add(entry);

			addGameMessage("Borrow recorded: " + entry.getItem()
				+ (quantity > 1 ? " x" + quantity : "") + " from " + partner
				+ " (" + daysLabel(effectiveLoanDays())
				+ (first && collateralGp > 0 ? ", collateral " + QuantityFormatter.quantityToStackSize(collateralGp) + " GP" : "")
				+ "). " + partner + " isn't in the group, so your plugin keeps this loan.");
			first = false;
		}
		return created;
	}

	/**
	 * Mirror of the lender-side tally, for loans this client keeps as the
	 * BORROWER: me handing their item back settles what I owe, them handing my
	 * deposit back settles what they owe. Runs before the lender-side pass so the
	 * same physical item can't settle two obligations.
	 */
	private void tallyKeptLoans(ReturnTally tally, String me, Map<Integer, Integer> myByBase,
		Map<Integer, Integer> theirByBase, long[] theirGp)
	{
		List<LendingEntry> kept = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (e.isKeptByBorrower() && !e.isFullySettled()
				&& me.equalsIgnoreCase(e.getBorrower())
				&& partner.equalsIgnoreCase(e.getLender()))
			{
				kept.add(e);
			}
		}
		kept.sort((a, b) -> Long.compare(a.getLendTime(), b.getLendTime()));

		for (LendingEntry e : kept)
		{
			boolean changed = false;

			// Borrowed side: me handing their item back
			int outLent = e.outstandingLentQty();
			if (outLent > 0)
			{
				int baseId = ItemVariationMapping.map(e.getItemId());
				int available = myByBase.getOrDefault(baseId, 0);
				int take = Math.min(available, outLent);
				if (take > 0)
				{
					myByBase.put(baseId, available - take);
					e.setLentOutstanding(outLent - take);
					tally.consumedMyQtyByBase.merge(baseId, take, Integer::sum);
					changed = true;
				}
			}

			// Collateral items side: them handing my deposit back
			List<int[]> collatPairs = parseIdQtyPairs(e.outstandingCollateralIds());
			if (!collatPairs.isEmpty())
			{
				boolean collatChanged = false;
				for (int[] pair : collatPairs)
				{
					int baseId = ItemVariationMapping.map(pair[0]);
					int available = theirByBase.getOrDefault(baseId, 0);
					int take = Math.min(available, pair[1]);
					if (take > 0)
					{
						theirByBase.put(baseId, available - take);
						pair[1] -= take;
						tally.consumedTheirQtyByBase.merge(baseId, take, Integer::sum);
						collatChanged = true;
					}
				}
				if (collatChanged)
				{
					e.setCollateralOutstandingIds(joinIdQtyPairs(collatPairs));
					changed = true;
				}
			}

			// Collateral GP side: them handing my coins back
			long outGp = e.outstandingCollateralGp();
			if (outGp > 0 && theirGp[0] > 0)
			{
				long take = Math.min(theirGp[0], outGp);
				theirGp[0] -= take;
				e.setCollateralGpOutstanding(outGp - take);
				changed = true;
			}

			if (changed)
			{
				e.setLentOutstanding(e.outstandingLentQty());
				e.setCollateralOutstandingIds(e.outstandingCollateralIds());
				e.setCollateralGpOutstanding(e.outstandingCollateralGp());
				if (e.isFullySettled())
				{
					tally.closed.add(e);
				}
				else
				{
					tally.progressed.add(e);
				}
			}
		}
	}

	/**
	 * The running-tally return engine. A loan settles piece by piece, on BOTH
	 * sides: the partner handing lent items back decrements what the borrower
	 * owes, and ME handing their collateral back decrements what I owe. Each
	 * completed trade consumes whatever matched (any quantity, oldest loan
	 * first); a loan only closes when both sides reach zero — so a partial
	 * return records progress but can never mark the loan complete, and a
	 * "return" that keeps the collateral no longer counts as settled.
	 *
	 * Runs on the lender's client (the single writer for the loan's lifecycle,
	 * same as loan recording) and syncs every change live to the group.
	 */
	private ReturnTally applyReturnTallies()
	{
		ReturnTally tally = new ReturnTally();
		String me = localPlayerName();
		if (me == null || partner == null)
		{
			return tally;
		}
		// Collat mode declares this whole trade a NEW borrow: my items are a fresh
		// deposit and the partner's items are what I'm borrowing (recorded on their
		// client). Explicit intent beats auto-matching — consuming either side as
		// returns here would fight the partner's client's interpretation.
		if (collatMode)
		{
			return tally;
		}

		// Pools of what actually changed hands, per variation-base id
		Map<Integer, Integer> theirByBase = new HashMap<>();
		if (finalTheirOffer != null)
		{
			for (Item item : finalTheirOffer)
			{
				if (item != null && item.getId() > 0)
				{
					theirByBase.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
				}
			}
		}
		Map<Integer, Integer> myByBase = new HashMap<>();
		long myGp = 0;
		if (finalMyOffer != null)
		{
			for (Item item : finalMyOffer)
			{
				if (item == null || item.getId() <= 0)
				{
					continue;
				}
				if (item.getId() == ItemID.COINS_995)
				{
					myGp += item.getQuantity();
				}
				else if (item.getId() == ItemID.PLATINUM_TOKEN)
				{
					myGp += item.getQuantity() * 1000L;
				}
				else
				{
					myByBase.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
				}
			}
		}
		if (theirByBase.isEmpty() && myByBase.isEmpty() && myGp == 0)
		{
			return tally;
		}

		// Loans this client keeps as the borrower settle first (see tallyKeptLoans)
		long[] theirGp = { 0 };
		if (finalTheirOffer != null)
		{
			for (Item item : finalTheirOffer)
			{
				if (item != null && item.getId() == ItemID.COINS_995)
				{
					theirGp[0] += item.getQuantity();
				}
				else if (item != null && item.getId() == ItemID.PLATINUM_TOKEN)
				{
					theirGp[0] += item.getQuantity() * 1000L;
				}
			}
		}
		tallyKeptLoans(tally, me, myByBase, theirByBase, theirGp);

		// Reserve quantities I owe THIS partner as their borrower: those hand-overs
		// settle MY debt on their client's records — the same physical item must
		// not also be consumed here as a collateral return on a loan I lent
		// (cross-loans with the same base item would let one item settle two
		// obligations across the two clients).
		for (LendingEntry e : dataService.getActiveEntries())
		{
			// Loans I keep myself were already settled from this pool above
			if (!e.isKeptByBorrower()
				&& me.equalsIgnoreCase(e.getBorrower())
				&& partner.equalsIgnoreCase(e.getLender()))
			{
				int owed = e.outstandingLentQty();
				if (owed > 0)
				{
					int baseId = ItemVariationMapping.map(e.getItemId());
					int pool = myByBase.getOrDefault(baseId, 0);
					if (pool > 0)
					{
						myByBase.put(baseId, Math.max(0, pool - owed));
					}
				}
			}
		}

		List<LendingEntry> candidates = new ArrayList<>();
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isFullySettled()
				&& me.equalsIgnoreCase(e.getLender())
				&& partner.equalsIgnoreCase(e.getBorrower()))
			{
				candidates.add(e);
			}
		}
		candidates.sort((a, b) -> Long.compare(a.getLendTime(), b.getLendTime()));

		for (LendingEntry e : candidates)
		{
			boolean changed = false;

			// Lent side: partner handing my item back
			int outLent = e.outstandingLentQty();
			if (outLent > 0)
			{
				int baseId = ItemVariationMapping.map(e.getItemId());
				int available = theirByBase.getOrDefault(baseId, 0);
				int take = Math.min(available, outLent);
				if (take > 0)
				{
					theirByBase.put(baseId, available - take);
					e.setLentOutstanding(outLent - take);
					tally.consumedTheirQtyByBase.merge(baseId, take, Integer::sum);
					changed = true;
				}
			}

			// Collateral items side: me handing their deposit back
			List<int[]> collatPairs = parseIdQtyPairs(e.outstandingCollateralIds());
			if (!collatPairs.isEmpty())
			{
				boolean collatChanged = false;
				for (int[] pair : collatPairs)
				{
					int baseId = ItemVariationMapping.map(pair[0]);
					int available = myByBase.getOrDefault(baseId, 0);
					int take = Math.min(available, pair[1]);
					if (take > 0)
					{
						myByBase.put(baseId, available - take);
						pair[1] -= take;
						tally.consumedMyQtyByBase.merge(baseId, take, Integer::sum);
						collatChanged = true;
					}
				}
				if (collatChanged)
				{
					e.setCollateralOutstandingIds(joinIdQtyPairs(collatPairs));
					changed = true;
				}
			}

			// Collateral GP side: me handing coins/plat back
			long outGp = e.outstandingCollateralGp();
			if (outGp > 0 && myGp > 0)
			{
				long take = Math.min(myGp, outGp);
				myGp -= take;
				e.setCollateralGpOutstanding(outGp - take);
				changed = true;
			}

			if (changed)
			{
				// Materialize all three tallies so a legacy record that just took its
				// first partial return can't fall back to the old all-or-nothing view
				e.setLentOutstanding(e.outstandingLentQty());
				e.setCollateralOutstandingIds(e.outstandingCollateralIds());
				e.setCollateralGpOutstanding(e.outstandingCollateralGp());
				if (e.isFullySettled())
				{
					tally.closed.add(e);
				}
				else
				{
					tally.progressed.add(e);
				}
			}
		}
		return tally;
	}

	/**
	 * Variation-base ids of items I still owe back to this partner (I'm their
	 * borrower). Anything matching these in MY offer is a return in progress —
	 * never a new lend, never a marketplace-popup candidate.
	 */
	private Set<Integer> baseIdsIOweTo(String partnerName)
	{
		Set<Integer> bases = new HashSet<>();
		String me = localPlayerName();
		if (me == null || partnerName == null)
		{
			return bases;
		}
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (e.outstandingLentQty() > 0
				&& me.equalsIgnoreCase(e.getBorrower())
				&& partnerName.equalsIgnoreCase(e.getLender()))
			{
				// Grouped the way listings are, so handing it back as notes is still
				// a return.
				bases.add(familyOf.applyAsInt(e.getItemId()));
			}
		}
		return bases;
	}

	/** Any open (not fully settled) loan between me and this partner, either direction? */
	private boolean hasOpenLoanWithPartner()
	{
		String me = localPlayerName();
		if (me == null || partner == null)
		{
			return false;
		}
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (e.isFullySettled())
			{
				continue;
			}
			boolean meLender = me.equalsIgnoreCase(e.getLender()) && partner.equalsIgnoreCase(e.getBorrower());
			boolean meBorrower = me.equalsIgnoreCase(e.getBorrower()) && partner.equalsIgnoreCase(e.getLender());
			if (meLender || meBorrower)
			{
				return true;
			}
		}
		return false;
	}

	/** The collateral a loan was given, as recorded (GP and/or items), or null if none. */
	private static String collateralDescription(LendingEntry e)
	{
		List<String> parts = new ArrayList<>();
		if (e.getCollateralValue() != null && e.getCollateralValue() > 0 && "GP".equals(e.getCollateralType()))
		{
			parts.add(QuantityFormatter.quantityToStackSize(e.getCollateralValue()) + " GP");
		}
		if (e.getCollateralItems() != null && !e.getCollateralItems().isEmpty())
		{
			parts.add(e.getCollateralItems());
		}
		return parts.isEmpty() ? null : String.join(" + ", parts);
	}

	/** Who still owes what on a loan, by name - for the incomplete-return warning. */
	private String missingSummary(LendingEntry e)
	{
		List<String> parts = new ArrayList<>();
		int lent = e.outstandingLentQty();
		if (lent > 0)
		{
			parts.add(e.getBorrower() + " still has " + e.getItemName() + (lent > 1 ? " x" + lent : ""));
		}
		List<String> back = new ArrayList<>();
		for (int[] p : parseIdQtyPairs(e.outstandingCollateralIds()))
		{
			if (p[1] > 0)
			{
				back.add(itemName(p[0]) + (p[1] > 1 ? " x" + p[1] : ""));
			}
		}
		long gp = e.outstandingCollateralGp();
		if (gp > 0)
		{
			back.add(QuantityFormatter.quantityToStackSize(gp) + " GP");
		}
		if (!back.isEmpty())
		{
			parts.add(e.getLender() + " still has the collateral: " + String.join(", ", back));
		}
		return parts.isEmpty() ? "nothing" : String.join("; ", parts);
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
	private List<LendingEntry> recordLoans(Set<Integer> loanItemIds, ReturnTally tally)
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
		// Quantities I handed back as COLLATERAL RETURNS are not new lends — the
		// tally consumed them; subtract per variation-base so a mixed trade
		// (returning their deposit while lending something new) records only the
		// genuinely new part.
		if (!tally.consumedMyQtyByBase.isEmpty())
		{
			Map<Integer, Integer> remainingConsumed = new HashMap<>(tally.consumedMyQtyByBase);
			for (Map.Entry<Integer, Integer> lent : new ArrayList<>(lentQuantities.entrySet()))
			{
				int baseId = ItemVariationMapping.map(lent.getKey());
				int consumed = remainingConsumed.getOrDefault(baseId, 0);
				if (consumed <= 0)
				{
					continue;
				}
				int take = Math.min(consumed, lent.getValue());
				remainingConsumed.put(baseId, consumed - take);
				int left = lent.getValue() - take;
				if (left > 0)
				{
					lentQuantities.put(lent.getKey(), left);
				}
				else
				{
					lentQuantities.remove(lent.getKey());
				}
			}
		}
		if (lentQuantities.isEmpty())
		{
			return created;
		}

		// The partner's side of a loan trade is collateral. Coins and platinum
		// tokens count as GP. Quantities the tally consumed as LENT-ITEM RETURNS
		// are exactly that — returns — so only the leftover is collateral.
		Map<Integer, Integer> remainingTheirConsumed = new HashMap<>(tally.consumedTheirQtyByBase);
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
				else
				{
					int baseId = ItemVariationMapping.map(item.getId());
					int consumed = remainingTheirConsumed.getOrDefault(baseId, 0);
					int qty = item.getQuantity();
					int take = Math.min(consumed, qty);
					if (take > 0)
					{
						remainingTheirConsumed.put(baseId, consumed - take);
						qty -= take;
					}
					if (qty > 0)
					{
						collateralItems.add(itemName(item.getId())
							+ (qty > 1 ? " x" + qty : ""));
						collateralIdPairs.add(item.getId() + ":" + qty);
					}
				}
			}
		}

		long dueTime = dueTimeFor(effectiveLoanDays());
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
			// Running tally starts with everything outstanding
			entry.setLentOutstanding(quantity);
			entry.setCollateralOutstandingIds("");
			entry.setCollateralGpOutstanding(0L);
			// Lender chose one-time: tracked like any loan, never (re)listed on return
			entry.setOneTime(autoLoanAll && loanOneTime);
			// Collateral covers the whole trade — attach it to the FIRST loan record
			// only, so several loans from one trade don't multiply the recorded total
			if (first)
			{
				if (collateralGp > 0)
				{
					entry.setCollateralValue((int) Math.min(collateralGp, Integer.MAX_VALUE));
					entry.setCollateralType("GP");
					entry.setCollateralGpOutstanding(collateralGp);
				}
				if (!collateralItems.isEmpty())
				{
					entry.setCollateralItems(String.join(", ", collateralItems));
					entry.setCollateralItemIds(String.join(",", collateralIdPairs));
					entry.setCollateralOutstandingIds(String.join(",", collateralIdPairs));
				}
			}
			dataService.addLoan(group.getId(), me, partner, entry, dueTime);
			adjustListingForLoan(group.getId(), me, itemId, quantity);
			created.add(entry);

			addGameMessage("Loan recorded: " + entry.getItem()
				+ (quantity > 1 ? " x" + quantity : "") + " to " + partner
				+ " (" + daysLabel(effectiveLoanDays())
				+ (entry.isOneTimeLoan() ? ", one-time" : "")
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
	private void relistReturnedItem(LendingEntry returned, Set<String> relistedNow)
	{
		// One-time loans are recorded and tracked like any other, but the lender
		// chose NOT to make the item a standing marketplace offer — coming home
		// ends the story.
		if (returned.isOneTimeLoan())
		{
			addGameMessage(returned.getItemName() + " returned — not re-listed (one-time loan).");
			return;
		}

		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || group == null || returned.getItemId() <= 0)
		{
			return;
		}

		// The listing the loan came off, picked the way adjustListingForLoan picks
		// it: this exact item, or its plain (un-noted) self.
		int plainId = returned.getItemId();
		try
		{
			plainId = itemManager.canonicalize(plainId);
		}
		catch (Exception e)
		{
			log.debug("Couldn't canonicalize {}: {}", plainId, e.getMessage());
		}
		LendingEntry target = listingFor(group.getId(), me, returned.getItemId(), plainId);
		// Two loans of one item closing in the same trade: the second goes on the
		// listing the first one just made.
		boolean madeNow = target != null && relistedNow.contains(target.getId());
		// With no listing left, it was kept if any form of the item was - and is
		// made again under that one, so it comes back kept.
		int keptId = target != null ? target.getItemId() : keptIdFor(returned.getItemId(), plainId);
		boolean kept = keptAmount != null && keptAmount.test(keptId);
		// A listing following the bank is never restocked by a return: it shows
		// what's held, and what came home is counted that way - by the bank once
		// it's been seen this session, by the trade itself before then. Adding it
		// here as well counted it twice. With no listing of this item, one of its
		// family following the bank counts it the same way - unless this item had
		// a kept listing, taken down when the last one went out, which is made
		// again below.
		if (target != null && !madeNow && !kept)
		{
			addGameMessage(returned.getItemName() + " is home - back on the group marketplace.");
			return;
		}
		if (target == null && !kept)
		{
			int family = familyOf.applyAsInt(returned.getItemId());
			for (LendingEntry o : dataService.getOfferingsByOwner(group.getId(), me))
			{
				if (familyOf.applyAsInt(o.getItemId()) == family && !relistedNow.contains(o.getId())
					&& (keptAmount == null || !keptAmount.test(o.getItemId())))
				{
					addGameMessage(returned.getItemName() + " is home - back on the group marketplace.");
					return;
				}
			}
		}
		// A kept listing has a fixed number: put them back on it.
		if (target != null)
		{
			String ownerKey = target.getLender() != null ? target.getLender() : me;
			int typed = Math.max(0, target.getQuantity());
			int back = Math.max(1, returned.getQuantity());
			LendingEntry updated = new LendingEntry(target);
			updated.setQuantity(typed + back);
			updated.setValue(typed > 0 ? target.getValue() * (typed + back) / typed : returned.getValue());
			updated.setHeldQty(null);
			dataService.updateAvailable(group.getId(), ownerKey, target.getItem(), target.getItemId(), updated);
			addGameMessage(returned.getItemName() + " re-listed on the group marketplace.");
			return;
		}

		// None at all: a new listing, under the plain item rather than its note.
		LendingEntry listing = new LendingEntry();
		listing.setId(UUID.randomUUID().toString());
		relistedNow.add(listing.getId());
		listing.setItem(returned.getItem());
		listing.setItemId(kept ? keptId : plainId);
		listing.setQuantity(Math.max(1, returned.getQuantity()));
		listing.setValue(returned.getValue());
		listing.setBorrower("");
		listing.setLendTime(System.currentTimeMillis());
		listing.setDueTime(Long.MAX_VALUE);
		dataService.addAvailable(group.getId(), me, listing);
		addGameMessage(returned.getItemName() + " re-listed on the group marketplace.");
	}

	/**
	 * A listed item just went out on loan. Only a kept listing (a fixed number) is
	 * changed here: one following the bank shows what's held, so the items leaving
	 * already took them off - by the bank once it's been seen this session, by the
	 * trade itself before then. Taking them off here as well counted them twice.
	 */
	private void adjustListingForLoan(String groupId, String me, int itemId, int quantity)
	{
		int plainId = itemId;
		try
		{
			plainId = itemManager.canonicalize(itemId);
		}
		catch (Exception e)
		{
			log.debug("Couldn't canonicalize {}: {}", itemId, e.getMessage());
		}
		LendingEntry offering = listingFor(groupId, me, itemId, plainId);
		if (offering == null || keptAmount == null || !keptAmount.test(offering.getItemId()))
		{
			return;
		}
		String ownerKey = offering.getLender() != null ? offering.getLender() : me;
		// Take the loan off the fixed number, or take it down when that's all of them.
		int have = offering.shownQuantity();
		if (have > quantity)
		{
			LendingEntry updated = new LendingEntry(offering);
			updated.setQuantity(have - quantity);
			updated.setValue(have > 0 ? offering.shownValue() * (have - quantity) / have : 0);
			updated.setHeldQty(null);
			dataService.updateAvailable(groupId, ownerKey, offering.getItem(), offering.getItemId(), updated);
		}
		else
		{
			dataService.removeAvailable(groupId, ownerKey, offering.getItem(), offering.getItemId());
		}
	}

	/**
	 * My listing of this item: the exact item, else one of the same item noted or
	 * not. Never a sibling of its family - a kept listing is a fixed number of one
	 * item, and lending a different one of the family doesn't change it. Client
	 * thread only.
	 */
	private LendingEntry listingFor(String groupId, String me, int itemId, int plainId)
	{
		LendingEntry plain = null;
		for (LendingEntry o : dataService.getOfferingsByOwner(groupId, me))
		{
			if (o.getItemId() == itemId)
			{
				return o;
			}
			if (plain == null && plainOf(o.getItemId()) == plainId)
			{
				plain = o;
			}
		}
		return plain;
	}

	/** Which form of an item has a keep flag: plain, as returned, or noted. Client thread only. */
	private int keptIdFor(int returnedId, int plainId)
	{
		if (keptAmount == null || keptAmount.test(plainId))
		{
			return plainId;
		}
		if (keptAmount.test(returnedId))
		{
			return returnedId;
		}
		try
		{
			int noted = itemManager.getItemComposition(plainId).getLinkedNoteId();
			if (noted > 0 && keptAmount.test(noted))
			{
				return noted;
			}
		}
		catch (Exception e)
		{
			log.debug("No composition for {}: {}", plainId, e.getMessage());
		}
		return plainId;
	}

	/** The un-noted id of an item. Client thread only. */
	private int plainOf(int itemId)
	{
		try
		{
			return itemManager.canonicalize(itemId);
		}
		catch (Exception e)
		{
			log.debug("Couldn't canonicalize {}: {}", itemId, e.getMessage());
			return itemId;
		}
	}

	/**
	 * The trade completed while the "is this a loan?" popup was still open — stash
	 * everything needed so a late "yes" can still record the loans.
	 * Already-recorded ids (explicitly marked ones) are excluded.
	 */
	private PendingLoanDecision stashPendingDecision(Set<Integer> alreadyRecorded, ReturnTally tally)
	{
		String me = localPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || partner == null || group == null || finalMyOffer == null)
		{
			return null;
		}

		PendingLoanDecision stash = new PendingLoanDecision(sessionId, partner, me, group.getId());
		stash.days = effectiveLoanDays();
		// Quantities consumed as returns (either side) never become stashed loans
		Set<Integer> owedBases = baseIdsIOweTo(partner);
		Map<Integer, Integer> quantities = new HashMap<>();
		for (Item item : finalMyOffer)
		{
			if (item != null && item.getId() > 0 && !alreadyRecorded.contains(item.getId())
				&& isListedForLending(item.getId())
				&& !owedBases.contains(familyOf.applyAsInt(item.getId())))
			{
				quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		if (!tally.consumedMyQtyByBase.isEmpty())
		{
			Map<Integer, Integer> remainingConsumed = new HashMap<>(tally.consumedMyQtyByBase);
			for (Map.Entry<Integer, Integer> q : new ArrayList<>(quantities.entrySet()))
			{
				int baseId = ItemVariationMapping.map(q.getKey());
				int consumed = remainingConsumed.getOrDefault(baseId, 0);
				if (consumed <= 0)
				{
					continue;
				}
				int take = Math.min(consumed, q.getValue());
				remainingConsumed.put(baseId, consumed - take);
				int left = q.getValue() - take;
				if (left > 0)
				{
					quantities.put(q.getKey(), left);
				}
				else
				{
					quantities.remove(q.getKey());
				}
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

		// Collateral snapshot (same rules as recordLoans: returned lent items are
		// returns, only the leftover of the partner's offer is collateral)
		Map<Integer, Integer> remainingTheirConsumed = new HashMap<>(tally.consumedTheirQtyByBase);
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
				else
				{
					int baseId = ItemVariationMapping.map(item.getId());
					int consumed = remainingTheirConsumed.getOrDefault(baseId, 0);
					int qty = item.getQuantity();
					int take = Math.min(consumed, qty);
					if (take > 0)
					{
						remainingTheirConsumed.put(baseId, consumed - take);
						qty -= take;
					}
					if (qty > 0)
					{
						collateralItems.add(itemName(item.getId())
							+ (qty > 1 ? " x" + qty : ""));
						collateralIdPairs.add(item.getId() + ":" + qty);
					}
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
		String outsideWhy = outsideLoanScope(stash.groupId, stash.partnerName);
		if (outsideWhy != null)
		{
			addGameMessage(warnPrefix() + outsideWhy + " - NOT recorded as a loan.");
			return;
		}
		long dueTime = dueTimeFor(stash.days == 0 ? Math.max(1, config.defaultLoanDuration()) : stash.days);
		boolean first = true;
		List<LendingEntry> recorded = new ArrayList<>();
		for (StashedLoan loan : stash.loans)
		{
			LendingEntry entry = new LendingEntry();
			entry.setId(UUID.randomUUID().toString());
			entry.setItem(loan.name);
			entry.setItemId(loan.itemId);
			entry.setQuantity(loan.quantity);
			entry.setValue(loan.value);
			// Running tally starts with everything outstanding
			entry.setLentOutstanding(loan.quantity);
			entry.setCollateralOutstandingIds("");
			entry.setCollateralGpOutstanding(0L);
			if (first)
			{
				if (stash.collateralGp > 0)
				{
					entry.setCollateralValue((int) Math.min(stash.collateralGp, Integer.MAX_VALUE));
					entry.setCollateralType("GP");
					entry.setCollateralGpOutstanding(stash.collateralGp);
				}
				if (stash.collateralItems != null)
				{
					entry.setCollateralItems(stash.collateralItems);
					entry.setCollateralItemIds(stash.collateralItemIds);
					entry.setCollateralOutstandingIds(stash.collateralItemIds);
				}
			}
			dataService.addLoan(stash.groupId, stash.lenderName, stash.partnerName, entry, dueTime);
			recorded.add(entry);
			adjustListingForLoan(stash.groupId, stash.lenderName, loan.itemId, loan.quantity);

			addGameMessage("Loan recorded: " + loan.name
				+ (loan.quantity > 1 ? " x" + loan.quantity : "") + " to " + stash.partnerName
				+ " (" + daysLabel(stash.days == 0 ? Math.max(1, config.defaultLoanDuration()) : stash.days)
				+ (first && stash.collateralGp > 0 ? ", collateral " + QuantityFormatter.quantityToStackSize(stash.collateralGp) + " GP" : "")
				+ ").");
			first = false;
		}
		// The trade's picture is long gone by the time a late "yes" arrives.
		discordWebhook.postBatch(DiscordWebhook.Event.LOAN, recorded, localPlayerName(), null, null);

		if (!stash.loans.isEmpty() && onLoanRecorded != null)
		{
			onLoanRecorded.run();
		}
	}

	/**
	 * A display-only entry describing MY collateral deposit (Collat mode), so the
	 * borrower's proof screenshot overlay names what was handed over. Never saved.
	 */
	private LendingEntry provisionalDepositEntry()
	{
		String me = localPlayerName();
		if (me == null || partner == null || finalMyOffer == null)
		{
			return null;
		}
		long gp = 0;
		List<String> names = new ArrayList<>();
		for (Item item : finalMyOffer)
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			if (item.getId() == ItemID.COINS_995)
			{
				gp += item.getQuantity();
			}
			else if (item.getId() == ItemID.PLATINUM_TOKEN)
			{
				gp += item.getQuantity() * 1000L;
			}
			else
			{
				names.add(itemName(item.getId())
					+ (item.getQuantity() > 1 ? " x" + item.getQuantity() : ""));
			}
		}
		if (gp == 0 && names.isEmpty())
		{
			return null;
		}
		LendingEntry entry = new LendingEntry();
		entry.setItem("Collateral deposit");
		entry.setLender(partner);
		entry.setBorrower(me);
		if (gp > 0)
		{
			entry.setCollateralValue((int) Math.min(gp, Integer.MAX_VALUE));
			entry.setCollateralType("GP");
		}
		if (!names.isEmpty())
		{
			entry.setCollateralItems(String.join(", ", names));
		}
		return entry;
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

	/**
	 * Is a loan with this player outside what the group tracks? Returns why, for
	 * the chat message, or null when it's fine. For a clan group only a CONFIRMED
	 * non-member is refused: unknown - this client can't see that clan's list -
	 * still records, it just never reaches the clan's Discord, which needs a yes.
	 */
	private String outsideLoanScope(String groupId, String name)
	{
		if (groupId == null || name == null)
		{
			return null;
		}
		switch (groupService.getLoanScope(groupId))
		{
			case CLAN:
				String clan = groupService.getLinkedClan(groupId);
				clanRoster.refresh();
				return clanRoster.check(clan, name) == ClanRoster.Membership.NO
					? name + " isn't in " + clan + ", and this group only tracks loans inside the clan"
					: null;
			case GROUP:
				LendingGroup group = groupService.getGroup(groupId);
				return group != null && !group.hasMember(name)
					? name + " isn't in this group, and it only tracks loans between its members"
					: null;
			default:
				return null;
		}
	}

	private boolean isPartnerInLinkedClan()
	{
		LendingGroup group = groupService.getActiveGroup();
		return group != null && partner != null
			&& clanRoster.check(groupService.getLinkedClan(group.getId()), partner) == ClanRoster.Membership.YES;
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

	/** Sum quantities per variation-base id from a live container. */
	private void collectBaseQuantities(Map<Integer, Integer> into, int containerId)
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
				into.merge(ItemVariationMapping.map(item.getId()), item.getQuantity(), Integer::sum);
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
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>" + (message), "");
	}
}
