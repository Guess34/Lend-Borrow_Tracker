package com.guess34.lendingtracker.ui;

import com.guess34.lendingtracker.services.TradeLoanTracker;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A flashing warning across the trade window when the other player's name only
 * LOOKS like someone this player knows. Drawn only - it changes nothing in the
 * game, and is gone the moment the trade closes.
 */
public class TradeScamOverlay extends Overlay
{
	// See-through, so the items offered under the banner still show.
	private static final Color BRIGHT = new Color(170, 0, 0, 110);
	private static final Color DIM = new Color(70, 0, 0, 110);
	// The names line is drawn in a monospaced font, where I, l and 1 look
	// different - in the game's own font they are the same mark.
	private static final java.awt.Font NAMES_FONT = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 14);

	private final Client client;
	private final TradeLoanTracker tradeLoanTracker;

	@Inject
	public TradeScamOverlay(Client client, TradeLoanTracker tradeLoanTracker)
	{
		this.client = client;
		this.tradeLoanTracker = tradeLoanTracker;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		TradeLoanTracker.ScamWarning warning = tradeLoanTracker.currentScamWarning();
		if (warning == null)
		{
			return null;
		}
		Widget window = shown(client.getWidget(InterfaceID.Tradeconfirm.UNIVERSE));
		if (window == null)
		{
			window = shown(client.getWidget(InterfaceID.Trademain.UNIVERSE));
		}
		Rectangle r = window != null ? window.getBounds() : null;
		if (r == null || r.width <= 0 || r.height <= 0)
		{
			return null;
		}

		boolean bright = (System.currentTimeMillis() / 400) % 2 == 0;

		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(4f));
		g.setColor(bright ? Color.RED : Color.YELLOW);
		g.drawRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4);
		g.setStroke(old);

		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add(warning.loanParty ? "POSSIBLE SCAM - CHECK THE NAME" : "CHECK THE NAME");
		lines.add("'" + warning.partnerName + "' is NOT '" + warning.realName + "'");
		if (warning.difference != null)
		{
			lines.add(Character.toUpperCase(warning.difference.charAt(0)) + warning.difference.substring(1));
		}
		lines.add(warning.detail);

		java.awt.Font gameFont = FontManager.getRunescapeBoldFont();
		int lineHeight = Math.max(g.getFontMetrics(gameFont).getHeight(), g.getFontMetrics(NAMES_FONT).getHeight());
		// Just under the window's own title, so the name it shows stays visible.
		int top = r.y + 26;
		g.setColor(bright ? BRIGHT : DIM);
		g.fillRect(r.x + 6, top, r.width - 12, lineHeight * lines.size() + 10);
		for (int i = 0; i < lines.size(); i++)
		{
			String text = lines.get(i);
			g.setFont(i == 1 ? NAMES_FONT : gameFont);
			FontMetrics fm = g.getFontMetrics();
			int x = r.x + (r.width - fm.stringWidth(text)) / 2;
			int y = top + 5 + i * lineHeight + fm.getAscent();
			g.setColor(Color.BLACK);
			g.drawString(text, x + 1, y + 1);
			g.setColor(i == 0 ? (bright ? Color.YELLOW : Color.WHITE) : Color.WHITE);
			g.drawString(text, x, y);
		}
		return null;
	}

	private static Widget shown(Widget w)
	{
		return w != null && !w.isHidden() ? w : null;
	}
}
