package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.guess34.lendingtracker.LendingTrackerConfig;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingGroup;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.Graphics2D;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Posts loan events to the Discord channel of the group the loan was made in.
 *
 * The webhook belongs to the GROUP: staff set it once in the group settings and
 * it syncs to every member (sealed - see WebhookSeal). So a player in two groups
 * never posts one group's loans into the other's Discord. Each player still has
 * to turn posting on for themselves; RuneLite requires that for anything sent to
 * a third party.
 *
 * One way only - nothing is ever read back. Each post is one small embed: a
 * sentence saying what happened, the items, and the trade picture as a
 * click-to-enlarge thumbnail. Everything from one trade is one post.
 *
 * EXACTLY ONE CLIENT POSTS EACH EVENT, which matters with a whole clan trading:
 *  - Only the loan's KEEPER posts. That is the lender's client (they tap Loan,
 *    the borrower taps Collat and records nothing) - except when the lender
 *    isn't in the group and can't record it, typically a clan-mate on mobile.
 *    Then the borrower's client keeps the loan, flagged keptByBorrower, and it
 *    alone posts. The flag is set once when the loan is recorded, so the two
 *    sides never both think they are the keeper.
 *  - The lender's client posts a loan when it first SEES it, not only when it
 *    records it. An accepted lend offer is recorded by the borrower, and arrives
 *    at the lender by sync - it used to go unposted.
 *  - Every post is remembered (loan id + event), so a restart, a re-sync or the
 *    same loan arriving twice never posts it again.
 * The one case left is the same account logged in on two computers at once,
 * each with its own memory of what it posted.
 */
@Slf4j
@Singleton
public class DiscordWebhook
{
	public enum Event
	{
		LOAN("New loan", 0x3BA55D, "\uD83D\uDCE6"),
		RETURNED("Returned", 0x5865F2, "\u2705"),
		OVERDUE("Overdue", 0xFEE75C, "\u23F0"),
		FORGIVEN("Forgiven", 0x99AAB5, "\uD83E\uDD1D"),
		PARTIAL("Incomplete return", 0xF0A020, "\u26A0"),
		REMOVED("Removed", 0x99AAB5, "\uD83D\uDDD1");

		private final String label;
		private final int color;
		private final String icon;

		Event(String label, int color, String icon)
		{
			this.label = label;
			this.color = color;
			this.icon = icon;
		}
	}

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final MediaType PNG = MediaType.parse("image/png");
	private static final int MAX_LINES = 20;
	// Collateral item names, filled on the client thread before a post is built.
	private final java.util.Map<Integer, String> itemNames = new java.util.concurrent.ConcurrentHashMap<>();
	// One row of icons, however many items; waits at most this long for them.
	private static final int MAX_ICONS = 10;
	private static final long ICON_WAIT_SECONDS = 3;
	// Up to 30s for the game to load the clan list after login before posting anyway.
	private static final int ROSTER_WAIT_TRIES = 3;
	private static final long ROSTER_WAIT_SECONDS = 10;
	private static final String CONFIG_GROUP = "lendingtracker";
	private static final String POSTED_KEY = "webhookPosted";
	private static final int POSTED_CAP = 400;
	// A loan that turns up by sync is only news if it is recent - otherwise
	// turning the webhook on, or a fresh install, would post every loan the
	// group has ever had.
	private static final long SEEN_LOAN_WINDOW_MS = 24L * 3600000L;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final LendingTrackerConfig config;
	private final GroupService groupService;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executor;
	private final ClanRoster clanRoster;
	private final ClientThread clientThread;
	private final ItemManager itemManager;

