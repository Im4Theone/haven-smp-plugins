package net.kaloyan.smpinfo;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The /ranks menu: what every rank gives and where to buy it. Nothing can be taken out of it; a click on a rank sends the store link. */
public final class RankMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int[] RANK_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int STORE_SLOT = 22;

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Register this once: it keeps the menus read-only and reacts to clicks. */
    public static final Listener LISTENER = new Listener() {
        @EventHandler
        public void onClick(InventoryClickEvent event) {
            RankMenu.click(event);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof RankMenu) {
                event.setCancelled(true);
            }
        }
    };

    private final Inventory inventory;
    private final Settings.RankShop shop;
    private final String serverName;

    private RankMenu(Settings.RankShop shop, String serverName) {
        this.shop = shop;
        this.serverName = serverName;
        this.inventory = Bukkit.createInventory(this, SIZE, MINI.deserialize(shop.title()));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /**
     * Opens the menu for a player.
     *
     * @param player     who
     * @param shop       the configured ranks
     * @param serverName the server name for texts
     */
    public static void open(Player player, Settings.RankShop shop, String serverName) {
        RankMenu menu = new RankMenu(shop, serverName);
        menu.fill(player);
        player.openInventory(menu.inventory);
    }

    /**
     * @param ranks the ranks, cheapest first
     * @param owned the ids of the ranks the player has (this one or a higher one)
     * @return the index of the highest rank the player has, or -1
     */
    static int highestOwned(List<Settings.Rank> ranks, java.util.function.Predicate<String> owned) {
        int best = -1;
        for (int i = 0; i < ranks.size(); i++) {
            if (owned.test(ranks.get(i).id())) {
                best = i;
            }
        }
        return best;
    }

    private Component line(String text, TagResolver... values) {
        return MINI.deserialize("<!italic>" + text, TagResolver.resolver(values));
    }

    private void fill(Player player) {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.displayName(Component.empty());
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, pane);
        }
        List<Settings.Rank> ranks = shop.ranks();
        int mine = highestOwned(ranks, id -> player.hasPermission("group." + id));
        for (int i = 0; i < ranks.size() && i < RANK_SLOTS.length; i++) {
            Settings.Rank rank = ranks.get(i);
            ItemStack item = new ItemStack(rank.item());
            ItemMeta meta = item.getItemMeta();
            TagResolver price = Placeholder.unparsed("price", rank.price());
            meta.displayName(line(rank.name()));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            rank.perks().forEach(perk -> lore.add(line(perk)));
            lore.add(Component.empty());
            if (i <= mine) {
                lore.add(line(shop.owned()));
            } else {
                lore.add(line(shop.priceLine(), price));
                lore.add(line(shop.clickLine()));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            if (i <= mine) {
                meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
            inventory.setItem(RANK_SLOTS[i], item);
        }
        ItemStack store = new ItemStack(Material.EMERALD);
        ItemMeta meta = store.getItemMeta();
        meta.displayName(line(shop.storeName()));
        meta.lore(List.of(line(shop.storeLore(), Placeholder.unparsed("server", serverName))));
        store.setItemMeta(meta);
        inventory.setItem(STORE_SLOT, store);
    }

    private static void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RankMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        boolean rank = false;
        for (int i = 0; i < menu.shop.ranks().size() && i < RANK_SLOTS.length; i++) {
            rank |= RANK_SLOTS[i] == slot;
        }
        if (rank || slot == STORE_SLOT) {
            player.closeInventory();
            Component link = Component.text(menu.shop.url()).clickEvent(ClickEvent.openUrl(menu.shop.url())).decorate(TextDecoration.UNDERLINED);
            player.sendMessage(MINI.deserialize(menu.shop.linkMessage(), Placeholder.component("link", link),
                    Placeholder.unparsed("server", menu.serverName)));
        }
    }
}
