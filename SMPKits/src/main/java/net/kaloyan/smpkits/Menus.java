package net.kaloyan.smpkits;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The kit menu and the kit preview. */
public final class Menus implements Listener {

    private enum Page { MAIN, PREVIEW }

    private static final class Holder implements InventoryHolder {
        final Page page;
        final String kit;
        Inventory inventory;

        Holder(Page page, String kit) {
            this.page = page;
            this.kit = kit;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Supplier<Settings> settings;
    private final KitService service;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param settings the current settings
     * @param service  hands out kits
     */
    public Menus(Supplier<Settings> settings, KitService service) {
        this.settings = settings;
        this.service = service;
    }

    private static Component white(String text) {
        return Component.text(text, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private Component kitName(Kit kit) {
        return mini.deserialize(kit.name()).decoration(TextDecoration.ITALIC, false);
    }

    private Inventory create(Holder holder, int size, String title, TagResolver resolver) {
        Inventory inv = Bukkit.createInventory(holder, size, mini.deserialize(settings.get().title(title), resolver));
        holder.inventory = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        for (int i = 0; i < size; i++) {
            inv.setItem(i, pane);
        }
        return inv;
    }

    /**
     * Opens the kit menu.
     *
     * @param player who sees it
     */
    public void openMain(Player player) {
        Inventory inv = create(new Holder(Page.MAIN, ""), 27, "main", TagResolver.empty());
        long now = System.currentTimeMillis();
        for (Kit kit : settings.get().kits().values()) {
            KitService.Result r = service.availability(player, kit, now);
            List<Component> lore = new ArrayList<>();
            Material icon = kit.icon();
            switch (r.outcome()) {
                case OK -> lore.add(Component.text("Ready - click to claim", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                case COOLDOWN -> lore.add(Component.text("Ready again in " + Durations.format(r.remaining()), NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
                case USED -> {
                    lore.add(Component.text("Already claimed (one time only)", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                    icon = Material.GRAY_DYE;
                }
                default -> {
                    lore.add(Component.text("Locked - needs the " + kit.role() + " rank", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                    icon = Material.BARRIER;
                }
            }
            lore.add(gray("Right-click to preview"));
            ItemStack stack = new ItemStack(icon);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(kitName(kit));
            meta.lore(lore);
            stack.setItemMeta(meta);
            inv.setItem(kit.slot(), stack);
        }
        player.openInventory(inv);
    }

    /**
     * Shows what a kit contains.
     *
     * @param player who sees it
     * @param kit    the kit
     */
    public void openPreview(Player player, Kit kit) {
        List<ItemStack> items = service.items(kit);
        int rows = Math.min(6, 2 + (items.size() + 8) / 9);
        Inventory inv = create(new Holder(Page.PREVIEW, kit.id()), rows * 9, "preview", Placeholder.parsed("kit", kit.name()));
        for (int i = 0; i < items.size() && i < (rows - 2) * 9; i++) {
            inv.setItem(9 + i, items.get(i));
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta meta = back.getItemMeta();
        meta.displayName(Component.text("Back", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(meta);
        inv.setItem(rows * 9 - 5, back);
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
        if (holder.page == Page.PREVIEW) {
            if (event.getSlot() == holder.inventory.getSize() - 5) {
                openMain(player);
            }
            return;
        }
        for (Kit kit : settings.get().kits().values()) {
            if (kit.slot() == event.getSlot()) {
                if (event.isRightClick()) {
                    openPreview(player, kit);
                } else {
                    player.closeInventory();
                    Bukkit.dispatchCommand(player, "kit " + kit.id());
                }
                return;
            }
        }
    }
}
