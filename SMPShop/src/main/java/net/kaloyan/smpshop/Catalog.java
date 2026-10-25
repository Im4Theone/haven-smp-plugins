package net.kaloyan.smpshop;

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
 * Everything the shop sells, validated once when the config is loaded.
 *
 * @param categories    the money shop categories by id
 * @param keys          crates sold for shards
 * @param spawners      spawners sold for shards
 * @param messages      MiniMessage texts by key
 * @param titles        MiniMessage menu titles by key
 * @param rewardMinutes minutes between two shard rolls
 * @param rewardChance  percent chance that a roll gives a shard
 */
public record Catalog(Map<String, Category> categories, List<Offer> keys, List<Offer> spawners, Map<String, String> messages,
                      Map<String, String> titles, int rewardMinutes, int rewardChance) {

    /**
     * One item for money.
     *
     * @param material the item
     * @param cents    the price of one item in hundredths
     */
    public record Entry(Material material, long cents) {
    }

    /**
     * A page of the money shop.
     *
     * @param id    the config key
     * @param title MiniMessage title
     * @param name  MiniMessage name on the main menu
     * @param icon  the icon on the main menu
     * @param slot  the slot on the main menu
     * @param items what it sells
     */
    public record Category(String id, String title, String name, Material icon, int slot, List<Entry> items) {
    }

    /** What an offer delivers. */
    public enum Kind { KEY, SPAWNER }

    /**
     * Something sold for shards.
     *
     * @param kind   key or spawner
     * @param id     crate id or creature
     * @param name   MiniMessage name
     * @param icon   the icon
     * @param shards the price of one
     */
    public record Offer(Kind kind, String id, String name, Material icon, long shards) {
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

    /** @return the text for a message key, or the key itself */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    /** @return the menu title for a key, or the key itself */
    public String title(String key) {
        return titles.getOrDefault(key, key);
    }

    /**
     * @param config the configuration
     * @return the catalog
     * @throws Invalid listing every problem
     */
    public static Catalog parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        Map<String, Category> categories = new LinkedHashMap<>();
        ConfigurationSection cs = config.getConfigurationSection("categories");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(id);
                String where = "categories." + id;
                if (c == null) {
                    problems.add("'" + where + "' must be a section");
                    continue;
                }
                Material icon = material(problems, where + ".icon", c.getString("icon"));
                int slot = c.getInt("slot", -1);
                if (slot < 0 || slot > 26) {
                    problems.add("'" + where + ".slot' must be 0..26");
                }
                markup(problems, mini, where + ".title", c.getString("title", ""));
                markup(problems, mini, where + ".name", c.getString("name", ""));
                List<Entry> items = new ArrayList<>();
                List<Map<?, ?>> raw = c.getMapList("items");
                if (raw.isEmpty() || raw.size() > 36) {
                    problems.add("'" + where + ".items' needs 1..36 entries");
                }
                for (int i = 0; i < raw.size(); i++) {
                    Map<?, ?> m = raw.get(i);
                    String at = where + ".items[" + (i + 1) + "]";
                    Material material = material(problems, at + ".item", String.valueOf(m.get("item")));
                    long cents = cents(problems, at + ".price", m.get("price"));
                    if (material != null && cents >= 0) {
                        items.add(new Entry(material, cents));
                    }
                }
                if (icon != null) {
                    categories.put(id, new Category(id, c.getString("title", id), c.getString("name", id), icon, slot, items));
                }
            }
        }
        List<Offer> keys = offers(problems, mini, config.getMapList("shard-shop.keys"), Kind.KEY);
        List<Offer> spawners = offers(problems, mini, config.getMapList("shard-shop.spawners"), Kind.SPAWNER);
        if (keys.size() > 5) {
            problems.add("'shard-shop.keys' can hold at most 5 keys");
        }
        if (spawners.size() > 9) {
            problems.add("'shard-shop.spawners' can hold at most 9 spawners");
        }
        Map<String, String> messages = texts(problems, mini, config.getConfigurationSection("messages"), "message ");
        Map<String, String> titles = texts(problems, mini, config.getConfigurationSection("titles"), "title ");
        int minutes = config.getInt("shards.interval-minutes", 10);
        int chance = config.getInt("shards.chance-percent", 50);
        if (minutes < 1 || minutes > 1440) {
            problems.add("'shards.interval-minutes' must be 1..1440");
        }
        if (chance < 0 || chance > 100) {
            problems.add("'shards.chance-percent' must be 0..100");
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Catalog(categories, keys, spawners, messages, titles, minutes, chance);
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

    private static List<Offer> offers(List<String> problems, MiniMessage mini, List<Map<?, ?>> raw, Kind kind) {
        List<Offer> out = new ArrayList<>();
        String section = kind == Kind.KEY ? "shard-shop.keys" : "shard-shop.spawners";
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> m = raw.get(i);
            String at = section + "[" + (i + 1) + "]";
            String id = String.valueOf(m.get(kind == Kind.KEY ? "id" : "creature")).toLowerCase(Locale.ROOT);
            boolean idOk = id.matches("[a-z_]{2,40}");
            if (!idOk) {
                problems.add("'" + at + "' has an invalid id");
            }
            Material icon = material(problems, at + ".icon", String.valueOf(m.get("icon")));
            String name = String.valueOf(m.get("name"));
            markup(problems, mini, at + ".name", name);
            Object price = m.get("price");
            long shards = price instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() >= 1 && n.longValue() <= 1_000_000_000L ? n.longValue() : -1;
            if (shards < 0) {
                problems.add("'" + at + ".price' must be a whole number of shards, 1 or more");
            }
            if (icon != null && shards > 0 && idOk) {
                out.add(new Offer(kind, kind == Kind.SPAWNER ? id.toUpperCase(Locale.ROOT) : id, name, icon, shards));
            }
        }
        return out;
    }

    private static Material material(List<String> problems, String where, String name) {
        Material material = name == null ? null : Material.getMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null || name.toUpperCase(Locale.ROOT).endsWith("AIR")) {
            problems.add("'" + where + "': '" + name + "' is not an item");
            return null;
        }
        return material;
    }

    private static long cents(List<String> problems, String where, Object value) {
        try {
            BigDecimal price = new BigDecimal(String.valueOf(value));
            if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2 || price.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
                problems.add("'" + where + "' must be above 0, at most 1000000000, with at most 2 decimals");
                return -1;
            }
            return price.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException e) {
            problems.add("'" + where + "' is not a valid price");
            return -1;
        }
    }

    private static void markup(List<String> problems, MiniMessage mini, String where, String text) {
        try {
            mini.deserialize(text);
        } catch (RuntimeException e) {
            problems.add(where + " is invalid: " + e.getMessage());
        }
    }
}
