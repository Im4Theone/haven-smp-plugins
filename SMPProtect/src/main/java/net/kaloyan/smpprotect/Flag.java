package net.kaloyan.smpprotect;

import java.util.Locale;
import java.util.Optional;

/**
 * What a region can allow or deny. Without a flag set anywhere, everything is allowed (vanilla behaviour).
 */
public enum Flag {
    BUILD("placing blocks, buckets, boats, minecarts, armor stands, spawn eggs, fire", true),
    BREAK("breaking blocks, frames and paintings", true),
    INTERACT("doors, buttons, levers, redstone, trampling farmland, villagers, horses", true),
    CONTAINER("opening chests, barrels, furnaces, hoppers and other containers", true),
    ENTITY_DAMAGE("hurting animals and villagers, and touching armor stands and item frames", true),
    PVP("players hurting other players", false),
    DAMAGE("any damage to players (void damage still works)", false),
    HUNGER("losing food", false),
    MOB_SPAWN("natural mob spawning", false),
    MOB_GRIEF("endermen, ravagers, withers and other mobs changing blocks", false),
    EXPLOSION("explosions breaking blocks", false),
    FIRE("fire spreading, burning blocks and lava igniting", false);

    private final String description;
    private final boolean playerAction;

    Flag(String description, boolean playerAction) {
        this.description = description;
        this.playerAction = playerAction;
    }

    /** @return what the flag controls */
    public String description() {
        return description;
    }

    /**
     * @return {@code true} if owners and members of the deciding region may still do it (a player doing something);
     *         {@code false} for things that happen to everybody (damage, spawning, explosions)
     */
    public boolean memberExempt() {
        return playerAction;
    }

    /** @return the name used in commands and files, e.g. {@code entity-damage} */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * @param id a flag name as typed, case-insensitive, with {@code -} or {@code _}
     * @return the flag, or empty
     */
    public static Optional<Flag> parse(String id) {
        String normalised = id.toUpperCase(Locale.ROOT).replace('-', '_');
        for (Flag flag : values()) {
            if (flag.name().equals(normalised)) {
                return Optional.of(flag);
            }
        }
        return Optional.empty();
    }
}
