package com.guess34.lendingtracker.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;

import com.guess34.lendingtracker.model.GroupMember;
import com.guess34.lendingtracker.model.LendingEntry;
import com.guess34.lendingtracker.model.LendingGroup;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Color;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import net.runelite.client.ui.ColorScheme;

/**
 * Unified group service that handles:
 * 1. Account-specific group storage with JSON persistence (from GroupConfigStore)
 * 2. Group creation, invite code management, member management (from GroupManager)
 * 3. Real-time synchronization of group data across members (from GroupSyncService)
 *
 * IMPORTANT: Group data is stored PER-ACCOUNT to prevent bleeding between accounts.
 * Each account has its own list of groups they are a member of.
 *
 * Storage keys use format: "lendingtracker.{accountName}.groups"
 */
@Slf4j
@Singleton
public class GroupService
{
	// --- Config Key Constants ---
	private static final String CFG_GROUP = "lendingtracker";
	private static final String CFG_KEY_GROUPS_SUFFIX = ".groups";
	private static final String CFG_KEY_ACTIVE_SUFFIX = ".activeGroupId";

	// --- Sync Constants ---
	private static final String SYNC_KEY_PREFIX = "sync.";
	private static final String SYNC_EVENTS_SUFFIX = ".events";
	private static final int MAX_SYNC_EVENTS = 100;
	private static final long SYNC_INTERVAL_MS = 5000;

	// --- Injected Dependencies ---
	@Inject private ConfigManager configManager;
	@Inject private Client client;
	@Inject private Gson gson;
	@Inject private DataService dataService;

	// --- Group State ---
	private final Map<String, LendingGroup> groups = new LinkedHashMap<>();
	private String activeGroupId;
	private String currentAccountName = null;

	// --- Sync State ---
	private ScheduledExecutorService syncExecutor;
	private String currentSyncGroupId;
	private String currentSyncPlayerName;
	private long lastSyncTimestamp = 0;
	private Runnable onSyncCallback;

	// --- Initialization & Account Lifecycle ---

	public void initialize()
	{
		groups.clear();
		activeGroupId = null;
		currentAccountName = null;
	}

	public void onAccountLogin(String accountName)
	{
		if (accountName == null || accountName.isEmpty())
		{
			log.warn("onAccountLogin called with null/empty accountName");
			return;
		}

		String normalizedName = accountName.toLowerCase().replace(" ", "_");

		if (normalizedName.equals(currentAccountName) && !groups.isEmpty())
		{
			return;
		}

		currentAccountName = normalizedName;
		groups.clear();
		activeGroupId = null;

		loadGroups();
		loadActiveGroup();
	}

	public boolean isLoggedIn()
	{
		try
		{
			if (client == null || client.getGameState() != GameState.LOGGED_IN)
			{
				return false;
			}
			if (client.getLocalPlayer() != null)
			{
				String name = client.getLocalPlayer().getName();
				return name != null && !name.isEmpty();
			}
		}
		catch (Exception e)
		{
			log.debug("Error checking login status", e);
		}
		return false;
	}

	public boolean hasCurrentAccount()
	{
		return currentAccountName != null && !currentAccountName.isEmpty();
	}

	// --- Active Group Management ---

	public LendingGroup getActiveGroup()
	{
		if (!isLoggedIn() || activeGroupId == null)
		{
			return null;
		}
		return groups.get(activeGroupId);
	}

	public LendingGroup getActiveGroupUnchecked()
	{
		return activeGroupId != null ? groups.get(activeGroupId) : null;
	}

	public String getCurrentGroupId()
	{
		return isLoggedIn() ? activeGroupId : null;
	}

	public String getCurrentGroupIdUnchecked()
	{
		return activeGroupId;
	}

	public void setCurrentGroupId(String id)
	{
		if (id != null && groups.containsKey(id))
		{
			activeGroupId = id;
			saveActiveGroup();
		}
	}

	// --- Group CRUD ---

