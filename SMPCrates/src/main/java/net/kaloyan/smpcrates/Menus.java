package net.kaloyan.smpcrates;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import net.kaloyan.smpcrates.CrateService.OpenResult;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** The crate menu, the reward preview and the opening animation. Nothing can be taken out of these inventories. */
public final class Menus implements Listener {

    private enum Kind { MAIN, PREVIEW, OPEN }

    private static final class Holder implements InventoryHolder {
        final Kind kind;
        final String crate;
        Inventory inventory;
        BukkitTask task;

        Holder(Kind kind, String crate) {
            this.kind = kind;
            this.crate = crate;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int[] CRATE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int INFO_SLOT = 22;
    private static final int BACK_SLOT = 49;

    private final Plugin plugin;
    private final CrateService service;
    private final CrateStore store;
    private final Messages messages;
    private final Items items;
    private final Supplier<CratesSettings> settings;
    private final Random random = new Random();

    /**
     * @param plugin   the owning plugin
     * @param service  the crate rules
     * @param store    balances
     * @param messages the texts
     * @param items    builds display items
     * @param settings the current settings
     */
    public Menus(Plugin plugin, CrateService service, CrateStore store, Messages messages, Items items, Supplier<CratesSettings> settings) {
        this.plugin = plugin;
        this.service = service;
        this.store = store;
        this.messages = messages;
        this.items = items;
        this.settings = settings;
    }

    // ---------------------------------------------------------------- main menu

    private ItemStack filler() {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.space());
        pane.setItemMeta(meta);
        return pane;
    }

    private List<CrateType> crateList() {
        return new ArrayList<>(settings.get().crates().values());
    }

