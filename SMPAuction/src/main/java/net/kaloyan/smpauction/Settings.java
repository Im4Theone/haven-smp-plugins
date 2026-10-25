package net.kaloyan.smpauction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param durations        listing lengths offered in the sell menu, in hours
 * @param defaultHours     the length used by {@code /ah sell}
 * @param minCents         lowest price of a listing, in hundredths
 * @param maxCents         highest price of a listing, in hundredths
 * @param maxListings      how many live listings a player can have without a permission tier
 * @param tiers            permission to listing limit (0 = unlimited)
 * @param bidding          whether auctions are allowed
 * @param incrementPercent a new bid must beat the old one by this percent...
 * @param incrementCents   ...or by this amount, whichever is higher
 * @param antiSnipeMillis  a bid in the last moments extends the auction to this long
 * @param taxPercent       percent of the price taken from the seller when an item sells
 * @param feeFlatCents     fixed listing fee in hundredths
 * @param feePercent       listing fee as a percent of the price
 * @param feeMaxCents      highest fee (0 = no cap)
 * @param blockedModes     game modes that cannot list
 * @param blockedWorlds    worlds where listing is not allowed
 * @param blacklist        items that can never be listed
 * @param titles           MiniMessage menu titles by key
 * @param messages         MiniMessage texts by key
 */
public record Settings(List<Integer> durations, int defaultHours, long minCents, long maxCents, int maxListings, Map<String, Integer> tiers,
                       boolean bidding, BigDecimal incrementPercent, long incrementCents, long antiSnipeMillis, BigDecimal taxPercent,
                       long feeFlatCents, BigDecimal feePercent, long feeMaxCents, Set<String> blockedModes, Set<String> blockedWorlds,
                       Set<Material> blacklist, Map<String, String> titles, Map<String, String> messages) {

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
     * @param price a sale price in hundredths
     * @return the sales tax in hundredths, rounded half up
     */
    public long tax(long price) {
        return percent(price, taxPercent);
    }

    /**
     * @param price a listing price in hundredths
     * @return the listing fee in hundredths
     */
    public long fee(long price) {
        long fee = feeFlatCents + percent(price, feePercent);
        return feeMaxCents > 0 ? Math.min(fee, feeMaxCents) : fee;
    }

    private static long percent(long cents, BigDecimal percent) {
        return BigDecimal.valueOf(cents).multiply(percent).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * @param hasPermission tells whether the player has a permission
     * @return how many live listings the player may have, {@code Integer.MAX_VALUE} for unlimited
     */
    public int limit(Predicate<String> hasPermission) {
        int best = maxListings;
        for (Map.Entry<String, Integer> tier : tiers.entrySet()) {
            if (hasPermission.test(tier.getKey())) {
                if (tier.getValue() == 0) {
                    return Integer.MAX_VALUE;
                }
                best = Math.max(best, tier.getValue());
            }
        }
        return best;
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
        List<Integer> durations = new ArrayList<>();
        for (int hours : config.getIntegerList("auction.durations")) {
            if (hours < 1 || hours > 24 * 60) {
                problems.add("'auction.durations': " + hours + " must be 1..1440 hours");
            } else {
                durations.add(hours);
            }
        }
        if (durations.isEmpty() || durations.size() > 7) {
            problems.add("'auction.durations' needs 1..7 entries");
        }
        int defaultHours = config.getInt("auction.default-duration-hours", 48);
        if (!durations.contains(defaultHours)) {
            problems.add("'auction.default-duration-hours' must be one of 'auction.durations'");
        }
        long min = money(problems, "auction.min-price", config.get("auction.min-price", 10));
        long max = money(problems, "auction.max-price", config.get("auction.max-price", 1_000_000_000));
        if (min > 0 && max > 0 && min > max) {
            problems.add("'auction.min-price' is above 'auction.max-price'");
        }
        int maxListings = config.getInt("auction.max-listings", 5);
        if (maxListings < 1 || maxListings > 1000) {
            problems.add("'auction.max-listings' must be 1..1000");
        }
        Map<String, Integer> tiers = new LinkedHashMap<>();
        ConfigurationSection ts = config.getConfigurationSection("auction.limit-permissions");
        if (ts != null) {
            for (String permission : ts.getKeys(true)) {      // the dots in permission names make nested sections; the leaves are the limits
                if (ts.isConfigurationSection(permission)) {
                    continue;
                }
                int value = ts.getInt(permission);
                if (value < 0 || value > 100000) {
                    problems.add("'auction.limit-permissions." + permission + "' must be 0 (unlimited) or 1..100000");
                }
                tiers.put(permission, value);
            }
        }
        boolean bidding = config.getBoolean("bidding.enabled", true);
        BigDecimal incPercent = decimal(problems, "bidding.min-increment-percent", config.get("bidding.min-increment-percent", 5), 1000);
        long incCents = money(problems, "bidding.min-increment", config.get("bidding.min-increment", 1));
        int snipe = config.getInt("bidding.anti-snipe-seconds", 30);
        if (snipe < 0 || snipe > 3600) {
            problems.add("'bidding.anti-snipe-seconds' must be 0..3600");
        }
        BigDecimal tax = decimal(problems, "economy.sales-tax-percent", config.get("economy.sales-tax-percent", 5), 100);
        BigDecimal feePercent = decimal(problems, "economy.listing-fee.percent", config.get("economy.listing-fee.percent", 0), 100);
        long feeFlat = config.getDouble("economy.listing-fee.flat", 0) == 0 ? 0 : money(problems, "economy.listing-fee.flat", config.get("economy.listing-fee.flat"));
        long feeMax = config.getDouble("economy.listing-fee.max", 0) == 0 ? 0 : money(problems, "economy.listing-fee.max", config.get("economy.listing-fee.max"));
        Set<String> modes = new TreeSet<>();
        for (String mode : config.getStringList("restrictions.blocked-gamemodes")) {
            modes.add(mode.toUpperCase(Locale.ROOT));
        }
        Set<String> worlds = new TreeSet<>(config.getStringList("restrictions.disabled-worlds"));
        Set<Material> blacklist = new TreeSet<>();
        for (String name : config.getStringList("blacklist.materials")) {
            Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) {
                problems.add("'blacklist.materials': '" + name + "' is not an item");
            } else {
                blacklist.add(material);
            }
        }
        Map<String, String> titles = texts(problems, mini, config.getConfigurationSection("titles"), "title ");
        Map<String, String> messages = texts(problems, mini, config.getConfigurationSection("messages"), "message ");
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(durations, defaultHours, min, max, maxListings, tiers, bidding, incPercent, incCents, snipe * 1000L, tax,
                feeFlat, feePercent, feeMax, modes, worlds, blacklist, titles, messages);
    }

    private static BigDecimal decimal(List<String> problems, String where, Object value, int max) {
        try {
            BigDecimal d = new BigDecimal(String.valueOf(value));
            if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(max)) > 0) {
                problems.add("'" + where + "' must be 0.." + max);
                return BigDecimal.ZERO;
            }
            return d;
        } catch (NumberFormatException e) {
            problems.add("'" + where + "' is not a number");
            return BigDecimal.ZERO;
        }
    }

    private static long money(List<String> problems, String where, Object value) {
        try {
            BigDecimal price = new BigDecimal(String.valueOf(value));
            if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2 || price.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0) {
                problems.add("'" + where + "' must be above 0, at most 1000000000000, with at most 2 decimals");
                return -1;
            }
            return price.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException e) {
            problems.add("'" + where + "' is not a valid amount");
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
