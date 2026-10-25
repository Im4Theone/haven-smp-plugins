package net.kaloyan.smpworth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Works out what an item is worth and keeps the "Worth" line in its lore up to date. */
public final class WorthApplier {

    private final NamespacedKey marker;
    private final Supplier<PriceSheet> sheet;
    private final Supplier<WorthSettings> settings;

    /**
     * @param plugin   the owning plugin (its name is part of the marker)
     * @param sheet    the current price sheet
     * @param settings the current settings
     */
    public WorthApplier(Plugin plugin, Supplier<PriceSheet> sheet, Supplier<WorthSettings> settings) {
        this.marker = new NamespacedKey(plugin, "worth_line");
        this.sheet = sheet;
        this.settings = settings;
    }

    /** @return the enchantments of an item (or the ones stored in an enchanted book) by id */
    private static Map<String, Integer> enchantments(ItemMeta meta) {
        Map<Enchantment, Integer> source = meta instanceof EnchantmentStorageMeta book ? book.getStoredEnchants() : meta.getEnchants();
        Map<String, Integer> out = new HashMap<>();
        source.forEach((e, level) -> out.put(e.getKey().getKey(), level));
        return out;
    }

    /**
     * The worth of one item of this stack.
     *
     * @param item the stack (its amount is ignored)
     * @return the worth, or empty if the price sheet does not list this kind of item
     */
    public OptionalDouble worthOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return OptionalDouble.empty();
        }
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        return sheet.get().worth(item.getType().getKey().getKey(), meta == null ? Map.of() : enchantments(meta));
    }

    /**
     * Makes sure the item shows its current worth. The stack is changed in place only if the line is missing or wrong.
     *
     * @param item any stack, may be {@code null} or air
     * @return {@code true} if the stack was changed
     */
    public boolean apply(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        WorthSettings s = settings.get();
        OptionalDouble worth = worthOf(item);
        double value = worth.orElse(0);
        Component line = value <= 0 && s.hideZero() ? null
                : MiniMessage.miniMessage().deserialize(s.format(), Placeholder.unparsed("price", LoreLine.price(value)))
                        .decoration(TextDecoration.ITALIC, false);
        ItemMeta meta = item.getItemMeta();
        String previous = meta.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        List<Component> lore = meta.lore();
        if (LoreLine.upToDate(lore, previous, line)) {
            return false;
        }
        meta.lore(LoreLine.update(lore, previous, line));
        if (line == null) {
            meta.getPersistentDataContainer().remove(marker);
        } else {
            meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, LoreLine.plain(line));
        }
        item.setItemMeta(meta);
        return true;
    }
}
