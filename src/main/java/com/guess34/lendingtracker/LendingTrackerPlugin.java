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
	private volatile boolean bankSeenThisSession;
	// Set by container changes, acted on once per tick - so a bank deposit's
	// inventory and bank updates are seen together, not one half at a time.
	private volatile boolean listingsDirty;
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
	// Syncs arrive on several threads; one at a time applies agreed extensions.
	private final Object extensionLock = new Object();
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
	@Inject private net.runelite.client.ui.overlay.OverlayManager overlayManager;
	@Inject private com.guess34.lendingtracker.ui.TradeScamOverlay tradeScamOverlay;

	private LendingPanel newPanel;
	private NavigationButton navButton;
	// Local world, cached from the client thread (see onGameStateChanged). The relay
	// presence join message reads this off the ws thread, where a direct
	// client.getWorld() would be unsafe/stale.
	private volatile int lastKnownWorld;

	@Override
	protected void startUp() throws Exception
	{
		// Anything remembered from before a restart of the plugin is out of date.
		forgetContainers();
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
		overlayManager.add(tradeScamOverlay);

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
		tradeLoanTracker.setFamilyOf(id -> net.runelite.client.game.ItemVariationMapping.map(owningIdFor(id)));
		tradeLoanTracker.setKeptAmount(this::isKeepAmount);
		tradeLoanTracker.setOnTradeSettled(this::onTradeSettled);

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
			// First run an hour in: login (or starting up already logged in) runs its
			// own check once this account has caught up with the group.
			scheduledTasks.add(executor.scheduleAtFixedRate(this::hourlyOverdueCheck, 1, 1, TimeUnit.HOURS));
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
		flushListingChanges();
		groupService.stopSync();
		overlayManager.remove(tradeScamOverlay);
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
		applyAgreedExtensions();
		// Overdue loans are told at login, once this account's loans have loaded -
		// not only on the hourly timer, which may not come round before they log off.
		scheduleLoginOverdueCheck(epoch, 0);
	}

	// Set when an hourly reminder was put off for a catch-up, so only one in a row is.
	private volatile boolean overdueCheckPutOff;

	/**
	 * The hourly reminder. Put off once while still catching up with the group,
	 * rather than run on old data - but only once: if Cloud Sync can't reach the
	 * group at all, waiting won't make the data any fresher, and the reminder
	 * still matters.
	 */
	private void hourlyOverdueCheck()
	{
		LendingGroup g = groupService.getActiveGroup();
		if (!overdueCheckPutOff && config.enableRelaySync() && g != null && !groupService.canPublishRemoval(g.getId()))
		{
			overdueCheckPutOff = true;
			return;
		}
		overdueCheckPutOff = false;
		checkOverdueLoans();
	}

	/**
	 * Overdue loans are told at login, once this account's loans have loaded and -
	 * with Cloud Sync on - once it has caught up with the group, so a loan given
	 * more time, or forgiven, while this player was away isn't reported overdue.
	 * Stops waiting after about three minutes and checks anyway.
	 */
	private void scheduleLoginOverdueCheck(long epoch, int tries)
	{
		executor.schedule(() ->
		{
			if (epoch != sessionEpoch.get())
			{
				return;
			}
			LendingGroup g = groupService.getActiveGroup();
			boolean catchingUp = config.enableRelaySync() && g != null && !groupService.canPublishRemoval(g.getId());
			if (catchingUp && tries < 12)
			{
				scheduleLoginOverdueCheck(epoch, tries + 1);
				return;
			}
			checkOverdueLoans();
		}, tries == 0 ? 20 : 15, TimeUnit.SECONDS);
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
		applyAgreedExtensions();
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
		else if (event.getGroupId() == net.runelite.api.gameval.InterfaceID.BANK_DEPOSITBOX)
		{
			// A deposit box puts things in the bank without the bank being seen, so
			// the last look at it is out of date: until the bank is opened again,
			// listings may only go up, never be hidden as gone.
			bankSeenThisSession = false;
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
			listingsDirty = true;
		}
		else if (event.getContainerId() == net.runelite.api.gameval.InventoryID.INV
			&& event.getItemContainer() != null)
		{
			countById(carriedNow, event.getItemContainer().getItems());
			listingsDirty = true;
		}
		// Bank snapshot for the fungible-duplicate guard logic: owning a spare copy
		// of a collateral/borrowed item (safe in the bank) means carrying your own
		// copy isn't at-risk. Only known once the bank has been opened this session.
		if (event.getContainerId() == net.runelite.api.gameval.InventoryID.BANK
			&& event.getItemContainer() != null)
		{
			tradeLoanTracker.onBankUpdated(event.getItemContainer().getItems());
			// Only a main-game bank says anything about main-game listings - a
			// Leagues, Deadman or beta bank is a different bank under the same name.
			if (RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.STANDARD)
			{
				countById(bankedNow, event.getItemContainer().getItems());
				bankSeenThisSession = true;
				listingsDirty = true;
			}
		}
	}

	/**
	 * Say what following the bank changed: listings now showing what's actually
	 * held - more or fewer than typed - or hidden because none is held. Once per
	 * item per session, since the bank fires on every deposit. Client thread.
	 */
	private void announceFollowed(java.util.List<LendingEntry> changed)
	{
		java.util.List<String> said = new java.util.ArrayList<>();
		boolean suggestKeep = false;
		for (LendingEntry e : changed)
		{
			if (e == null || e.getHeldQty() == null || !missingOnce.add(e.getItemId())) { continue; }
			// Hidden because it's lent out: it comes back on its own, and keeping
			// it now would keep a number that still counts the loan.
			if (e.heldNone() && lentOut(e.getItemId()) > 0)
			{
				said.add(e.getItem() + " is hidden while it's out on loan");
				continue;
			}
			// Keep is refused while any of it is lent, so only offer it when it'd work.
			if (lentOut(e.getItemId()) == 0) { suggestKeep = true; }
			said.add(e.heldNone() ? e.getItem() + " is hidden (you don't have it)"
				: e.getItem() + " now shows " + e.shownQuantity());
		}
		if (said.isEmpty()) { return; }
		String list = said.size() <= 3 ? String.join(", ", said)
			: said.size() + " listings (" + String.join(", ", said.subList(0, 3)) + ", ...)";
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
			"<col=ff0000>" + ("Lending Tracker: your listings follow what you hold - " + list
				+ (suggestKeep ? ". To stop one following your bank, right-click it and pick Keep this amount." : ".")), "");
	}

	/**
	 * How many of this item's family I have out on loan right now, grouped the
	 * way listings are - a loan of notes counts against the listing of the item.
	 * Client thread only.
	 */
	private int lentOut(int itemId)
	{
		String me = getCurrentPlayerName();
		if (me == null) { return 0; }
		int family = net.runelite.client.game.ItemVariationMapping.map(owningIdFor(itemId));
		int out = 0;
		for (LendingEntry e : dataService.getActiveEntries())
		{
			if (!e.isKeptByBorrower() && me.equalsIgnoreCase(e.getLender()) && e.getItemId() > 0
				&& net.runelite.client.game.ItemVariationMapping.map(owningIdFor(e.getItemId())) == family)
			{
				out += Math.max(0, e.outstandingLentQty());
			}
		}
		return out;
	}

	private static final String KEEP_AMOUNT_PREFIX = "keepAmount.";

	/** Item ids this account told not to follow its bank. */
	private java.util.Set<Integer> keptAmount(String account)
	{
		java.util.Set<Integer> out = new java.util.HashSet<>();
		String csv = account == null ? null
			: configManager.getConfiguration("lendingtracker", KEEP_AMOUNT_PREFIX + account.toLowerCase());
		if (csv != null && !csv.isEmpty())
		{
			for (String part : csv.split(","))
			{
				try { out.add(Integer.parseInt(part.trim())); }
				catch (NumberFormatException ignored) { /* a damaged entry is just dropped */ }
			}
		}
		return out;
	}

	private void saveKeptAmount(String account, java.util.Set<Integer> kept)
	{
		if (account == null) { return; }
		String key = KEEP_AMOUNT_PREFIX + account.toLowerCase();
		if (kept.isEmpty())
		{
			configManager.unsetConfiguration("lendingtracker", key);
		}
		else
		{
			configManager.setConfiguration("lendingtracker", key,
				kept.stream().map(String::valueOf).collect(Collectors.joining(",")));
		}
	}

	/** Is this listing set to keep the number typed rather than follow the bank? */
	public boolean isKeepAmount(int itemId)
	{
		String me = getCurrentPlayerName();
		return me != null && keptAmount(me).contains(itemId);
	}

	/**
	 * Stop a listing following the bank (gear in a house or a bag, or only lending
	 * some of what you have), or start again. Keeping it keeps the number on
	 * screen right now, not whatever was first typed.
	 */
	public void setKeepAmount(int itemId, boolean keep)
	{
		setKeepAmount(itemId, keep, true);
	}

	/**
	 * @param rebase when keeping, first set the listing to the number on screen.
	 *               The edit dialog passes false: the owner has just typed the
	 *               number they want, and a follow slipping in between must not
	 *               replace it.
	 */
	public void setKeepAmount(int itemId, boolean keep, boolean rebase)
	{
		clientThread.invokeLater(() ->
		{
			String me = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
			if (me == null) { return; }
			String groupId = groupService.getCurrentGroupId();
			// Some of it is out on loan, or promised and not handed over yet: the
			// number it would keep can't tell those apart from what's here, and the
			// return would add them again.
			if (keep && rebase && groupId != null && lentOut(itemId) > 0)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff0000>Lending Tracker: some of that item is out on loan. Pick Keep this amount once your loans of it are back.", "");
				refreshPanel();
				return;
			}
			if (keep && rebase && groupId != null)
			{
				for (LendingEntry e : dataService.getOfferingsByOwner(groupId, me))
				{
					if (e.getItemId() == itemId && e.getHeldQty() != null && e.shownQuantity() > 0)
					{
						LendingEntry fixed = new LendingEntry(e);
						fixed.setQuantity(e.shownQuantity());
						fixed.setValue(e.shownValue());
						fixed.setHeldQty(null);
						dataService.updateAvailable(groupId, e.getLender(), e.getItem(), e.getItemId(), fixed);
					}
				}
			}
			java.util.Set<Integer> kept = keptAmount(me);
			boolean changed = keep ? kept.add(itemId) : kept.remove(itemId);
			if (changed) { saveKeptAmount(me, kept); }
			// A number typed while some are lent out is what's here now; say so.
			int out = keep && changed && !rebase ? lentOut(itemId) : 0;
			if (out > 0)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff0000>Lending Tracker: the " + out + " out on loan go back on that listing when they're returned.", "");
			}
			recomputeWornListings();
			refreshPanel();
		});
	}

	/**
	 * A listing was removed on purpose: stop keeping its number, so listing that
	 * item again follows the bank like any new listing. Written to the owner's
	 * own setting, so it holds even if they've just logged out.
	 */
	public void forgetKeepAmount(String account, int itemId)
	{
		if (account == null) { return; }
		clientThread.invokeLater(() ->
		{
			java.util.Set<Integer> kept = keptAmount(account);
			if (!kept.remove(itemId)) { return; }
			saveKeptAmount(account, kept);
			recomputeWornListings();
			refreshPanel();
		});
	}

	/** Drop what we know of our own containers: a restart, a new account or a different world type. */
	private void forgetContainers()
	{
		wornNow.clear();
		carriedNow.clear();
		bankedNow.clear();
		missingOnce.clear();
		bankSeenThisSession = false;
		listingsDirty = false;
	}

	/** Save and send what following the bank changed, now - before sync stops. */
	private void flushListingChanges()
	{
		// Unchecked: at the login screen the game no longer counts as logged in,
		// but this is exactly when the save matters.
		String groupId = groupService.getCurrentGroupIdUnchecked();
		if (groupId != null)
		{
			dataService.saveListings(groupId);
		}
		if (wornPushQueued.getAndSet(false))
		{
			groupService.pushStateNow();
		}
	}

	/**
	 * RuneLite closes without calling shutDown, so save what following the bank
	 * changed now. The config itself is written after plugins have had their say.
	 */
	@Subscribe
	public void onClientShutdown(net.runelite.client.events.ClientShutdown event)
	{
		String groupId = groupService.getCurrentGroupIdUnchecked();
		if (groupId != null)
		{
			dataService.saveListings(groupId);
		}
	}

	/**
	 * A trade completed. Once the bank has been seen this session, following it
	 * sees what left and what came home. Before then the trade is the only news
	 * of it, so what changed hands moves what our listings show - only things that
	 * could be lent, as following the bank counts them.
	 */
	private void onTradeSettled(java.util.Map<Integer, Integer> leftById, java.util.Map<Integer, Integer> cameHomeById)
	{
		listingsDirty = true;
		// A Leagues, Deadman or beta trade says nothing about main-game listings.
		if (bankSeenThisSession
			|| RuneScapeProfileType.getCurrent(client) != RuneScapeProfileType.STANDARD) { return; }
		String groupId = groupService.getCurrentGroupId();
		String me = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
		if (groupId == null || groupId.isEmpty() || me == null) { return; }
		java.util.Map<Integer, Integer> change = new java.util.HashMap<>();
		cameHomeById.forEach((id, q) -> changeFamily(change, id, q));
		leftById.forEach((id, q) -> changeFamily(change, id, -q));
		java.util.List<LendingEntry> moved = dataService.adjustHeldQuantities(groupId, me, change, keptAmount(me),
			id -> net.runelite.client.game.ItemVariationMapping.map(owningIdFor(id)));
		if (!moved.isEmpty())
		{
			refreshPanel();
			queueListingPush();
		}
	}

	private void changeFamily(java.util.Map<Integer, Integer> change, int rawId, int qty)
	{
		int id = owningIdFor(rawId);
		if (lendableIdCached(id) > 0)
		{
			change.merge(net.runelite.client.game.ItemVariationMapping.map(id), qty, Integer::sum);
		}
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
		// Not during a trade: offered items leave the inventory and may come
		// straight back. The first tick after it closes catches up.
		if (tradeLoanTracker.isTradeOpen())
		{
			listingsDirty = true;
			return;
		}
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
		// Everything we can see we own, worn included - but only things that could
		// be lent at all (an imbued ring isn't the plain one), less what we owe
		// others (things borrowed, collateral held). Listings follow that, up as
		// well as down, once the bank has been seen this session; before then they
		// may only go UP, since what isn't in view may be sitting in the bank.
		java.util.Map<Integer, Integer> lendable = new java.util.HashMap<>();
		for (java.util.Map<Integer, Integer> where : java.util.Arrays.asList(wornNow, carriedNow, bankedNow))
		{
			where.forEach((id, qty) ->
			{
				if (lendableIdCached(id) > 0) { lendable.merge(id, qty, Integer::sum); }
			});
		}
		java.util.Map<Integer, Integer> held = byFamily(lendable);
		// Re-keyed the way held is, so owing notes still counts against the item.
		tradeLoanTracker.owedByBase().forEach((base, owed) ->
			held.computeIfPresent(net.runelite.client.game.ItemVariationMapping.map(owningIdFor(base)),
				(k, v) -> Math.max(0, v - owed)));
		java.util.List<LendingEntry> followed = dataService.setHeldQuantities(groupId, me, held,
			bankSeenThisSession, keptAmount(me),
			id -> net.runelite.client.game.ItemVariationMapping.map(owningIdFor(id)));
		// Not mid-trade: offered items leave the inventory and may come straight
		// back, and nobody needs a running commentary on that.
		if (bankSeenThisSession && !tradeLoanTracker.isTradeOpen()) { announceFollowed(followed); }
		// Worn after held: what's worn is counted against what is now shown. Its
		// spares are only copies that could be lent, and not ones held as someone's
		// collateral. (Borrowed copies stay in: which copy is on our back can't be
		// told.)
		java.util.Map<Integer, Integer> spareLendable = new java.util.HashMap<>();
		for (java.util.Map<Integer, Integer> where : java.util.Arrays.asList(carriedNow, bankedNow))
		{
			where.forEach((id, qty) ->
			{
				if (lendableIdCached(id) > 0) { spareLendable.merge(id, qty, Integer::sum); }
			});
		}
		java.util.Map<Integer, Integer> spareFree = byFamily(spareLendable);
		tradeLoanTracker.collateralHeldByBase().forEach((base, q) ->
			spareFree.computeIfPresent(net.runelite.client.game.ItemVariationMapping.map(owningIdFor(base)),
				(k, v) -> Math.max(0, v - q)));
		boolean wornChanged = dataService.setWornQuantities(groupId, me, worn, spareFree);
		if (!wornChanged && followed.isEmpty()) { return; }
		refreshPanel();
		queueListingPush();
	}

	/**
	 * One push per burst of listing changes, never one per equip: every publish
	 * sends the whole group snapshot, so somebody flicking through gear switches
	 * must not broadcast to the clan on every tick. Worst case the group learns
	 * about it on the five-minute heartbeat instead.
	 */
	private void queueListingPush()
	{
		if (wornPushQueued.compareAndSet(false, true))
		{
			try
			{
				executor.schedule(() ->
				{
					wornPushQueued.set(false);
					String pushGroup = groupService.getCurrentGroupIdUnchecked();
					if (pushGroup != null) { dataService.saveListings(pushGroup); }
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
			flushListingChanges();
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
			// A different world type has its own bank and gear; start again.
			forgetContainers();
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
		if (listingsDirty)
		{
			listingsDirty = false;
			recomputeWornListings();
		}
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
		// Only while logged in. The first run fires as RuneLite starts, at the
		// login screen: a popup there goes unseen, and it used up the day's
		// reminder, so the player never heard about it in game.
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null) { return; }
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
				// Keyed with the due date: a loan given more time can go overdue again
				// and count up from day 1, which mustn't look like a day already told.
				Long last = overdueRemindedAt.put(entry.getId() + "@" + entry.getDueDate(), daysOverdue);
				if (last != null && last == daysOverdue) { continue; }
				// With running-tally returns a loan can stay open past due while the
				// BORROWER's side is fully home (only collateral still to hand back) —
				// don't frame that as an overdue item on the borrower.
				boolean iLent = me.equalsIgnoreCase(entry.getLender());
				String message = entry.outstandingLentQty() > 0
					? (iLent ? "Overdue: " + entry.getItemName() + " you lent to " + entry.getBorrower()
						: "Overdue: " + entry.getItemName() + " you borrowed from " + entry.getLender()
							+ " - return it, or ask for more time")
						+ " (" + daysOverdue + (daysOverdue == 1 ? " day" : " days") + " overdue)"
					: "Open loan: " + entry.getItemName() + " — items returned, collateral still to be handed back";
				// The Discord post is its own setting - someone who silences the
				// popups may still want the channel to hear about it.
				if (entry.outstandingLentQty() > 0)
				{
					// Per group too: each group's loans go to that group's channel.
					// A loan given more time tags its posts with its due date, so a second
					// overdue run isn't taken for the first one's days and skipped. Loans
					// never extended keep the old key - one overdue right now mustn't
					// post today's reminder twice because of this update.
					String occurrence = entry.wasExtended()
						? daysOverdue + "@" + (entry.getDueDate() / 86400000L) : String.valueOf(daysOverdue);
					overduePosts.computeIfAbsent(entry.getGroupId() + "|"
						+ String.valueOf(entry.getBorrower()).toLowerCase() + "|" + occurrence,
						k -> new ArrayList<>()).add(entry);
				}
				if (!config.enableNotifications()) { continue; }
				notifier.notify(message);
				// In game too: a desktop popup is easy to miss, and some have them off.
				final String chat = message;
				clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"<col=ff0000>" + ("[Lending Tracker] " + chat), ""));
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
			// Copies: an extension applied while this waits for the clan list must
			// not rewrite a post about the loan as it was when found overdue.
			List<LendingEntry> asFound = new ArrayList<>();
			for (LendingEntry e : post.getValue()) { asFound.add(new LendingEntry(e)); }
			discordWebhook.postBatch(DiscordWebhook.Event.OVERDUE, asFound, me, day, null);
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

			boolean askedByStaff = !r.isStaffRemoval() && r.getFrom() != null
				&& !r.getFrom().equalsIgnoreCase(entry.getLender()) && !r.getFrom().equalsIgnoreCase(entry.getBorrower());
			if (askedByStaff && DataService.hasCollateral(entry))
			{
				continue; // collateral turned up after the ask - the borrower must have a say
			}
			String stamp = r.isStaffRemoval()
				? "[Removed by staff approval, requested by " + r.getFrom() + "]"
				: askedByStaff
					? "[Removed at " + r.getFrom() + "'s request (staff), approved by the lender " + r.getTo() + "]"
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

	/**
	 * An owner or co-owner asks the LENDER to take a loan off the board that
	 * isn't theirs - a stale one, say, whose borrower has gone quiet. Nothing is
	 * removed until the lender approves: it's their item, so it's their call. It
	 * goes as an ordinary removal request addressed to the lender, which every
	 * version of the plugin already shows them and carries out when approved.
	 */
	public boolean requestStaleLoanRemoval(LendingEntry loan, String reason)
	{
		String me = getCurrentPlayerName();
		String groupId = groupService.getCurrentGroupIdUnchecked();
		LendingGroup group = groupService.getActiveGroup();
		if (me == null || groupId == null || group == null || loan == null || loan.isKeptByBorrower()
			|| loan.getLender() == null || me.equalsIgnoreCase(loan.getLender()) || me.equalsIgnoreCase(loan.getBorrower())
			|| !(groupService.isOwner(groupId, me) || groupService.isCoOwner(groupId, me))
			|| !group.hasMember(loan.getLender()) || dataService.hasPendingRemovalFor(groupId, loan.getId())
			// Collateral still held means the borrower is owed something; that needs
			// the borrower's say too, so it stays on the two-party path.
			|| DataService.hasCollateral(loan))
		{
			return false;
		}
		LendingRequest request = new LendingRequest();
		request.setId(UUID.randomUUID().toString());
		request.setGroupId(groupId);
		request.setType(LendingRequest.TYPE_REMOVAL_MUTUAL);
		request.setFrom(me);
		request.setTo(loan.getLender());
		request.setEntryId(loan.getId());
		request.setItemName(loan.getItemName());
		request.setItemId(loan.getItemId());
		request.setQuantity(loan.getQuantity());
		request.setMessage(reason);
		request.setStatus(LendingRequest.STATUS_PENDING);
		long now = System.currentTimeMillis();
		request.setCreatedAt(now);
		request.setUpdatedAt(now);
		dataService.addRequest(groupId, request);
		refreshPanel();
		return true;
	}

	// --- More time on a loan ---

	/**
	 * Carry out extensions that were agreed, and report refused ones. Runs after
	 * every sync, at login, and straight after this player answers one.
	 *
	 * Only the client that keeps the loan ever moves its due date - anyone else's
	 * write would replace the keeper's row for everybody, return tallies and all.
	 * So when the other side says yes, the date moves the next time the keeper's
	 * client sees that answer. Moving it is "later only", which is what makes
	 * running this over and over harmless.
	 */
	public void applyAgreedExtensions()
	{
		synchronized (extensionLock)
		{
			String me = getCurrentPlayerName();
			LendingGroup group = groupService.getActiveGroup();
			if (me == null || group == null)
			{
				return;
			}
			List<LendingRequest> all = dataService.getRequests(group.getId());
			long now = System.currentTimeMillis();
			boolean changed = false;
			for (LendingRequest r : all)
			{
				if (!r.isExtension() || r.isPending())
				{
					continue;
				}
				LendingEntry loan = dataService.getActiveEntry(r.getEntryId());
				if (loan == null || !me.equalsIgnoreCase(DataService.keeperOf(loan))
					|| !DataService.isGenuineExtensionStep(r, loan))
				{
					continue;
				}
				if (LendingRequest.STATUS_ACCEPTED.equals(r.getStatus()))
				{
					long proposed = r.proposedDueTime(loan.getLendTime());
					long oldDue = dataService.extendLoan(loan.getId(), proposed, me);
					if (oldDue < 0)
					{
						continue; // already done, or the loan has since moved later still
					}
					changed = true;
					postExtension(DiscordWebhook.Event.EXTENDED, loan, oldDue, proposed,
						DiscordWebhook.ExtensionNote.AGREED, "due" + proposed);
					String other = me.equalsIgnoreCase(loan.getLender()) ? loan.getBorrower() : loan.getLender();
					clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
						"<col=ff0000>" + ("[Lending Tracker] More time agreed with " + other + ": "
							+ loan.getItemName() + " is now due " + DataService.dueText(proposed) + "."), ""));
				}
				else if (LendingRequest.STATUS_DECLINED.equals(r.getStatus())
					&& isRealRefusal(group.getId(), r, loan)
					&& now - refusalTime(dataService.getRequests(group.getId()), r) < 24L * 3600000L
					&& config.webhookEnabled() && config.webhookExtensions()
					&& refusalSettled(r.getId())
					&& markRefusalPosted(r.getId()))
				{
					// Once per refusal, remembered on its own so a busy channel's
					// other posts can't push it out of memory; the age limit keeps old
					// refusals from posting when the webhook is first turned on.
					postExtension(DiscordWebhook.Event.EXTENSION_REFUSED, loan, loan.getDueTime(), loan.getDueTime(),
						r.isBorrowerExtensionStep() ? DiscordWebhook.ExtensionNote.LENDER_SAID_NO
							: DiscordWebhook.ExtensionNote.BORROWER_SAID_NO, r.getId());
				}
			}
			if (changed)
			{
				refreshPanel();
			}
		}
	}

	/**
	 * Did the other side answer this step with a date of their own? A counter is
	 * always written BEFORE the step it answers is marked declined, so anything
	 * that sees the "no" also sees the counter, and never mistakes it for a refusal.
	 * A counter its sender withdrew doesn't count - that leaves a plain "no". One
	 * that lapsed unanswered still does: an offer nobody took up isn't a refusal.
	 */
	private static boolean wasCountered(List<LendingRequest> all, LendingRequest step, LendingEntry loan)
	{
		String next = step.nextExtensionStep();
		if (next == null || step.getEntryId() == null)
		{
			return false;
		}
		for (LendingRequest r : all)
		{
			if (next.equals(r.getType()) && step.getEntryId().equals(r.getEntryId())
				&& r.getCreatedAt() > step.getCreatedAt())
			{
				// Withdrawn means cancelled by hand while the due date still falls
				// short of what it offered. One the due date now covers was carried
				// out - more time given directly - not taken back.
				boolean withdrawn = LendingRequest.STATUS_CANCELLED.equals(r.getStatus()) && !r.isExpired()
					&& DataService.hasDueDate(loan) && loan.getDueTime() < r.proposedDueTime(loan.getLendTime());
				if (!withdrawn)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A declined step that really was a "no": not answered with a counter, and not
	 * already covered by the loan's due date - the keeper may have given more time
	 * directly, which is a yes. Read against a FRESH copy of the requests, taken
	 * after the decline was seen, so a counter that landed with it is never missed.
	 */
	private boolean isRealRefusal(String groupId, LendingRequest r, LendingEntry loan)
	{
		if (!DataService.hasDueDate(loan) || loan.getDueTime() >= r.proposedDueTime(loan.getLendTime()))
		{
			return false;
		}
		// A negotiation that ended because one side left or was removed from the
		// group wasn't turned down - leaving cancels whatever they had waiting.
		LendingGroup g = groupService.getGroup(groupId);
		if (g == null || !g.hasMember(loan.getLender()) || !g.hasMember(loan.getBorrower()))
		{
			return false;
		}
		// ...or left and came back since. A rejoin gets a fresh joinedAt, and the
		// negotiation they left behind still wasn't turned down.
		for (GroupMember m : g.getMembers())
		{
			if (m != null && m.getName() != null && m.getJoinedAt() > r.getCreatedAt()
				&& (m.getName().equalsIgnoreCase(loan.getLender()) || m.getName().equalsIgnoreCase(loan.getBorrower())))
			{
				return false;
			}
		}
		return !wasCountered(dataService.getRequests(groupId), r, loan);
	}

	/** When the "no" was said: the decline, or a later withdrawal of the counter to it. */
	private static long refusalTime(List<LendingRequest> all, LendingRequest step)
	{
		long t = step.getUpdatedAt();
		String next = step.nextExtensionStep();
		if (next != null && step.getEntryId() != null)
		{
			for (LendingRequest r : all)
			{
				if (next.equals(r.getType()) && step.getEntryId().equals(r.getEntryId())
					&& r.getCreatedAt() > step.getCreatedAt()
					&& LendingRequest.STATUS_CANCELLED.equals(r.getStatus()) && !r.isExpired())
				{
					t = Math.max(t, r.getUpdatedAt());
				}
			}
		}
		return t;
	}

	// Declines seen with no counter, and when this client first saw each. One is
	// only acted on after it has stayed that way for a minute: a counter written
	// at the same moment can land a beat later, and an older client can even
	// publish the "no" on its own, half way through a merge.
	private final java.util.Map<String, Long> refusalFirstSeen = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long REFUSAL_SETTLE_MS = 60_000L;

	private boolean refusalSettled(String requestId)
	{
		long now = System.currentTimeMillis();
		Long first = refusalFirstSeen.putIfAbsent(requestId, now);
		if (first == null)
		{
			// Look again once it has settled, rather than whenever a sync next comes.
			long epoch = sessionEpoch.get();
			executor.schedule(() ->
			{
				if (epoch == sessionEpoch.get())
				{
					applyAgreedExtensions();
					checkForRequestNotifications();
				}
			}, REFUSAL_SETTLE_MS + 5_000L, TimeUnit.MILLISECONDS);
			return false;
		}
		return now - first >= REFUSAL_SETTLE_MS;
	}

	private static final String REFUSALS_POSTED_KEY = "extensionRefusalsPosted";
	private final Object refusalPostLock = new Object();

	/** Remember a refusal as posted, for 15 days. False if it already was. */
	private boolean markRefusalPosted(String requestId)
	{
		synchronized (refusalPostLock)
		{
			long now = System.currentTimeMillis();
			java.util.LinkedHashMap<String, Long> posted = new java.util.LinkedHashMap<>();
			String csv = configManager.getConfiguration("lendingtracker", REFUSALS_POSTED_KEY);
			if (csv != null && !csv.isEmpty())
			{
				for (String part : csv.split(","))
				{
					int at = part.lastIndexOf('@');
					if (at <= 0)
					{
						continue;
					}
					try
					{
						long when = Long.parseLong(part.substring(at + 1));
						if (now - when < 15L * 86400000L)
						{
							posted.put(part.substring(0, at), when);
						}
					}
					catch (NumberFormatException ignored)
					{
						// a damaged entry is just forgotten
					}
				}
			}
			if (posted.containsKey(requestId))
			{
				return false;
			}
			posted.put(requestId, now);
			configManager.setConfiguration("lendingtracker", REFUSALS_POSTED_KEY, posted.entrySet().stream()
				.map(e -> e.getKey() + "@" + e.getValue()).collect(Collectors.joining(",")));
			return true;
		}
	}

	/**
	 * The keeper moves a due date later on the spot: the lender giving more time,
	 * or the borrower of a loan they keep because the lender has no plugin.
	 *
	 * @return true if the date moved
	 */
	public boolean extendLoanNow(LendingEntry loan, long newDue)
	{
		String me = getCurrentPlayerName();
		long oldDue = dataService.extendLoan(loan.getId(), newDue, me);
		if (oldDue < 0)
		{
			return false;
		}
		String how = me.equalsIgnoreCase(loan.getLender()) ? DiscordWebhook.ExtensionNote.LENDER
			: DiscordWebhook.ExtensionNote.BORROWER_KEEPER;
		postExtension(DiscordWebhook.Event.EXTENDED, loan, oldDue, newDue, how, "due" + newDue);
		// Anything still waiting on this loan that the new date already covers is
		// settled: the other side's asks are granted (and they're told), this
		// player's own offers are withdrawn. A step asking for more than was given
		// stays open.
		String groupId = loan.getGroupId();
		if (groupId != null)
		{
			for (LendingRequest r : dataService.getRequests(groupId))
			{
				if (!r.isExtension() || !r.isPending() || !loan.getId().equals(r.getEntryId())
					|| (newDue != 0 && r.proposedDueTime(loan.getLendTime()) > newDue))
				{
					continue;
				}
				dataService.resolvePendingRequest(groupId, r.getId(), me.equalsIgnoreCase(r.getFrom())
					? LendingRequest.STATUS_CANCELLED : LendingRequest.STATUS_ACCEPTED);
			}
		}
		refreshPanel();
		return true;
	}

	/** Post an extension or a refusal to the group's Discord, with the loan as it stands now. */
	private void postExtension(DiscordWebhook.Event event, LendingEntry loan, long oldDue, long newDue,
		String how, String occurrence)
	{
		LendingEntry current = dataService.getActiveEntry(loan.getId());
		discordWebhook.postExtension(event, new LendingEntry(current != null ? current : loan),
			getCurrentPlayerName(), occurrence, new DiscordWebhook.ExtensionNote(oldDue, newDue, how));
	}

	/**
	 * Send one step of the more-time handshake. Addressed to nobody - see
	 * LendingRequest.TYPE_EXTEND_ASK for why - and carrying the proposed date as
	 * the loan's whole length in days.
	 *
	 * @return the request sent, or null if it couldn't be
	 */
	public LendingRequest sendExtensionStep(LendingEntry loan, String type, long proposedDue, String message)
	{
		String groupId = groupService.getCurrentGroupIdUnchecked();
		String me = getCurrentPlayerName();
		if (groupId == null || groupId.isEmpty() || me == null || loan == null || loan.getLendTime() <= 0)
		{
			return null;
		}
		LendingRequest request = new LendingRequest();
		request.setId(UUID.randomUUID().toString());
		request.setGroupId(groupId);
		request.setType(type);
		request.setFrom(me);
		request.setTo("");
		request.setEntryId(loan.getId());
		request.setItemName(loan.getItemName());
		request.setItemId(loan.getItemId());
		request.setQuantity(loan.getQuantity());
		request.setDurationDays(extensionDays(loan, proposedDue));
		request.setMessage(message);
		request.setStatus(LendingRequest.STATUS_PENDING);
		// Telling a counter from a refusal goes by which step came after which, and
		// the two sides' clocks can disagree by minutes. So a new step is always
		// stamped after every step already on this loan, whatever this clock says.
		long stamp = System.currentTimeMillis();
		for (LendingRequest earlier : dataService.getRequests(groupId))
		{
			if (earlier.isExtension() && loan.getId().equals(earlier.getEntryId()))
			{
				stamp = Math.max(stamp, earlier.getCreatedAt() + 1);
			}
		}
		request.setCreatedAt(stamp);
		request.setUpdatedAt(stamp);
		dataService.addRequest(groupId, request);
		refreshPanel();
		return request;
	}

	/** A proposed due date as whole days from the loan's start, rounded up - what a request can carry. */
	public static int extensionDays(LendingEntry loan, long proposedDue)
	{
		long span = proposedDue - loan.getLendTime();
		return (int) Math.max(1, Math.min(36500, (span + 86400000L - 1) / 86400000L));
	}

	/** The due date a request for `target` will actually carry. */
	public static long requestableDue(LendingEntry loan, long target)
	{
		return loan.getLendTime() + extensionDays(loan, target) * 86400000L;
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

			// More-time steps are addressed to nobody (older clients mustn't see
			// them), so they're found through the loan rather than by name.
			for (LendingRequest r : dataService.getPendingExtensionsFor(groupId, me))
			{
				String key = r.getId() + ":IN";
				seen.add(key);
				if (notified.add(key))
				{
					LendingEntry loan = dataService.getActiveEntry(r.getEntryId());
					String until = loan != null ? DataService.dueText(r.proposedDueTime(loan.getLendTime())) : "?";
					String what;
					switch (r.getType())
					{
						case LendingRequest.TYPE_EXTEND_ASK:
							what = r.getFrom() + " asks for more time with " + r.getItemName() + ", until " + until
								+ " - answer in the panel";
							break;
						case LendingRequest.TYPE_EXTEND_COUNTER:
							what = r.getFrom() + " offers you " + r.getItemName() + " until " + until
								+ " instead - answer in the panel";
							break;
						case LendingRequest.TYPE_EXTEND_REPLY:
							what = r.getFrom() + " asks to keep " + r.getItemName() + " until " + until
								+ " instead - answer in the panel";
							break;
						default:
							what = r.getFrom() + "'s final offer: " + r.getItemName() + " until " + until
								+ " - accept it, or return it on time";
					}
					notifier.notify("[Lending Tracker] " + what);
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
				// A step on a loan that has since closed needs no "nobody answered".
				if (r.isExtension() && dataService.getActiveEntry(r.getEntryId()) == null) { continue; }
				if (r.isExpired() && LendingRequest.STATUS_CANCELLED.equals(r.getStatus()))
				{
					String key = r.getId() + ":EXPIRED";
					seen.add(key);
					if (notified.add(key))
					{
						// The timer only expires requests 14 days old; a younger more-time
						// step carrying the flag was closed because one side left the group.
						boolean closedByLeaving = r.isExtension()
							&& System.currentTimeMillis() - r.getCreatedAt() < 14L * 86400000L;
						notifier.notify("[Lending Tracker] " + (closedByLeaving
							? "Your more-time request for " + r.getItemName() + " was closed - one side of the loan is no longer in the group."
							: r.isLookingFor()
							? "Your Looking For post for " + r.getItemName() + " expired - post it again if you still need it."
							: "Your request for " + r.getItemName() + " expired — nobody answered it."));
						changed = true;
					}
					continue;
				}
				// A plain cancellation is the requester's own doing; they know.
				if (LendingRequest.STATUS_CANCELLED.equals(r.getStatus())) { continue; }
				if (r.isExtension())
				{
					// Addressed to nobody, so the usual "<to> accepted" would have no
					// name in it. A counter isn't news here either - it arrives as a
					// request of its own, above.
					LendingEntry loan = dataService.getActiveEntry(r.getEntryId());
					if (loan == null || (LendingRequest.STATUS_DECLINED.equals(r.getStatus())
						&& (!isRealRefusal(groupId, r, loan) || !refusalSettled(r.getId()))))
					{
						continue;
					}
					String key = r.getId() + ":" + r.getStatus();
					seen.add(key);
					if (notified.add(key))
					{
						String other = DataService.extensionAnswerer(r, loan);
						notifier.notify("[Lending Tracker] " + (LendingRequest.STATUS_ACCEPTED.equals(r.getStatus())
							// Say how the loan stands now: the keeper may have given more
							// than was asked, or lifted the limit.
							? (!DataService.hasDueDate(loan)
								? other + " agreed - " + r.getItemName() + " has no due date now"
								: other + " agreed: " + r.getItemName() + " until " + DataService.dueText(
									Math.max(loan.getDueTime(), r.proposedDueTime(loan.getLendTime()))))
							: r.isBorrowerExtensionStep()
								? other + " said no to more time with " + r.getItemName()
								: other + " will return " + r.getItemName() + " by the current due date instead"));
						changed = true;
					}
					continue;
				}
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
