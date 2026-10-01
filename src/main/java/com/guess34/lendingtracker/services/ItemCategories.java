package com.guess34.lendingtracker.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Sorts gear into the groups people actually shop by - melee, range, mage, tank
 * and so on - straight from the item's own equipment bonuses.
 *
 * Worked out on each client from RuneLite's item data and never sent anywhere,
 * so nobody has to tag anything and older clients see nothing new on the wire.
 * The lookup has to run on the client thread, so results are cached and the
 * panel asks for anything it has not seen yet, then redraws once it is known.
 */
@Slf4j
@Singleton
public class ItemCategories
{
	public enum Category
	{
		MELEE("Melee"),
		RANGE("Range"),
		MAGIC("Mage"),
		TANK("Tank"),
		DPS("DPS"),
		STAB("Stab"),
		SLASH("Slash"),
		CRUSH("Crush");

		private final String label;

		Category(String label)
		{
			this.label = label;
		}

		public String getLabel()
		{
			return label;
		}
	}

	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final Map<Integer, Set<Category>> cache = new ConcurrentHashMap<>();
	// Where the item is worn, kept alongside the categories because the lookup
	// that answers one already answers the other.
	private final Map<Integer, Integer> slots = new ConcurrentHashMap<>();
	private final Set<Integer> queued = ConcurrentHashMap.newKeySet();
	// True once RuneLite's item stats have answered for anything at all. After
	// that a blank answer is a real one ("this item has no equipment stats")
	// rather than "not downloaded yet", so it can be cached instead of being
	// asked again for ever.
	private volatile boolean statsReady;

