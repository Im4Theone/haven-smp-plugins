package net.kaloyan.smpshop;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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

/** The shop menus. Nothing can be taken out of or put into them except through the buttons. */
public final class ShopMenus implements Listener {

    private static final TextColor GREEN = TextColor.color(0x4FF750);
    private static final TextColor TEAL = TextColor.color(0x5EEAD4);
    private static final int BUY_MAX = 64;
    private static final int SLOT_SHARDS = 15;

    private enum Page { MAIN, CATEGORY, BUY, SHARDS, SHARD_BUY }

    private static final class Holder implements InventoryHolder {
        final Page page;
        final String category;
        final int index;
        int quantity = 1;
        Inventory inventory;

        Holder(Page page, String category, int index) {
            this.page = page;
            this.category = category;
            this.index = index;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Supplier<Catalog> catalog;
    private final ShardManager shards;
    private final Seller seller;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param catalog the current catalog
     * @param shards  the shard balances
     * @param seller  makes the purchases
     */
    public ShopMenus(Supplier<Catalog> catalog, ShardManager shards, Seller seller) {
        this.catalog = catalog;
        this.shards = shards;
        this.seller = seller;
    }

    // ---------------------------------------------------------------- text helpers

    /**
     * @param cents an amount in hundredths
     * @return for example {@code $1,234.5}
     */
    public static String money(long cents) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return "$" + format.format(cents / 100.0);
    }

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component white(String text) {
        return plain(text, NamedTextColor.WHITE);
    }

