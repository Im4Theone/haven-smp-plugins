package net.kaloyan.smpdrill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param size           width and height of the tunnel (odd, 3 to 9; 5 = 5x5)
 * @param depth          layers broken per swing
 * @param sneakMinesOne  whether sneaking turns the drill into a normal pickaxe
 * @param wearPerBlock   durability lost for each extra block (0 = none; the first block wears like any pickaxe)
 * @param unbreakable    whether the item cannot break at all
 * @param name           MiniMessage display name
 * @param lore           MiniMessage lore lines
 * @param enchantments   enchantment key to level
 * @param disabledWorlds worlds where the drill mines one block at a time
 * @param messages       MiniMessage texts by key
 */
public record DrillSettings(int size, int depth, boolean sneakMinesOne, int wearPerBlock, boolean unbreakable, String name,
                            List<String> lore, Map<String, Integer> enchantments, Set<String> disabledWorlds,
                            Map<String, String> messages) {

    /** Every problem found in the configuration. */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        private final String[] problems;

        Invalid(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = problems.toArray(String[]::new);
        }

        /** @return the problems */
        public List<String> problems() {
            return List.of(problems);
        }
    }

    /** @return blocks on each side of the centre */
    public int radius() {
        return size / 2;
    }

    /**
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static DrillSettings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        int size = config.getInt("tunnel.size", 5);
        int depth = config.getInt("tunnel.depth", 1);
        int wear = config.getInt("tunnel.wear-per-extra-block", 0);
        if (size < 3 || size > 9 || size % 2 == 0) {
            problems.add("'tunnel.size' must be an odd number from 3 to 9 (5 = a 5x5 tunnel)");
        }
        if (depth < 1 || depth > 8) {
            problems.add("'tunnel.depth' must be 1..8");
        }
        if (wear < 0 || wear > 10) {
            problems.add("'tunnel.wear-per-extra-block' must be 0..10");
        }
        String name = config.getString("item.name", "<#F472B6><bold>Amethyst Drill");
        List<String> lore = config.getStringList("item.lore");
        try {
            mini.deserialize(name);
            lore.forEach(mini::deserialize);
        } catch (RuntimeException e) {
            problems.add("'item.name' or 'item.lore' is not valid MiniMessage: " + e.getMessage());
        }
        Map<String, Integer> enchants = new LinkedHashMap<>();
        ConfigurationSection e = config.getConfigurationSection("item.enchantments");
        if (e != null) {
            for (String key : e.getKeys(false)) {
                int level = e.getInt(key);
                String id = key.toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z_]+") || level < 1 || level > 255) {
                    problems.add("'item.enchantments." + key + "': the level must be 1..255 and the name only letters and _");
                } else {
                    enchants.put(id, level);
                }
            }
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection m = config.getConfigurationSection("messages");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                String text = m.getString(key, "");
                try {
                    mini.deserialize(text);
                } catch (RuntimeException ex) {
                    problems.add("message '" + key + "' is invalid: " + ex.getMessage());
                }
                messages.put(key, text);
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new DrillSettings(size, depth, config.getBoolean("tunnel.sneak-mines-one", true), wear,
                config.getBoolean("item.unbreakable", false), name, lore, enchants,
                Set.copyOf(config.getStringList("disabled-worlds")), messages);
    }
}
