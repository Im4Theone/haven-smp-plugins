package net.kaloyan.smpstats;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** The leaderboard menus: a choice of boards, and the ten best players of one board. */
public final class Menus implements Listener {

    private static final TextColor GREEN = TextColor.color(0x00F986);
    private static final TextColor GOLD = TextColor.color(0xFBBF24);
    private static final int[] RANK_SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    private static final int[] STAT_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int BACK = 40;

    private static final class Holder implements InventoryHolder {
        final Stat stat;
        Inventory inventory;

        Holder(Stat stat) {
            this.stat = stat;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Boards boards;

    /** @param boards the leaderboards */
    public Menus(Boards boards) {
        this.boards = boards;
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (lore.length > 0) {
            meta.lore(List.of(lore));
        }
        item.setItemMeta(meta);
        return item;
    }

    private Inventory create(Holder holder, int size, Component title) {
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.inventory = inv;
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < size; i++) {
            inv.setItem(i, pane);
        }
        return inv;
    }

    /**
     * Opens the choice of leaderboards.
     *
     * @param player who sees it
     */
    public void openMain(Player player) {
        Inventory inv = create(new Holder(null), 27, Component.text("ᴛᴏᴘ ᴘʟᴀʏᴇʀѕ", GREEN));
        Stat[] stats = Stat.values();
        for (int i = 0; i < stats.length; i++) {
            Stat s = stats[i];
            long mine = boards.valueOf(s, player.getUniqueId());
            int place = boards.placeOf(s, player.getUniqueId());
            inv.setItem(STAT_SLOTS[i], named(s.icon(), line(s.title(), GREEN).decorate(TextDecoration.BOLD),
                    line("Click to see the top 10", NamedTextColor.GRAY),
                    line("You: " + Boards.show(s, mine) + (place > 0 ? " (#" + place + ")" : ""), NamedTextColor.WHITE)));
        }
        player.openInventory(inv);
    }

    /**
     * Opens the top ten of one leaderboard.
     *
     * @param player who sees it
     * @param stat   the leaderboard
     */
    public void openBoard(Player player, Stat stat) {
        Inventory inv = create(new Holder(stat), 45, Component.text(stat.title().toUpperCase(java.util.Locale.ROOT) + " · TOP 10", GREEN));
        List<StatsStore.Row> rows = boards.top(stat, 10);
        for (int i = 0; i < RANK_SLOTS.length; i++) {
            if (i >= rows.size()) {
                inv.setItem(RANK_SLOTS[i], named(Material.GRAY_STAINED_GLASS_PANE, line("#" + (i + 1) + " - nobody yet", NamedTextColor.DARK_GRAY)));
                continue;
            }
            StatsStore.Row row = rows.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.player()));
            TextColor color = i == 0 ? GOLD : i == 1 ? TextColor.color(0xD1D5DB) : i == 2 ? TextColor.color(0xD97706) : NamedTextColor.WHITE;
            meta.displayName(line("#" + (i + 1) + " " + row.name(), color).decorate(TextDecoration.BOLD));
            meta.lore(List.of(line(stat.title() + ": " + Boards.show(stat, row.value()), NamedTextColor.WHITE)));
            head.setItemMeta(meta);
            inv.setItem(RANK_SLOTS[i], head);
        }
        long mine = boards.valueOf(stat, player.getUniqueId());
        int place = boards.placeOf(stat, player.getUniqueId());
        inv.setItem(31, named(Material.PAPER, line("Your place", GREEN), line(place > 0 ? "#" + place : "not ranked yet", NamedTextColor.WHITE),
                line(stat.title() + ": " + Boards.show(stat, mine), NamedTextColor.GRAY)));
        if ((stat == Stat.MONEY || stat == Stat.SHARDS) && boards.refreshed() == 0) {
            inv.setItem(30, named(Material.CLOCK, line("Still loading", NamedTextColor.YELLOW), line("Open the board again in a moment", NamedTextColor.GRAY)));
        }
        inv.setItem(BACK, named(Material.ARROW, line("Back", NamedTextColor.GREEN)));
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
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (holder.stat != null) {
            if (event.getSlot() == BACK) {
                openMain(player);
            }
            return;
        }
        Stat[] stats = Stat.values();
        for (int i = 0; i < stats.length; i++) {
            if (STAT_SLOTS[i] == event.getSlot()) {
                openBoard(player, stats[i]);
                return;
            }
        }
    }
}
