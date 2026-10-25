package net.kaloyan.smpsocial;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who is away. A player is away after {@code autoMillis} without activity (or after /afk) and is back on the next activity. Pure logic. */
public final class AfkTracker {

    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> manual = ConcurrentHashMap.newKeySet();
    private final Set<UUID> away = ConcurrentHashMap.newKeySet();

    /**
     * Notes that a player did something.
     *
     * @param id  the player
     * @param now the time
     * @return {@code true} if they were away and are back now
     */
    public boolean activity(UUID id, long now) {
        lastActive.put(id, now);
        manual.remove(id);
        return away.remove(id);
    }

    /**
     * Switches the away state by hand.
     *
     * @param id  the player
     * @param now the time
     * @return {@code true} if they are away now
     */
    public boolean toggle(UUID id, long now) {
        if (away.contains(id)) {
            activity(id, now);
            return false;
        }
        manual.add(id);
        away.add(id);
        lastActive.put(id, now);
        return true;
    }

    /**
     * Marks everybody who has been idle for too long.
     *
     * @param online     the players who are online
     * @param exempt     the players who are never marked automatically
     * @param now        the time
     * @param autoMillis the idle time that makes a player away (0 or less = never)
     * @return the players who just became away
     */
    public Set<UUID> check(Set<UUID> online, Set<UUID> exempt, long now, long autoMillis) {
        Set<UUID> changed = new HashSet<>();
        lastActive.keySet().retainAll(online);
        away.retainAll(online);
        manual.retainAll(online);
        for (UUID id : online) {
            Long last = lastActive.get(id);
            if (last == null) {
                lastActive.put(id, now);
            } else if (autoMillis > 0 && now - last >= autoMillis && !exempt.contains(id) && away.add(id)) {
                changed.add(id);
            }
        }
        return changed;
    }

    /** @return whether the player is away */
    public boolean isAway(UUID id) {
        return away.contains(id);
    }

    /** Forgets a player who left. */
    public void forget(UUID id) {
        lastActive.remove(id);
        manual.remove(id);
        away.remove(id);
    }
}
