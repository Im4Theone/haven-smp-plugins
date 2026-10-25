package net.kaloyan.smpmarket.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpmarket.config.MarketSettings;
import net.kaloyan.smpmarket.model.Delivery;
import net.kaloyan.smpmarket.model.EscrowResolution;
import net.kaloyan.smpmarket.model.FeeState;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.Listing;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.model.Price;
import net.kaloyan.smpmarket.service.ItemGateway.GiveResult;
import net.kaloyan.smpmarket.service.MarketResults.BuyResult;
import net.kaloyan.smpmarket.service.MarketResults.BuyStatus;
import net.kaloyan.smpmarket.service.MarketResults.CancelResult;
import net.kaloyan.smpmarket.service.MarketResults.CancelStatus;
import net.kaloyan.smpmarket.service.MarketResults.CollectResult;
import net.kaloyan.smpmarket.service.MarketResults.JoinSummary;
import net.kaloyan.smpmarket.service.MarketResults.ListingView;
import net.kaloyan.smpmarket.service.MarketResults.Page;
import net.kaloyan.smpmarket.service.MarketResults.RecoveryReport;
import net.kaloyan.smpmarket.service.MarketResults.SellResult;
import net.kaloyan.smpmarket.service.MarketResults.SellStatus;
import net.kaloyan.smpmarket.storage.MarketStore;
import net.kaloyan.smpmarket.storage.MarketStore.CancelOutcome;
import net.kaloyan.smpmarket.storage.MarketStore.Expired;
import net.kaloyan.smpmarket.storage.MarketStore.PurchaseStart;
import net.kaloyan.smpmarket.storage.StorageException;

/**
 * The market's business logic and the only owner of the transaction workflow described in
 * docs/TRANSACTION_DESIGN.md.
 *
 * <h2>Threads</h2>
 * <ul>
 *   <li>{@code storage}: a single-thread executor. Every {@link MarketStore} call runs there, never on the main
 *       thread.</li>
 *   <li>{@code mainThread}: the server main thread. Every {@link ItemGateway} call runs there, never on the storage
 *       thread or SMP-Economy's thread.</li>
 *   <li>SMP-Economy completes its futures on its own storage thread; continuations hop to {@code storage}
 *       immediately and never touch Bukkit.</li>
 * </ul>
 * Public methods that take a player may be called from the main thread; {@link #sell} must be.
 *
 * <h2>Rule that everything else follows</h2>
 * The database is written <em>before</em> an irreversible external step (intent) and <em>after</em> it (outcome).
 * When the outcome of an external step cannot be proven, the state becomes explicitly uncertain and is never
 * retried, refunded or discarded automatically.
 */
public final class MarketService {

    /** Entries per page of browse, history and administrative listings. */
    public static final int PAGE_SIZE = 8;
    /** History keeps showing only this many most recent entries. */
    public static final int HISTORY_LIMIT = 100;

    private static final int MAX_ITEMS_PER_COLLECT = 54;
    private static final int STORE_ATTEMPTS = 3;
    private static final int SWEEP_BATCH = 200;
    private static final int FEE_BATCH = 20;

    /** One delivery claim that has not been concluded yet. */
    private record Claim(String token, AtomicBoolean given) {
    }

    private final MarketStore store;
    private final EconomyService economy;
    private final ItemGateway items;
    private final Supplier<MarketSettings> settings;
    private final Executor storage;
    private final Executor mainThread;
    private final Clock clock;
    private final Logger logger;

    private final Set<UUID> selling = ConcurrentHashMap.newKeySet();
    private final Set<Long> buyingListings = ConcurrentHashMap.newKeySet();
    private final Set<UUID> buyingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<Long, Claim> openClaims = new ConcurrentHashMap<>();
    /** Listings recorded as PENDING_ESCROW whose item has not been removed from the seller yet. */
    private final Set<Long> escrowAwaitingRemoval = ConcurrentHashMap.newKeySet();
    /** Economy calls (and the database write that records their outcome) that are in flight. */
    private final AtomicInteger criticalInFlight = new AtomicInteger();

    /**
     * @param store      open store
     * @param economy    the real SMP-Economy service
     * @param items      player and inventory access; used on {@code mainThread} only
     * @param settings   live configuration
     * @param storage    runs all database work, one task at a time in submission order
     * @param mainThread runs all inventory and player access
     * @param clock      source of UTC time
     * @param logger     console logger
     */
    public MarketService(MarketStore store, EconomyService economy, ItemGateway items, Supplier<MarketSettings> settings,
                         Executor storage, Executor mainThread, Clock clock, Logger logger) {
        this.store = store;
        this.economy = economy;
        this.items = items;
        this.settings = settings;
        this.storage = storage;
        this.mainThread = mainThread;
        this.clock = clock;
        this.logger = logger;
    }

    // ---------------------------------------------------------------- formatting

    /**
     * Formats an amount with SMP-Economy's currency settings.
     *
     * @param minor amount in minor units
     * @return for example {@code $12.50}
     */
    public String format(long minor) {
        return economy.format(Price.toDecimal(minor));
    }

    private long now() {
        return clock.millis();
    }

    // ---------------------------------------------------------------- sell

