package net.kaloyan.smpcatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param consoleAlerts   print alerts to the console
 * @param logFile         append alerts to alerts.log
 * @param cooldownMillis  the same alert for the same player and decoy is not repeated within this time
 * @param decayPerHour    points lost per hour
 * @param points          points by event: veinHidden, veinVisible, stashOpen, stashBreak, baitTaken, xrayStats
 * @param thresholds      point levels, ascending
 * @param commands        console commands to run per level (parallel to {@code thresholds})
 * @param statsEnabled    whether mining statistics run
 * @param window          statistics window
 * @param minSamples      samples needed before judging
 * @param ratio           share (0..1) of hidden rare ores that raises an alert
 * @param rare            rare ores that count
 * @param bulk            ordinary blocks that count as samples
 * @param hosts           rock that decoys may replace
 * @param veinDrops       whether fake ore drops items
 * @param loot            stash loot
 * @param seedEnabled     automatic decoys in new chunks
 * @param seedWorlds      the worlds for automatic decoys
 * @param seedChance      percent chance per new chunk
 * @param seedTypes       the ore names used for automatic decoys
 * @param vanishPersist   whether vanish survives reconnects and restarts
 * @param fakeMessages    whether vanishing announces a fake leave or join
 * @param vanishSafe      whether vanished staff are protected
 * @param messages        MiniMessage texts by key
 */
public record Settings(boolean consoleAlerts, boolean logFile, long cooldownMillis, double decayPerHour, Map<String, Double> points,
                       List<Double> thresholds, List<List<String>> commands, boolean statsEnabled, int window, int minSamples, double ratio,
                       Set<Material> rare, Set<Material> bulk, Set<Material> hosts, boolean veinDrops, List<Loot> loot, boolean seedEnabled,
                       Set<String> seedWorlds, double seedChance, List<String> seedTypes, boolean vanishPersist, boolean fakeMessages,
                       boolean vanishSafe, Map<String, String> messages) {

    /**
     * One stash item.
     *
     * @param item   the item
     * @param min    the fewest
     * @param max    the most
     * @param chance percent chance to be in a stash
     */
    public record Loot(Material item, int min, int max, double chance) {
    }

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

    /** @return the points for an event key */
    public double pointsFor(String key) {
        return points.getOrDefault(key, 0.0);
    }

    /** @return the text for a message key, or the key itself */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * @param config the configuration
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        Map<String, Double> points = new LinkedHashMap<>();
        for (String key : List.of("vein-hidden", "vein-visible", "stash-open", "stash-break", "bait-taken", "xray-stats")) {
            double v = config.getDouble("points." + key, 0);
            if (v < 0 || v > 1000) {
                problems.add("'points." + key + "' must be 0..1000");
            }
            points.put(key, v);
        }
        double decay = config.getDouble("points.decay-per-hour", 0.5);
        if (decay < 0 || decay > 1000) {
            problems.add("'points.decay-per-hour' must be 0..1000");
        }
        List<Double> thresholds = new ArrayList<>();
        List<List<String>> commands = new ArrayList<>();
        double last = 0;
        for (Map<?, ?> t : config.getMapList("thresholds")) {
            Object p = t.get("points");
            if (!(p instanceof Number n) || n.doubleValue() <= last) {
                problems.add("'thresholds': each level needs 'points' above the one before");
                continue;
            }
            last = n.doubleValue();
            thresholds.add(last);
            List<String> list = new ArrayList<>();
            if (t.get("commands") instanceof List<?> raw) {
                raw.forEach(c -> list.add(String.valueOf(c)));
            }
            commands.add(list);
        }
        int window = config.getInt("stats.window", 300);
        int minSamples = config.getInt("stats.min-samples", 150);
        double percent = config.getDouble("stats.hidden-rare-percent", 4.0);
        if (window < 20 || window > 100000 || minSamples < 10 || minSamples > window) {
            problems.add("'stats.window' must be 20..100000 and 'stats.min-samples' 10..window");
        }
        if (percent <= 0 || percent > 100) {
            problems.add("'stats.hidden-rare-percent' must be above 0 and at most 100");
        }
        Set<Material> rare = materials(problems, "stats.rare", config.getStringList("stats.rare"));
        Set<Material> bulk = materials(problems, "stats.bulk", config.getStringList("stats.bulk"));
        Set<Material> hosts = materials(problems, "hosts", config.getStringList("hosts"));
        if (hosts.isEmpty()) {
            problems.add("'hosts' needs at least one rock");
        }
        List<Loot> loot = new ArrayList<>();
        List<Map<?, ?>> rawLoot = config.getMapList("stash.loot");
        for (int i = 0; i < rawLoot.size(); i++) {
            Map<?, ?> m = rawLoot.get(i);
            String where = "stash.loot[" + (i + 1) + "]";
            Material item = Material.getMaterial(String.valueOf(m.get("item")).toUpperCase(Locale.ROOT));
            int min = number(m.get("min"), 1);
            int max = number(m.get("max"), min);
            double chance = m.get("chance") instanceof Number c ? c.doubleValue() : 100;
            if (item == null || item.name().endsWith("AIR")) {
                problems.add("'" + where + ".item' is not an item");
            } else if (min < 1 || max < min || max > 64 || chance <= 0 || chance > 100) {
                problems.add("'" + where + "' needs 1 <= min <= max <= 64 and a chance above 0 up to 100");
            } else {
                loot.add(new Loot(item, min, max, chance));
            }
        }
        Set<String> worlds = new TreeSet<>(config.getStringList("auto-seed.worlds"));
        List<String> types = new ArrayList<>();
        for (String type : config.getStringList("auto-seed.types")) {
            if (Ore.exists(type)) {
                types.add(type.toLowerCase(Locale.ROOT));
            } else {
                problems.add("'auto-seed.types': '" + type + "' is not a known ore");
            }
        }
        double seedChance = config.getDouble("auto-seed.chance-percent", 6);
        if (seedChance < 0 || seedChance > 100) {
            problems.add("'auto-seed.chance-percent' must be 0..100");
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
        long cooldown = config.getLong("alerts.cooldown-seconds", 2);
        if (cooldown < 0 || cooldown > 3600) {
            problems.add("'alerts.cooldown-seconds' must be 0..3600");
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(config.getBoolean("alerts.console", true), config.getBoolean("alerts.log-file", true), cooldown * 1000, decay,
                points, thresholds, commands, config.getBoolean("stats.enabled", true), window, minSamples, percent / 100.0, rare, bulk, hosts,
                config.getBoolean("veins.drops", true), loot, config.getBoolean("auto-seed.enabled", false), worlds, seedChance, types,
                config.getBoolean("vanish.persist", true), config.getBoolean("vanish.fake-messages", true), config.getBoolean("vanish.safe", true),
                messages);
    }

    private static Set<Material> materials(List<String> problems, String where, List<String> names) {
        Set<Material> out = new TreeSet<>();
        for (String name : names) {
            Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                problems.add("'" + where + "': '" + name + "' is not a block or item");
            } else {
                out.add(material);
            }
        }
        return out;
    }

    private static int number(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }
}
