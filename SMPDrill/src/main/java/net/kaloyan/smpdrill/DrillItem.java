package net.kaloyan.smpdrill;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Creates and recognises the Amethyst Drill: a netherite pickaxe carrying a hidden tag. */
public final class DrillItem {

    private final NamespacedKey tag;

    /** @param plugin the owning plugin (its name is part of the tag) */
    public DrillItem(Plugin plugin) {
        this.tag = new NamespacedKey(plugin, "amethyst_drill");
    }

    /** @return the enchantment with this name on this server, or {@code null} */
    static Enchantment enchantment(String name) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(name));
    }

    /**
     * @param settings the settings
     * @return a message per enchantment name this server does not know; empty if all are known
     */
    public static List<String> unknownEnchantments(DrillSettings settings) {
        List<String> out = new ArrayList<>();
        for (String name : settings.enchantments().keySet()) {
            if (enchantment(name) == null) {
                out.add("'item.enchantments." + name + "': this server has no such enchantment");
            }
        }
        return out;
    }

    /**
     * @param settings name, lore and enchantments
     * @return a new drill
     */
    public ItemStack create(DrillSettings settings) {
        MiniMessage mini = MiniMessage.miniMessage();
        ItemStack item = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(mini.deserialize(settings.name()).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : settings.lore()) {
            lore.add(mini.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        for (Map.Entry<String, Integer> e : settings.enchantments().entrySet()) {
            Enchantment enchantment = enchantment(e.getKey());
            if (enchantment != null) {
                meta.addEnchant(enchantment, e.getValue(), true);
            }
        }
        meta.setUnbreakable(settings.unbreakable());
        meta.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * @param item any item, possibly {@code null}
     * @return {@code true} if it is an Amethyst Drill
     */
    public boolean isDrill(ItemStack item) {
        return item != null && item.getType() == Material.NETHERITE_PICKAXE && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }
}
