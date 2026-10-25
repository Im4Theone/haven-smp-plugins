package net.kaloyan.smpkits;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * A kit for one rank.
 *
 * @param id             the id (used in commands and in the permission {@code smpkits.kit.<id>})
 * @param name           MiniMessage display name
 * @param role           the rank's plain name, shown on locked kits
 * @param icon           the icon in the menu
 * @param slot           the slot in the menu (0-26)
 * @param cooldownMillis the wait between two claims, or {@link #ONCE}
 * @param items          what the kit contains
 * @param commands       console commands run after the items are given ({@code %player%} is the player's name)
 */
public record Kit(String id, String name, String role, Material icon, int slot, long cooldownMillis, List<Spec> items, List<String> commands) {

    /** The cooldown value of a kit that can be claimed once only. */
    public static final long ONCE = -1;

    /**
     * One item of a kit.
     *
     * @param material the item
     * @param amount   how many
     * @param name     MiniMessage name, or {@code null} for the normal name
     * @param lore     MiniMessage lore lines
     * @param enchants minecraft enchantment names with levels
     */
    public record Spec(Material material, int amount, String name, List<String> lore, Map<String, Integer> enchants) {
    }

    /** @return the permission that gives access to this kit */
    public String permission() {
        return "smpkits.kit." + id;
    }
}
