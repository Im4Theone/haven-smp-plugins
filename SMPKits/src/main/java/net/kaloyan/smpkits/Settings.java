package net.kaloyan.smpkits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param excludedGroups ranks (lower case LuckPerms group names) that get no kits
 * @param kits           the kits by id, in menu order
 * @param titles         MiniMessage menu titles by key
 * @param messages       MiniMessage texts by key
 */
public record Settings(Set<String> excludedGroups, Map<String, Kit> kits, Map<String, String> titles, Map<String, String> messages) {

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

    /** @return the text for a message key, or the key itself */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    /** @return the title for a key, or the key itself */
    public String title(String key) {
        return titles.getOrDefault(key, key);
    }

    /**
     * @param config the configuration
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        Set<String> excluded = new TreeSet<>();
        for (String g : config.getStringList("excluded-groups")) {
            excluded.add(g.toLowerCase(Locale.ROOT));
        }
        Map<String, Kit> kits = new LinkedHashMap<>();
        Set<Integer> slots = new TreeSet<>();
        ConfigurationSection ks = config.getConfigurationSection("kits");
        if (ks == null || ks.getKeys(false).isEmpty()) {
            problems.add("'kits' needs at least one kit");
        } else {
            for (String id : ks.getKeys(false)) {
                String where = "kits." + id;
                ConfigurationSection k = ks.getConfigurationSection(id);
                if (k == null || !id.matches("[a-z0-9_]{2,24}")) {
                    problems.add("'" + where + "' needs a lower case id of 2..24 letters, digits or underscores, and must be a section");
                    continue;
                }
                Material icon = Material.getMaterial(k.getString("icon", "").toUpperCase(Locale.ROOT));
                if (icon == null) {
                    problems.add("'" + where + ".icon' is not an item");
                }
                int slot = k.getInt("slot", -1);
                if (slot < 0 || slot > 26 || !slots.add(slot)) {
                    problems.add("'" + where + ".slot' must be 0..26 and different from the other kits");
                }
                long cooldown = cooldown(String.valueOf(k.get("cooldown", "1d")));
                if (cooldown == 0) {
                    problems.add("'" + where + ".cooldown' must be a length of time such as 30m, 12h, 3d, 1w or 'once'");
                }
                markup(problems, mini, where + ".name", k.getString("name", ""));
                List<Kit.Spec> items = new ArrayList<>();
                List<Map<?, ?>> raw = k.getMapList("items");
                if (raw.isEmpty() && k.getStringList("commands").isEmpty()) {
                    problems.add("'" + where + "' needs items or commands");
                }
                for (int i = 0; i < raw.size(); i++) {
                    Kit.Spec spec = spec(problems, mini, where + ".items[" + (i + 1) + "]", raw.get(i));
                    if (spec != null) {
                        items.add(spec);
                    }
                }
                if (items.size() > 36) {
                    problems.add("'" + where + ".items' has more than 36 entries");
                }
                if (icon != null) {
                    kits.put(id, new Kit(id, k.getString("name", id), k.getString("role", id), icon, slot, cooldown, items, k.getStringList("commands")));
                }
            }
        }
        Map<String, String> titles = texts(problems, mini, config.getConfigurationSection("titles"), "title ");
        Map<String, String> messages = texts(problems, mini, config.getConfigurationSection("messages"), "message ");
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(excluded, kits, titles, messages);
    }

    /** @return the cooldown in milliseconds, {@link Kit#ONCE}, or 0 if the text is not valid */
    static long cooldown(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.equals("once")) {
            return Kit.ONCE;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,5})(w|d|h|m)").matcher(t);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                return 0;
            }
            end = m.end();
            long n = Long.parseLong(m.group(1));
            total += n * switch (m.group(2)) {
                case "w" -> 7L * 86_400_000L;
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                default -> 60_000L;
            };
        }
        return end == t.length() ? total : 0;
    }

    private static Kit.Spec spec(List<String> problems, MiniMessage mini, String where, Map<?, ?> m) {
        Material material = Material.getMaterial(String.valueOf(m.get("item")).toUpperCase(Locale.ROOT));
        if (material == null || material.name().endsWith("AIR")) {
            problems.add("'" + where + ".item' is not an item");
            return null;
        }
        int amount = m.get("amount") instanceof Number n ? n.intValue() : 1;
        if (amount < 1 || amount > 64) {
            problems.add("'" + where + ".amount' must be 1..64");
            return null;
        }
        String name = m.get("name") == null ? null : String.valueOf(m.get("name"));
        if (name != null) {
            markup(problems, mini, where + ".name", name);
        }
        List<String> lore = new ArrayList<>();
        if (m.get("lore") instanceof List<?> l) {
            l.forEach(line -> {
                markup(problems, mini, where + ".lore", String.valueOf(line));
                lore.add(String.valueOf(line));
            });
        }
        Map<String, Integer> enchants = new TreeMap<>();
        if (m.get("enchants") instanceof Map<?, ?> e) {
            for (Map.Entry<?, ?> entry : e.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (!key.matches("[a-z_]{2,40}") || !(entry.getValue() instanceof Number level) || level.intValue() < 1 || level.intValue() > 10) {
                    problems.add("'" + where + ".enchants': '" + key + "' needs a level of 1..10");
                } else {
                    enchants.put(key, level.intValue());
                }
            }
        }
        return new Kit.Spec(material, amount, name, lore, enchants);
    }

    private static Map<String, String> texts(List<String> problems, MiniMessage mini, ConfigurationSection section, String label) {
        Map<String, String> out = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String text = section.getString(key, "");
                markup(problems, mini, label + "'" + key + "'", text);
                out.put(key, text);
            }
        }
        return out;
    }

    private static void markup(List<String> problems, MiniMessage mini, String where, String text) {
        try {
            mini.deserialize(text);
        } catch (RuntimeException e) {
            problems.add(where + " is invalid: " + e.getMessage());
        }
    }
}
