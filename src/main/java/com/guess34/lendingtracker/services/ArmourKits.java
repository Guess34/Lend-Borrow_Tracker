package com.guess34.lendingtracker.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which kit a piece of armour belongs to from its name, so somebody
 * who lists a helm, a body and a pair of legs one at a time still reads as
 * "Torva set" instead of three rows in a row.
 *
 * Nothing here is stored or sent anywhere - it is worked out again every time
 * the marketplace draws, and a set someone made by hand always wins. The rule
 * is deliberately shy: the name has to end in a piece we know, and anything it
 * cannot place stays its own listing. Grouping gear that is not really a kit is
 * worse than not grouping it, because it suggests a lend nobody can actually
 * make.
 */
public final class ArmourKits
{
	/**
	 * What kind of armour a piece is. Only used to keep look-alikes apart - a
	 * Bandos chestplate and a Bandos d'hide body both want the body slot, and
	 * they are plainly not the same kit.
	 */
	public enum Cls
	{
		METAL("armour"),
		HIDE("dragonhide"),
		ROBE("robes"),
		NEUTRAL("gear");

		private final String word;

		Cls(String word)
		{
			this.word = word;
		}

		/** Added to the name only when one key produced more than one kit. */
		public String getWord()
		{
			return word;
		}
	}

	/** One listing's place in a kit, or nothing if it does not belong to one. */
	public static final class Kit
	{
		private final String key;
		private final String title;
		private final Cls cls;

		Kit(String key, String title, Cls cls)
		{
			this.key = key;
			this.title = title;
			this.cls = cls;
		}

		/** Pieces sharing this belong together. Includes the (t)/(g)/(f) variant. */
		public String getKey()
		{
			return key;
		}

		/** "Dharok's", "Masori (f)" - spelled the way the game spells it. */
		public String getTitle()
		{
			return title;
		}

		public Cls getCls()
		{
			return cls;
		}
	}

	// The tail of the name that says which piece it is. Longest match wins, so
	// "Inquisitor's great helm" loses "great helm" and not just "helm" - strip
	// one word and it would never match "Inquisitor's hauberk".
	private static final Map<String, Cls> PIECES = new HashMap<>();

	static
	{
		put(Cls.METAL, "full helm", "full helmet", "med helm", "great helm", "sq shield",
			"helm", "helmet", "platebody", "chestplate", "chestguard", "hauberk", "cuirass",
			"brassard", "chainbody", "platelegs", "plateskirt", "chainskirt", "tassets",
			"chausses", "cuisse", "legguards", "greaves", "kiteshield", "faceguard",
			"sallet", "chest");
		put(Cls.HIDE, "d'hide body", "d'hide chaps", "d'hide vambraces", "d'hide shield",
			"coif", "cowl", "chaps", "vambraces", "bracers", "leathertop", "leatherskirt");
		put(Cls.ROBE, "robe top", "robe bottom", "robe bottoms", "robe legs", "robe skirt",
			"robe", "robetop", "robeskirt", "hood", "hat", "mitre", "stole", "bottoms", "skirt");
		put(Cls.NEUTRAL, "body", "top", "legs", "mask", "boots", "shoes", "gloves",
			"gauntlets", "shield", "torso");
	}

	private static void put(Cls cls, String... names)
	{
		for (String n : names)
		{
			PIECES.put(n, cls);
		}
	}

	// Worn as the same kit but named as if they were four (Void knight top, Void
	// mage helm). The only family whose own pieces disagree on their leading
	// words, so it is a row here rather than a cleverer rule that would also
	// start merging things that should stay apart.
	private static final Set<String> VOID_WORDS = new HashSet<>(
		Arrays.asList("knight", "mage", "ranger", "melee"));

	// Armour that looks like part of a set by name and is not. A Black mask is
	// not Black armour, and a defender is not a shield anyone lends as a look.
	private static final List<String> BLOCKED = Collections.unmodifiableList(Arrays.asList(
		"black mask", "slayer helmet", "defender", "spiny helmet", "rune pouch"));

	// Said by the material, never by the kit: "Black d'hide body" is a black kit
	// made of hide, not a "black d'hide" kit. It comes off the key so the pieces
	// find each other, and goes back on the name so the row still reads right.
	private static final Map<String, String> MATERIALS = new HashMap<>();

