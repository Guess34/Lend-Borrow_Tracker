package com.guess34.lendingtracker.services;

import com.guess34.lendingtracker.model.LendingEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

/**
 * Turns armour somebody listed piece by piece into a real item set, so a helm, a
 * body and some legs read as "Torva set" for the whole group instead of three
 * rows in a row.
 *
 * Real sets rather than a tidier drawing on one screen: a set lives on the
 * listings themselves and syncs, so everyone sees the same thing, and renaming,
 * breaking up and "request whole set" already work on it.
 *
 * Only ever our own listings - a set is the owner's statement about their own
 * gear, and no other client may make it for them. Break one up and it stays
 * broken until the owner puts it back themselves.
 *
 * Two decisions hold the whole thing together:
 *
 * The set id is WORKED OUT, not invented. It is a name-based UUID over the group,
 * the owner and the kit, so a piece listed next week lands in the same set as the
 * first three, and the owner's second computer arrives at the same id instead of
 * minting a rival set for the same gear. A set built from scratch by hand gets a
 * random id and is never mistaken for one of these.
 *
 * A kit is not something you break up. Selling or lending a piece takes that one
 * listing off the market and whatever is left stays grouped, which is what keeps
 * the board readable. There is deliberately no "leave this kit alone" state: an
 * invisible, permanent flag that switched grouping off for a whole family, with
 * nothing on screen to explain it, is the trap an earlier version fell into.
 */
@Slf4j
@Singleton
public class ArmourSets
{
	/** Item sets are named for people, not databases; the set dialog caps here too. */
	private static final int MAX_NAME = 30;

	private final DataService dataService;
	private final ItemCategories itemCategories;

	/**
	 * What our own loose armour looked like last time we tried. Grouping only runs
	 * again once that changes, so redrawing the panel costs nothing.
	 */
	private final Map<String, String> lastSeen = new HashMap<>();

	@Inject
	public ArmourSets(DataService dataService, ItemCategories itemCategories)
	{
		this.dataService = dataService;
		this.itemCategories = itemCategories;
	}

	/** One kit's worth of an owner's listings, with the set it belongs in. */
	public static final class Kit
	{
		private final String family;
		private final String setId;
		private final String name;
		private final List<LendingEntry> pieces;
		private final boolean alreadyHasSet;
		/** The set existed under an older id of ours and is only being re-stamped. */
		private final boolean reStamped;

		Kit(String family, String setId, String name, List<LendingEntry> pieces,
			boolean alreadyHasSet, boolean reStamped)
		{
			this.reStamped = reStamped;
			this.family = family;
			this.setId = setId;
			this.name = name;
			this.pieces = pieces;
			this.alreadyHasSet = alreadyHasSet;
		}

		/** The kit this belongs to, e.g. "torva|". What "leave it alone" is keyed on. */
		public String getFamily()
		{
			return family;
		}

		public String getName()
		{
			return name;
		}

		/** The listings that are not in this set yet. */
		public List<LendingEntry> getPieces()
		{
			return pieces;
		}
	}

