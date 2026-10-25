package net.kaloyan.smpkits;

/** Writes lengths of time for players. */
public final class Durations {

    private Durations() {
    }

    /**
     * @param millis a length of time
     * @return for example {@code 2d 3h}, {@code 5h 10m}, {@code 3m} or {@code 20s}
     */
    public static String format(long millis) {
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
