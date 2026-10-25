package net.kaloyan.smpmarket.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Immutable snapshot of the validated configuration. Prices are in minor units (hundredths).
 *
 * @param maxPerPlayer       maximum listings per player (active or being sold)
 * @param duration           how long a listing stays on sale
 * @param minPriceMinor      minimum total price
 * @param maxPriceMinor      maximum total price
 * @param minAmount          minimum stack size of a listing
 * @param maxAmount          maximum stack size of a listing
 * @param feePercentHundredths market fee in hundredths of a percent
 * @param allowSelfPurchase  whether players may buy their own listings
 * @param blockedMaterials   upper-case glob patterns of material names that cannot be listed
 * @param debug              extra console output
 */
public record MarketSettings(int maxPerPlayer, Duration duration, long minPriceMinor, long maxPriceMinor,
                             int minAmount, int maxAmount, int feePercentHundredths, boolean allowSelfPurchase,
                             List<String> blockedMaterials, boolean debug) {

    public MarketSettings {
        blockedMaterials = List.copyOf(blockedMaterials);
    }

    /**
     * Returns the settings used for every key missing from {@code config.yml}; identical to the bundled file.
     *
     * @return the defaults
     */
    public static MarketSettings defaults() {
        return new MarketSettings(10, Duration.ofHours(72), 1L, 100_000_000L, 1, 64, 0, false, List.of(), false);
    }

    /**
     * Tells whether an item of the given material may not be listed.
     *
     * @param materialName Bukkit material name or namespaced key; case-insensitive, {@code minecraft:} is ignored
     * @return {@code true} if a restriction pattern matches
     */
    public boolean isBlocked(String materialName) {
        String name = materialName.toUpperCase(Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) {
            name = name.substring("MINECRAFT:".length());
        }
        for (String pattern : blockedMaterials) {
            if (globToRegex(pattern).matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    static Pattern globToRegex(String glob) {
        List<String> quoted = new ArrayList<>();
        for (String part : glob.split("\\*", -1)) {
            quoted.add(Pattern.quote(part));
        }
        return Pattern.compile(String.join(".*", quoted));
    }
}
