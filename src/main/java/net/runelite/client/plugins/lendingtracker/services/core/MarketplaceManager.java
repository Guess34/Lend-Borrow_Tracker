package net.runelite.client.plugins.lendingtracker.services.core;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.lendingtracker.model.LendingEntry;
import net.runelite.client.plugins.lendingtracker.services.Recorder;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MarketplaceManager - P2P item offerings marketplace
 *
 * UNIFIED: Now delegates ALL storage operations to Recorder to prevent duplicates.
 * This class provides a cleaner API while Recorder handles the actual storage.
 */
@Slf4j
@Singleton
public class MarketplaceManager
{
	@Inject
	private Recorder recorder;

	/**
	 * Initialize - nothing to do since Recorder handles storage
	 */
	public void initialize()
	{
		log.info("MarketplaceManager initialized (delegates to Recorder for storage)");
	}

	/**
	 * Add an offering to the marketplace
	 * UNIFIED: Delegates to Recorder
	 */
	public void addOffering(String groupId, String ownerName, LendingEntry entry)
	{
		if (groupId == null || ownerName == null || entry == null)
		{
			throw new IllegalArgumentException("Invalid offering parameters");
		}

		// Delegate to Recorder
		recorder.addAvailable(groupId, ownerName, entry);
		log.debug("MarketplaceManager.addOffering delegated to Recorder: {} offers {} in group {}",
			ownerName, entry.getItem() != null ? entry.getItem() : entry.getItemName(), groupId);
	}

	/**
	 * Remove an offering from the marketplace
	 * UNIFIED: Delegates to Recorder
	 */
	public void removeOffering(String groupId, String ownerName, String itemName, int itemId)
	{
		if (groupId == null || ownerName == null)
		{
			throw new IllegalArgumentException("Invalid removal parameters");
		}

		// Delegate to Recorder
		recorder.removeAvailable(groupId, ownerName, itemName, itemId);
		log.debug("MarketplaceManager.removeOffering delegated to Recorder: {} removed {} from group {}",
			ownerName, itemName, groupId);
	}

	/**
	 * Update an existing offering
	 * UNIFIED: Delegates to Recorder
	 */
	public void updateOffering(String groupId, String ownerName, String itemName, int itemId, LendingEntry updatedEntry)
	{
		if (groupId == null || ownerName == null || updatedEntry == null)
		{
			throw new IllegalArgumentException("Invalid update parameters");
		}

		// Delegate to Recorder
		recorder.updateAvailable(groupId, ownerName, itemName, itemId, updatedEntry);
		log.debug("MarketplaceManager.updateOffering delegated to Recorder: {} updated {} in group {}",
			ownerName, itemName, groupId);
	}

	/**
	 * Get all available offerings for a group
	 * UNIFIED: Delegates to Recorder
	 */
	public List<LendingEntry> getAvailable(String groupId)
	{
		// Delegate to Recorder
		return recorder.getAvailable(groupId);
	}

	/**
	 * Get roster (map of players to their offerings)
	 * UNIFIED: Gets data from Recorder
	 */
	public Map<String, List<LendingEntry>> getRoster(String groupId)
	{
		List<LendingEntry> allEntries = recorder.getAvailable(groupId);

		// Group by lender
		return allEntries.stream()
			.filter(e -> e.getLender() != null)
			.collect(Collectors.groupingBy(LendingEntry::getLender));
	}

	/**
	 * Get offerings by a specific owner
	 * UNIFIED: Gets from Recorder and filters by owner
	 */
	public List<LendingEntry> getOfferingsByOwner(String groupId, String ownerName)
	{
		List<LendingEntry> allEntries = recorder.getAvailableList(groupId);
		if (allEntries == null || ownerName == null)
		{
			return Collections.emptyList();
		}
		// Filter by lender name
		return allEntries.stream()
			.filter(e -> ownerName.equalsIgnoreCase(e.getLender()))
			.collect(Collectors.toList());
	}

	/**
	 * Clear all offerings for a group
	 * UNIFIED: Delegates to Recorder
	 */
	public void clearGroupOfferings(String groupId)
	{
		recorder.clearGroupData(groupId);
		log.info("MarketplaceManager.clearGroupOfferings delegated to Recorder for group: {}", groupId);
	}

	/**
	 * Clear all offerings for a specific player in a group
	 * UNIFIED: Delegates to Recorder
	 */
	public void clearPlayerOfferings(String groupId, String ownerName)
	{
		// Get all offerings for the player and remove them one by one
		List<LendingEntry> offerings = getOfferingsByOwner(groupId, ownerName);
		for (LendingEntry entry : offerings)
		{
			recorder.removeAvailable(groupId, ownerName, entry.getItem(), entry.getItemId());
		}
		log.info("Cleared all offerings for player: {} in group: {}", ownerName, groupId);
	}

	/**
	 * Remove all items for a specific lender (alias for clearPlayerOfferings)
	 * Used when a member is kicked from the group
	 */
	public void removeItemsForLender(String groupId, String lenderName)
	{
		clearPlayerOfferings(groupId, lenderName);
	}

	/**
	 * Get total count of offerings in a group
	 * UNIFIED: Gets data from Recorder
	 */
	public int getOfferingCount(String groupId)
	{
		List<LendingEntry> entries = recorder.getAvailable(groupId);
		return entries != null ? entries.size() : 0;
	}

	/**
	 * Check if a player has any offerings in a group
	 * UNIFIED: Gets data from Recorder
	 */
	public boolean hasOfferings(String groupId, String ownerName)
	{
		List<LendingEntry> offerings = getOfferingsByOwner(groupId, ownerName);
		return offerings != null && !offerings.isEmpty();
	}

	/**
	 * Load marketplace data for a group
	 * UNIFIED: Delegates to Recorder
	 */
	public void loadGroupData(String groupId)
	{
		log.debug("MarketplaceManager.loadGroupData delegating to Recorder for group: {}", groupId);
		recorder.loadGroupData(groupId);
	}
}