	/**
	 * Group any of our own armour that plainly belongs together and is not in its
	 * set yet. Returns the names of the sets it created, so the caller can say so;
	 * a piece joining a set that is already on screen is not announced.
	 *
	 * Safe to call on every redraw: it does nothing unless our own loose armour has
	 * actually changed, and nothing at all until the item stats that tell armour
	 * from a weapon have loaded - in which case it asks for them itself and runs
	 * onReady once they arrive.
	 */
	public List<String> groupNewKits(String groupId, String owner, Runnable onReady)
	{
		if (groupId == null || groupId.isEmpty() || owner == null)
		{
			return Collections.emptyList();
		}
		List<LendingEntry> mine = dataService.getOfferingsByOwner(groupId, owner);
		if (mine.isEmpty())
		{
			log.debug("No kits: {} has nothing listed in {}", owner, groupId);
			return Collections.emptyList();
		}

		// Act on complete information or not at all: grouping two pieces of a
		// three-piece kit because the third was not known yet would leave the odd
		// one sitting outside its own set. Ask for whatever is missing rather than
		// waiting to be fed it, so this cannot stall on an id nobody else primes.
		List<Integer> waiting = new ArrayList<>();
		for (LendingEntry e : mine)
		{
			// An id at or below zero is never queued and never resolves, so waiting
			// on it would switch the feature off for this player for good. It can
			// never be armour either, so it can never be a missing kit piece.
			if (e.getItemId() > 0 && itemCategories.get(e.getItemId()) == null)
			{
				waiting.add(e.getItemId());
			}
		}
		if (!waiting.isEmpty())
		{
			// Not a failure - the item stats arrive a moment later and the callback
			// brings us straight back here.
			log.debug("No kits yet: waiting on stats for {} item(s)", waiting.size());
			itemCategories.prime(waiting, onReady);
			return Collections.emptyList();
		}

		// Sets built by hand before kits grouped themselves are just kits wearing a
		// random id. Take them over first, or the same gear ends up in two different
		// sets on two different machines and neither can be dissolved.
		if (adoptHandMadeKits(groupId, owner, mine))
		{
			mine = dataService.getOfferingsByOwner(groupId, owner);
		}

		String mark = groupId + "#" + owner.toLowerCase();
		List<Kit> kits = proposeKits(groupId, owner, mine);
		StringBuilder signature = new StringBuilder();
		for (Kit k : kits)
		{
			signature.append(k.getFamily()).append('/').append(k.getPieces().size()).append(';');
		}
		if (signature.toString().equals(lastSeen.get(mark)))
		{
			return Collections.emptyList();
		}
		log.debug("Kits for {}: {}", owner, signature.length() == 0 ? "(none)" : signature);

		List<DataService.AutoSet> batch = new ArrayList<>();
		List<String> made = new ArrayList<>();
		for (Kit kit : kits)
		{
			batch.add(new DataService.AutoSet(kit.setId, kit.getName(), itemIdsOf(kit)));
			// A re-stamp is the same set under a new id, so saying "grouped your
			// listings into ..." would be a lie about a set already on screen.
			if (!kit.alreadyHasSet && !kit.reStamped)
			{
				made.add(kit.getName());
			}
		}
		// Only remember having done this once it has actually settled. Recording it
		// up front meant a write that landed nowhere - an owner key not there yet,
		// say - was never tried again for the rest of the session.
		if (batch.isEmpty())
		{
			lastSeen.put(mark, signature.toString());
			return Collections.emptyList();
		}
		if (dataService.applyAutoSets(groupId, owner, batch) == 0)
		{
			return Collections.emptyList();
		}
		lastSeen.put(mark, signature.toString());
		return made;
	}

	/**
	 * True for a set this class worked out, as opposed to one the owner built by
	 * hand. Ours are name-derived UUIDs (version 3); anything made by hand is random
	 * (version 4), including every set made before this existed.
	 */
	public static boolean isAutoSet(String setId)
	{
		if (setId == null || setId.isEmpty())
		{
			return false;
		}
		try
		{
			return UUID.fromString(setId).version() == 3;
		}
		catch (IllegalArgumentException e)
		{
			return false;
		}
	}

