package net.kaloyan.smpmarket.model;

import java.util.UUID;

/**
 * A listing row. Prices are in minor units (hundredths). Times are UTC epoch milliseconds.
 *
 * @param expiresAt 0 while the listing has never been active
 */
public record Listing(long id, UUID owner, String ownerName, ListingStatus status, long priceMinor, ItemSnapshot item,
                      long createdAt, long expiresAt, long updatedAt) {
}
