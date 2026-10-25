package net.kaloyan.smpworth;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adds a "Worth: price" line to the lore of items, using a price sheet (base value plus a bonus per enchantment level).
 *
 * <p>The line is written into the real item, so it survives dropping, trading and chests, and every item of one kind carries the
 * same text and keeps stacking. It is refreshed in player inventories every {@code scan-ticks}, in containers when they are opened
 * and closed, and on items picked up from the ground. Menus made by other plugins (shop, crates, auction house) are not touched.
 */
public final class SMPWorthPlugin extends JavaPlugin implements Listener, TabExecutor {

    private volatile PriceSheet sheet;
    private volatile WorthSettings settings;
    private WorthApplier applier;
    private SellMenu sellMenu;

    /** Version of the bundled price sheet and config; older files on the server are replaced (and kept as .old files). */
    private static final int SHEET_VERSION = 2;

    /** Moves a price sheet or config of an older version aside so the bundled one is installed. */
    private void replaceOldFiles() {
        File sheetFile = new File(getDataFolder(), "prices.json");
        if (sheetFile.isFile()) {
            int version = 0;
            try (Reader in = Files.newBufferedReader(sheetFile.toPath(), StandardCharsets.UTF_8)) {
                var meta = com.google.gson.JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("metadata");
                version = meta != null && meta.has("sheet_version") ? meta.get("sheet_version").getAsInt() : 0;
            } catch (IOException | RuntimeException e) {
                version = 0;
            }
            if (version < SHEET_VERSION) {
                moveAside(sheetFile, "prices.old-" + System.currentTimeMillis() + ".json");
            }
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.isFile()) {
            YamlConfiguration yaml = new YamlConfiguration();
            int version = 0;
            try {
                yaml.load(configFile);
                version = yaml.getInt("config-version", 0);
            } catch (IOException | InvalidConfigurationException e) {
                version = 0;
            }
            if (version < SHEET_VERSION) {
                moveAside(configFile, "config.old-" + System.currentTimeMillis() + ".yml");
            }
        }
    }

    private void moveAside(File file, String newName) {
        try {
            Files.move(file.toPath(), file.toPath().resolveSibling(newName));
            getLogger().warning(file.getName() + " was from an older version; it was moved to " + newName + " and replaced by the new one.");
        } catch (IOException e) {
            getLogger().warning("Could not replace the old " + file.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void onEnable() {
        replaceOldFiles();
        saveDefaultConfig();
        if (!new File(getDataFolder(), "prices.json").isFile()) {
            saveResource("prices.json", false);
        }
        try {
            settings = loadSettings();
            sheet = loadSheet().withRules(settings.fallbacks());
        } catch (WorthSettings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Worth. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException e) {
            getLogger().severe("plugins/SMP-Worth/prices.json cannot be used: " + e.getMessage());
            getLogger().severe("Fix it or delete it (the bundled sheet is then restored on the next start). Disabling SMP-Worth.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        applier = new WorthApplier(this, () -> sheet, () -> settings);
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("worth").setExecutor(this);
        getCommand("worth").setTabCompleter(this);
        sellMenu = new SellMenu(this, applier, () -> settings, getLogger());
        getServer().getPluginManager().registerEvents(sellMenu, this);
        getCommand("sell").setExecutor(this);
        getCommand("sell").setTabCompleter(this);
        schedule();
        getLogger().info("SMP-Worth ready: " + sheet.size() + " priced items.");
    }

    private int taskId = -1;

    private void schedule() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = Bukkit.getScheduler().runTaskTimer(this, this::scan, 20L, settings.scanTicks()).getTaskId();
    }

    private WorthSettings loadSettings() throws WorthSettings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new WorthSettings.Invalid(List.of("config.yml cannot be read: " + e.getMessage()));
        }
        try (Reader reader = new java.io.InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));      // new keys (like /sell) work with an older config file
        } catch (IOException e) {
            throw new WorthSettings.Invalid(List.of("the bundled config.yml cannot be read: " + e.getMessage()));
        }
        return WorthSettings.parse(yaml);
    }

    private PriceSheet loadSheet() throws IOException {
        try (Reader in = Files.newBufferedReader(new File(getDataFolder(), "prices.json").toPath(), StandardCharsets.UTF_8)) {
            return PriceSheet.parse(in);
        }
    }

