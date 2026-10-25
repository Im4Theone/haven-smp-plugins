package net.kaloyan.smpeconomy.config;

import java.math.BigDecimal;
import java.util.Objects;
import net.kaloyan.smpeconomy.money.Money;

/**
 * Immutable snapshot of SMP-Economy's validated configuration. Amounts are held as minor units
 * (hundredths) so they can be compared and added with exact integer arithmetic.
 *
 * @param symbol             currency symbol shown in front of amounts; never blank
 * @param startingMinor      balance of a new account, 0 to {@code maxBalanceMinor}
 * @param maxBalanceMinor    largest balance an account may reach through a credit, 1 to {@link Money#HARD_MAX_MINOR}
 * @param maxTransactionMinor largest single deposit, withdrawal or transfer, 1 to {@link Money#HARD_MAX_MINOR}
 * @param debug              whether extra diagnostic logging is enabled
 */
public record EconomySettings(String symbol, long startingMinor, long maxBalanceMinor, long maxTransactionMinor,
                              boolean debug) {

    /** Default currency symbol. */
    public static final String DEFAULT_SYMBOL = "$";
    /** Default starting balance, 10,000.00. */
    public static final long DEFAULT_STARTING_MINOR = 1_000_000L;
    /** Default maximum balance, 1,000,000,000,000.00 (one trillion). */
    public static final long DEFAULT_MAX_BALANCE_MINOR = 100_000_000_000_000L;
    /** Default maximum transaction amount, 1,000,000,000.00 (one billion). */
    public static final long DEFAULT_MAX_TRANSACTION_MINOR = 100_000_000_000L;

    public EconomySettings {
        Objects.requireNonNull(symbol, "symbol");
        if (startingMinor < 0 || startingMinor > maxBalanceMinor) {
            throw new IllegalArgumentException("starting balance must be between 0 and the maximum balance");
        }
        if (maxBalanceMinor < 1 || maxBalanceMinor > Money.HARD_MAX_MINOR) {
            throw new IllegalArgumentException("maximum balance out of range");
        }
        if (maxTransactionMinor < 1 || maxTransactionMinor > Money.HARD_MAX_MINOR) {
            throw new IllegalArgumentException("maximum transaction amount out of range");
        }
    }

    /**
     * Returns the settings used for every key missing from {@code config.yml}.
     *
     * @return the default settings
     */
    public static EconomySettings defaults() {
        return new EconomySettings(DEFAULT_SYMBOL, DEFAULT_STARTING_MINOR, DEFAULT_MAX_BALANCE_MINOR,
                DEFAULT_MAX_TRANSACTION_MINOR, false);
    }

    /**
     * Returns the starting balance as a decimal.
     *
     * @return the starting balance
     */
    public BigDecimal startingBalance() {
        return Money.fromMinor(startingMinor);
    }

    /**
     * Returns the maximum balance as a decimal.
     *
     * @return the maximum balance
     */
    public BigDecimal maxBalance() {
        return Money.fromMinor(maxBalanceMinor);
    }

    /**
     * Returns the maximum transaction amount as a decimal.
     *
     * @return the maximum transaction amount
     */
    public BigDecimal maxTransaction() {
        return Money.fromMinor(maxTransactionMinor);
    }
}
