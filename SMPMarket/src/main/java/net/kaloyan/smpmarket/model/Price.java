package net.kaloyan.smpmarket.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Exact conversions between decimal prices and minor units (hundredths). Nothing is ever rounded silently
 * except the fee, which is explicitly rounded down.
 */
public final class Price {

    /** Largest amount SMP-Economy can store, in minor units (1,000,000,000,000,000.00). */
    public static final long HARD_MAX_MINOR = 100_000_000_000_000_000L;

    private static final Pattern PLAIN = Pattern.compile("\\d{1,16}(\\.\\d{1,2})?");

    private Price() {
    }

    /** Thrown for text that is not a plain amount with at most two decimals. */
    public static final class InvalidPriceException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidPriceException(String message) {
            super(message);
        }
    }

    /**
     * Parses plain decimal text such as {@code 25} or {@code 12.50}. Rejects signs, exponents, grouping, more than
     * two decimals, blank input and anything above the storable maximum. Zero is returned as zero; callers decide.
     *
     * @param text the user's input
     * @return the amount in minor units
     * @throws InvalidPriceException if the text is not valid
     */
    public static long parseMinor(String text) throws InvalidPriceException {
        if (text == null || !PLAIN.matcher(text).matches()) {
            throw new InvalidPriceException("That is not a valid price. Use a plain positive number such as 25 or 12.50 "
                    + "(at most 2 decimals).");
        }
        long minor = new BigDecimal(text).movePointRight(2).longValueExact();
        if (minor > HARD_MAX_MINOR) {
            throw new InvalidPriceException("That price is too large.");
        }
        return minor;
    }

    /**
     * Converts minor units to a scale-2 decimal.
     *
     * @param minor amount in hundredths
     * @return the decimal
     */
    public static BigDecimal toDecimal(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    /**
     * Converts a decimal with at most two decimals to minor units.
     *
     * @param amount the amount
     * @return minor units
     * @throws ArithmeticException if the amount has more than two decimals or does not fit
     */
    public static long toMinor(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    /**
     * Fee for a price: {@code price * percentHundredths / 10000}, rounded down to the cent.
     *
     * @param priceMinor        price in minor units
     * @param percentHundredths fee percent in hundredths of a percent (5.25% = 525)
     * @return the fee in minor units
     */
    public static long fee(long priceMinor, int percentHundredths) {
        return BigInteger.valueOf(priceMinor).multiply(BigInteger.valueOf(percentHundredths))
                .divide(BigInteger.valueOf(10_000)).longValueExact();
    }
}