	/**
	 * Kits worth drawing as one row, for gear that has no real set behind it -
	 * somebody whose own client has not grouped it because they have not updated
	 * yet. Display only: nothing is written, nothing is sent, and a listing that is
	 * already in a set is never touched.
	 *
	 * The owner's own listings do not come through here. Theirs are real sets, and
	 * anything of theirs still loose is loose on purpose.
	 */
	public List<Kit> displayKits(List<LendingEntry> listings)
	{
		Map<String, List<LendingEntry>> buckets = new LinkedHashMap<>();
		Map<Integer, ArmourKits.Kit> parsed = new HashMap<>();
		for (LendingEntry e : listings)
		{
			if (e == null || e.isInSet() || !itemCategories.isArmour(e.getItemId()))
			{
				continue;
			}
			ArmourKits.Kit kit = ArmourKits.parse(e.getItem());
			if (kit == null)
			{
				continue;
			}
			parsed.put(e.getItemId(), kit);
			buckets.computeIfAbsent(kit.getKey(), k -> new ArrayList<>()).add(e);
		}

		List<Kit> out = new ArrayList<>();
		for (Map.Entry<String, List<LendingEntry>> bucket : buckets.entrySet())
		{
			List<List<LendingEntry>> groups = splitByKind(bucket.getValue(), parsed);
			boolean several = groups.size() > 1;
			for (List<LendingEntry> group : groups)
			{
				if (group.size() < 2 || distinctSlots(group) < 2)
				{
					continue;
				}
				out.add(new Kit(bucket.getKey() + "|" + kindOf(group, parsed).name(),
					null, nameFor(group, parsed, several), group, false, false));
			}
		}
		return out;
	}

	/**
	 * The item ids in this lot that make up a real kit - the pieces that would
	 * group themselves anyway.
	 *
	 * Those are not something to unpick by hand: a kit piece leaves by being sold
	 * or lent, which takes the listing off the board and leaves the rest grouped.
	 * Anything else in a set was put there by the owner - a Serpentine helm sitting
	 * with Torva legs and platebody - and is theirs to take out again.
	 */
	public Set<Integer> kitCore(List<LendingEntry> pieces)
	{
		Map<String, List<LendingEntry>> buckets = new LinkedHashMap<>();
		Map<Integer, ArmourKits.Kit> parsed = new HashMap<>();
		if (pieces != null)
		{
			for (LendingEntry e : pieces)
			{
				if (e == null || !itemCategories.isArmour(e.getItemId()))
				{
					continue;
				}
				ArmourKits.Kit kit = ArmourKits.parse(e.getItem());
				if (kit == null)
				{
					continue;
				}
				parsed.put(e.getItemId(), kit);
				buckets.computeIfAbsent(kit.getKey(), k -> new ArrayList<>()).add(e);
			}
		}

		Set<Integer> core = new HashSet<>();
		for (List<LendingEntry> bucket : buckets.values())
		{
			for (List<LendingEntry> group : splitByKind(bucket, parsed))
			{
				if (group.size() >= 2 && distinctSlots(group) >= 2)
				{
					for (LendingEntry e : group)
					{
						core.add(e.getItemId());
					}
				}
			}
		}
		return core;
	}

	/**
	 * Re-stamp any set the owner built by hand that turns out to be nothing but a
	 * kit, so it carries the kit's own derived id and behaves like every other one.
	 * The name they gave it is kept.
	 *
	 * A set that MIXES things - a Bandos chest with Justiciar legs, or a kit with a
	 * Serpentine helm added - is the owner's own arrangement and is left alone.
	 * That is the whole distinction: kits group themselves, mixtures are built.
	 *
	 * @return true if anything changed, so the caller can re-read the listings.
	 */
	private boolean adoptHandMadeKits(String groupId, String owner, List<LendingEntry> mine)
	{
		Map<String, List<LendingEntry>> bySet = new LinkedHashMap<>();
		for (LendingEntry e : mine)
		{
			if (e != null && e.isInSet() && !isAutoSet(e.getSetId()))
			{
				bySet.computeIfAbsent(e.getSetId(), k -> new ArrayList<>()).add(e);
			}
		}

		List<DataService.AutoSet> batch = new ArrayList<>();
		for (Map.Entry<String, List<LendingEntry>> set : bySet.entrySet())
		{
			List<LendingEntry> pieces = set.getValue();
			// Every piece has to be part of the kit. One odd item in there and this
			// is a set somebody meant to build.
			if (pieces.size() < 2 || kitCore(pieces).size() != pieces.size())
			{
				continue;
			}

			Map<Integer, ArmourKits.Kit> parsed = new HashMap<>();
			String family = null;
			boolean single = true;
			for (LendingEntry e : pieces)
			{
				ArmourKits.Kit kit = ArmourKits.parse(e.getItem());
				if (kit == null)
				{
					single = false;
					break;
				}
				parsed.put(e.getItemId(), kit);
				if (family == null)
				{
					family = kit.getKey();
				}
				else if (!family.equals(kit.getKey()))
				{
					// Two kits sharing one hand-made set: leave it, rather than
					// guessing which of them the set is supposed to be.
					single = false;
					break;
				}
			}
			if (!single || family == null)
			{
				continue;
			}

			String derived = autoSetId(groupId, owner, family, kindOf(pieces, parsed));
			if (derived.equals(set.getKey()))
			{
				continue;
			}
			List<Integer> ids = new ArrayList<>();
			for (LendingEntry e : pieces)
			{
				ids.add(e.getItemId());
			}
			String name = pieces.get(0).getSetName();
			batch.add(new DataService.AutoSet(derived,
				name != null && !name.isEmpty() ? name : nameFor(pieces, parsed, false), ids));
		}

		if (batch.isEmpty())
		{
			return false;
		}
		lastSeen.remove(groupId + "#" + owner.toLowerCase());
		return dataService.applyAutoSets(groupId, owner, batch) > 0;
	}

