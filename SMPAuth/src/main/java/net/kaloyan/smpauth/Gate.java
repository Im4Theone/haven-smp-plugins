package net.kaloyan.smpauth;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides, before a player is let into the login, whether the name has to be proven with Mojang. Names that were flagged as bought accounts
 * always are; unknown names are when a bought account has that name (so nobody can take the name of somebody else). Names that already
 * have a password account here are not: they log in with the password.
 */
public final class Gate {

    /** What to do with a joining name. */
    public enum Verdict {
        /** let the login go on (password or register) */
        PASS,
        /** make the client prove the account with Mojang first */
        VERIFY,
        /** Mojang could not be asked, so no safe decision exists */
        UNKNOWN,
        /** this address asked about too many names */
        BUSY
    }

    private record Known(boolean exists, long until) {
    }

    private static final long FOUND_MILLIS = 10 * 60_000L;
    private static final long MISSING_MILLIS = 2 * 60_000L;

    private final boolean enabled;
    private final boolean detect;
    private final Predicate<String> flagged;
    private final Predicate<String> hasAccount;
    private final Function<String, CompletableFuture<Mojang.Lookup>> lookup;
    private final Throttle lookups;
    private final Map<String, Known> cache = new ConcurrentHashMap<>();

    /**
     * @param enabled    whether bought accounts are verified at all
     * @param detect     whether unknown names are looked up
     * @param flagged    names that are known bought accounts
     * @param hasAccount names with a password account
     * @param lookup     asks Mojang whether a bought account has the name
     * @param lookups    limits how many names one address may ask about
     */
    public Gate(boolean enabled, boolean detect, Predicate<String> flagged, Predicate<String> hasAccount,
                Function<String, CompletableFuture<Mojang.Lookup>> lookup, Throttle lookups) {
        this.enabled = enabled;
        this.detect = detect;
        this.flagged = flagged;
        this.hasAccount = hasAccount;
        this.lookup = lookup;
        this.lookups = lookups;
    }

    /**
     * @param name the name the client gave
     * @param ip   its address
     * @param now  the time
     * @return the verdict
     */
    public CompletableFuture<Verdict> decide(String name, String ip, long now) {
        if (!enabled) {
            return CompletableFuture.completedFuture(Verdict.PASS);
        }
        if (flagged.test(name)) {
            return CompletableFuture.completedFuture(Verdict.VERIFY);
        }
        if (hasAccount.test(name) || !detect) {
            return CompletableFuture.completedFuture(Verdict.PASS);
        }
        String key = name.toLowerCase(Locale.ROOT);
        Known known = cache.get(key);
        if (known != null && known.until() > now) {
            return CompletableFuture.completedFuture(known.exists() ? Verdict.VERIFY : Verdict.PASS);
        }
        if (lookups.locked("ip:" + ip, now) > 0) {
            return CompletableFuture.completedFuture(Verdict.BUSY);
        }
        lookups.fail("ip:" + ip, now);
        return lookup.apply(name).handle((answer, error) -> {
            if (error != null || answer == null || answer == Mojang.Lookup.ERROR) {
                return Verdict.UNKNOWN;
            }
            boolean exists = answer == Mojang.Lookup.EXISTS;
            cache.put(key, new Known(exists, System.currentTimeMillis() + (exists ? FOUND_MILLIS : MISSING_MILLIS)));
            return exists ? Verdict.VERIFY : Verdict.PASS;
        });
    }

    /** Forgets what was learned about a name (after staff changed it). */
    public void forget(String name) {
        cache.remove(name.toLowerCase(Locale.ROOT));
    }
}
