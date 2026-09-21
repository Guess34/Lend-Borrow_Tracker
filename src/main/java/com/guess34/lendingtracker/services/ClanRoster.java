package com.guess34.lendingtracker.services;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.clan.ClanMember;
import net.runelite.api.clan.ClanSettings;
import net.runelite.client.util.Text;

/**
 * The player's in-game clan roster, as the game itself reports it: every member,
 * on any platform, mobile included. Read locally - nothing is fetched from
 * anywhere - which is what lets a group recognise clan members who can't run
 * the plugin.
 *
 * The game only exposes the clan the player is IN, so a member's client can only
 * vouch for the clan it belongs to; asked about any other clan it says "unknown",
 * never "no".
 */
@Singleton
public class ClanRoster
{
	/** Answer to "is this player in that clan?" */
	public enum Membership { YES, NO, UNKNOWN }

	private final Client client;

	private volatile String clanName;
	private volatile Set<String> members = Collections.emptySet();

	@Inject
	public ClanRoster(Client client)
	{
		this.client = client;
	}

	/** Re-read the roster. Client thread only. */
	public void refresh()
	{
		ClanSettings settings = client.getClanSettings();
		if (settings == null || settings.getName() == null)
		{
			clanName = null;
			members = Collections.emptySet();
			return;
		}
		Set<String> names = new HashSet<>();
		for (ClanMember m : settings.getMembers())
		{
			if (m != null && m.getName() != null)
			{
				names.add(key(m.getName()));
			}
		}
		members = Collections.unmodifiableSet(names);
		clanName = Text.removeTags(settings.getName()).replace('\u00A0', ' ').trim();
	}

	/** Has the game given us this player's clan list yet? */
	public boolean isLoaded()
	{
		return clanName != null;
	}

	/** The clan this player is in right now, or null. */
	public String currentClanName()
	{
		return clanName;
	}

	/** Is player in the named clan, as far as this client can tell? */
	public Membership check(String clan, String player)
	{
		String mine = clanName;
		if (clan == null || clan.trim().isEmpty() || player == null || mine == null
			|| !mine.equalsIgnoreCase(clan.trim()))
		{
			return Membership.UNKNOWN;
		}
		return members.contains(key(player)) ? Membership.YES : Membership.NO;
	}

	// Jagex treats spaces, underscores, hyphens and non-breaking spaces in a name
	// as the same character, and names are case-insensitive.
	private static String key(String name)
	{
		return Text.toJagexName(Text.removeTags(name)).toLowerCase();
	}
}
