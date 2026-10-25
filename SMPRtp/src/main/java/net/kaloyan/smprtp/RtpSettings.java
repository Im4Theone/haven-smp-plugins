package net.kaloyan.smprtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param cooldownSeconds seconds between two random teleports of one player
 * @param warmupSeconds   seconds the player must stand still before being teleported
 * @param rounds          how many rounds of candidate spots are tried before giving up
 * @param candidates      how many spots are tried (loaded in parallel) per round
 * @param defaultWorld    destination key used by a plain {@code /rtp}
 * @param avoidProtected  never drop anyone inside an SMP-Protect region that denies building
 * @param worlds          destinations by key
 * @param messages        MiniMessage texts by key
 */
public record RtpSettings(int cooldownSeconds, int warmupSeconds, int rounds, int candidates, String defaultWorld,
                          boolean avoidProtected, Map<String, RtpWorld> worlds, Map<String, String> messages) {

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
            return List.of(this.problems);
        }
    }

    /** The destination keys, in the order they are listed. */
    public static final List<String> KEYS = List.of("overworld", "nether", "end");

    /**
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static RtpSettings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        int cooldown = config.getInt("cooldown-seconds", 60);
        int warmup = config.getInt("warmup-seconds", 5);
        int rounds = config.getInt("search.rounds", 8);
        int candidates = config.getInt("search.candidates-per-round", 6);
        if (cooldown < 0 || cooldown > 86400) {
            problems.add("'cooldown-seconds' must be 0..86400");
        }
        if (warmup < 0 || warmup > 60) {
            problems.add("'warmup-seconds' must be 0..60");
        }
        if (rounds < 1 || rounds > 100) {
            problems.add("'search.rounds' must be 1..100");
        }
        if (candidates < 1 || candidates > 20) {
            problems.add("'search.candidates-per-round' must be 1..20");
        }
        Map<String, RtpWorld> worlds = new LinkedHashMap<>();
        for (String key : KEYS) {
            ConfigurationSection s = config.getConfigurationSection("worlds." + key);
            if (s == null) {
                problems.add("'worlds." + key + "' is missing");
                continue;
            }
            String world = s.getString("world", "");
            int min = s.getInt("min-radius", 0);
            int size = s.getInt("size", 0);
            int minY = s.getInt("min-y", -64);
            int maxY = s.getInt("max-y", -1);
            double cost = s.getDouble("cost", 0);
            if (world.isBlank()) {
                problems.add("'worlds." + key + ".world' must name a world");
            }
            if (size < 2 || size > 58_000_000) {
                problems.add("'worlds." + key + ".size' must be 2..58000000 (the side of the square, in blocks)");
            }
            if (min < 0 || min >= size / 2) {
                problems.add("'worlds." + key + "': min-radius must be >= 0 and below half the size");
            }
            if (maxY != -1 && maxY < minY) {
                problems.add("'worlds." + key + "': max-y must be -1 or at least min-y");
            }
            if (cost < 0 || Double.isNaN(cost)) {
                problems.add("'worlds." + key + ".cost' must not be negative");
            }
            worlds.put(key, new RtpWorld(key, world, s.getBoolean("enabled", true), s.getInt("center-x", 0),
                    s.getInt("center-z", 0), min, size, minY, maxY, cost));
        }
        String defaultWorld = config.getString("default-world", "overworld");
        if (!KEYS.contains(defaultWorld)) {
            problems.add("'default-world' must be one of " + KEYS);
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection m = config.getConfigurationSection("messages");
        if (m != null) {
            MiniMessage mini = MiniMessage.miniMessage();
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
        return new RtpSettings(cooldown, warmup, rounds, candidates, defaultWorld, config.getBoolean("avoid-protected-regions", true),
                Map.copyOf(worlds), Map.copyOf(messages));
    }

    /** @return the text for a message key, or the key itself if the config lacks it */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }
}
