package net.runelite.client.plugins.lendingtracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LendingTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LendingTrackerPlugin.class);
		RuneLite.main(args);
	}
}
