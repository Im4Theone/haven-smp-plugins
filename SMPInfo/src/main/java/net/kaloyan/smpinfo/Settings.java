package net.kaloyan.smpinfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param serverName      the name shown in texts
 * @param motd            MiniMessage lines of the server list text (empty = leave it alone)
 * @param join            join message ({@code ""} = none)
 * @param quit            quit message ({@code ""} = none)
 * @param firstJoin       broadcast for a player's very first join ({@code ""} = none)
 * @param announcerOn     whether the announcer runs
 * @param announcerTicks  ticks between two announcements
 * @param announcerPrefix MiniMessage prefix of every announcement
 * @param announcements   the announcements, in order
 * @param commands        the info commands by name
 * @param ranks           the /ranks menu
 */
public record Settings(String serverName, List<String> motd, String join, String quit, String firstJoin, boolean announcerOn, long announcerTicks,
                       String announcerPrefix, List<String> announcements, Map<String, Info> commands, RankShop ranks) {

    /**
     * One info command.
     *
     * @param description what it is
     * @param lines       MiniMessage lines
     */
    public record Info(String description, List<String> lines) {
    }

    /**
     * One rank in the /ranks menu.
     *
     * @param id    the LuckPerms group
     * @param name  MiniMessage name
     * @param item  the icon
     * @param price what it costs, as text
     * @param perks MiniMessage lines
     */
    public record Rank(String id, String name, Material item, String price, List<String> perks) {
    }

    /**
     * The /ranks menu.
     *
     * @param title       MiniMessage title
     * @param url         the store address
     * @param ranks       ranks, cheapest first (at most 7)
     * @param owned       lore line for a rank the player has
     * @param priceLine   lore line with the price
     * @param clickLine   lore line that says what a click does
     * @param storeName   name of the store button
     * @param storeLore   lore of the store button
     * @param linkMessage chat message sent when something is clicked
     */
    public record RankShop(String title, String url, List<Rank> ranks, String owned, String priceLine, String clickLine, String storeName,
                           String storeLore, String linkMessage) {
    }

    /** Names that would clash with commands every server already has. */
    private static final Set<String> RESERVED = Set.of("help", "me", "msg", "tell", "w", "list", "kick", "ban", "op", "stop", "reload");

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
     * @param config the configuration
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        List<String> motd = config.getStringList("motd");
        motd.forEach(line -> markup(problems, mini, "motd", line));
        if (motd.size() > 2) {
            problems.add("'motd' can have at most 2 lines");
        }
        String join = config.getString("messages.join", "");
        String quit = config.getString("messages.quit", "");
        String first = config.getString("messages.first-join", "");
        markup(problems, mini, "messages.join", join);
        markup(problems, mini, "messages.quit", quit);
        markup(problems, mini, "messages.first-join", first);
        int seconds = config.getInt("announcer.interval-seconds", 300);
        if (seconds < 10 || seconds > 86400) {
            problems.add("'announcer.interval-seconds' must be 10..86400");
        }
        String prefix = config.getString("announcer.prefix", "");
        markup(problems, mini, "announcer.prefix", prefix);
        List<String> announcements = config.getStringList("announcer.messages");
        announcements.forEach(line -> markup(problems, mini, "announcer.messages", line));
        Map<String, Info> commands = new LinkedHashMap<>();
        ConfigurationSection cs = config.getConfigurationSection("commands");
        if (cs != null) {
            for (String name : cs.getKeys(false)) {
                String where = "commands." + name;
                if (!name.matches("[a-z0-9_]{2,20}") || RESERVED.contains(name)) {
                    problems.add("'" + where + "' needs a lower case name of 2..20 letters or digits that no other command uses");
                    continue;
                }
                List<String> lines = cs.getStringList(name + ".lines");
                if (lines.isEmpty()) {
                    problems.add("'" + where + ".lines' needs at least one line");
                }
                lines.forEach(line -> markup(problems, mini, where + ".lines", line));
                commands.put(name.toLowerCase(Locale.ROOT), new Info(cs.getString(name + ".description", name), lines));
            }
        }
        RankShop shop = parseRanks(config, problems, mini);
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(config.getString("server-name", "the server"), motd, join, quit, first, config.getBoolean("announcer.enabled", true),
                seconds * 20L, prefix, announcements, commands, shop);
    }

    private static RankShop parseRanks(ConfigurationSection config, List<String> problems, MiniMessage mini) {
        String url = config.getString("ranks.store-url", "");
        if (!url.matches("https?://\\S+")) {
            problems.add("'ranks.store-url' must be an http(s) address");
        }
        String[] keys = {"title", "owned", "price-line", "click-line", "store-name", "store-lore", "link-message"};
        String[] texts = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            texts[i] = config.getString("ranks." + keys[i], "");
            markup(problems, mini, "ranks." + keys[i], texts[i]);
        }
        List<Rank> ranks = new ArrayList<>();
        ConfigurationSection list = config.getConfigurationSection("ranks.list");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                String where = "ranks.list." + id;
                if (!id.matches("[a-z0-9_]{1,32}")) {
                    problems.add("'" + where + "' must be a LuckPerms group name (lower case letters, digits, _)");
                    continue;
                }
                Material item = Material.matchMaterial(list.getString(id + ".item", ""));
                if (item == null || item.isLegacy()) {
                    problems.add("'" + where + ".item' is not an item");
                    item = Material.PAPER;
                }
                String name = list.getString(id + ".name", id);
                markup(problems, mini, where + ".name", name);
                List<String> perks = list.getStringList(id + ".perks");
                perks.forEach(line -> markup(problems, mini, where + ".perks", line));
                ranks.add(new Rank(id, name, item, list.getString(id + ".price", "?"), perks));
            }
        }
        if (ranks.size() > 7) {
            problems.add("'ranks.list' can have at most 7 ranks");
        }
        return new RankShop(texts[0], url, ranks, texts[1], texts[2], texts[3], texts[4], texts[5], texts[6]);
    }

    private static void markup(List<String> problems, MiniMessage mini, String where, String text) {
        try {
            mini.deserialize(text);
        } catch (RuntimeException e) {
            problems.add(where + " is invalid: " + e.getMessage());
        }
    }
}