    /**
     * Lists the item in the player's main hand. Call on the main thread.
     *
     * <p>Sequence (see the design document): validate and snapshot the hand on the main thread; durably record the
     * listing as {@code PENDING_ESCROW} (limit checked in the same transaction); remove exactly that stack on the
     * main thread; durably activate the listing. The item leaves the player only after the listing row, which holds
     * a complete copy of it, is on disk.
     *
     * @param player     the seller
     * @param name       the seller's current name
     * @param priceMinor the total price in minor units
     * @return the outcome
     */
    public CompletableFuture<SellResult> sell(UUID player, String name, long priceMinor) {
        MarketSettings s = settings.get();
        if (priceMinor < s.minPriceMinor()) {
            return done(SellResult.of(SellStatus.PRICE_TOO_LOW, s.minPriceMinor()));
        }
        long economyLimit = Price.toMinor(economy.getMaximumTransactionAmount());
        long max = Math.min(s.maxPriceMinor(), economyLimit);
        if (priceMinor > s.maxPriceMinor()) {
            return done(SellResult.of(SellStatus.PRICE_TOO_HIGH, s.maxPriceMinor()));
        }
        if (priceMinor > max) {
            return done(SellResult.of(SellStatus.ECONOMY_LIMIT, economyLimit));
        }
        Optional<ItemSnapshot> held = items.readMainHand(player);
        if (held.isEmpty()) {
            return done(SellResult.of(SellStatus.EMPTY_HAND));
        }
        ItemSnapshot item = held.get();
        if (item.amount() < s.minAmount()) {
            return done(SellResult.of(SellStatus.AMOUNT_OUT_OF_RANGE, s.minAmount()));
        }
        if (item.amount() > s.maxAmount()) {
            return done(SellResult.of(SellStatus.AMOUNT_OUT_OF_RANGE, s.maxAmount()));
        }
        if (s.isBlocked(item.material())) {
            return done(SellResult.of(SellStatus.ITEM_RESTRICTED));
        }
        if (!selling.add(player)) {
            return done(SellResult.of(SellStatus.BUSY));
        }
        CompletableFuture<SellResult> result;
        try {
            result = CompletableFuture.supplyAsync(
                            () -> {
                                MarketStore.EscrowStart start = store.insertPendingEscrow(player, name, item, priceMinor,
                                        s.maxPerPlayer(), now());
                                if (!start.limitReached()) {
                                    escrowAwaitingRemoval.add(start.listingId());
                                }
                                return start;
                            }, storage)
                    .thenComposeAsync(start -> start.limitReached()
                            ? done(SellResult.of(SellStatus.LIMIT_REACHED, s.maxPerPlayer()))
                            : escrow(player, start.listingId(), item, priceMinor), mainThread)
                    .exceptionally(error -> {
                        logger.log(Level.SEVERE, "Listing failed before the item was touched", unwrap(error));
                        return SellResult.of(SellStatus.ERROR);
                    });
        } catch (RuntimeException e) {
            selling.remove(player);
            throw e;
        }
        return result.whenComplete((r, e) -> selling.remove(player));
    }

