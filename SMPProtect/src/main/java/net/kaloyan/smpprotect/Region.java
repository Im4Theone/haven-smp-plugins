package net.kaloyan.smpprotect;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A named cuboid in one world. Immutable: every change returns a new region, so the manager can swap its index
 * atomically.
 *
 * @param name     unique name, lower case
 * @param world    the world's name
 * @param minX     lowest X (inclusive)
 * @param minY     lowest Y
 * @param minZ     lowest Z
 * @param maxX     highest X (inclusive)
 * @param maxY     highest Y
 * @param maxZ     highest Z
 * @param priority higher priority regions win where regions overlap
 * @param owners   players who may do everything player-related in the region and manage its members
 * @param members  players exempt from the player-action flags (build, break, interact...)
 * @param flags    {@code true} = allow, {@code false} = deny; a missing flag is undecided
 * @param greeting MiniMessage text shown to a player who enters the region; empty = none
 * @param farewell MiniMessage text shown to a player who leaves the region; empty = none
 */
public record Region(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int priority,
                     Set<UUID> owners, Set<UUID> members, Map<Flag, Boolean> flags, String greeting, String farewell) {

    public Region {
        owners = Set.copyOf(owners);
        members = Set.copyOf(members);
        flags = Map.copyOf(flags);
        greeting = greeting == null ? "" : greeting;
        farewell = farewell == null ? "" : farewell;
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("min must not be above max");
        }
    }

    /**
     * Creates a region from two corners in any order.
     *
     * @param name  the name
     * @param world the world's name
     * @return the region with no flags, owners, members or messages and priority 0
     */
    public static Region of(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(name, world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2),
                Math.max(y1, y2), Math.max(z1, z2), 0, Set.of(), Set.of(), Map.of(), "", "");
    }

    /**
     * A region covering a whole world, from bedrock to the sky and out to the world border.
     *
     * @param name  the name
     * @param world the world's name
     * @return the region
     */
    public static Region wholeWorld(String name, String world) {
        return of(name, world, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE);
    }

    /** @return {@code true} if this region covers every block of its world */
    public boolean isWholeWorld() {
        return minX == Integer.MIN_VALUE && maxX == Integer.MAX_VALUE && minY == Integer.MIN_VALUE
                && maxY == Integer.MAX_VALUE && minZ == Integer.MIN_VALUE && maxZ == Integer.MAX_VALUE;
    }

    /** @return {@code true} if the block at these coordinates, in {@code world}, is inside */
    public boolean contains(String world, int x, int y, int z) {
        return this.world.equals(world) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** @return the number of blocks (saturating) */
    public long volume() {
        return (long) Math.min(1e18, ((double) maxX - minX + 1) * ((double) maxY - minY + 1) * ((double) maxZ - minZ + 1));
    }

    /** @return {@code true} if {@code player} is an owner or a member */
    public boolean isMember(UUID player) {
        return owners.contains(player) || members.contains(player);
    }

    public Region withPriority(int value) {
        return new Region(name, world, minX, minY, minZ, maxX, maxY, maxZ, value, owners, members, flags, greeting, farewell);
    }

    /** @param state {@code null} removes the flag */
    public Region withFlag(Flag flag, Boolean state) {
        Map<Flag, Boolean> copy = new EnumMap<>(Flag.class);
        copy.putAll(flags);
        if (state == null) {
            copy.remove(flag);
        } else {
            copy.put(flag, state);
        }
        return new Region(name, world, minX, minY, minZ, maxX, maxY, maxZ, priority, owners, members, copy, greeting, farewell);
    }

    public Region withOwner(UUID player, boolean add) {
        Set<UUID> copy = new TreeSet<>(owners);
        if (add ? copy.add(player) : copy.remove(player)) {
            return new Region(name, world, minX, minY, minZ, maxX, maxY, maxZ, priority, copy, members, flags, greeting, farewell);
        }
        return this;
    }

    public Region withMember(UUID player, boolean add) {
        Set<UUID> copy = new TreeSet<>(members);
        if (add ? copy.add(player) : copy.remove(player)) {
            return new Region(name, world, minX, minY, minZ, maxX, maxY, maxZ, priority, owners, copy, flags, greeting, farewell);
        }
        return this;
    }

    /** @return the same region with new bounds */
    public Region withBounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(name, world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2),
                Math.max(y1, y2), Math.max(z1, z2), priority, owners, members, flags, greeting, farewell);
    }

    /** @param text MiniMessage text, or empty to remove it */
    public Region withGreeting(String text) {
        return new Region(name, world, minX, minY, minZ, maxX, maxY, maxZ, priority, owners, members, flags, text, farewell);
    }

    /** @param text MiniMessage text, or empty to remove it */
    public Region withFarewell(String text) {
        return new Region(name, world, minX, minY, minZ, maxX, maxY, maxZ, priority, owners, members, flags, greeting, text);
    }
}
