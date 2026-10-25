package net.kaloyan.smpeconomy.service;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.log.DebugLogger;
import net.kaloyan.smpeconomy.money.InvalidAmountException;
import net.kaloyan.smpeconomy.money.Money;
import net.kaloyan.smpeconomy.storage.AccountRef;
import net.kaloyan.smpeconomy.storage.EconomyStore;

/**
 * Implements the public {@link EconomyService} on top of {@link EconomyStore} and adds the operations only
 * SMP-Economy itself needs (account creation, lookups by name, administrative changes).
 *
 * <p>Every call is queued on {@code storageExecutor}, which must run tasks one at a time in submission
 * order (SMP-Economy uses a single thread). That keeps database work off the main thread and gives
 * operations a simple, predictable order; correctness of each money change does not depend on it because
 * {@link EconomyStore} makes every change an atomic {@code BEGIN IMMEDIATE} transaction by itself.
 */
public final class EconomyServiceImpl implements EconomyService {

    private final EconomyStore store;
    private final Executor storageExecutor;
    private final Supplier<EconomySettings> settings;
    private final DebugLogger debug;

    /**
     * @param store           the open store
     * @param storageExecutor runs all database work; a single-thread executor in production
     * @param settings        supplies the live configuration
     * @param debug           debug logger
     */
    public EconomyServiceImpl(EconomyStore store, Executor storageExecutor, Supplier<EconomySettings> settings,
                              DebugLogger debug) {
        this.store = store;
        this.storageExecutor = storageExecutor;
        this.settings = settings;
        this.debug = debug;
    }

    // ---------------------------------------------------------------- public API

    @Override
    public int getFractionDigits() {
        return Money.SCALE;
    }

    @Override
    public String getCurrencySymbol() {
        return settings.get().symbol();
    }

    @Override
    public String format(BigDecimal amount) {
        return Money.format(settings.get().symbol(), amount);
    }

    @Override
    public BigDecimal getMaximumBalance() {
        return settings.get().maxBalance();
    }

    @Override
    public BigDecimal getMaximumTransactionAmount() {
        return settings.get().maxTransaction();
    }

    @Override
    public CompletableFuture<Boolean> hasAccount(UUID player) {
        Objects.requireNonNull(player, "player");
        return submit(() -> store.hasAccount(player));
    }

    @Override
    public CompletableFuture<Optional<BigDecimal>> getBalance(UUID player) {
        Objects.requireNonNull(player, "player");
        return submit(() -> store.balance(player));
    }

    @Override
    public CompletableFuture<Boolean> hasFunds(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        try {
            Money.toNonNegativeMinor(amount);
        } catch (InvalidAmountException e) {
            return CompletableFuture.completedFuture(false);
        }
        return submit(() -> store.balance(player).map(balance -> balance.compareTo(amount) >= 0).orElse(false));
    }

    @Override
    public CompletableFuture<TransactionResult> deposit(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        return submit(() -> logged("deposit", player, amount, store.deposit(player, amount)));
    }

    @Override
    public CompletableFuture<TransactionResult> withdraw(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        return submit(() -> logged("withdraw", player, amount, store.withdraw(player, amount)));
    }

    @Override
    public CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return submit(() -> logged("transfer from " + from + " to", to, amount, store.transfer(from, to, amount)));
    }

    // ---------------------------------------------------------------- internal operations

    /**
     * Creates the account with the starting balance if missing and records the player's name.
     *
     * @param player the player's UUID
     * @param name   the player's current name
     * @return a future of {@code true} if the account was created now
     */
    public CompletableFuture<Boolean> initializeAccount(UUID player, String name) {
        Objects.requireNonNull(player, "player");
        return submit(() -> {
            boolean created = store.initializeAccount(player, name);
            debug.debug(() -> (created ? "Created account " : "Account exists, name refreshed: ") + player);
            return created;
        });
    }

    /**
     * Looks an account up by UUID. Never creates one.
     *
     * @param player the player's UUID
     * @return a future of the account, or empty
     */
    public CompletableFuture<Optional<AccountRef>> findAccount(UUID player) {
        Objects.requireNonNull(player, "player");
        return submit(() -> store.findAccount(player));
    }

    /**
     * Looks an account up by last known name. Never creates one.
     *
     * @param name the player name
     * @return a future of the account, or empty
     */
    public CompletableFuture<Optional<AccountRef>> findAccountByName(String name) {
        Objects.requireNonNull(name, "name");
        return submit(() -> store.findAccountByName(name));
    }

    /**
     * Administrative credit; exempt from the maximum transaction amount.
     *
     * @param player the account
     * @param amount the amount
     * @return a future of the outcome
     */
    public CompletableFuture<TransactionResult> adminAdd(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        return submit(() -> logged("admin add to", player, amount, store.adminAdd(player, amount)));
    }

    /**
     * Administrative debit; exempt from the maximum transaction amount, never goes below zero.
     *
     * @param player the account
     * @param amount the amount
     * @return a future of the outcome
     */
    public CompletableFuture<TransactionResult> adminTake(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        return submit(() -> logged("admin take from", player, amount, store.adminTake(player, amount)));
    }

    /**
     * Administrative overwrite of a balance.
     *
     * @param player the account
     * @param amount the new balance
     * @return a future of the outcome
     */
    public CompletableFuture<TransactionResult> setBalance(UUID player, BigDecimal amount) {
        Objects.requireNonNull(player, "player");
        return submit(() -> logged("admin set", player, amount, store.setBalance(player, amount)));
    }

    // ---------------------------------------------------------------- plumbing

    private TransactionResult logged(String what, UUID player, BigDecimal amount, TransactionResult result) {
        debug.debug(() -> what + " " + player + " amount=" + amount + " -> " + result.status());
        return result;
    }

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            storageExecutor.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) { // handed to the caller through the future
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("SMP-Economy is shut down", e));
        }
        return future;
    }
}