    private Component message(String key, Object... placeholders) {
        var values = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return MiniMessage.miniMessage().deserialize(settings.messages().getOrDefault(key, key), values.build());
    }

    // ---------------------------------------------------------------- keeping the lore up to date

    private boolean skip(Player player) {
        return settings.skipCreative() && player.getGameMode() == GameMode.CREATIVE;
    }

    private void refresh(Inventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack != null && !stack.getType().isAir()) {
                ItemStack copy = stack.clone();
                if (applier.apply(copy)) {
                    inventory.setItem(i, copy);
                }
            }
        }
    }

    private void scan() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!skip(player)) {
                refresh(player.getInventory());
            }
        }
    }

    /** Real containers only: chests, barrels, shulker boxes, hoppers, ender chests, chest minecarts; never another plugin's menu. */
    private static boolean isRealContainer(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof BlockState || holder instanceof Entity || holder instanceof HumanEntity
                || inventory instanceof DoubleChestInventory || holder instanceof org.bukkit.block.DoubleChest;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!skip(event.getPlayer())) {
            refresh(event.getPlayer().getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (isRealContainer(event.getInventory()) && event.getPlayer() instanceof Player player && !skip(player)) {
            refresh(event.getInventory());
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (isRealContainer(event.getInventory()) && event.getPlayer() instanceof Player player && !skip(player)) {
            refresh(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !skip(player)) {
            Item dropped = event.getItem();
            ItemStack stack = dropped.getItemStack().clone();
            if (applier.apply(stack)) {
                dropped.setItemStack(stack);
            }
        }
    }

    // ---------------------------------------------------------------- command

    private void sell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("not-player"));
            return;
        }
        if (!settings.sell().enabled()) {
            player.sendMessage(message("sell-disabled"));
        } else if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            sellMenu.sellHand(player);
        } else if (args.length > 0) {
            player.sendMessage(message("sell-usage"));
        } else {
            sellMenu.open(player);
        }
    }

    @Override
    public void onDisable() {
        if (sellMenu != null) {
            sellMenu.closeAll();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("sell")) {
            sell(sender, args);
            return true;
        }
        String sub = args.length == 0 ? "hand" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "hand" -> hand(sender);
            case "reload" -> {
                if (!sender.hasPermission("smpworth.admin")) {
                    sender.sendMessage(message("no-permission"));
                    return true;
                }
                reload(sender);
            }
            case "selftest" -> {
                if (sender.hasPermission("smpworth.admin")) {
                    selfTest(sender);
                }
            }
            case "missing" -> {
                if (!sender.hasPermission("smpworth.admin")) {
                    sender.sendMessage(message("no-permission"));
                    return true;
                }
                missing(sender);
            }
            default -> {
                Material material = Material.matchMaterial(args[0]);
                if (material == null || !material.isItem()) {
                    sender.sendMessage(message("usage"));
                } else {
                    OptionalDouble worth = sheet.worth(material.getKey().getKey(), java.util.Map.of());
                    sender.sendMessage(message("item", "item", material.getKey().getKey(),
                            "price", worth.isPresent() ? LoreLine.price(worth.getAsDouble()) : "0"));
                }
            }
        }
        return true;
    }

    private static org.bukkit.enchantments.Enchantment enchantment(String name) {
        return io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                .get(org.bukkit.NamespacedKey.minecraft(name));
    }

    /** Admin check on the real server: builds items, applies the lore and verifies it is correct, idempotent and keeps stacking. */
    private void selfTest(CommandSender sender) {
        List<String> failures = new ArrayList<>();
        ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
        var meta = pick.getItemMeta();
        meta.addEnchant(enchantment("efficiency"), 5, true);
        meta.addEnchant(enchantment("mending"), 1, true);
        meta.lore(List.of(Component.text("Special item")));
        pick.setItemMeta(meta);
        if (!applier.apply(pick)) {
            failures.add("a fresh item was not changed");
        }
        List<Component> lore = pick.getItemMeta().lore();
        String last = lore == null || lore.isEmpty() ? "" : LoreLine.plain(lore.get(lore.size() - 1));
        if (!last.equals("Worth: 150")) {
            failures.add("pickaxe line was '" + last + "', expected 'Worth: 150' (90 + 25 + 35)");
        }
        if (lore == null || lore.size() != 2 || !LoreLine.plain(lore.get(0)).equals("Special item")) {
            failures.add("the item's own lore was not kept");
        }
        if (applier.apply(pick)) {
            failures.add("applying twice changed the item again");
        }
        ItemStack twin = new ItemStack(Material.DIAMOND_PICKAXE);
        var tm = twin.getItemMeta();
        tm.addEnchant(enchantment("efficiency"), 5, true);
        tm.addEnchant(enchantment("mending"), 1, true);
        tm.lore(List.of(Component.text("Special item")));
        twin.setItemMeta(tm);
        applier.apply(twin);
        if (!pick.isSimilar(twin)) {
            failures.add("two identical items no longer stack");
        }
        ItemStack stone = new ItemStack(Material.STONE, 64);
        applier.apply(stone);
        ItemStack stone2 = new ItemStack(Material.STONE, 3);
        applier.apply(stone2);
        if (!stone.isSimilar(stone2)) {
            failures.add("plain items no longer stack");
        }
        var stoneLore = stone.getItemMeta().lore();
        if (stoneLore == null || !LoreLine.plain(stoneLore.get(stoneLore.size() - 1)).equals("Worth: 1.5")) {
            failures.add("stone line wrong: " + stoneLore);
        }
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        var bm = (org.bukkit.inventory.meta.EnchantmentStorageMeta) book.getItemMeta();
        bm.addStoredEnchant(enchantment("sharpness"), 5, true);
        book.setItemMeta(bm);
        applier.apply(book);
        var bookLore = book.getItemMeta().lore();
        if (bookLore == null || !LoreLine.plain(bookLore.get(bookLore.size() - 1)).equals("Worth: 42")) {
            failures.add("book line wrong: " + bookLore);
        }
        ItemStack curse = new ItemStack(Material.IRON_HELMET);
        var cm = curse.getItemMeta();
        cm.addEnchant(enchantment("binding_curse"), 1, true);
        curse.setItemMeta(cm);
        if (Math.abs(applier.worthOf(curse).orElse(-1) - (sheet.worth("iron_helmet", java.util.Map.of()).orElse(0) - 8)) > 0.011) {
            failures.add("the binding curse was not priced");
        }
        sender.sendMessage(failures.isEmpty() ? Component.text("SELFTEST PASSED", NamedTextColor.GREEN)
                : Component.text("SELFTEST FAILED: " + failures, NamedTextColor.RED));
        getLogger().info(failures.isEmpty() ? "SELFTEST PASSED" : "SELFTEST FAILED: " + failures);
    }

    private void hand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("not-player"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(message("empty-hand"));
            return;
        }
        double each = applier.worthOf(held).orElse(0);
        sender.sendMessage(message("hand", "item", held.getType().getKey().getKey(), "price", LoreLine.price(each),
                "amount", held.getAmount(), "total", LoreLine.price(each * held.getAmount())));
    }

    private void reload(CommandSender sender) {
        try {
            WorthSettings fresh = loadSettings();
            PriceSheet freshSheet = loadSheet().withRules(fresh.fallbacks());
            settings = fresh;
            sheet = freshSheet;
            schedule();
            sender.sendMessage(message("reloaded", "count", freshSheet.size()));
        } catch (WorthSettings.Invalid | IOException e) {
            getLogger().warning("Reload rejected, the previous settings stay active: " + e.getMessage());
            sender.sendMessage(message("reload-rejected"));
        }
    }

    private void missing(CommandSender sender) {
        TreeSet<String> missing = new TreeSet<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_") && !sheet.knows(m.getKey().getKey())) {
                missing.add(m.getKey().getKey());
            }
        }
        sender.sendMessage(message("missing", "count", missing.size()));
        List<String> shown = new ArrayList<>(missing);
        sender.sendMessage(Component.text(String.join(", ", shown.subList(0, Math.min(shown.size(), 60)))
                + (shown.size() > 60 ? ", ... (full list in the console)" : ""), NamedTextColor.GRAY));
        getLogger().info("Items without a price (" + missing.size() + "): " + String.join(", ", missing));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("sell")) {
            return args.length == 1 ? List.of("hand") : List.of();
        }
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("hand"));
        if (sender.hasPermission("smpworth.admin")) {
            options.addAll(List.of("reload", "missing"));
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(prefix)).toList();
    }
}
