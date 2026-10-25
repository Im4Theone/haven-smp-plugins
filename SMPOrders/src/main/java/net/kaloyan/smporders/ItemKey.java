package net.kaloyan.smporders;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * What an order asks for: a material and an exact set of enchantments. Delivered items must match it exactly, so the buyer gets
 * back an item that is made new from the key (nothing else about the delivered stack is kept).
 *
 * @param material the item
 * @param enchants minecraft enchantment names (e.g. {@code sharpness}) with their levels, sorted by name
 */
public record ItemKey(Material material, Map<String, Integer> enchants) {

    /** Normalises the enchantment map so equal keys are equal. */
    public ItemKey {
        enchants = Collections.unmodifiableMap(new TreeMap<>(enchants));
    }

    /**
     * @param material the item
     * @return a key without enchantments
     */
    public static ItemKey plain(Material material) {
        return new ItemKey(material, Map.of());
    }

    /** @return the text stored in {@code orders.yml}, for example {@code DIAMOND_SWORD|sharpness:5,unbreaking:3} */
    public String encode() {
        if (enchants.isEmpty()) {
            return material.name();
        }
        StringBuilder out = new StringBuilder(material.name()).append('|');
        enchants.forEach((name, level) -> out.append(out.charAt(out.length() - 1) == '|' ? "" : ",").append(name).append(':').append(level));
        return out.toString();
    }

    /**
     * @param text a string made by {@link #encode()}
     * @return the key, or {@code null} if the text is not valid
     */
    public static ItemKey decode(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split("\\|", 2);
        Material material = Material.getMaterial(parts[0].toUpperCase(Locale.ROOT));
        if (material == null) {
            return null;
        }
        Map<String, Integer> enchants = new TreeMap<>();
        if (parts.length == 2 && !parts[1].isEmpty()) {
            for (String entry : parts[1].split(",")) {
                String[] pair = entry.split(":");
                if (pair.length != 2 || !pair[0].matches("[a-z_]{2,40}")) {
                    return null;
                }
                try {
                    int level = Integer.parseInt(pair[1]);
                    if (level < 1 || level > 255) {
                        return null;
                    }
                    enchants.put(pair[0], level);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return new ItemKey(material, enchants);
    }

    private static Map<String, Integer> enchantsOf(ItemStack item) {
        Map<String, Integer> out = new TreeMap<>();
        Map<Enchantment, Integer> source = item.getItemMeta() instanceof EnchantmentStorageMeta book
                ? book.getStoredEnchants() : item.getEnchantments();
        source.forEach((enchantment, level) -> out.put(enchantment.getKey().getKey(), level));
        return out;
    }

    /**
     * @param item a stack
     * @return its key
     */
    public static ItemKey of(ItemStack item) {
        return new ItemKey(item.getType(), enchantsOf(item));
    }

    /**
     * Whether a stack may be delivered at all: it must be plain (not renamed, not damaged, no custom enchantments, no contents in
     * a shulker box or bundle). Lore is ignored, so the price lore other plugins add does not matter.
     *
     * @param item a stack
     * @return {@code true} if it can be delivered
     */
    public static boolean deliverable(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName() || meta instanceof Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            return false;
        }
        if (meta instanceof BlockStateMeta box && box.hasBlockState() && box.getBlockState() instanceof Container container
                && !container.getInventory().isEmpty()) {
            return false;
        }
        Map<Enchantment, Integer> all = meta instanceof EnchantmentStorageMeta book ? book.getStoredEnchants() : item.getEnchantments();
        for (Enchantment enchantment : all.keySet()) {
            if (!NamespacedKey.MINECRAFT.equals(enchantment.getKey().getNamespace())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param item a stack
     * @return whether it is this item with exactly these enchantments and may be delivered
     */
    public boolean accepts(ItemStack item) {
        return item != null && item.getType() == material && deliverable(item) && enchantsOf(item).equals(enchants);
    }

    /**
     * Makes new items.
     *
     * @param amount how many (at most one stack)
     * @return the stack
     */
    public ItemStack create(int amount) {
        ItemStack item = new ItemStack(material, amount);
        if (enchants.isEmpty()) {
            return item;
        }
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        ItemMeta meta = item.getItemMeta();
        enchants.forEach((name, level) -> {
            Enchantment enchantment = registry.get(NamespacedKey.minecraft(name));
            if (enchantment == null) {
                return;
            }
            if (meta instanceof EnchantmentStorageMeta book) {
                book.addStoredEnchant(enchantment, level, true);
            } else {
                meta.addEnchant(enchantment, level, true);
            }
        });
        item.setItemMeta(meta);
        return item;
    }

    /** @return a readable name, for example {@code Diamond Sword (Sharpness 5, Unbreaking 3)} */
    public String label() {
        StringBuilder out = new StringBuilder(pretty(material.name()));
        if (!enchants.isEmpty()) {
            out.append(" (");
            boolean first = true;
            for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                out.append(first ? "" : ", ").append(pretty(e.getKey())).append(' ').append(e.getValue());
                first = false;
            }
            out.append(')');
        }
        return out.toString();
    }

    /**
     * @param id a name in capitals or lower case with underscores
     * @return the words capitalised
     */
    public static String pretty(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }
}
