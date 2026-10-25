package net.kaloyan.smpmarket.service;

import java.util.List;
import net.kaloyan.smpmarket.model.Delivery;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.Listing;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;

/** Result types returned by {@link MarketService}. Commands turn them into player messages. */
public final class MarketResults {

    private MarketResults() {
    }

    /** Why a sell succeeded or failed. */
    public enum SellStatus {
        LISTED, EMPTY_HAND, PRICE_TOO_LOW, PRICE_TOO_HIGH, ECONOMY_LIMIT, AMOUNT_OUT_OF_RANGE, ITEM_RESTRICTED,
        LIMIT_REACHED, BUSY, ITEM_CHANGED,
        /** The database failed before the item was touched; nothing happened. */
        ERROR,
        /** The item was taken but the listing could not be activated; an administrator must resolve it. */
        ERROR_NEEDS_ADMIN
    }

    /**
     * @param listingId the new listing, or 0
     * @param item      the listed item, when known
     * @param price     the price in minor units
     * @param expiresAt UTC epoch millis when listed, else 0
     * @param bound     the bound that was violated, for range statuses (minimum or maximum), else 0
     */
    public record SellResult(SellStatus status, long listingId, ItemSnapshot item, long price, long expiresAt,
                             long bound) {
        static SellResult of(SellStatus status) {
            return new SellResult(status, 0, null, 0, 0, 0);
        }

        static SellResult of(SellStatus status, long bound) {
            return new SellResult(status, 0, null, 0, 0, bound);
        }
    }

    /** Why a purchase succeeded or failed. */
    public enum BuyStatus {
        PURCHASED, NOT_FOUND, UNAVAILABLE, EXPIRED, SELF_PURCHASE, BUSY, INSUFFICIENT_FUNDS, NO_ACCOUNT_BUYER,
        NO_ACCOUNT_SELLER, SELLER_BALANCE_LIMIT, PRICE_EXCEEDS_ECONOMY_LIMIT, ECONOMY_UNAVAILABLE,
        /** The payment may or may not have happened. The listing stays reserved; an administrator resolves it. */
        PAYMENT_UNCERTAIN,
        /** The payment happened but the database could not record it. The transaction is recoverable by an administrator. */
        PAID_BUT_UNRECORDED,
        /** The database failed before anything was charged. */
        ERROR
    }

    /**
     * @param tx        the transaction, when one was started
     * @param item      the item, when known
     * @param delivered whether the item is already in the buyer's inventory (only for {@link BuyStatus#PURCHASED})
     * @param listingStatus the listing's status for {@link BuyStatus#UNAVAILABLE}
     */
    public record BuyResult(BuyStatus status, MarketTx tx, ItemSnapshot item, boolean delivered,
                            ListingStatus listingStatus) {
        static BuyResult of(BuyStatus status) {
            return new BuyResult(status, null, null, false, null);
        }

        static BuyResult of(BuyStatus status, MarketTx tx, ItemSnapshot item) {
            return new BuyResult(status, tx, item, false, null);
        }
    }

    /** Why a cancel succeeded or failed. */
    public enum CancelStatus {
        CANCELLED, NOT_FOUND, NOT_OWNER, NOT_ACTIVE, ERROR
    }

    /**
     * @param item          the item returned, when cancelled
     * @param delivered     whether it is already in the owner's inventory
     * @param listingStatus the listing's status for {@link CancelStatus#NOT_ACTIVE}
     */
    public record CancelResult(CancelStatus status, ItemSnapshot item, boolean delivered, ListingStatus listingStatus) {
        static CancelResult of(CancelStatus status) {
            return new CancelResult(status, null, false, null);
        }
    }

    /**
     * @param delivered number of items put into the inventory by this attempt
     * @param remaining number of items still waiting afterwards
     * @param error     whether the database failed
     */
    public record CollectResult(int delivered, int remaining, boolean error) {
        static final CollectResult NONE = new CollectResult(0, 0, false);
    }

    /**
     * @param items       the entries of this page
     * @param page        1-based page number actually returned
     * @param pages       number of pages (at least 1)
     * @param total       total number of entries
     */
    public record Page<T>(List<T> items, int page, int pages, int total) {
    }

    /** What an administrator sees for one listing. */
    public record ListingView(Listing listing, Delivery delivery, List<MarketTx> transactions) {
    }

    /** What join handling found for a player. */
    public record JoinSummary(int pendingDeliveries, List<MarketTx> unnotifiedSales) {
    }

    /** Outcome of the startup recovery pass. */
    public record RecoveryReport(int escrows, int payments, int deliveries, int fees, int expired) {
        /** @return whether anything needs an administrator */
        public boolean needsAttention() {
            return escrows + payments + deliveries + fees > 0;
        }
    }
}