    /** Runs on the main thread. */
    private CompletableFuture<SellResult> escrow(UUID player, long listingId, ItemSnapshot item, long priceMinor) {
        boolean removed;
        try {
            removed = items.removeExact(player, item.payload());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Removing the item for listing " + listingId + " failed", e);
            removed = false;
        } finally {
            escrowAwaitingRemoval.remove(listingId);
        }
        if (!removed) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    store.abortEscrow(listingId, now());
                } catch (StorageException e) {
                    // Stays PENDING_ESCROW; the next start turns it into ESCROW_UNCERTAIN for an administrator.
                    logger.log(Level.SEVERE, "Could not abort listing " + listingId, e);
                }
                return SellResult.of(SellStatus.ITEM_CHANGED);
            }, storage);
        }
        // The item is gone from the inventory and a complete copy is on disk. From here on the listing must never be
        // reverted automatically: retry the activation, and otherwise leave the row for an administrator.
        long expiresAt = now() + settings.get().duration().toMillis();
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= STORE_ATTEMPTS; attempt++) {
                try {
                    if (store.activate(listingId, now(), expiresAt)) {
                        return new SellResult(SellStatus.LISTED, listingId, item, priceMinor, expiresAt, 0);
                    }
                    break;
                } catch (StorageException e) {
                    logger.log(Level.SEVERE, "Activating listing " + listingId + " failed (attempt " + attempt + ")", e);
                }
            }
            logger.severe("Listing " + listingId + " holds an item that was removed from " + player
                    + " but could not be activated. It is safe in the database; an administrator must resolve it "
                    + "with /market admin resolve escrow " + listingId + " (it becomes ESCROW_UNCERTAIN on the next start).");
            return SellResult.of(SellStatus.ERROR_NEEDS_ADMIN);
        }, storage);
    }

    // ---------------------------------------------------------------- reading

    /**
     * Active listings, newest first.
     *
     * @param page 1-based page number; clamped into range
     * @return the page
     */
    public CompletableFuture<Page<Listing>> browse(int page) {
        return CompletableFuture.supplyAsync(() -> {
            long now = now();
            int total = store.countBrowse(now);
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            int shown = Math.min(Math.max(page, 1), pages);
            return new Page<>(store.browse(now, (shown - 1) * PAGE_SIZE, PAGE_SIZE), shown, pages, total);
        }, storage);
    }

    /**
     * A player's open listings.
     *
     * @param owner the player
     * @return the listings, newest first
     */
    public CompletableFuture<List<Listing>> myListings(UUID owner) {
        return CompletableFuture.supplyAsync(() -> store.ownerListings(owner), storage);
    }

    /**
     * A player's most recent completed sales and purchases.
     *
     * @param player the player
     * @param page   1-based page number; clamped into range
     * @return the page, newest first
     */
    public CompletableFuture<Page<MarketTx>> history(UUID player, int page) {
        return CompletableFuture.supplyAsync(() -> {
            int total = Math.min(store.countHistory(player), HISTORY_LIMIT);
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            int shown = Math.min(Math.max(page, 1), pages);
            int offset = (shown - 1) * PAGE_SIZE;
            int limit = Math.min(PAGE_SIZE, Math.max(0, total - offset));
            return new Page<>(limit == 0 ? List.of() : store.history(player, offset, limit), shown, pages, total);
        }, storage);
    }

    /**
     * Number of active listings, for the status line of {@code /market}.
     *
     * @return the count
     */
    public CompletableFuture<Integer> activeCount() {
        return CompletableFuture.supplyAsync(() -> store.countBrowse(now()), storage);
    }

    // ---------------------------------------------------------------- cancel

    /**
     * Cancels a listing exactly once and returns the item to its owner (immediately if they are online with room,
     * otherwise as a pending delivery).
     *
     * @param actor the player (or administrator) cancelling
     * @param admin whether the actor may cancel other players' listings
     * @param id    the listing id
     * @return the outcome
     */
    public CompletableFuture<CancelResult> cancel(UUID actor, boolean admin, long id) {
        return CompletableFuture.supplyAsync(() -> {
                    CancelOutcome outcome = store.cancel(id, actor, admin, now());
                    ItemSnapshot item = outcome.kind() == CancelOutcome.Kind.CANCELLED
                            ? store.findDeliveryForListing(id).map(Delivery::item).orElse(null) : null;
                    return new CancelPlus(outcome, item);
                }, storage)
                .thenCompose(cancelled -> {
                    CancelOutcome outcome = cancelled.outcome();
                    switch (outcome.kind()) {
                        case NOT_FOUND:
                            return done(CancelResult.of(CancelStatus.NOT_FOUND));
                        case NOT_OWNER:
                            return done(CancelResult.of(CancelStatus.NOT_OWNER));
                        case NOT_ACTIVE:
                            return done(new CancelResult(CancelStatus.NOT_ACTIVE, null, false, outcome.status()));
                        case CANCELLED:
                        default:
                            // The cancel is committed. Delivery is best effort and recoverable: the item is already
                            // owed to the owner.
                            return deliver(outcome.owner()).handle((collected, error) -> new CancelResult(
                                    CancelStatus.CANCELLED, cancelled.item(), error == null && collected.delivered() > 0,
                                    null));
                    }
                })
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Cancelling listing " + id + " failed", unwrap(error));
                    return CancelResult.of(CancelStatus.ERROR);
                });
    }

    private record CancelPlus(CancelOutcome outcome, ItemSnapshot item) {
    }

    // ---------------------------------------------------------------- collect / delivery

    /**
     * Delivers the player's pending items into their inventory.
     *
     * @param player the player, online
     * @return how many items were delivered and how many still wait
     */
    public CompletableFuture<CollectResult> collect(UUID player) {
        return deliver(player).exceptionally(error -> {
            logger.log(Level.SEVERE, "Collecting for " + player + " failed", unwrap(error));
            return new CollectResult(0, 0, true);
        });
    }

    /**
     * One delivery attempt: claim (durable), give on the main thread, conclude (durable). An item is marked
     * delivered only after the inventory accepted the complete stack; an item that is not accepted returns to
     * pending. A crash between claim and conclusion leaves the row {@code DELIVERING}, which the next start turns
     * into {@code UNCERTAIN}: never delivered twice automatically, never silently dropped.
     */
    private CompletableFuture<CollectResult> deliver(UUID owner) {
        String token = UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(() -> {
            List<Delivery> claimed = store.claimDeliveries(owner, token, MAX_ITEMS_PER_COLLECT, now());
            for (Delivery d : claimed) {
                openClaims.put(d.id(), new Claim(token, new AtomicBoolean()));
            }
            return claimed;
        }, storage).thenComposeAsync(claimed -> {
            if (claimed.isEmpty()) {
                return CompletableFuture.supplyAsync(() -> new CollectResult(0, store.pendingDeliveryCount(owner), false),
                        storage);
            }
            List<Long> delivered = new ArrayList<>();
            List<Long> released = new ArrayList<>();
            boolean offline = false;
            for (Delivery d : claimed) {
                GiveResult result = GiveResult.OFFLINE;
                if (!offline) {
                    try {
                        result = items.give(owner, d.item().payload());
                    } catch (RuntimeException e) {
                        logger.log(Level.SEVERE, "Giving delivery " + d.id() + " failed; it stays pending", e);
                        result = GiveResult.INVALID_ITEM;
                    }
                }
                if (result == GiveResult.DELIVERED) {
                    openClaims.get(d.id()).given().set(true);
                    delivered.add(d.id());
                } else {
                    released.add(d.id());
                    offline |= result == GiveResult.OFFLINE;
                    if (result == GiveResult.INVALID_ITEM) {
                        logger.warning("Delivery " + d.id() + " (listing " + d.listingId() + ") could not be restored "
                                + "on this server version; it stays pending.");
                    }
                }
            }
            return CompletableFuture.supplyAsync(() -> {
                for (int attempt = 1; ; attempt++) {
                    try {
                        store.finishDeliveries(token, delivered, released, now());
                        break;
                    } catch (StorageException e) {
                        logger.log(Level.SEVERE, "Concluding delivery attempt failed (attempt " + attempt + ")", e);
                        if (attempt == STORE_ATTEMPTS) {
                            // Claims stay open: onDisable retries, and the next start marks them UNCERTAIN.
                            return new CollectResult(delivered.size(), released.size(), true);
                        }
                    }
                }
                delivered.forEach(openClaims::remove);
                released.forEach(openClaims::remove);
                return new CollectResult(delivered.size(), store.pendingDeliveryCount(owner), false);
            }, storage);
        }, mainThread);
    }

    private CompletableFuture<CollectResult> deliverIfOnline(UUID owner) {
        return CompletableFuture.supplyAsync(() -> items.isOnline(owner), mainThread)
                .thenCompose(online -> online ? deliver(owner) : done(CollectResult.NONE));
    }

    /**
     * Concludes every delivery claim still open, for plugin shutdown. Claims whose item was given are marked
     * delivered; the others return to pending. Call on the main thread after the storage executor has drained
     * and before the store is closed.
     */
    public void concludeOpenClaims() {
        for (Long listingId : List.copyOf(escrowAwaitingRemoval)) {
            // The item never left the seller (the main-thread step was cancelled by the shutdown): close the listing
            // so the next start does not mistake it for an interrupted removal.
            try {
                store.abortEscrow(listingId, now());
            } catch (StorageException e) {
                logger.log(Level.SEVERE, "Could not abort listing " + listingId + " at shutdown", e);
            }
        }
        escrowAwaitingRemoval.clear();
        Map<String, List<Long>> deliveredByToken = new java.util.HashMap<>();
        Map<String, List<Long>> releasedByToken = new java.util.HashMap<>();
        openClaims.forEach((id, claim) -> (claim.given().get() ? deliveredByToken : releasedByToken)
                .computeIfAbsent(claim.token(), t -> new ArrayList<>()).add(id));
        Set<String> tokens = new LinkedHashSet<>(deliveredByToken.keySet());
        tokens.addAll(releasedByToken.keySet());
        for (String token : tokens) {
            try {
                store.finishDeliveries(token, deliveredByToken.getOrDefault(token, List.of()),
                        releasedByToken.getOrDefault(token, List.of()), now());
            } catch (StorageException e) {
                logger.log(Level.SEVERE, "Could not conclude delivery claims at shutdown; they become UNCERTAIN on "
                        + "the next start", e);
            }
        }
        openClaims.clear();
    }

    /**
     * Waits until no Economy call (and the write that records its outcome) is in flight, so a shutdown does not
     * strand a payment whose outcome would then have to be treated as uncertain.
     *
     * @param timeout how long to wait at most
     * @param unit    the unit of {@code timeout}
     * @return {@code true} if nothing is in flight
     */
    public boolean awaitQuiescent(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (criticalInFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return criticalInFlight.get() == 0;
    }

    // ---------------------------------------------------------------- purchase

    /**
     * Buys a listing. See docs/TRANSACTION_DESIGN.md for the full state machine.
     *
     * <ol>
     *   <li>One database transaction reserves the listing ({@code ACTIVE -> RESERVED}) and records the payment
     *       intent ({@code PAYMENT_PENDING}). Only one buyer can win.</li>
     *   <li>One call to {@code EconomyService.transfer(buyer, seller, price)}, which SMP-Economy documents as
     *       atomic: both balances change or neither does.</li>
     *   <li>Outcome recorded: {@code SUCCESS} gives {@code DELIVERY_PENDING} and creates the buyer's delivery in the
     *       same transaction as {@code RESERVED -> SOLD}; a definitive failure gives {@code FAILED} and reopens the
     *       listing; anything unprovable gives {@code PAYMENT_UNCERTAIN} and nothing else happens.</li>
     *   <li>Fee withdrawal from the seller (if configured), delivery to the buyer, notification of the seller.</li>
     * </ol>
     *
     * @param buyer     the buyer
     * @param buyerName the buyer's current name
     * @param listingId the listing id
     * @return the outcome; never reports success before SMP-Economy confirmed the payment
     */
    public CompletableFuture<BuyResult> buy(UUID buyer, String buyerName, long listingId) {
        if (!buyingListings.add(listingId)) {
            return done(BuyResult.of(BuyStatus.BUSY));
        }
        if (!buyingPlayers.add(buyer)) {
            buyingListings.remove(listingId);
            return done(BuyResult.of(BuyStatus.BUSY));
        }
        MarketSettings s = settings.get();
        CompletableFuture<BuyResult> result;
        try {
            result = CompletableFuture.supplyAsync(() -> begin(buyer, buyerName, listingId, s), storage)
                    .thenCompose(started -> started.start() == null ? done(started.early()) : pay(started))
                    .exceptionally(error -> {
                        logger.log(Level.SEVERE, "Purchase of listing " + listingId + " failed before any payment", unwrap(error));
                        return BuyResult.of(BuyStatus.ERROR);
                    });
        } catch (RuntimeException e) {
            buyingListings.remove(listingId);
            buyingPlayers.remove(buyer);
            throw e;
        }
        return result.whenComplete((r, e) -> {
            buyingListings.remove(listingId);
            buyingPlayers.remove(buyer);
        });
    }

    private record Started(PurchaseStart start, BuyResult early, ItemSnapshot item) {
    }

    private Started begin(UUID buyer, String buyerName, long listingId, MarketSettings s) {
        PurchaseStart start = store.beginPurchase(listingId, buyer, buyerName, s.allowSelfPurchase(),
                s.feePercentHundredths(), now());
        switch (start.kind()) {
            case NOT_FOUND:
                return new Started(null, BuyResult.of(BuyStatus.NOT_FOUND), null);
            case EXPIRED:
                return new Started(null, BuyResult.of(BuyStatus.EXPIRED), null);
            case SELF_PURCHASE:
                return new Started(null, BuyResult.of(BuyStatus.SELF_PURCHASE), null);
            case UNAVAILABLE:
                return new Started(null, new BuyResult(BuyStatus.UNAVAILABLE, null, null, false, start.status()), null);
            case STARTED:
            default:
                ItemSnapshot item = store.findListing(listingId).map(Listing::item).orElse(null);
                return new Started(start, null, item);
        }
    }

    /** The intent is durable. Make the single Economy call and settle its outcome. */
    private CompletableFuture<BuyResult> pay(Started started) {
        MarketTx tx = started.start().tx();
        CompletableFuture<TransactionResult> payment;
        criticalInFlight.incrementAndGet();
        try {
            // Buying your own listing (only when allowed by configuration) moves no money; SMP-Economy would refuse
            // a self transfer, and there is nothing to transfer.
            payment = tx.buyer().equals(tx.seller())
                    ? CompletableFuture.completedFuture(TransactionResult.success(java.math.BigDecimal.ZERO, null))
                    : economy.transfer(tx.buyer(), tx.seller(), Price.toDecimal(tx.priceMinor()));
        } catch (RuntimeException e) {
            payment = CompletableFuture.failedFuture(e);
        }
        return payment.handleAsync((result, error) -> {
                    try {
                        return settle(tx, started.item(), result, error);
                    } finally {
                        criticalInFlight.decrementAndGet();
                    }
                }, storage)
                .thenCompose(settled -> settled.status() == BuyStatus.PURCHASED ? afterPayment(settled) : done(settled));
    }

    /** Runs on the storage thread. */
    private BuyResult settle(MarketTx tx, ItemSnapshot item, TransactionResult result, Throwable error) {
        if (error != null) {
            Throwable cause = unwrap(error);
            if (notExecuted(cause)) {
                return fail(tx, item, BuyStatus.ECONOMY_UNAVAILABLE, "SMP-Economy is not available: " + cause.getMessage());
            }
            logger.log(Level.SEVERE, "Payment of transaction " + tx.id() + " ended with an error; its outcome is unknown. "
                    + "Marking PAYMENT_UNCERTAIN; nothing will be retried or refunded automatically.", cause);
            return uncertain(tx, item);
        }
        if (result == null) {
            return uncertain(tx, item);
        }
        if (result.isSuccess()) {
            for (int attempt = 1; attempt <= STORE_ATTEMPTS; attempt++) {
                try {
                    store.confirmPayment(tx.id(), now());
                    return BuyResult.of(BuyStatus.PURCHASED, tx, item);
                } catch (StorageException e) {
                    logger.log(Level.SEVERE, "Recording the confirmed payment of transaction " + tx.id()
                            + " failed (attempt " + attempt + ")", e);
                }
            }
            logger.severe("Transaction " + tx.id() + " (listing " + tx.listingId() + "): " + tx.buyer() + " WAS CHARGED "
                    + tx.priceMinor() + " minor units but the database could not record it. The row stays PAYMENT_PENDING "
                    + "and becomes PAYMENT_UNCERTAIN on the next start; resolve it with /market admin resolve payment "
                    + tx.id() + " paid.");
            return BuyResult.of(BuyStatus.PAID_BUT_UNRECORDED, tx, item);
        }
        BuyStatus status = switch (result.status()) {
            case INSUFFICIENT_FUNDS -> BuyStatus.INSUFFICIENT_FUNDS;
            case SOURCE_ACCOUNT_NOT_FOUND -> BuyStatus.NO_ACCOUNT_BUYER;
            case TARGET_ACCOUNT_NOT_FOUND -> BuyStatus.NO_ACCOUNT_SELLER;
            case BALANCE_LIMIT_EXCEEDED -> BuyStatus.SELLER_BALANCE_LIMIT;
            case AMOUNT_EXCEEDS_LIMIT -> BuyStatus.PRICE_EXCEEDS_ECONOMY_LIMIT;
            case SELF_TRANSFER -> BuyStatus.SELF_PURCHASE;
            case STORAGE_ERROR -> BuyStatus.ECONOMY_UNAVAILABLE;
            default -> BuyStatus.ERROR;
        };
        return fail(tx, item, status, "Economy refused: " + result.status());
    }

    private BuyResult fail(MarketTx tx, ItemSnapshot item, BuyStatus status, String reason) {
        try {
            store.failPayment(tx.id(), reason, now(), false);
        } catch (StorageException e) {
            // Nothing was charged, but the row cannot be closed now: it stays PAYMENT_PENDING, which the next start
            // treats as uncertain. Safe, just needs an administrator.
            logger.log(Level.SEVERE, "Could not close failed transaction " + tx.id(), e);
        }
        return BuyResult.of(status, tx, item);
    }

    private BuyResult uncertain(MarketTx tx, ItemSnapshot item) {
        try {
            store.markPaymentUncertain(tx.id(), now());
        } catch (StorageException e) {
            logger.log(Level.SEVERE, "Could not mark transaction " + tx.id() + " uncertain; the next start will", e);
        }
        return BuyResult.of(BuyStatus.PAYMENT_UNCERTAIN, tx, item);
    }

    private static boolean notExecuted(Throwable cause) {
        return cause instanceof IllegalStateException && cause.getCause() instanceof RejectedExecutionException;
    }

    /** Payment is confirmed and recorded. Everything from here is forward-only: the buyer is owed the item. */
    private CompletableFuture<BuyResult> afterPayment(BuyResult paid) {
        MarketTx tx = paid.tx();
        return collectFee(tx.id())
                .handle((ignored, error) -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Fee collection for transaction " + tx.id() + " failed", unwrap(error));
                    }
                    return null;
                })
                .thenCompose(ignored -> deliver(tx.buyer()))
                .handle((collected, error) -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Delivery after transaction " + tx.id()
                                + " failed; the item stays pending for /market collect", unwrap(error));
                    }
                    boolean delivered = error == null && collected.delivered() > 0;
                    notifySeller(tx, paid.item());
                    return new BuyResult(BuyStatus.PURCHASED, tx, paid.item(), delivered, null);
                });
    }

    private void notifySeller(MarketTx tx, ItemSnapshot item) {
        CompletableFuture.supplyAsync(() -> {
            if (!items.isOnline(tx.seller())) {
                return false;
            }
            long net = tx.priceMinor() - tx.feeMinor();
            items.notifyPlayer(tx.seller(), "Your listing #" + tx.listingId() + " (" + (item == null ? "item" : item.display())
                    + ") sold to " + tx.buyerName() + " for " + format(tx.priceMinor())
                    + (tx.feeMinor() > 0 ? " (fee " + format(tx.feeMinor()) + ", you receive " + format(net) + ")" : "") + ".");
            return true;
        }, mainThread).thenAcceptAsync(told -> {
            if (told) {
                try {
                    store.markSellerNotified(List.of(tx.id()), now());
                } catch (StorageException e) {
                    logger.log(Level.WARNING, "Could not record seller notification of transaction " + tx.id(), e);
                }
            }
        }, storage).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not notify seller of transaction " + tx.id(), unwrap(error));
            return null;
        });
    }

    // ---------------------------------------------------------------- fee

    /**
     * Withdraws the market fee from the seller after a confirmed payment. A definite failure leaves the fee
     * {@code PENDING} (retried by the periodic sweep); an unprovable outcome makes it {@code UNCERTAIN}.
     */
    private CompletableFuture<Void> collectFee(long txId) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<MarketTx> found = store.findTx(txId);
            if (found.isEmpty() || found.get().feeState() != FeeState.PENDING || found.get().feeMinor() <= 0
                    || !store.beginFee(txId, now())) {
                return Optional.<MarketTx>empty();
            }
            return found;
        }, storage).thenCompose(found -> {
            if (found.isEmpty()) {
                return done((Void) null);
            }
            MarketTx tx = found.get();
            CompletableFuture<TransactionResult> withdrawal;
            criticalInFlight.incrementAndGet();
            try {
                withdrawal = economy.withdraw(tx.seller(), Price.toDecimal(tx.feeMinor()));
            } catch (RuntimeException e) {
                withdrawal = CompletableFuture.failedFuture(e);
            }
            return withdrawal.handleAsync((result, error) -> {
              try {
                FeeState outcome;
                if (error != null) {
                    outcome = notExecuted(unwrap(error)) ? FeeState.PENDING : FeeState.UNCERTAIN;
                } else if (result != null && result.isSuccess()) {
                    outcome = FeeState.COLLECTED;
                } else {
                    outcome = result == null ? FeeState.UNCERTAIN : FeeState.PENDING;
                }
                if (outcome != FeeState.COLLECTED) {
                    logger.warning("Fee of transaction " + txId + " not collected: "
                            + (error != null ? unwrap(error).toString() : String.valueOf(result)) + " -> " + outcome);
                }
                try {
                    store.finishFee(txId, outcome, now());
                } catch (StorageException e) {
                    logger.log(Level.SEVERE, "Could not record the fee outcome of transaction " + txId
                            + " (" + outcome + "); it becomes UNCERTAIN on the next start", e);
                }
                return (Void) null;
              } finally {
                criticalInFlight.decrementAndGet();
              }
            }, storage);
        });
    }

    // ---------------------------------------------------------------- sweep / recovery / join

    /**
     * Startup recovery. Call once, before any other operation; the single storage thread then guarantees
     * it runs first. Moves everything an interrupted run left in flight to an explicit uncertain state, logs what
     * needs an administrator, then performs a normal sweep.
     *
     * @return what was found
     */
    public CompletableFuture<RecoveryReport> recoverOnStartup() {
        return CompletableFuture.supplyAsync(() -> {
            MarketStore.RestartRecovery moved = store.recoverAfterRestart(now());
            MarketStore.Issues issues = store.issues();
            if (!issues.isEmpty()) {
                logger.warning("SMP-Market found " + issues.escrows().size() + " uncertain listing(s), "
                        + issues.payments().size() + " uncertain payment(s), " + issues.deliveries().size()
                        + " uncertain delivery(ies) and " + issues.fees().size() + " uncertain fee(s). Nothing was paid, "
                        + "refunded, delivered or discarded automatically. See /market admin issues.");
            }
            return moved;
        }, storage).thenCompose(moved -> sweep().thenApply(expired ->
                new RecoveryReport(moved.escrows(), moved.payments(), moved.deliveries(), moved.fees(), expired)));
    }

    /**
     * Expires due listings, delivers returned items to owners who are online, and retries fees that definitively
     * failed before. Idempotent; safe to call at any time.
     *
     * @return how many listings were expired
     */
    public CompletableFuture<Integer> sweep() {
        return CompletableFuture.supplyAsync(() -> store.expireDue(now(), SWEEP_BATCH), storage)
                .thenCompose(expired -> {
                    Set<UUID> owners = new LinkedHashSet<>();
                    for (Expired e : expired) {
                        owners.add(e.owner());
                    }
                    CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
                    for (UUID owner : owners) {
                        chain = chain.thenCompose(x -> deliverIfOnline(owner).handle((collected, error) -> {
                            if (error == null && collected.delivered() > 0) {
                                notifyLater(owner, "Your expired listing(s) were returned: " + collected.delivered()
                                        + " item(s) delivered" + (collected.remaining() > 0
                                        ? ", " + collected.remaining() + " more waiting - use /market collect." : "."));
                            } else if (error == null && collected.remaining() > 0) {
                                notifyLater(owner, "You have " + collected.remaining()
                                        + " item(s) waiting - make room and use /market collect.");
                            }
                            return null;
                        }));
                    }
                    return chain.thenCompose(x -> retryFees()).thenApply(x -> expired.size());
                })
                .exceptionally(error -> {
                    logger.log(Level.SEVERE, "Market sweep failed", unwrap(error));
                    return 0;
                });
    }

    private CompletableFuture<Void> retryFees() {
        return CompletableFuture.supplyAsync(() -> store.feesPending(FEE_BATCH), storage).thenCompose(pending -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (MarketTx tx : pending) {
                chain = chain.thenCompose(x -> collectFee(tx.id()));
            }
            return chain;
        });
    }

    private void notifyLater(UUID player, String text) {
        mainThread.execute(() -> {
            try {
                items.notifyPlayer(player, text);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not notify " + player, e);
            }
        });
    }

    /**
     * Collects what to tell a player who just joined: waiting items and sales they have not heard about.
     *
     * @param player the player
     * @return the summary
     */
    public CompletableFuture<JoinSummary> onJoin(UUID player) {
        return CompletableFuture.supplyAsync(
                () -> new JoinSummary(store.pendingDeliveryCount(player), store.unnotifiedSales(player)), storage);
    }

    /**
     * Records that the player has been told about these sales.
     *
     * @param sales the sales just shown to the seller
     */
    public void markSalesNotified(Collection<MarketTx> sales) {
        List<Long> ids = sales.stream().map(MarketTx::id).toList();
        CompletableFuture.runAsync(() -> store.markSellerNotified(ids, now()), storage).exceptionally(error -> {
            logger.log(Level.WARNING, "Could not record seller notifications", unwrap(error));
            return null;
        });
    }

    // ---------------------------------------------------------------- administration

    /**
     * One page of listings in any status.
     *
     * @param status only this status, or {@code null}
     * @param page   1-based page
     * @return the listings
     */
    public CompletableFuture<List<Listing>> adminListings(ListingStatus status, int page) {
        return CompletableFuture.supplyAsync(
                () -> store.adminListings(status, (Math.max(page, 1) - 1) * PAGE_SIZE, PAGE_SIZE), storage);
    }

    /**
     * Everything stored about one listing.
     *
     * @param id the listing id
     * @return the view, or empty if there is no such listing
     */
    public CompletableFuture<Optional<ListingView>> inspectListing(long id) {
        return CompletableFuture.supplyAsync(() -> store.findListing(id).map(listing -> new ListingView(listing,
                store.findDeliveryForListing(id).orElse(null), store.txsForListing(id))), storage);
    }

    /**
     * One transaction.
     *
     * @param id the transaction id
     * @return the transaction, or empty
     */
    public CompletableFuture<Optional<MarketTx>> inspectTx(long id) {
        return CompletableFuture.supplyAsync(() -> store.findTx(id), storage);
    }

    /**
     * Everything that needs an administrator.
     *
     * @return the unresolved rows
     */
    public CompletableFuture<MarketStore.Issues> issues() {
        return CompletableFuture.supplyAsync(store::issues, storage);
    }

    /**
     * An administrator states whether an uncertain payment happened.
     *
     * @param actor  who is deciding (name), recorded in the audit log
     * @param txId   the transaction
     * @param paid   {@code true} if the buyer was charged and the seller paid
     * @param reason why (mandatory, recorded)
     * @return whether the transaction was uncertain and was resolved
     */
    public CompletableFuture<Boolean> resolvePayment(String actor, long txId, boolean paid, String reason) {
        return admin("resolve payment", actor, 0, txId, 0, reason,
                () -> store.resolvePayment(txId, paid, actor, reason, now()))
                .thenComposeAsync(applied -> {
                    if (!applied || !paid) {
                        return done(applied);
                    }
                    Optional<MarketTx> tx = store.findTx(txId);
                    if (tx.isEmpty()) {
                        return done(applied);
                    }
                    return collectFee(txId).thenCompose(x -> deliverIfOnline(tx.get().buyer())).handle((x, e) -> applied);
                }, storage);
    }

    /**
     * An administrator states what happened to an uncertain delivery.
     *
     * @param delivered {@code true} if the owner has the item; otherwise it becomes collectable again
     * @return whether the delivery was uncertain and was resolved
     */
    public CompletableFuture<Boolean> resolveDelivery(String actor, long deliveryId, boolean delivered, String reason) {
        return admin("resolve delivery", actor, 0, 0, deliveryId, reason,
                () -> store.resolveDelivery(deliveryId, delivered, actor, reason, now()));
    }

    /**
     * An administrator decides an uncertain listing.
     *
     * @param resolution activate (item left the seller), return (give it back) or abort (it never left)
     * @return whether the listing was uncertain and was resolved
     */
    public CompletableFuture<Boolean> resolveEscrow(String actor, long listingId, EscrowResolution resolution,
                                                    String reason) {
        long expiresAt = now() + settings.get().duration().toMillis();
        return admin("resolve escrow", actor, listingId, 0, 0, reason,
                () -> store.resolveEscrow(listingId, resolution, expiresAt, actor, reason, now()))
                .thenComposeAsync(applied -> {
                    if (!applied || resolution != EscrowResolution.RETURN) {
                        return done(applied);
                    }
                    Optional<Listing> listing = store.findListing(listingId);
                    return listing.isEmpty() ? done(applied)
                            : deliverIfOnline(listing.get().owner()).handle((x, e) -> applied);
                }, storage);
    }

    /**
     * An administrator states what happened to an uncertain fee withdrawal.
     *
     * @param collected {@code true} if the fee was taken; otherwise it is attempted again
     * @return whether the fee was uncertain and was resolved
     */
    public CompletableFuture<Boolean> resolveFee(String actor, long txId, boolean collected, String reason) {
        return admin("resolve fee", actor, 0, txId, 0, reason,
                () -> store.resolveFee(txId, collected, actor, reason, now()));
    }

    /**
     * Cancels any player's active listing on behalf of an administrator; the item goes to its owner. Audited.
     *
     * @param actorId   the administrator's UUID
     * @param actorName the administrator's name, for the audit log
     * @param id        the listing
     * @param reason    why (mandatory)
     * @return the outcome
     */
    public CompletableFuture<CancelResult> adminCancel(UUID actorId, String actorName, long id, String reason) {
        return cancel(actorId, true, id).thenApply(result -> {
            if (result.status() == CancelStatus.CANCELLED) {
                try {
                    store.audit(actorName, "ADMIN_CANCEL", id, 0, 0, reason, now());
                } catch (StorageException e) {
                    logger.log(Level.SEVERE, "Could not audit administrative cancel of listing " + id, e);
                }
                logger.info("ADMIN actor=" + actorName + " action=cancel listing=" + id + " reason=" + reason);
            }
            return result;
        });
    }

    private CompletableFuture<Boolean> admin(String what, String actor, long listingId, long txId, long deliveryId,
                                             String reason, Supplier<Boolean> action) {
        return CompletableFuture.supplyAsync(() -> {
            boolean applied = action.get();
            logger.info("ADMIN actor=" + actor + " action=" + what + " listing=" + listingId + " tx=" + txId
                    + " delivery=" + deliveryId + " reason=" + reason + " applied=" + applied);
            return applied;
        }, storage);
    }

    // ---------------------------------------------------------------- plumbing

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