    private void fillMain(Player player, Inventory inv) {
        UUID id = player.getUniqueId();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler());
        }
        List<CrateType> crates = crateList();
        for (int i = 0; i < crates.size() && i < CRATE_SLOTS.length; i++) {
            CrateType crate = crates.get(i);
            int have = store.balance(id, crate.id());
            ItemStack stack = new ItemStack(Material.getMaterial(crate.material()));
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(Items.plain(messages.parse(crate.display())));
            meta.lore(List.of(Items.plain(messages.render("menu-have", "count", Integer.toString(have))),
                    Items.plain(messages.render(have > 0 ? "menu-open" : "menu-none")), Items.plain(messages.render("menu-preview"))));
            if (have > 0) {
                meta.setEnchantmentGlintOverride(true);
            }
            stack.setItemMeta(meta);
            inv.setItem(CRATE_SLOTS[i], stack);
        }
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();
        if (settings.get().hourlyEnabled()) {
            meta.displayName(Items.plain(messages.render("menu-timer", "time", HourlyClock.format(service.hourly().untilNext(System.currentTimeMillis())))));
            meta.lore(List.of(Items.plain(messages.render("menu-timer-lore"))));
        } else {
            meta.displayName(Items.plain(messages.render("menu-no-timer")));
        }
        clock.setItemMeta(meta);
        inv.setItem(INFO_SLOT, clock);
    }

    /**
     * Opens the main menu; it refreshes every second while open (crate counts and the timer).
     *
     * @param player who looks at it
     */
    public void openMain(Player player) {
        Holder holder = new Holder(Kind.MAIN, null);
        Inventory inv = Bukkit.createInventory(holder, 27, messages.render("menu-title"));
        holder.inventory = inv;
        fillMain(player, inv);
        player.openInventory(inv);
        holder.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) {
                holder.task.cancel();
                return;
            }
            fillMain(player, inv);
        }, 20L, 20L);
    }

    // ---------------------------------------------------------------- preview

    /**
     * Shows what a crate can give and how likely each reward is.
     *
     * @param player who looks at it
     * @param crate  the crate type
     */
    public void openPreview(Player player, CrateType crate) {
        Holder holder = new Holder(Kind.PREVIEW, crate.id());
        Inventory inv = Bukkit.createInventory(holder, 54, messages.render("preview-title", "crate", messages.parse(crate.display())));
        holder.inventory = inv;
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler());
        }
        List<Reward> sorted = new ArrayList<>(crate.rewards());
        sorted.sort((a, b) -> Integer.compare(a.weight(), b.weight()));
        for (int i = 0; i < sorted.size() && i < 45; i++) {
            Reward reward = sorted.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(Items.plain(messages.render("preview-chance", "chance", String.format("%.2f", crate.chancePercent(reward)))));
            if (reward.broadcast()) {
                lore.add(Items.plain(messages.render("preview-rare")));
            }
            inv.setItem(i, items.displayOf(reward, lore));
        }
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta meta = back.getItemMeta();
        meta.displayName(Items.plain(messages.render("menu-back")));
        back.setItemMeta(meta);
        inv.setItem(BACK_SLOT, back);
        player.openInventory(inv);
    }

    // ---------------------------------------------------------------- opening

    /**
     * Opens a crate for a player: takes it, hands over the reward, tells the player and shows the animation.
     *
     * @param player who opens
     * @param crate  the crate type
     */
    public void open(Player player, CrateType crate) {
        OpenResult result = service.open(player.getUniqueId(), crate.id());
        Component name = messages.parse(crate.display());
        switch (result.outcome()) {
            case OPENED -> {
                Component rewardName = items.rewardName(result.reward());
                messages.send(player, "opened", "crate", name, "reward", rewardName);
                if (result.reward().broadcast()) {
                    Bukkit.broadcast(messages.render("broadcast", "player", player.getName(), "crate", name, "reward", rewardName));
                }
                animate(player, crate, result.reward());
            }
            case NONE_LEFT -> messages.send(player, "none-left", "crate", name);
            case CANNOT_DELIVER -> messages.send(player, "cannot-deliver", "crate", name);
            case DELIVERY_FAILED -> messages.send(player, "delivery-failed", "crate", name);
            case SAVE_FAILED -> {
                messages.send(player, "save-failed");
                plugin.getLogger().severe("Crate data could not be saved while " + player.getName() + " opened a " + crate.id()
                        + " crate. Check disk space and permissions of plugins/SMP-Crates; the reward may need to be handled by hand: "
                        + (result.reward() == null ? "none was chosen" : result.reward()));
            }
            default -> messages.send(player, "unknown-crate");
        }
    }

    private void animate(Player player, CrateType crate, Reward winner) {
        Holder holder = new Holder(Kind.OPEN, crate.id());
        Inventory inv = Bukkit.createInventory(holder, 27, messages.render("opening-title", "crate", messages.parse(crate.display())));
        holder.inventory = inv;
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler());
        }
        ItemStack pointer = new ItemStack(Material.HOPPER);
        ItemMeta pm = pointer.getItemMeta();
        pm.displayName(Component.space());
        pointer.setItemMeta(pm);
        inv.setItem(4, pointer);
        inv.setItem(22, pointer);
        int steps = 32;
        List<ItemStack> strip = new ArrayList<>();
        for (int i = 0; i < steps + 9; i++) {
            strip.add(items.displayOf(crate.pick(random), List.of()));
        }
        strip.set(steps + 4, items.displayOf(winner, List.of(Items.plain(messages.render("won")))));
        player.openInventory(inv);
        int[] state = {0, 0};      // current step, ticks until the next step
        holder.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inv) {
                holder.task.cancel();
                return;
            }
            if (state[1] > 0) {
                state[1]--;
                return;
            }
            for (int i = 0; i < 9; i++) {
                inv.setItem(9 + i, strip.get(state[0] + i));
            }
            if (state[0] >= steps) {
                holder.task.cancel();
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                return;
            }
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f);
            state[0]++;
            state[1] = state[0] < 20 ? 1 : state[0] < 28 ? 3 : 6;
        }, 0L, 1L);
    }

    // ---------------------------------------------------------------- events

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
        switch (holder.kind) {
            case MAIN -> {
                List<CrateType> crates = crateList();
                for (int i = 0; i < CRATE_SLOTS.length && i < crates.size(); i++) {
                    if (CRATE_SLOTS[i] == slot) {
                        CrateType crate = crates.get(i);
                        if (event.getClick() == ClickType.RIGHT || event.getClick() == ClickType.SHIFT_RIGHT) {
                            openPreview(player, crate);
                        } else {
                            open(player, crate);
                        }
                    }
                }
            }
            case PREVIEW -> {
                if (slot == BACK_SLOT) {
                    openMain(player);
                }
            }
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder && holder.task != null) {
            holder.task.cancel();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder holder && holder.task != null) {
            holder.task.cancel();
        }
    }
}
