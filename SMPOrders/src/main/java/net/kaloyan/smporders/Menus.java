package net.kaloyan.smporders;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** All the order menus: browse, your orders, manage, deliver, new order, item selector and enchantment picker. */
public final class Menus implements Listener {

    private static final TextColor GREEN = TextColor.color(0x4FF750);
    private static final TextColor TEAL = TextColor.color(0x00F986);
    private static final int ITEMS = 45;
    private static final int DELIVER_CONFIRM = 53;
    private static final int DELIVER_CANCEL = 45;
    private static final int DELIVER_INFO = 49;

    private enum Page { MAIN, MINE, MANAGE, DELIVER, NEW, SELECT, ENCHANT }

    /** How the browse list is ordered. */
    private enum Sort {
        RECENT("Recently Listed"), PAID("Most Paid"), DELIVERED("Most Delivered"), PER_ITEM("Most Money Per Item");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Which items the browse list shows. */
    private enum Filter {
        ALL("All"), BLOCKS("Blocks"), TOOLS("Tools"), COMBAT("Combat"), FOOD("Food"), BOOKS("Books"), MISC("Everything else");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        boolean accepts(Material m) {
            String n = m.name();
            boolean tool = n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS")
                    || n.equals("FISHING_ROD") || n.equals("FLINT_AND_STEEL");
            boolean combat = n.endsWith("_SWORD") || n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                    || n.endsWith("_BOOTS") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("SHIELD")
                    || n.equals("MACE") || n.equals("TOTEM_OF_UNDYING") || n.endsWith("ARROW");
            boolean books = n.equals("BOOK") || n.equals("ENCHANTED_BOOK");
            return switch (this) {
                case ALL -> true;
                case BLOCKS -> m.isBlock() && !tool && !combat;
                case TOOLS -> tool;
                case COMBAT -> combat;
                case FOOD -> m.isEdible();
                case BOOKS -> books;
                case MISC -> !tool && !combat && !books && !m.isBlock() && !m.isEdible();
            };
        }
    }

    private static final class View {
        Sort sort = Sort.RECENT;
        Filter filter = Filter.ALL;
        String search = "";
    }

    private static final class Draft {
        Material material;
        final Map<String, Integer> enchants = new TreeMap<>();
        int amount;
        long cents;

        ItemKey key() {
            return material == null ? null : new ItemKey(material, enchants);
        }
    }

    private static final class Holder implements InventoryHolder {
        final Page page;
        final long order;
        final int number;
        /** The order shown in each slot when the menu was drawn, so a click always means the order that was on screen. */
        final long[] ids = new long[ITEMS];
        Inventory inventory;

        Holder(Page page, long order, int number) {
            this.page = page;
            this.order = order;
            this.number = number;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final OrderService service;
    private final Supplier<Settings> settings;
    private final Prompts prompts;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private List<Material> catalogue;

    /**
     * @param plugin   the owning plugin
     * @param service  the rules
     * @param settings the current settings
     * @param prompts  chat prompts
     */
    public Menus(Plugin plugin, OrderService service, Supplier<Settings> settings, Prompts prompts) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        this.prompts = prompts;
    }

    // ---------------------------------------------------------------- helpers

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component white(String text) {
        return plain(text, NamedTextColor.WHITE);
    }

    private static Component gray(String text) {
        return plain(text, NamedTextColor.GRAY);
    }

    private Component message(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(settings.get().message(key), values.build());
    }

    private void say(Player player, String key, Object... placeholders) {
        player.sendMessage(message(key, placeholders));
    }

    private static ItemStack icon(ItemStack base, Component name, List<Component> lore) {
        ItemMeta meta = base.getItemMeta();
        if (name != null) {
            meta.displayName(name);
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        base.setItemMeta(meta);
        return base;
    }

    private static ItemStack button(Material material, Component name, Component... lore) {
        return icon(new ItemStack(material), name, List.of(lore));
    }

    private void fillAll(Inventory inv) {
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }
    }

