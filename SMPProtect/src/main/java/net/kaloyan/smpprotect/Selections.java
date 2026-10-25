package net.kaloyan.smpprotect;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Each admin's two selected corners, picked with the wand (left click = first, right click = second). */
public final class Selections implements Listener {

    /** A selected block. */
    public record Point(String world, int x, int y, int z) {
    }

    /**
     * A finished selection.
     *
     * @param first  first corner
     * @param second second corner
     */
    public record Selection(Point first, Point second) {
    }

    private final NamespacedKey wandKey;
    private final Map<UUID, Point> first = new HashMap<>();
    private final Map<UUID, Point> second = new HashMap<>();
    private volatile Material wandMaterial = Material.BLAZE_ROD;

    /** @param plugin the owning plugin */
    public Selections(Plugin plugin) {
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    /** @param material what the wand item is made of */
    public void wandMaterial(Material material) {
        this.wandMaterial = material;
    }

    /** @return a new wand item */
    public ItemStack wand() {
        ItemStack item = new ItemStack(wandMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Region wand", NamedTextColor.GOLD));
        meta.lore(java.util.List.of(Component.text("Left click: first corner", NamedTextColor.GRAY),
                Component.text("Right click: second corner", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == wandMaterial && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /**
     * @param player a player
     * @param which  1 or 2
     * @param at     the block position
     */
    public void set(Player player, int which, Location at) {
        Point point = new Point(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        (which == 1 ? first : second).put(player.getUniqueId(), point);
        player.sendMessage(Component.text("Corner " + which + " set to " + point.x() + " " + point.y() + " " + point.z()
                + " in '" + point.world() + "'.", NamedTextColor.GREEN));
    }

    /**
     * @param player a player
     * @return the selection, if both corners are set and in the same world
     */
    public Optional<Selection> get(Player player) {
        Point a = first.get(player.getUniqueId());
        Point b = second.get(player.getUniqueId());
        if (a == null || b == null || !a.world().equals(b.world())) {
            return Optional.empty();
        }
        return Optional.of(new Selection(a, b));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !isWand(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!player.hasPermission("smpprotect.admin")) {
            return;
        }
        set(player, event.getAction() == Action.LEFT_CLICK_BLOCK ? 1 : 2, event.getClickedBlock().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        first.remove(event.getPlayer().getUniqueId());
        second.remove(event.getPlayer().getUniqueId());
    }
}
