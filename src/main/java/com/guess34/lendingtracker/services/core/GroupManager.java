package com.guess34.lendingtracker.services.core;

import lombok.extern.slf4j.Slf4j;
import com.guess34.lendingtracker.model.LendingGroup;
import com.guess34.lendingtracker.services.group.GroupConfigStore;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * GroupManager - Group creation, invite code management, member management
 * Phase 1: Delegates to existing GroupConfigStore for compatibility
 */
@Slf4j
@Singleton
public class GroupManager
{
	@Inject
	private GroupConfigStore groupConfigStore;

	/**
	 * Create a new lending group (delegates to GroupConfigStore)
	 */
	public LendingGroup createGroup(String groupName, String description, String ownerName)
	{
		if (groupName == null || groupName.trim().isEmpty())
		{
			throw new IllegalArgumentException("Group name cannot be empty");
		}

		if (ownerName == null || ownerName.trim().isEmpty())
		{
			throw new IllegalArgumentException("Owner name cannot be empty");
		}

		// Delegate to GroupConfigStore which handles all the logic
		String groupId = groupConfigStore.createGroup(groupName, description, ownerName);
		LendingGroup group = groupConfigStore.getGroup(groupId);

		log.info("Created new group: {} with ID: {} for owner: {}", groupName, groupId, ownerName);
		return group;
	}

	/**
	 * Join a group using an invite code (delegates to GroupConfigStore)
	 */
	public LendingGroup joinGroupWithCode(String inviteCode, String playerName)
	{
		if (inviteCode == null || inviteCode.trim().isEmpty())
		{
			throw new IllegalArgumentException("Invite code cannot be empty");
		}

		if (playerName == null || playerName.trim().isEmpty())
		{
			throw new IllegalArgumentException("Player name cannot be empty");
		}

		// Delegate to GroupConfigStore
		boolean success = groupConfigStore.joinGroupWithCode(inviteCode, playerName);
		if (!success)
		{
			throw new IllegalArgumentException("Failed to join group with code");
		}

		// Find the group that was joined
		LendingGroup group = groupConfigStore.findGroupByCode(inviteCode);
		log.info("Player {} joined group via code: {}", playerName, inviteCode);
		return group;
	}

	/**
	 * Leave a group (delegates to GroupConfigStore)
	 */
	public void leaveGroup(String groupId, String playerName)
	{
		groupConfigStore.removeMember(groupId, playerName);
		log.info("Player {} left group: {}", playerName, groupId);
	}

	/**
	 * Delete a group (delegates to GroupConfigStore)
	 */
	public void deleteGroup(String groupId)
	{
		groupConfigStore.deleteGroup(groupId);
		log.info("Group {} deleted", groupId);
	}

	/**
	 * Regenerate single-use group code (uses existing GroupConfigStore logic)
	 */
	public String regenerateGroupCode(String groupId)
	{
		LendingGroup group = groupConfigStore.getGroup(groupId);
		if (group == null)
		{
			throw new IllegalArgumentException("Group not found");
		}

		group.regenerateGroupCode();
		// Note: GroupConfigStore doesn't expose saveGroup(), so changes are saved via internal mechanisms
		log.info("Regenerated group code for group: {}", group.getName());
		return group.getInviteCode();
	}

	/**
	 * Regenerate multi-use clan code (uses existing GroupConfigStore logic)
	 */
	public String regenerateClanCode(String groupId)
	{
		LendingGroup group = groupConfigStore.getGroup(groupId);
		if (group == null)
		{
			throw new IllegalArgumentException("Group not found");
		}

		group.regenerateClanCode();
		// Note: GroupConfigStore doesn't expose saveGroup(), so changes are saved via internal mechanisms
		log.info("Regenerated clan code for group: {}", group.getName());
		return group.getClanCode();
	}

	/**
	 * Enable/disable clan code (uses existing GroupConfigStore logic)
	 */
	public void setClanCodeEnabled(String groupId, boolean enabled)
	{
		LendingGroup group = groupConfigStore.getGroup(groupId);
		if (group == null)
		{
			throw new IllegalArgumentException("Group not found");
		}

		group.setClanCodeEnabled(enabled);
		log.info("Clan code {} for group: {}", enabled ? "enabled" : "disabled", group.getName());
	}

	/**
	 * Get a group by ID (delegates to GroupConfigStore)
	 */
	public LendingGroup getGroup(String groupId)
	{
		return groupConfigStore.getGroup(groupId);
	}

	/**
	 * Get all groups (delegates to GroupConfigStore)
	 */
	public List<LendingGroup> getAllGroups()
	{
		return new ArrayList<>(groupConfigStore.getAllGroups());
	}
}
