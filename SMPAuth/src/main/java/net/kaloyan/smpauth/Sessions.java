package net.kaloyan.smpauth;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Remembers for a few minutes that a name logged in from an address, so a quick reconnect needs no password. Pure logic. */
public final class Sessions {

    private record Session(String ip, long until) {
    }

    private final Map<String, Session> sessions = new HashMap<>();

    /**
     * Remembers a login.
     *
     * @param name   the name
     * @param ip     the address
     * @param now    the time
     * @param length how long the session lasts (0 or less = none is made)
     */
    public synchronized void start(String name, String ip, long now, long length) {
        if (length > 0 && ip != null) {
            sessions.put(name.toLowerCase(Locale.ROOT), new Session(ip, now + length));
        }
    }

    /**
     * @param name the name
     * @param ip   the address the player joins from
     * @param now  the time
     * @return whether the name logged in from exactly this address recently enough
     */
    public synchronized boolean valid(String name, String ip, long now) {
        String key = name.toLowerCase(Locale.ROOT);
        Session s = sessions.get(key);
        if (s == null) {
            return false;
        }
        if (s.until() < now) {
            sessions.remove(key);
            return false;
        }
        return ip != null && ip.equals(s.ip());
    }

    /** Forgets a name's session (log out, a changed or deleted account). */
    public synchronized void end(String name) {
        sessions.remove(name.toLowerCase(Locale.ROOT));
    }
}
