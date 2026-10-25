package net.kaloyan.smpspawners;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration. The spawner and price sections use the same keys as the old ISpawners config, so an existing config
 * keeps working.
 *
 * @param refreshTicks    ticks between two production rounds
 * @param maxItems        items one spawner stores before it is full (see {@code multiplier})
 * @param multiplier      capacity = maxItems * multiplier * stacked spawners
 * @param maxStack        most spawners in one block
 * @param activationRange blocks within which a player must be for a spawner to produce
 * @param requireSilk     whether breaking a spawner needs a pickaxe with silk touch
 * @param sneakBatch      most spawners taken out of a stack by one break while sneaking
 * @param protectBlocks   whether explosions and pistons leave stacked spawners alone
 * @param kinds           what each creature's spawner produces, by id
 * @param pricesCents     what one item sells for, in hundredths, by material name
 * @param messages        MiniMessage texts by key
 */
public record Settings(int refreshTicks, long maxItems, double multiplier, int maxStack, int activationRange, boolean requireSilk,
                       int sneakBatch, boolean protectBlocks, Map<String, Kind> kinds, Map<String, Long> pricesCents,
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

    /**
     * @param stack how many spawners are stacked
     * @return how many items such a stack stores before it is full
     */
    public long capacity(int stack) {
        return (long) Math.floor(maxItems * multiplier * stack);
    }

    /**
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        int refresh = config.getInt("refresh_time", 80);
        long maxItems = config.getLong("max_items", 10000);
        double multiplier = config.getDouble("max_items_multiplier", 1.5);
        int maxStack = config.getInt("max_stacked_spawners", 500);
        int range = config.getInt("activation_range", 16);
        int batch = config.getInt("sneak_break_batch", 64);
        if (refresh < 5 || refresh > 12000) {
            problems.add("'refresh_time' must be 5..12000 ticks");
        }
        if (maxItems < 1 || maxItems > 100_000_000L) {
            problems.add("'max_items' must be 1..100000000");
        }
        if (!(multiplier > 0) || multiplier > 1000) {
            problems.add("'max_items_multiplier' must be above 0 and at most 1000");
        }
        if (maxStack < 1 || maxStack > 100_000) {
            problems.add("'max_stacked_spawners' must be 1..100000");
        }
        if (range < 1 || range > 128) {
            problems.add("'activation_range' must be 1..128");
        }
        if (batch < 1 || batch > 2304) {
            problems.add("'sneak_break_batch' must be 1..2304");
        }

        Map<String, Kind> kinds = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("spawners");
        if (section == null || section.getKeys(false).isEmpty()) {
            problems.add("'spawners' must define at least one creature");
        } else {
            for (String name : section.getKeys(false)) {
                String id = name.toUpperCase(Locale.ROOT);
                String where = "spawners." + name;
                if (!id.matches("[A-Z_]{2,40}")) {
                    problems.add("'" + where + "' is not a creature name");
                    continue;
                }
                ConfigurationSection c = section.getConfigurationSection(name);
                if (c == null) {
                    problems.add("'" + where + "' must be a section");
                    continue;
                }
                int exp = c.getInt("exp", 0);
                if (exp < 0 || exp > 1_000_000) {
                    problems.add("'" + where + ".exp' must be 0..1000000");
                }
                List<Kind.Drop> drops = new ArrayList<>();
                List<?> raw = c.getList("loot", List.of());
                for (int i = 0; i < raw.size(); i++) {
                    Kind.Drop drop = drop(problems, where + ".loot[" + (i + 1) + "]", raw.get(i));
                    if (drop != null) {
                        drops.add(drop);
                    }
                }
                if (drops.isEmpty()) {
                    problems.add("'" + where + ".loot' needs at least one drop");
                } else {
                    kinds.put(id, new Kind(id, exp, drops));
                }
            }
        }

        Map<String, Long> prices = new LinkedHashMap<>();
        ConfigurationSection ps = config.getConfigurationSection("prices");
        if (ps != null) {
            for (String name : ps.getKeys(false)) {
                String id = name.toUpperCase(Locale.ROOT);
                if (Material.getMaterial(id) == null) {
                    problems.add("'prices." + name + "' is not an item");
                    continue;
                }
                try {
                    BigDecimal value = new BigDecimal(String.valueOf(ps.get(name)));
                    if (value.signum() < 0 || value.stripTrailingZeros().scale() > 2) {
                        problems.add("'prices." + name + "' must be 0 or more with at most 2 decimals");
                    } else {
                        prices.put(id, value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact());
                    }
                } catch (NumberFormatException | ArithmeticException e) {
                    problems.add("'prices." + name + "' is not a valid price");
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
                } catch (RuntimeException e) {
                    problems.add("message '" + key + "' is invalid: " + e.getMessage());
                }
                messages.put(key, text);
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(refresh, maxItems, multiplier, maxStack, range, config.getBoolean("require_silk_touch", true), batch,
                config.getBoolean("protect_spawners", true), kinds, prices, messages);
    }

    private static Kind.Drop drop(List<String> problems, String where, Object entry) {
        String item;
        int min = 1;
        int max = 1;
        int weight = 1;
        if (entry instanceof Map<?, ?> map) {
            item = String.valueOf(map.get("item")).toUpperCase(Locale.ROOT);
            min = number(map.get("min"), 1);
            max = number(map.get("max"), min);
            weight = number(map.get("weight"), 1);
        } else {
            item = String.valueOf(entry).toUpperCase(Locale.ROOT);
        }
        int before = problems.size();
        Material material = Material.getMaterial(item);
        if (material == null || item.endsWith("AIR")) {
            problems.add("'" + where + "': '" + item + "' is not an item");
        }
        if (min < 0 || max < min || max > 10_000) {
            problems.add("'" + where + "': min and max must satisfy 0 <= min <= max <= 10000");
        }
        if (weight < 1 || weight > 1_000_000) {
            problems.add("'" + where + "': weight must be 1..1000000");
        }
        return problems.size() > before ? null : new Kind.Drop(item, min, max, weight);
    }

    private static int number(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /** @return the text for a message key, or the key itself if the config lacks it */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }
}
