package net.kaloyan.smpscoreboard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Number and time formatting for the sidebar. Pure functions, no server needed. */
public final class Formats {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final String[] SUFFIXES = {"", "k", "m", "b", "t", "q"};

    /**
     * @param seconds a duration in seconds, or negative for "none"
     * @return {@code mm:ss} (a full hour reads {@code 60:00}), or {@code -} for a negative value
     */
    public static String countdown(long seconds) {
        if (seconds < 0) {
            return "-";
        }
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private Formats() {
    }

    /**
     * Compact money: {@code 950}, {@code 12.5}, {@code 10k}, {@code 1.2m}. Never rounds up into the next unit
     * ({@code 999999} is {@code 999.9k}), and shows at most one decimal for large values.
     *
     * @param symbol the currency symbol, written in front
     * @param amount the amount (may be negative)
     * @return the text
     */
    public static String money(String symbol, BigDecimal amount) {
        BigDecimal value = amount.abs();
        int unit = 0;
        while (value.compareTo(THOUSAND) >= 0 && unit < SUFFIXES.length - 1) {
            value = value.divide(THOUSAND);
            unit++;
        }
        BigDecimal shown = unit == 0
                ? value.setScale(2, RoundingMode.DOWN)
                : value.setScale(1, RoundingMode.DOWN);
        String text = shown.stripTrailingZeros().toPlainString();
        return (amount.signum() < 0 ? "-" : "") + symbol + text + SUFFIXES[unit];
    }

    /**
     * Playtime as {@code 0d 0h}, or {@code 3d 4h}; below an hour it shows minutes ({@code 0d 0h} would hide
     * the first hour, so {@code 12m} is used until one hour has passed).
     *
     * @param ticks the {@code PLAY_ONE_MINUTE} statistic (game ticks)
     * @return the text
     */
    public static String playtime(long ticks) {
        long minutes = Math.max(0, ticks) / (20L * 60);
        long days = minutes / (60 * 24);
        long hours = (minutes / 60) % 24;
        if (days == 0 && hours == 0) {
            return minutes + "m";
        }
        return days + "d " + hours + "h";
    }
}
