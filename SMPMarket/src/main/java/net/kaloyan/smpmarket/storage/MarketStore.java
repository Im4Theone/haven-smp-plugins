package net.kaloyan.smpmarket.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kaloyan.smpmarket.model.Delivery;
import net.kaloyan.smpmarket.model.DeliveryReason;
import net.kaloyan.smpmarket.model.DeliveryStatus;
import net.kaloyan.smpmarket.model.EscrowResolution;
import net.kaloyan.smpmarket.model.FeeState;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.Listing;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.model.Price;
import net.kaloyan.smpmarket.model.TxState;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * The only class that reads or writes market state. All SQL lives here.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>Every public mutating method is exactly one {@code BEGIN IMMEDIATE} ... {@code COMMIT} transaction.
 *       {@code IMMEDIATE} takes SQLite's write lock before the first read, so a state that is checked is the state
 *       that is updated. Every status change is a conditional {@code UPDATE ... WHERE status = <expected>} whose
 *       row count is checked, so two racing operations on one listing (buy, buy / buy, cancel / cancel, expire)
 *       have exactly one winner.</li>
 *   <li>A failure rolls the whole transaction back and surfaces as {@link StorageException}. Nothing is swallowed.</li>
 *   <li>One connection is shared and every public method holds {@link #lock}; callers run it on a dedicated storage
 *       thread so the main thread never waits for the database.</li>
 *   <li>Only one server may use a database file. See the README.</li>
 * </ul>
 *
 * <p>Methods are not final so tests can inject faults at precise points.
 */
public class MarketStore implements AutoCloseable {

    /** How long SQLite waits for a lock held by another connection before giving up. */
    public static final int BUSY_TIMEOUT_MS = 5_000;

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    /** Result of {@link #insertPendingEscrow}. */
    public record EscrowStart(boolean limitReached, long listingId) {
    }

    /** Result of {@link #cancel}. */
    public record CancelOutcome(Kind kind, ListingStatus status, UUID owner, long deliveryId) {
        /** What happened. */
        public enum Kind { CANCELLED, NOT_FOUND, NOT_OWNER, NOT_ACTIVE }
    }

    /** A listing that {@link #expireDue} just expired. */
    public record Expired(long listingId, UUID owner) {
    }

    /** Result of {@link #beginPurchase}. */
    public record PurchaseStart(Kind kind, ListingStatus status, MarketTx tx) {
        /** What happened. */
        public enum Kind { STARTED, NOT_FOUND, UNAVAILABLE, EXPIRED, SELF_PURCHASE }
    }

    /** Rows moved to an "uncertain" state by {@link #recoverAfterRestart}. */
    public record RestartRecovery(int escrows, int payments, int deliveries, int fees) {
        /** @return whether anything needed attention */
        public boolean any() {
            return escrows + payments + deliveries + fees > 0;
        }
    }

    /** Everything an administrator has to look at. */
    public record Issues(List<Listing> escrows, List<MarketTx> payments, List<Delivery> deliveries,
                         List<MarketTx> fees) {
        /** @return whether there is nothing to resolve */
        public boolean isEmpty() {
            return escrows.isEmpty() && payments.isEmpty() && deliveries.isEmpty() && fees.isEmpty();
        }
    }

    private static final String LISTING_COLUMNS = "id, owner, owner_name, status, price, item_payload, item_material, "
            + "item_amount, item_description, created_at, expires_at, updated_at";
    private static final String TX_COLUMNS = "id, listing_id, buyer, buyer_name, seller, seller_name, price, fee, state, "
            + "fee_state, failure_reason, seller_notified, created_at, updated_at, completed_at";
    private static final String DELIVERY_COLUMNS = "id, owner, listing_id, tx_id, reason, status, item_payload, "
            + "item_material, item_amount, item_description, created_at, updated_at";

    /**
     * Test seam: called under the store lock before every read or transaction with the name of the public
     * operation, on the thread that runs it. May throw {@link StorageException} to simulate a database failure.
     * Production code never sets it.
     */
    @FunctionalInterface
    public interface FaultInjector {
        /**
         * @param operation name of the public {@code MarketStore} method being run
         */
        void beforeOperation(String operation);
    }

    private final Connection connection;
    private final Object lock = new Object();
    private boolean closed;
    private volatile FaultInjector faults;

    private MarketStore(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens (creating if needed) the database file and brings its schema up to date.
     *
     * @param file database file; its directory is created if missing
     * @return an open store; close it when done
     * @throws StorageException if the file cannot be opened or its schema is unusable
     */
    public static MarketStore open(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path absolute = file.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            SQLiteConfig config = new SQLiteConfig();
            config.setBusyTimeout(BUSY_TIMEOUT_MS);
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            // FULL: a committed state change survives a power cut. The write volume of an SMP is tiny.
            config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
            config.enforceForeignKeys(true);
            Connection connection = new JDBC().connect("jdbc:sqlite:" + absolute, config.toProperties());
            if (connection == null) {
                throw new StorageException("The SQLite driver rejected the database URL");
            }
            try {
                SchemaMigrator.migrate(connection);
            } catch (RuntimeException e) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
            return new MarketStore(connection);
        } catch (IOException | SQLException e) {
            throw new StorageException("Could not open the market database " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Installs (or removes, with {@code null}) a fault injector. Intended for tests only.
     *
     * @param injector the injector, or {@code null}
     */
    public void setFaultInjector(FaultInjector injector) {
        this.faults = injector;
    }

    private void injectFaults() {
        FaultInjector injector = faults;
        if (injector != null) {
            injector.beforeOperation(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                    .filter(f -> f.getDeclaringClass() == MarketStore.class)
                    .map(f -> f.getMethodName().startsWith("lambda$") ? f.getMethodName().split("\\$")[1] : f.getMethodName())
                    .filter(name -> !name.equals("transaction") && !name.equals("read") && !name.equals("injectFaults"))
                    .findFirst().orElse("unknown")));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                connection.close();
            } catch (SQLException e) {
                throw new StorageException("Could not close the market database: " + e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------- listings

    /**
     * First phase of listing: records the listing as {@link ListingStatus#PENDING_ESCROW} after checking the
     * per-player limit in the same transaction, so concurrent sells cannot exceed it. The row is not visible and
     * not buyable.
     *
     * @param maxPerPlayer maximum number of open listings (pending, active, reserved, uncertain)
     * @return the new listing id, or {@code limitReached}
     */
    public EscrowStart insertPendingEscrow(UUID owner, String ownerName, ItemSnapshot item, long priceMinor,
                                           int maxPerPlayer, long now) {
        return transaction(() -> {
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM listings WHERE owner = ? "
                    + "AND status IN ('PENDING_ESCROW','ESCROW_UNCERTAIN','ACTIVE','RESERVED')")) {
                count.setString(1, owner.toString());
                try (ResultSet rs = count.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxPerPlayer) {
                        return new EscrowStart(true, 0);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO listings (owner, owner_name, "
                    + "status, price, item_payload, item_material, item_amount, item_description, created_at, "
                    + "expires_at, updated_at) VALUES (?, ?, 'PENDING_ESCROW', ?, ?, ?, ?, ?, ?, 0, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, owner.toString());
                insert.setString(2, ownerName);
                insert.setLong(3, priceMinor);
                insert.setBytes(4, item.payload());
                insert.setString(5, item.material());
                insert.setInt(6, item.amount());
                insert.setString(7, item.description());
                insert.setLong(8, now);
                insert.setLong(9, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return new EscrowStart(false, keys.getLong(1));
                }
            }
        });
    }

    /**
     * The item could not be taken from the seller: the listing never goes on sale.
     *
     * @return whether the listing was still pending escrow
     */
    public boolean abortEscrow(long listingId, long now) {
        return transaction(() -> setListingStatus(listingId, ListingStatus.PENDING_ESCROW, ListingStatus.ABORTED, now));
    }

    /**
     * Second phase of listing, after the item has provably been removed from the seller: puts the listing on sale.
     *
     * @return whether the listing was still pending escrow
     */
    public boolean activate(long listingId, long now, long expiresAt) {
        return transaction(() -> activateInTransaction(listingId, ListingStatus.PENDING_ESCROW, now, expiresAt));
    }

    private boolean activateInTransaction(long listingId, ListingStatus from, long now, long expiresAt)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE listings SET status = 'ACTIVE', created_at = ?, expires_at = ?, updated_at = ? "
                        + "WHERE id = ? AND status = ?")) {
            update.setLong(1, now);
            update.setLong(2, expiresAt);
            update.setLong(3, now);
            update.setLong(4, listingId);
            update.setString(5, from.name());
            return update.executeUpdate() == 1;
        }
    }

    /**
     * Looks a listing up by id in any status.
     *
     * @return the listing, or empty
     */
    public Optional<Listing> findListing(long listingId) {
        return read(() -> findListingRow(listingId));
    }

    /**
     * Active listings that have not expired, newest first.
     *
     * @return one page
     */
    public List<Listing> browse(long now, int offset, int limit) {
        return read(() -> queryListings("SELECT " + LISTING_COLUMNS + " FROM listings WHERE status = 'ACTIVE' "
                + "AND expires_at > ? ORDER BY id DESC LIMIT ? OFFSET ?", now, limit, offset));
    }

    /** @return how many listings {@link #browse} spans */
    public int countBrowse(long now) {
        return read(() -> count("SELECT COUNT(*) FROM listings WHERE status = 'ACTIVE' AND expires_at > ?", now));
    }

    /**
     * A player's open listings (on sale or being bought), newest first.
     *
     * @return the listings
     */
    public List<Listing> ownerListings(UUID owner) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM listings WHERE owner = ? AND status IN ('ACTIVE','RESERVED','PENDING_ESCROW',"
                    + "'ESCROW_UNCERTAIN') ORDER BY id DESC")) {
                statement.setString(1, owner.toString());
                return listings(statement);
            }
        });
    }

    /**
     * Every listing in any status, newest first. For administrators.
     *
     * @param status only this status, or {@code null} for all
     * @return one page
     */
    public List<Listing> adminListings(ListingStatus status, int offset, int limit) {
        return read(() -> {
            String where = status == null ? "" : " WHERE status = ?";
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM listings" + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                int index = 1;
                if (status != null) {
                    statement.setString(index++, status.name());
                }
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
                return listings(statement);
            }
        });
    }

    /**
     * Cancels a listing exactly once. In the same transaction the listing becomes {@code CANCELLED} and a
     * delivery for the owner is created, so the item is owed to the owner from that instant on.
     *
     * @param admin whether the requester may cancel other players' listings
     * @return the outcome
     */
    public CancelOutcome cancel(long listingId, UUID requester, boolean admin, long now) {
        return transaction(() -> {
            Optional<Listing> found = findListingRow(listingId);
            if (found.isEmpty()) {
                return new CancelOutcome(CancelOutcome.Kind.NOT_FOUND, null, null, 0);
            }
            Listing listing = found.get();
            if (!listing.owner().equals(requester) && !admin) {
                // Do not reveal anything about a listing the requester does not own.
                return new CancelOutcome(CancelOutcome.Kind.NOT_OWNER, null, listing.owner(), 0);
            }
            if (listing.status() != ListingStatus.ACTIVE
                    || !setListingStatus(listingId, ListingStatus.ACTIVE, ListingStatus.CANCELLED, now)) {
                return new CancelOutcome(CancelOutcome.Kind.NOT_ACTIVE, listing.status(), listing.owner(), 0);
            }
            long deliveryId = insertDeliveryFromListing(listingId, listing.owner(), 0, DeliveryReason.CANCELLED, now);
            return new CancelOutcome(CancelOutcome.Kind.CANCELLED, ListingStatus.CANCELLED, listing.owner(), deliveryId);
        });
    }

    /**
     * Expires every active listing whose expiry time has passed, creating the owner's delivery in the same
     * transaction. Idempotent.
     *
     * @param max upper bound per call
     * @return the listings expired by this call
     */
    public List<Expired> expireDue(long now, int max) {
        return transaction(() -> {
            List<Expired> expired = new ArrayList<>();
            try (PreparedStatement due = connection.prepareStatement(
                    "SELECT id, owner FROM listings WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY id LIMIT ?")) {
                due.setLong(1, now);
                due.setInt(2, max);
                try (ResultSet rs = due.executeQuery()) {
                    while (rs.next()) {
                        expired.add(new Expired(rs.getLong(1), UUID.fromString(rs.getString(2))));
                    }
                }
            }
            for (Expired e : expired) {
                if (!setListingStatus(e.listingId(), ListingStatus.ACTIVE, ListingStatus.EXPIRED, now)) {
                    throw new StorageException("Listing " + e.listingId() + " changed during expiry");
                }
                insertDeliveryFromListing(e.listingId(), e.owner(), 0, DeliveryReason.EXPIRED, now);
            }
            return expired;
        });
    }

    // ---------------------------------------------------------------- purchases

    /**
     * Starts a purchase: atomically reserves the listing and records the payment intent
     * ({@link TxState#PAYMENT_PENDING}). Of any number of concurrent buyers exactly one gets
     * {@link PurchaseStart.Kind#STARTED}.
     *
     * @param feePercentHundredths fee in hundredths of a percent, fixed for this transaction
     * @return the outcome; the transaction row when started
     */
    public PurchaseStart beginPurchase(long listingId, UUID buyer, String buyerName, boolean allowSelf,
                                       int feePercentHundredths, long now) {
        return transaction(() -> {
            Optional<Listing> found = findListingRow(listingId);
            if (found.isEmpty()) {
                return new PurchaseStart(PurchaseStart.Kind.NOT_FOUND, null, null);
            }
            Listing listing = found.get();
            if (listing.status() != ListingStatus.ACTIVE) {
                return new PurchaseStart(PurchaseStart.Kind.UNAVAILABLE, listing.status(), null);
            }
            if (listing.expiresAt() <= now) {
                return new PurchaseStart(PurchaseStart.Kind.EXPIRED, listing.status(), null);
            }
            if (listing.owner().equals(buyer) && !allowSelf) {
                return new PurchaseStart(PurchaseStart.Kind.SELF_PURCHASE, listing.status(), null);
            }
            if (!setListingStatus(listingId, ListingStatus.ACTIVE, ListingStatus.RESERVED, now)) {
                return new PurchaseStart(PurchaseStart.Kind.UNAVAILABLE, listing.status(), null);
            }
            // Buying your own listing moves no money, so it carries no fee.
            long fee = listing.owner().equals(buyer) ? 0 : Price.fee(listing.priceMinor(), feePercentHundredths);
            long txId;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO market_tx (listing_id, buyer, "
                    + "buyer_name, seller, seller_name, price, fee, state, fee_state, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PAYMENT_PENDING', ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, listingId);
                insert.setString(2, buyer.toString());
                insert.setString(3, buyerName);
                insert.setString(4, listing.owner().toString());
                insert.setString(5, listing.ownerName());
                insert.setLong(6, listing.priceMinor());
                insert.setLong(7, fee);
                insert.setString(8, fee > 0 ? FeeState.PENDING.name() : FeeState.NONE.name());
                insert.setLong(9, now);
                insert.setLong(10, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    txId = keys.getLong(1);
                }
            }
            return new PurchaseStart(PurchaseStart.Kind.STARTED, ListingStatus.RESERVED, findTxRow(txId).orElseThrow());
        });
    }

    /**
     * Records that SMP-Economy confirmed the payment: the transaction moves to {@code DELIVERY_PENDING}, the
     * listing to {@code SOLD}, and the buyer's delivery is created, all in one transaction. Idempotent: a
     * transaction that is no longer awaiting payment is left alone.
     *
     * @return whether this call applied the confirmation
     */
    public boolean confirmPayment(long txId, long now) {
        return transaction(() -> confirmPaymentInTransaction(txId, now, TxState.PAYMENT_PENDING, TxState.PAYMENT_UNCERTAIN));
    }

    private boolean confirmPaymentInTransaction(long txId, long now, TxState... allowedFrom) throws SQLException {
        Optional<MarketTx> found = findTxRow(txId);
        if (found.isEmpty() || !contains(allowedFrom, found.get().state())) {
            return false;
        }
        MarketTx tx = found.get();
        try (PreparedStatement update = connection.prepareStatement("UPDATE market_tx SET state = 'DELIVERY_PENDING', "
                + "completed_at = ?, updated_at = ? WHERE id = ? AND state = ?")) {
            update.setLong(1, now);
            update.setLong(2, now);
            update.setLong(3, txId);
            update.setString(4, tx.state().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        }
        if (!setListingStatus(tx.listingId(), ListingStatus.RESERVED, ListingStatus.SOLD, now)) {
            throw new StorageException("Listing " + tx.listingId() + " of transaction " + txId + " is not reserved");
        }
        insertDeliveryFromListing(tx.listingId(), tx.buyer(), txId, DeliveryReason.PURCHASED, now);
        return true;
    }

    /**
     * Records that the payment definitively did not happen: the transaction fails and the listing is reopened.
     *
     * @param fromUncertain whether a {@code PAYMENT_UNCERTAIN} transaction may be failed (administrators only)
     * @return whether this call applied the failure
     */
    public boolean failPayment(long txId, String reason, long now, boolean fromUncertain) {
        return transaction(() -> failPaymentInTransaction(txId, reason, now,
                fromUncertain ? new TxState[] {TxState.PAYMENT_PENDING, TxState.PAYMENT_UNCERTAIN}
                        : new TxState[] {TxState.PAYMENT_PENDING}));
    }

    private boolean failPaymentInTransaction(long txId, String reason, long now, TxState[] allowedFrom)
            throws SQLException {
        Optional<MarketTx> found = findTxRow(txId);
        if (found.isEmpty() || !contains(allowedFrom, found.get().state())) {
            return false;
        }
        MarketTx tx = found.get();
        try (PreparedStatement update = connection.prepareStatement("UPDATE market_tx SET state = 'FAILED', "
                + "failure_reason = ?, fee_state = 'NONE', updated_at = ? WHERE id = ? AND state = ?")) {
            update.setString(1, reason);
            update.setLong(2, now);
            update.setLong(3, txId);
            update.setString(4, tx.state().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        }
        setListingStatus(tx.listingId(), ListingStatus.RESERVED, ListingStatus.ACTIVE, now);
        return true;
    }

    /**
     * The outcome of the payment call is unknown.
     *
     * @return whether the transaction was still {@code PAYMENT_PENDING}
     */
    public boolean markPaymentUncertain(long txId, long now) {
        return transaction(() -> setTxState(txId, TxState.PAYMENT_PENDING, TxState.PAYMENT_UNCERTAIN, now));
    }

    /**
     * Starts the fee withdrawal. Only a confirmed transaction whose fee is {@code PENDING} qualifies.
     *
     * @return whether the fee moved to {@code COLLECTING}
     */
    public boolean beginFee(long txId, long now) {
        return transaction(() -> setFeeState(txId, FeeState.PENDING, FeeState.COLLECTING, now));
    }

    /**
     * Records the result of a fee withdrawal: {@code COLLECTED}, {@code PENDING} (definitive failure, safe to
     * retry) or {@code UNCERTAIN}.
     *
     * @return whether the fee was still {@code COLLECTING}
     */
    public boolean finishFee(long txId, FeeState result, long now) {
        if (result != FeeState.COLLECTED && result != FeeState.PENDING && result != FeeState.UNCERTAIN) {
            throw new IllegalArgumentException("not a fee result: " + result);
        }
        return transaction(() -> setFeeState(txId, FeeState.COLLECTING, result, now));
    }

    /**
     * Confirmed transactions whose fee still has to be withdrawn.
     *
     * @return up to {@code limit} transactions, oldest first
     */
    public List<MarketTx> feesPending(int limit) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + TX_COLUMNS + " FROM market_tx "
                    + "WHERE fee_state = 'PENDING' AND state IN ('DELIVERY_PENDING','COMPLETED') ORDER BY id LIMIT ?")) {
                statement.setInt(1, limit);
                return txs(statement);
            }
        });
    }

    /**
     * Every purchase attempt of a listing, oldest first.
     *
     * @return the transactions
     */
    public List<MarketTx> txsForListing(long listingId) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + TX_COLUMNS
                    + " FROM market_tx WHERE listing_id = ? ORDER BY id")) {
                statement.setLong(1, listingId);
                return txs(statement);
            }
        });
    }

    /**
     * Looks a transaction up by id.
     *
     * @return the transaction, or empty
     */
    public Optional<MarketTx> findTx(long txId) {
        return read(() -> findTxRow(txId));
    }

    /**
     * A player's completed purchases and sales (payment confirmed), newest first.
     *
     * @return one page
     */
    public List<MarketTx> history(UUID player, int offset, int limit) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + TX_COLUMNS + " FROM market_tx "
                    + "WHERE (buyer = ? OR seller = ?) AND state IN ('DELIVERY_PENDING','COMPLETED') "
                    + "ORDER BY id DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, player.toString());
                statement.setString(2, player.toString());
                statement.setInt(3, limit);
                statement.setInt(4, offset);
                return txs(statement);
            }
        });
    }

    /** @return how many history entries the player has */
    public int countHistory(UUID player) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM market_tx WHERE "
                    + "(buyer = ? OR seller = ?) AND state IN ('DELIVERY_PENDING','COMPLETED')")) {
                statement.setString(1, player.toString());
                statement.setString(2, player.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /**
     * Sales of this seller that the seller has not been told about yet.
     *
     * @return the transactions, oldest first
     */
    public List<MarketTx> unnotifiedSales(UUID seller) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT " + TX_COLUMNS + " FROM market_tx "
                    + "WHERE seller = ? AND seller_notified = 0 AND state IN ('DELIVERY_PENDING','COMPLETED') "
                    + "ORDER BY id LIMIT 50")) {
                statement.setString(1, seller.toString());
                return txs(statement);
            }
        });
    }

    /** Remembers that the seller has been told about these sales. */
    public void markSellerNotified(Collection<Long> txIds, long now) {
        transaction(() -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE market_tx SET seller_notified = 1, updated_at = ? WHERE id = ?")) {
                for (long id : txIds) {
                    update.setLong(1, now);
                    update.setLong(2, id);
                    update.executeUpdate();
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- deliveries

    /**
     * Claims up to {@code limit} pending deliveries of {@code owner} for one delivery attempt. A claimed delivery
     * is {@code DELIVERING} and cannot be claimed again, so repeated or concurrent attempts never hand out the
     * same item twice.
     *
     * @param token unique per attempt; later passed to {@link #finishDeliveries}
     * @return the deliveries claimed by this call
     */
    public List<Delivery> claimDeliveries(UUID owner, String token, int limit, long now) {
        return transaction(() -> {
            List<Delivery> claimed;
            try (PreparedStatement select = connection.prepareStatement("SELECT " + DELIVERY_COLUMNS
                    + " FROM deliveries WHERE owner = ? AND status = 'PENDING' ORDER BY id LIMIT ?")) {
                select.setString(1, owner.toString());
                select.setInt(2, limit);
                claimed = deliveries(select);
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE deliveries SET status = 'DELIVERING', "
                    + "claim_token = ?, updated_at = ? WHERE id = ? AND status = 'PENDING'")) {
                for (Delivery d : claimed) {
                    update.setString(1, token);
                    update.setLong(2, now);
                    update.setLong(3, d.id());
                    if (update.executeUpdate() != 1) {
                        throw new StorageException("Delivery " + d.id() + " changed while being claimed");
                    }
                }
            }
            return claimed;
        });
    }

    /**
     * Concludes a delivery attempt. Delivered items become {@code DELIVERED} (and their purchase
     * {@code COMPLETED}); the others return to {@code PENDING}. Only deliveries still claimed by {@code token}
     * are touched, so a late or repeated call is harmless.
     */
    public void finishDeliveries(String token, Collection<Long> delivered, Collection<Long> released, long now) {
        transaction(() -> {
            try (PreparedStatement done = connection.prepareStatement("UPDATE deliveries SET status = 'DELIVERED', "
                    + "delivered_at = ?, updated_at = ? WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?");
                 PreparedStatement complete = connection.prepareStatement("UPDATE market_tx SET state = 'COMPLETED', "
                         + "updated_at = ? WHERE id = (SELECT tx_id FROM deliveries WHERE id = ?) "
                         + "AND state = 'DELIVERY_PENDING'")) {
                for (long id : delivered) {
                    done.setLong(1, now);
                    done.setLong(2, now);
                    done.setLong(3, id);
                    done.setString(4, token);
                    if (done.executeUpdate() == 1) {
                        complete.setLong(1, now);
                        complete.setLong(2, id);
                        complete.executeUpdate();
                    }
                }
            }
            try (PreparedStatement back = connection.prepareStatement("UPDATE deliveries SET status = 'PENDING', "
                    + "claim_token = NULL, updated_at = ? WHERE id = ? AND status = 'DELIVERING' AND claim_token = ?")) {
                for (long id : released) {
                    back.setLong(1, now);
                    back.setLong(2, id);
                    back.setString(3, token);
                    back.executeUpdate();
                }
            }
            return null;
        });
    }

    /** @return how many items wait for the player */
    public int pendingDeliveryCount(UUID owner) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM deliveries WHERE owner = ? AND status = 'PENDING'")) {
                statement.setString(1, owner.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    /**
     * Looks a delivery up by id.
     *
     * @return the delivery, or empty
     */
    public Optional<Delivery> findDelivery(long deliveryId) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + DELIVERY_COLUMNS + " FROM deliveries WHERE id = ?")) {
                statement.setLong(1, deliveryId);
                return deliveries(statement).stream().findFirst();
            }
        });
    }

    /**
     * Looks the delivery of a listing up (there is at most one).
     *
     * @return the delivery, or empty
     */
    public Optional<Delivery> findDeliveryForListing(long listingId) {
        return read(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + DELIVERY_COLUMNS + " FROM deliveries WHERE listing_id = ?")) {
                statement.setLong(1, listingId);
                return deliveries(statement).stream().findFirst();
            }
        });
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Startup recovery. At the start of a server run nothing is in flight, so every row still in an "in flight"
     * state was interrupted by the previous run and its true outcome cannot be proven. Such rows are moved to an
     * explicit {@code *_UNCERTAIN} state. Nothing is paid, refunded, delivered or discarded. Idempotent: running it
     * again finds nothing left to move.
     *
     * <p>Must only be called before any market operation of this run has started.
     */
    public RestartRecovery recoverAfterRestart(long now) {
        return transaction(() -> {
            int escrows = update("UPDATE listings SET status = 'ESCROW_UNCERTAIN', updated_at = ? "
                    + "WHERE status = 'PENDING_ESCROW'", now);
            int payments = update("UPDATE market_tx SET state = 'PAYMENT_UNCERTAIN', updated_at = ? "
                    + "WHERE state = 'PAYMENT_PENDING'", now);
            int deliveries = update("UPDATE deliveries SET status = 'UNCERTAIN', updated_at = ? "
                    + "WHERE status = 'DELIVERING'", now);
            int fees = update("UPDATE market_tx SET fee_state = 'UNCERTAIN', updated_at = ? "
                    + "WHERE fee_state = 'COLLECTING'", now);
            return new RestartRecovery(escrows, payments, deliveries, fees);
        });
    }

    /**
     * Everything that needs an administrator.
     *
     * @return the unresolved rows
     */
    public Issues issues() {
        return read(() -> {
            List<Listing> escrows;
            try (PreparedStatement s = connection.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM listings WHERE status IN ('ESCROW_UNCERTAIN','PENDING_ESCROW') ORDER BY id")) {
                escrows = listings(s);
            }
            List<MarketTx> payments;
            try (PreparedStatement s = connection.prepareStatement("SELECT " + TX_COLUMNS
                    + " FROM market_tx WHERE state IN ('PAYMENT_UNCERTAIN','PAYMENT_PENDING') ORDER BY id")) {
                payments = txs(s);
            }
            List<Delivery> deliveries;
            try (PreparedStatement s = connection.prepareStatement("SELECT " + DELIVERY_COLUMNS
                    + " FROM deliveries WHERE status = 'UNCERTAIN' ORDER BY id")) {
                deliveries = deliveries(s);
            }
            List<MarketTx> fees;
            try (PreparedStatement s = connection.prepareStatement("SELECT " + TX_COLUMNS
                    + " FROM market_tx WHERE fee_state = 'UNCERTAIN' ORDER BY id")) {
                fees = txs(s);
            }
            return new Issues(escrows, payments, deliveries, fees);
        });
    }

    // ---------------------------------------------------------------- administrative resolutions

    /**
     * An administrator states the true outcome of an uncertain payment. {@code paid} continues the purchase
     * (the buyer gets the item); otherwise the transaction fails and the listing reopens. Audited.
     *
     * @return whether the transaction was {@code PAYMENT_UNCERTAIN} and was changed
     */
    public boolean resolvePayment(long txId, boolean paid, String actor, String reason, long now) {
        return transaction(() -> {
            Optional<MarketTx> tx = findTxRow(txId);
            if (tx.isEmpty() || tx.get().state() != TxState.PAYMENT_UNCERTAIN) {
                return false;
            }
            boolean applied = paid
                    ? confirmPaymentInTransaction(txId, now, TxState.PAYMENT_UNCERTAIN)
                    : failPaymentInTransaction(txId, "Resolved by administrator as not paid: " + reason, now,
                            new TxState[] {TxState.PAYMENT_UNCERTAIN});
            if (applied) {
                audit(now, actor, paid ? "RESOLVE_PAYMENT_PAID" : "RESOLVE_PAYMENT_UNPAID", tx.get().listingId(), txId,
                        0, reason);
            }
            return applied;
        });
    }

    /**
     * An administrator states what happened to an uncertain delivery: {@code delivered} marks it delivered,
     * otherwise it returns to {@code PENDING} so the owner can collect it. Audited.
     *
     * @return whether the delivery was {@code UNCERTAIN} and was changed
     */
    public boolean resolveDelivery(long deliveryId, boolean delivered, String actor, String reason, long now) {
        return transaction(() -> {
            Optional<Delivery> found = findDeliveryRow(deliveryId);
            if (found.isEmpty() || found.get().status() != DeliveryStatus.UNCERTAIN) {
                return false;
            }
            Delivery d = found.get();
            try (PreparedStatement update = connection.prepareStatement(delivered
                    ? "UPDATE deliveries SET status = 'DELIVERED', delivered_at = ?, updated_at = ? WHERE id = ? "
                            + "AND status = 'UNCERTAIN'"
                    : "UPDATE deliveries SET status = 'PENDING', claim_token = NULL, delivered_at = 0, updated_at = ? "
                            + "WHERE id = ? AND status = 'UNCERTAIN'")) {
                int i = 1;
                if (delivered) {
                    update.setLong(i++, now);
                }
                update.setLong(i++, now);
                update.setLong(i, deliveryId);
                if (update.executeUpdate() != 1) {
                    return false;
                }
            }
            if (delivered && d.txId() > 0) {
                try (PreparedStatement complete = connection.prepareStatement("UPDATE market_tx SET state = "
                        + "'COMPLETED', updated_at = ? WHERE id = ? AND state = 'DELIVERY_PENDING'")) {
                    complete.setLong(1, now);
                    complete.setLong(2, d.txId());
                    complete.executeUpdate();
                }
            }
            audit(now, actor, delivered ? "RESOLVE_DELIVERY_DELIVERED" : "RESOLVE_DELIVERY_RETRY", d.listingId(),
                    d.txId(), deliveryId, reason);
            return true;
        });
    }

    /**
     * An administrator decides an uncertain escrow. {@code ACTIVATE}: the item really left the seller, so the listing
     * goes on sale. {@code RETURN}: the item left the seller and is delivered back. {@code ABORT}: the item never left
     * the seller, so the listing is closed and no item is created. Nothing is deleted. Audited.
     *
     * @return whether the listing was {@code ESCROW_UNCERTAIN} and was changed
     */
    public boolean resolveEscrow(long listingId, EscrowResolution resolution, long expiresAt, String actor,
                                 String reason, long now) {
        return transaction(() -> {
            Optional<Listing> found = findListingRow(listingId);
            if (found.isEmpty() || found.get().status() != ListingStatus.ESCROW_UNCERTAIN) {
                return false;
            }
            Listing listing = found.get();
            switch (resolution) {
                case ACTIVATE -> {
                    if (!activateInTransaction(listingId, ListingStatus.ESCROW_UNCERTAIN, now, expiresAt)) {
                        return false;
                    }
                }
                case RETURN -> {
                    if (!setListingStatus(listingId, ListingStatus.ESCROW_UNCERTAIN, ListingStatus.CANCELLED, now)) {
                        return false;
                    }
                    insertDeliveryFromListing(listingId, listing.owner(), 0, DeliveryReason.ESCROW_RETURN, now);
                }
                case ABORT -> {
                    if (!setListingStatus(listingId, ListingStatus.ESCROW_UNCERTAIN, ListingStatus.ABORTED, now)) {
                        return false;
                    }
                }
            }
            audit(now, actor, "RESOLVE_ESCROW_" + resolution.name(), listingId, 0, 0, reason);
            return true;
        });
    }

    /**
     * An administrator states what happened to an uncertain fee withdrawal: {@code collected} or back to
     * {@code PENDING} so it is attempted again. Audited.
     *
     * @return whether the fee was {@code UNCERTAIN} and was changed
     */
    public boolean resolveFee(long txId, boolean collected, String actor, String reason, long now) {
        return transaction(() -> {
            Optional<MarketTx> tx = findTxRow(txId);
            if (tx.isEmpty()
                    || !setFeeState(txId, FeeState.UNCERTAIN, collected ? FeeState.COLLECTED : FeeState.PENDING, now)) {
                return false;
            }
            audit(now, actor, collected ? "RESOLVE_FEE_COLLECTED" : "RESOLVE_FEE_RETRY", tx.get().listingId(), txId, 0,
                    reason);
            return true;
        });
    }

    /**
     * Records an administrative action that changes no market state itself (for example an administrative cancel).
     */
    public void audit(String actor, String action, long listingId, long txId, long deliveryId, String reason, long now) {
        transaction(() -> {
            audit(now, actor, action, listingId, txId, deliveryId, reason);
            return null;
        });
    }

    /** @return the number of audit rows (for diagnostics and tests) */
    public int auditCount() {
        return read(() -> count("SELECT COUNT(*) FROM admin_actions", new Object[0]));
    }

    /**
     * Returns the audit trail, oldest first.
     *
     * @return rows of {@code actor|action|listingId|txId|deliveryId|reason}
     */
    public List<String> auditTrail() {
        return read(() -> {
            List<String> rows = new ArrayList<>();
            try (Statement s = connection.createStatement();
                 ResultSet rs = s.executeQuery("SELECT actor, action, listing_id, tx_id, delivery_id, reason "
                         + "FROM admin_actions ORDER BY id")) {
                while (rs.next()) {
                    rows.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getLong(3) + "|" + rs.getLong(4) + "|"
                            + rs.getLong(5) + "|" + rs.getString(6));
                }
            }
            return rows;
        });
    }

    // ---------------------------------------------------------------- internals

    private void audit(long now, String actor, String action, long listingId, long txId, long deliveryId, String reason)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO admin_actions (at, actor, action, "
                + "listing_id, tx_id, delivery_id, reason) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setLong(1, now);
            insert.setString(2, actor);
            insert.setString(3, action);
            insert.setLong(4, listingId);
            insert.setLong(5, txId);
            insert.setLong(6, deliveryId);
            insert.setString(7, reason);
            insert.executeUpdate();
        }
    }

    private static boolean contains(TxState[] states, TxState state) {
        for (TxState s : states) {
            if (s == state) {
                return true;
            }
        }
        return false;
    }

    private boolean setListingStatus(long id, ListingStatus from, ListingStatus to, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE listings SET status = ?, updated_at = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setLong(2, now);
            update.setLong(3, id);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        }
    }

    private boolean setTxState(long id, TxState from, TxState to, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE market_tx SET state = ?, updated_at = ? WHERE id = ? AND state = ?")) {
            update.setString(1, to.name());
            update.setLong(2, now);
            update.setLong(3, id);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        }
    }

    private boolean setFeeState(long id, FeeState from, FeeState to, long now) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE market_tx SET fee_state = ?, updated_at = ? "
                + "WHERE id = ? AND fee_state = ? AND state IN ('DELIVERY_PENDING','COMPLETED')")) {
            update.setString(1, to.name());
            update.setLong(2, now);
            update.setLong(3, id);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        }
    }

    private int update(String sql, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        }
    }

    /** Copies the listing's item into the single delivery row the listing may ever have. */
    private long insertDeliveryFromListing(long listingId, UUID owner, long txId, DeliveryReason reason, long now)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO deliveries (owner, listing_id, tx_id, "
                + "reason, status, item_payload, item_material, item_amount, item_description, created_at, updated_at) "
                + "SELECT ?, id, ?, ?, 'PENDING', item_payload, item_material, item_amount, item_description, ?, ? "
                + "FROM listings WHERE id = ?", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, owner.toString());
            insert.setLong(2, txId);
            insert.setString(3, reason.name());
            insert.setLong(4, now);
            insert.setLong(5, now);
            insert.setLong(6, listingId);
            if (insert.executeUpdate() != 1) {
                throw new StorageException("Could not create the delivery of listing " + listingId);
            }
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private Optional<Listing> findListingRow(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + LISTING_COLUMNS + " FROM listings WHERE id = ?")) {
            statement.setLong(1, id);
            return listings(statement).stream().findFirst();
        }
    }

    private Optional<MarketTx> findTxRow(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + TX_COLUMNS + " FROM market_tx WHERE id = ?")) {
            statement.setLong(1, id);
            return txs(statement).stream().findFirst();
        }
    }

    private Optional<Delivery> findDeliveryRow(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + DELIVERY_COLUMNS + " FROM deliveries WHERE id = ?")) {
            statement.setLong(1, id);
            return deliveries(statement).stream().findFirst();
        }
    }

    private List<Listing> queryListings(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return listings(statement);
        }
    }

    private int count(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            Object p = parameters[i];
            if (p instanceof Long l) {
                statement.setLong(i + 1, l);
            } else if (p instanceof Integer n) {
                statement.setInt(i + 1, n);
            } else {
                statement.setString(i + 1, String.valueOf(p));
            }
        }
    }

    private static List<Listing> listings(PreparedStatement statement) throws SQLException {
        List<Listing> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                        ListingStatus.valueOf(rs.getString(4)), rs.getLong(5),
                        new ItemSnapshot(rs.getBytes(6), rs.getString(7), rs.getInt(8), rs.getString(9)),
                        rs.getLong(10), rs.getLong(11), rs.getLong(12)));
            }
        }
        return result;
    }

    private static List<MarketTx> txs(PreparedStatement statement) throws SQLException {
        List<MarketTx> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(new MarketTx(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)), rs.getString(4),
                        UUID.fromString(rs.getString(5)), rs.getString(6), rs.getLong(7), rs.getLong(8),
                        TxState.valueOf(rs.getString(9)), FeeState.valueOf(rs.getString(10)), rs.getString(11),
                        rs.getInt(12) != 0, rs.getLong(13), rs.getLong(14), rs.getLong(15)));
            }
        }
        return result;
    }

    private static List<Delivery> deliveries(PreparedStatement statement) throws SQLException {
        List<Delivery> result = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                result.add(new Delivery(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getLong(4),
                        DeliveryReason.valueOf(rs.getString(5)), DeliveryStatus.valueOf(rs.getString(6)),
                        new ItemSnapshot(rs.getBytes(7), rs.getString(8), rs.getInt(9), rs.getString(10)),
                        rs.getLong(11), rs.getLong(12)));
            }
        }
        return result;
    }

    private <T> T read(Work<T> work) {
        synchronized (lock) {
            requireOpen();
            injectFaults();
            try {
                return work.run();
            } catch (SQLException e) {
                throw new StorageException("Database read failed: " + e.getMessage(), e);
            }
        }
    }

    private <T> T transaction(Work<T> work) {
        synchronized (lock) {
            requireOpen();
            injectFaults();
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                try {
                    T result = work.run();
                    statement.execute("COMMIT");
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        statement.execute("ROLLBACK");
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
            } catch (SQLException e) {
                throw new StorageException("Database transaction failed: " + e.getMessage(), e);
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new StorageException("The market database is closed");
        }
    }
}
