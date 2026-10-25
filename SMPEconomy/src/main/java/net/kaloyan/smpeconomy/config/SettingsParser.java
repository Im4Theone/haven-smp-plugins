package net.kaloyan.smpeconomy.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.kaloyan.smpeconomy.money.InvalidAmountException;
import net.kaloyan.smpeconomy.money.Money;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Turns a raw configuration tree into validated {@link EconomySettings}. Missing (or null) keys take
 * their value from {@link EconomySettings#defaults()}; present keys must have the right type and range.
 *
 * <p>Amounts should be written as quoted strings ({@code "100.00"}) because YAML reads unquoted decimals
 * as binary floating point. Integers and short unquoted decimals (below 10<sup>12</sup>) are accepted too,
 * converted through their shortest decimal representation, which equals what was typed.
 */
final class SettingsParser {

    static final String KEY_SYMBOL = "currency.symbol";
    static final String KEY_STARTING = "balances.starting";
    static final String KEY_MAX_BALANCE = "balances.maximum";
    static final String KEY_MAX_TRANSACTION = "transactions.maximum-amount";
    static final String KEY_DEBUG = "logging.debug";

    static final int MAX_SYMBOL_LENGTH = 8;

    // Below this, a value with 2 decimals has at most 14 significant digits (a double round-trips 15),
    // so BigDecimal.valueOf(d) reproduces the literal the admin typed.
    private static final double MAX_UNQUOTED_DECIMAL = 1e12;

    private SettingsParser() {
    }

    /**
     * Validates {@code config} and builds settings from it.
     *
     * @param config the loaded configuration; must not contain defaults of its own
     * @return the validated settings
     * @throws InvalidConfigException if any value is invalid; lists all problems
     */
    static EconomySettings parse(ConfigurationSection config) throws InvalidConfigException {
        EconomySettings defaults = EconomySettings.defaults();
        List<String> problems = new ArrayList<>();

        String symbol = readSymbol(config, defaults.symbol(), problems);
        Long starting = readAmount(config, KEY_STARTING, defaults.startingMinor(), false, problems);
        Long maxBalance = readAmount(config, KEY_MAX_BALANCE, defaults.maxBalanceMinor(), true, problems);
        Long maxTransaction = readAmount(config, KEY_MAX_TRANSACTION, defaults.maxTransactionMinor(), true, problems);
        boolean debug = readBoolean(config, KEY_DEBUG, defaults.debug(), problems);

        if (starting != null && maxBalance != null && starting > maxBalance) {
            problems.add("'" + KEY_STARTING + "' (" + Money.fromMinor(starting) + ") must not be greater than '"
                    + KEY_MAX_BALANCE + "' (" + Money.fromMinor(maxBalance) + ")");
        }
        if (!problems.isEmpty()) {
            throw new InvalidConfigException(problems);
        }
        return new EconomySettings(symbol, starting, maxBalance, maxTransaction, debug);
    }

    private static String readSymbol(ConfigurationSection config, String fallback, List<String> problems) {
        Object raw = config.get(KEY_SYMBOL);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String symbol)) {
            problems.add("'" + KEY_SYMBOL + "' must be text, but was: " + raw);
            return fallback;
        }
        if (symbol.isBlank()) {
            problems.add("'" + KEY_SYMBOL + "' must not be blank");
            return fallback;
        }
        if (symbol.length() > MAX_SYMBOL_LENGTH) {
            problems.add("'" + KEY_SYMBOL + "' is " + symbol.length() + " characters long; the maximum is "
                    + MAX_SYMBOL_LENGTH);
            return fallback;
        }
        if (symbol.chars().anyMatch(Character::isISOControl)) {
            problems.add("'" + KEY_SYMBOL + "' must not contain control characters");
            return fallback;
        }
        return symbol;
    }

    /** Returns the amount in minor units, or {@code null} (after recording a problem) if invalid. */
    private static Long readAmount(ConfigurationSection config, String key, long fallback, boolean mustBePositive,
                                   List<String> problems) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        BigDecimal value;
        try {
            value = toDecimal(raw);
        } catch (InvalidAmountException e) {
            problems.add("'" + key + "' is invalid: " + e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            problems.add("'" + key + "' " + e.getMessage() + ", but was: " + raw);
            return null;
        }
        try {
            long minor = mustBePositive ? Money.toPositiveMinor(value) : Money.toNonNegativeMinor(value);
            return minor;
        } catch (InvalidAmountException e) {
            problems.add("'" + key + "' is invalid: " + e.getMessage());
            return null;
        }
    }

    private static BigDecimal toDecimal(Object raw) throws InvalidAmountException {
        if (raw instanceof String text) {
            return Money.parse(text.trim());
        }
        if (raw instanceof Integer || raw instanceof Long) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Double number) {
            if (!Double.isFinite(number) || Math.abs(number) >= MAX_UNQUOTED_DECIMAL) {
                throw new IllegalArgumentException("is too large to write without quotes; write it as text such as \"1000000000000.00\"");
            }
            return BigDecimal.valueOf(number);
        }
        throw new IllegalArgumentException("must be an amount such as \"100.00\"");
    }

    private static boolean readBoolean(ConfigurationSection config, String key, boolean fallback,
                                       List<String> problems) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        problems.add("'" + key + "' must be true or false, but was: " + raw);
        return fallback;
    }
}
