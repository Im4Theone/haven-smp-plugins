package net.kaloyan.smporders;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * @param maxActive       most open orders one player can have
 * @param minPriceCents   lowest price for one item, in hundredths
 * @param maxPriceCents   highest price for one item, in hundredths
 * @param maxAmount       most items in one order
 * @param taxPercent      tax on the order's total, taken when it is created and not refunded
 * @param maxEnchants     most enchantments on one order (0 = no limit)
 * @param expiryDays      days until an order expires
 * @param blocked         items that cannot be ordered
 * @param titles          MiniMessage menu titles by key
 * @param messages        MiniMessage texts by key
 */
public record Settings(int maxActive, long minPriceCents, long maxPriceCents, int maxAmount, BigDecimal taxPercent, int maxEnchants,
                       int expiryDays, Set<Material> blocked, Map<String, String> titles, Map<String, String> messages) {

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
     * @param total an order's total in hundredths
     * @return the creation tax in hundredths, rounded half up
     */
    public long tax(long total) {
        return BigDecimal.valueOf(total).multiply(taxPercent).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
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
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        int maxActive = config.getInt("orders.max-active-per-player", 10);
        int maxAmount = config.getInt("orders.max-amount", 100_000);
        int maxEnchants = config.getInt("orders.max-enchantments-per-order", 5);
        int expiry = config.getInt("orders.expiry-days", 7);
        if (maxActive < 1 || maxActive > 100) {
            problems.add("'orders.max-active-per-player' must be 1..100");
        }
        if (maxAmount < 1 || maxAmount > 10_000_000) {
            problems.add("'orders.max-amount' must be 1..10000000");
        }
        if (maxEnchants < 0 || maxEnchants > 40) {
            problems.add("'orders.max-enchantments-per-order' must be 0..40 (0 = no limit)");
        }
        if (expiry < 1 || expiry > 365) {
            problems.add("'orders.expiry-days' must be 1..365");
        }
        long min = price(problems, "orders.min-price-per-item", config.get("orders.min-price-per-item", 0.01));
        long max = price(problems, "orders.max-price-per-item", config.get("orders.max-price-per-item", 1_000_000));
        if (min > 0 && max > 0 && min > max) {
            problems.add("'orders.min-price-per-item' is above 'orders.max-price-per-item'");
        }
        BigDecimal tax = BigDecimal.ZERO;
        try {
            tax = new BigDecimal(String.valueOf(config.get("orders.creation-tax-percent", 5)));
            if (tax.signum() < 0 || tax.compareTo(BigDecimal.valueOf(100)) > 0) {
                problems.add("'orders.creation-tax-percent' must be 0..100");
                tax = BigDecimal.ZERO;
            }
        } catch (NumberFormatException e) {
            problems.add("'orders.creation-tax-percent' is not a number");
        }
        Set<Material> blocked = new TreeSet<>();
        for (String name : config.getStringList("orders.blocked-items")) {
            Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                problems.add("'orders.blocked-items': '" + name + "' is not an item");
            } else {
                blocked.add(material);
            }
        }
        Map<String, String> titles = texts(problems, mini, config.getConfigurationSection("titles"), "title ");
        Map<String, String> messages = texts(problems, mini, config.getConfigurationSection("messages"), "message ");
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(maxActive, min, max, maxAmount, tax, maxEnchants, expiry, blocked, titles, messages);
    }

    private static long price(List<String> problems, String where, Object value) {
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