	/**
	 * The slots that hold actual armour. A cape, amulet, ring or arrow is worn
	 * too, but it is a one-per-setup pick people hunt by name, so it stays its
	 * own listing instead of disappearing into somebody's armour bundle.
	 * Weapons stay separate for the same reason.
	 */
	private static final Set<Integer> ARMOUR_SLOTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		EquipmentInventorySlot.HEAD.getSlotIdx(),
		EquipmentInventorySlot.BODY.getSlotIdx(),
		EquipmentInventorySlot.LEGS.getSlotIdx(),
		EquipmentInventorySlot.GLOVES.getSlotIdx(),
		EquipmentInventorySlot.BOOTS.getSlotIdx(),
		EquipmentInventorySlot.SHIELD.getSlotIdx())));

	@Inject
	public ItemCategories(ItemManager itemManager, ClientThread clientThread)
	{
		this.itemManager = itemManager;
		this.clientThread = clientThread;
	}

	/** This item's categories, or null if it has not been worked out yet. */
	public Set<Category> get(int itemId)
	{
		return cache.get(itemId);
	}

	/**
	 * Where this item is worn, or -1 if it is not worn at all - or simply not
	 * known yet. Reads the cache only, so it is safe on every redraw.
	 */
	public int getSlot(int itemId)
	{
		Integer slot = slots.get(itemId);
		return slot == null ? -1 : slot;
	}

	/**
	 * True if this is worn as armour - head, body, legs, hands, feet or shield.
	 * Anything nobody has asked about yet answers false, so whatever groups by
	 * this has to draw again from prime()'s callback.
	 */
	public boolean isArmour(int itemId)
	{
		return ARMOUR_SLOTS.contains(getSlot(itemId));
	}

	/**
	 * Work out any of these not known yet, then run onReady if anything new was
	 * learned. Cheap to call on every redraw - known and already-queued ids are
	 * skipped.
	 */
	public void prime(Collection<Integer> itemIds, Runnable onReady)
	{
		List<Integer> missing = new ArrayList<>();
		for (Integer id : itemIds)
		{
			if (id != null && id > 0 && !cache.containsKey(id) && queued.add(id))
			{
				missing.add(id);
			}
		}
		if (missing.isEmpty())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			Map<Integer, Set<Category>> batch = new HashMap<>();
			Map<Integer, Integer> slotBatch = new HashMap<>();
			boolean anyStats = false;
			for (int id : missing)
			{
				ItemStats stats = null;
				try
				{
					stats = itemManager.getItemStats(id);
				}
				catch (Exception e)
				{
					log.debug("No item stats for {}: {}", id, e.getMessage());
				}
				anyStats |= stats != null;
				batch.put(id, classify(stats));
				slotBatch.put(id, slotOf(stats));
			}
			queued.removeAll(missing);
			// A batch with no stats at all means RuneLite has not finished
			// downloading them yet. Caching that would file every item under
			// nothing for the rest of the session, so leave it to be asked again.
			if (anyStats)
			{
				statsReady = true;
			}
			if (anyStats || statsReady)
			{
				cache.putAll(batch);
				// Has to stay in here with the categories: cached from an empty
				// batch, every item would read as "worn nowhere" for the rest of
				// the session and nothing would ever group.
				slots.putAll(slotBatch);
				if (onReady != null)
				{
					onReady.run();
				}
			}
		});
	}

	/** -1 for anything not worn. Handles nulls exactly as classify() does. */
	static int slotOf(ItemStats stats)
	{
		if (stats == null || !stats.isEquipable() || stats.getEquipment() == null)
		{
			return -1;
		}
		return stats.getEquipment().getSlot();
	}

	static Set<Category> classify(ItemStats stats)
	{
		if (stats == null || !stats.isEquipable() || stats.getEquipment() == null)
		{
			return Collections.emptySet();
		}
		ItemEquipmentStats e = stats.getEquipment();
		Set<Category> out = EnumSet.noneOf(Category.class);
		boolean weapon = e.getSlot() == EquipmentInventorySlot.WEAPON.getSlotIdx();

		// Each style scores its accuracy plus its damage. Magic damage is a
		// percentage, so it is weighted up to sit on the same scale.
		float melee = Math.max(e.getAstab(), Math.max(e.getAslash(), e.getAcrush())) + e.getStr();
		float range = e.getArange() + e.getRstr();
		float magic = e.getAmagic() + e.getMdmg() * 3f;
		float best = Math.max(melee, Math.max(range, magic));
		if (best > 0)
		{
			// Anything close to the best style counts too, so hybrid gear
			// shows up under every style it is actually good for.
			if (melee > 0 && melee >= best * 0.6f) out.add(Category.MELEE);
			if (range > 0 && range >= best * 0.6f) out.add(Category.RANGE);
			if (magic > 0 && magic >= best * 0.6f) out.add(Category.MAGIC);
		}

		// Tank: heavy defence and next to nothing on offence (Justiciar, Torva,
		// Elysian, DFS), or a weapon that is mostly a wall (Dinh's bulwark).
		int defence = e.getDstab() + e.getDslash() + e.getDcrush() + e.getDrange();
		if ((defence >= 150 && best <= 8) || (weapon && defence >= 300))
		{
			out.add(Category.TANK);
		}

		// DPS: gear that adds damage. For weapons that is any damage bonus, or
		// enough accuracy to be a real ranged or magic weapon.
		boolean dps = weapon
			? e.getStr() > 0 || e.getRstr() > 0 || e.getMdmg() > 0 || e.getArange() >= 60 || e.getAmagic() >= 25
			: e.getStr() >= 5 || e.getRstr() >= 2 || e.getMdmg() >= 2;
		if (dps)
		{
			out.add(Category.DPS);
		}

		// Attack style. Weapons take their strongest; other gear only when one
		// style clearly leads (Inquisitor's is crush, a torture is all three).
		if (out.contains(Category.MELEE))
		{
			int stab = e.getAstab();
			int slash = e.getAslash();
			int crush = e.getAcrush();
			int top = Math.max(stab, Math.max(slash, crush));
			int second = stab + slash + crush - top - Math.min(stab, Math.min(slash, crush));
			if (top > 0 && (weapon || top >= second * 1.25f))
			{
				out.add(top == stab ? Category.STAB : top == slash ? Category.SLASH : Category.CRUSH);
			}
		}
		return out;
	}
}