    private Inventory open(Player player, Holder holder, int size, String titleKey, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        Inventory inv = Bukkit.createInventory(holder, size, mini.deserialize(settings.get().title(titleKey), values.build()));
        holder.inventory = inv;
        fillAll(inv);
        player.openInventory(inv);
        return inv;
    }

    private View view(Player player) {
        return views.computeIfAbsent(player.getUniqueId(), id -> new View());
    }

    private Draft draft(Player player) {
        return drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
    }

    private Component expiry(Order o) {
        long left = o.expires() - System.currentTimeMillis();
        return gray(left <= 0 ? "Expiring" : Amounts.duration(left) + " until the order expires");
    }

    // ---------------------------------------------------------------- the browse list

    /**
     * Opens the list of all open orders.
     *
     * @param player who sees it
     * @param search an item name to start with, or empty
     */
    public void openMain(Player player, String search) {
        View v = view(player);
        v.search = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        openMain(player, 0);
    }

    private List<Order> visible(View v) {
        List<Order> list = new ArrayList<>();
        for (Order o : service.store().view()) {
            if (o.open() && v.filter.accepts(o.item().material())
                    && (v.search.isEmpty() || o.item().label().toLowerCase(Locale.ROOT).contains(v.search))) {
                list.add(o);
            }
        }
        Comparator<Order> by = switch (v.sort) {
            case PAID -> Comparator.comparingLong((Order o) -> o.priceCents() * o.amount()).reversed();
            case DELIVERED -> Comparator.<Order>comparingInt(Order::filled).reversed();
            case PER_ITEM -> Comparator.<Order>comparingLong(Order::priceCents).reversed();
            default -> Comparator.<Order>comparingLong(Order::created).reversed();
        };
        list.sort(by);
        return list;
    }

