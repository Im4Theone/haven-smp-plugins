package net.kaloyan.smpmarket.model;

/** State of an item owed to a player. */
public enum DeliveryStatus {
    /** Waiting for the owner to collect it. */
    PENDING,
    /** Claimed by one delivery attempt that is putting the item into an inventory right now. */
    DELIVERING,
    /** Safely delivered; never delivered again. */
    DELIVERED,
    /** A previous server run died while delivering: it is unknown whether the item reached the inventory. */
    UNCERTAIN
}
