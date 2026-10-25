package net.kaloyan.smptravel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param warmupSeconds    seconds every player must stand still before a teleport
 * @param tpaCooldown      seconds between two teleport requests of one player
 * @param tpaExpire        seconds a teleport request stays valid
 * @param defaultHomes     homes a player may have without a {@code smptravel.homes.N} permission
 * @param spawnWorld       world of {@code /spawn} until an admin runs {@code /setspawn}
 * @param hubWorld         world of {@code /hub} until an admin runs {@code /setspawn hub}
 * @param firstJoinHub     whether new players start in the hub
 * @param respawnMode      {@code hub}, {@code bed} or {@code vanilla} (see {@link Respawn})
 * @param respawn          where players respawn when no hub was saved with {@code /setspawn hub}
 * @param messages         MiniMessage texts by key
 */
public record TravelSettings(int warmupSeconds, int tpaCooldown, int tpaExpire, int defaultHomes, String spawnWorld,
                             String hubWorld, boolean firstJoinHub, String respawnMode, Spot respawn,
                             Map<String, String> messages) {

    /** The highest {@code smptravel.homes.N} permission that is looked for. */
    public static final int MAX_HOME_PERMISSION = 100;

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
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static TravelSettings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        int warmup = config.getInt("warmup-seconds", 5);
        int cooldown = config.getInt("tpa.cooldown-seconds", 10);
        int expire = config.getInt("tpa.expire-seconds", 60);
        int homes = config.getInt("homes.default", 1);
        if (warmup < 0 || warmup > 60) {
            problems.add("'warmup-seconds' must be 0..60");
        }
        if (cooldown < 0 || cooldown > 3600) {
            problems.add("'tpa.cooldown-seconds' must be 0..3600");
        }
        if (expire < 5 || expire > 600) {
            problems.add("'tpa.expire-seconds' must be 5..600");
        }
        if (homes < 0 || homes > MAX_HOME_PERMISSION) {
            problems.add("'homes.default' must be 0.." + MAX_HOME_PERMISSION);
        }
        String spawn = config.getString("spawn-world", "world");
        String hub = config.getString("hub-world", "hub");
        if (spawn.isBlank() || hub.isBlank()) {
            problems.add("'spawn-world' and 'hub-world' must name a world");
        }
        String mode = config.getString("respawn.mode", "hub").toLowerCase(java.util.Locale.ROOT);
        if (!List.of("hub", "bed", "vanilla").contains(mode)) {
            problems.add("'respawn.mode' must be hub, bed or vanilla, but was '" + mode + "'");
        }
        String respawnWorld = config.getString("respawn.world", "hub");
        if (respawnWorld.isBlank()) {
            problems.add("'respawn.world' must name a world");
        }
        Spot respawn = new Spot(respawnWorld, config.getDouble("respawn.x", 0.5), config.getDouble("respawn.y", 100),
                config.getDouble("respawn.z", 0.5), (float) config.getDouble("respawn.yaw", 0), (float) config.getDouble("respawn.pitch", 0));
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
        return new TravelSettings(warmup, cooldown, expire, homes, spawn, hub, config.getBoolean("first-join-hub", true), mode, respawn,
                messages);
    }

    /**
     * How many homes a player may have.
     *
     * @param defaultHomes the configured default
     * @param has          whether the player has a permission node
     * @return the limit: the highest of the default and any {@code smptravel.homes.N} they have, or unlimited
     */
    public static int homeLimit(int defaultHomes, IntPredicate has) {
        int limit = defaultHomes;
        for (int n = 1; n <= MAX_HOME_PERMISSION; n++) {
            if (n > limit && has.test(n)) {
                limit = n;
            }
        }
        return limit;
    }
}