    private Component markup(String text) {
        return mini.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private Component message(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(catalog.get().message(key), values.build());
    }

    private static ItemStack named(Material material, int amount, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (name != null) {
            meta.displayName(name);
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static String pretty(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    private void fill(Inventory inv) {
        ItemStack pane = named(Material.BLACK_STAINED_GLASS_PANE, 1, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }
    }

    private ItemStack backButton() {
        return named(Material.RED_STAINED_GLASS_PANE, 1, markup(catalog.get().title("back")), List.of(white("Click to return")));
    }

    private Inventory create(Holder holder, int size, String title) {
        Inventory inv = Bukkit.createInventory(holder, size, mini.deserialize(title));
        holder.inventory = inv;
        fill(inv);
        return inv;
    }

    // ---------------------------------------------------------------- pages

    /**
     * Opens the main menu.
     *
     * @param player who sees it
     */
    public void openMain(Player player) {
        Catalog c = catalog.get();
        Inventory inv = create(new Holder(Page.MAIN, "", 0), 27, c.title("main"));
        for (Catalog.Category category : c.categories().values()) {
            inv.setItem(category.slot(), named(category.icon(), 1, markup(category.name()), List.of(white("Click to open"))));
        }
        inv.setItem(SLOT_SHARDS, named(Material.AMETHYST_SHARD, 1, markup(c.title("shard-name")), List.of(white("Click to open"))));
        player.openInventory(inv);
    }

    private void openCategory(Player player, Catalog.Category category) {
        int rows = 2 + (category.items().size() + 8) / 9;
        Inventory inv = create(new Holder(Page.CATEGORY, category.id(), 0), rows * 9, category.title());
        inv.setItem((rows - 1) * 9, backButton());
        for (int i = 0; i < category.items().size(); i++) {
            Catalog.Entry entry = category.items().get(i);
            inv.setItem(9 + i, named(entry.material(), 1, null,
                    List.of(white("Buy Price: ").append(plain(money(entry.cents()), GREEN)), white("Click to choose an amount"))));
        }
        player.openInventory(inv);
    }

    private void openShards(Player player) {
        Catalog c = catalog.get();
        Inventory inv = create(new Holder(Page.SHARDS, "", 0), 27, c.title("shards"));
        inv.setItem(18, backButton());
        for (int i = 0; i < c.keys().size(); i++) {
            inv.setItem(2 + i, offerIcon(c.keys().get(i), "Click to buy this key"));
        }
        for (int i = 0; i < c.spawners().size(); i++) {
            inv.setItem(9 + i, offerIcon(c.spawners().get(i), "Click to buy this spawner"));
        }
        inv.setItem(22, named(Material.PRISMARINE_SHARD, 1, plain("Your Shards", TEAL),
                List.of(white(shards.get(player.getUniqueId()) + " Shards"), plain("You find 1 now and then while playing", NamedTextColor.GRAY))));
        player.openInventory(inv);
    }

    private ItemStack offerIcon(Catalog.Offer offer, String hint) {
        return named(offer.icon(), 1, markup(offer.name()),
                List.of(white("Price: ").append(plain(offer.shards() + " Shards", TEAL)), plain(hint, NamedTextColor.GRAY)));
    }

    private void openBuy(Player player, Page page, String category, int index, int quantity) {
        Holder holder = new Holder(page, category, index);
        holder.quantity = quantity;
        String title = page == Page.BUY
                ? "Buy " + pretty(catalog.get().categories().get(category).items().get(index).material().name())
                : "Buy " + pretty(offerAt(index).id());
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text(title.length() > 32 ? title.substring(0, 32) : title));
        holder.inventory = inv;
        drawBuy(inv, holder);
        player.openInventory(inv);
    }

    private Catalog.Offer offerAt(int index) {
        Catalog c = catalog.get();
        return index < c.keys().size() ? c.keys().get(index) : c.spawners().get(index - c.keys().size());
    }

    private int maxQuantity(Holder holder) {
        if (holder.page == Page.BUY) {
            return Math.min(BUY_MAX, catalog.get().categories().get(holder.category).items().get(holder.index).material().getMaxStackSize());
        }
        return BUY_MAX;
    }

    private void drawBuy(Inventory inv, Holder h) {
        fill(inv);
        int max = maxQuantity(h);
        int q = h.quantity;
        ItemStack display;
        if (h.page == Page.BUY) {
            Catalog.Entry entry = catalog.get().categories().get(h.category).items().get(h.index);
            display = named(entry.material(), q, null,
                    List.of(white("Buy Price: ").append(plain(money(entry.cents() * q), GREEN))));
        } else {
            Catalog.Offer offer = offerAt(h.index);
            display = named(offer.icon(), q, markup(offer.name()), List.of(white("Price: ").append(plain(offer.shards() * q + " Shards", TEAL))));
        }
        inv.setItem(13, display);
        if (max > 1) {
            if (q > 1) {
                inv.setItem(9, pane(Material.RED_STAINED_GLASS_PANE, "Set to 1"));
                inv.setItem(11, pane(Material.RED_STAINED_GLASS_PANE, "Remove 1"));
            }
            if (q > 10) {
                inv.setItem(10, pane(Material.RED_STAINED_GLASS_PANE, "Remove 10"));
            }
            if (q < max) {
                inv.setItem(15, pane(Material.LIME_STAINED_GLASS_PANE, "Add 1"));
                inv.setItem(17, pane(Material.LIME_STAINED_GLASS_PANE, "Set to " + max));
                if (max - q >= 10) {
                    inv.setItem(16, pane(Material.LIME_STAINED_GLASS_PANE, "Add 10"));
                }
            }
        }
        inv.setItem(21, named(Material.RED_STAINED_GLASS_PANE, 1, markup(catalog.get().title("cancel")), List.of(white("Click to cancel"))));
        inv.setItem(23, named(Material.LIME_STAINED_GLASS_PANE, 1, markup(catalog.get().title("confirm")), List.of(white("Click to buy"))));
    }

    private ItemStack pane(Material material, String text) {
        NamedTextColor color = material == Material.RED_STAINED_GLASS_PANE ? NamedTextColor.RED : NamedTextColor.GREEN;
        return named(material, 1, plain(text, color), List.of());
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getSlot();
        Catalog c = catalog.get();
        click(player);
        switch (holder.page) {
            case MAIN -> {
                if (slot == SLOT_SHARDS) {
                    openShards(player);
                    return;
                }
                for (Catalog.Category category : c.categories().values()) {
                    if (category.slot() == slot) {
                        openCategory(player, category);
                        return;
                    }
                }
            }
            case CATEGORY -> {
                Catalog.Category category = c.categories().get(holder.category);
                int rows = holder.inventory.getSize() / 9;
                if (category == null) {
                    player.closeInventory();
                } else if (slot == (rows - 1) * 9) {
                    openMain(player);
                } else if (slot >= 9 && slot - 9 < category.items().size()) {
                    openBuy(player, Page.BUY, category.id(), slot - 9, 1);
                }
            }
            case SHARDS -> {
                int total = c.keys().size() + c.spawners().size();
                int index = slot >= 2 && slot < 2 + c.keys().size() ? slot - 2
                        : slot >= 9 && slot - 9 < c.spawners().size() ? c.keys().size() + slot - 9 : -1;
                if (slot == 18) {
                    openMain(player);
                } else if (index >= 0 && index < total) {
                    openBuy(player, Page.SHARD_BUY, "", index, 1);
                }
            }
            case BUY, SHARD_BUY -> buyPage(player, holder, slot);
        }
    }

    private void buyPage(Player player, Holder h, int slot) {
        Catalog c = catalog.get();
        int max = maxQuantity(h);
        int next = h.quantity;
        switch (slot) {
            case 9 -> next = 1;
            case 10 -> next = h.quantity - 10;
            case 11 -> next = h.quantity - 1;
            case 15 -> next = h.quantity + 1;
            case 16 -> next = h.quantity + 10;
            case 17 -> next = max;
            case 21 -> {
                if (h.page == Page.BUY) {
                    openCategory(player, c.categories().get(h.category));
                } else {
                    openShards(player);
                }
                return;
            }
            case 23 -> {
                purchase(player, h);
                return;
            }
            default -> {
                return;
            }
        }
        next = Math.max(1, Math.min(next, max));
        if (next != h.quantity) {
            h.quantity = next;
            drawBuy(h.inventory, h);
        }
    }

    private void purchase(Player player, Holder h) {
        Catalog c = catalog.get();
        Seller.Outcome outcome;
        String name;
        if (h.page == Page.BUY) {
            Catalog.Entry entry = c.categories().get(h.category).items().get(h.index);
            outcome = seller.buyItem(player, entry, h.quantity);
            name = pretty(entry.material().name());
        } else {
            Catalog.Offer offer = offerAt(h.index);
            outcome = seller.buyOffer(player, offer, h.quantity);
            name = pretty(offer.id()) + (offer.kind() == Catalog.Kind.KEY ? " Key" : " Spawner");
        }
        boolean money = h.page == Page.BUY;
        switch (outcome.status()) {
            case DONE -> {
                String price = money ? money(outcome.charged()) : outcome.charged() + " Shards";
                player.sendMessage(message(money ? "bought-item" : "bought-offer", "amount", outcome.given(), "item", name, "price", price));
                sound(player, "entity.experience_orb.pickup");
                if (!money) {
                    drawBuy(h.inventory, h);
                }
            }
            case BROKE -> {
                player.sendMessage(message(money ? "not-enough-money" : "not-enough-shards"));
                sound(player, "entity.villager.no");
            }
            case NO_ROOM -> {
                player.sendMessage(message("inventory-full"));
                sound(player, "entity.villager.no");
            }
            case UNSURE -> {
                player.sendMessage(message("payment-unsure"));
                sound(player, "entity.villager.no");
            }
            default -> {
                player.sendMessage(message("unavailable"));
                sound(player, "entity.villager.no");
            }
        }
    }

    private static void click(Player player) {
        sound(player, "ui.button.click");
    }

    private static void sound(Player player, String key) {
        player.playSound(player.getLocation(), key, 0.5f, 1f);
    }

    /**
     * Opens the shard shop directly.
     *
     * @param player who sees it
     */
    public void openShardShop(Player player) {
        openShards(player);
    }
}
