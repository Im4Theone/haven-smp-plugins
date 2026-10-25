package net.kaloyan.smpinfo;

/** Which announcement comes next. */
public final class Announcements {

    private Announcements() {
    }

    /**
     * @param current the index shown last (any number, also a stale one after the list got shorter)
     * @param size    how many announcements there are now
     * @return the index to show next: the one after the last, wrapping around
     */
    public static int next(int current, int size) {
        return size <= 0 ? 0 : Math.floorMod(current + 1, size);
    }
}
