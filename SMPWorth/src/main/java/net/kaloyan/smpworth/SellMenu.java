package net.kaloyan.smpworth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * {@code /sell}: players put items into a menu (or use {@code /sell hand}) and are paid a percent of each item's worth. Items are taken
 * first and the money paid second; if the economy definitely refuses, the items go back; if it does not answer in time they do not (they
 * may have been paid for), and the console says so.
 */
public final class SellMenu implements Listener {

    private static final int ITEMS = 45;
    private static final int CANCEL = 45;
    private static final int INFO = 49;
    private static final int CONFIRM = 53;

    private static final class Holder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Line(int slot, ItemStack stack, long cents) {
    }

    private final Plugin plugin;
    private final WorthApplier applier;
    private final Supplier<WorthSettings> settings;
    private final Logger log;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param plugin   the owning plugin
     * @param applier  knows what items are worth
     * @param settings the current settings
     * @param log      the plugin logger
     */
    public SellMenu(Plugin plugin, WorthApplier applier, Supplier<WorthSettings> settings, Logger log) {
        this.plugin = plugin;
        this.applier = applier;
        this.settings = settings;
        this.log = log;
    }

    // ---------------------------------------------------------------- text

    private Component text(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(settings.get().messages().getOrDefault(key, key), values.build());
    }