    private void openMain(Player player, int page) {
        View v = view(player);
        List<Order> list = visible(v);
        int pages = Math.max(1, (list.size() + ITEMS - 1) / ITEMS);
        int p = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder(Page.MAIN, 0, p);
        Inventory inv = open(player, holder, 54, "main", "page", p + 1);
        for (int i = 0; i < ITEMS && p * ITEMS + i < list.size(); i++) {
            Order shown = list.get(p * ITEMS + i);
            holder.ids[i] = shown.id();
            inv.setItem(i, orderIcon(shown, player, true));
        }
        inv.setItem(45, button(Material.HOPPER, plain("SORT", NamedTextColor.LIGHT_PURPLE), white("Current: " + v.sort.label),
                gray("Click to change")));
        inv.setItem(46, button(Material.BREWING_STAND, plain("FILTER", NamedTextColor.LIGHT_PURPLE), white("Current: " + v.filter.label),
                gray("Click to change")));
        inv.setItem(47, button(Material.OAK_SIGN, plain("SEARCH", NamedTextColor.YELLOW),
                white("Current: " + (v.search.isEmpty() ? "None" : v.search)), gray("Click to type a name, right-click to clear")));
        inv.setItem(48, p > 0 ? button(Material.ARROW, plain("Previous Page", NamedTextColor.YELLOW))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No Previous Page", NamedTextColor.GRAY)));
        inv.setItem(49, button(Material.LIME_DYE, plain("Refresh", NamedTextColor.GREEN), gray(list.size() + " open orders")));
        inv.setItem(50, p + 1 < pages ? button(Material.ARROW, plain("Next Page", NamedTextColor.GREEN))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No Next Page", NamedTextColor.GRAY)));
        inv.setItem(52, button(Material.CHEST, plain("YOUR ORDERS", NamedTextColor.GREEN), white("Manage your orders")));
        inv.setItem(53, button(Material.WRITABLE_BOOK, plain("New Order", NamedTextColor.GREEN), white("Click to create a buy order")));
    }

    private ItemStack orderIcon(Order o, Player viewer, boolean forDelivery) {
        ItemStack shown = o.item().create(1);
        List<Component> lore = new ArrayList<>();
        lore.add(plain(o.ownerName() + "'s Order", NamedTextColor.GREEN));
        lore.add(white(Amounts.money(o.priceCents()) + " each"));
        lore.add(white("Delivered: " + o.filled() + "/" + o.amount()));
        lore.add(white("Paid: " + Amounts.money(o.priceCents() * o.filled()) + " of " + Amounts.money(o.priceCents() * o.amount())));
        if (o.closed() == Order.Closed.NO) {
            lore.add(expiry(o));
        } else {
            lore.add(gray(o.closed() == Order.Closed.COMPLETE ? "Complete" : o.closed() == Order.Closed.EXPIRED ? "Expired" : "Cancelled"));
        }
        if (o.collectable() > 0) {
            lore.add(plain(o.collectable() + " item(s) waiting to be collected", NamedTextColor.GOLD));
        }
        if (forDelivery) {
            lore.add(o.owner().equals(viewer.getUniqueId()) ? gray("Your order - click to manage")
                    : white("Click to deliver " + o.item().label()));
        } else {
            lore.add(gray("Click to manage"));
        }
        ItemMeta meta = shown.getItemMeta();
        meta.lore(lore);
        shown.setItemMeta(meta);
        return shown;
    }

    // ---------------------------------------------------------------- your orders

    /**
     * Opens the player's own orders.
     *
     * @param player who sees them
     */
    public void openMine(Player player) {
        List<Order> mine = service.store().of(player.getUniqueId());
        mine.sort(Comparator.<Order>comparingLong(Order::created).reversed());
        Holder holder = new Holder(Page.MINE, 0, 0);
        Inventory inv = open(player, holder, 54, "mine");
        for (int i = 0; i < ITEMS && i < mine.size(); i++) {
            holder.ids[i] = mine.get(i).id();
            inv.setItem(i, orderIcon(mine.get(i), player, false));
        }
        long owed = service.store().owed(player.getUniqueId());
        if (owed > 0) {
            inv.setItem(49, button(Material.GOLD_INGOT, plain("Waiting for payment", NamedTextColor.GOLD),
                    white(Amounts.money(owed) + " will be paid out soon")));
        }
        inv.setItem(45, button(Material.RED_STAINED_GLASS_PANE, plain("Back", NamedTextColor.RED)));
        inv.setItem(53, button(Material.WRITABLE_BOOK, plain("New Order", NamedTextColor.GREEN)));
    }

    private void openManage(Player player, Order o) {
        Inventory inv = open(player, new Holder(Page.MANAGE, o.id(), 0), 27, "manage");
        inv.setItem(11, orderIcon(o, player, false));
        inv.setItem(13, o.collectable() > 0
                ? button(Material.CHEST, plain("COLLECT ITEMS", NamedTextColor.GREEN), white("Items waiting: " + o.collectable()),
                        gray("Click to collect"))
                : button(Material.BARRIER, plain("Nothing to collect", NamedTextColor.GRAY)));
        inv.setItem(15, o.open()
                ? button(Material.RED_STAINED_GLASS_PANE, plain("CANCEL ORDER", NamedTextColor.RED),
                        white("Refunds " + Amounts.money(o.escrowCents()) + " (the tax is not refunded)"))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("Order closed", NamedTextColor.GRAY)));
        inv.setItem(22, button(Material.ARROW, plain("Back", NamedTextColor.GREEN)));
    }

    // ---------------------------------------------------------------- delivering

    private void openDeliver(Player player, Order o) {
        Inventory inv = open(player, new Holder(Page.DELIVER, o.id(), 0), 54, "deliver");
        for (int i = 0; i < ITEMS; i++) {
            inv.setItem(i, null);
        }
        inv.setItem(DELIVER_CANCEL, button(Material.RED_STAINED_GLASS_PANE, plain("Cancel", NamedTextColor.RED),
                white("Return the items to your inventory")));
        drawDeliverInfo(inv, o);
    }

    private int[] plan(Order o, Inventory inv) {
        int[] take = new int[ITEMS];
        int left = o.remaining();
        for (int i = 0; i < ITEMS && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && o.item().accepts(s)) {
                take[i] = Math.min(s.getAmount(), left);
                left -= take[i];
            }
        }
        return take;
    }

