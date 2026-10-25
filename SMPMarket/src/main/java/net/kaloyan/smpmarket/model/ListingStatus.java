package net.kaloyan.smpmarket.model;

/**
 * Lifecycle of a listing. The status is the durable source of truth for who owns the item right now.
 *
 * <pre>
 * PENDING_ESCROW -> ACTIVE -> RESERVED -> SOLD
 *       |              |          |
 *       |              |          +-> ACTIVE (payment definitively failed)
 *       |              +-> CANCELLED | EXPIRED
 *       +-> ABORTED | ESCROW_UNCERTAIN
 * </pre>
 */
public enum ListingStatus {
    /** Row written; the item has not (provably) left the seller's inventory yet. Not visible, not buyable. */
    PENDING_ESCROW,
    /** Found in {@link #PENDING_ESCROW} after a restart: nobody can prove whether the item was removed. Admin decides. */
    ESCROW_UNCERTAIN,
    /** The item could not be taken from the seller; the listing never went on sale. */
    ABORTED,
    /** On sale. */
    ACTIVE,
    /** A purchase is in progress (or its payment outcome is uncertain). Cannot be bought, cancelled or expired. */
    RESERVED,
    /** Paid; the item belongs to the buyer (delivery row exists). */
    SOLD,
    /** Cancelled; the item belongs to the seller (delivery row exists). */
    CANCELLED,
    /** Expired; the item belongs to the seller (delivery row exists). */
    EXPIRED
}