	/**
	 * If this lot contains a kit, the set that kit belongs to - the plugin's own,
	 * with its own id and name. Null when there is no kit in there.
	 *
	 * Real sets are the plugin's to make. Somebody ticking a kit into a set of
	 * their own would otherwise create a rival claim on the same gear, and since a
	 * piece can only be in one set, whichever wrote last would win - differently on
	 * different machines. Extras they picked join the kit's set instead.
	 */
	public Kit kitSetFor(String groupId, String owner, List<LendingEntry> picked)
	{
		Set<Integer> core = kitCore(picked);
		if (core.isEmpty())
		{
			return null;
		}

		List<LendingEntry> kitPieces = new ArrayList<>();
		Map<Integer, ArmourKits.Kit> parsed = new HashMap<>();
		String family = null;
		for (LendingEntry e : picked)
		{
			if (e == null || !core.contains(e.getItemId()))
			{
				continue;
			}
			ArmourKits.Kit kit = ArmourKits.parse(e.getItem());
			if (kit == null)
			{
				continue;
			}
			// Two different kits ticked together: go with the first, rather than
			// guessing which one the person meant.
			if (family == null)
			{
				family = kit.getKey();
			}
			else if (!family.equals(kit.getKey()))
			{
				continue;
			}
			parsed.put(e.getItemId(), kit);
			kitPieces.add(e);
		}
		if (family == null || kitPieces.isEmpty())
		{
			return null;
		}
		return new Kit(family, autoSetId(groupId, owner, family, kindOf(kitPieces, parsed)),
			nameFor(kitPieces, parsed, false), kitPieces, true, false);
	}

	/** The id of the set this kit belongs to. */
	public static String setIdOf(Kit kit)
	{
		return kit == null ? null : kit.setId;
	}

	// ------------------------------------------------------------------ detection

