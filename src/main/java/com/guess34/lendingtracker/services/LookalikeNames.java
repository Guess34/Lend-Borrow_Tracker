package com.guess34.lendingtracker.services;

import java.util.Locale;
import net.runelite.client.util.Text;

/**
 * Names that look the same on screen but belong to different accounts - the
 * trick behind "let me have that back quick": borrow from Guess34, and the item
 * goes back to Guess 34. The game treats case, and a space, underscore or hyphen
 * in the same place, as the same name; anything else is a different player.
 */
public final class LookalikeNames
{
	private LookalikeNames()
	{
	}

	/** One account however it's written: any case, and _ - and space interchangeable. */
	public static String accountKey(String name)
	{
		if (name == null)
		{
			return "";
		}
		String s = Text.toJagexName(Text.removeTags(name).replace('\u00A0', ' '));
		return s.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ").trim();
	}

	public static boolean sameAccount(String a, String b)
	{
		String ka = accountKey(a);
		return !ka.isEmpty() && ka.equals(accountKey(b));
	}

	/**
	 * What a name looks like at a glance: spaces dropped, and the letters the
	 * game's font draws alike folded together - I, l, i and 1; O and 0; rn and m;
	 * vv and w.
	 */
	public static String skeleton(String name)
	{
		String s = accountKey(name).replace(" ", "");
		s = s.replace("rn", "m").replace("vv", "w");
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c == 'i' || c == '1' || c == '|' || c == '!')
			{
				b.append('l');
			}
			else if (c == '0')
			{
				b.append('o');
			}
			else
			{
				b.append(c);
			}
		}
		return b.toString();
	}

	/**
	 * A different account whose name reads the same, or nearly: identical once
	 * the lookalike letters and spaces are folded together, or - for names of
	 * five letters or more - one letter added, dropped, changed or swapped.
	 */
	public static boolean looksLike(String a, String b)
	{
		if (sameAccount(a, b))
		{
			return false;
		}
		String sa = skeleton(a);
		String sb = skeleton(b);
		if (sa.isEmpty() || sb.isEmpty())
		{
			return false;
		}
		if (sa.equals(sb))
		{
			return true;
		}
		return Math.min(sa.length(), sb.length()) >= 5 && oneEditApart(sa, sb);
	}

	/**
	 * How the two names differ, in words. The game's font draws I, l and 1 the
	 * same, so two names side by side can read as the same name twice - this says
	 * what to look for instead. Null when there's nothing short to say.
	 */
	public static String describeDifference(String theirs, String real)
	{
		String a = clean(theirs);
		String b = clean(real);
		String la = a.toLowerCase(Locale.ROOT);
		String lb = b.toLowerCase(Locale.ROOT);
		if (squash(la).equals(squash(lb)))
		{
			// Same letters; only the spaces differ.
			int i = 0;
			while (i < a.length() && i < b.length() && la.charAt(i) == lb.charAt(i))
			{
				i++;
			}
			String before = a.substring(0, i).trim();
			boolean theirsHasSpace = i < a.length() && isGap(a.charAt(i));
			if (before.isEmpty())
			{
				return "the spaces in the name are different";
			}
			return theirsHasSpace
				? "their name has a space after \"" + before + "\""
				: "their name has no space after \"" + before + "\"";
		}
		String sa = squash(a);
		String sb = squash(b);
		if (sa.length() == sb.length())
		{
			for (int i = 0; i < sa.length(); i++)
			{
				char ca = sa.charAt(i);
				char cb = sb.charAt(i);
				if (Character.toLowerCase(ca) != Character.toLowerCase(cb))
				{
					return "their name has " + charName(ca) + " where " + clean(real) + " has " + charName(cb)
						+ (i > 0 ? ", after \"" + sa.substring(0, i) + "\"" : "");
				}
			}
		}
		return null;
	}

	private static String clean(String name)
	{
		return name == null ? "" : Text.removeTags(name).replace('\u00A0', ' ').trim();
	}

	private static boolean isGap(char c)
	{
		return c == ' ' || c == '_' || c == '-';
	}

	private static String squash(String s)
	{
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			if (!isGap(s.charAt(i)))
			{
				b.append(s.charAt(i));
			}
		}
		return b.toString();
	}

	/** A character named so it can't be mistaken for its lookalikes. */
	private static String charName(char c)
	{
		switch (c)
		{
			case 'I':
				return "a capital i";
			case 'l':
				return "a small L";
			case 'i':
				return "a small i";
			case 'L':
				return "a capital L";
			case '1':
				return "the number 1";
			case '0':
				return "the number 0";
			case 'O':
				return "a capital O";
			case 'o':
				return "a small o";
			default:
				if (Character.isUpperCase(c))
				{
					return "a capital " + c;
				}
				if (Character.isLowerCase(c))
				{
					return "a small " + c;
				}
				return "\"" + c + "\"";
		}
	}

	/** One letter added, dropped or changed, or two neighbours swapped. */
	static boolean oneEditApart(String a, String b)
	{
		int la = a.length();
		int lb = b.length();
		if (Math.abs(la - lb) > 1)
		{
			return false;
		}
		if (la == lb)
		{
			int first = -1;
			int second = -1;
			int diffs = 0;
			for (int i = 0; i < la; i++)
			{
				if (a.charAt(i) != b.charAt(i))
				{
					diffs++;
					if (first < 0)
					{
						first = i;
					}
					else
					{
						second = i;
					}
				}
			}
			if (diffs == 1)
			{
				return true;
			}
			return diffs == 2 && second == first + 1
				&& a.charAt(first) == b.charAt(second) && a.charAt(second) == b.charAt(first);
		}
		String shorter = la < lb ? a : b;
		String longer = la < lb ? b : a;
		int i = 0;
		int j = 0;
		boolean skipped = false;
		while (i < shorter.length() && j < longer.length())
		{
			if (shorter.charAt(i) == longer.charAt(j))
			{
				i++;
				j++;
			}
			else
			{
				if (skipped)
				{
					return false;
				}
				skipped = true;
				j++;
			}
		}
		return true;
	}
}