	@Inject
	public DiscordWebhook(OkHttpClient httpClient, Gson gson, LendingTrackerConfig config, GroupService groupService,
		ConfigManager configManager, ScheduledExecutorService executor, ClanRoster clanRoster, ClientThread clientThread,
		ItemManager itemManager)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.executor = executor;
		this.clanRoster = clanRoster;
		this.httpClient = httpClient;
		this.gson = gson;
		this.config = config;
		this.groupService = groupService;
		this.configManager = configManager;
	}

	/**
	 * Only a real Discord webhook address is accepted. Anything else is ignored,
	 * so a mistyped or malicious link can't turn this into a way of sending loan
	 * data to some other server.
	 */
	public static boolean isDiscordWebhook(String url)
	{
		if (url == null) return false;
		HttpUrl parsed = HttpUrl.parse(url.trim());
		if (parsed == null || !parsed.isHttps()) return false;
		String host = parsed.host();
		boolean discord = host.equals("discord.com") || host.equals("discordapp.com")
			|| host.equals("ptb.discord.com") || host.equals("canary.discord.com");
		return discord && parsed.encodedPath().startsWith("/api/webhooks/");
	}

	/** Post this event if the webhook is on, the event type is wanted, and we are the client that should. */
	public void post(Event event, LendingEntry loan, String reporter)
	{
		post(event, loan, reporter, null);
	}

	/** A loan that reached this client by sync rather than being recorded here. */
	public void postLoanSeen(LendingEntry loan, String reporter)
	{
		if (loan == null || loan.isReturned() || loan.getLendTime() < System.currentTimeMillis() - SEEN_LOAN_WINDOW_MS)
		{
			return;
		}
		post(Event.LOAN, loan, reporter, null);
	}

	/**
	 * @param occurrence tells apart events that can legitimately repeat for one
	 *                   loan - the overdue day - or null for one-off events
	 */
	public void post(Event event, LendingEntry loan, String reporter, String occurrence)
	{
		postBatch(event, Collections.singletonList(loan), reporter, occurrence, null);
	}

	/**
	 * A short "connected" message, so staff can check the link works. Only with
	 * posting turned on in this player's own settings, like everything else.
	 *
	 * @return null when sent, else why not
	 */
	public String postTest(String groupId, String player)
	{
		if (!config.webhookEnabled())
		{
			return "Turn on 'Post loans to Discord' in the plugin's settings first - RuneLite requires it before the plugin sends anything to Discord.";
		}
		String url = groupService.getGroupWebhook(groupId);
		if (url == null)
		{
			return "This group has no Discord webhook set.";
		}
		LendingGroup group = groupService.getGroup(groupId);
		JsonObject embed = new JsonObject();
		embed.addProperty("title", "Lending Tracker connected");
		embed.addProperty("description", "Loans in " + safe(group != null ? group.getName() : "this group")
			+ " will be posted here. Test sent by " + safe(player) + ".");
		embed.addProperty("color", 0x3BA55D);
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		JsonObject body = new JsonObject();
		body.addProperty("username", "Lending Tracker");
		body.add("embeds", embeds);
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		body.add("allowed_mentions", mentions);
		send(url, RequestBody.create(JSON, gson.toJson(body)));
		return null;
	}

	/** Post the trade-window picture with loan events? */
	public boolean wantsScreenshot()
	{
		return config.webhookEnabled() && config.webhookScreenshot();
	}

	/**
	 * One post for everything that happened in one go - every item lent in a
	 * single trade is its own loan record, but it was one hand-over and reads as
	 * one message. All loans passed must share lender and borrower.
	 *
	 * @param proof the trade window, already cropped to just that window, or null
	 */
	public void postBatch(Event event, List<LendingEntry> loans, String reporter, String occurrence,
		BufferedImage proof)
	{
		if (loans == null || loans.isEmpty() || loans.get(0) == null || reporter == null
			|| !config.webhookEnabled() || !wanted(event))
		{
			return;
		}
		// The channel of the group this trade happened in - nowhere else.
		String url = groupService.getGroupWebhook(loans.get(0).getGroupId());
		if (url == null)
		{
			return;
		}
		List<LendingEntry> fresh = new ArrayList<>();
		for (LendingEntry loan : loans)
		{
			if (loan == null || loan.getId() == null || !shouldReport(loan, reporter))
			{
				continue;
			}
			if (claim(loan.getId() + ":" + event.name() + (occurrence != null ? ":" + occurrence : "")))
			{
				fresh.add(loan);   // not already posted from this computer
			}
		}
		if (fresh.isEmpty())
		{
			return;
		}
		deliver(fresh, event, proof, url.trim(), 0);
	}

	/**
	 * Apply the group's loan scope (clan / group / anyone) before posting, with
	 * clan membership read fresh from the game's clan list. If the game hasn't
	 * loaded the clan yet (just logged in), wait a little and try again rather
	 * than decide on a list that isn't there.
	 */
	private void deliver(List<LendingEntry> fresh, Event event, BufferedImage proof, String target, int attempt)
	{
		LendingEntry first = fresh.get(0);
		String groupId = first.getGroupId();
		GroupService.LoanScope scope = groupService.getLoanScope(groupId);
		String clan = groupService.getLinkedClan(groupId);
		// A loan whose "New loan" went out always gets its ending posted too, even
		// if someone has since left the clan or group - otherwise the channel, and
		// any bot reading it, would show it open forever.
		boolean announced = event != Event.LOAN && wasAnnounced(fresh);

		if (!announced && scope == GroupService.LoanScope.GROUP)
		{
			LendingGroup group = groupService.getGroup(groupId);
			if (group == null || !group.hasMember(first.getLender()) || !group.hasMember(first.getBorrower()))
			{
				log.debug("Not posting {}: {} -> {} not both in the group", event, first.getLender(), first.getBorrower());
				return;
			}
		}
		if (clan == null)
		{
			withIcons(fresh, strip -> buildAndSend(fresh, event, proof, target, strip));
			return;
		}
		clientThread.invokeLater(() ->
		{
			clanRoster.refresh();
			if (!clanRoster.isLoaded() && attempt < ROSTER_WAIT_TRIES)
			{
				executor.schedule(() -> deliver(fresh, event, proof, target, attempt + 1),
					ROSTER_WAIT_SECONDS, TimeUnit.SECONDS);
				return;
			}
			// Clan scope: both players must be confirmed members by the game's own
			// list, or nothing is posted. Unconfirmed counts as no.
			if (!announced && scope == GroupService.LoanScope.CLAN
				&& (clanRoster.check(clan, first.getLender()) != ClanRoster.Membership.YES
				|| clanRoster.check(clan, first.getBorrower()) != ClanRoster.Membership.YES))
			{
				log.debug("Not posting {}: {} -> {} not both confirmed in {}", event,
					first.getLender(), first.getBorrower(), clan);
				return;
			}
			withIcons(fresh, strip -> buildAndSend(fresh, event, proof, target, strip));
		});
	}

	private static final String ANNOUNCED = ":ANNOUNCED";

	private synchronized boolean wasAnnounced(List<LendingEntry> loans)
	{
		String csv = configManager.getConfiguration(CONFIG_GROUP, POSTED_KEY);
		if (csv == null || csv.isEmpty()) return false;
		java.util.Set<String> posted = new java.util.HashSet<>(java.util.Arrays.asList(csv.split(",")));
		for (LendingEntry loan : loans)
		{
			if (loan.getId() != null && posted.contains(loan.getId() + ANNOUNCED)) return true;
		}
		return false;
	}

	/**
	 * Draw the items' icons into one small strip, the way they sit in an
	 * inventory, then hand it on. Icons come from the game so they are fetched on
	 * the client thread; one that never loads can't hold the post up for long.
	 */
	private void withIcons(List<LendingEntry> loans, java.util.function.Consumer<BufferedImage> then)
	{
		clientThread.invokeLater(() ->
		{
			// Names for any collateral still out - only the client thread can look them up
			for (LendingEntry loan : loans)
			{
				for (int[] p : idQty(loan.outstandingCollateralIds()))
				{
					itemNames.computeIfAbsent(p[0], id -> itemManager.getItemComposition(id).getName());
				}
			}
			List<AsyncBufferedImage> icons = new ArrayList<>();
			for (LendingEntry loan : loans)
			{
				if (icons.size() >= MAX_ICONS)
				{
					break;
				}
				if (loan.getItemId() > 0)
				{
					icons.add(itemManager.getImage(loan.getItemId(), Math.max(1, loan.getQuantity()), loan.getQuantity() > 1));
				}
			}
			AtomicBoolean done = new AtomicBoolean();
			AtomicInteger waiting = new AtomicInteger(icons.size());
			Runnable finish = () ->
			{
				if (done.compareAndSet(false, true))
				{
					executor.submit(() -> then.accept(strip(icons)));
				}
			};
			if (icons.isEmpty())
			{
				finish.run();
				return;
			}
			for (AsyncBufferedImage icon : icons)
			{
				icon.onLoaded(() ->
				{
					if (waiting.decrementAndGet() == 0)
					{
						finish.run();
					}
				});
			}
			executor.schedule(finish, ICON_WAIT_SECONDS, TimeUnit.SECONDS);
		});
	}

	private static BufferedImage strip(List<AsyncBufferedImage> icons)
	{
		if (icons.isEmpty())
		{
			return null;
		}
		int w = 36;
		int h = 32;
		int gap = 2;
		BufferedImage out = new BufferedImage(icons.size() * w + (icons.size() - 1) * gap, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		for (int i = 0; i < icons.size(); i++)
		{
			g.drawImage(icons.get(i), i * (w + gap), 0, null);
		}
		g.dispose();
		return out;
	}

	/** Build the post and send it. Runs on the executor - encoding the pictures is real work. */
	private void buildAndSend(List<LendingEntry> fresh, Event event, BufferedImage proof, String target,
		BufferedImage iconStrip)
	{
		LendingEntry first = fresh.get(0);
		if (event == Event.LOAN)
		{
			for (LendingEntry loan : fresh)
			{
				if (loan.getId() != null) claim(loan.getId() + ANNOUNCED);
			}
		}

		// Laid out like the Dink loot posts people already know: group name as a
		// small header, a sentence ending in a colon, one line per item (linked to
		// the wiki), then the numbers in boxes. The trade picture sits in the
		// corner and opens full size when clicked.
		StringBuilder items = new StringBuilder();
		List<String> collateral = new ArrayList<>();
		long total = 0;
		int shown = 0;
		for (LendingEntry loan : fresh)
		{
			total += loan.getValue();
			String c = collateralText(loan);
			if (c != null) collateral.add(c);
			if (shown++ >= MAX_LINES)
			{
				continue;
			}
			items.append('\n').append(Math.max(1, loan.getQuantity())).append(" x ").append(itemLink(loan))
				.append(" (").append(QuantityFormatter.quantityToStackSize(loan.getValue())).append(")");
		}
		if (fresh.size() > MAX_LINES)
		{
			items.append("\n...and ").append(fresh.size() - MAX_LINES).append(" more");
		}
		LendingGroup group = first.getGroupId() != null ? groupService.getGroup(first.getGroupId()) : null;

		JsonObject embed = new JsonObject();
		if (group != null && group.getName() != null)
		{
			JsonObject author = new JsonObject();
			author.addProperty("name", safe(group.getName()));
			embed.add("author", author);
		}
		embed.addProperty("title", event.icon + " " + event.label);
		embed.addProperty("description", lead(event, first) + "\n"
			+ (event == Event.PARTIAL ? missingList(fresh)
				: event == Event.RETURNED ? returnedList(fresh, collateral)
			: event == Event.OVERDUE ? overdueList(fresh)
				: items.toString()));
		embed.addProperty("color", event.color);
		embed.addProperty("timestamp", Instant.now().toString());

		JsonArray fields = new JsonArray();
		if (event != Event.PARTIAL)
		{
			fields.add(field("Value", "`" + QuantityFormatter.quantityToStackSize(total) + " gp`", true));
		}
		String collateralNow = String.join(" + ", collateral).replace("`", "'");
		boolean stillOut = event == Event.LOAN || event == Event.OVERDUE || event == Event.PARTIAL;
		if ((event == Event.LOAN || event == Event.OVERDUE) && !collateral.isEmpty())
		{
			fields.add(field("Collateral", "`" + collateralNow + "`", true));
		}
		if (event == Event.LOAN && first.getDueTime() <= 0)
		{
			fields.add(field("Due", "`No limit`", true));
		}
		if (stillOut && first.getDueTime() > 0 && first.getDueTime() < Long.MAX_VALUE / 2)
		{
			// "in 7 days" / "2 days ago", in each reader's own time zone. Can't sit
			// inside a code box - Discord wouldn't render it there.
			fields.add(field("Due", "<t:" + (first.getDueTime() / 1000) + ":R>", true));
		}
		embed.add("fields", fields);

		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Lending Tracker");
		embed.add("footer", footer);

		// No username or avatar set here on purpose: the post uses the name and
		// picture the group gave the webhook in Discord, which is where it's
		// customised.
		JsonObject body = new JsonObject();
		// Tag the lender and borrower, if they turned "Ping me" on. Pings only work
		// in the message text, not inside the embed. allowed_mentions lists exactly
		// those two IDs, so nothing else in a post - an item or group name - can
		// ever ping anyone.
		JsonArray pingIds = new JsonArray();
		StringBuilder content = new StringBuilder();
		for (String who : new String[] { first.getLender(), first.getBorrower() })
		{
			String id = groupService.getMemberDiscordId(first.getGroupId(), who);
			if (id != null && !pingIds.contains(new com.google.gson.JsonPrimitive(id)))
			{
				pingIds.add(id);
				content.append(content.length() > 0 ? " " : "").append("<@").append(id).append(">");
			}
		}
		if (content.length() > 0)
		{
			body.addProperty("content", content.toString());
		}
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		mentions.add("users", pingIds);
		body.add("allowed_mentions", mentions);

		// Two optional pictures: the trade window as the corner thumbnail (click to
		// enlarge) and the item icon strip, shown at its own small size underneath.
		byte[] tradePng = encode(proof);
		byte[] iconsPng = encode(iconStrip);
		JsonArray attachments = new JsonArray();
		List<String> fileNames = new ArrayList<>();
		List<byte[]> files = new ArrayList<>();
		if (tradePng != null)
		{
			JsonObject thumb = new JsonObject();
			thumb.addProperty("url", "attachment://trade.png");
			embed.add("thumbnail", thumb);
			fileNames.add("trade.png");
			files.add(tradePng);
		}
		if (iconsPng != null)
		{
			JsonObject image = new JsonObject();
			image.addProperty("url", "attachment://items.png");
			embed.add("image", image);
			fileNames.add("items.png");
			files.add(iconsPng);
		}
		for (int i = 0; i < files.size(); i++)
		{
			JsonObject att = new JsonObject();
			att.addProperty("id", i);
			att.addProperty("filename", fileNames.get(i));
			attachments.add(att);
		}
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		body.add("embeds", embeds);

		RequestBody requestBody;
		if (files.isEmpty())
		{
			requestBody = RequestBody.create(JSON, gson.toJson(body));
		}
		else
		{
			body.add("attachments", attachments);
			MultipartBody.Builder multipart = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", gson.toJson(body));
			for (int i = 0; i < files.size(); i++)
			{
				multipart.addFormDataPart("files[" + i + "]", fileNames.get(i), RequestBody.create(PNG, files.get(i)));
			}
			requestBody = multipart.build();
		}
		send(target, requestBody);
	}

	private void send(String url, RequestBody body)
	{
		Request request = new Request.Builder().url(url).post(body).build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Discord webhook post failed: {}", e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						log.debug("Discord webhook answered HTTP {}", r.code());
					}
				}
			}
		});
	}

	private static byte[] encode(BufferedImage image)
	{
		if (image == null)
		{
			return null;
		}
		try (ByteArrayOutputStream out = new ByteArrayOutputStream())
		{
			ImageIO.write(image, "png", out);
			byte[] bytes = out.toByteArray();
			// Well under Discord's upload limit for a cropped trade window; if
			// something odd produced a huge image, post the text alone.
			return bytes.length > 0 && bytes.length < 7_000_000 ? bytes : null;
		}
		catch (IOException e)
		{
			log.debug("Could not encode the trade picture: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Record that this post is being made; false if it already was. Claimed
	 * before sending, so two paths reporting the same loan at once (recorded
	 * here, then echoed back by sync) can't both get through.
	 */
	private synchronized boolean claim(String key)
	{
		java.util.LinkedHashSet<String> posted = new java.util.LinkedHashSet<>();
		String csv = configManager.getConfiguration(CONFIG_GROUP, POSTED_KEY);
		if (csv != null && !csv.isEmpty())
		{
			posted.addAll(java.util.Arrays.asList(csv.split(",")));
		}
		if (!posted.add(key))
		{
			return false;
		}
		java.util.Iterator<String> oldest = posted.iterator();
		while (posted.size() > POSTED_CAP && oldest.hasNext())
		{
			oldest.next();
			oldest.remove();
		}
		configManager.setConfiguration(CONFIG_GROUP, POSTED_KEY, String.join(",", posted));
		return true;
	}

	private boolean wanted(Event event)
	{
		switch (event)
		{
			case LOAN:
				return config.webhookLoans();
			case OVERDUE:
				return config.webhookOverdue();
			default:
				return config.webhookReturns();
		}
	}

	// The keeper posts: the lender, or the borrower for a loan the borrower keeps
	// because the lender can't run the plugin. Never both.
	private static boolean shouldReport(LendingEntry loan, String reporter)
	{
		return loan.isKeptByBorrower()
			? reporter.equalsIgnoreCase(loan.getBorrower())
			: reporter.equalsIgnoreCase(loan.getLender());
	}

	private static String collateralText(LendingEntry loan)
	{
		StringBuilder sb = new StringBuilder();
		if (loan.getCollateralValue() != null && loan.getCollateralValue() > 0 && "GP".equals(loan.getCollateralType()))
		{
			sb.append(QuantityFormatter.quantityToStackSize(loan.getCollateralValue())).append(" gp");
		}
		if (loan.getCollateralItems() != null && !loan.getCollateralItems().isEmpty())
		{
			if (sb.length() > 0) sb.append(" + ");
			sb.append(safe(loan.getCollateralItems()));
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	/** The sentence above the item list. Names bolded, markdown in them escaped. */
	private static String lead(Event event, LendingEntry first)
	{
		String lender = "**" + md(first.getLender()) + "**";
		String borrower = "**" + md(first.getBorrower()) + "**";
		switch (event)
		{
			case LOAN:
				return lender + " lent " + borrower + ":";
			case RETURNED:
				return borrower + " returned everything to " + lender + ":";
			case OVERDUE:
				long late = Math.max(1, (System.currentTimeMillis() - first.getDueTime()) / 86400000L);
				return borrower + " is **" + late + (late == 1 ? " day" : " days") + "** overdue returning to " + lender + ":";
			case PARTIAL:
				return borrower + " returned part of the loan to " + lender + " - it stays open until everything is back.";
			case FORGIVEN:
				return lender + " forgave " + borrower + ":";
			default:
				return "Loan between " + lender + " and " + borrower + " removed:";
		}
	}

	/**
	 * What a part-return still leaves out, and who has it - loaned items with the
	 * borrower, collateral with the lender.
	 */
	private String missingList(List<LendingEntry> loans)
	{
		// A "diff" code box shows lines starting with "-" in red on Discord for PC;
		// the cross on each line carries it where the red doesn't show (some phone
		// apps). Code boxes can't hold links or bold, so names are plain here.
		LendingEntry first = loans.get(0);
		List<String> lines = new ArrayList<>();
		for (LendingEntry l : loans)
		{
			int q = l.outstandingLentQty();
			if (q > 0)
			{
				lines.add(q + " x " + plain(l.getItemName()) + "  (with " + plain(l.getBorrower()) + ")");
			}
		}
		for (LendingEntry l : loans)
		{
			for (int[] p : idQty(l.outstandingCollateralIds()))
			{
				lines.add(p[1] + " x " + plain(itemNames.getOrDefault(p[0], "Item " + p[0]))
					+ " collateral  (with " + plain(first.getLender()) + ")");
			}
			long gp = l.outstandingCollateralGp();
			if (gp > 0)
			{
				lines.add(QuantityFormatter.quantityToStackSize(gp) + " gp collateral  (with " + plain(first.getLender()) + ")");
			}
		}
		// What has come back so far, so the box shows progress, not just the gap.
		List<String> back = new ArrayList<>();
		for (LendingEntry l : loans)
		{
			int returned = Math.max(1, l.getQuantity()) - l.outstandingLentQty();
			if (returned > 0)
			{
				back.add(returned + " x " + plain(l.getItemName()) + "  (returned)");
			}
		}
		for (LendingEntry l : loans)
		{
			java.util.Map<Integer, Integer> stillOut = new java.util.HashMap<>();
			for (int[] p : idQty(l.outstandingCollateralIds()))
			{
				stillOut.merge(p[0], p[1], Integer::sum);
			}
			for (int[] p : idQty(l.getCollateralItemIds()))
			{
				int got = p[1] - stillOut.getOrDefault(p[0], 0);
				if (got > 0)
				{
					back.add(got + " x " + plain(itemNames.getOrDefault(p[0], "Item " + p[0])) + " collateral  (given back)");
				}
			}
			long gpGiven = l.getCollateralValue() != null && "GP".equals(l.getCollateralType()) ? l.getCollateralValue() : 0;
			long gpBack = gpGiven - l.outstandingCollateralGp();
			if (gpBack > 0)
			{
				back.add(QuantityFormatter.quantityToStackSize(gpBack) + " gp collateral  (given back)");
			}
		}
		StringBuilder sb = new StringBuilder("\n**Return progress:**\n```diff");
		for (String line : back)
		{
			// Still "-" lines: the whole box stays red while the loan is open; the
			// green tick is what marks these as already back.
			sb.append("\n- \u2705 ").append(line);
		}
		for (String line : lines)
		{
			sb.append("\n- \u274C ").append(line);
		}
		return sb.append("\n```").toString();
	}

	/**
	 * The green twin of missingList: "+" lines in a diff box show green on PC, and
	 * the tick carries it elsewhere. A return only completes once the collateral
	 * is back too, so that gets its own line.
	 */
	private static String returnedList(List<LendingEntry> loans, List<String> collateral)
	{
		StringBuilder sb = new StringBuilder("```diff");
		int shown = 0;
		for (LendingEntry l : loans)
		{
			if (shown++ >= MAX_LINES)
			{
				sb.append("\n+ ...and ").append(loans.size() - MAX_LINES).append(" more");
				break;
			}
			sb.append("\n+ \u2705 ").append(Math.max(1, l.getQuantity())).append(" x ").append(plain(l.getItemName()));
		}
		if (!collateral.isEmpty())
		{
			sb.append("\n+ \u2705 Collateral back with ").append(plain(loans.get(0).getBorrower()))
				.append(": ").append(plain(String.join(" + ", collateral)));
		}
		return sb.append("\n```").toString();
	}

	/** Overdue: what's still out, in yellow, with who has it. */
	private static String overdueList(List<LendingEntry> loans)
	{
		// An "ansi" code box takes real colour codes; 33 is yellow on Discord for PC.
		// ("fix" boxes used to be yellow but Discord now draws them blue.)
		StringBuilder sb = new StringBuilder("```ansi");
		for (LendingEntry l : loans)
		{
			int q = l.outstandingLentQty();
			if (q > 0)
			{
				sb.append("\n\u001B[33m\u23F0 ").append(q).append(" x ").append(plain(l.getItemName()))
					.append("  (with ").append(plain(l.getBorrower())).append(")\u001B[0m");
			}
		}
		return sb.append("\n```").toString();
	}

	/** Text safe inside a code box: nothing that could close it early. */
	private static String plain(String s)
	{
		return s == null ? "-" : s.replace("`", "'");
	}

	/** "itemId:qty,itemId:qty" -> pairs with a quantity still owed. */
	private static List<int[]> idQty(String csv)
	{
		List<int[]> out = new ArrayList<>();
		if (csv == null || csv.isEmpty())
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String[] kv = part.split(":");
			try
			{
				int id = Integer.parseInt(kv[0].trim());
				int qty = kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1;
				if (id > 0 && qty > 0)
				{
					out.add(new int[] { id, qty });
				}
			}
			catch (NumberFormatException ignored)
			{
				// a damaged pair just isn't listed
			}
		}
		return out;
	}

	/** Item name linked to its OSRS Wiki page (looked up by id, so names never need matching). */
	private static String itemLink(LendingEntry loan)
	{
		String name = md(loan.getItemName()).replace("[", "(").replace("]", ")");
		return loan.getItemId() > 0
			? "[" + name + "](https://oldschool.runescape.wiki/w/Special:Lookup?type=item&id=" + loan.getItemId() + ")"
			: name;
	}

	private static String md(String s)
	{
		if (s == null) return "-";
		return s.replaceAll("([*_~`|>])", "\\\\$1");
	}

	private static JsonObject field(String name, String value, boolean inline)
	{
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", value == null || value.isEmpty() ? "-" : value);
		f.addProperty("inline", inline);
		return f;
	}

	/**
	 * Trimmed to Discord's field limit and otherwise left exactly as it is, so a
	 * bot reads the real name. Pings are already off via allowed_mentions.
	 */
	private static String safe(String s)
	{
		if (s == null) return "-";
		return s.length() > 1000 ? s.substring(0, 1000) : s;
	}
}
