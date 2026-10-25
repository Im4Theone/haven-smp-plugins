package net.kaloyan.smpcrates;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Builds the item stacks for rewards and menus. Needs a running server (materials and enchantments come from its registries). */
public final class Items {

    private final Messages messages;

    /** @param messages renders MiniMessage names */
    public Items(Messages messages) {
        this.messages = messages;
    }

    /**
     * Checks every enchantment named in the config against this server.
     *
     * @param settings the settings
     * @return a message per unknown enchantment; empty if all are known
     */
    public static List<String> unknownEnchantments(CratesSettings settings) {
        List<String> out = new ArrayList<>();
        for (CrateType crate : settings.crates().values()) {
            Material icon = Material.getMaterial(crate.material());
            if (icon == null || !icon.isItem()) {
                out.add("crates." + crate.id() + ".material: '" + crate.material() + "' is not an item on this server");
            }
            for (Reward reward : crate.rewards()) {
                Material m = Material.getMaterial(reward.material());
                if (m == null || !m.isItem()) {
                    out.add("crates." + crate.id() + ": '" + reward.material() + "' is not an item on this server");
                }
                for (String name : reward.enchants().keySet()) {
                    if (enchantment(name) == null) {
                        out.add("crates." + crate.id() + ": this server has no enchantment '" + name + "'");
                    }
                }
            }
        }
        return out;
    }

    /** @return the enchantment with this name on this server, or {@code null} */
    static Enchantment enchantment(String name) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(name));
    }

    /** A line without the italics Minecraft adds to renamed items. */
    public static Component plain(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * The item(s) an ITEM reward hands over, split into stacks.
     *
     * @param reward an ITEM reward
     * @return the stacks
     */
    public List<ItemStack> stacksOf(Reward reward) {
        Material material = Material.getMaterial(reward.material());
        List<ItemStack> out = new ArrayList<>();
        long left = reward.amount();
        int max = Math.max(1, material.getMaxStackSize());
        while (left > 0) {
            int n = (int) Math.min(left, max);
            ItemStack stack = new ItemStack(material, n);
            ItemMeta meta = stack.getItemMeta();
            if (!reward.name().isEmpty()) {
                meta.displayName(plain(messages.parse(reward.name())));
            }
            for (Map.Entry<String, Integer> e : reward.enchants().entrySet()) {
                Enchantment enchantment = enchantment(e.getKey());
                if (enchantment != null) {
                    meta.addEnchant(enchantment, e.getValue(), true);
                }
            }
            stack.setItemMeta(meta);
            out.add(stack);
            left -= n;
        }
        return out;
    }

    /** @return the name of a reward as shown in menus and messages */
    public Component rewardName(Reward reward) {
        if (!reward.name().isEmpty()) {
            return messages.parse(reward.name());
        }
        return switch (reward.kind()) {
            case ITEM -> Component.text(reward.amount() + "x " + pretty(reward.material()), NamedTextColor.WHITE);
            case MONEY -> Component.text("$" + String.format("%,d", reward.amount()), NamedTextColor.GREEN);
            case SHARDS -> Component.text(reward.amount() + " Shards", NamedTextColor.AQUA);
            case COMMAND -> Component.text("Special reward", NamedTextColor.GOLD);
        };
    }

    private static String pretty(String material) {
        StringBuilder sb = new StringBuilder();
        for (String part : material.toLowerCase().split("_")) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * The item that stands for a reward in a menu.
     *
     * @param reward the reward
     * @param lore   extra lore lines
     * @return the display item
     */
    public ItemStack displayOf(Reward reward, List<Component> lore) {
        Material material = Material.getMaterial(reward.material());
        ItemStack stack = new ItemStack(material, reward.kind() == Reward.Kind.ITEM ? (int) Math.min(reward.amount(), material.getMaxStackSize()) : 1);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(rewardName(reward)));
        if (reward.kind() == Reward.Kind.ITEM) {
            for (Map.Entry<String, Integer> e : reward.enchants().entrySet()) {
                Enchantment enchantment = enchantment(e.getKey());
                if (enchantment != null) {
                    meta.addEnchant(enchantment, e.getValue(), true);
                }
            }
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
