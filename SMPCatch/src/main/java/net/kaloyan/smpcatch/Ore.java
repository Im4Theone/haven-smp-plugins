package net.kaloyan.smpcatch;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.World.Environment;

/**
 * Which fake ores can be placed where.
 *
 * @param name   the name used in commands
 * @param normal the ore in ordinary rock
 * @param deep   the ore in deepslate (the same as {@code normal} if there is no deepslate version)
 * @param env    the dimension it is found in
 * @param minY   the lowest level it naturally appears at
 * @param maxY   the highest level it naturally appears at
 * @param minSize the fewest blocks in a vein
 * @param maxSize the most blocks in a vein
 */
public record Ore(String name, Material normal, Material deep, Environment env, int minY, int maxY, int minSize, int maxSize) {

    private static final List<Ore> ALL = List.of(
            new Ore("diamond", Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Environment.NORMAL, -64, 16, 2, 6),
            new Ore("emerald", Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE, Environment.NORMAL, -16, 100, 1, 1),
            new Ore("gold", Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Environment.NORMAL, -64, 32, 3, 7),
            new Ore("iron", Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Environment.NORMAL, -64, 64, 4, 9),
            new Ore("lapis", Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, Environment.NORMAL, -64, 32, 2, 5),
            new Ore("redstone", Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, Environment.NORMAL, -64, 16, 3, 7),
            new Ore("copper", Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, Environment.NORMAL, -16, 112, 4, 10),
            new Ore("coal", Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, Environment.NORMAL, 0, 128, 4, 12),
            new Ore("debris", Material.ANCIENT_DEBRIS, Material.ANCIENT_DEBRIS, Environment.NETHER, 8, 30, 1, 3),
            new Ore("quartz", Material.NETHER_QUARTZ_ORE, Material.NETHER_QUARTZ_ORE, Environment.NETHER, 10, 117, 3, 7),
            new Ore("nethergold", Material.NETHER_GOLD_ORE, Material.NETHER_GOLD_ORE, Environment.NETHER, 10, 117, 3, 6));

    /**
     * @param name an ore name from a command ({@code netherite} means {@code debris})
     * @param env  the dimension the player is in
     * @return the ore, or {@code null} if there is none of that name in that dimension
     */
    public static Ore find(String name, Environment env) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("netherite")) {
            n = "debris";
        }
        for (Ore ore : ALL) {
            if (ore.name.equals(n) && ore.env == env) {
                return ore;
            }
        }
        return null;
    }

    /**
     * @param env a dimension
     * @return the names usable there
     */
    public static List<String> names(Environment env) {
        return ALL.stream().filter(o -> o.env == env).map(Ore::name).toList();
    }

    /** @return whether any dimension has an ore of that name */
    public static boolean exists(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("netherite") || ALL.stream().anyMatch(o -> o.name.equals(n));
    }
}