	/**
	 * Create a new lending group with input validation.
	 * @return The group ID if successful, null if name is taken or inputs invalid
	 */
	public String createGroup(String name, String description, String ownerName)
	{
		if (name == null || name.trim().isEmpty())
		{
			throw new IllegalArgumentException("Group name cannot be empty");
		}
		if (ownerName == null || ownerName.trim().isEmpty())
		{
			throw new IllegalArgumentException("Owner name cannot be empty");
		}

		ensureCurrentAccount();

		if (isGroupNameTaken(name))
		{
			return null;
		}

		String id = UUID.randomUUID().toString().substring(0, 8);
		LendingGroup g = new LendingGroup(id, name, description);

		GroupMember owner = new GroupMember(ownerName, "owner");
		g.addMember(owner);

		groups.put(id, g);
		activeGroupId = id;
		saveActiveGroup();
		saveGroups();
		return id;
	}

	public void deleteGroup(String id)
	{
		groups.remove(id);
		if (Objects.equals(activeGroupId, id))
		{
			activeGroupId = groups.isEmpty() ? null : groups.keySet().iterator().next();
			saveActiveGroup();
		}
		saveGroups();
	}

	public LendingGroup getGroup(String id)
	{
		return groups.get(id);
	}

	public Collection<LendingGroup> getAllGroups()
	{
		return Collections.unmodifiableCollection(groups.values());
	}

	public String getGroupNameById(String id)
	{
		LendingGroup group = groups.get(id);
		return group != null ? group.getName() : null;
	}

	private LendingGroup getGroupByName(String name)
	{
		if (name == null || name.isEmpty())
		{
			return null;
		}
		return groups.values().stream()
			.filter(g -> name.equals(g.getName()))
			.findFirst()
			.orElse(null);
	}

	/** @return "success", "not_member", or "not_found" */
	public String switchToGroup(String groupName, String playerName)
	{
		LendingGroup group = getGroupByName(groupName);
		if (group == null)
		{
			return "not_found";
		}

		if (group.getMembers() != null)
		{
			boolean isMember = group.getMembers().stream()
				.anyMatch(m -> m.getName().equalsIgnoreCase(playerName));
			if (isMember)
			{
				setCurrentGroupId(group.getId());
				return "success";
			}
		}

		return "not_member";
	}

	// --- Members & Roles ---

	public void addMember(String groupId, String name, String role)
	{
		LendingGroup g = groups.get(groupId);
		if (g == null) return;
		if (g.getMembers() == null) g.setMembers(new ArrayList<>());
		boolean exists = g.getMembers().stream().anyMatch(m -> m.getName().equalsIgnoreCase(name));
		if (!exists)
		{
			g.getMembers().add(new GroupMember(name, role));
			saveGroups();
			publishEvent(SyncEventType.MEMBER_JOINED, groupId + ":" + name, null);
		}
	}