    private void drawDeliverInfo(Inventory inv, Order o) {
        int count = 0;
        for (int n : plan(o, inv)) {
            count += n;
        }
        inv.setItem(DELIVER_INFO, orderIcon(o, null, false));
        inv.setItem(DELIVER_CONFIRM, count == 0
                ? button(Material.GRAY_STAINED_GLASS_PANE, plain("Put " + o.item().label() + " here", NamedTextColor.GRAY),
                        gray("Place the items in the free slots above"))
                : button(Material.LIME_STAINED_GLASS_PANE, plain("DELIVER " + count, NamedTextColor.GREEN),
                        white("You receive " + Amounts.money(o.priceCents() * count))));
    }

    private void confirmDeliver(Player player, Order o, Inventory inv) {
        int[] take = plan(o, inv);
        int count = 0;
        for (int n : take) {
            count += n;
        }
        if (count == 0) {
            say(player, "wrong-items", "item", o.item().label());
            return;
        }
        OrderService.Result r = service.deliver(player.getUniqueId(), o, count);
        switch (r.status()) {
            case OK -> {
                for (int i = 0; i < ITEMS; i++) {
                    ItemStack s = inv.getItem(i);
                    if (take[i] > 0 && s != null) {
                        if (s.getAmount() <= take[i]) {
                            inv.setItem(i, null);
                        } else {
                            s.setAmount(s.getAmount() - take[i]);
                        }
                    }
                }
                say(player, "delivered", "amount", count, "item", o.item().label(), "price", Amounts.money(r.cents()));
                if (r.pending()) {
                    say(player, "payout-later");
                }
                Player buyer = Bukkit.getPlayer(o.owner());
                if (buyer != null) {
                    say(buyer, "order-received", "player", player.getName(), "amount", count, "item", o.item().label());
                    if (o.closed() == Order.Closed.COMPLETE) {
                        say(buyer, "order-complete", "item", o.item().label());
                    }
                }
                player.closeInventory();
            }
            case NOT_OPEN -> {
                say(player, "order-gone");
                player.closeInventory();
            }
            case OWN_ORDER -> say(player, "own-order");
            default -> say(player, "failed");
        }
    }

    // ---------------------------------------------------------------- new order

