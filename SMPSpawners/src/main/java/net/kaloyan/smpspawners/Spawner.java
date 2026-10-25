package net.kaloyan.smpspawners;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * One stacked spawner block and what it has stored. Main thread only.
 */
public final class Spawner {

    /**
     * Where a spawner is.
     *
     * @param world the world's name
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     */
    public record Pos(String world, int x, int y, int z) {
    }

    private final Pos pos;
    private final String kind;
    private int stack;
    private long xp;
    private final Map<String, Long> loot = new TreeMap<>();

    /**
     * @param pos   where it is
     * @param kind  the creature id
     * @param stack how many spawners are stacked, at least 1
     */
    public Spawner(Pos pos, String kind, int stack) {
        if (stack < 1) {
            throw new IllegalArgumentException("a spawner needs at least one stacked spawner");
        }
        this.pos = pos;
        this.kind = kind;
        this.stack = stack;
    }

    public Pos pos() {
        return pos;
    }

    public String kind() {
        return kind;
    }

    public int stack() {
        return stack;
    }

    public void stack(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("stack must be at least 1");
        }
        this.stack = value;
    }

    /** @return the experience points waiting to be collected */
    public long xp() {
        return xp;
    }

    public void xp(long value) {
        this.xp = Math.max(0, value);
    }

    /** @return the stored items by material name (a copy) */
    public Map<String, Long> loot() {
        return new TreeMap<>(loot);
    }

    /** @return how many items are stored in all */
    public long totalItems() {
        long total = 0;
        for (long n : loot.values()) {
            total += n;
        }
        return total;
    }

    /** @param material the item's name in capitals */
    public void addLoot(String material, long amount) {
        if (amount > 0) {
            loot.merge(material, amount, Long::sum);
        }
    }

    /**
     * Takes everything out of storage.
     *
     * @return what was stored
     */
    public Map<String, Long> takeAllLoot() {
        Map<String, Long> all = new TreeMap<>(loot);
        loot.clear();
        return all;
    }

    /**
     * Takes up to {@code amount} of one item out of storage.
     *
     * @return how many were taken
     */
    public long takeLoot(String material, long amount) {
        long have = loot.getOrDefault(material, 0L);
        long taken = Math.min(have, Math.max(0, amount));
        if (taken > 0) {
            if (taken == have) {
                loot.remove(material);
            } else {
                loot.put(material, have - taken);
            }
        }
        return taken;
    }

    /**
     * @param settings the configuration
     * @return how full the storage is, 0 to 100, rounded down
     */
    public int fillPercent(Settings settings) {
        long capacity = settings.capacity(stack);
        return capacity <= 0 ? 100 : (int) Math.min(100, totalItems() * 100 / capacity);
    }

    /**
     * Runs one production round: every stacked spawner makes one weighted pick and adds experience. Nothing is produced while the
     * storage is full; a round that does not fit is cut off at the capacity.
     *
     * @param kind     what the creature produces
     * @param settings the configuration
     * @param random   the random source
     * @return how many items were added
     */
    public long produce(Kind kind, Settings settings, Random random) {
        long room = settings.capacity(stack) - totalItems();
        if (room <= 0) {
            return 0;
        }
        xp = Math.min(Long.MAX_VALUE / 4, xp + (long) kind.exp() * stack);
        long added = 0;
        for (int i = 0; i < stack && added < room; i++) {
            Kind.Drop drop = kind.pick(random);
            int amount = drop.min() == drop.max() ? drop.min() : drop.min() + random.nextInt(drop.max() - drop.min() + 1);
            long take = Math.min(amount, room - added);
            if (take > 0) {
                addLoot(drop.item(), take);
                added += take;
            }
        }
        return added;
    }
}