	public void removeMember(String groupId, String name)
	{
		LendingGroup g = groups.get(groupId);
		if (g == null || g.getMembers() == null) return;
		g.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(name));
		saveGroups();
		publishEvent(SyncEventType.MEMBER_LEFT, groupId + ":" + name, null);
	}

	public boolean removeMemberFromGroup(String groupId, String requesterName, String targetName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		if (!canKick(groupId, requesterName, targetName))
		{
			log.warn("{} doesn't have permission to kick {}", requesterName, targetName);
			return false;
		}

		boolean removed = group.getMembers().removeIf(m -> m.getName().equalsIgnoreCase(targetName));
		if (removed)
		{
			saveGroups();
			publishEvent(SyncEventType.MEMBER_LEFT, groupId + ":" + targetName, null);
		}
		return removed;
	}

	public boolean setMemberRole(String groupId, String requesterName, String targetName, String newRole)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		if (!canChangeRole(groupId, requesterName, targetName)) return false;
		if ("owner".equalsIgnoreCase(newRole)) return false;
		if ("co-owner".equalsIgnoreCase(newRole) && !isOwner(groupId, requesterName)) return false;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(targetName))
			{
				member.setRole(newRole.toLowerCase());
				saveGroups();
				publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
				return true;
			}
		}

		return false;
	}

	public boolean transferOwnership(String groupId, String currentOwnerName, String newOwnerName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;
		if (!isOwner(groupId, currentOwnerName)) return false;
		if (currentOwnerName.equalsIgnoreCase(newOwnerName)) return false;

		GroupMember currentOwnerMember = null;
		GroupMember newOwnerMember = null;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(currentOwnerName)) currentOwnerMember = member;
			if (member.getName().equalsIgnoreCase(newOwnerName)) newOwnerMember = member;
		}

		if (currentOwnerMember == null || newOwnerMember == null) return false;

		newOwnerMember.setRole("owner");
		currentOwnerMember.setRole("co-owner");

		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	public String getMemberRole(String groupId, String playerName)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return null;

		for (GroupMember member : group.getMembers())
		{
			if (member.getName().equalsIgnoreCase(playerName))
			{
				return member.getRole();
			}
		}
		return null;
	}

	// --- Role Hierarchy & Permissions ---

	public static String[] getAvailableRoles()
	{
		return new String[] {"co-owner", "admin", "mod", "member"};
	}

	public static int getRoleRank(String role)
	{
		if (role == null) return 1;
		switch (role.toLowerCase())
		{
			case "owner": return 5;
			case "co-owner": return 4;
			case "admin": return 3;
			case "mod": return 2;
			default: return 1;
		}
	}

	public static String formatRoleName(String role)
	{
		if (role == null || role.isEmpty()) return "Member";
		return Arrays.stream(role.split("-"))
			.map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
			.collect(Collectors.joining("-"));
	}

	public static Color getRoleBackgroundColor(String role)
	{
		switch (role.toLowerCase())
		{
			case "owner": return new Color(255, 215, 0);
			case "co-owner": return new Color(192, 192, 192);
			case "admin": return ColorScheme.BRAND_ORANGE;
			case "mod": return new Color(100, 149, 237);
			default: return ColorScheme.MEDIUM_GRAY_COLOR;
		}
	}

	public static Color getRoleForegroundColor(String role)
	{
		switch (role.toLowerCase())
		{
			case "owner": case "co-owner": return Color.BLACK;
			default: return Color.WHITE;
		}
	}

	public boolean isOwner(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "owner");
	}

	public boolean isAdmin(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "owner") ||
			hasRole(groupId, playerName, "admin") ||
			hasRole(groupId, playerName, "moderator");
	}

	public boolean isCoOwner(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "co-owner");
	}

	public boolean isMod(String groupId, String playerName)
	{
		return hasRole(groupId, playerName, "mod");
	}

	public boolean canKick(String groupId, String kickerName, String targetName)
	{
		if (groupId == null || kickerName == null || targetName == null) return false;
		if (kickerName.equalsIgnoreCase(targetName)) return false;

		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		String kickerRole = getMemberRole(groupId, kickerName);
		String targetRole = getMemberRole(groupId, targetName);
		if (kickerRole == null || targetRole == null) return false;

		int kickerRank = getRoleRank(kickerRole);
		int targetRank = getRoleRank(targetRole);

		if (kickerRank < 2 || kickerRank <= targetRank) return false;
		if (kickerRank == 5) return true;

		switch (kickerRole.toLowerCase())
		{
			case "co-owner": return group.isCoOwnerCanKick();
			case "admin": return group.isAdminCanKick();
			case "mod": return group.isModCanKick();
			default: return false;
		}
	}

	public boolean setKickPermission(String groupId, String requesterName, String role, boolean value)
	{
		return setPermission(groupId, requesterName, role, value, "kick");
	}

	public boolean setInvitePermission(String groupId, String requesterName, String role, boolean value)
	{
		return setPermission(groupId, requesterName, role, value, "invite");
	}

	private boolean setPermission(String groupId, String requesterName, String role, boolean value, String permType)
	{
		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		String requesterRole = getMemberRole(groupId, requesterName);
		if (requesterRole == null || getRoleRank(requesterRole) < 4) return false;

		boolean isKick = "kick".equals(permType);
		switch (role.toLowerCase())
		{
			case "co-owner":
				if (getRoleRank(requesterRole) < 5) return false;
				if (isKick) group.setCoOwnerCanKick(value); else group.setCoOwnerCanInvite(value);
				break;
			case "admin":
				if (isKick) group.setAdminCanKick(value); else group.setAdminCanInvite(value);
				break;
			case "mod":
				if (isKick) group.setModCanKick(value); else group.setModCanInvite(value);
				break;
			default:
				return false;
		}
		saveGroups();
		publishEvent(SyncEventType.SETTINGS_CHANGED, groupId, null);
		return true;
	}

	public boolean canGenerateInviteCode(String groupId, String playerName)
	{
		if (groupId == null || playerName == null) return false;

		LendingGroup group = groups.get(groupId);
		if (group == null) return false;

		String role = getMemberRole(groupId, playerName);
		if (role == null) return false;

		int rank = getRoleRank(role);
		if (rank == 5) return true;

		switch (role.toLowerCase())
		{
			case "co-owner": return group.isCoOwnerCanInvite();
			case "admin": return group.isAdminCanInvite();
			case "mod": return group.isModCanInvite();
			default: return false;
		}
	}

	public boolean canChangeRole(String groupId, String changerName, String targetName)
	{
		if (groupId == null || changerName == null || targetName == null) return false;
		if (changerName.equalsIgnoreCase(targetName)) return false;

		String changerRole = getMemberRole(groupId, changerName);
		String targetRole = getMemberRole(groupId, targetName);
		if (changerRole == null) return false;

		int changerRank = getRoleRank(changerRole);
		int targetRank = getRoleRank(targetRole);

		if (changerRank < 4) return false;
		if (changerRank == 5) return true;
		return targetRank < 4;
	}

	// --- Invite Codes ---

	/** @return The group ID if successful, null if code invalid/expired/already used */
	public String useInviteCode(String code, String playerName)
	{
		if (code == null || code.trim().isEmpty() || playerName == null || playerName.trim().isEmpty())
		{
			return null;
		}

		String trimmedCode = code.trim();

		for (LendingGroup group : groups.values())
		{
			if (group.hasActiveInviteCode() && trimmedCode.equals(group.getInviteCode()))
			{
				if (!group.hasMember(playerName))
				{
					group.addMember(new GroupMember(playerName, "member"));
				}

				group.markGroupCodeUsed(playerName);
				setCurrentGroupId(group.getId());
				saveGroups();
				return group.getId();
			}

			if (group.isClanCodeEnabled() && trimmedCode.equals(group.getClanCode()))
			{
				if (!group.hasMember(playerName))
				{
					group.addMember(new GroupMember(playerName, "member"));
				}

				group.setClanCodeUseCount(group.getClanCodeUseCount() + 1);
				setCurrentGroupId(group.getId());
				saveGroups();
				return group.getId();
			}
		}

		return null;
	}

	public String generateSingleUseInviteCode(String groupId)
	{
		LendingGroup group = groups.get(groupId);
		if (group != null)
		{
			String code = group.generateSingleUseCode();
			saveGroups();
			return code;
		}
		return null;
	}

	// --- Real-Time Sync ---

	public void startSync(String groupId, String playerName)
	{
		if (groupId == null || playerName == null)
		{
			return;
		}

		stopSync();

		this.currentSyncGroupId = groupId;
		this.currentSyncPlayerName = playerName;
		this.lastSyncTimestamp = System.currentTimeMillis();

		syncExecutor = Executors.newSingleThreadScheduledExecutor();
		syncExecutor.scheduleAtFixedRate(this::pollForUpdates, SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public void stopSync()
	{
		if (syncExecutor != null && !syncExecutor.isShutdown())
		{
			syncExecutor.shutdown();
			try
			{
				if (!syncExecutor.awaitTermination(1, TimeUnit.SECONDS))
				{
					syncExecutor.shutdownNow();
				}
			}
			catch (InterruptedException e)
			{
				syncExecutor.shutdownNow();
			}
		}
		currentSyncGroupId = null;
		currentSyncPlayerName = null;
	}

	public void setOnSyncCallback(Runnable callback)
	{
		this.onSyncCallback = callback;
	}

	public void publishEvent(SyncEventType type, String dataId, Object data)
	{
		if (currentSyncGroupId == null || currentSyncPlayerName == null)
		{
			return;
		}

		SyncEvent event = new SyncEvent();
		event.setType(type);
		event.setTimestamp(System.currentTimeMillis());
		event.setPublisher(currentSyncPlayerName);

		addEventToQueue(event);
	}

	public void syncLending(String groupId, LendingEntry entry)
	{
		if (entry == null) return;
		String previousGroupId = currentSyncGroupId;
		currentSyncGroupId = groupId;
		publishEvent(SyncEventType.ITEM_ADDED, entry.getId(), entry);
		currentSyncGroupId = previousGroupId;
	}

	public void syncAllEntries(String groupId, List<LendingEntry> entries)
	{
		if (entries == null || entries.isEmpty()) return;
		String previousGroupId = currentSyncGroupId;
		currentSyncGroupId = groupId;
		for (LendingEntry entry : entries)
		{
			publishEvent(SyncEventType.ITEM_UPDATED, entry.getId(), entry);
		}
		currentSyncGroupId = previousGroupId;
	}

	// --- Sync Queue Management ---

	private void addEventToQueue(SyncEvent event)
	{
		String key = SYNC_KEY_PREFIX + currentSyncGroupId + SYNC_EVENTS_SUFFIX;
		List<SyncEvent> events = loadEventsFromQueue();
		events.add(event);

		while (events.size() > MAX_SYNC_EVENTS)
		{
			events.remove(0);
		}

		configManager.setConfiguration(CFG_GROUP, key, gson.toJson(events));
	}

	private List<SyncEvent> loadEventsFromQueue()
	{
		if (currentSyncGroupId == null)
		{
			return new ArrayList<>();
		}

		String key = SYNC_KEY_PREFIX + currentSyncGroupId + SYNC_EVENTS_SUFFIX;
		String json = configManager.getConfiguration(CFG_GROUP, key);

		if (json != null && !json.isEmpty())
		{
			try
			{
				Type type = new TypeToken<List<SyncEvent>>(){}.getType();
				List<SyncEvent> events = gson.fromJson(json, type);
				return events != null ? new ArrayList<>(events) : new ArrayList<>();
			}
			catch (Exception e)
			{
				log.error("Failed to load sync events: {}", e.getMessage());
			}
		}

		return new ArrayList<>();
	}

	private void pollForUpdates()
	{
		if (currentSyncGroupId == null) return;

		try
		{
			List<SyncEvent> events = loadEventsFromQueue();
			List<SyncEvent> newEvents = new ArrayList<>();

			for (SyncEvent event : events)
			{
				if (event.getTimestamp() > lastSyncTimestamp &&
					!currentSyncPlayerName.equalsIgnoreCase(event.getPublisher()))
				{
					newEvents.add(event);
				}
			}

			if (!newEvents.isEmpty())
			{
				for (SyncEvent event : newEvents)
				{
					processEvent(event);
				}
				lastSyncTimestamp = System.currentTimeMillis();
				if (onSyncCallback != null)
				{
					onSyncCallback.run();
				}
			}
		}
		catch (Exception e)
		{
			log.error("Error polling for sync updates: {}", e.getMessage());
		}
	}

	private void processEvent(SyncEvent event)
	{
		try
		{
			switch (event.getType())
			{
				case ITEM_ADDED:
				case ITEM_REMOVED:
				case ITEM_UPDATED:
				case ITEM_RETURNED:
				case ITEM_SET_DELETED:
					if (currentSyncGroupId != null)
					{
						dataService.loadGroupData(currentSyncGroupId);
					}
					break;
				case MEMBER_JOINED:
				case MEMBER_LEFT:
				case SETTINGS_CHANGED:
					break;
				default:
					log.warn("Unknown sync event type: {}", event.getType());
			}
		}
		catch (Exception e)
		{
			log.error("Error processing sync event {}: {}", event.getType(), e.getMessage());
		}
	}

	// --- Persistence (Account-Specific) ---

	private String getGroupsKey()
	{
		return (currentAccountName != null && !currentAccountName.isEmpty())
			? currentAccountName + CFG_KEY_GROUPS_SUFFIX
			: null;
	}

	private String getActiveGroupKey()
	{
		return (currentAccountName != null && !currentAccountName.isEmpty())
			? currentAccountName + CFG_KEY_ACTIVE_SUFFIX
			: null;
	}

	private void loadGroups()
	{
		String key = getGroupsKey();
		if (key == null) return;

		String json = configManager.getConfiguration(CFG_GROUP, key);
		if (json != null && !json.isEmpty())
		{
			try
			{
				Type type = new TypeToken<List<LendingGroup>>(){}.getType();
				List<LendingGroup> list = gson.fromJson(json, type);
				groups.clear();
				if (list != null)
				{
					for (LendingGroup g : list)
					{
						groups.put(g.getId(), g);
					}
				}

			}
			catch (Exception e)
			{
				log.error("Failed to load groups from {}", key, e);
			}
		}
	}

	private void saveGroups()
	{
		ensureCurrentAccount();

		String key = getGroupsKey();
		if (key == null) return;

		String json = gson.toJson(new ArrayList<>(groups.values()));
		configManager.setConfiguration(CFG_GROUP, key, json);
	}

	private void loadActiveGroup()
	{
		String key = getActiveGroupKey();
		if (key == null) return;

		activeGroupId = configManager.getConfiguration(CFG_GROUP, key);
		if (activeGroupId != null && !groups.containsKey(activeGroupId))
		{
			activeGroupId = null;
		}
	}

	private void saveActiveGroup()
	{
		String key = getActiveGroupKey();
		if (key == null) return;
		configManager.setConfiguration(CFG_GROUP, key, activeGroupId);
	}

	private void ensureCurrentAccount()
	{
		if (currentAccountName == null && isLoggedIn())
		{
			try
			{
				String playerName = client.getLocalPlayer().getName();
				if (playerName != null && !playerName.isEmpty())
				{
					currentAccountName = playerName.toLowerCase().replace(" ", "_");
					loadGroups();
					loadActiveGroup();
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to auto-set currentAccountName", e);
			}
		}
	}

	// --- Helpers ---

	private boolean hasRole(String groupId, String playerName, String role)
	{
		if (groupId == null || playerName == null) return false;
		LendingGroup g = groups.get(groupId);
		if (g == null || g.getMembers() == null) return false;
		return g.getMembers().stream()
			.anyMatch(m -> m.getName().equalsIgnoreCase(playerName) &&
				role.equalsIgnoreCase(m.getRole()));
	}

	private boolean isGroupNameTaken(String name)
	{
		if (name == null || name.trim().isEmpty()) return false;
		String lowerName = name.trim().toLowerCase();
		return groups.values().stream()
			.anyMatch(g -> g.getName().toLowerCase().equals(lowerName));
	}

	// --- Sync Event Types and Data Class ---

	public enum SyncEventType
	{
		ITEM_ADDED,
		ITEM_REMOVED,
		ITEM_UPDATED,
		ITEM_RETURNED,
		MEMBER_JOINED,
		MEMBER_LEFT,
		SETTINGS_CHANGED,
		ITEM_SET_DELETED
	}

	public static class SyncEvent
	{
		private SyncEventType type;
		private long timestamp;
		private String publisher;

		public SyncEventType getType() { return type; }
		public void setType(SyncEventType type) { this.type = type; }
		public long getTimestamp() { return timestamp; }
		public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
		public String getPublisher() { return publisher; }
		public void setPublisher(String publisher) { this.publisher = publisher; }
	}
}
