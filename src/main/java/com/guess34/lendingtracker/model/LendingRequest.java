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
	// A "Looking For" post: addressed to nobody (to = ""), visible to the whole
	// group. These used to live only in the poster's own config, so nobody else
	// ever saw them. Riding on requests gets them synced, expired after two weeks
	// and pruned like everything else. Older clients only ever list requests
	// addressed to them or sent by them, so they carry these without showing them.
	public static final String TYPE_LOOKING_FOR = "LOOKING_FOR";
	// Asking for more time on a loan: the four steps of the agreed handshake, one
	// request each. The borrower asks, the lender may offer a different date, the
	// borrower may answer that once with a date of their own, and the lender's
	// next word is final. Each counter is a NEW request, because the merge only
	// ever carries a status forward on a row a client already holds - never its
	// other fields.
	//
	// Addressed to nobody (to = "") ON PURPOSE. A client from before these types
	// shows any unfamiliar request addressed to it as "<from> offers to lend you",
	// and accepting that records a brand-new loan running the wrong way round.
	// Those clients only list requests addressed to them (and staff removals), so
	// with to = "" the person who has to answer never sees one there. They DO still
	// list the sender's own steps: a player with a second computer not yet updated
	// sees their own step there with no name, and may be told a counter was a
	// decline - harmless, and gone once that computer updates. Who answers comes
	// from the loan instead: whichever of its lender and borrower didn't send it.
	//
	// The proposed due date rides in durationDays, as the loan's whole length in
	// days from when it was lent - an existing field, so it survives an older
	// client passing the row along, which drops every field it doesn't know.
	public static final String TYPE_EXTEND_ASK = "EXTEND_ASK";         // borrower asks
	public static final String TYPE_EXTEND_COUNTER = "EXTEND_COUNTER"; // lender's first counter
	public static final String TYPE_EXTEND_REPLY = "EXTEND_REPLY";     // borrower's counter back
	public static final String TYPE_EXTEND_FINAL = "EXTEND_FINAL";     // lender's final offer

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

	public boolean isLookingFor()
	{
		return TYPE_LOOKING_FOR.equals(type);
	}

	public boolean isExtension()
	{
		return TYPE_EXTEND_ASK.equals(type) || TYPE_EXTEND_COUNTER.equals(type)
			|| TYPE_EXTEND_REPLY.equals(type) || TYPE_EXTEND_FINAL.equals(type);
	}

	/** An extension step the borrower writes (the ask, the counter back); the others are the lender's. */
	public boolean isBorrowerExtensionStep()
	{
		return TYPE_EXTEND_ASK.equals(type) || TYPE_EXTEND_REPLY.equals(type);
	}

	/** The step that answers this one with a different date, or null after the lender's final offer. */
	public String nextExtensionStep()
	{
		if (TYPE_EXTEND_ASK.equals(type)) return TYPE_EXTEND_COUNTER;
		if (TYPE_EXTEND_COUNTER.equals(type)) return TYPE_EXTEND_REPLY;
		if (TYPE_EXTEND_REPLY.equals(type)) return TYPE_EXTEND_FINAL;
		return null;
	}

	/** The due date an extension step proposes, for a loan lent at lendTime. */
	public long proposedDueTime(long lendTime)
	{
		return lendTime + Math.max(1, durationDays) * 86400000L;
	}

	public boolean isStaffRemoval()
	{
		return TYPE_REMOVAL_STAFF.equals(type);
	}
}
