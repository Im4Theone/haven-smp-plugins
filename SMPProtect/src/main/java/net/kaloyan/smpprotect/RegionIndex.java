package net.kaloyan.smpprotect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable snapshot of all regions, grouped by world and ordered so that the first match decides: higher priority
 * first, then the smaller region, then the name.
 */
public final class RegionIndex {

    /**
     * The answer to "may this happen here?".
     *
     * @param allowed   whether it may happen
     * @param decidedBy the region whose flag decided, or {@code null} if no region has the flag (allowed by default)
     */
    public record Verdict(boolean allowed, Region decidedBy) {
    }

    private static final Verdict DEFAULT = new Verdict(true, null);
    private static final Comparator<Region> ORDER = Comparator.comparingInt(Region::priority).reversed()
            .thenComparingLong(Region::volume).thenComparing(Region::name);

    private final Map<String, List<Region>> byWorld;

    /** @param regions every region */
    public RegionIndex(Collection<Region> regions) {
        Map<String, List<Region>> grouped = new HashMap<>();
        for (Region region : regions) {
            grouped.computeIfAbsent(region.world(), w -> new ArrayList<>()).add(region);
        }
        grouped.values().forEach(list -> list.sort(ORDER));
        grouped.replaceAll((w, list) -> List.copyOf(list));
        this.byWorld = Map.copyOf(grouped);
    }

    /**
     * @param world the world's name
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @return every region containing the block, strongest first
     */
    public List<Region> regionsAt(String world, int x, int y, int z) {
        List<Region> out = new ArrayList<>();
        for (Region region : byWorld.getOrDefault(world, List.of())) {
            if (region.contains(world, x, y, z)) {
                out.add(region);
            }
        }
        return out;
    }

    /** @return {@code true} if any region exists in this world (lets listeners skip worlds with nothing to protect) */
    public boolean hasRegions(String world) {
        return byWorld.containsKey(world);
    }

    /**
     * Decides whether something may happen at a block.
     *
     * @param world  the world's name
     * @param x      block X
     * @param y      block Y
     * @param z      block Z
     * @param flag   what is being done
     * @param player who does it, or {@code null} if nobody (explosions, mobs, fire)
     * @return the verdict: the strongest region that has this flag decides; owners and members of that region are
     *         exempt from deny for player actions; with no such region it is allowed
     */
    public Verdict query(String world, int x, int y, int z, Flag flag, UUID player) {
        List<Region> regions = byWorld.get(world);
        if (regions == null) {
            return DEFAULT;
        }
        for (Region region : regions) {
            if (!region.contains(world, x, y, z)) {
                continue;
            }
            Boolean state = region.flags().get(flag);
            if (state == null) {
                continue;
            }
            boolean allowed = state || (flag.memberExempt() && player != null && region.isMember(player));
            return new Verdict(allowed, region);
        }
        return DEFAULT;
    }
}
