package net.kaloyan.smpmarket.model;

import java.util.UUID;

/**
 * A purchase transaction row. Prices are in minor units; times are UTC epoch milliseconds
 * ({@code completedAt} is 0 until the payment is confirmed).
 */
public record MarketTx(long id, long listingId, UUID buyer, String buyerName, UUID seller, String sellerName,
                       long priceMinor, long feeMinor, TxState state, FeeState feeState, String failureReason,
                       boolean sellerNotified, long createdAt, long updatedAt, long completedAt) {
}
