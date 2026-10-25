package net.kaloyan.smpworth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The price sheet: a base value per item and a value per enchantment level. The worth of an item is
 * {@code base_value + sum(bonus_per_level * level)} over its enchantments, rounded to two decimals, exactly as the sheet's
 * {@code pricing_model} says. Pure logic, no server needed.
 */
public final class PriceSheet {

    /** Minecraft's internal enchantment names that the sheet spells differently. */
    private static final Map<String, String> ALIASES = Map.of("binding_curse", "curse_of_binding",
            "vanishing_curse", "curse_of_vanishing");

    /**
     * A rule that prices items the sheet does not list. The first rule whose pattern matches the whole item id wins.
     *
     * @param pattern the item ids it applies to
     * @param value   a fixed base value, or {@code null} if {@code sameAs} is used
     * @param sameAs  the id of another item whose price is used, written with {@code $1} style references to the pattern's groups
     *                (for example {@code waxed_(.*)} with {@code $1}); {@code null} if {@code value} is used
     */
    public record Rule(Pattern pattern, Double value, String sameAs) {
    }

    private static final int MAX_DEPTH = 4;

    private final Map<String, Double> items;
    private final Map<String, Double> bonuses;
    private final Map<String, Integer> maxLevels;
    private final List<Rule> rules;

    private PriceSheet(Map<String, Double> items, Map<String, Double> bonuses, Map<String, Integer> maxLevels, List<Rule> rules) {
        this.items = Map.copyOf(items);
        this.bonuses = Map.copyOf(bonuses);
        this.maxLevels = Map.copyOf(maxLevels);
        this.rules = List.copyOf(rules);
    }

    /**
     * @param fallbacks rules for items the sheet does not list, tried in order
     * @return the same sheet with those rules
     */
    public PriceSheet withRules(List<Rule> fallbacks) {
        return new PriceSheet(items, bonuses, maxLevels, fallbacks);
    }

    private OptionalDouble base(String id, int depth) {
        Double listed = items.get(id);
        if (listed != null) {
            return OptionalDouble.of(listed);
        }
        if (depth >= MAX_DEPTH) {
            return OptionalDouble.empty();
        }
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(id);
            if (!m.matches()) {
                continue;
            }
            if (rule.value() != null) {
                return OptionalDouble.of(rule.value());
            }
            OptionalDouble other = base(m.replaceAll(rule.sameAs()).toLowerCase(Locale.ROOT), depth + 1);
            if (other.isPresent()) {
                return other;
            }
        }
        return OptionalDouble.empty();
    }

    /**
     * Reads a sheet in the format of {@code donutsmp_custom_prices.json}.
     *
     * @param reader the JSON text
     * @return the sheet
     * @throws IOException if the text is not valid JSON, lacks {@code items}, or holds a value that is not a finite number; nothing is
     *                     guessed, so a broken sheet never silently prices items at zero
     */
    public static PriceSheet parse(Reader reader) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("the price sheet is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.has("items") || !root.get("items").isJsonObject()) {
            throw new IOException("the price sheet has no \"items\" section");
        }
        Map<String, Double> items = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
            items.put(e.getKey().toLowerCase(Locale.ROOT), number(e.getValue(), "items." + e.getKey(), "base_value"));
        }
        Map<String, Double> bonuses = new HashMap<>();
        Map<String, Integer> maxLevels = new HashMap<>();
        JsonObject rules = root.has("enchantment_rules") && root.get("enchantment_rules").isJsonObject()
                ? root.getAsJsonObject("enchantment_rules") : new JsonObject();
        if (rules.has("enchantments") && rules.get("enchantments").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : rules.getAsJsonObject("enchantments").entrySet()) {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                bonuses.put(key, number(e.getValue(), "enchantment_rules.enchantments." + e.getKey(), "bonus_per_level"));
                if (e.getValue().getAsJsonObject().has("max_level")) {
                    maxLevels.put(key, e.getValue().getAsJsonObject().get("max_level").getAsInt());
                }
            }
        }
        if (items.isEmpty()) {
            throw new IOException("the price sheet lists no items");
        }
        return new PriceSheet(items, bonuses, maxLevels, List.of());
    }

    private static double number(JsonElement entry, String where, String field) throws IOException {
        try {
            double value = entry.getAsJsonObject().get(field).getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IOException(where + "." + field + " is not a finite number");
            }
            return value;
        } catch (RuntimeException e) {
            throw new IOException(where + " has no usable \"" + field + "\"", e);
        }
    }

    /** @return how many items the sheet prices */
    public int size() {
        return items.size();
    }

    /** @return every item id in the sheet */
    public Set<String> itemIds() {
        return items.keySet();
    }

    /** @return {@code true} if the sheet or a fallback rule prices this item (even at zero) */
    public boolean knows(String itemId) {
        return base(itemId.toLowerCase(Locale.ROOT), 0).isPresent();
    }

    /**
     * The worth of one item.
     *
     * @param itemId       the item's id without namespace, e.g. {@code diamond_pickaxe}
     * @param enchantments the item's enchantments (or the stored ones of an enchanted book) as id to level
     * @return the worth, or empty if the sheet does not list the item; enchantments the sheet does not know add nothing
     */
    public OptionalDouble worth(String itemId, Map<String, Integer> enchantments) {
        OptionalDouble base = base(itemId.toLowerCase(Locale.ROOT), 0);
        if (base.isEmpty()) {
            return OptionalDouble.empty();
        }
        double total = base.getAsDouble();
        for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            String key = ALIASES.getOrDefault(e.getKey().toLowerCase(Locale.ROOT), e.getKey().toLowerCase(Locale.ROOT));
            Double bonus = bonuses.get(key);
            if (bonus != null && e.getValue() > 0) {
                total += bonus * e.getValue();
            }
        }
        return OptionalDouble.of(new BigDecimal(total).setScale(2, RoundingMode.HALF_UP).doubleValue());
    }

    /** @return the highest level the sheet allows for an enchantment, or 0 if it does not know it */
    public int maxLevel(String enchantment) {
        return maxLevels.getOrDefault(ALIASES.getOrDefault(enchantment, enchantment), 0);
    }
}
