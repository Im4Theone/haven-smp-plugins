package net.kaloyan.smpspawners;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The two spawner menus: the main one (storage, experience, sell everything) and the storage pages. Nothing can be taken out of
 * or put into these inventories except through the buttons.
 */
public final class Menus implements Listener {

    private enum Page { MAIN, STORAGE }

    private static final class Holder implements InventoryHolder {
        final Page page;
        final Spawner.Pos pos;
        final int number;
        Inventory inventory;

        Holder(Page page, Spawner.Pos pos, int number) {
            this.page = page;
            this.pos = pos;
            this.number = number;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int SLOT_STORAGE = 11;
    private static final int SLOT_MIDDLE = 13;
    private static final int SLOT_XP = 15;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_SELL = 49;
    private static final int SLOT_NEXT = 50;
    private static final int PAGE_SIZE = 45;

    private final Registry registry;
    private final Actions actions;
    private final Supplier<Settings> settings;

    /**
     * @param registry the spawners
     * @param actions  what the buttons do
     * @param settings the current settings
     */
    public Menus(Registry registry, Actions actions, Supplier<Settings> settings) {
        this.registry = registry;
        this.actions = actions;
        this.settings = settings;
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

    private static String title(Spawner s) {
        return s.stack() + " " + Texts.pretty(s.kind()) + (s.stack() > 1 ? " Spawners" : " Spawner");
    }

    // ---------------------------------------------------------------- main menu

    /**
     * Opens the main menu.
     *
     * @param player  who sees it
     * @param spawner the spawner
     */
    public void openMain(Player player, Spawner spawner) {
        Holder holder = new Holder(Page.MAIN, spawner.pos(), 1);
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(title(spawner)));
        holder.inventory = inv;
        drawMain(inv, spawner);
        player.openInventory(inv);
    }

    private void drawMain(Inventory inv, Spawner s) {
        inv.clear();
        inv.setItem(SLOT_STORAGE, named(Material.CHEST, Texts.text("SPAWNER STORAGE", Texts.ORANGE, true),
                Texts.text(String.valueOf(s.totalItems()), Texts.ORANGE, false).append(Texts.white(" Items")),
                Texts.white("Click to view, collect and sell")));
        inv.setItem(SLOT_XP, named(Material.EXPERIENCE_BOTTLE, Texts.text("COLLECT XP", Texts.MINT, true),
                Texts.text(String.valueOf(s.xp()), Texts.MINT, false).append(Texts.white(" XP Points"))));
        Material head = Material.getMaterial(s.kind() + "_HEAD");
        if ("SKELETON".equals(s.kind())) {
            head = Material.SKELETON_SKULL;
        }
        String label = s.stack() + " " + s.kind().replace('_', ' ') + (s.stack() > 1 ? " SPAWNERS" : " SPAWNER");
        inv.setItem(SLOT_MIDDLE, named(head != null ? head : Material.SPAWNER, Texts.text(label, Texts.TEAL, true),
                Texts.text(s.fillPercent(settings.get()) + "%", Texts.TEAL, false).append(Texts.white(" filled")),
                Texts.white("Click to sell everything and collect XP")));
    }

    // ---------------------------------------------------------------- storage pages

    /**
     * Opens one page of the storage.
     *
     * @param player  who sees it
     * @param spawner the spawner
     * @param page    1-based page, clamped into range
     */
    public void openStorage(Player player, Spawner spawner, int page) {
        List<Map.Entry<String, Long>> stacks = stacks(spawner);
        int pages = Math.max(1, (stacks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int shown = Math.max(1, Math.min(page, pages));
        Holder holder = new Holder(Page.STORAGE, spawner.pos(), shown);
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(title(spawner)));
        holder.inventory = inv;
        int from = (shown - 1) * PAGE_SIZE;
        for (int i = from; i < Math.min(stacks.size(), from + PAGE_SIZE); i++) {
            Material material = Material.getMaterial(stacks.get(i).getKey());
            if (material == null) {
                continue;
            }
            long total = stacks.get(i).getValue();
            ItemStack item = new ItemStack(material, (int) Math.min(total, material.getMaxStackSize()));
            ItemMeta meta = item.getItemMeta();
            meta.lore(List.of(Texts.white(total + " in total"), Texts.white("Click: take a stack"), Texts.white("Shift click: take all that fits")));
            item.setItemMeta(meta);
            inv.setItem(i - from, item);
        }
        inv.setItem(SLOT_BACK, named(Material.ARROW, Texts.text("BACK", NamedTextColor.GRAY, true)));
        inv.setItem(SLOT_PREVIOUS, shown > 1
                ? named(Material.SPECTRAL_ARROW, Texts.text("BACKWARDS", NamedTextColor.YELLOW, true), Texts.white("Page " + (shown - 1) + "/" + pages))
                : named(Material.ARROW, Texts.text("BACKWARDS", NamedTextColor.RED, true)));
        inv.setItem(SLOT_NEXT, shown < pages
                ? named(Material.SPECTRAL_ARROW, Texts.text("FORWARDS", NamedTextColor.YELLOW, true), Texts.white("Page " + (shown + 1) + "/" + pages))
                : named(Material.ARROW, Texts.text("FORWARDS", NamedTextColor.RED, true)));
        inv.setItem(SLOT_SELL, named(Material.GOLD_INGOT, Texts.text("SELL ALL", NamedTextColor.YELLOW, true),
                Texts.white("Worth " + Texts.money(worth(spawner)))));
        player.openInventory(inv);
    }

    /** The stored items in a stable order, most plentiful first. */
    private static List<Map.Entry<String, Long>> stacks(Spawner s) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(s.loot().entrySet());
        list.sort((a, b) -> b.getValue() != a.getValue().longValue() ? Long.compare(b.getValue(), a.getValue()) : a.getKey().compareTo(b.getKey()));
        return list;
    }

    private long worth(Spawner s) {
        long sum = 0;
        for (Map.Entry<String, Long> e : s.loot().entrySet()) {
            sum += settings.get().pricesCents().getOrDefault(e.getKey(), 0L) * e.getValue();
        }
        return sum;
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        Spawner spawner = registry.at(holder.pos);
        if (spawner == null) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        Inventory top = event.getView().getTopInventory();
        if (holder.page == Page.MAIN) {
            if (slot == SLOT_STORAGE) {
                openStorage(player, spawner, 1);
            } else if (slot == SLOT_XP) {
                actions.collectXp(player, spawner);
                drawMain(top, spawner);
            } else if (slot == SLOT_MIDDLE) {
                actions.sellAll(player, spawner);
                actions.collectXp(player, spawner);
                drawMain(top, spawner);
            }
            return;
        }
        if (slot == SLOT_BACK) {
            openMain(player, spawner);
        } else if (slot == SLOT_PREVIOUS && clicked.getType() == Material.SPECTRAL_ARROW) {
            openStorage(player, spawner, holder.number - 1);
        } else if (slot == SLOT_NEXT && clicked.getType() == Material.SPECTRAL_ARROW) {
            openStorage(player, spawner, holder.number + 1);
        } else if (slot == SLOT_SELL) {
            actions.sellAll(player, spawner);
            openStorage(player, spawner, 1);
        } else if (slot < PAGE_SIZE) {
            actions.collectItem(player, spawner, clicked.getType().name(), event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT);
            openStorage(player, spawner, holder.number);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
