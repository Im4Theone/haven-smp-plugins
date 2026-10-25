package net.kaloyan.smpdaily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param days     what each day of the cycle gives
 * @param titles   MiniMessage menu titles
 * @param messages MiniMessage texts
 */
public record Settings(List<Reward> days, Map<String, String> titles, Map<String, String> messages) {

    /**
     * One day's reward.
     *
     * @param cents  money in hundredths
     * @param shards shards
     * @param crates crate id to amount
     */
    public record Reward(long cents, long shards, Map<String, Integer> crates) {
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

    /**
     * @param config the configuration
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        List<Reward> days = new ArrayList<>();
        List<Map<?, ?>> raw = config.getMapList("days");
        if (raw.isEmpty() || raw.size() > 7) {
            problems.add("'days' needs 1 to 7 entries");
        }
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> m = raw.get(i);
            String where = "days[" + (i + 1) + "]";
            long cents = 0;
            if (m.get("money") != null) {
                try {
                    BigDecimal money = new BigDecimal(String.valueOf(m.get("money")));
                    if (money.signum() < 0 || money.stripTrailingZeros().scale() > 2 || money.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
                        problems.add("'" + where + ".money' must be 0 or more with at most 2 decimals");
                    } else {
                        cents = money.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
                    }
                } catch (NumberFormatException e) {
                    problems.add("'" + where + ".money' is not a number");
                }
            }
            long shards = m.get("shards") instanceof Number n && n.doubleValue() == n.longValue() && n.longValue() >= 0 && n.longValue() <= 1_000_000 ? n.longValue() : m.get("shards") == null ? 0 : -1;
            if (shards < 0) {
                problems.add("'" + where + ".shards' must be a whole number from 0 to 1000000");
            }
            Map<String, Integer> crates = new LinkedHashMap<>();
            if (m.get("crates") instanceof Map<?, ?> c) {
                for (Map.Entry<?, ?> e : c.entrySet()) {
                    String id = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                    if (!id.matches("[a-z_]{2,40}") || !(e.getValue() instanceof Number amount) || amount.intValue() < 1 || amount.intValue() > 64) {
                        problems.add("'" + where + ".crates': '" + id + "' needs an amount of 1..64");
                    } else {
                        crates.put(id, amount.intValue());
                    }
                }
            }
            if (cents == 0 && shards == 0 && crates.isEmpty()) {
                problems.add("'" + where + "' gives nothing");
            }
            days.add(new Reward(cents, Math.max(0, shards), crates));
        }
        Map<String, String> titles = texts(problems, mini, config.getConfigurationSection("titles"), "title ");
        Map<String, String> messages = texts(problems, mini, config.getConfigurationSection("messages"), "message ");
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(days, titles, messages);
    }

    private static Map<String, String> texts(List<String> problems, MiniMessage mini, ConfigurationSection section, String label) {
        Map<String, String> out = new LinkedHashMap<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String text = section.getString(key, "");
                try {
                    mini.deserialize(text);
                } catch (RuntimeException e) {
                    problems.add(label + "'" + key + "' is invalid: " + e.getMessage());
                }
                out.put(key, text);
            }
        }
        return out;
    }
}
