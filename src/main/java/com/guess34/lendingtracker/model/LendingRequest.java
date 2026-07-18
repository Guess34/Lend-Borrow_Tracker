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

	public static final String STATUS_PENDING = "PENDING";
	public static final String STATUS_ACCEPTED = "ACCEPTED";
	public static final String STATUS_DECLINED = "DECLINED";
	public static final String STATUS_CANCELLED = "CANCELLED";

	private String id;
	private String groupId;
	private String type;         // TYPE_BORROW_REQUEST or TYPE_LEND_OFFER
	private String from;         // player who created the request
	private String to;           // player the request is addressed to
	private String itemName;
	private int itemId;
	private int quantity;
	private int durationDays;
	private String message;
	private String status;       // STATUS_* constants
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
}
