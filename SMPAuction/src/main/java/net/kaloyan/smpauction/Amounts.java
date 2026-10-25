package net.kaloyan.smpauction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Reading and writing the numbers players type: {@code 100}, {@code 1.5k}, {@code 2m}, and formatting money. */
public final class Amounts {

    private Amounts() {
    }

    private static BigDecimal parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
        BigDecimal factor = BigDecimal.ONE;
        if (!t.isEmpty()) {
            switch (t.charAt(t.length() - 1)) {
                case 'k' -> factor = BigDecimal.valueOf(1_000L);
                case 'm' -> factor = BigDecimal.valueOf(1_000_000L);
                case 'b' -> factor = BigDecimal.valueOf(1_000_000_000L);
                default -> { }
            }
            if (!factor.equals(BigDecimal.ONE)) {
                t = t.substring(0, t.length() - 1);
            }
        }
        if (!t.matches("\\d{1,12}(\\.\\d{1,6})?")) {
            return null;
        }
        return new BigDecimal(t).multiply(factor);
    }

    /**
     * @param text what the player typed
     * @return a whole number of items, or {@code -1} if it is not a valid positive whole number
     */
    public static int count(String text) {
        BigDecimal value = parse(text);
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return -1;
        }
        return value.intValueExact();
    }

    /**
     * @param text what the player typed
     * @return an amount of money in hundredths, or {@code -1} if it is not valid (at most two decimals, above zero)
     */
    public static long cents(String text) {
        BigDecimal value = parse(text);
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 2 || value.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0) {
            return -1;
        }
        return value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    /**
     * @param cents an amount in hundredths
     * @return for example {@code $1,234.5}
     */
    public static String money(long cents) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return "$" + format.format(cents / 100.0);
    }

    /**
     * @param millis a duration
     * @return for example {@code 2d 3h}, {@code 5h 10m} or {@code 3m}
     */
    public static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + mins + "m" : Math.max(1, mins) + "m";
    }
}
