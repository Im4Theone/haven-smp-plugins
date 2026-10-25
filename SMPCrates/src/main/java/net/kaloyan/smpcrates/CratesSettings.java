package net.kaloyan.smpcrates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param hourlyEnabled whether everybody gets a free crate every round
 * @param hourlyCrate   the crate type given
 * @param intervalMinutes length of a round
 * @param catchUp       whether a player who was offline during a round gets their crate when they join (never more than one)
 * @param crates        the crate types by id, in config order
 * @param keyallAllowed the players (by name) who may use {@code /keyall}; empty = anybody who has the permission
 * @param messages      MiniMessage texts by key
 */
public record CratesSettings(boolean hourlyEnabled, String hourlyCrate, int intervalMinutes, boolean catchUp,
                             Map<String, CrateType> crates, List<String> keyallAllowed, Map<String, String> messages) {

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

    /** The largest item amount of one reward (36 full stacks). */
    public static final int MAX_ITEMS = 64 * 36;

    /**
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static CratesSettings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        int interval = config.getInt("hourly.interval-minutes", 60);
        if (interval < 1 || interval > 10080) {
            problems.add("'hourly.interval-minutes' must be 1..10080");
        }
        String hourlyCrate = config.getString("hourly.crate", "common");
        Map<String, CrateType> crates = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("crates");
        if (section == null || section.getKeys(false).isEmpty()) {
            problems.add("'crates' must define at least one crate");
        } else {
            for (String id : section.getKeys(false)) {
                String where = "crates." + id;
                if (!id.matches("[a-z0-9_-]{1,24}")) {
                    problems.add("'" + where + "': the id may only use a-z, 0-9, _ and - (up to 24 characters)");
                    continue;
                }
                ConfigurationSection c = section.getConfigurationSection(id);
                if (c == null) {
                    problems.add("'" + where + "' must be a section");
                    continue;
                }
                String display = c.getString("display", id);
                String material = c.getString("material", "CHEST").toUpperCase(Locale.ROOT);
                checkMaterial(problems, where + ".material", material);
                tryMini(problems, mini, where + ".display", display);
                List<Reward> rewards = new ArrayList<>();
                List<Map<?, ?>> list = c.getMapList("rewards");
                if (list.isEmpty()) {
                    problems.add("'" + where + ".rewards' must have at least one reward");
                }
                for (int i = 0; i < list.size(); i++) {
                    Reward r = reward(problems, mini, where + ".rewards[" + (i + 1) + "]", list.get(i));
                    if (r != null) {
                        rewards.add(r);
                    }
                }
                if (!rewards.isEmpty()) {
                    crates.put(id, new CrateType(id, display, material, rewards));
                }
            }
        }
        if (!crates.containsKey(hourlyCrate) && config.getBoolean("hourly.enabled", true)) {
            problems.add("'hourly.crate' names '" + hourlyCrate + "', which is not one of the crates " + crates.keySet());
        }
        List<String> allowed = new ArrayList<>();
        for (String name : config.getStringList("keyall.allowed-players")) {
            if (name.matches("[A-Za-z0-9_]{1,16}")) {
                allowed.add(name);
            } else {
                problems.add("'keyall.allowed-players': '" + name + "' is not a valid player name");
            }
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection m = config.getConfigurationSection("messages");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                String text = m.getString(key, "");
                tryMini(problems, mini, "messages." + key, text);
                messages.put(key, text);
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new CratesSettings(config.getBoolean("hourly.enabled", true), hourlyCrate, interval,
                config.getBoolean("hourly.catch-up", true), crates, List.copyOf(allowed), messages);
    }

    private static void tryMini(List<String> problems, MiniMessage mini, String where, String text) {
        try {
            mini.deserialize(text);
        } catch (RuntimeException e) {
            problems.add("'" + where + "' is not valid MiniMessage: " + e.getMessage());
        }
    }

    private static void checkMaterial(List<String> problems, String where, String name) {
        // Whether it is a real item is checked against the running server (Items.unknownEnchantments); here only the name.
        Material m = Material.getMaterial(name);
        if (m == null || name.endsWith("AIR")) {
            problems.add("'" + where + "': '" + name + "' is not an item");
        }
    }

    private static Reward reward(List<String> problems, MiniMessage mini, String where, Map<?, ?> map) {
        Object typeObject = map.get("type");
        Reward.Kind kind;
        try {
            kind = Reward.Kind.valueOf(String.valueOf(typeObject).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("'" + where + ".type' must be item, money, shards or command, but was '" + typeObject + "'");
            return null;
        }
        int before = problems.size();
        int weight = number(map.get("weight"), 1);
        if (weight < 1 || weight > 1_000_000) {
            problems.add("'" + where + ".weight' must be 1..1000000");
        }
        long amount = number(map.get("amount"), 1);
        String material = map.get("material") == null ? "" : String.valueOf(map.get("material")).toUpperCase(Locale.ROOT);
        String name = map.get("name") == null ? "" : String.valueOf(map.get("name"));
        String command = map.get("command") == null ? "" : String.valueOf(map.get("command"));
        tryMini(problems, mini, where + ".name", name);
        Map<String, Integer> enchants = new LinkedHashMap<>();
        switch (kind) {
            case ITEM -> {
                checkMaterial(problems, where + ".material", material);
                if (amount < 1 || amount > MAX_ITEMS) {
                    problems.add("'" + where + ".amount' must be 1.." + MAX_ITEMS);
                }
                if (map.get("enchantments") instanceof Map<?, ?> e) {
                    for (Map.Entry<?, ?> entry : e.entrySet()) {
                        String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                        int level = number(entry.getValue(), 1);
                        if (!key.matches("[a-z_]+") || level < 1 || level > 255) {
                            problems.add("'" + where + ".enchantments': '" + key + "' with level " + level + " is not valid");
                        } else {
                            enchants.put(key, level);
                        }
                    }
                }
            }
            case MONEY, SHARDS -> {
                if (amount < 1 || amount > 1_000_000_000L) {
                    problems.add("'" + where + ".amount' must be 1..1000000000");
                }
                if (material.isEmpty()) {
                    material = kind == Reward.Kind.MONEY ? "GOLD_INGOT" : "PRISMARINE_SHARD";
                } else {
                    checkMaterial(problems, where + ".material", material);
                }
            }
            case COMMAND -> {
                if (command.isBlank()) {
                    problems.add("'" + where + ".command' must not be empty");
                }
                if (material.isEmpty()) {
                    material = "PAPER";
                } else {
                    checkMaterial(problems, where + ".material", material);
                }
            }
            default -> { }
        }
        if (problems.size() > before) {
            return null;
        }
        return new Reward(kind, weight, material, amount, name, enchants, command, Boolean.parseBoolean(String.valueOf(map.get("broadcast"))));
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