	/**
	 * Which of this owner's listings belong in which kit. Pieces already in a set
	 * come along, because they are how we know a kit has one and what it is called.
	 */
	private List<Kit> proposeKits(String groupId, String owner, List<LendingEntry> listings)
	{
		Map<String, List<LendingEntry>> buckets = new LinkedHashMap<>();
		Map<Integer, ArmourKits.Kit> parsed = new HashMap<>();
		for (LendingEntry e : listings)
		{
			// Only armour, and only on the game's own word for where it is worn -
			// weapons, rings, amulets and capes are picks in their own right.
			if (e == null || !itemCategories.isArmour(e.getItemId()))
			{
				continue;
			}
			ArmourKits.Kit kit = ArmourKits.parse(e.getItem());
			if (kit == null)
			{
				continue;
			}
			parsed.put(e.getItemId(), kit);
			buckets.computeIfAbsent(kit.getKey(), k -> new ArrayList<>()).add(e);
		}

		List<Kit> out = new ArrayList<>();
		for (Map.Entry<String, List<LendingEntry>> bucket : buckets.entrySet())
		{
			List<List<LendingEntry>> groups = splitByKind(bucket.getValue(), parsed);
			boolean several = groups.size() > 1;
			for (List<LendingEntry> group : groups)
			{
				String setId = autoSetId(groupId, owner, bucket.getKey(), kindOf(group, parsed));

				// If the set already exists, ITS name is the one that counts. Settling
				// that first stops a row still carrying an older id of ours from
				// renaming the set it is about to be merged into - which would leave
				// one set id wearing two different names across its own rows.
				boolean hasSet = false;
				String name = null;
				for (LendingEntry e : group)
				{
					if (setId.equals(e.getSetId()))
					{
						hasSet = true;
						if (name == null)
						{
							name = e.getSetName();
						}
					}
				}

				// Anything not already carrying this kit's own set id needs adding.
				List<LendingEntry> loose = new ArrayList<>();
				boolean reStamped = false;
				String carried = null;
				for (LendingEntry e : group)
				{
					if (setId.equals(e.getSetId()))
					{
						continue;
					}
					if (!e.isInSet())
					{
						loose.add(e);
					}
					else if (isAutoId(groupId, owner, bucket.getKey(), e.getSetId()))
					{
						// A set id we could have minted for this same kit is one of ours,
						// under a kind that has since changed because another piece got
						// listed. Re-stamp it rather than stranding the new piece outside
						// its own set.
						loose.add(e);
						reStamped = true;
						if (carried == null)
						{
							carried = e.getSetName();
						}
					}
					// A piece the owner arranged into some other set is theirs, and is
					// left alone.
				}
				if (loose.isEmpty())
				{
					continue;
				}
				// With no set of its own yet, a kit has to be worth the name: two
				// pieces over two different slots. Two of the same helm is a quantity,
				// not a kit. Once the set exists a single piece may join it, which is
				// the whole point of listing a fourth piece later.
				if (!hasSet && (loose.size() < 2 || distinctSlots(loose) < 2))
				{
					continue;
				}
				if (name == null || name.isEmpty())
				{
					// Keep a name the owner gave the old set, unless this family has
					// just split in two - then carrying it would leave both halves
					// called the same thing.
					name = carried != null && !carried.isEmpty() && !several
						? carried : nameFor(group, parsed, several);
				}
				out.add(new Kit(bucket.getKey(), setId, name, loose, hasSet, reStamped));
			}
		}
		return out;
	}

	/**
	 * The set id for one kit of one owner's gear: worked out rather than invented,
	 * so every machine agrees and a piece listed later joins the same set. The kind
	 * is part of it because one name can produce two kits - Bandos plate and Bandos
	 * dragonhide are not the same gear.
	 */
	private static String autoSetId(String groupId, String owner, String family, ArmourKits.Cls kind)
	{
		// Locale.ROOT throughout: a Turkish or Azeri locale lower-cases "I" to a
		// dotless i, which would hand that machine a different id for the same gear.
		String seed = "lending-tracker/auto-set|" + groupId + "|"
			+ (owner == null ? "" : owner.toLowerCase(java.util.Locale.ROOT))
			+ "|" + family + "|" + kind.name();
		return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
	}

