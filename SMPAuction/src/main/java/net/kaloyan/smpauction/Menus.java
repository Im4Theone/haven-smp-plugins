package net.kaloyan.smpauction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** All the auction house menus: browse, confirm, bid, your listings, collection box, history and the sell screen. */
public final class Menus implements Listener {

    private static final TextColor GOLD = TextColor.color(0xFBBF24);
    private static final TextColor GREEN = TextColor.color(0x4FF750);
    private static final int ITEMS = 45;

    private enum Page { BROWSE, CONFIRM, BID, MINE, COLLECT, HISTORY, SELL }

    private enum Sort {
        NEWEST("Newest first"), ENDING("Ending soon"), CHEAPEST("Lowest price"), PRICIEST("Highest price");

        final String label;

        Sort(String label) {
            this.label = label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Filter {
        ALL("All"), BLOCKS("Blocks"), TOOLS("Tools"), COMBAT("Combat"), FOOD("Food"), BOOKS("Books"), AUCTIONS("Auctions only"), MISC("Everything else");

        final String label;

        Filter(String label) {
            this.label = label;
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        boolean accepts(Listing l) {
            Material m = Material.getMaterial(l.goods().material());
            if (m == null) {
                return this == ALL;
            }
            String n = m.name();
            boolean tool = n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("SHEARS")
                    || n.equals("FISHING_ROD");
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
                case AUCTIONS -> l.type() == Listing.Type.AUCTION;
                case MISC -> !tool && !combat && !books && !m.isBlock() && !m.isEdible();
            };
        }
    }

    private static final class View {
        Sort sort = Sort.NEWEST;
        Filter filter = Filter.ALL;
        String search = "";
    }

    private static final class Draft {
        long cents;
        int hoursIndex = -1;
        Listing.Type type = Listing.Type.BIN;
    }

    private static final class Holder implements InventoryHolder {
        final Page page;
        final long ref;
        final int number;
        /** The listing (or collected item) shown in each slot when the menu was drawn, so a click always means what was on screen. */
        final long[] ids = new long[ITEMS];
        Inventory inventory;

        Holder(Page page, long ref, int number) {
            this.page = page;
            this.ref = ref;
            this.number = number;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final AuctionService service;
    private final Supplier<Settings> settings;
    private final Prompts prompts;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Draft> drafts = new HashMap<>();

    /**
     * @param plugin   the owning plugin
     * @param service  the rules
     * @param settings the current settings
     * @param prompts  chat prompts
     */
    public Menus(Plugin plugin, AuctionService service, Supplier<Settings> settings, Prompts prompts) {
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

    /**
     * Sends a configured message.
     *
     * @param to           who gets it
     * @param key          the message key
     * @param placeholders name, value pairs
     */
    public void say(Player to, String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        to.sendMessage(mini.deserialize(settings.get().message(key), values.build()));
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

    private Inventory open(Player player, Holder holder, int size, String titleKey, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        Inventory inv = Bukkit.createInventory(holder, size, mini.deserialize(settings.get().title(titleKey), values.build()));
        holder.inventory = inv;
        ItemStack pane = button(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < size; i++) {
            inv.setItem(i, pane);
        }
        player.openInventory(inv);
        return inv;
    }

    private View view(Player player) {
        return views.computeIfAbsent(player.getUniqueId(), id -> new View());
    }

    /** The saved item with extra lines about the listing added to its own lore. */
    private ItemStack show(Listing l, List<Component> extra) {
        ItemStack item;
        try {
            item = Items.unpack(l.goods());
        } catch (RuntimeException e) {
            item = new ItemStack(Material.BARRIER);
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.text(" "));
        lore.addAll(extra);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> details(Listing l, String action) {
        List<Component> lines = new ArrayList<>();
        lines.add(white("Seller: ").append(plain(l.sellerName(), GOLD)));
        if (l.type() == Listing.Type.BIN) {
            lines.add(white("Price: ").append(plain(Amounts.money(l.price()), GREEN)));
        } else if (l.bids() == 0) {
            lines.add(white("Starting bid: ").append(plain(Amounts.money(l.price()), GREEN)));
        } else {
            lines.add(white("Highest bid: ").append(plain(Amounts.money(l.bid()), GREEN)).append(gray(" (" + l.bidderName() + ", " + l.bids() + " bids)")));
        }
        long left = l.expires() - System.currentTimeMillis();
        lines.add(gray("Ends in " + Amounts.duration(left) + (l.type() == Listing.Type.AUCTION ? " - auction" : "")));
        if (action != null) {
            lines.add(plain(action, NamedTextColor.YELLOW));
        }
        return lines;
    }

    // ---------------------------------------------------------------- browse

    private List<Listing> visible(View v) {
        List<Listing> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Listing l : service.store().listings()) {
            if (l.expires() > now && v.filter.accepts(l) && (v.search.isEmpty()
                    || l.goods().label().toLowerCase(Locale.ROOT).contains(v.search) || l.sellerName().toLowerCase(Locale.ROOT).contains(v.search)
                    || l.goods().material().toLowerCase(Locale.ROOT).replace('_', ' ').contains(v.search))) {
                list.add(l);
            }
        }
        Comparator<Listing> by = switch (v.sort) {
            case ENDING -> Comparator.<Listing>comparingLong(Listing::expires);
            case CHEAPEST -> Comparator.<Listing>comparingLong(Listing::current);
            case PRICIEST -> Comparator.<Listing>comparingLong(Listing::current).reversed();
            default -> Comparator.<Listing>comparingLong(Listing::created).reversed();
        };
        list.sort(by);
        return list;
    }

    /**
     * Opens the market.
     *
     * @param player who sees it
     * @param search a search to start with, or empty
     */
    public void openBrowse(Player player, String search) {
        view(player).search = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        openBrowse(player, 0);
    }

    private void openBrowse(Player player, int page) {
        View v = view(player);
        List<Listing> list = visible(v);
        int pages = Math.max(1, (list.size() + ITEMS - 1) / ITEMS);
        int p = Math.max(0, Math.min(page, pages - 1));
        Holder holder = new Holder(Page.BROWSE, 0, p);
        Inventory inv = open(player, holder, 54, "browse", "page", p + 1);
        for (int i = 0; i < ITEMS && p * ITEMS + i < list.size(); i++) {
            Listing l = list.get(p * ITEMS + i);
            holder.ids[i] = l.id();
            boolean own = l.seller().equals(player.getUniqueId());
            inv.setItem(i, show(l, details(l, own ? "Your listing - click to manage"
                    : l.type() == Listing.Type.BIN ? "Click to buy" : "Click to bid")));
        }
        inv.setItem(45, button(Material.HOPPER, plain("SORT", NamedTextColor.LIGHT_PURPLE), white("Current: " + v.sort.label), gray("Click to change")));
        inv.setItem(46, button(Material.BREWING_STAND, plain("FILTER", NamedTextColor.LIGHT_PURPLE), white("Current: " + v.filter.label), gray("Click to change")));
        inv.setItem(47, button(Material.OAK_SIGN, plain("SEARCH", NamedTextColor.YELLOW), white("Current: " + (v.search.isEmpty() ? "None" : v.search)),
                gray("Click to type, right-click to clear")));
        inv.setItem(48, p > 0 ? button(Material.ARROW, plain("Previous Page", NamedTextColor.YELLOW))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No Previous Page", NamedTextColor.GRAY)));
        inv.setItem(49, button(Material.LIME_DYE, plain("Refresh", NamedTextColor.GREEN), gray(list.size() + " listings")));
        inv.setItem(50, p + 1 < pages ? button(Material.ARROW, plain("Next Page", NamedTextColor.GREEN))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("No Next Page", NamedTextColor.GRAY)));
        inv.setItem(51, button(Material.BOOK, plain("HISTORY", NamedTextColor.AQUA), white("Your sales and purchases")));
        int waiting = service.store().collectedOf(player.getUniqueId()).size();
        inv.setItem(52, button(Material.CHEST, plain("COLLECTION BOX", NamedTextColor.GOLD), white(waiting + " item(s) waiting")));
        inv.setItem(53, button(Material.GOLD_INGOT, plain("YOUR LISTINGS", NamedTextColor.GREEN), white("Sell and manage your items")));
    }

    // ---------------------------------------------------------------- confirm and bid

    private void openConfirm(Player player, Listing l) {
        Inventory inv = open(player, new Holder(Page.CONFIRM, l.id(), 0), 27, "confirm");
        inv.setItem(13, show(l, details(l, null)));
        inv.setItem(11, button(Material.RED_STAINED_GLASS_PANE, plain("CANCEL", NamedTextColor.RED)));
        inv.setItem(15, button(Material.LIME_STAINED_GLASS_PANE, plain("BUY for " + Amounts.money(l.price()), NamedTextColor.GREEN),
                gray("The item goes to your collection box")));
    }

    private void openBid(Player player, Listing l) {
        long minimum = service.minimumBid(l);
        Inventory inv = open(player, new Holder(Page.BID, l.id(), 0), 27, "bid");
        inv.setItem(13, show(l, details(l, null)));
        boolean mine = player.getUniqueId().equals(l.bidder());
        inv.setItem(11, mine ? button(Material.GRAY_STAINED_GLASS_PANE, plain("You have the highest bid", NamedTextColor.GRAY))
                : button(Material.LIME_STAINED_GLASS_PANE, plain("BID " + Amounts.money(minimum), NamedTextColor.GREEN), gray("The minimum bid")));
        inv.setItem(15, button(Material.GOLD_NUGGET, plain("OWN BID", NamedTextColor.AQUA), gray("Click and type your bid in chat")));
        inv.setItem(22, button(Material.RED_STAINED_GLASS_PANE, plain("Back", NamedTextColor.RED)));
    }

    private void placeBid(Player player, Listing l, long cents) {
        AuctionService.Result r = service.bid(player.getUniqueId(), player.getName(), l, cents);
        switch (r.status()) {
            case OK -> {
                say(player, "bid-placed", "price", Amounts.money(cents), "item", l.goods().label());
                if (r.pending()) {
                    say(player, "payout-later");
                }
                Player seller = Bukkit.getPlayer(l.seller());
                if (seller != null) {
                    say(seller, "bid-on-yours", "bidder", player.getName(), "price", Amounts.money(cents), "item", l.goods().label());
                }
                Player outbid = r.other() == null ? null : Bukkit.getPlayer(r.other());
                if (outbid != null) {
                    say(outbid, "outbid", "item", l.goods().label(), "price", Amounts.money(cents));
                }
                openBid(player, l);
            }
            case TOO_LOW -> say(player, "bid-too-low", "min", Amounts.money(r.cents()));
            default -> report(player, r.status());
        }
    }

    private void report(Player player, AuctionService.Status status) {
        Settings s = settings.get();
        switch (status) {
            case GONE -> say(player, "gone");
            case OWN -> say(player, "own");
            case WRONG_TYPE -> say(player, "wrong-type");
            case BROKE -> say(player, "not-enough-money");
            case ALREADY_HIGHEST -> say(player, "already-highest");
            case HAS_BIDS -> say(player, "has-bids");
            case NOT_OWNER -> say(player, "not-owner");
            case BAD_PRICE -> say(player, "bad-price", "min", Amounts.money(s.minCents()), "max", Amounts.money(s.maxCents()));
            case BAD_DURATION -> say(player, "bad-duration");
            case LIMIT -> say(player, "limit", "limit", s.limit(player::hasPermission));
            case BLOCKED -> say(player, "blocked");
            case BIDDING_OFF -> say(player, "bidding-off");
            case UNSURE -> say(player, "payment-unsure");
            default -> say(player, "failed");
        }
    }

    // ---------------------------------------------------------------- your listings, collection box, history

    /**
     * Opens the player's own listings.
     *
     * @param player who sees them
     */
    public void openMine(Player player) {
        List<Listing> mine = service.store().listingsOf(player.getUniqueId());
        mine.sort(Comparator.<Listing>comparingLong(Listing::created).reversed());
        Holder holder = new Holder(Page.MINE, 0, 0);
        Inventory inv = open(player, holder, 54, "mine");
        for (int i = 0; i < ITEMS && i < mine.size(); i++) {
            holder.ids[i] = mine.get(i).id();
            inv.setItem(i, show(mine.get(i), details(mine.get(i), mine.get(i).bids() > 0 ? "Has bids - it can't be cancelled" : "Shift-click to cancel")));
        }
        inv.setItem(45, button(Material.RED_STAINED_GLASS_PANE, plain("Back", NamedTextColor.RED)));
        inv.setItem(49, button(Material.GRAY_STAINED_GLASS_PANE, plain(mine.size() + " live listing(s)", NamedTextColor.GRAY),
                gray("Limit: " + limitText(player))));
        inv.setItem(53, button(Material.WRITABLE_BOOK, plain("SELL AN ITEM", NamedTextColor.GREEN), white("Sell the item in your main hand")));
    }

    private String limitText(Player player) {
        int limit = settings.get().limit(player::hasPermission);
        return limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit);
    }

    /**
     * Opens the player's collection box.
     *
     * @param player who sees it
     */
    public void openCollect(Player player) {
        List<AuctionStore.Collected> box = service.store().collectedOf(player.getUniqueId());
        Inventory inv = open(player, new Holder(Page.COLLECT, 0, 0), 54, "collect");
        for (int i = 0; i < ITEMS && i < box.size(); i++) {
            AuctionStore.Collected c = box.get(i);
            ItemStack shown;
            try {
                shown = Items.unpack(c.goods());
            } catch (RuntimeException e) {
                shown = new ItemStack(Material.BARRIER);
            }
            ItemMeta meta = shown.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.text(" "));
            lore.add(gray("From: " + c.reason()));
            lore.add(plain("Click to collect", NamedTextColor.YELLOW));
            meta.lore(lore);
            shown.setItemMeta(meta);
            inv.setItem(i, shown);
        }
        inv.setItem(45, button(Material.RED_STAINED_GLASS_PANE, plain("Back", NamedTextColor.RED)));
        inv.setItem(49, button(Material.HOPPER, plain("COLLECT ALL", NamedTextColor.GREEN), white(box.size() + " item(s) waiting")));
    }

    private int collectOne(Player player, long id) {
        AuctionStore.Collected c = service.claim(player.getUniqueId(), id);
        if (c == null) {
            return 0;
        }
        ItemStack item = Items.unpack(c.goods());
        var rest = player.getInventory().addItem(item);
        if (rest.isEmpty()) {
            say(player, "collected", "item", c.goods().label());
            return 1;
        }
        int left = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (left == item.getAmount()) {
            service.restore(c);
            say(player, "collect-nothing-fit");
            return -1;
        }
        // part fitted: keep the rest in the box
        ItemStack remainder = rest.values().iterator().next();
        Listing.Goods smaller = Items.pack(remainder);
        service.restore(new AuctionStore.Collected(c.id(), c.owner(), smaller, c.reason()));
        say(player, "collect-nothing-fit");
        return -1;
    }

    /**
     * Opens the player's finished sales and purchases.
     *
     * @param player who sees them
     */
    public void openHistory(Player player) {
        List<AuctionStore.Sale> mine = new ArrayList<>();
        for (AuctionStore.Sale s : service.store().history()) {
            if (s.seller().equals(player.getUniqueId()) || s.buyer().equals(player.getUniqueId())) {
                mine.add(s);
            }
        }
        java.util.Collections.reverse(mine);
        Inventory inv = open(player, new Holder(Page.HISTORY, 0, 0), 54, "history");
        for (int i = 0; i < ITEMS && i < mine.size(); i++) {
            AuctionStore.Sale s = mine.get(i);
            boolean sold = s.seller().equals(player.getUniqueId());
            Material m = Material.getMaterial(s.goods().material());
            inv.setItem(i, button(m == null ? Material.PAPER : m, white(s.goods().label()),
                    plain(sold ? "Sold to " + s.buyerName() : "Bought from " + s.sellerName(), sold ? GREEN : NamedTextColor.AQUA),
                    white("Price: " + Amounts.money(s.cents())), gray(Amounts.duration(System.currentTimeMillis() - s.time()) + " ago"
                            + (s.type() == Listing.Type.AUCTION ? " - auction" : ""))));
        }
        inv.setItem(45, button(Material.RED_STAINED_GLASS_PANE, plain("Back", NamedTextColor.RED)));
    }

    // ---------------------------------------------------------------- selling

    /**
     * Opens the sell screen for the item in the player's main hand.
     *
     * @param player who sells
     */
    public void openSell(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            say(player, "nothing-to-sell");
            return;
        }
        Settings s = settings.get();
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
        if (d.hoursIndex < 0 || d.hoursIndex >= s.durations().size()) {
            d.hoursIndex = Math.max(0, s.durations().indexOf(s.defaultHours()));
        }
        Inventory inv = open(player, new Holder(Page.SELL, 0, 0), 27, "sell");
        ItemStack shown = hand.clone();
        inv.setItem(13, shown);
        inv.setItem(11, button(Material.GOLD_NUGGET, plain("PRICE", NamedTextColor.AQUA),
                white(d.cents > 0 ? Amounts.money(d.cents) : "Not set"), gray("Click and type it in chat")));
        inv.setItem(15, button(Material.CLOCK, plain("LENGTH", NamedTextColor.YELLOW), white(s.durations().get(d.hoursIndex) + " hours"),
                gray("Click to change")));
        if (s.bidding()) {
            inv.setItem(20, button(d.type == Listing.Type.BIN ? Material.EMERALD : Material.ANVIL,
                    plain(d.type == Listing.Type.BIN ? "BUY IT NOW" : "AUCTION", NamedTextColor.GREEN),
                    white(d.type == Listing.Type.BIN ? "Fixed price" : "Highest bid wins; the price is the starting bid"), gray("Click to change")));
        }
        long fee = d.cents > 0 ? s.fee(d.cents) : 0;
        inv.setItem(22, d.cents > 0
                ? button(Material.LIME_STAINED_GLASS_PANE, plain("LIST IT", NamedTextColor.GREEN),
                        white("Sales tax: " + s.taxPercent().stripTrailingZeros().toPlainString() + "%"),
                        white("Listing fee: " + Amounts.money(fee)))
                : button(Material.GRAY_STAINED_GLASS_PANE, plain("Set a price first", NamedTextColor.GRAY)));
        inv.setItem(18, button(Material.RED_STAINED_GLASS_PANE, plain("CANCEL", NamedTextColor.RED)));
    }

    private void confirmSell(Player player) {
        Settings s = settings.get();
        Draft d = drafts.get(player.getUniqueId());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (d == null || d.cents < 1 || hand.getType().isAir()) {
            say(player, "nothing-to-sell");
            return;
        }
        if (s.blockedModes().contains(player.getGameMode().name()) || s.blockedWorlds().contains(player.getWorld().getName())) {
            say(player, "blocked");
            return;
        }
        ItemStack taken = hand.clone();
        int hours = s.durations().get(d.hoursIndex);
        // The item leaves the hand first: a crash can lose it but never copy it. It comes back if the listing fails.
        player.getInventory().setItemInMainHand(null);
        AuctionService.Result r = service.list(player.getUniqueId(), player.getName(), Items.pack(taken), d.type, d.cents, hours,
                s.limit(player::hasPermission), taken.getType());
        if (r.status() != AuctionService.Status.OK) {
            player.getInventory().setItemInMainHand(taken);
            report(player, r.status());
            return;
        }
        drafts.remove(player.getUniqueId());
        say(player, "listed", "item", r.listing().goods().label(), "price", Amounts.money(d.cents),
                "type", d.type == Listing.Type.BIN ? "Buy It Now" : "auction", "hours", hours);
        if (r.cents() > 0) {
            say(player, "listed-fee", "fee", Amounts.money(r.cents()));
        }
        player.closeInventory();
    }

    /**
     * Lists the item in the player's hand at once with the default length (for {@code /ah sell <price>}).
     *
     * @param player who sells
     * @param cents  the price
     * @param type   BIN or auction
     */
    public void quickSell(Player player, long cents, Listing.Type type) {
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
        d.cents = cents;
        d.type = type;
        Settings s = settings.get();
        d.hoursIndex = Math.max(0, s.durations().indexOf(s.defaultHours()));
        confirmSell(player);
    }

    private void ask(Player player, String promptKey, java.util.function.Consumer<String> apply, Runnable back, Object... placeholders) {
        player.closeInventory();
        say(player, promptKey, placeholders);
        say(player, "prompt-cancel");
        prompts.ask(player, apply, back);
    }

    // ---------------------------------------------------------------- events

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
        int slot = event.getSlot();
        switch (holder.page) {
            case BROWSE -> clickBrowse(player, holder, slot, event.isRightClick());
            case CONFIRM -> {
                Listing l = service.store().listing(holder.ref);
                if (slot == 11 || l == null) {
                    openBrowse(player, 0);
                } else if (slot == 15) {
                    AuctionService.Result r = service.buy(player.getUniqueId(), player.getName(), l);
                    if (r.status() == AuctionService.Status.OK) {
                        say(player, "bought", "item", l.goods().label(), "price", Amounts.money(l.price()));
                        if (r.pending()) {
                            say(player, "payout-later");
                        }
                        Player seller = Bukkit.getPlayer(l.seller());
                        if (seller != null) {
                            say(seller, "sold", "buyer", player.getName(), "item", l.goods().label(), "price", Amounts.money(l.price()));
                        }
                        player.closeInventory();
                    } else {
                        report(player, r.status());
                        openBrowse(player, 0);
                    }
                }
            }
            case BID -> {
                Listing l = service.store().listing(holder.ref);
                if (l == null || slot == 22) {
                    openBrowse(player, 0);
                } else if (slot == 11) {
                    placeBid(player, l, service.minimumBid(l));
                } else if (slot == 15) {
                    ask(player, "type-bid", text -> {
                        long cents = Amounts.cents(text);
                        Listing now = service.store().listing(l.id());
                        if (cents < 1 || now == null) {
                            say(player, cents < 1 ? "invalid-price" : "gone");
                            openBrowse(player, 0);
                        } else {
                            placeBid(player, now, cents);
                        }
                    }, () -> openBrowse(player, 0), "min", Amounts.money(service.minimumBid(l)));
                }
            }
            case MINE -> clickMine(player, holder, slot, event.isShiftClick());
            case COLLECT -> clickCollect(player, slot);
            case HISTORY -> {
                if (slot == 45) {
                    openBrowse(player, 0);
                }
            }
            case SELL -> clickSell(player, slot);
            default -> { }
        }
    }

    private void clickBrowse(Player player, Holder holder, int slot, boolean right) {
        View v = view(player);
        if (slot < ITEMS) {
            Listing l = holder.ids[slot] == 0 ? null : service.store().listing(holder.ids[slot]);
            if (l == null) {
                return;
            }
            if (l.seller().equals(player.getUniqueId())) {
                openMine(player);
            } else if (l.type() == Listing.Type.BIN) {
                openConfirm(player, l);
            } else {
                openBid(player, l);
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                v.sort = v.sort.next();
                openBrowse(player, 0);
            }
            case 46 -> {
                v.filter = v.filter.next();
                openBrowse(player, 0);
            }
            case 47 -> {
                if (right) {
                    v.search = "";
                    openBrowse(player, 0);
                } else {
                    ask(player, "type-search", text -> {
                        v.search = text.toLowerCase(Locale.ROOT);
                        openBrowse(player, 0);
                    }, () -> openBrowse(player, 0));
                }
            }
            case 48 -> openBrowse(player, holder.number - 1);
            case 49 -> openBrowse(player, holder.number);
            case 50 -> openBrowse(player, holder.number + 1);
            case 51 -> openHistory(player);
            case 52 -> openCollect(player);
            case 53 -> openMine(player);
            default -> { }
        }
    }

    private void clickMine(Player player, Holder holder, int slot, boolean shift) {
        if (slot == 45) {
            openBrowse(player, 0);
        } else if (slot == 53) {
            openSell(player);
        } else if (slot < ITEMS && shift) {
            Listing shown = holder.ids[slot] == 0 ? null : service.store().listing(holder.ids[slot]);
            if (shown != null) {
                AuctionService.Result r = service.cancel(player.getUniqueId(), false, shown);
                if (r.status() == AuctionService.Status.OK) {
                    say(player, "cancelled");
                } else {
                    report(player, r.status());
                }
                openMine(player);
            }
        }
    }

    private void clickCollect(Player player, int slot) {
        if (slot == 45) {
            openBrowse(player, 0);
        } else if (slot == 49) {
            for (AuctionStore.Collected c : service.store().collectedOf(player.getUniqueId())) {
                if (collectOne(player, c.id()) < 0) {
                    break;
                }
            }
            openCollect(player);
        } else if (slot < ITEMS) {
            List<AuctionStore.Collected> box = service.store().collectedOf(player.getUniqueId());
            if (slot < box.size()) {
                collectOne(player, box.get(slot).id());
                openCollect(player);
            }
        }
    }

    private void clickSell(Player player, int slot) {
        Draft d = drafts.computeIfAbsent(player.getUniqueId(), id -> new Draft());
        Settings s = settings.get();
        switch (slot) {
            case 11 -> ask(player, "type-price", text -> {
                long cents = Amounts.cents(text);
                if (cents < s.minCents() || cents > s.maxCents()) {
                    say(player, "bad-price", "min", Amounts.money(s.minCents()), "max", Amounts.money(s.maxCents()));
                } else {
                    d.cents = cents;
                }
                openSell(player);
            }, () -> openSell(player));
            case 15 -> {
                d.hoursIndex = (d.hoursIndex + 1) % s.durations().size();
                openSell(player);
            }
            case 20 -> {
                if (s.bidding()) {
                    d.type = d.type == Listing.Type.BIN ? Listing.Type.AUCTION : Listing.Type.BIN;
                    openSell(player);
                }
            }
            case 22 -> confirmSell(player);
            case 18 -> {
                drafts.remove(player.getUniqueId());
                openMine(player);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            service.payout(player.getUniqueId());
            AuctionStore.Unseen unseen = service.takeUnseen(player.getUniqueId());
            if (unseen != null) {
                say(player, "sold-while-away", "sales", unseen.sales(), "price", Amounts.money(unseen.cents()));
            }
            int waiting = service.store().collectedOf(player.getUniqueId()).size();
            if (waiting > 0) {
                say(player, "box-waiting", "count", waiting);
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
        drafts.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Tells the people involved that listings ended.
     *
     * @param ended what ended
     */
    public void announce(List<AuctionService.Ended> ended) {
        for (AuctionService.Ended e : ended) {
            Listing l = e.listing();
            Player seller = Bukkit.getPlayer(l.seller());
            if (e.sold()) {
                Player winner = Bukkit.getPlayer(l.bidder());
                if (winner != null) {
                    say(winner, "won", "item", l.goods().label(), "price", Amounts.money(e.cents()));
                }
                if (seller != null) {
                    say(seller, "auction-sold", "item", l.goods().label(), "buyer", l.bidderName(), "price", Amounts.money(e.cents()));
                }
            } else if (seller != null) {
                say(seller, "expired", "item", l.goods().label());
            }
        }
    }
}
