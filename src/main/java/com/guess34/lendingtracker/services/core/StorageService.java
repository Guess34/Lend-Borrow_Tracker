package com.guess34.lendingtracker.services.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import com.guess34.lendingtracker.model.LendingEntry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StorageService - Pure data persistence layer
 * CRITICAL: Uses EXACT SAME config keys as old Recorder.java for backward compatibility
 */
@Slf4j
@Singleton
public class StorageService
{
	private static final String CONFIG_GROUP = "lendingtracker";
	// CRITICAL: These keys MUST match the old Recorder.java exactly
	private static final String CONFIG_KEY_RECORDER = "lendingtracker.recorder."; // + groupId
	private static final String CONFIG_KEY_ENTRIES = "lendingtracker.entries";
	private static final String CONFIG_KEY_HISTORY = "lendingtracker.history";

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	/**
	 * Save group-specific data (lent, borrowed, available)
	 * Uses EXACT SAME format as old Recorder
	 */
	public void saveGroupData(String groupId,
		Map<String, List<LendingEntry>> groupLent,
		Map<String, List<LendingEntry>> groupBorrowed,
		Map<String, List<LendingEntry>> groupAvailable)
	{
		try
		{
			Map<String, Object> groupData = new HashMap<>();
			groupData.put("lent", groupLent);
			groupData.put("borrowed", groupBorrowed);
			groupData.put("available", groupAvailable);

			String json = gson.toJson(groupData);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_RECORDER + groupId, json);
			log.debug("Saved group data for groupId: {}", groupId);
		}
		catch (Exception e)
		{
			log.error("Failed to save group data for groupId: {}", groupId, e);
		}
	}

	/**
	 * Load group-specific data
	 * Returns Map with keys: "lent", "borrowed", "available"
	 * Each value is Map<String, List<LendingEntry>>
	 */
	public Map<String, Map<String, List<LendingEntry>>> loadGroupData(String groupId)
	{
		Map<String, Map<String, List<LendingEntry>>> result = new HashMap<>();

		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_RECORDER + groupId);
			if (json != null && !json.isEmpty())
			{
				Type type = new TypeToken<Map<String, Map<String, List<LendingEntry>>>>()
				{
				}.getType();
				Map<String, Map<String, List<LendingEntry>>> groupData = gson.fromJson(json, type);

				if (groupData != null)
				{
					result.put("lent", groupData.getOrDefault("lent", new ConcurrentHashMap<>()));
					result.put("borrowed", groupData.getOrDefault("borrowed", new ConcurrentHashMap<>()));
					result.put("available", groupData.getOrDefault("available", new ConcurrentHashMap<>()));
				}
			}

			log.debug("Loaded group data for groupId: {}", groupId);
		}
		catch (Exception e)
		{
			log.error("Failed to load group data for groupId: {}", groupId, e);
		}

		// Return empty maps if nothing loaded
		if (result.isEmpty())
		{
			result.put("lent", new ConcurrentHashMap<>());
			result.put("borrowed", new ConcurrentHashMap<>());
			result.put("available", new ConcurrentHashMap<>());
		}

		return result;
	}

	/**
	 * Save all entries (active loans)
	 * Uses EXACT SAME format as old Recorder
	 */
	public void saveEntries(Map<String, LendingEntry> allEntries)
	{
		try
		{
			String json = gson.toJson(allEntries);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_ENTRIES, json);
			log.debug("Saved {} entries", allEntries.size());
		}
		catch (Exception e)
		{
			log.error("Failed to save entries", e);
		}
	}

	/**
	 * Load all entries (active loans)
	 */
	public Map<String, LendingEntry> loadEntries()
	{
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_ENTRIES);
			if (json != null && !json.isEmpty())
			{
				Type type = new TypeToken<Map<String, LendingEntry>>()
				{
				}.getType();
				Map<String, LendingEntry> entries = gson.fromJson(json, type);
				log.debug("Loaded {} entries", entries != null ? entries.size() : 0);
				return entries != null ? new ConcurrentHashMap<>(entries) : new ConcurrentHashMap<>();
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load entries", e);
		}

		return new ConcurrentHashMap<>();
	}

	/**
	 * Save history (returned/completed loans)
	 * Uses EXACT SAME format as old Recorder
	 */
	public void saveHistory(List<LendingEntry> historyEntries)
	{
		try
		{
			String json = gson.toJson(historyEntries);
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY_HISTORY, json);
			log.debug("Saved {} history entries", historyEntries.size());
		}
		catch (Exception e)
		{
			log.error("Failed to save history", e);
		}
	}

	/**
	 * Load history (returned/completed loans)
	 */
	public List<LendingEntry> loadHistory()
	{
		try
		{
			String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY_HISTORY);
			if (json != null && !json.isEmpty())
			{
				Type type = new TypeToken<List<LendingEntry>>()
				{
				}.getType();
				List<LendingEntry> history = gson.fromJson(json, type);
				log.debug("Loaded {} history entries", history != null ? history.size() : 0);
				return history != null ? Collections.synchronizedList(new ArrayList<>(history)) : Collections.synchronizedList(new ArrayList<>());
			}
		}
		catch (Exception e)
		{
			log.error("Failed to load history", e);
		}

		return Collections.synchronizedList(new ArrayList<>());
	}

	/**
	 * Delete all data for a specific group
	 */
	public void deleteGroupData(String groupId)
	{
		try
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_RECORDER + groupId);
			log.info("Deleted group data for groupId: {}", groupId);
		}
		catch (Exception e)
		{
			log.error("Failed to delete group data for groupId: {}", groupId, e);
		}
	}

	/**
	 * Clear all entries
	 */
	public void clearEntries()
	{
		try
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_ENTRIES);
			log.info("Cleared all entries");
		}
		catch (Exception e)
		{
			log.error("Failed to clear entries", e);
		}
	}

	/**
	 * Clear all history
	 */
	public void clearHistory()
	{
		try
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY_HISTORY);
			log.info("Cleared all history");
		}
		catch (Exception e)
		{
			log.error("Failed to clear history", e);
		}
	}
}
