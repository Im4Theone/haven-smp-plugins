package net.kaloyan.smpeconomy.storage;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.money.InvalidAmountException;
import net.kaloyan.smpeconomy.money.Money;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * The only class that reads or writes balances. All money rules and all SQL live here, so there is exactly
 * one implementation of every balance change.
 *
 * <h2>Transaction guarantees</h2>
 * <ul>
 *   <li>Every change runs in one {@code BEGIN IMMEDIATE} ... {@code COMMIT} transaction. {@code IMMEDIATE}
 *       takes SQLite's write lock before the first read, so the balance that is checked is the balance that is
 *       updated: no other writer (another thread, another connection, another process) can slip in between.</li>
 *   <li>Any failure, including an exception between the two halves of a transfer, rolls the whole transaction
 *       back. A failed or rejected operation reports a non-success {@link TransactionResult}; it never
 *       reports success unless {@code COMMIT} returned normally.</li>
 *   <li>One connection is shared and every public method holds {@link #lock}, so the store is thread-safe.
 *       Callers are expected to run it on a dedicated storage thread (see {@code EconomyServiceImpl}).</li>
 *   <li>If another process holds the write lock, SQLite waits up to {@link #BUSY_TIMEOUT_MS} milliseconds
 *       before the operation fails with {@link Status#STORAGE_ERROR}.</li>
 * </ul>
 *
 * <p>The configuration is read from {@code settings} once per operation, so a reload applies to the next
 * operation without affecting one that is running.
 */
public final class EconomyStore implements AutoCloseable {

    /** How long SQLite waits for a lock held by another connection before giving up. */
    public static final int BUSY_TIMEOUT_MS = 5_000;

    private static final int MAX_NAME_LENGTH = 64;

    @FunctionalInterface
    private interface Work<T> {
        T run() throws SQLException;
    }

    private final Connection connection;
    private final Supplier<EconomySettings> settings;
    private final Logger logger;
    private final Object lock = new Object();
    private boolean closed;

    private EconomyStore(Connection connection, Supplier<EconomySettings> settings, Logger logger) {
        this.connection = connection;
        this.settings = settings;
        this.logger = logger;
    }

    /**
     * Opens (creating if needed) the database file and brings its schema up to date.
     *
     * @param file     database file; its directory is created if missing
     * @param settings supplies the live configuration
     * @param logger   receives storage errors
     * @return an open store; close it when done
     * @throws StorageException if the file cannot be opened or its schema is unusable
     */
    public static EconomyStore open(Path file, Supplier<EconomySettings> settings, Logger logger) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(logger, "logger");
        try {
            Path absolute = file.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            SQLiteConfig config = new SQLiteConfig();
            config.setBusyTimeout(BUSY_TIMEOUT_MS);
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            // FULL: a committed payment survives a power cut. The write volume of an SMP is tiny.
            config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
            config.enforceForeignKeys(true);
            Connection connection = new JDBC().connect("jdbc:sqlite:" + absolute, config.toProperties());
            if (connection == null) {
                throw new StorageException("The SQLite driver rejected the database URL");
            }
            try {
                SchemaMigrator.migrate(connection);
            } catch (RuntimeException e) {
                closeQuietly(connection, e);
                throw e;
            }
            return new EconomyStore(connection, settings, logger);
        } catch (IOException | SQLException e) {
            throw new StorageException("Could not open the economy database " + file + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(Connection connection, Throwable primary) {
        try {
            connection.close();
        } catch (SQLException e) {
            primary.addSuppressed(e);
        }
    }

    // ---------------------------------------------------------------- accounts

    /**
     * Creates the account with the configured starting balance if it does not exist, and records the
     * player's current name. Idempotent: an existing account is never touched except for its name, so the
     * starting balance can be granted at most once per UUID however often or concurrently this is called.
     *
     * @param player the player's UUID
     * @param name   the player's current name, or {@code null}
     * @return {@code true} if the account was created by this call
     * @throws StorageException if the database fails
     */
    public boolean initializeAccount(UUID player, String name) {
        Objects.requireNonNull(player, "player");
        long starting = settings.get().startingMinor();
        String cleanName = normalizeName(name);
        long now = System.currentTimeMillis();
        return transaction(() -> {
            if (cleanName != null) {
                // The name now belongs to this player; a different account still carrying it is stale.
                try (PreparedStatement clear = connection.prepareStatement(
                        "UPDATE accounts SET last_name = NULL WHERE uuid <> ? AND last_name = ? COLLATE NOCASE")) {
                    clear.setString(1, player.toString());
                    clear.setString(2, cleanName);
                    clear.executeUpdate();
                }
            }
            boolean created;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO accounts (uuid, balance, last_name, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, player.toString());
                insert.setLong(2, starting);
                insert.setString(3, cleanName);
                insert.setLong(4, now);
                insert.setLong(5, now);
                created = insert.executeUpdate() == 1;
            }
            if (!created && cleanName != null) {
                try (PreparedStatement rename = connection.prepareStatement(
                        "UPDATE accounts SET last_name = ?, updated_at = ? "
                                + "WHERE uuid = ? AND (last_name IS NULL OR last_name <> ?)")) {
                    rename.setString(1, cleanName);
                    rename.setLong(2, now);
                    rename.setString(3, player.toString());
                    rename.setString(4, cleanName);
                    rename.executeUpdate();
                }
            }
            return created;
        }, committed -> true);
    }

    /**
     * Checks whether an account exists. Never creates one.
     *
     * @param player the player's UUID
     * @return {@code true} if it exists
     * @throws StorageException if the database fails
     */
    public boolean hasAccount(UUID player) {
        return readBalanceMinor(Objects.requireNonNull(player, "player")).isPresent();
    }

    /**
     * Reads a balance. Never creates an account.
     *
     * @param player the player's UUID
     * @return the balance, or empty if there is no account
     * @throws StorageException if the database fails
     */
    public Optional<BigDecimal> balance(UUID player) {
        OptionalLong minor = readBalanceMinor(Objects.requireNonNull(player, "player"));
        return minor.isPresent() ? Optional.of(Money.fromMinor(minor.getAsLong())) : Optional.empty();
    }

    /**
     * Looks an account up by UUID.
     *
     * @param player the player's UUID
     * @return the account, or empty if none exists
     * @throws StorageException if the database fails
     */
    public Optional<AccountRef> findAccount(UUID player) {
        Objects.requireNonNull(player, "player");
        return query("SELECT uuid, last_name FROM accounts WHERE uuid = ?", player.toString());
    }

    /**
     * Looks an account up by last known name, ignoring case. Names are unique among accounts: when a
     * player joins under a name, any other account still carrying that name loses it.
     *
     * @param name the player name
     * @return the account, or empty if no account has that name
     * @throws StorageException if the database fails
     */
    public Optional<AccountRef> findAccountByName(String name) {
        Objects.requireNonNull(name, "name");
        return query("SELECT uuid, last_name FROM accounts WHERE last_name = ? COLLATE NOCASE", name);
    }

    private Optional<AccountRef> query(String sql, String parameter) {
        synchronized (lock) {
            requireOpen();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, parameter);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new AccountRef(UUID.fromString(rs.getString(1)), rs.getString(2)))
                            : Optional.empty();
                }
            } catch (SQLException e) {
                throw new StorageException("Account lookup failed: " + e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------- money changes

    /**
     * Adds money to an account, subject to the maximum transaction amount and maximum balance.
     *
     * @param player the account
     * @param amount the amount, greater than zero
     * @return the outcome
     */
    public TransactionResult deposit(UUID player, BigDecimal amount) {
        return adjust(player, amount, true, true);
    }

    /**
     * Removes money from an account, subject to the maximum transaction amount; never below zero.
     *
     * @param player the account
     * @param amount the amount, greater than zero
     * @return the outcome
     */
    public TransactionResult withdraw(UUID player, BigDecimal amount) {
        return adjust(player, amount, false, true);
    }

    /**
     * Administrative credit. Same validation and transaction as {@link #deposit}, except the maximum
     * transaction amount does not apply (the maximum balance and the absolute storage limit still do).
     *
     * @param player the account
     * @param amount the amount, greater than zero
     * @return the outcome
     */
    public TransactionResult adminAdd(UUID player, BigDecimal amount) {
        return adjust(player, amount, true, false);
    }

    /**
     * Administrative debit. Same validation and transaction as {@link #withdraw}, except the maximum
     * transaction amount does not apply. The balance still cannot go below zero.
     *
     * @param player the account
     * @param amount the amount, greater than zero
     * @return the outcome
     */
    public TransactionResult adminTake(UUID player, BigDecimal amount) {
        return adjust(player, amount, false, false);
    }

    /**
     * Administrative overwrite of a balance. The new balance must be between zero and the maximum balance.
     *
     * @param player the account; it must already exist
     * @param amount the new balance
     * @return the outcome; on success {@link TransactionResult#balance()} is the new balance
     */
    public TransactionResult setBalance(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        EconomySettings current = settings.get();
        long newBalance;
        try {
            newBalance = Money.toNonNegativeMinor(amount);
        } catch (InvalidAmountException e) {
            return TransactionResult.failure(Status.INVALID_AMOUNT, e.getMessage());
        }
        if (newBalance > current.maxBalanceMinor()) {
            return TransactionResult.failure(Status.BALANCE_LIMIT_EXCEEDED,
                    "A balance cannot be set above the maximum balance of " + Money.fromMinor(current.maxBalanceMinor()));
        }
        return guarded("set balance", player, () -> transaction(() -> {
            if (readBalanceMinorInTransaction(player).isEmpty()) {
                return TransactionResult.failure(Status.ACCOUNT_NOT_FOUND, "No account for " + player);
            }
            writeBalance(player, newBalance);
            return TransactionResult.success(Money.fromMinor(newBalance), null);
        }, TransactionResult::isSuccess));
    }

    /**
     * Moves money between two accounts atomically.
     *
     * @param from   the paying account
     * @param to     the receiving account
     * @param amount the amount, greater than zero and at most the maximum transaction amount
     * @return the outcome; on success the payer's and recipient's new balances
     */
    public TransactionResult transfer(UUID from, UUID to, BigDecimal amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        EconomySettings current = settings.get();
        long minor;
        try {
            minor = Money.toPositiveMinor(amount);
        } catch (InvalidAmountException e) {
            return TransactionResult.failure(Status.INVALID_AMOUNT, e.getMessage());
        }
        if (minor > current.maxTransactionMinor()) {
            return exceedsLimit(current);
        }
        if (from.equals(to)) {
            return TransactionResult.failure(Status.SELF_TRANSFER, "Payer and recipient are the same account");
        }
        return guarded("transfer", from, () -> transaction(() -> {
            OptionalLong payer = readBalanceMinorInTransaction(from);
            if (payer.isEmpty()) {
                return TransactionResult.failure(Status.SOURCE_ACCOUNT_NOT_FOUND, "No account for payer " + from);
            }
            OptionalLong recipient = readBalanceMinorInTransaction(to);
            if (recipient.isEmpty()) {
                return TransactionResult.failure(Status.TARGET_ACCOUNT_NOT_FOUND, "No account for recipient " + to);
            }
            if (payer.getAsLong() < minor) {
                return TransactionResult.failure(Status.INSUFFICIENT_FUNDS, "The payer has less than the amount");
            }
            long payerAfter = payer.getAsLong() - minor;
            long recipientAfter;
            try {
                recipientAfter = Math.addExact(recipient.getAsLong(), minor);
            } catch (ArithmeticException e) {
                return balanceLimit(current);
            }
            if (recipientAfter > current.maxBalanceMinor()) {
                return balanceLimit(current);
            }
            writeBalance(from, payerAfter);
            writeBalance(to, recipientAfter);
            return TransactionResult.success(Money.fromMinor(payerAfter), Money.fromMinor(recipientAfter));
        }, TransactionResult::isSuccess));
    }

    private TransactionResult adjust(UUID player, BigDecimal amount, boolean credit, boolean enforceTransactionLimit) {
        Objects.requireNonNull(player, "player");
        EconomySettings current = settings.get();
        long minor;
        try {
            minor = Money.toPositiveMinor(amount);
        } catch (InvalidAmountException e) {
            return TransactionResult.failure(Status.INVALID_AMOUNT, e.getMessage());
        }
        if (enforceTransactionLimit && minor > current.maxTransactionMinor()) {
            return exceedsLimit(current);
        }
        return guarded(credit ? "credit" : "debit", player, () -> transaction(() -> {
            OptionalLong before = readBalanceMinorInTransaction(player);
            if (before.isEmpty()) {
                return TransactionResult.failure(Status.ACCOUNT_NOT_FOUND, "No account for " + player);
            }
            long after;
            if (credit) {
                try {
                    after = Math.addExact(before.getAsLong(), minor);
                } catch (ArithmeticException e) {
                    return balanceLimit(current);
                }
                if (after > current.maxBalanceMinor()) {
                    return balanceLimit(current);
                }
            } else {
                if (before.getAsLong() < minor) {
                    return TransactionResult.failure(Status.INSUFFICIENT_FUNDS, "The account has less than the amount");
                }
                after = before.getAsLong() - minor;
            }
            writeBalance(player, after);
            return TransactionResult.success(Money.fromMinor(after), null);
        }, TransactionResult::isSuccess));
    }

    private static TransactionResult exceedsLimit(EconomySettings current) {
        return TransactionResult.failure(Status.AMOUNT_EXCEEDS_LIMIT,
                "The amount is above the maximum transaction amount of " + Money.fromMinor(current.maxTransactionMinor()));
    }

    private static TransactionResult balanceLimit(EconomySettings current) {
        return TransactionResult.failure(Status.BALANCE_LIMIT_EXCEEDED,
                "The balance would exceed the maximum balance of " + Money.fromMinor(current.maxBalanceMinor()));
    }

    /** Runs a money change, turning a database failure into a STORAGE_ERROR result instead of an exception. */
    private TransactionResult guarded(String what, UUID subject, Supplier<TransactionResult> operation) {
        try {
            return operation.get();
        } catch (StorageException e) {
            logger.log(Level.SEVERE, "Economy " + what + " for " + subject + " failed and was rolled back: "
                    + e.getMessage(), e);
            return TransactionResult.failure(Status.STORAGE_ERROR,
                    "The economy database reported an error; the transaction was not applied.");
        }
    }

    // ---------------------------------------------------------------- SQL helpers

    private OptionalLong readBalanceMinor(UUID player) {
        synchronized (lock) {
            requireOpen();
            try {
                return selectBalance(player);
            } catch (SQLException e) {
                throw new StorageException("Balance lookup failed: " + e.getMessage(), e);
            }
        }
    }

    /** Must be called inside {@link #transaction}. */
    private OptionalLong readBalanceMinorInTransaction(UUID player) throws SQLException {
        return selectBalance(player);
    }

    private OptionalLong selectBalance(UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM accounts WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    /** Must be called inside {@link #transaction}. Fails (and so rolls back) unless exactly one row changed. */
    private void writeBalance(UUID player, long balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance = ?, updated_at = ? WHERE uuid = ?")) {
            statement.setLong(1, balance);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, player.toString());
            int rows = statement.executeUpdate();
            if (rows != 1) {
                throw new SQLException("Expected to update exactly one account but updated " + rows);
            }
        }
    }

    /**
     * Runs {@code work} inside {@code BEGIN IMMEDIATE}. Commits if {@code commitWhen} accepts the result,
     * otherwise rolls back. Any exception rolls back and is rethrown as {@link StorageException}.
     */
    private <T> T transaction(Work<T> work, Predicate<T> commitWhen) {
        synchronized (lock) {
            requireOpen();
            try (Statement statement = connection.createStatement()) {
                statement.execute("BEGIN IMMEDIATE");
                T result;
                try {
                    result = work.run();
                    statement.execute(commitWhen.test(result) ? "COMMIT" : "ROLLBACK");
                } catch (SQLException | RuntimeException e) {
                    try {
                        statement.execute("ROLLBACK");
                    } catch (SQLException rollbackFailure) {
                        // "no transaction is active" is expected if SQLite already rolled back on its own.
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                if (e instanceof StorageException storage) {
                    throw storage;
                }
                throw new StorageException("Transaction failed: " + e.getMessage(), e);
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new StorageException("The economy database is closed");
        }
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        return name;
    }

    /** Closes the database. Safe to call more than once. Waits for a running transaction to finish. */
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
                logger.log(Level.WARNING, "Closing the economy database failed: " + e.getMessage(), e);
            }
        }
    }
}