    /**
     * Opens the new-order screen.
     *
     * @param player who sees it
     */
    public void openNew(Player player) {
        Draft d = draft(player);
        Settings s = settings.get();
        Inventory inv = open(player, new Holder(Page.NEW, 0, 0), 27, "new");
        ItemKey key = d.key();
        inv.setItem(10, key == null
                ? button(Material.ITEM_FRAME, plain("ITEM", NamedTextColor.YELLOW), white("Not selected"),
                        gray("Click to choose from a list, or click with an item on your cursor"))
                : icon(key.create(1), plain(key.label(), NamedTextColor.AQUA), List.of(gray("Click to change the item"))));
        inv.setItem(12, key != null && canEnchant(key.material())
                ? button(Material.ENCHANTED_BOOK, plain("ENCHANTMENTS", NamedTextColor.LIGHT_PURPLE),
                        white(d.enchants.isEmpty() ? "None selected" : d.enchants.size() + " selected"), gray("Click to pick"))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No enchantments", NamedTextColor.GRAY)));
        inv.setItem(14, button(Material.PAPER, plain("AMOUNT", NamedTextColor.GREEN),
                white(d.amount > 0 ? "Amount: " + d.amount : "Not set"), gray("Click and type it in chat (1k = 1000)")));
        inv.setItem(16, button(Material.GOLD_NUGGET, plain("PRICE", NamedTextColor.AQUA),
                white(d.cents > 0 ? "Price per item: " + Amounts.money(d.cents) : "Not set"), gray("Click and type it in chat (1.5k = 1500)")));
        if (key != null && d.amount > 0 && d.cents > 0) {
            long total = d.cents * d.amount;
            inv.setItem(22, button(Material.LIME_STAINED_GLASS_PANE, plain("CONFIRM", NamedTextColor.GREEN),
                    white("Total: " + Amounts.money(total)), white("Tax: " + Amounts.money(s.tax(total))),
                    white("You pay: " + Amounts.money(total + s.tax(total))), gray("Click to publish the order")));
        } else {
            inv.setItem(22, button(Material.GRAY_STAINED_GLASS_PANE, plain("Set item, amount and price first", NamedTextColor.GRAY)));
        }
        inv.setItem(18, button(Material.RED_STAINED_GLASS_PANE, plain("CANCEL", NamedTextColor.RED)));
    }

    private static boolean canEnchant(Material material) {
        return material == Material.ENCHANTED_BOOK || material.getMaxDurability() > 0;
    }

    private void ask(Player player, String promptKey, java.util.function.Consumer<String> apply) {
        player.closeInventory();
        say(player, promptKey);
        say(player, "prompt-cancel");
        prompts.ask(player, apply, () -> openNew(player));
    }

    private void confirmNew(Player player) {
        Draft d = draft(player);
        ItemKey key = d.key();
        if (key == null || d.amount < 1 || d.cents < 1) {
            say(player, "set-all");
            return;
        }
        OrderService.Result r = service.create(player.getUniqueId(), player.getName(), key, d.amount, d.cents);
        Settings s = settings.get();
        switch (r.status()) {
            case OK -> {
                drafts.remove(player.getUniqueId());
                say(player, "order-created", "amount", d.amount, "item", key.label(), "price", Amounts.money(r.cents()));
                player.closeInventory();
            }
            case MAX_ORDERS -> say(player, "max-orders", "limit", s.maxActive());
            case BAD_PRICE -> say(player, "bad-price", "min", Amounts.money(s.minPriceCents()), "max", Amounts.money(s.maxPriceCents()));
            case BAD_AMOUNT -> say(player, "bad-amount", "max", s.maxAmount());
            case BLOCKED -> say(player, "blocked");
            case BROKE -> say(player, "not-enough-money", "total", Amounts.money(d.cents * d.amount + s.tax(d.cents * d.amount)));
            case UNSURE -> say(player, "payment-unsure");
            default -> say(player, "failed");
        }
    }

    // ---------------------------------------------------------------- item selector and enchantment picker

    private List<Material> catalogue() {
        if (catalogue == null) {
            List<Material> all = new ArrayList<>();
            for (Material m : Material.values()) {
                if (!m.isLegacy() && m.isItem() && !m.isAir() && !settings.get().blocked().contains(m)) {
                    all.add(m);
                }
            }
            all.sort(Comparator.comparing(Material::name));
            catalogue = all;
        }
        return catalogue;
    }

    private void openSelect(Player player, int page, String search) {
        String q = search.toLowerCase(Locale.ROOT);
        List<Material> list = new ArrayList<>();
        for (Material m : catalogue()) {
            if (q.isEmpty() || m.name().toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)) {
                list.add(m);
            }
        }
        int pages = Math.max(1, (list.size() + ITEMS - 1) / ITEMS);
        int p = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder(Page.SELECT, 0, p);
        selectSearch.put(player.getUniqueId(), q);
        Inventory inv = open(player, holder, 54, "select", "page", p + 1);
        for (int i = 0; i < ITEMS && p * ITEMS + i < list.size(); i++) {
            Material m = list.get(p * ITEMS + i);
            inv.setItem(i, icon(new ItemStack(m), null, List.of(gray("Click to select"))));
        }
        inv.setItem(45, button(Material.RED_STAINED_GLASS_PANE, plain("Back", NamedTextColor.RED)));
        inv.setItem(47, button(Material.OAK_SIGN, plain("SEARCH", NamedTextColor.YELLOW), white("Current: " + (q.isEmpty() ? "None" : q)),
                gray("Click to type a name, right-click to clear")));
        inv.setItem(48, p > 0 ? button(Material.ARROW, plain("Previous Page", NamedTextColor.YELLOW))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No Previous Page", NamedTextColor.GRAY)));
        inv.setItem(50, p + 1 < pages ? button(Material.ARROW, plain("Next Page", NamedTextColor.GREEN))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No Next Page", NamedTextColor.GRAY)));
    }

    private final Map<UUID, String> selectSearch = new HashMap<>();

    private List<Enchantment> applicable(Material material) {
        List<Enchantment> out = new ArrayList<>();
        ItemStack probe = new ItemStack(material);
        for (Enchantment e : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            if (material == Material.ENCHANTED_BOOK || e.canEnchantItem(probe)) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing(e -> e.getKey().getKey()));
        return out;
    }

    private void openEnchants(Player player) {
        Draft d = draft(player);
        if (d.material == null) {
            openNew(player);
            return;
        }
        Inventory inv = open(player, new Holder(Page.ENCHANT, 0, 0), 54, "enchant");
        List<Enchantment> list = applicable(d.material);
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (int i = 0; i < ITEMS && i < list.size(); i++) {
            Enchantment e = list.get(i);
            String name = e.getKey().getKey();
            int level = d.enchants.getOrDefault(name, 0);
            boolean conflict = level == 0 && d.enchants.keySet().stream().anyMatch(other -> {
                Enchantment o = registry.get(org.bukkit.NamespacedKey.minecraft(other));
                return o != null && e.conflictsWith(o);
            });
            ItemStack shown = new ItemStack(level > 0 ? Material.ENCHANTED_BOOK : conflict ? Material.BARRIER : Material.BOOK);
            inv.setItem(i, icon(shown, plain(ItemKey.pretty(name), level > 0 ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                    List.of(white("Level: " + level + " / " + e.getMaxLevel()),
                            gray(conflict ? "Conflicts with a selected enchantment" : "Left-click: level up, right-click: level down"))));
        }
        inv.setItem(45, button(Material.RED_STAINED_GLASS_PANE, plain("Clear all", NamedTextColor.RED)));
        inv.setItem(53, button(Material.LIME_STAINED_GLASS_PANE, plain("DONE", NamedTextColor.GREEN), white(d.enchants.size() + " selected")));
    }

    private void clickEnchant(Player player, int slot, boolean right) {
        Draft d = draft(player);
        if (d.material == null) {
            return;
        }
        List<Enchantment> list = applicable(d.material);
        if (slot >= list.size()) {
            return;
        }
        Enchantment e = list.get(slot);
        String name = e.getKey().getKey();
        int level = d.enchants.getOrDefault(name, 0);
        int next = right ? level - 1 : level + 1;
        if (next > e.getMaxLevel()) {
            return;
        }
        if (next <= 0) {
            d.enchants.remove(name);
        } else {
            if (level == 0) {
                var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
                for (String other : d.enchants.keySet()) {
                    Enchantment o = registry.get(org.bukkit.NamespacedKey.minecraft(other));
                    if (o != null && e.conflictsWith(o)) {
                        say(player, "enchant-conflict");
                        return;
                    }
                }
                int limit = settings.get().maxEnchants();
                if (limit > 0 && d.enchants.size() >= limit) {
                    say(player, "max-enchants", "limit", limit);
                    return;
                }
            }
            d.enchants.put(name, next);
        }
        openEnchants(player);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        if (holder.page != Page.DELIVER || event.getRawSlots().stream().anyMatch(s -> s >= ITEMS && s < 54)) {
            event.setCancelled(true);
        } else {
            refreshLater(event.getView().getTopInventory(), holder);
        }
    }

    private void refreshLater(Inventory inv, Holder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Order o = service.store().get(holder.order);
            if (o != null && inv.getViewers().size() > 0) {
                drawDeliverInfo(inv, o);
            }
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (holder.page == Page.DELIVER) {
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
                return;
            }
            if (event.getClickedInventory() == top && event.getSlot() >= ITEMS) {
                event.setCancelled(true);
                Order o = service.store().get(holder.order);
                if (o == null || !o.open()) {
                    say(player, "order-gone");
                    player.closeInventory();
                } else if (event.getSlot() == DELIVER_CONFIRM) {
                    confirmDeliver(player, o, top);
                } else if (event.getSlot() == DELIVER_CANCEL) {
                    player.closeInventory();
                }
                return;
            }
            refreshLater(top, holder);
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != top) {
            return;
        }
        int slot = event.getSlot();
        boolean right = event.isRightClick();
        switch (holder.page) {
            case MAIN -> clickMain(player, holder, slot, right);
            case MINE -> clickMine(player, holder, slot);
            case MANAGE -> clickManage(player, holder, slot);
            case NEW -> clickNew(player, slot, event.getCursor());
            case SELECT -> clickSelect(player, holder, slot, right);
            case ENCHANT -> {
                if (slot == 45) {
                    draft(player).enchants.clear();
                    openEnchants(player);
                } else if (slot == 53) {
                    openNew(player);
                } else if (slot < ITEMS) {
                    clickEnchant(player, slot, right);
                }
            }
            default -> { }
        }
    }

    private void clickMain(Player player, Holder holder, int slot, boolean right) {
        View v = view(player);
        if (slot < ITEMS) {
            Order o = orderAt(holder, slot);
            if (o == null) {
                return;
            }
            if (o.owner().equals(player.getUniqueId())) {
                openManage(player, o);
            } else {
                openDeliver(player, o);
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                v.sort = v.sort.next();
                openMain(player, 0);
            }
            case 46 -> {
                v.filter = v.filter.next();
                openMain(player, 0);
            }
            case 47 -> {
                if (right) {
                    v.search = "";
                    openMain(player, 0);
                } else {
                    ask(player, "type-search", text -> {
                        v.search = text.toLowerCase(Locale.ROOT);
                        openMain(player, 0);
                    });
                }
            }
            case 48 -> openMain(player, holder.number - 1);
            case 49 -> openMain(player, holder.number);
            case 50 -> openMain(player, holder.number + 1);
            case 52 -> openMine(player);
            case 53 -> openNew(player);
            default -> { }
        }
    }

    private Order orderAt(Holder holder, int slot) {
        long id = holder.ids[slot];
        return id == 0 ? null : service.store().get(id);
    }

    private void clickMine(Player player, Holder holder, int slot) {
        if (slot == 45) {
            openMain(player, 0);
        } else if (slot == 53) {
            openNew(player);
        } else if (slot < ITEMS) {
            Order shown = holder.ids[slot] == 0 ? null : service.store().get(holder.ids[slot]);
            if (shown != null && shown.owner().equals(player.getUniqueId())) {
                openManage(player, shown);
            }
        }
    }

    private void clickManage(Player player, Holder holder, int slot) {
        Order o = service.store().get(holder.order);
        if (o == null) {
            openMine(player);
            return;
        }
        switch (slot) {
            case 13 -> {
                OrderService.Result r = service.collect(player.getUniqueId(), o, n -> give(player, o.item(), n));
                if (r.status() == OrderService.Status.OK) {
                    say(player, "collected", "amount", r.cents(), "item", o.item().label());
                }
                openMine(player);
            }
            case 15 -> {
                OrderService.Result r = service.cancel(player.getUniqueId(), false, o);
                if (r.status() == OrderService.Status.OK) {
                    say(player, "order-cancelled", "price", Amounts.money(r.cents()));
                    if (r.pending()) {
                        say(player, "payout-later");
                    }
                } else if (r.status() == OrderService.Status.NOT_OPEN) {
                    say(player, "order-gone");
                } else {
                    say(player, "failed");
                }
                openMine(player);
            }
            case 22 -> openMine(player);
            default -> { }
        }
    }

    /** Gives items to a player; returns how many did not fit (those stay waiting in the order). */
    private int give(Player player, ItemKey key, int amount) {
        int left = amount;
        int stack = key.material().getMaxStackSize();
        int notFitting = 0;
        while (left > 0) {
            int part = Math.min(left, stack);
            for (ItemStack rest : player.getInventory().addItem(key.create(part)).values()) {
                notFitting += rest.getAmount();
            }
            left -= part;
        }
        return notFitting;
    }

    private void clickNew(Player player, int slot, ItemStack cursor) {
        Draft d = draft(player);
        switch (slot) {
            case 10 -> {
                if (cursor != null && !cursor.getType().isAir()) {
                    ItemKey picked = ItemKey.of(cursor);
                    if (settings.get().blocked().contains(picked.material())) {
                        say(player, "blocked");
                        return;
                    }
                    d.material = picked.material();
                    d.enchants.clear();
                    d.enchants.putAll(picked.enchants());
                    openNew(player);
                } else {
                    openSelect(player, 0, "");
                }
            }
            case 12 -> {
                if (d.material != null && canEnchant(d.material)) {
                    openEnchants(player);
                }
            }
            case 14 -> ask(player, "type-amount", text -> {
                int amount = Amounts.count(text);
                if (amount < 1 || amount > settings.get().maxAmount()) {
                    say(player, "bad-amount", "max", settings.get().maxAmount());
                } else {
                    d.amount = amount;
                }
                openNew(player);
            });
            case 16 -> ask(player, "type-price", text -> {
                long cents = Amounts.cents(text);
                Settings s = settings.get();
                if (cents < s.minPriceCents() || cents > s.maxPriceCents()) {
                    say(player, "bad-price", "min", Amounts.money(s.minPriceCents()), "max", Amounts.money(s.maxPriceCents()));
                } else {
                    d.cents = cents;
                }
                openNew(player);
            });
            case 22 -> confirmNew(player);
            case 18 -> {
                drafts.remove(player.getUniqueId());
                openMain(player, 0);
            }
            default -> { }
        }
    }

    private void clickSelect(Player player, Holder holder, int slot, boolean right) {
        String q = selectSearch.getOrDefault(player.getUniqueId(), "");
        if (slot < ITEMS) {
            ItemStack clicked = player.getOpenInventory().getTopInventory().getItem(slot);
            if (clicked != null && clicked.getType() != Material.BLACK_STAINED_GLASS_PANE) {
                Draft d = draft(player);
                if (d.material != clicked.getType()) {
                    d.enchants.clear();
                }
                d.material = clicked.getType();
                openNew(player);
            }
            return;
        }
        switch (slot) {
            case 45 -> openNew(player);
            case 47 -> {
                if (right) {
                    openSelect(player, 0, "");
                } else {
                    ask(player, "type-search", text -> openSelect(player, 0, text));
                }
            }
            case 48 -> openSelect(player, holder.number - 1, q);
            case 50 -> openSelect(player, holder.number + 1, q);
            default -> { }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.page != Page.DELIVER
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        for (int i = 0; i < ITEMS; i++) {
            ItemStack s = event.getInventory().getItem(i);
            if (s != null && !s.getType().isAir()) {
                event.getInventory().setItem(i, null);
                for (ItemStack rest : player.getInventory().addItem(s).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                service.payout(player.getUniqueId());
                int waiting = 0;
                for (Order o : service.store().of(player.getUniqueId())) {
                    waiting += o.collectable();
                }
                if (waiting > 0) {
                    say(player, "items-waiting", "amount", waiting);
                }
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
        selectSearch.remove(event.getPlayer().getUniqueId());
    }

    /** Closes every open order menu so items in delivery screens go back to their owners. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                player.closeInventory();
            }
        }
    }
}
