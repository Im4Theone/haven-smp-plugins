package net.kaloyan.smpmarket.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kaloyan.smpmarket.model.Price;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Turns a raw configuration tree into validated {@link MarketSettings}. Missing (or null) keys take their value
 * from {@link MarketSettings#defaults()}; present keys must have the right type and range. Every problem is
 * collected so an admin can fix the file in one pass.
 */
final class SettingsParser {

    static final String KEY_MAX_PER_PLAYER = "listing.max-per-player";
    static final String KEY_DURATION = "listing.duration-hours";
    static final String KEY_MIN_PRICE = "listing.min-price";
    static final String KEY_MAX_PRICE = "listing.max-price";
    static final String KEY_MIN_AMOUNT = "listing.min-amount";
    static final String KEY_MAX_AMOUNT = "listing.max-amount";
    static final String KEY_FEE = "fee.percent";
    static final String KEY_SELF = "purchase.allow-self-purchase";
    static final String KEY_BLOCKED = "restrictions.blocked-materials";
    static final String KEY_DEBUG = "logging.debug";

    static final int MAX_FEE_HUNDREDTHS = 5_000;

    private SettingsParser() {
    }

    /**
     * Validates {@code config} and builds settings from it.
     *
     * @param config the loaded configuration
     * @return the validated settings
     * @throws InvalidConfigException if any value is invalid; lists all problems
     */
    static MarketSettings parse(ConfigurationSection config) throws InvalidConfigException {
        MarketSettings defaults = MarketSettings.defaults();
        List<String> problems = new ArrayList<>();

        int maxPerPlayer = readInt(config, KEY_MAX_PER_PLAYER, defaults.maxPerPlayer(), 1, 1000, problems);
        int hours = readInt(config, KEY_DURATION, (int) defaults.duration().toHours(), 1, 8760, problems);
        long minPrice = readPrice(config, KEY_MIN_PRICE, defaults.minPriceMinor(), problems);
        long maxPrice = readPrice(config, KEY_MAX_PRICE, defaults.maxPriceMinor(), problems);
        int minAmount = readInt(config, KEY_MIN_AMOUNT, defaults.minAmount(), 1, 99, problems);
        int maxAmount = readInt(config, KEY_MAX_AMOUNT, defaults.maxAmount(), 1, 99, problems);
        int fee = readFee(config, defaults.feePercentHundredths(), problems);
        boolean self = readBoolean(config, KEY_SELF, defaults.allowSelfPurchase(), problems);
        List<String> blocked = readBlocked(config, problems);
        boolean debug = readBoolean(config, KEY_DEBUG, defaults.debug(), problems);

        if (minPrice > maxPrice) {
            problems.add("'" + KEY_MIN_PRICE + "' (" + Price.toDecimal(minPrice) + ") must not be greater than '"
                    + KEY_MAX_PRICE + "' (" + Price.toDecimal(maxPrice) + ")");
        }
        if (minAmount > maxAmount) {
            problems.add("'" + KEY_MIN_AMOUNT + "' (" + minAmount + ") must not be greater than '" + KEY_MAX_AMOUNT
                    + "' (" + maxAmount + ")");
        }
        if (!problems.isEmpty()) {
            throw new InvalidConfigException(problems);
        }
        return new MarketSettings(maxPerPlayer, Duration.ofHours(hours), minPrice, maxPrice, minAmount, maxAmount,
                fee, self, blocked, debug);
    }

    private static int readInt(ConfigurationSection config, String key, int fallback, int min, int max,
                               List<String> problems) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Integer value)) {
            problems.add("'" + key + "' must be a whole number, but was: " + raw);
            return fallback;
        }
        if (value < min || value > max) {
            problems.add("'" + key + "' must be between " + min + " and " + max + ", but was: " + value);
            return fallback;
        }
        return value;
    }

    private static boolean readBoolean(ConfigurationSection config, String key, boolean fallback, List<String> problems) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Boolean value)) {
            problems.add("'" + key + "' must be true or false, but was: " + raw);
            return fallback;
        }
        return value;
    }

    /** Reads an amount, in minor units. Positive; records a problem and returns the fallback if invalid. */
    private static long readPrice(ConfigurationSection config, String key, long fallback, List<String> problems) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        String text = textOf(raw);
        if (text == null) {
            problems.add("'" + key + "' must be an amount such as \"10.00\", but was: " + raw);
            return fallback;
        }
        try {
            long minor = Price.parseMinor(text.trim());
            if (minor <= 0) {
                problems.add("'" + key + "' must be greater than zero, but was: " + text);
                return fallback;
            }
            return minor;
        } catch (Price.InvalidPriceException e) {
            problems.add("'" + key + "' is invalid (" + text + "): " + e.getMessage());
            return fallback;
        }
    }

    private static int readFee(ConfigurationSection config, int fallback, List<String> problems) {
        Object raw = config.get(KEY_FEE);
        if (raw == null) {
            return fallback;
        }
        String text = textOf(raw);
        if (text == null) {
            problems.add("'" + KEY_FEE + "' must be a percentage such as \"2.5\", but was: " + raw);
            return fallback;
        }
        try {
            long hundredths = Price.parseMinor(text.trim()); // same grammar: plain, at most 2 decimals
            if (hundredths > MAX_FEE_HUNDREDTHS) {
                problems.add("'" + KEY_FEE + "' must be between 0 and 50, but was: " + text);
                return fallback;
            }
            return (int) hundredths;
        } catch (Price.InvalidPriceException e) {
            problems.add("'" + KEY_FEE + "' is invalid (" + text + "): " + e.getMessage());
            return fallback;
        }
    }

    /** Accepts quoted text, integers and short decimals; the latter via their shortest decimal form. */
    private static String textOf(Object raw) {
        if (raw instanceof String text) {
            return text;
        }
        if (raw instanceof Integer || raw instanceof Long) {
            return raw.toString();
        }
        if (raw instanceof Double number && !number.isNaN() && !number.isInfinite() && Math.abs(number) < 1e12) {
            return BigDecimal.valueOf(number).toPlainString();
        }
        return null;
    }

    private static List<String> readBlocked(ConfigurationSection config, List<String> problems) {
        Object raw = config.get(KEY_BLOCKED);
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> entries)) {
            problems.add("'" + KEY_BLOCKED + "' must be a list of material names, but was: " + raw);
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof String text) || text.isBlank()) {
                problems.add("'" + KEY_BLOCKED + "' entries must be non-blank text, but found: " + entry);
                continue;
            }
            String pattern = text.trim().toUpperCase(Locale.ROOT);
            if (!pattern.matches("[A-Z0-9_*]+")) {
                problems.add("'" + KEY_BLOCKED + "' entry '" + text + "' may only contain letters, digits, '_' and '*'");
                continue;
            }
            java.util.regex.Pattern regex = MarketSettings.globToRegex(pattern);
            boolean matchesSomething = false;
            for (Material material : Material.values()) {
                if (!material.isLegacy() && regex.matcher(material.name()).matches()) {
                    matchesSomething = true;
                    break;
                }
            }
            if (!matchesSomething) {
                problems.add("'" + KEY_BLOCKED + "' entry '" + text + "' does not match any material (typo?)");
                continue;
            }
            patterns.add(pattern);
        }
        return patterns;
    }
}
