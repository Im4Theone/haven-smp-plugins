package net.kaloyan.smpdaily;

/**
 * The rules of the daily reward. A reward can be claimed once every {@code COOLDOWN} (24 hours). Claim again within {@code GRACE} (48 hours
 * of the last claim) and the streak goes on; wait longer and it starts again at day 1. After the last day of the cycle it starts again.
 */
public final class Streak {

    /** Time between two claims. */
    public static final long COOLDOWN = 24L * 3_600_000L;
    /** The streak survives a claim made within this long after the last one. */
    public static final long GRACE = 48L * 3_600_000L;

    private Streak() {
    }

    /**
     * @param last   when the last reward was claimed (epoch milliseconds), 0 if never
     * @param now    the time
     * @return whether a reward can be claimed now
     */
    public static boolean ready(long last, long now) {
        return last == 0 || now - last >= COOLDOWN;
    }

    /**
     * @param last the time of the last claim, 0 if never
     * @param day  the day of the cycle that was claimed last (1-based), 0 if never
     * @param now  the time
     * @param days how many days one cycle has
     * @return the day (1-based) the next claim is for
     */
    public static int nextDay(long last, int day, long now, int days) {
        if (last == 0 || day < 1 || now - last > GRACE) {
            return 1;
        }
        return day % days + 1;
    }

    /**
     * @param last the time of the last claim, 0 if never
     * @param now  the time
     * @return milliseconds until the next claim is possible, 0 if it is possible now
     */
    public static long until(long last, long now) {
        return last == 0 ? 0 : Math.max(0, last + COOLDOWN - now);
    }

    /**
     * @param last the time of the last claim, 0 if never
     * @param now  the time
     * @return whether the streak is already lost (the grace time has passed)
     */
    public static boolean lost(long last, long now) {
        return last != 0 && now - last > GRACE;
    }
}
