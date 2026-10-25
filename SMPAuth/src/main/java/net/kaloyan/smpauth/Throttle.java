package net.kaloyan.smpauth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Stops password guessing. Wrong passwords are counted per key (an account name or an address); after {@code maxFailures} within
 * {@code windowMillis} the key is locked for {@code lockMillis}. A right password clears the count. Pure logic.
 */
public final class Throttle {

    private final int maxFailures;
    private final long windowMillis;
    private final long lockMillis;
    private final Map<String, Deque<Long>> failures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();

    /**
     * @param maxFailures  wrong passwords allowed within the window
     * @param windowMillis the window
     * @param lockMillis   how long a key stays locked
     */
    public Throttle(int maxFailures, long windowMillis, long lockMillis) {
        this.maxFailures = maxFailures;
        this.windowMillis = windowMillis;
        this.lockMillis = lockMillis;
    }

    /**
     * @param key the account or address
     * @param now the time
     * @return how many milliseconds the key is still locked, 0 if it is not
     */
    public synchronized long locked(String key, long now) {
        Long until = lockedUntil.get(key);
        if (until == null) {
            return 0;
        }
        if (until <= now) {
            lockedUntil.remove(key);
            failures.remove(key);
            return 0;
        }
        return until - now;
    }

    /**
     * Counts a wrong password.
     *
     * @param key the account or address
     * @param now the time
     * @return {@code true} if the key is locked now
     */
    public synchronized boolean fail(String key, long now) {
        Deque<Long> times = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
            times.removeFirst();
        }
        if (times.size() >= maxFailures) {
            lockedUntil.put(key, now + lockMillis);
            return true;
        }
        return false;
    }

    /**
     * Forgets the failures of a key (a right password).
     *
     * @param key the account or address
     */
    public synchronized void clear(String key) {
        failures.remove(key);
        lockedUntil.remove(key);
    }
}