	static
	{
		MATERIALS.put("d'hide", "dragonhide");
		MATERIALS.put("dragonhide", "dragonhide");
		MATERIALS.put("leather", "leather");
		MATERIALS.put("studded", "studded");
		MATERIALS.put("hard", "");
	}

	private ArmourKits()
	{
	}

	/**
	 * Which kit this item belongs to, or null if it should stay its own listing.
	 * Only ever asked about things the game says are worn as armour - this reads
	 * the name, it does not decide what armour is.
	 */
	public static Kit parse(String itemName)
	{
		if (itemName == null)
		{
			return null;
		}
		String base = itemName.trim();
		if (base.isEmpty())
		{
			return null;
		}

		// Barrows wear ("Karil's leathertop 75") is the same kit at any condition,
		// so the number comes off and is left to show on the piece itself.
		base = base.replaceAll("\\s(100|75|50|25|0)$", "");

		// Trim, gold trim, ornament kits and so on are part of the kit: a (t) body
		// with a (g) helm is not a matching set and must never be sold as one.
		List<String> variants = new ArrayList<>();
		while (true)
		{
			int open = base.lastIndexOf('(');
			if (open <= 0 || !base.endsWith(")"))
			{
				break;
			}
			variants.add(base.substring(open + 1, base.length() - 1).trim()
				.toLowerCase(java.util.Locale.ROOT));
			base = base.substring(0, open).trim();
		}
		Collections.sort(variants);

		// Locale.ROOT everywhere a kit key is built: under a Turkish locale "I"
		// lower-cases to a dotless i, so "Inquisitor's" would key differently on that
		// machine than on everyone else's.
		String lowerBase = base.toLowerCase(java.util.Locale.ROOT);
		for (String bad : BLOCKED)
		{
			if (lowerBase.contains(bad))
			{
				return null;
			}
		}

		String[] words = base.split("\\s+");
		if (words.length < 2)
		{
			return null;
		}
		String[] lower = new String[words.length];
		for (int i = 0; i < words.length; i++)
		{
			lower[i] = words[i].toLowerCase(java.util.Locale.ROOT);
		}

		// Longest piece name that fits the end of the name, then whatever is left
		// in front of it is the kit.
		Cls cls = null;
		int keep = -1;
		String piece = null;
		for (int take = Math.min(2, words.length); take >= 1 && cls == null; take--)
		{
			String tail = String.join(" ", Arrays.asList(lower).subList(words.length - take, words.length));
			Cls found = PIECES.get(tail);
			if (found != null)
			{
				cls = found;
				keep = words.length - take;
				piece = tail;
			}
		}
		if (cls == null || keep <= 0)
		{
			return null;
		}

		List<String> keyWords = new ArrayList<>();
		List<String> titleWords = new ArrayList<>();
		String material = null;
		for (int i = 0; i < keep; i++)
		{
			String made = MATERIALS.get(lower[i]);
			if (made != null)
			{
				if (!made.isEmpty())
				{
					material = made;
				}
				continue;
			}
			keyWords.add(lower[i]);
			titleWords.add(words[i]);
		}
		if (keyWords.isEmpty())
		{
			return null;
		}
		if (cls == Cls.NEUTRAL && material != null)
		{
			cls = Cls.HIDE;
		}
		// "Bandos d'hide body" carries its material in the piece instead, and the
		// row still wants to say so.
		if (material == null && (piece.startsWith("d'hide") || piece.startsWith("dragonhide")))
		{
			material = "dragonhide";
		}

		// Void: one kit, four names for it.
		if (keyWords.size() == 2 && "void".equals(keyWords.get(0)) && VOID_WORDS.contains(keyWords.get(1)))
		{
			keyWords = new ArrayList<>(Collections.singletonList("void"));
			titleWords = new ArrayList<>(Collections.singletonList(words[0]));
		}

		String variant = String.join("+", variants);
		String title = String.join(" ", titleWords);
		if (material != null)
		{
			title = title + " " + material;
		}
		if (!variant.isEmpty())
		{
			title = title + " (" + String.join(", ", variants) + ")";
		}
		return new Kit(String.join(" ", keyWords) + "|" + variant, title, cls);
	}
}
