package net.kaloyan.smpmarket.model;

import java.util.UUID;

/**
 * An item owed to a player. Exactly one delivery exists per listing (enforced by a unique index), which makes
 * it structurally impossible for one listing to produce two items.
 *
 * @param txId 0 when the delivery is not the result of a purchase
 */
public record Delivery(long id, UUID owner, long listingId, long txId, DeliveryReason reason, DeliveryStatus status,
                       ItemSnapshot item, long createdAt, long updatedAt) {
}
