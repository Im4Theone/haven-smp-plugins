package net.kaloyan.smpcrates;

/**
 * The server's crate clock. Rounds are aligned to the wall clock (for a 60 minute interval a new round starts on every full
 * hour, UTC), so the timer is the same for every player and does not restart when the server does.
 */
public final class HourlyClock {

    private final long intervalMs;

    /** @param intervalMinutes length of a round, at least 1 */
    public HourlyClock(int intervalMinutes) {
        if (intervalMinutes < 1) {
            throw new IllegalArgumentException("interval must be at least one minute");
        }
        this.intervalMs = intervalMinutes * 60_000L;
    }

    /**
     * @param nowMs the current time, epoch milliseconds
     * @return the number of the round that is running now
     */
    public long round(long nowMs) {
        return Math.floorDiv(nowMs, intervalMs);
    }

    /**
     * @param nowMs the current time
     * @return milliseconds until the next round starts (1 to the interval)
     */
    public long untilNext(long nowMs) {
        return intervalMs - Math.floorMod(nowMs, intervalMs);
    }

    /**
     * @param ms a duration
     * @return {@code MM:SS}, with minutes above 59 for a long wait, so a full hour reads {@code 60:00}
     */
    public static String format(long ms) {
        long seconds = Math.max(0, (ms + 999) / 1000);
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