    private static String money(long cents) {
        java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US));
        return "$" + format.format(cents / 100.0);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (lore.length > 0) {
            meta.lore(List.of(lore));
        }
        item.setItemMeta(meta);
        return item;
    }

    // ---------------------------------------------------------------- what is sold and for how much

    /** @return what the stack is paid in hundredths, or {@code -1} if it cannot be sold */
    long price(ItemStack stack) {
        WorthSettings.Sell sell = settings.get().sell();
        if (stack == null || stack.getType().isAir()) {
            return -1;
        }
        OptionalDouble worth = applier.worthOf(stack);
        if (worth.isEmpty() || worth.getAsDouble() <= 0) {
            return -1;
        }
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        int damage = 0;
        if (meta != null) {
            for (var key : meta.getPersistentDataContainer().getKeys()) {
                if (sell.protectedNamespaces().contains(key.getNamespace().toLowerCase(Locale.ROOT))) {
                    return -1;      // a special item of another plugin (the drill, bait, a spawner ...)
                }
            }
            if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                return -1;
            }
            if (meta instanceof BlockStateMeta box && box.hasBlockState() && box.getBlockState() instanceof Container container
                    && !container.getInventory().isEmpty()) {
                return -1;      // the contents would be lost
            }
            if (meta instanceof Damageable d) {
                damage = d.getDamage();
            }
        }
        long cents = SellMath.cents(worth.getAsDouble(), stack.getAmount(), damage, stack.getType().getMaxDurability(), sell.percent());
        return cents > 0 ? cents : -1;
    }

    private List<Line> lines(Inventory inv) {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            ItemStack stack = inv.getItem(i);
            long cents = price(stack);
            if (cents > 0) {
                out.add(new Line(i, stack, cents));
            }
        }
        return out;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    private enum Paid { PAID, REFUSED, UNSURE }

    private Paid pay(Player player, long cents) {
        Economy economy = economy();
        if (economy == null) {
            return Paid.REFUSED;
        }
        EconomyResponse response = economy.depositPlayer(player, cents / 100.0);
        if (response.transactionSuccess()) {
            return Paid.PAID;
        }
        String error = response.errorMessage == null ? "" : response.errorMessage.toUpperCase(Locale.ROOT);
        return error.contains("UNCONFIRMED") ? Paid.UNSURE : Paid.REFUSED;
    }

    // ---------------------------------------------------------------- /sell hand

    /**
     * Sells the stack in the player's main hand.
     *
     * @param player who sells
     */
    public void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        long cents = price(hand);
        if (cents <= 0) {
            player.sendMessage(text(hand.getType().isAir() ? "sell-empty-hand" : "sell-unsellable"));
            return;
        }
        ItemStack taken = hand.clone();
        player.getInventory().setItemInMainHand(null);      // the items go first: they are never in two places
        switch (pay(player, cents)) {
            case PAID -> player.sendMessage(text("sold", "amount", taken.getAmount(), "price", money(cents)));
            case REFUSED -> {
                player.getInventory().setItemInMainHand(taken);
                player.sendMessage(text("sell-failed"));
            }
            default -> unsure(player, cents);
        }
    }

    private void unsure(Player player, long cents) {
        log.severe("Payment of " + cents + " cents to " + player.getName() + " for /sell was not confirmed; the items are not returned. Check their balance.");
        player.sendMessage(text("sell-unsure"));
    }

    // ---------------------------------------------------------------- the menu

    /**
     * Opens the sell menu.
     *
     * @param player who sells
     */
    public void open(Player player) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 54, text("sell-title"));
        holder.inventory = inv;
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = ITEMS; i < 54; i++) {
            inv.setItem(i, pane);
        }
        inv.setItem(CANCEL, button(Material.RED_STAINED_GLASS_PANE, line("Cancel", NamedTextColor.RED), line("Gives your items back", NamedTextColor.GRAY)));
        draw(inv);
        player.openInventory(inv);
    }

    private void draw(Inventory inv) {
        List<Line> lines = lines(inv);
        long total = 0;
        int count = 0;
        for (Line l : lines) {
            total += l.cents();
            count += l.stack().getAmount();
        }
        WorthSettings.Sell sell = settings.get().sell();
        inv.setItem(INFO, button(Material.PAPER, line("You get " + sell.percent().stripTrailingZeros().toPlainString() + "% of an item's Worth", NamedTextColor.GRAY),
                line("Put items in the free slots above", NamedTextColor.GRAY), line("Items that can't be sold stay here and come back", NamedTextColor.GRAY)));
        inv.setItem(CONFIRM, lines.isEmpty()
                ? button(Material.GRAY_STAINED_GLASS_PANE, line("Nothing to sell yet", NamedTextColor.GRAY))
                : button(Material.LIME_STAINED_GLASS_PANE, line("SELL " + count + " item(s)", NamedTextColor.GREEN), line("You get " + money(total), NamedTextColor.WHITE)));
    }

    private void confirm(Player player, Inventory inv) {
        List<Line> lines = lines(inv);
        if (lines.isEmpty()) {
            player.sendMessage(text("sell-nothing"));
            return;
        }
        long total = 0;
        int count = 0;
        for (Line l : lines) {
            total += l.cents();
            count += l.stack().getAmount();
            inv.setItem(l.slot(), null);        // the items go first
        }
        switch (pay(player, total)) {
            case PAID -> player.sendMessage(text("sold", "amount", count, "price", money(total)));
            case REFUSED -> {
                for (Line l : lines) {
                    inv.setItem(l.slot(), l.stack());
                }
                player.sendMessage(text("sell-failed"));
            }
            default -> unsure(player, total);
        }
        draw(inv);
    }

    private void refreshLater(Inventory inv) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!inv.getViewers().isEmpty()) {
                draw(inv);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder)) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(s -> s >= ITEMS && s < 54)) {
            event.setCancelled(true);
        } else {
            refreshLater(event.getView().getTopInventory());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == top && event.getSlot() >= ITEMS) {
            event.setCancelled(true);
            if (event.getSlot() == CONFIRM) {
                confirm(player, top);
            } else if (event.getSlot() == CANCEL) {
                player.closeInventory();
            }
            return;
        }
        refreshLater(top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        for (int i = 0; i < ITEMS; i++) {
            ItemStack stack = event.getInventory().getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                event.getInventory().setItem(i, null);
                for (ItemStack rest : player.getInventory().addItem(stack).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        }
    }

    /** Closes open sell menus so nobody's items stay inside when the plugin stops. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }
}
