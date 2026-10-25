package net.kaloyan.smpeconomy.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import net.kaloyan.smpeconomy.money.InvalidAmountException.Reason;

/**
 * Conversions between {@link BigDecimal} amounts and the 64-bit "minor unit" integers (hundredths) that
 * are persisted. Every conversion is exact: an amount that would need rounding is rejected, never rounded.
 * No {@code double} or {@code float} is used anywhere.
 *
 * <h2>Limits</h2>
 * Amounts are limited to {@link #HARD_MAX} ({@code 1,000,000,000,000,000.00}, i.e. 10<sup>17</sup>
 * minor units). That is about 1/92 of {@code Long.MAX_VALUE}, so the sum of any two valid amounts can
 * never overflow a {@code long}; the code still uses {@link Math#addExact} as a second line of defence.
 * The configurable maximum balance and maximum transaction amount can only be set at or below this ceiling.
 */
public final class Money {

    /** Number of decimal places of the currency. */
    public static final int SCALE = 2;

    /** Largest storable amount in minor units: 10^17, i.e. 1,000,000,000,000,000.00. */
    public static final long HARD_MAX_MINOR = 100_000_000_000_000_000L;

    /** {@link #HARD_MAX_MINOR} as a decimal amount. */
    public static final BigDecimal HARD_MAX = BigDecimal.valueOf(HARD_MAX_MINOR, SCALE);

    /** Longest text {@link #parse} will look at; protects against absurdly long numbers. */
    static final int MAX_INPUT_LENGTH = 40;

    // Integer digits of HARD_MAX (10^15 has 16 digits); anything with more integer digits is too large.
    private static final int MAX_INTEGER_DIGITS = 16;

    private Money() {
    }

    /**
     * Parses user input such as {@code 12}, {@code 12.5} or {@code 0.05}. Only ASCII digits, one optional
     * leading minus sign and one decimal point are accepted: no plus sign, exponent, grouping separators,
     * whitespace, {@code NaN} or {@code Infinity}.
     *
     * @param text the text to parse
     * @return the amount, with scale 2; may be zero or negative (callers decide whether that is acceptable)
     * @throws InvalidAmountException if the text is not a valid amount with at most two decimals within the hard limit
     */
    public static BigDecimal parse(String text) throws InvalidAmountException {
        if (text == null || text.isEmpty()) {
            throw new InvalidAmountException(Reason.MISSING, "No amount was given.");
        }
        if (text.length() > MAX_INPUT_LENGTH || !isPlainDecimal(text)) {
            throw new InvalidAmountException(Reason.MALFORMED,
                    "That is not a valid amount. Use a plain number such as 25 or 12.50.");
        }
        BigDecimal value = new BigDecimal(text);
        toMinor(value); // validates precision and magnitude
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private static boolean isPlainDecimal(String text) {
        int i = 0;
        int length = text.length();
        if (text.charAt(0) == '-') {
            i++;
        }
        int integerDigits = 0;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
            integerDigits++;
        }
        if (integerDigits == 0) {
            return false;
        }
        if (i == length) {
            return true;
        }
        if (text.charAt(i) != '.') {
            return false;
        }
        i++;
        int fractionDigits = 0;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
            fractionDigits++;
        }
        return fractionDigits > 0 && i == length;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Converts an amount of any sign to minor units, exactly.
     *
     * @param amount the amount
     * @return the amount in hundredths, between {@code -HARD_MAX_MINOR} and {@code HARD_MAX_MINOR}
     * @throws InvalidAmountException if the amount is {@code null}, needs more than two decimals, or exceeds {@link #HARD_MAX}
     */
    public static long toMinor(BigDecimal amount) throws InvalidAmountException {
        if (amount == null) {
            throw new InvalidAmountException(Reason.MISSING, "No amount was given.");
        }
        if (amount.signum() == 0) {
            return 0L;
        }
        // Cheap magnitude test first, so a value such as 1E+999999999 never reaches any expensive arithmetic.
        if (amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw tooLarge();
        }
        if (amount.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidAmountException(Reason.PRECISION,
                    "Amounts can have at most " + SCALE + " decimal places.");
        }
        long minor;
        try {
            minor = amount.setScale(SCALE, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
        } catch (ArithmeticException e) {
            throw tooLarge();
        }
        if (minor > HARD_MAX_MINOR || minor < -HARD_MAX_MINOR) {
            throw tooLarge();
        }
        return minor;
    }

    /**
     * Converts to minor units, requiring zero or more.
     *
     * @param amount the amount
     * @return the amount in hundredths, never negative
     * @throws InvalidAmountException if invalid (see {@link #toMinor}) or negative
     */
    public static long toNonNegativeMinor(BigDecimal amount) throws InvalidAmountException {
        long minor = toMinor(amount);
        if (minor < 0) {
            throw new InvalidAmountException(Reason.NEGATIVE, "The amount cannot be negative.");
        }
        return minor;
    }

    /**
     * Converts to minor units, requiring more than zero.
     *
     * @param amount the amount
     * @return the amount in hundredths, at least 1
     * @throws InvalidAmountException if invalid (see {@link #toMinor}), negative or zero
     */
    public static long toPositiveMinor(BigDecimal amount) throws InvalidAmountException {
        long minor = toNonNegativeMinor(amount);
        if (minor == 0) {
            throw new InvalidAmountException(Reason.ZERO, "The amount must be greater than zero.");
        }
        return minor;
    }

    /**
     * Converts stored minor units back to an amount.
     *
     * @param minor hundredths
     * @return the amount, with scale 2
     */
    public static BigDecimal fromMinor(long minor) {
        return BigDecimal.valueOf(minor, SCALE);
    }

    /**
     * Formats an amount for display: the symbol, thousands separators (comma) and exactly two decimals,
     * for example {@code $1,234.50}. Negative amounts are written {@code -$5.00}. Locale independent.
     *
     * @param symbol the currency symbol
     * @param amount the amount; must have at most two decimal places
     * @return the display text
     * @throws IllegalArgumentException if the amount is {@code null} or would need rounding
     */
    public static String format(String symbol, BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        BigDecimal exact;
        try {
            exact = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount has more than " + SCALE + " decimal places", e);
        }
        NumberFormat numbers = NumberFormat.getInstance(Locale.ROOT);
        numbers.setGroupingUsed(true);
        numbers.setMinimumFractionDigits(SCALE);
        numbers.setMaximumFractionDigits(SCALE);
        numbers.setRoundingMode(RoundingMode.UNNECESSARY);
        String digits = numbers.format(exact.abs());
        return (exact.signum() < 0 ? "-" : "") + symbol + digits;
    }

    private static InvalidAmountException tooLarge() {
        return new InvalidAmountException(Reason.TOO_LARGE,
                "That amount is too large (the absolute maximum is " + format("", HARD_MAX) + ").");
    }
}
