package net.kaloyan.smpmarket.model;

/** State of a purchase transaction. See docs/TRANSACTION_DESIGN.md. */
public enum TxState {
    /** Intent recorded; the Economy call may be in flight. After a restart this is treated as uncertain. */
    PAYMENT_PENDING,
    /** The outcome of the payment is unknown. Nothing is retried or refunded automatically. */
    PAYMENT_UNCERTAIN,
    /** Payment confirmed by SMP-Economy; the item is owed to the buyer and not yet delivered. */
    DELIVERY_PENDING,
    /** Payment confirmed and item delivered to the buyer. */
    COMPLETED,
    /** Payment definitively did not happen; the listing was reopened. */
    FAILED
}
