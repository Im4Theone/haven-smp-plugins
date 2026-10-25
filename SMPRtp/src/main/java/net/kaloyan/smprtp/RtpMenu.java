package net.kaloyan.smprtp;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** {@code /rtp} without a world opens this menu: one button per world, with how many players are there. */
public final class RtpMenu implements Listener {

    private static final TextColor GREEN = TextColor.color(0x00F986);
    private static final String[] KEYS = {"overworld", "nether", "end"};
    private static final int[] SLOTS = {11, 13, 15};
    private static final Material[] ICONS = {Material.GRASS_BLOCK, Material.NETHERRACK, Material.END_STONE};
    private static final String[] NAMES = {"Overworld", "Nether", "The End"};

    private static final class Holder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final RtpService service;

    /** @param service the random teleport service */
    public RtpMenu(RtpService service) {
        this.service = service;
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Opens the menu.
     *
     * @param player who sees it
     */
    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("ʀᴀɴᴅᴏᴍ ᴛᴇʟᴇᴘᴏʀᴛ", GREEN));
        holder.inventory = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.displayName(Component.text(" "));
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, pane);
        }
        RtpSettings settings = service.settings();
        for (int i = 0; i < KEYS.length; i++) {
            RtpWorld target = settings.worlds().get(KEYS[i]);
            World world = target == null ? null : Bukkit.getWorld(target.world());
            boolean allowed = player.hasPermission("smprtp.world." + KEYS[i]);
            ItemStack item = new ItemStack(target != null && target.enabled() && world != null && allowed ? ICONS[i] : Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(line(NAMES[i], GREEN).decorate(TextDecoration.BOLD));
            if (target == null || !target.enabled() || world == null) {
                meta.lore(List.of(line("Not available right now", NamedTextColor.RED)));
            } else if (!allowed) {
                meta.lore(List.of(line("Locked - you can't use this world yet", NamedTextColor.RED)));
            } else {
                meta.lore(List.of(line("Teleport to a random place in the " + NAMES[i], NamedTextColor.WHITE),
                        line("Players there: " + world.getPlayers().size(), NamedTextColor.GRAY),
                        line("Click to teleport", NamedTextColor.GREEN)));
            }
            item.setItemMeta(meta);
            inv.setItem(SLOTS[i], item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        for (int i = 0; i < KEYS.length; i++) {
            if (SLOTS[i] == event.getSlot()) {
                RtpWorld target = service.settings().worlds().get(KEYS[i]);
                player.closeInventory();
                if (!player.hasPermission("smprtp.world." + KEYS[i])) {
                    service.send(player, "no-permission-world", "world", KEYS[i]);
                } else if (target != null) {
                    service.start(player, target);
                }
                return;
            }
        }
    }
}
