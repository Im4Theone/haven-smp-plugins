package net.kaloyan.smpstats;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Writes numbers for players. */
public final class Fmt {

    private Fmt() {
    }

    /**
     * @param n a number
     * @return the number with thousands separators, for example {@code 12,345}
     */
    public static String number(long n) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).format(n);
    }

    /**
     * @param n an amount of money in whole units
     * @return a short form: {@code 950}, {@code 12.5K}, {@code 3.2M}, {@code 1.1B}
     */
    public static String compact(long n) {
        double v = Math.abs((double) n);
        String sign = n < 0 ? "-" : "";
        if (v >= 1e12) {
            return sign + trim(v / 1e12) + "T";
        }
        if (v >= 1e9) {
            return sign + trim(v / 1e9) + "B";
        }
        if (v >= 1e6) {
            return sign + trim(v / 1e6) + "M";
        }
        if (v >= 1e4) {
            return sign + trim(v / 1e3) + "K";
        }
        return number(n);
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.1f", Math.floor(v * 10) / 10.0);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /**
     * @param minutes minutes played
     * @return for example {@code 3d 4h}, {@code 5h 10m} or {@code 12m}
     */
    public static String playtime(long minutes) {
        long days = minutes / 1440;
        long hours = minutes % 1440 / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + mins + "m" : mins + "m";
    }
}
