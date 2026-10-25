package net.kaloyan.smpeconomy.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public economy API of SMP-Economy. Obtain it from SMP-Core's service registry:
 *
 * <pre>{@code
 * EconomyService economy = smpApi.getServices().get(EconomyService.class);
 * }</pre>
 *
 * <h2>Money</h2>
 * Amounts are {@link BigDecimal}s with at most two decimal places (hundredths). Amounts that would need
 * rounding are rejected, never rounded. Every operation that takes an amount treats a {@code null},
 * negative, zero (where positive is required), over-precise or oversized amount as
 * {@link TransactionResult.Status#INVALID_AMOUNT}.
 *
 * <h2>Threading</h2>
 * Every method that touches stored data returns a {@link CompletableFuture} and runs on a single
 * dedicated storage thread, so it is safe to call from the main thread. Consequences:
 * <ul>
 *   <li>Never call {@code join()} or {@code get()} on a returned future from the server main thread.</li>
 *   <li>Dependent stages (for example {@code thenAccept}) run <em>on the storage thread</em>. Do not touch
 *       Bukkit state from them; hop back with {@code Bukkit.getScheduler().runTask(plugin, ...)}.</li>
 *   <li>Operations are executed one after another in the order they were submitted, and every
 *       money-changing operation is a single atomic database transaction.</li>
 *   <li>If the database fails, money-changing methods complete normally with
 *       {@link TransactionResult.Status#STORAGE_ERROR}; read methods complete exceptionally.</li>
 *   <li>After SMP-Economy shuts down, returned futures complete exceptionally with
 *       {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>There is deliberately no method that overwrites a balance. Only deposits, withdrawals and
 * transfers are available to other plugins.
 */
public interface EconomyService {

    /**
     * Returns the number of decimal places of the currency.
     *
     * @return always {@code 2}
     */
    int getFractionDigits();

    /**
     * Returns the currently configured currency symbol, for example {@code $}.
     *
     * @return the symbol
     */
    String getCurrencySymbol();

    /**
     * Formats an amount for display using the current configuration, for example {@code $1,234.50}.
     * Negative amounts are written {@code -$5.00}. Not locale dependent.
     *
     * @param amount an amount with at most two decimal places
     * @return the formatted text
     * @throws IllegalArgumentException if {@code amount} is {@code null} or needs rounding
     */
    String format(BigDecimal amount);

    /**
     * Returns the currently configured maximum balance of an account.
     *
     * @return the maximum balance
     */
    BigDecimal getMaximumBalance();

    /**
     * Returns the currently configured maximum amount of a single deposit, withdrawal or transfer.
     *
     * @return the maximum transaction amount
     */
    BigDecimal getMaximumTransactionAmount();

    /**
     * Checks whether an account exists.
     *
     * @param player the player's UUID
     * @return a future of {@code true} if the account exists
     */
    CompletableFuture<Boolean> hasAccount(UUID player);

    /**
     * Reads a balance. Never creates an account.
     *
     * @param player the player's UUID
     * @return a future of the balance, or empty if the player has no account
     */
    CompletableFuture<Optional<BigDecimal>> getBalance(UUID player);

    /**
     * Checks whether an account holds at least {@code amount}. This is informational only: the balance
     * can change before you act on the answer, so use {@link #withdraw} or {@link #transfer} (which check
     * and debit atomically) to actually take money.
     *
     * @param player the player's UUID
     * @param amount the amount, zero or more
     * @return a future of {@code true} if the account exists and holds at least {@code amount};
     *         {@code false} if it has less, does not exist, or the amount is invalid
     */
    CompletableFuture<Boolean> hasFunds(UUID player, BigDecimal amount);

    /**
     * Adds money to an existing account. Fails with {@code BALANCE_LIMIT_EXCEEDED} if the balance would
     * go above the maximum balance.
     *
     * @param player the receiving player's UUID
     * @param amount the amount, greater than zero and at most the maximum transaction amount
     * @return a future of the outcome; on success {@link TransactionResult#balance()} is the new balance
     */
    CompletableFuture<TransactionResult> deposit(UUID player, BigDecimal amount);

    /**
     * Removes money from an existing account. A balance never goes below zero: if the account holds less
     * than {@code amount} the call fails with {@code INSUFFICIENT_FUNDS} and nothing changes.
     *
     * @param player the player's UUID
     * @param amount the amount, greater than zero and at most the maximum transaction amount
     * @return a future of the outcome; on success {@link TransactionResult#balance()} is the new balance
     */
    CompletableFuture<TransactionResult> withdraw(UUID player, BigDecimal amount);

    /**
     * Moves money from one account to another in one atomic transaction: either both balances change or
     * neither does. Transferring to yourself fails with {@code SELF_TRANSFER}.
     *
     * @param from   the paying player's UUID
     * @param to     the receiving player's UUID
     * @param amount the amount, greater than zero and at most the maximum transaction amount
     * @return a future of the outcome; on success {@link TransactionResult#balance()} is the payer's new
     *         balance and {@link TransactionResult#counterpartBalance()} the recipient's
     */
    CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount);
}