	/**
	 * True if this set id is one WE could have minted for this same kit, under any
	 * kind, which makes it ours to re-stamp rather than the owner's arrangement.
	 *
	 * A set built from scratch by hand gets a random UUID and can never match one of
	 * these - but that is NOT on its own what protects the owner's choices, because
	 * the set dialog also lets them tick a piece into a set of ours, and that piece
	 * then carries our derived id. What protects them is the keep-apart marker:
	 * moving a piece between sets by hand writes one, and nothing here is applied to
	 * a family that has one.
	 */
	private static boolean isAutoId(String groupId, String owner, String family, String id)
	{
		if (id == null || id.isEmpty())
		{
			return false;
		}
		for (ArmourKits.Cls kind : ArmourKits.Cls.values())
		{
			if (autoSetId(groupId, owner, family, kind).equals(id))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Two pieces wanting the same slot are only a problem when they are plainly
	 * different armour - a Bandos chestplate and a Bandos d'hide body. The same slot
	 * twice in the same kind is just a spare, like platelegs and a skirt.
	 */
	private List<List<LendingEntry>> splitByKind(List<LendingEntry> members,
		Map<Integer, ArmourKits.Kit> parsed)
	{
		Map<Integer, ArmourKits.Cls> taken = new HashMap<>();
		boolean clash = false;
		for (LendingEntry e : members)
		{
			ArmourKits.Cls kind = parsed.get(e.getItemId()).getCls();
			if (kind == ArmourKits.Cls.NEUTRAL)
			{
				continue;
			}
			ArmourKits.Cls was = taken.put(itemCategories.getSlot(e.getItemId()), kind);
			if (was != null && was != kind)
			{
				clash = true;
			}
		}
		if (!clash)
		{
			return Collections.singletonList(members);
		}

		// Once one name has produced two kits, boots and gloves say nothing about
		// which of them they belong with. Guessing put metal boots in the dragonhide
		// set, so they are left out of both and stay listings of their own - the
		// owner can add them by hand if they want them in one.
		Map<ArmourKits.Cls, List<LendingEntry>> byKind = new LinkedHashMap<>();
		for (LendingEntry e : members)
		{
			ArmourKits.Cls kind = parsed.get(e.getItemId()).getCls();
			if (kind != ArmourKits.Cls.NEUTRAL)
			{
				byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(e);
			}
		}
		return new ArrayList<>(byKind.values());
	}

	/**
	 * What kind of armour this group is. Taken in enum order rather than list order,
	 * so it cannot change when the pieces arrive in a different sequence - the set
	 * id is built from it and has to stay put.
	 */
	private ArmourKits.Cls kindOf(List<LendingEntry> group, Map<Integer, ArmourKits.Kit> parsed)
	{
		for (ArmourKits.Cls candidate : ArmourKits.Cls.values())
		{
			if (candidate == ArmourKits.Cls.NEUTRAL)
			{
				continue;
			}
			for (LendingEntry e : group)
			{
				if (parsed.get(e.getItemId()).getCls() == candidate)
				{
					return candidate;
				}
			}
		}
		return ArmourKits.Cls.NEUTRAL;
	}

	/**
	 * The fullest name any piece gives the kit - "Bandos dragonhide" reads better
	 * than "Bandos" just because the chaps happened to come first. The kind is only
	 * worth saying when one name produced two kits.
	 */
	private String nameFor(List<LendingEntry> group, Map<Integer, ArmourKits.Kit> parsed, boolean several)
	{
		ArmourKits.Kit best = parsed.get(group.get(0).getItemId());
		for (LendingEntry e : group)
		{
			ArmourKits.Kit other = parsed.get(e.getItemId());
			if (other != null && other.getTitle().length() > best.getTitle().length())
			{
				best = other;
			}
		}
		String word = best.getCls().getWord();
		boolean say = several && !best.getTitle().toLowerCase(java.util.Locale.ROOT).endsWith(word);
		String name = best.getTitle() + (say ? " " + word : "") + " set";
		return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
	}

	private int distinctSlots(List<LendingEntry> pieces)
	{
		Set<Integer> slots = new HashSet<>();
		for (LendingEntry e : pieces)
		{
			slots.add(itemCategories.getSlot(e.getItemId()));
		}
		return slots.size();
	}

	private static List<Integer> itemIdsOf(Kit kit)
	{
		List<Integer> ids = new ArrayList<>();
		for (LendingEntry e : kit.getPieces())
		{
			ids.add(e.getItemId());
		}
		return ids;
	}
}
