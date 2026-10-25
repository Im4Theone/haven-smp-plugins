package net.kaloyan.smpmarket.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Brings the database schema up to {@link #LATEST_VERSION}. The version is stored in SQLite's
 * {@code PRAGMA user_version}. Each step runs in its own {@code BEGIN IMMEDIATE} transaction together with the
 * version bump, so a failed step leaves the previous version intact. A database written by a newer SMP-Market is
 * refused rather than opened and possibly damaged.
 *
 * <p>To change the schema in a future release: append a new list to {@link #STEPS}; never edit an existing one.
 * The schema is documented in docs/TRANSACTION_DESIGN.md.
 */
final class SchemaMigrator {

    /** Schema version this build reads and writes. */
    static final int LATEST_VERSION = 1;

    private static final String LISTING_STATUSES =
            "'PENDING_ESCROW','ESCROW_UNCERTAIN','ABORTED','ACTIVE','RESERVED','SOLD','CANCELLED','EXPIRED'";
    private static final String TX_STATES =
            "'PAYMENT_PENDING','PAYMENT_UNCERTAIN','DELIVERY_PENDING','COMPLETED','FAILED'";
    private static final String FEE_STATES = "'NONE','PENDING','COLLECTING','COLLECTED','UNCERTAIN'";
    private static final String DELIVERY_STATUSES = "'PENDING','DELIVERING','DELIVERED','UNCERTAIN'";
    private static final String DELIVERY_REASONS = "'PURCHASED','CANCELLED','EXPIRED','ESCROW_RETURN'";

    /** Step {@code n} (zero-based index n-1) migrates from version n-1 to n. */
    private static final List<List<String>> STEPS = List.of(
            // Version 1. Money is in minor units (hundredths); times are UTC epoch milliseconds; UUIDs are text.
            List.of(
                    // The listing row is the durable source of truth for who owns the item (see ListingStatus).
                    "CREATE TABLE listings ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "owner TEXT NOT NULL, "
                            + "owner_name TEXT NOT NULL, "
                            + "status TEXT NOT NULL CHECK (status IN (" + LISTING_STATUSES + ")), "
                            + "price INTEGER NOT NULL CHECK (price > 0), "
                            + "item_payload BLOB NOT NULL, "
                            + "item_material TEXT NOT NULL, "
                            + "item_amount INTEGER NOT NULL CHECK (item_amount > 0), "
                            + "item_description TEXT NOT NULL, "
                            + "created_at INTEGER NOT NULL, "
                            + "expires_at INTEGER NOT NULL, "
                            + "updated_at INTEGER NOT NULL)",
                    "CREATE INDEX listings_status_expiry ON listings (status, expires_at)",
                    "CREATE INDEX listings_owner_status ON listings (owner, status)",
                    // One row per purchase attempt. Never deleted; it is the audit trail.
                    "CREATE TABLE market_tx ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "listing_id INTEGER NOT NULL REFERENCES listings(id), "
                            + "buyer TEXT NOT NULL, "
                            + "buyer_name TEXT NOT NULL, "
                            + "seller TEXT NOT NULL, "
                            + "seller_name TEXT NOT NULL, "
                            + "price INTEGER NOT NULL CHECK (price > 0), "
                            + "fee INTEGER NOT NULL CHECK (fee >= 0 AND fee <= price), "
                            + "state TEXT NOT NULL CHECK (state IN (" + TX_STATES + ")), "
                            + "fee_state TEXT NOT NULL CHECK (fee_state IN (" + FEE_STATES + ")), "
                            + "failure_reason TEXT, "
                            + "seller_notified INTEGER NOT NULL DEFAULT 0, "
                            + "created_at INTEGER NOT NULL, "
                            + "updated_at INTEGER NOT NULL, "
                            + "completed_at INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX market_tx_listing ON market_tx (listing_id)",
                    "CREATE INDEX market_tx_buyer ON market_tx (buyer, state)",
                    "CREATE INDEX market_tx_seller ON market_tx (seller, state)",
                    // At most one purchase may be unresolved per listing: a second concurrent buyer cannot get in.
                    "CREATE UNIQUE INDEX market_tx_one_open_per_listing ON market_tx (listing_id) "
                            + "WHERE state IN ('PAYMENT_PENDING','PAYMENT_UNCERTAIN')",
                    // Items owed to players. The unique index on listing_id means a listing can never yield two items.
                    "CREATE TABLE deliveries ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "owner TEXT NOT NULL, "
                            + "listing_id INTEGER NOT NULL REFERENCES listings(id), "
                            + "tx_id INTEGER NOT NULL DEFAULT 0, "
                            + "reason TEXT NOT NULL CHECK (reason IN (" + DELIVERY_REASONS + ")), "
                            + "status TEXT NOT NULL CHECK (status IN (" + DELIVERY_STATUSES + ")), "
                            + "claim_token TEXT, "
                            + "item_payload BLOB NOT NULL, "
                            + "item_material TEXT NOT NULL, "
                            + "item_amount INTEGER NOT NULL, "
                            + "item_description TEXT NOT NULL, "
                            + "created_at INTEGER NOT NULL, "
                            + "updated_at INTEGER NOT NULL, "
                            + "delivered_at INTEGER NOT NULL DEFAULT 0)",
                    "CREATE UNIQUE INDEX deliveries_one_per_listing ON deliveries (listing_id)",
                    "CREATE INDEX deliveries_owner_status ON deliveries (owner, status)",
                    // Every administrative recovery action.
                    "CREATE TABLE admin_actions ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "at INTEGER NOT NULL, "
                            + "actor TEXT NOT NULL, "
                            + "action TEXT NOT NULL, "
                            + "listing_id INTEGER NOT NULL DEFAULT 0, "
                            + "tx_id INTEGER NOT NULL DEFAULT 0, "
                            + "delivery_id INTEGER NOT NULL DEFAULT 0, "
                            + "reason TEXT NOT NULL)"));

    private SchemaMigrator() {
    }

    /**
     * Migrates {@code connection}'s database to the latest version.
     *
     * @param connection an open connection in auto-commit mode
     * @throws StorageException if the database is newer than this build or a step fails
     */
    static void migrate(Connection connection) {
        try {
            int found = readVersion(connection);
            if (found > LATEST_VERSION) {
                throw newerThanSupported(found);
            }
            for (int target = found + 1; target <= LATEST_VERSION; target++) {
                applyStep(connection, target);
            }
        } catch (SQLException e) {
            throw new StorageException("Could not prepare the database schema: " + e.getMessage(), e);
        }
    }

    private static void applyStep(Connection connection, int target) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                // Another process may have migrated while we waited for the write lock.
                int current = readVersion(connection);
                if (current >= target) {
                    if (current > LATEST_VERSION) {
                        throw newerThanSupported(current);
                    }
                    statement.execute("ROLLBACK");
                    return;
                }
                for (String sql : STEPS.get(target - 1)) {
                    statement.execute(sql);
                }
                statement.execute("PRAGMA user_version = " + target);
                statement.execute("COMMIT");
            } catch (SQLException | RuntimeException e) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }

    static int readVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static StorageException newerThanSupported(int found) {
        return new StorageException("The market database has schema version " + found
                + " but this SMP-Market only understands up to version " + LATEST_VERSION
                + ". It was probably written by a newer release; refusing to open it so nothing is damaged."
                + " Update SMP-Market or restore an older backup.");
    }
}
