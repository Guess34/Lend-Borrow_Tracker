package com.guess34.lendingtracker.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A direct lending request between two group members:
 * either a borrow request (borrower asks a lender for an item)
 * or a lend offer (lender offers an item to someone looking for it).
 *
 * Requests are stored in the per-group data snapshot so they sync
 * across machines through the relay and reach recipients who were
 * offline when the request was made.
 */
@Data
@NoArgsConstructor
public class LendingRequest
{
	public static final String TYPE_BORROW_REQUEST = "BORROW_REQUEST";
	public static final String TYPE_LEND_OFFER = "LEND_OFFER";
	// Loan-removal governance: MUTUAL = the loan's counterparty must approve
	// (first course of action); STAFF = an uninvolved owner/co-owner adjudicates,
	// only for loans whose counterparty was never a group member (mobile/no-plugin
	// borrowers) or as escalation after a mutual request failed.
	public static final String TYPE_REMOVAL_MUTUAL = "REMOVAL_MUTUAL";
	public static final String TYPE_REMOVAL_STAFF = "REMOVAL_STAFF";

	public static final String STATUS_PENDING = "PENDING";
	public static final String STATUS_ACCEPTED = "ACCEPTED";
	public static final String STATUS_DECLINED = "DECLINED";
	public static final String STATUS_CANCELLED = "CANCELLED";

	private String id;
	private String groupId;
	private String type;         // one of the TYPE_* constants
	private String from;         // player who created the request
	private String to;           // player the request is addressed to ("" for staff review)
	private String entryId;      // the loan this request refers to (removal types)
	private String itemName;
	private int itemId;
	private int quantity;
	private int durationDays;
	private String message;
	private String status;       // STATUS_* constants
	// Cancelled because nobody ever answered it, rather than because somebody
	// decided to. A separate flag rather than a new STATUS_ value on purpose:
	// a status they don't recognise makes older clients announce "declined your
	// request", inventing a decision nobody made. An unknown FIELD they simply
	// ignore, and they read the status as a plain cancellation, which is true.
	private boolean expired;
	private long createdAt;
	private long updatedAt;

	public boolean isPending()
	{
		return STATUS_PENDING.equals(status);
	}

	public boolean isBorrowRequest()
	{
		return TYPE_BORROW_REQUEST.equals(type);
	}

	public boolean isRemoval()
	{
		return TYPE_REMOVAL_MUTUAL.equals(type) || TYPE_REMOVAL_STAFF.equals(type);
	}

	public boolean isStaffRemoval()
	{
		return TYPE_REMOVAL_STAFF.equals(type);
	}
}
