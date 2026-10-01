package com.guess34.lendingtracker;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.eventbus.EventBus;

import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.Notifier;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.guess34.lendingtracker.model.*;
import com.guess34.lendingtracker.ui.LendingPanel;
import com.guess34.lendingtracker.services.ArmourSets;
import com.guess34.lendingtracker.services.ClanRoster;
import net.runelite.client.discord.DiscordService;
import net.runelite.client.discord.events.DiscordReady;
import com.guess34.lendingtracker.services.DataService;
import com.guess34.lendingtracker.services.DiscordWebhook;
import com.guess34.lendingtracker.services.ItemCategories;
import com.guess34.lendingtracker.services.LocalDataSyncService;
import com.guess34.lendingtracker.services.ProofScreenshot;
import com.guess34.lendingtracker.services.GroupService;
import com.guess34.lendingtracker.services.RelaySyncService;
import com.guess34.lendingtracker.services.TradeLoanTracker;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
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
// This plugin has been trimmed more times than rune armor at the GE. ~Guess34
public class LendingTrackerPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private LendingTrackerConfig config;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ItemManager itemManager;
	@Inject private ArmourSets armourSets;
	@Inject private ScheduledExecutorService executor;

	// What this account is wearing, carrying and last had in the bank, by EXACT
	// item id. Used to work out which of our own listings can't be handed over
	// right now. Session-only and never written to disk: it means "right now".
	// Worked out once per item id: the bank can hold hundreds, and resolving one
	// costs an item-definition lookup and a price lookup.
	private final java.util.Map<Integer, Integer> lendableIds = new java.util.HashMap<>();
	// Listings already warned about this session, so the bank check says each one
	// once rather than on every deposit and withdrawal.
	private final java.util.Set<Integer> missingOnce = new java.util.HashSet<>();
	private final java.util.Map<Integer, Integer> wornNow = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> carriedNow = new java.util.HashMap<>();
	private final java.util.Map<Integer, Integer> bankedNow = new java.util.HashMap<>();
	// Until this account has opened its own bank we cannot rule out a spare
	// sitting in it, so nothing is hidden. Same caution the trade guards take.
	private boolean bankSeenThisSession;
	// Gear changes are coalesced into one publish; see recomputeWornListings.
	private static final long WORN_PUSH_DELAY_SECONDS = 30;
	private final java.util.concurrent.atomic.AtomicBoolean wornPushQueued =
		new java.util.concurrent.atomic.AtomicBoolean();

	// The executor above is the CLIENT'S, shared with every other plugin - we must
	// never shut it down. But that also means nothing stops our tasks when the
	// plugin is disabled: they kept running for the rest of the session, still
	// firing overdue notifications and sounds, and every re-enable added another
	// set. Hold the handles so shutDown can cancel exactly ours.
	private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

	// Bumped whenever the plugin stops or the player logs out. The login flow
	// runs on the shared executor now, so a queued one can still be waiting
	// when that happens - and it starts sync and opens a relay socket, which
	// would come back up after teardown and leak for the rest of the session.
	// Each run captures the value it was queued with and stops if it moved.
	private final java.util.concurrent.atomic.AtomicLong sessionEpoch = new java.util.concurrent.atomic.AtomicLong();
	// Loan id -> the days-overdue count we last reminded about. The check runs
	// hourly but a reminder day lasts 24 hours, so without this every due
	// reminder went off 24 times.
	private final java.util.Map<String, Long> overdueRemindedAt = new java.util.concurrent.ConcurrentHashMap<>();
	@Inject private Notifier notifier;
	@Inject private EventBus eventBus;
	@Inject private DataService dataService;
	@Inject private ItemCategories itemCategories;
	@Inject private DiscordWebhook discordWebhook;
	@Inject private ClanRoster clanRoster;
	@Inject private DiscordService discordService;
	private int clanRefreshTicks = 99; // first tick after start reads it straight away
	@Inject private GroupService groupService;
	@Inject private LocalDataSyncService localDataSyncService;
	@Inject private ProofScreenshot proofScreenshot;
	@Inject private RelaySyncService relaySyncService;
	@Inject private TradeLoanTracker tradeLoanTracker;

	private LendingPanel newPanel;
	private NavigationButton navButton;
	// Local world, cached from the client thread (see onGameStateChanged). The relay
	// presence join message reads this off the ws thread, where a direct
	// client.getWorld() would be unsafe/stale.
	private volatile int lastKnownWorld;

	@Override
	protected void startUp() throws Exception
	{
		dataService.initialize();
		groupService.initialize();
		localDataSyncService.initialize();

		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		}
		catch (Exception e)
		{
			log.warn("Failed to load panel icon, using default: {}", e.getMessage());
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = icon.createGraphics();
			g.setColor(Color.ORANGE);
			g.fillOval(2, 2, 12, 12);
			g.setColor(Color.WHITE);
			g.drawString("L", 6, 12);
			g.dispose();
		}

		newPanel = new LendingPanel(this, eventBus);
		navButton = NavigationButton.builder()
			.tooltip("Lending Tracker").icon(icon).priority(5).panel(newPanel).build();
		if (clientToolbar != null) { clientToolbar.addNavigation(navButton); }
		else { log.error("ClientToolbar is null - UI will not appear"); }

		groupService.setActive(true);
		// The lender posts a loan to Discord the first time their client sees it,
		// however it got here. The webhook remembers what it posted, so the loans
		// reloaded at every login are not posted again.
		dataService.setOnNewLoanFromSync(loan -> discordWebhook.postLoanSeen(loan, getCurrentPlayerName()));
		groupService.setOnSyncCallback(this::onGroupDataSynced);
		// A group vanishing without a word is the single most confusing thing
		// the plugin can do, so say what happened.
		groupService.setOnGroupGone(msg ->
		{
			if (config.enableNotifications()) { notifier.notify("[Lending Tracker] " + msg); }
		});
		groupService.setOnWildernessAlert(this::handleWildernessAlert);
		tradeLoanTracker.setOnLoanRecorded(this::refreshPanel);

		// Register relay sync callbacks for cross-machine sync
		relaySyncService.setOnEventReceived(event -> groupService.handleRelayEvent(event));
		relaySyncService.setOnStateReceived((groupJson, dataJson, publisher) -> groupService.handleRelayState(groupJson, dataJson, publisher));
		relaySyncService.setOnConnectionChanged(status ->
		{
			if (newPanel != null) { newPanel.updateConnectionStatus(status); }
			if (!status)
			{
				// Invalidate the publish gate's caught-up marker at DROP time, not
				// reconnect time — a queued publish racing the reconnect callback
				// could otherwise upload pre-outage state over peers' changes.
				groupService.onRelayDisconnected();
				// When our own socket drops we can no longer vouch for anyone's presence.
				if (groupService.clearPresence() && newPanel != null)
				{
					newPanel.refreshRoster();
				}
			}
		});
		// Announce our roster the instant we (re)connect so a freshly joined
		// member shows up for everyone immediately instead of after the periodic push.
		relaySyncService.setOnConnected(() -> groupService.onRelayConnected());
		// Relay-authoritative online status: a member is online iff they hold an open
		// sync socket to the room, regardless of friends chat / friends list.
		relaySyncService.setOnPresenceReceived(present ->
		{
			if (groupService.handlePresence(present) && newPanel != null)
			{
				newPanel.refreshRoster();
			}
		});
		// Report our world in presence so peers can show it next to our name. Read
		// from a value cached on the client thread — the supplier runs on the ws
		// thread, where calling client.getWorld() directly could be stale/unsafe.
		relaySyncService.setLocalWorldSupplier(() -> lastKnownWorld);

		// Keep the Render free-tier relay warm so invite codes don't get
		// wiped by spindown between when the owner creates one and a member uses it.
		relaySyncService.startKeepalive();

		if (client.getGameState() == GameState.LOGGED_IN
			&& client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			lastKnownWorld = client.getWorld();
			triggerLoginFlow(client.getLocalPlayer().getName(), sessionEpoch.get());
		}

		try
		{
			scheduledTasks.add(executor.scheduleAtFixedRate(this::checkOverdueLoans, 0, 1, TimeUnit.HOURS));
			scheduledTasks.add(executor.scheduleAtFixedRate(this::cleanupOldRecords, 1, 24, TimeUnit.HOURS));
			scheduledTasks.add(executor.scheduleAtFixedRate(this::syncGroupData, 0, 5, TimeUnit.MINUTES));
			scheduledTasks.add(executor.scheduleAtFixedRate(this::updateMarketplacePrices, 1, 12, TimeUnit.HOURS));
		}
		catch (Exception e) { log.warn("Failed to schedule periodic tasks: {}", e.getMessage()); }
	}

	@Override
	protected void shutDown() throws Exception
	{
		sessionEpoch.incrementAndGet();
		// Ours to cancel. The executor belongs to the client and is shared
		// with every other plugin, so we must never shut it down - but that
		// also means nothing else stops these. Left running they kept firing
		// overdue notifications and sounds after the plugin was disabled, and
		// every re-enable stacked another set on top.
		for (ScheduledFuture<?> task : scheduledTasks)
		{
			if (task != null) { task.cancel(false); }
		}
		scheduledTasks.clear();

		relaySyncService.stopKeepalive();
		groupService.setActive(false);
		groupService.stopSync();
		if (navButton != null) { clientToolbar.removeNavigation(navButton); }
		if (localDataSyncService != null)
		{
			try { localDataSyncService.shutdown(); }
			catch (Exception e) { log.warn("Error shutting down local data sync: {}", e.getMessage()); }
		}
		// Dropping the reference does not release it: the event bus holds a
		// strong reference to a registered subscriber, so the whole panel tree
		// stays alive and keeps handling events, and its poller keeps ticking.
		if (newPanel != null) { newPanel.deregister(); }
		newPanel = null;
		navButton = null;
		tradeLoanTracker.reset();
		tradeLoanTracker.clearPendingDecisions();
	}

	private void triggerLoginFlow(String playerName, long epoch)
	{
		// Stopped or logged out while this was queued - do nothing.
		if (epoch != sessionEpoch.get()) { return; }
		groupService.onAccountLogin(playerName);
		// Read every group's stored data before the local backup restore runs. The
		// restore only fills in what's missing, so it needs to see what we already
		// have - and it writes as it goes, which without this would save our empty
		// in-memory state over the real file.
		for (LendingGroup g : groupService.getAllGroups())
		{
			if (g != null) { dataService.loadGroupData(g.getId()); }
		}
		localDataSyncService.onAccountLogin();
		configManager.setConfiguration("lendingtracker", "currentAccount", playerName);
		// A hide only ever means "on my back right now". One restored from this
		// computer's backup, or left over from another account's session, would hide
		// gear this account is not wearing - so the slate is wiped before anything
		// is published, then filled in again from the live containers.
		for (LendingGroup g : groupService.getAllGroups())
		{
			if (g != null)
			{
				dataService.setWornQuantities(g.getId(), playerName, null, null);
			}
		}
		// Containers can only be read on the client thread, and the equipment event
		// may well have arrived before this account's data was loaded, so ask again
		// here rather than waiting for the next gear change.
		clientThread.invokeLater(this::readLiveContainers);
		LendingGroup activeGroup = groupService.getActiveGroup();
		if (activeGroup != null) { groupService.startSync(activeGroup.getId(), playerName); }
		refreshPanel();
		checkForRequestNotifications();
		applyApprovedRemovals();
	}

	/** Runs whenever group data changes via sync (local poll or relay). */
	private void onGroupDataSynced()
	{
		// A sync can reload our own rows wholesale from the stored snapshot, which
		// brings back whatever "worn" flag was last written to disk - leaving gear
		// hidden from the group that is in fact sitting in the bank. Re-apply what
		// the containers actually say; it no-ops when nothing changed.
		clientThread.invokeLater(this::readLiveContainers);
		refreshPanel();
		checkForRequestNotifications();
		applyApprovedRemovals();
	}

	// --- Event Handlers ---

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// Player-to-player trade screens: the loan capture flow lives in TradeLoanTracker
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.TRADEMAIN)
		{
			tradeLoanTracker.onTradeMainLoaded();
		}
		else if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.TRADECONFIRM)
		{
			tradeLoanTracker.onTradeConfirmLoaded();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// The trade screen 1 build script — fires when the interface is fully drawn
		// and after any rebuild, so it's the reliable moment to (re)add the button
		if (event.getScriptId() == ScriptID.TRADE_MAIN_INIT)
		{
			tradeLoanTracker.onTradeMainBuilt();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// My side of the trade offer changed — drives the "is this a loan?" popup
		// for items listed on the marketplace
		if (event.getContainerId() == net.runelite.api.gameval.InventoryID.TRADEOFFER
			&& event.getItemContainer() != null)
		{
			tradeLoanTracker.onMyOfferChanged(event.getItemContainer().getItems());
		}
		// Either side changed — keeps the first-screen proof shot current so the
		// saved frame shows the final offers (partner's container is id | 0x8000)
		if (event.getContainerId() == net.runelite.api.gameval.InventoryID.TRADEOFFER
			|| event.getContainerId() == (net.runelite.api.gameval.InventoryID.TRADEOFFER | 0x8000))
		{
			tradeLoanTracker.onTradeOfferUpdated();
		}
		// Gear on our own back can't be handed over, so it comes off the board
		// until it comes off us. Only this client can see this account's equipment,
		// and what we're carrying or have banked is what says whether a spare could
		// cover the listing instead.
		if (event.getContainerId() == net.runelite.api.gameval.InventoryID.WORN
			&& event.getItemContainer() != null)
		{
			countById(wornNow, event.getItemContainer().getItems());
			recomputeWornListings();
		}
		else if (event.getContainerId() == net.runelite.api.gameval.InventoryID.INV
			&& event.getItemContainer() != null)
		{
			countById(carriedNow, event.getItemContainer().getItems());
			recomputeWornListings();
		}
		// Bank snapshot for the fungible-duplicate guard logic: owning a spare copy
		// of a collateral/borrowed item (safe in the bank) means carrying your own
		// copy isn't at-risk. Only known once the bank has been opened this session.
		if (event.getContainerId() == net.runelite.api.gameval.InventoryID.BANK
			&& event.getItemContainer() != null)
		{
			tradeLoanTracker.onBankUpdated(event.getItemContainer().getItems());
			countById(bankedNow, event.getItemContainer().getItems());
			bankSeenThisSession = true;
			recomputeWornListings();
			warnAboutListingsNotHeld();
		}
	}

	/**
	 * With the bank open we can see everything we own, so say so when something we
	 * have listed is nowhere to be found - sold, traded, lost on a death, or parked
	 * somewhere unreadable like a POH rack or a looting bag.
	 *
	 * It only ever WARNS. It changes nothing and publishes nothing; taking the
	 * listing down is the owner's call. Removing it automatically needs a stricter
	 * idea of "gone" than one bank visit can give - a death being reclaimed, or
	 * collateral handed over in a trade, both look exactly like this.
	 *
	 * Standard worlds only. Leagues, Deadman and beta worlds have a bank of their
	 * own under the same name, and every main-game listing would look missing.
	 */
	private void warnAboutListingsNotHeld()
	{
		if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) { return; }
		String groupId = groupService.getCurrentGroupId();
		String me = client != null && client.getLocalPlayer() != null
			? client.getLocalPlayer().getName() : null;
		if (groupId == null || groupId.isEmpty() || me == null || !bankSeenThisSession) { return; }

		// Both sides through the same resolver and the same family key, so a noted
		// listing, a kitted copy in the bank or a charged one on your back all count.
		java.util.Map<Integer, Integer> owned = new java.util.HashMap<>();
		for (java.util.Map<Integer, Integer> where : java.util.Arrays.asList(bankedNow, carriedNow, wornNow))
		{
			where.forEach((id, qty) ->
				owned.merge(net.runelite.client.game.ItemVariationMapping.map(id), qty, Integer::sum));
		}

		java.util.List<String> missing = new java.util.ArrayList<>();
		for (LendingEntry e : dataService.getOfferingsByOwner(groupId, me))
		{
			if (e == null || e.getItemId() <= 0) { continue; }
			// Out on loan: the borrower has it, which is the point.
			if (e.getBorrower() != null && !e.getBorrower().isEmpty()) { continue; }
			int family = net.runelite.client.game.ItemVariationMapping.map(owningIdFor(e.getItemId()));
			int have = owned.getOrDefault(family, 0);
			owned.put(family, Math.max(0, have - Math.max(1, e.getQuantity())));
			// Once per item per session: the bank fires on every deposit.
			if (have <= 0 && missingOnce.add(e.getItemId()))
			{
				missing.add(e.getItem());
			}
		}
		if (missing.isEmpty()) { return; }
		String said = missing.size() <= 3 ? String.join(", ", missing)
			: missing.size() + " items (" + String.join(", ", missing.subList(0, 3)) + ", ...)";
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff0000>" + ("Lending Tracker: you have " + said + " listed, but it isn't in your bank, inventory "
				+ "or worn gear. Delist it if it's gone."), "");
	}

	/**
	 * Re-key a tally by item family, so a Soulreaper axe with an ornament kit on it
	 * counts as the plain axe that was listed - you only own the one, and it is on
	 * your back. Nothing is wrongly hidden by this, because what we can SEE spare
	 * is counted the same way: a plain Berserker ring in the bank still covers its
	 * listing while an imbued one is worn.
	 */
	private static java.util.Map<Integer, Integer> byFamily(java.util.Map<Integer, Integer> byId)
	{
		java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
		byId.forEach((id, qty) ->
			out.merge(net.runelite.client.game.ItemVariationMapping.map(id), qty, Integer::sum));
		return out;
	}

	/** Re-read our own containers from scratch. Client thread only. */
	private void readLiveContainers()
	{
		if (client == null) { return; }
		net.runelite.api.ItemContainer worn = client.getItemContainer(net.runelite.api.gameval.InventoryID.WORN);
		net.runelite.api.ItemContainer inv = client.getItemContainer(net.runelite.api.gameval.InventoryID.INV);
		countById(wornNow, worn != null ? worn.getItems() : null);
		countById(carriedNow, inv != null ? inv.getItems() : null);
		recomputeWornListings();
	}

	/**
	 * Tally a container by exact item id, replacing whatever was there. Client
	 * thread only - canonicalize reads item definitions.
	 *
	 * canonicalize is what makes equipped gear match a listing at all: worn
	 * Graceful reports its own "worn" item ids, and it maps those back to the ones
	 * people actually list. It leaves imbues and degrade states alone, which is
	 * what stops a Berserker ring (i) from being mistaken for a plain one.
	 *
	 * Anything with no quantity is skipped, which is how bank placeholders - empty
	 * slots holding a ghost of the item - stay out of the count instead of looking
	 * like a spare.
	 */
	/**
	 * The id a thing we own should be counted under: the same id it would be
	 * LISTED under. Canonicalize first, which settles notes, placeholders and the
	 * separate ids worn Graceful uses, then resolve an ornament kit or a charge
	 * back to the plain item.
	 *
	 * These two have to agree. When they did not, gear was listed as the plain
	 * version and then counted as the kitted one, so it read as neither worn nor
	 * owned - and the bank check deleted the listing.
	 */
	private int owningIdFor(int rawId)
	{
		int canon;
		try
		{
			canon = itemManager.canonicalize(rawId);
		}
		catch (Exception e)
		{
			canon = rawId;
		}
		int known = lendableIdCached(canon);
		return known > 0 ? known : canon;
	}

	private void countById(java.util.Map<Integer, Integer> into, Item[] items)
	{
		into.clear();
		if (items == null) { return; }
		for (Item it : items)
		{
			if (it == null || it.getId() <= 0 || it.getQuantity() <= 0) { continue; }
			// A bank placeholder is the empty peg left behind when you withdraw the
			// last one. It is not an item you own, and canonicalize would turn it
			// into the real id and make it look like one.
			try
			{
				if (itemManager.getItemComposition(it.getId()).getPlaceholderTemplateId() != -1)
				{
					continue;
				}
			}
			catch (Exception e)
			{
				log.debug("No composition for {}: {}", it.getId(), e.getMessage());
			}
			into.merge(owningIdFor(it.getId()), it.getQuantity(), Integer::sum);
		}
	}

	/**
	 * Work out which of our own listings can't be handed over because we are
	 * wearing them, and let the group know. Items we have not listed are ignored
	 * outright, so an ordinary gear switch costs nothing.
	 *
	 * Gear on our back is gear in use, so it comes off the board as soon as we put
	 * it on. A copy we can see elsewhere - in the inventory, or in the bank once it
	 * has been opened - keeps the listing up, because then the worn one is not the
	 * one being offered.
	 */
	private void recomputeWornListings()
	{
		// A Leagues, Deadman or beta world has its own gear; it says nothing about
		// what this player's main-game listings are doing.
		if (RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) { return; }
		String groupId = groupService.getCurrentGroupId();
		String me = client != null && client.getLocalPlayer() != null
			? client.getLocalPlayer().getName() : null;
		if (groupId == null || groupId.isEmpty() || me == null) { return; }

		// What we can positively see that is NOT on our back. An unopened bank
		// contributes nothing, which means gear we are wearing comes off the board
		// straight away - if it is on us, it is in use. Opening the bank later only
		// makes this kinder: a spare found in there puts the listing back.
		java.util.Map<Integer, Integer> worn = byFamily(wornNow);
		java.util.Map<Integer, Integer> spare = byFamily(carriedNow);
		byFamily(bankedNow).forEach((id, qty) -> spare.merge(id, qty, Integer::sum));
		if (!dataService.setWornQuantities(groupId, me, worn, spare)) { return; }
		refreshPanel();

		// One push per burst of changes, never one per equip: every publish sends
		// the whole group snapshot, so somebody flicking through gear switches must
		// not broadcast to the clan on every tick. Worst case the group learns
		// about it on the five-minute heartbeat instead.
		if (wornPushQueued.compareAndSet(false, true))
		{
			try
			{
				executor.schedule(() ->
				{
					wornPushQueued.set(false);
					groupService.announcePresence();
				}, WORN_PUSH_DELAY_SECONDS, TimeUnit.SECONDS);
			}
			catch (Exception e)
			{
				wornPushQueued.set(false);
				log.debug("Couldn't schedule a worn-gear push: {}", e.getMessage());
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		// Cleans up sessions ended by walking away / Esc, which produce neither a
		// Decline click nor a chat message
		if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.TRADEMAIN
			|| event.getGroupId() == net.runelite.api.gameval.InterfaceID.TRADECONFIRM)
		{
			tradeLoanTracker.onTradeWidgetClosed(event.getGroupId());
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// "Mark as Loan" on the player's offered items in the trade window
		tradeLoanTracker.onMenuEntryAdded(event);

		String target = event.getTarget();
		if (target == null || target.isEmpty()) { return; }

		// Only offer to lend/list tradeable items — you can't lend what you can't
		// trade. Checked here (not just on click) so the option never appears on an
		// untradeable item in the first place.
		if (event.getOption().equals("Examine")
			&& event.getType() == MenuAction.EXAMINE_ITEM.getId()
			&& canBeListed(event.getItemId()))
		{
			addMenuEntry("Add to Lending List", event);
		}

		if (event.getOption().equals("Drop") && canBeListed(event.getItemId()))
		{
			addMenuEntry("Lend to Group", event);
		}

		if (event.getOption().equals("Report") && canCurrentUserInvite())
		{
			addMenuEntry("Invite to Lending Group", event);
		}
	}

	private boolean isTradeable(int itemId)
	{
		if (itemId <= 0)
		{
			return true; // can't identify the item — don't hide the option
		}
		try
		{
			return itemManager.getItemComposition(itemId).isTradeable();
		}
		catch (Exception e)
		{
			return true;
		}
	}

	/**
	 * The item this one would actually be lent as. An ornament kit or a charge
	 * makes gear untradeable, but taking the kit off - or uncharging it - hands the
	 * plain item straight back, so there is no reason to make somebody strip their
	 * gear before listing it. It goes up as the plain version, which is what
	 * changes hands anyway.
	 *
	 * RuneLite already knows what reverts to what: ItemMapping is the table it uses
	 * to price untradeable gear, so a Soulreaper axe with its ornament kit maps to
	 * the plain axe, and a charged Serpentine helm to the plain helm. Guessing from
	 * the name, or from the variation mapping, does not cover either case - which
	 * is why the first version of this silently offered nothing at all.
	 *
	 * Where something maps to more than one component - a kitted axe is both an axe
	 * AND a kit - the dearest one wins. That is the item being lent; the cheap half
	 * is the cosmetic or sacrificial part.
	 *
	 * An imbue is deliberately not included. It is not a kit you take off: the
	 * plain ring is a different item you would have to go and get, so an imbued one
	 * is never offered as a plain one.
	 *
	 * @return the id to list, or -1 if this cannot be lent at all.
	 */
	private int lendableIdFor(int itemId)
	{
		if (itemId <= 0)
		{
			return itemId;
		}
		try
		{
			if (itemManager.getItemComposition(itemId).isTradeable())
			{
				return itemId;
			}
			String name = itemManager.getItemComposition(itemId).getName();
			log.debug("Not tradeable: {} ({}) - looking for what it reverts to", name, itemId);
			// An imbue is not a kit you take off, in any of its spellings: (i),
			// (ri) and the (i1)-(i5) sets.
			if (name != null && name.toLowerCase(java.util.Locale.ROOT).matches(".*\\((?:r?i|i\\d+)\\).*"))
			{
				return -1;
			}

			// Which of the things this reverts to IS the item, rather than a part
			// of it. Decided without prices: they move every day, and when the
			// price feed fails at startup they are all 0. Tested against every one
			// of the 573 untradeable ids RuneLite maps - none comes out as a kit.
			//   1. the candidate in the same item family: the plain item under the
			//      kit (Occult necklace under Occult necklace (or));
			//   2. otherwise one that is not an ornament kit (Echo Virtus robe top
			//      maps to the robe AND the kit, but is not in the robe's family);
			//   3. only a kit on offer - refuse rather than list the kit as gear.
			// Lowest id breaks a tie, so the answer never depends on hash order.
			java.util.Collection<net.runelite.client.game.ItemMapping> reverts =
				net.runelite.client.game.ItemMapping.map(itemId);
			int family = net.runelite.client.game.ItemVariationMapping.map(itemId);
			int sameFamily = -1;
			int notKit = -1;
			if (reverts != null)
			{
				for (net.runelite.client.game.ItemMapping revert : reverts)
				{
					int candidate = revert.getTradeableItem();
					if (candidate <= 0 || candidate == itemId
						|| !itemManager.getItemComposition(candidate).isTradeable())
					{
						continue;
					}
					if (net.runelite.client.game.ItemVariationMapping.map(candidate) == family
						&& (sameFamily < 0 || candidate < sameFamily))
					{
						sameFamily = candidate;
					}
					String candidateName = itemManager.getItemComposition(candidate).getName();
					boolean kit = candidateName != null
						&& candidateName.toLowerCase(java.util.Locale.ROOT).endsWith("kit");
					if (!kit && (notKit < 0 || candidate < notKit))
					{
						notKit = candidate;
					}
				}
			}
			int best = sameFamily > 0 ? sameFamily : notKit;
			if (best > 0)
			{
				log.debug("{} ({}) lists as {} ({})", name, itemId,
					itemManager.getItemComposition(best).getName(), best);
				return best;
			}

			int plain = net.runelite.client.game.ItemVariationMapping.map(itemId);
			if (plain != itemId && itemManager.getItemComposition(plain).isTradeable())
			{
				log.debug("{} ({}) lists as variation base {}", name, itemId, plain);
				return plain;
			}
			log.debug("Nothing to list {} ({}) as: {} revert mapping(s), variation base {}",
				name, itemId, reverts == null ? 0 : reverts.size(), plain);
		}
		catch (Exception e)
		{
			log.debug("Couldn't work out a lendable id for {}: {}", itemId, e.getMessage());
		}
		return -1;
	}

	/**
	 * Resolve once per item id and remember it. Menu entries are rebuilt every time
	 * the mouse moves over the inventory, and a bank holds hundreds of items, so
	 * this must not do an item-definition lookup every time.
	 *
	 * The first resolution of anything untradeable is logged at DEBUG. That is off
	 * for this package by default (RuneLite only turns DEBUG on for its own), so
	 * raise it locally in the dev client when an ornament kit refuses to list -
	 * INFO would put a line per bank item into every user's log.
	 */
	private int lendableIdCached(int itemId)
	{
		Integer known = lendableIds.get(itemId);
		if (known != null)
		{
			return known;
		}
		int resolved = lendableIdFor(itemId);
		lendableIds.put(itemId, resolved);
		if (resolved != itemId)
		{
			String name = "?";
			try
			{
				name = itemManager.getItemComposition(itemId).getName();
			}
			catch (Exception ignored)
			{
				// name is only for the log line
			}
			log.debug("Lending Tracker: {} ({}) -> {}", name, itemId,
				resolved > 0 ? "lists as " + resolved : "cannot be lent");
		}
		return resolved;
	}

	/** True when this can go on the marketplace, kit on or off. */
	private boolean canBeListed(int itemId)
	{
		return itemId <= 0 || lendableIdCached(itemId) > 0;
	}

	private void addMenuEntry(String option, MenuEntryAdded event)
	{
		client.createMenuEntry(-1)
			.setOption(option).setTarget(event.getTarget()).setType(MenuAction.RUNELITE)
			.setParam0(event.getActionParam0()).setParam1(event.getActionParam1())
			.setIdentifier(event.getIdentifier())
			// The identifier is NOT the item id. Carry the item id across as well,
			// or the click reads a different number than the check that offered the
			// option in the first place.
			.setItemId(event.getItemId())
			// Never the left-click default (deprioritized); actual on-screen
			// position is fixed in onMenuOpened, which moves our entries to the
			// very bottom of the menu
			.setDeprioritized(true);
	}

	/** Options this plugin adds to item/player menus (not the trade-window ones). */
	private static final java.util.Set<String> OWN_MENU_OPTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
		"Add to Lending List", "Lend to Group", "Invite to Lending Group"));

	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		// Move OUR entries to the very bottom of the menu — below Examine, just
		// above Cancel — so they never sit next to Drop where a misclick drops a
		// valuable item. This must run in PostMenuSort: the client sorts the menu
		// AFTER events like MenuOpened, which would clobber any earlier reorder
		// (the same reason the core Menu Entry Swapper hooks this event). Only
		// this plugin's own entries are moved (matched by their distinctive
		// option text); vanilla options keep their exact order.
		MenuEntry[] entries = client.getMenuEntries();
		if (entries == null || entries.length < 3)
		{
			return;
		}

		java.util.List<MenuEntry> ours = new ArrayList<>();
		java.util.List<MenuEntry> rest = new ArrayList<>();
		for (MenuEntry entry : entries)
		{
			// Match by option name only — the deprioritized flag shifts the
			// reported type, so a type check can miss our own entries
			if (OWN_MENU_OPTIONS.contains(entry.getOption()))
			{
				ours.add(entry);
			}
			else
			{
				rest.add(entry);
			}
		}
		if (ours.isEmpty())
		{
			return;
		}

		// Index 0 renders at the BOTTOM of the menu ("Cancel" when it's open);
		// keep it there and slot our entries directly above it
		java.util.List<MenuEntry> reordered = new ArrayList<>(entries.length);
		int insertAfter = !rest.isEmpty() && "Cancel".equals(rest.get(0).getOption()) ? 1 : 0;
		reordered.addAll(rest.subList(0, insertAfter));
		reordered.addAll(ours);
		reordered.addAll(rest.subList(insertAfter, rest.size()));
		client.setMenuEntries(reordered.toArray(new MenuEntry[0]));
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		// Loan marking, decline detection, and the borrowed-item guards.
		// Returns true when the click was consumed.
		if (tradeLoanTracker.onMenuOptionClicked(event)) { return; }

		String option = event.getMenuOption();
		if (option.equals("Add to Lending List")) { handleAddToAvailableList(event); }
		else if (option.equals("Lend to Group")) { handleLendToGroup(event); }
		else if (option.equals("Invite to Lending Group")) { handlePlayerInvite(event); }
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String message = event.getMessage();
		if (event.getType() == ChatMessageType.PRIVATECHAT || event.getType() == ChatMessageType.PRIVATECHATOUT)
		{
			handlePrivateMessage(event.getName(), message);
		}
		// Trade completion / partner decline arrive as TRADE-type messages
		if (event.getType() == ChatMessageType.TRADE)
		{
			tradeLoanTracker.onTradeMessage(message);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() ->
			{
				if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) { return false; }
				String playerName = client.getLocalPlayer().getName();
				// Cache the world on the client thread for the presence join message
				// (fires on login AND after every world hop).
				lastKnownWorld = client.getWorld();
				groupService.setOnSyncCallback(this::onGroupDataSynced);
				// Only the two reads above need the client thread. The login flow
				// itself reads the whole backup file off disk and parses it, and that
				// file grows with your history - doing it here stalls the game.
				long epoch = sessionEpoch.get();
				executor.execute(() -> triggerLoginFlow(playerName, epoch));
				return true;
			});
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// A login flow queued moments ago must not start sync for an
			// account that has already logged out.
			sessionEpoch.incrementAndGet();
			groupService.stopSync();
			tradeLoanTracker.reset();
			tradeLoanTracker.clearPendingDecisions();
			// The next account on this client holds different things.
			wornNow.clear();
			carriedNow.clear();
			bankedNow.clear();
			missingOnce.clear();
			bankSeenThisSession = false;
			refreshPanel();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null) { return false; }
			String playerName = client.getLocalPlayer().getName();
			// Keep the cached world fresh here too — this path also re-sends the
			// presence join (via startSync's same-target fast path).
			lastKnownWorld = client.getWorld();
			long epoch = sessionEpoch.get();
			executor.execute(() -> triggerLoginFlow(playerName, epoch));
			return true;
		});
	}

	// The clan list changes as people join and leave; re-read it when the clan
	// channel changes and about once a minute, not every tick.
	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		clanRoster.refresh();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (++clanRefreshTicks >= 100)
		{
			clanRefreshTicks = 0;
			clanRoster.refresh();
			// Also catches groups joined since, and a Discord app opened late
			updateMyDiscordId();
		}
	}

	@Subscribe
	public void onDiscordReady(DiscordReady event)
	{
		updateMyDiscordId();
	}

	/**
	 * Keep this player's Discord ID on their group rows in step with "Ping me":
	 * the typed ID if there is one, else the one the Discord app reports, and
	 * none at all when pinging is off.
	 */
	private void updateMyDiscordId()
	{
		String me = getCurrentPlayerName();
		if (me == null) { return; }
		String id = null;
		if (config.webhookPingMe())
		{
			String typed = config.webhookDiscordId() != null ? config.webhookDiscordId().trim() : "";
			if (typed.matches("\\d{17,20}"))
			{
				id = typed;
			}
			else if (discordService.getCurrentUser() != null
				&& discordService.getCurrentUser().userId != null
				&& discordService.getCurrentUser().userId.matches("\\d{17,20}"))
			{
				id = discordService.getCurrentUser().userId;
			}
		}
		groupService.setMyDiscordId(me, id);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() != Varbits.IN_WILDERNESS)
		{
			return;
		}

		// Every enter/exit starts a new episode, invalidating any armed 45s check
		wildernessEpisode++;
		lenderAlertSentThisEpisode = false;

		if (client.getVarbitValue(Varbits.IN_WILDERNESS) == 1)
		{
			checkBorrowedItemsInWilderness();
			armLenderWildernessAlert();
		}
	}

	// FIXED: React to the Cloud Sync toggle at runtime. Previously startKeepalive()/connect()
	// only ran once in startUp() (gated on enableRelaySync), so enabling Cloud Sync mid-session
	// did nothing until a full RuneLite restart - the relay was never kept warm, it spun down,
	// and invite codes were wiped, which is why joiners saw "invalid or expired" for valid codes.
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("lendingtracker".equals(event.getGroup())
			&& ("webhookPingMe".equals(event.getKey()) || "webhookDiscordId".equals(event.getKey())))
		{
			updateMyDiscordId();
			return;
		}
		if (!"lendingtracker".equals(event.getGroup()) || !"enableRelaySync".equals(event.getKey()))
		{
			return;
		}

		if (config.enableRelaySync())
		{
			log.debug("Cloud Sync enabled - starting relay keepalive and connection");
			relaySyncService.startKeepalive();

			LendingGroup activeGroup = groupService.getActiveGroup();
			String playerName = getCurrentPlayerName();
			if (activeGroup != null && playerName != null)
			{
				// startSync handles connect + room join + HMAC secret for the active group
				groupService.startSync(activeGroup.getId(), playerName);
			}
			else
			{
				// No active group yet - at least open the socket so a later join is instant
				relaySyncService.connect();
			}
		}
		else
		{
			log.debug("Cloud Sync disabled - tearing down relay keepalive and connection");
			relaySyncService.stopKeepalive();
			relaySyncService.disconnect();
		}
	}

	// NOTE: the old "lending interface" parser (widget group 334, RS2's item-lend
	// screen) was removed — group 334 is actually the OSRS trade CONFIRM screen,
	// so that flow never fired correctly. Loans are now recorded from the real
	// trade window by TradeLoanTracker, which also detects returns when the
	// borrower hands the item back in a trade.

	// --- Scheduled Tasks ---

	private void checkOverdueLoans()
	{
		String me = getCurrentPlayerName();
		List<LendingEntry> overdueEntries = dataService.getOverdueEntries(me);
		if (overdueEntries.isEmpty()) { return; }
		// Grouped per borrower and day, so one trade's worth of overdue gear is one
		// Discord post rather than one per item.
		java.util.Map<String, List<LendingEntry>> overduePosts = new java.util.LinkedHashMap<>();
		for (LendingEntry entry : overdueEntries)
		{
			long daysOverdue = ChronoUnit.DAYS.between(Instant.ofEpochMilli(entry.getDueDate()), Instant.now());
			if (daysOverdue > 0 && daysOverdue % Math.max(1, config.overdueReminderFrequency()) == 0)
			{
				Long last = overdueRemindedAt.put(entry.getId(), daysOverdue);
				if (last != null && last == daysOverdue) { continue; }
				// With running-tally returns a loan can stay open past due while the
				// BORROWER's side is fully home (only collateral still to hand back) —
				// don't frame that as an overdue item on the borrower.
				String message = entry.outstandingLentQty() > 0
					? "Overdue loan: " + entry.getItemName() + " (" + daysOverdue + " days overdue)"
					: "Open loan: " + entry.getItemName() + " — items returned, collateral still to be handed back";
				// The Discord post is its own setting - someone who silences the
				// popups may still want the channel to hear about it.
				if (entry.outstandingLentQty() > 0)
				{
					// Per group too: each group's loans go to that group's channel.
					overduePosts.computeIfAbsent(entry.getGroupId() + "|"
						+ String.valueOf(entry.getBorrower()).toLowerCase() + "|" + daysOverdue,
						k -> new ArrayList<>()).add(entry);
				}
				if (!config.enableNotifications()) { continue; }
				notifier.notify(message);
				// playSoundEffect must run on the client thread. checkOverdueLoans runs on
				// the shared executor, so this needs the same hop the request-notification
				// path already makes.
				if (config.enableSoundAlerts())
				{
					clientThread.invokeLater(() -> client.playSoundEffect(SoundEffectID.UI_BOOP));
				}
			}
		}
		for (java.util.Map.Entry<String, List<LendingEntry>> post : overduePosts.entrySet())
		{
			String day = post.getKey().substring(post.getKey().lastIndexOf('|') + 1);
			discordWebhook.postBatch(DiscordWebhook.Event.OVERDUE, post.getValue(), me, day, null);
		}
	}

	private void syncGroupData()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (g != null) { groupService.syncAllEntries(g.getId(), dataService.getActiveEntries()); }
		// Data-resync heartbeat: push our full state every cycle even with no active
		// loans (syncAllEntries no-ops when we have none). This re-broadcasts our
		// roster/data so a peer that missed a live update converges, and it flushes
		// any changes made while we were briefly disconnected. (Online/offline status
		// is handled separately by the relay's presence broadcast, not this push.)
		groupService.announcePresence();
		// Keep an OPEN multi-use group code alive on the relay (stored codes
		// expire after 24h; this refreshes while an invite-permitted member is on)
		groupService.refreshGroupCodePresence();
	}

	// Resolved requests linger so both parties can observe the outcome; keep them at
	// least this long regardless of dataRetentionDays, so a low retention setting
	// can't prune a request before an offline requester ever syncs the result.
	private static final long REQUEST_RETENTION_MS = 14L * 86400000L;
	private void cleanupOldRecords()
	{
		long now = System.currentTimeMillis();

		// Expiry runs regardless of the retention setting. It uses its own fixed
		// window, and "keep forever" is a promise about returned LOAN RECORDS - the
		// users who set it are the ones most likely to have years of unanswered
		// requests piling up, so exempting them fixes nothing for exactly the people
		// who need it most.
		if (!dataService.expirePendingRequests(now - DataService.PENDING_REQUEST_EXPIRY_MS).isEmpty())
		{
			// Announce them through the normal path so the persisted de-dup set
			// applies. Whichever member's timer fires first does the expiring, and
			// everyone else learns about it as a synced status change - one route
			// for both, so the requester is told exactly once either way.
			checkForRequestNotifications();
			// Cards for the expired requests are still on screen and still look
			// actionable until the panel is redrawn.
			refreshPanel();
		}

		int days = config.dataRetentionDays();
		if (days <= 0) { return; }
		dataService.deleteOldReturnedEntries(now - (days * 86400000L));
		// Runs after the expiry above: a request only becomes prunable once it stops
		// being pending, so this is what eventually clears what expiry marked.
		dataService.pruneResolvedRequests(now - Math.max(days * 86400000L, REQUEST_RETENTION_MS));
	}

	private void updateMarketplacePrices()
	{
		try
		{
			String groupId = groupService.getCurrentGroupIdUnchecked();
			if (groupId == null || groupId.isEmpty()) { return; }
			String me = getCurrentPlayerName();
			if (me == null) { return; }
			List<LendingEntry> available = dataService.getAvailable(groupId);
			if (available == null || available.isEmpty()) { return; }

			// Our own listings only. getAvailable returns the whole group, and
			// writing another member's row back stamps it with OUR clock - which
			// both fights their edits and lets a routine price refresh out-rank a
			// removal they made, putting the listing back permanently.
			List<LendingEntry> toUpdate = available.stream()
				.filter(e -> me.equalsIgnoreCase(e.getLender()))
				.filter(e -> e.getBorrower() == null || e.getBorrower().isEmpty())
				.collect(Collectors.toList());
			if (toUpdate.isEmpty()) { return; }

			clientThread.invokeLater(() ->
			{
				// Only rows whose price actually moved get written. Writing all of
				// them because one changed re-dates the whole marketplace every
				// twelve hours for no reason.
				List<LendingEntry> changed = new ArrayList<>();
				for (LendingEntry entry : toUpdate)
				{
					if (entry.getItemId() > 0)
					{
						long p = itemManager.getItemPrice(entry.getItemId());
						if (p > 0 && p != entry.getValue()) { entry.setValue(p); changed.add(entry); }
					}
				}
				if (!changed.isEmpty())
				{
					for (LendingEntry e : changed)
					{
						dataService.updateAvailable(groupId, e.getLender(), e.getItem(), e.getItemId(), e);
					}
					if (newPanel != null) { SwingUtilities.invokeLater(() -> newPanel.refresh()); }
				}
			});
		}
		catch (Exception e) { log.warn("Failed to update marketplace prices: {}", e.getMessage()); }
	}

	// --- Menu Handlers ---

	private void handleAddToAvailableList(MenuOptionClicked event)
	{
		String itemName = event.getMenuTarget().replaceAll("<[^>]*>", "").trim();
		// getItemId() is the item; getId() is the menu entry's identifier, which is
		// only sometimes the same thing.
		int itemId = event.getItemId() > 0 ? event.getItemId() : event.getId();
		if (itemName.isEmpty()) { return; }

		// Kit on? List the plain one. That is what would change hands, and it saves
		// stripping gear just to put it up.
		int lendable = lendableIdCached(itemId);
		if (lendable <= 0)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>" + ("Lending Tracker: " + itemName + " can't be lent as it is."), "");
			return;
		}
		if (lendable != itemId)
		{
			itemId = lendable;
			try
			{
				itemName = itemManager.getItemComposition(lendable).getName();
			}
			catch (Exception e)
			{
				log.debug("No name for {}: {}", lendable, e.getMessage());
			}
		}
		final String listedName = itemName;
		final int listedId = itemId;

		SwingUtilities.invokeLater(() ->
		{
			String input = JOptionPane.showInputDialog(null,
				"How many " + listedName + " are you lending?", "Add to Available List", JOptionPane.QUESTION_MESSAGE);
			if (input == null || input.isEmpty()) { return; }
			try
			{
				int qty = Integer.parseInt(input);
				if (qty <= 0)
				{
					JOptionPane.showMessageDialog(null, "Enter how many you are lending - one or more.",
						"Invalid Input", JOptionPane.ERROR_MESSAGE);
					return;
				}
				LendingEntry entry = new LendingEntry();
				entry.setId(UUID.randomUUID().toString());
				entry.setItem(listedName);
				entry.setItemId(listedId);
				entry.setQuantity(qty);
				entry.setValue(calculateItemValue(listedId, qty));
				entry.setLender(client != null && client.getLocalPlayer() != null
					? client.getLocalPlayer().getName() : "Unknown");
				entry.setLendTime(Instant.now().toEpochMilli());
				dataService.addToAvailableList(entry, groupService.getCurrentGroupId());
				refreshPanel();
			}
			catch (NumberFormatException e)
			{
				JOptionPane.showMessageDialog(null, "Please enter a valid number.", "Invalid Input", JOptionPane.ERROR_MESSAGE);
			}
		});
	}

	private void handleLendToGroup(MenuOptionClicked event)
	{
		String target = event.getMenuTarget();
		if (target == null || target.isEmpty()) { return; }

		String itemName = Text.removeTags(target);
		int itemId = -1;
		try
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
			if (inv != null)
			{
				Item[] items = inv.getItems();
				int slot = event.getParam0();
				if (slot >= 0 && slot < items.length) { itemId = items[slot].getId(); }
			}
		}
		catch (Exception ex) { log.error("Could not get item ID from inventory", ex); }

		if (itemId == -1)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>" + ("Error: Could not determine item ID"), "");
			return;
		}
		String gid = groupService.getCurrentGroupId();
		if (gid == null || gid.isEmpty())
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>" + ("Error: You must be in a group to lend items"), "");
			return;
		}

		int itemPrice = 0;
		try
		{
			itemPrice = (int) Math.min(itemManager.getItemPrice(itemId), Integer.MAX_VALUE);
			int lendableHere = lendableIdCached(itemId);
			if (lendableHere > 0 && lendableHere != itemId)
			{
				itemId = lendableHere;
				itemName = itemManager.getItemComposition(lendableHere).getName();
			}
			if (lendableHere <= 0)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff0000>" + ("Cannot lend " + itemName + " - This item is untradeable"), "");
				return;
			}
		}
		catch (Exception ex) { log.warn("Could not check tradeability: {}", itemId, ex); }

		final String fn = itemName; final int fi = itemId; final int fp = itemPrice;
		SwingUtilities.invokeLater(() ->
		{
			try { showLendItemDialog(fn, fi, gid, fp); }
			catch (Exception ex)
			{
				log.error("Error in showLendItemDialog", ex);
				clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff0000>" + ("ERROR: Could not open lend dialog - " + ex.getMessage()), ""));
			}
		});
	}

	private void handlePlayerInvite(MenuOptionClicked event)
	{
		String target = event.getMenuTarget();
		if (target == null || target.isEmpty()) { return; }
		String playerName = extractPlayerNameFromTarget(target);
		if (playerName == null || playerName.isEmpty()) { return; }
		if (!canCurrentUserInvite()) { showNotification("Permission Denied", "Only owners/admins can invite"); return; }
		LendingGroup group = groupService.getActiveGroup();
		if (group == null) { showNotification("No Group", "No active lending group selected"); return; }
		String msg = "Join my lending group '" + group.getName() + "' with code: " + group.getInviteCode();
		SwingUtilities.invokeLater(() -> showInviteDialog(playerName, msg));
	}

	// --- Dialogs ---

	private void showLendItemDialog(String itemName, int itemId, String groupId, int itemPrice)
	{
		JDialog dlg = new JDialog((Frame) null, "Lend " + itemName + " to Group", false);
		dlg.setSize(500, 500);
		dlg.setLocationRelativeTo(null);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel main = new JPanel(new BorderLayout(10, 10));
		main.setBackground(ColorScheme.DARK_GRAY_COLOR);
		main.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel itemLabel = new JLabel("Item: " + itemName);
		itemLabel.setForeground(Color.WHITE);
		itemLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(itemLabel);
		content.add(Box.createVerticalStrut(15));

		SpinnerNumberModel colModel = new SpinnerNumberModel(itemPrice, 0, Integer.MAX_VALUE, 100000);
		JSpinner colSpinner = new JSpinner(colModel);
		colSpinner.setPreferredSize(new Dimension(150, 25));
		content.add(labeledRow("Collateral (GP):", colSpinner));
		content.add(Box.createVerticalStrut(5));

		SpinnerNumberModel pctModel = new SpinnerNumberModel(100, 0, 1000, 10);
		JSpinner pctSpinner = new JSpinner(pctModel);
		pctSpinner.setPreferredSize(new Dimension(80, 25));
		pctSpinner.addChangeListener(ev -> colSpinner.setValue((int) (itemPrice * ((int) pctSpinner.getValue() / 100.0))));
		content.add(labeledRow("Collateral % (100% = price):", pctSpinner));
		content.add(Box.createVerticalStrut(10));

		SpinnerNumberModel durModel = new SpinnerNumberModel(24, 1, 168, 1);
		JSpinner durSpinner = new JSpinner(durModel);
		durSpinner.setPreferredSize(new Dimension(100, 25));
		content.add(labeledRow("Duration (hours):", durSpinner));
		content.add(Box.createVerticalStrut(5));

		JCheckBox noDur = new JCheckBox("No Duration (Keep Until Removed)");
		noDur.setBackground(ColorScheme.DARK_GRAY_COLOR);
		noDur.setForeground(Color.WHITE);
		noDur.addActionListener(ev -> durSpinner.setEnabled(!noDur.isSelected()));
		noDur.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(noDur);
		content.add(Box.createVerticalStrut(10));

		JLabel notesLbl = new JLabel("Notes (optional):");
		notesLbl.setForeground(Color.WHITE);
		notesLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(notesLbl);
		content.add(Box.createVerticalStrut(5));
		JTextArea notesArea = new JTextArea(3, 30);
		notesArea.setLineWrap(true);
		notesArea.setWrapStyleWord(true);
		JScrollPane scroll = new JScrollPane(notesArea);
		scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		content.add(scroll);
		main.add(content, BorderLayout.CENTER);

		JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		btns.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton addBtn = new JButton("Add to Marketplace");
		addBtn.addActionListener(ev -> addToMarketplace(dlg, itemName, itemId, groupId, itemPrice,
			(int) colSpinner.getValue(), noDur.isSelected() ? 0 : (int) durSpinner.getValue(),
			noDur.isSelected(), notesArea.getText().trim()));
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.addActionListener(ev -> dlg.dispose());
		btns.add(addBtn);
		btns.add(cancelBtn);
		main.add(btns, BorderLayout.SOUTH);

		dlg.add(main);
		dlg.getRootPane().setDefaultButton(addBtn);
		dlg.setVisible(true);
		dlg.toFront();
		dlg.requestFocus();
	}

	private void addToMarketplace(JDialog dlg, String itemName, int itemId, String groupId,
		int itemPrice, int collateral, int duration, boolean noDuration, String notes)
	{
		try
		{
			long gePrice = itemPrice > 0 ? itemPrice : itemManager.getItemPrice(itemId);
			StringBuilder s = new StringBuilder();
			s.append("Item: ").append(itemName).append("\n");
			s.append("GE Value: ").append(QuantityFormatter.quantityToStackSize(gePrice)).append(" GP\n");
			if (collateral > 0) { s.append("Collateral: ").append(collateral).append(" GP\n"); }
			s.append("Duration: ").append(noDuration ? "No limit" : duration + " hours").append("\n");
			if (!notes.isEmpty()) { s.append("Notes: ").append(notes).append("\n"); }
			s.append("\nAdd this item to the marketplace?");
			if (JOptionPane.showConfirmDialog(dlg, s.toString(), "Confirm Listing",
				JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { return; }

			String player = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Unknown";
			LendingEntry offer = new LendingEntry();
			offer.setLender(player);
			offer.setBorrower("");
			offer.setItem(itemName);
			offer.setItemId(itemId);
			offer.setQuantity(1);
			offer.setCollateralValue(collateral);
			offer.setCollateralType(collateral > 0 ? "GP" : "none");
			offer.setNotes(notes);
			offer.setValue(gePrice);
			offer.setGroupId(groupId);
			offer.setLendTime(System.currentTimeMillis());
			offer.setDueTime(noDuration ? Long.MAX_VALUE : System.currentTimeMillis() + (duration * 3600000L));
			offer.setReturnedAt(0L);
			offer.setId(UUID.randomUUID().toString());

			dataService.addAvailable(groupId, player, offer);
			dlg.dispose();
			if (newPanel != null) { SwingUtilities.invokeLater(() -> newPanel.refresh()); }
			clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>" + ("Added " + itemName + " to group marketplace"), ""));
		}
		catch (Exception ex) { log.error("Error adding to marketplace", ex); }
	}

	private JPanel labeledRow(String label, JComponent field)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel lbl = new JLabel(label);
		lbl.setForeground(Color.WHITE);
		row.add(lbl);
		row.add(field);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		return row;
	}

	private void showInviteDialog(String playerName, String inviteMessage)
	{
		JDialog dlg = new JDialog();
		dlg.setTitle("Send Invite to " + playerName);
		dlg.setModal(true);
		dlg.setSize(400, 230);
		dlg.setLocationRelativeTo(null);

		JPanel p = new JPanel(new GridBagLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		GridBagConstraints c = new GridBagConstraints();

		c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.insets = new Insets(10, 10, 5, 10);
		JLabel lbl = new JLabel("<html>Send this PM to <b>" + playerName + "</b>:</html>");
		lbl.setForeground(Color.WHITE);
		p.add(lbl, c);

		c.gridy = 1; c.insets = new Insets(0, 10, 10, 10);
		JLabel hint = new JLabel("<html>Click <b>Copy Message</b>, then PM them in-game and press <b>Ctrl+V</b> to paste.</html>");
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
		p.add(hint, c);

		c.gridy = 2; c.insets = new Insets(5, 10, 10, 10);
		JTextField msgField = new JTextField(inviteMessage);
		msgField.setPreferredSize(new Dimension(350, 25));
		msgField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		msgField.setForeground(Color.WHITE);
		msgField.setEditable(false);
		msgField.selectAll();
		p.add(msgField, c);

		c.gridy = 3; c.gridwidth = 1; c.insets = new Insets(10, 10, 10, 5);
		JButton copyBtn = new JButton("Copy Message");
		copyBtn.addActionListener(ev ->
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
				new java.awt.datatransfer.StringSelection(inviteMessage), null);
			JOptionPane.showMessageDialog(dlg, "Copied!", "Copied", JOptionPane.INFORMATION_MESSAGE);
		});
		p.add(copyBtn, c);

		c.gridx = 1; c.insets = new Insets(10, 5, 10, 10);
		JButton closeBtn = new JButton("Close");
		closeBtn.addActionListener(ev -> dlg.dispose());
		p.add(closeBtn, c);
		dlg.add(p);
		dlg.setVisible(true);
	}

	// --- PM / Invite Processing ---

	private void handlePrivateMessage(String sender, String message)
	{
		if (sender == null || message == null) { return; }
		String lower = message.toLowerCase();
		if (lower.contains("join") && lower.contains("code:")) { processInviteRequest(sender, message); }
	}

	private void processInviteRequest(String sender, String message)
	{
		String[] parts = message.split("code:");
		if (parts.length < 2) { return; }
		String[] codeWords = parts[1].trim().split("\\s+");
		if (codeWords.length == 0 || codeWords[0].length() < 8) { return; }
		String code = codeWords[0].substring(0, 8);

		LendingGroup target = null;
		for (LendingGroup g : groupService.getAllGroups())
		{
			if (code.equals(g.getInviteCode())) { target = g; break; }
		}
		if (target == null) { return; }

		String me = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (me == null) { return; }
		if (!groupService.isOwner(target.getId(), me) && !groupService.isAdmin(target.getId(), me)) { return; }
		if (target.hasMember(sender))
		{
			showNotification("Already Member", sender + " is already in " + target.getName());
			return;
		}

		final LendingGroup fg = target;
		SwingUtilities.invokeLater(() ->
		{
			if (JOptionPane.showConfirmDialog(null,
				sender + " wants to join '" + fg.getName() + "'. Accept?",
				"Group Invite", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
			{
				groupService.addMember(fg.getId(), sender, "member");
				showNotification("Added", sender + " joined " + fg.getName());
				refreshPanel();
			}
		});
	}

	// --- Wilderness ---

	// Cooldown so repeatedly hopping the ditch (banking between trips) doesn't
	// fire a notification storm
	private static final long WILDERNESS_WARN_COOLDOWN_MS = 60_000L;
	// How long the borrower must stay in the wilderness before their lenders are
	// alerted — a quick ditch-hop shouldn't page anyone
	private static final long LENDER_ALERT_AFTER_MS = 45_000L;
	// Ignore alert events older than this (the local sync queue replays history)
	private static final long LENDER_ALERT_MAX_AGE_MS = 5 * 60_000L;
	private long lastWildernessWarnAt;
	// Bumped on every wilderness enter/exit so a scheduled 45s check can tell
	// whether the visit it was armed for is still the current one
	private volatile int wildernessEpisode;
	private volatile boolean lenderAlertSentThisEpisode;

	private void checkBorrowedItemsInWilderness()
	{
		if (config.wildernessGuard() == LendingTrackerConfig.GuardMode.OFF) { return; }
		if (System.currentTimeMillis() - lastWildernessWarnAt < WILDERNESS_WARN_COOLDOWN_MS) { return; }

		// Only items actually being carried (inventory or equipment) are at risk —
		// borrowed gear AND collateral held as the lender both count.
		List<LendingEntry> borrowed = tradeLoanTracker.carriedBorrowedEntries();
		List<LendingEntry> collateral = tradeLoanTracker.carriedCollateralEntries();
		if (borrowed.isEmpty() && collateral.isEmpty()) { return; }

		lastWildernessWarnAt = System.currentTimeMillis();
		StringBuilder summary = new StringBuilder();
		if (!borrowed.isEmpty())
		{
			long total = borrowed.stream().mapToLong(LendingEntry::getValue).sum();
			summary.append(borrowed.size()).append(" borrowed item(s) worth ")
				.append(QuantityFormatter.quantityToStackSize(total)).append(" GP");
		}
		if (!collateral.isEmpty())
		{
			if (summary.length() > 0) { summary.append(" and "); }
			summary.append("collateral you hold for ").append(collateral.size()).append(" loan(s)");
		}

		if (config.enableNotifications())
		{
			notifier.notify("[Lending Tracker] You entered the Wilderness carrying " + summary + "!");
		}
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff0000>" + ("[Lending Tracker] WARNING: you are in the Wilderness carrying " + summary
				+ ". Losing these still leaves you responsible for them!"), "");
	}

	/**
	 * Arm the 45-second sustained-stay check for the current wilderness visit.
	 * If the borrower is still in level 1+ wilderness carrying borrowed items when
	 * it fires, each affected lender is alerted through the signed sync channel.
	 */
	private void armLenderWildernessAlert()
	{
		final int episode = wildernessEpisode;
		executor.schedule(() -> clientThread.invokeLater(() ->
		{
			if (episode != wildernessEpisode || lenderAlertSentThisEpisode)
			{
				return; // left the wilderness (or already alerted) — stand down
			}
			if (client.getVarbitValue(Varbits.IN_WILDERNESS) != 1)
			{
				return;
			}
			List<LendingEntry> borrowed = tradeLoanTracker.carriedBorrowedEntries();
			List<LendingEntry> collateral = tradeLoanTracker.carriedCollateralEntries();
			if (borrowed.isEmpty() && collateral.isEmpty())
			{
				return;
			}

			lenderAlertSentThisEpisode = true;
			// One event per distinct counterparty (not per item): the receiver only
			// needs one ping, and every publishEvent pushes a full state snapshot
			java.util.Set<String> alerted = new java.util.HashSet<>();
			for (LendingEntry e : borrowed)
			{
				String lender = e.getLender() != null ? e.getLender().toLowerCase() : "";
				if (alerted.add("L:" + lender))
				{
					groupService.publishEvent(GroupService.SyncEventType.WILDERNESS_ALERT, e.getId(), null);
				}
			}
			// I'm the lender carrying the borrower's collateral — their property is
			// at risk, so THEY get the ping
			for (LendingEntry e : collateral)
			{
				String borrower = e.getBorrower() != null ? e.getBorrower().toLowerCase() : "";
				if (alerted.add("B:" + borrower))
				{
					groupService.publishEvent(GroupService.SyncEventType.WILDERNESS_ALERT_COLLATERAL, e.getId(), null);
				}
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>" + ("[Lending Tracker] You've been in the Wilderness over 45 seconds with loaned property — the other party has been notified."), "");
		}), LENDER_ALERT_AFTER_MS, TimeUnit.MILLISECONDS);
	}

	/**
	 * A wilderness alert sync event arrived — notify the affected party:
	 * WILDERNESS_ALERT → I'm the lender, my lent item is at risk with the borrower.
	 * WILDERNESS_ALERT_COLLATERAL → I'm the borrower, the collateral I gave the
	 * lender is at risk with them.
	 */
	private void handleWildernessAlert(GroupService.SyncEvent event)
	{
		if (!config.alertLenderWilderness() || !config.enableNotifications()) { return; }
		// The local sync queue replays recent history on login — never re-alert
		// for a stale event
		if (System.currentTimeMillis() - event.getTimestamp() > LENDER_ALERT_MAX_AGE_MS) { return; }

		String me = getCurrentPlayerName();
		if (me == null) { return; }

		LendingEntry entry = dataService.getActiveEntries().stream()
			.filter(e -> event.getDataId().equals(e.getId()))
			.findFirst().orElse(null);
		if (entry == null) { return; }

		if (event.getType() == GroupService.SyncEventType.WILDERNESS_ALERT
			&& me.equalsIgnoreCase(entry.getLender()))
		{
			String borrower = event.getPublisher() != null ? event.getPublisher() : entry.getBorrower();
			notifier.notify("[Lending Tracker] " + borrower + " has been in the Wilderness for 45+ seconds carrying your "
				+ entry.getItemName() + "!");
		}
		else if (event.getType() == GroupService.SyncEventType.WILDERNESS_ALERT_COLLATERAL
			&& me.equalsIgnoreCase(entry.getBorrower()))
		{
			String lender = event.getPublisher() != null ? event.getPublisher() : entry.getLender();
			notifier.notify("[Lending Tracker] " + lender + " has been in the Wilderness for 45+ seconds carrying the collateral you put up for "
				+ entry.getItemName() + "!");
		}
	}

	// --- Helpers ---



	private boolean canCurrentUserInvite()
	{
		// Asked for in issue #1: some people just don't want a plugin adding a
		// right-click entry, even one they're entitled to.
		if (config != null && !config.showInviteMenuOption()) { return false; }
		if (client == null || client.getLocalPlayer() == null) { return false; }
		String name = client.getLocalPlayer().getName();
		if (name == null || name.isEmpty() || groupService == null) { return false; }
		LendingGroup g = groupService.getActiveGroup();
		if (g == null) { return false; }
		return groupService.isOwner(g.getId(), name) || groupService.isAdmin(g.getId(), name);
	}

	private String extractPlayerNameFromTarget(String target)
	{
		if (target == null) { return null; }
		String[] parts = target.replaceAll("<[^>]*>", "").trim().split("\\s+");
		return parts.length > 0 ? parts[0] : null;
	}

	private long calculateItemValue(int itemId, int quantity)
	{
		return itemId <= 0 ? 0 : (long) itemManager.getItemPrice(itemId) * quantity;
	}

	private void showNotification(String title, String message)
	{
		SwingUtilities.invokeLater(() ->
			JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE));
	}

	// --- Direct Requests (borrow requests / lend offers) ---

	// Config key prefix for the per-account set of already-notified request keys.
	// Persisted so restarts don't replay notifications; a set (not a timestamp
	// cursor) so an out-of-order or offline-merged request is never skipped.
	private static final String REQUEST_NOTIFY_SET_PREFIX = "notifiedRequests.";
	private static final int REQUEST_NOTIFY_SET_CAP = 2000;
	private final Object requestNotifyLock = new Object();

	/**
	 * Send a borrow request to a lender. The request is stored in the group's
	 * synced data, so it reaches the lender live if they're online (relay
	 * broadcast / same-machine poll) or on their next login (join catch-up).
	 *
	 * @return true if the request was created, false if there's no active group
	 */
	public boolean sendBorrowRequest(String borrower, String lender, String itemName, int itemId, int quantity, int durationDays)
	{
		return createRequest(LendingRequest.TYPE_BORROW_REQUEST, borrower, lender,
			itemName, itemId, quantity, durationDays, null);
	}

	/**
	 * Send a lend offer to a player who posted a "Looking For" request.
	 *
	 * @return true if the offer was created, false if there's no active group
	 */
	public boolean sendLendOffer(String lender, String borrower, String itemName, int quantity, int durationDays, String message, String durationDisplay)
	{
		return createRequest(LendingRequest.TYPE_LEND_OFFER, lender, borrower,
			itemName, -1, quantity, durationDays, message);
	}

	/**
	 * Outcome of proposing a loan removal, so the UI can explain the routing.
	 */
	public enum RemovalRoute { MUTUAL, STAFF, ALREADY_PENDING, NOT_ALLOWED, NO_REVIEWER }

	/**
	 * Propose removing an active loan (accidental Loan-mode, mis-recorded trade…).
	 *
	 * Routing, per group policy: the loan's counterparty approving is ALWAYS the
	 * first course of action when possible — i.e. when the counterparty is (still)
	 * a member of the group, online or not (the request syncs and they answer on
	 * their next login). Staff review is reserved for loans whose counterparty was
	 * never a group member (mobile / no-plugin borrowers can't approve anything),
	 * or as escalation after a mutual request was declined.
	 */
	public RemovalRoute requestLoanRemoval(LendingEntry loan, String reason, boolean escalateToStaff)
	{
		String me = getCurrentPlayerName();
		String groupId = groupService.getCurrentGroupIdUnchecked();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || groupId == null || group == null || loan == null)
		{
			return RemovalRoute.NOT_ALLOWED;
		}

		// Only a party to the loan may propose its removal
		boolean iAmLender = me.equalsIgnoreCase(loan.getLender());
		boolean iAmBorrower = me.equalsIgnoreCase(loan.getBorrower());
		if (!iAmLender && !iAmBorrower)
		{
			return RemovalRoute.NOT_ALLOWED;
		}
		if (dataService.hasPendingRemovalFor(groupId, loan.getId()))
		{
			return RemovalRoute.ALREADY_PENDING;
		}

		String counterparty = iAmLender ? loan.getBorrower() : loan.getLender();
		boolean counterpartyInGroup = counterparty != null && group.hasMember(counterparty);
		// Escalation to staff is only valid when mutual consent is impossible
		// (counterparty never in the group) or already failed (declined)
		boolean canEscalate = !counterpartyInGroup
			|| dataService.hasDeclinedMutualRemovalFor(groupId, loan.getId());

		String type;
		String to;
		if (counterpartyInGroup && !(escalateToStaff && canEscalate))
		{
			type = LendingRequest.TYPE_REMOVAL_MUTUAL;
			to = counterparty;
		}
		else
		{
			// Don't file a dispute nobody in this group is allowed to rule on. In a
			// small group the only owner is often the other party to the loan, and
			// then there is no eligible reviewer at all - the request would just sit
			// there until it expired, which is a worse answer than saying so now.
			if (!dataService.hasAnyStaffReviewerFor(groupId, group, me, loan.getId()))
			{
				return RemovalRoute.NO_REVIEWER;
			}
			type = LendingRequest.TYPE_REMOVAL_STAFF;
			to = ""; // adjudicated by any eligible (uninvolved) owner/co-owner
		}

		LendingRequest request = new LendingRequest();
		request.setId(UUID.randomUUID().toString());
		request.setGroupId(groupId);
		request.setType(type);
		request.setFrom(me);
		request.setTo(to);
		request.setEntryId(loan.getId());
		request.setItemName(loan.getItem());
		request.setItemId(loan.getItemId());
		request.setQuantity(loan.getQuantity());
		request.setMessage(reason);
		request.setStatus(LendingRequest.STATUS_PENDING);
		long now = System.currentTimeMillis();
		request.setCreatedAt(now);
		request.setUpdatedAt(now);

		dataService.addRequest(groupId, request);
		refreshPanel();
		return LendingRequest.TYPE_REMOVAL_MUTUAL.equals(type) ? RemovalRoute.MUTUAL : RemovalRoute.STAFF;
	}

	/**
	 * Execute removals whose requests have been APPROVED. Runs after every sync
	 * and at login. Only the LENDER's client actually removes the loan (it is
	 * authoritative for its own loans — an approval executed anywhere else would
	 * just be resurrected by the lender's next push). Fallback: if the lender has
	 * left the group, the requester's client may execute a staff-approved removal.
	 */
	public void applyApprovedRemovals()
	{
		String me = getCurrentPlayerName();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || group == null)
		{
			return;
		}

		for (LendingRequest r : dataService.getRequests(group.getId()))
		{
			if (!r.isRemoval() || !LendingRequest.STATUS_ACCEPTED.equals(r.getStatus()))
			{
				continue;
			}
			LendingEntry entry = dataService.getActiveEntry(r.getEntryId());
			if (entry == null)
			{
				continue; // already executed — idempotent
			}

			boolean iAmLender = me.equalsIgnoreCase(entry.getLender());
			boolean lenderGone = entry.getLender() == null || !group.hasMember(entry.getLender());
			boolean requesterFallback = r.isStaffRemoval() && lenderGone && me.equalsIgnoreCase(r.getFrom());
			if (!iAmLender && !requesterFallback)
			{
				continue;
			}

			String stamp = r.isStaffRemoval()
				? "[Removed by staff approval, requested by " + r.getFrom() + "]"
				: "[Removed by mutual consent of " + r.getFrom() + " and " + r.getTo() + "]";
			if (dataService.removeLoanApproved(entry.getId(), stamp))
			{
				discordWebhook.post(DiscordWebhook.Event.REMOVED, entry, me);
				clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff0000>" + ("[Lending Tracker] Loan removed after approval: " + entry.getItemName()
						+ " (" + entry.getLender() + " -> " + entry.getBorrower() + ")."), ""));
				refreshPanel();
			}
		}
	}

	private boolean createRequest(String type, String from, String to,
		String itemName, int itemId, int quantity, int durationDays, String message)
	{
		String groupId = groupService.getCurrentGroupIdUnchecked();
		if (groupId == null || groupId.isEmpty() || from == null || to == null)
		{
			return false;
		}

		LendingRequest request = new LendingRequest();
		request.setId(UUID.randomUUID().toString());
		request.setGroupId(groupId);
		request.setType(type);
		request.setFrom(from);
		request.setTo(to);
		request.setItemName(itemName);
		request.setItemId(itemId);
		request.setQuantity(quantity);
		request.setDurationDays(durationDays);
		request.setMessage(message);
		request.setStatus(LendingRequest.STATUS_PENDING);
		long now = System.currentTimeMillis();
		request.setCreatedAt(now);
		request.setUpdatedAt(now);

		dataService.addRequest(groupId, request);
		refreshPanel();
		return true;
	}

	/**
	 * Notify about newly arrived requests addressed to this player, and about
	 * responses to requests this player sent. Called after every data sync.
	 *
	 * De-duplication uses a persisted per-account set of "requestId:kind" keys, so
	 * each request notifies exactly once — even across restarts, even though
	 * resolved requests linger in the synced snapshot, and regardless of the order
	 * in which requests arrive through the union merge. Synchronized so two sync
	 * threads can't both notify for the same request.
	 */
	private void checkForRequestNotifications()
	{
		if (!config.enableNotifications()) { return; }

		synchronized (requestNotifyLock)
		{
			String me = getCurrentPlayerName();
			LendingGroup activeGroup = groupService.getActiveGroup();
			if (me == null || activeGroup == null) { return; }

			String groupId = activeGroup.getId();
			String setKey = REQUEST_NOTIFY_SET_PREFIX + me.toLowerCase();
			LinkedHashSet<String> notified = loadNotifiedSet(setKey);
			// Keys for requests still visible this round, so the size cap evicts only
			// long-gone requests and never re-notifies one that's still around.
			LinkedHashSet<String> seen = new LinkedHashSet<>();
			boolean changed = false;
			boolean playSound = false;

			for (LendingRequest r : dataService.getPendingRequestsFor(groupId, me))
			{
				String key = r.getId() + ":IN";
				seen.add(key);
				if (notified.add(key))
				{
					String what;
					if (r.isRemoval())
					{
						what = r.getFrom() + " asks to remove the loan of " + r.getItemName()
							+ " — approve or decline in the panel";
					}
					else if (r.isBorrowRequest())
					{
						what = r.getFrom() + " wants to borrow: " + r.getItemName()
							+ (r.getQuantity() > 1 ? " x" + r.getQuantity() : "")
							+ " (" + r.getDurationDays() + " days)";
					}
					else
					{
						what = r.getFrom() + " offers to lend you: " + r.getItemName()
							+ (r.getQuantity() > 1 ? " x" + r.getQuantity() : "");
					}
					notifier.notify("[Lending Tracker] " + what);
					playSound = true;
					changed = true;
				}
			}

			// Staff-review removals aren't addressed to a single player — notify every
			// eligible (uninvolved owner/co-owner) staff member once
			for (LendingRequest r : dataService.getPendingStaffRemovalsFor(groupId, me, activeGroup))
			{
				String key = r.getId() + ":STAFF";
				seen.add(key);
				if (notified.add(key))
				{
					notifier.notify("[Lending Tracker] Staff review: " + r.getFrom()
						+ " asks to remove the loan of " + r.getItemName() + " — see the panel.");
					playSound = true;
					changed = true;
				}
			}

			for (LendingRequest r : dataService.getRequestsFrom(groupId, me))
			{
				if (r.isPending()) { continue; }
				// An expiry has to be reported wherever it happened. Any member's
				// client may be the one whose cleanup timer fires first, and the
				// result reaches us as an ordinary status change - so keying off
				// the flag here, rather than off our own expiry run, is the only
				// way the requester reliably hears about it.
				if (r.isExpired() && LendingRequest.STATUS_CANCELLED.equals(r.getStatus()))
				{
					String key = r.getId() + ":EXPIRED";
					seen.add(key);
					if (notified.add(key))
					{
						notifier.notify("[Lending Tracker] " + (r.isLookingFor()
							? "Your Looking For post for " + r.getItemName() + " expired - post it again if you still need it."
							: "Your request for " + r.getItemName() + " expired — nobody answered it."));
						changed = true;
					}
					continue;
				}
				// A plain cancellation is the requester's own doing; they know.
				if (LendingRequest.STATUS_CANCELLED.equals(r.getStatus())) { continue; }
				String key = r.getId() + ":" + r.getStatus();
				seen.add(key);
				if (notified.add(key))
				{
					String verb = LendingRequest.STATUS_ACCEPTED.equals(r.getStatus()) ? "accepted" : "declined";
					notifier.notify("[Lending Tracker] " + r.getTo() + " " + verb
						+ " your request for " + r.getItemName());
					changed = true;
				}
			}

			if (playSound && config.enableSoundAlerts())
			{
				// playSoundEffect must run on the client thread; this method is called
				// from sync callbacks that run on the relay/poll executor threads.
				clientThread.invokeLater(() -> client.playSoundEffect(SoundEffectID.UI_BOOP));
			}
			if (changed)
			{
				saveNotifiedSet(setKey, notified, seen);
			}
		}
	}

	private LinkedHashSet<String> loadNotifiedSet(String key)
	{
		LinkedHashSet<String> set = new LinkedHashSet<>();
		String csv = configManager.getConfiguration("lendingtracker", key);
		if (csv != null && !csv.isEmpty())
		{
			for (String k : csv.split(","))
			{
				if (!k.isEmpty()) { set.add(k); }
			}
		}
		return set;
	}

	private void saveNotifiedSet(String key, LinkedHashSet<String> set, LinkedHashSet<String> keepAtEnd)
	{
		// Reorder so currently-visible request keys sit at the most-recent end,
		// then bound growth by keeping the last CAP. This guarantees a still-active
		// request is never evicted (which would let it re-notify) — only keys for
		// requests that have disappeared can fall off.
		LinkedHashSet<String> ordered = new LinkedHashSet<>();
		for (String k : set)
		{
			if (!keepAtEnd.contains(k)) { ordered.add(k); }
		}
		ordered.addAll(keepAtEnd);

		List<String> keys = new ArrayList<>(ordered);
		if (keys.size() > REQUEST_NOTIFY_SET_CAP)
		{
			keys = keys.subList(keys.size() - REQUEST_NOTIFY_SET_CAP, keys.size());
		}
		configManager.setConfiguration("lendingtracker", key, String.join(",", keys));
	}

	public void refreshPanel()
	{
		if (newPanel != null) { SwingUtilities.invokeLater(() -> newPanel.refresh()); }
	}

	public String getCurrentPlayerName()
	{
		if (client != null && client.getLocalPlayer() != null) { return client.getLocalPlayer().getName(); }
		String stored = configManager.getConfiguration("lendingtracker", "currentAccount");
		return (stored != null && !stored.isEmpty()) ? stored : null;
	}

	public ArmourSets getArmourSets() { return armourSets; }
	public Client getClient() { return client; }
	public ClientThread getClientThread() { return clientThread; }
	public ConfigManager getConfigManager() { return configManager; }
	public ItemManager getItemManager() { return itemManager; }
	public DataService getDataService() { return dataService; }
	public ItemCategories getItemCategories() { return itemCategories; }
	public DiscordWebhook getDiscordWebhook() { return discordWebhook; }
	public ClanRoster getClanRoster() { return clanRoster; }
	public GroupService getGroupService() { return groupService; }
	public ProofScreenshot getProofScreenshot() { return proofScreenshot; }
	public boolean isRelaySyncConnected() { return relaySyncService != null && relaySyncService.isConnected(); }
	public boolean isRelaySyncConnecting() { return relaySyncService != null && relaySyncService.isConnecting(); }
	public LendingTrackerConfig getConfig() { return config; }

	@Provides
	LendingTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LendingTrackerConfig.class);
	}
}
