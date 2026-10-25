package net.kaloyan.smpmarket.model;

/** State of the market fee withdrawal that follows a confirmed payment. */
public enum FeeState {
    /** No fee is charged for this transaction. */
    NONE,
    /** Fee owed and no withdrawal attempted yet (or the last attempt definitively failed). Safe to attempt. */
    PENDING,
    /** A withdrawal may be in flight. After a restart this is treated as uncertain. */
    COLLECTING,
    /** The fee was withdrawn from the seller. */
    COLLECTED,
    /** The outcome of the withdrawal is unknown; an administrator decides. */
    UNCERTAIN
}
