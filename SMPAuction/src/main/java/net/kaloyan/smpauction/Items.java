package net.kaloyan.smpauction;

import java.util.Base64;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;

/** Turns items into text that can be saved and back, without losing anything (names, lore, enchantments, shulker contents). */
public final class Items {

    private Items() {
    }

    /**
     * @param item a stack
     * @return the saved form
     */
    public static Listing.Goods pack(ItemStack item) {
        String data = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        Component custom = item.getItemMeta() == null ? null : item.getItemMeta().displayName();
        String name = custom == null ? pretty(item.getType().name()) : PlainTextComponentSerializer.plainText().serialize(custom);
        return new Listing.Goods(data, item.getType().name(), item.getAmount(), item.getAmount() + " " + name);
    }

    /**
     * @param goods a saved item
     * @return the stack
     */
    public static ItemStack unpack(Listing.Goods goods) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(goods.data()));
    }

    /**
     * @param id a material name
     * @return for example {@code Diamond Sword}
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
