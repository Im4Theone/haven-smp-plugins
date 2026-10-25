package net.kaloyan.smppunish;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and writes lengths of time such as {@code 30m}, {@code 7d}, {@code 1w2d} or {@code perm}. */
public final class Durations {

    /** The value for "never expires". */
    public static final long PERMANENT = 0;
    /** The value returned for text that is not a length of time. */
    public static final long INVALID = -1;

    private static final Pattern PART = Pattern.compile("(\\d{1,6})(mo|y|w|d|h|m|s)");

    private Durations() {
    }

    /**
     * @param text for example {@code 12h}, {@code 1d12h}, {@code 2w}, {@code 1mo} (30 days), {@code 1y} (365 days), {@code perm}
     * @return the length in milliseconds, {@link #PERMANENT} for perm, or {@link #INVALID}
     */
    public static long parse(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.equals("perm") || t.equals("permanent") || t.equals("forever") || t.equals("never")) {
            return PERMANENT;
        }
        Matcher m = PART.matcher(t);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return INVALID;
            }
            end = m.end();
            long n = Long.parseLong(m.group(1));
            total += n * switch (m.group(2)) {
                case "y" -> 365L * 86_400_000L;
                case "mo" -> 30L * 86_400_000L;
                case "w" -> 7L * 86_400_000L;
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
            if (total > 100L * 365L * 86_400_000L) {
                return INVALID;
            }
        }
        return end == t.length() && total > 0 ? total : INVALID;
    }

    /**
     * @param millis a length of time, or {@link #PERMANENT}
     * @return for example {@code 2d 3h}, {@code 5h 10m}, {@code 3m} or {@code permanent}
     */
    public static String format(long millis) {
        if (millis == PERMANENT) {
            return "permanent";
        }
        long seconds = Math.max(0, millis) / 1000;
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        if (days > 0) {
            return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        }
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        return minutes > 0 ? minutes + "m" : Math.max(1, seconds) + "s";
    }
}
