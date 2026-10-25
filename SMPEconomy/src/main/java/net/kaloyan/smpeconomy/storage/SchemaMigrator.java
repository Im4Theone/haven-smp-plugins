package net.kaloyan.smpeconomy.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import net.kaloyan.smpeconomy.money.Money;

/**
 * Brings the database schema up to {@link #LATEST_VERSION}. The version is stored in SQLite's
 * {@code PRAGMA user_version}. Each step runs in its own {@code BEGIN IMMEDIATE} transaction together with
 * the version bump, so a failed step leaves the previous version intact. A database written by a newer
 * SMP-Economy is refused rather than opened and possibly damaged.
 *
 * <p>To change the schema in a future release: append a new list to {@link #STEPS}; never edit an existing one.
 */
final class SchemaMigrator {

    /** Schema version this build reads and writes. */
    static final int LATEST_VERSION = 1;

    /** Step {@code n} (zero-based index n-1) migrates from version n-1 to n. */
    private static final List<List<String>> STEPS = List.of(
            // Version 1: accounts. Balances are minor units (hundredths); the CHECK is a last line of defence
            // behind the Java-side validation. Names are unique case-insensitively among accounts that have one.
            List.of(
                    "CREATE TABLE accounts ("
                            + "uuid TEXT PRIMARY KEY NOT NULL, "
                            + "balance INTEGER NOT NULL CHECK (balance >= 0 AND balance <= " + Money.HARD_MAX_MINOR + "), "
                            + "last_name TEXT, "
                            + "created_at INTEGER NOT NULL, "
                            + "updated_at INTEGER NOT NULL)",
                    "CREATE UNIQUE INDEX accounts_last_name ON accounts (last_name COLLATE NOCASE) "
                            + "WHERE last_name IS NOT NULL"));

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
        return new StorageException("The economy database has schema version " + found
                + " but this SMP-Economy only understands up to version " + LATEST_VERSION
                + ". It was probably written by a newer release; refusing to open it so nothing is damaged."
                + " Update SMP-Economy or restore an older backup.");
    }
}
