package net.kaloyan.smpworth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param format    MiniMessage lore line with {@code <price>}
 * @param hideZero  whether items worth nothing (or missing from the sheet) get no line at all
 * @param scanTicks how often player inventories are checked
 * @param skipCreative whether players in creative mode are left alone
 * @param fallbacks rules that price items the sheet does not list
 * @param messages  MiniMessage texts by key
 * @param sell      the /sell settings
 */
public record WorthSettings(String format, boolean hideZero, int scanTicks, boolean skipCreative,
                           List<PriceSheet.Rule> fallbacks, Map<String, String> messages, Sell sell) {

    /**
     * Settings of {@code /sell}.
     *
     * @param enabled             whether /sell works
     * @param percent             what players get, as a percent of an item's worth (85 = 15 percent less than the shop's buy price)
     * @param protectedNamespaces items that carry a mark of one of these plugins can never be sold
     */
    public record Sell(boolean enabled, java.math.BigDecimal percent, java.util.Set<String> protectedNamespaces) {
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

    /**
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static WorthSettings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        String format = config.getString("lore.format", "<#9CA3AF>Worth: <#FBBF24><price>");
        if (!format.contains("<price>")) {
            problems.add("'lore.format' must contain <price>");
        }
        try {
            mini.deserialize(format);
        } catch (RuntimeException e) {
            problems.add("'lore.format' is not valid MiniMessage: " + e.getMessage());
        }
        int ticks = config.getInt("scan-ticks", 10);
        if (ticks < 2 || ticks > 200) {
            problems.add("'scan-ticks' must be 2..200");
        }
        List<PriceSheet.Rule> rules = new ArrayList<>();
        List<Map<?, ?>> list = config.getMapList("fallbacks");
        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> r = list.get(i);
            String where = "fallbacks[" + (i + 1) + "]";
            Object match = r.get("match");
            if (match == null) {
                problems.add("'" + where + "' needs a 'match' pattern");
                continue;
            }
            java.util.regex.Pattern pattern;
            try {
                pattern = java.util.regex.Pattern.compile(String.valueOf(match));
            } catch (java.util.regex.PatternSyntaxException e) {
                problems.add("'" + where + ".match' is not a valid pattern: " + e.getDescription());
                continue;
            }
            Object value = r.get("value");
            Object same = r.get("same-as");
            if ((value == null) == (same == null)) {
                problems.add("'" + where + "' needs exactly one of 'value' or 'same-as'");
            } else if (value != null) {
                if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 0) {
                    rules.add(new PriceSheet.Rule(pattern, n.doubleValue(), null));
                } else {
                    problems.add("'" + where + ".value' must be a number of 0 or more");
                }
            } else {
                rules.add(new PriceSheet.Rule(pattern, null, String.valueOf(same)));
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
        java.math.BigDecimal percent = java.math.BigDecimal.valueOf(85);
        try {
            percent = new java.math.BigDecimal(String.valueOf(config.get("sell.percent", 85)));
            if (percent.signum() <= 0 || percent.compareTo(java.math.BigDecimal.valueOf(200)) > 0) {
                problems.add("'sell.percent' must be above 0 and at most 200");
            }
        } catch (NumberFormatException e) {
            problems.add("'sell.percent' is not a number");
        }
        java.util.Set<String> protectedNamespaces = new java.util.TreeSet<>();
        for (String ns : config.getStringList("sell.protected-namespaces")) {
            protectedNamespaces.add(ns.toLowerCase(java.util.Locale.ROOT));
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new WorthSettings(format, config.getBoolean("lore.hide-zero", false), ticks, config.getBoolean("skip-creative", true), rules, messages,
                new Sell(config.getBoolean("sell.enabled", true), percent, protectedNamespaces));
    }
}
