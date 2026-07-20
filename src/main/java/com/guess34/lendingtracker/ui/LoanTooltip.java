package com.guess34.lendingtracker.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.JComponent;
import net.runelite.client.util.QuantityFormatter;

import com.guess34.lendingtracker.model.LendingEntry;

/**
 * LoanTooltip - shared hover tooltip for loan cards (Active Loans and History),
 * showing the full deal at a glance: borrower, dates, collateral, notes.
 */
final class LoanTooltip
{
	private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm");

	private LoanTooltip()
	{
	}

	/** Apply the loan tooltip to every given component (tooltips don't inherit). */
	static void apply(LendingEntry loan, JComponent... components)
	{
		String html = html(loan);
		for (JComponent c : components)
		{
			if (c != null)
			{
				c.setToolTipText(html);
			}
		}
	}

	static String html(LendingEntry loan)
	{
		StringBuilder sb = new StringBuilder("<html><b>")
			.append(escape(loan.getItem()));
		if (loan.getQuantity() > 1)
		{
			sb.append(" x").append(loan.getQuantity());
		}
		sb.append("</b> — ").append(QuantityFormatter.quantityToStackSize(loan.getValue())).append(" GP");

		sb.append("<br>Borrower: <b>").append(escape(loan.getBorrower())).append("</b>");
		if (loan.getLender() != null && !loan.getLender().isEmpty())
		{
			sb.append("<br>Lender: ").append(escape(loan.getLender()));
		}

		if (loan.getLendDate() > 0)
		{
			sb.append("<br>Lent: ").append(formatDate(loan.getLendDate()));
		}
		sb.append("<br>Due: ").append(loan.getDueTime() <= 0 || loan.getDueTime() == Long.MAX_VALUE
			? "no due date" : formatDate(loan.getDueTime()));
		if (loan.getReturnedAt() > 0)
		{
			sb.append("<br>Returned: ").append(formatDate(loan.getReturnedAt()));
		}

		sb.append("<br>Collateral: ").append(escape(collateralText(loan)));

		if (loan.getNotes() != null && !loan.getNotes().isEmpty())
		{
			sb.append("<br>Notes: ").append(escape(loan.getNotes()));
		}
		return sb.append("</html>").toString();
	}

	private static String collateralText(LendingEntry loan)
	{
		if (loan.getCollateralValue() != null && loan.getCollateralValue() > 0)
		{
			return QuantityFormatter.quantityToStackSize(loan.getCollateralValue()) + " GP";
		}
		if (loan.getCollateralItems() != null && !loan.getCollateralItems().isEmpty())
		{
			return loan.getCollateralItems();
		}
		if (loan.isAgreedNoCollateral())
		{
			return "None (agreed)";
		}
		return "None";
	}

	private static String formatDate(long epochMillis)
	{
		return DATE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
	}

	private static String escape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
