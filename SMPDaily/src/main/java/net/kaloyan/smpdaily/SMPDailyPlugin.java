package net.kaloyan.smpdaily;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** A daily reward with a 7-day streak: /daily opens the calendar, and a reminder shows on join when a reward is waiting. */
public final class SMPDailyPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 1;
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int CLAIM = 22;
    private static final TextColor GREEN = TextColor.color(0x00F986);
    private static final TextColor GOLD = TextColor.color(0xFBBF24);

    private static final class Holder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private volatile Settings settings;
    private Daily daily;
    private final MiniMessage mini = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Daily. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ClaimStore store = new ClaimStore(getDataFolder().toPath().resolve("daily.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let everybody claim again and restart every streak, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore daily.yml and restart. SMP-Daily is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        daily = new Daily(store, () -> settings, getLogger());
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("daily").setExecutor(this);
        getLogger().info("SMP-Daily ready: " + settings.days().size() + "-day cycle.");
    }

    /** Loads config.yml, replacing one from an older version (the old file is kept) and filling gaps from the built-in one. */
    private Settings loadSettings() throws Settings.Invalid, IOException, InvalidConfigurationException {
        Path path = getDataFolder().toPath().resolve("config.yml");
        if (Files.isRegularFile(path)) {
            YamlConfiguration current = new YamlConfiguration();
            current.load(path.toFile());
            if (current.getInt("config-version", 0) < CONFIG_VERSION) {
                Path old = path.resolveSibling("config.old-" + System.currentTimeMillis() + ".yml");
                Files.move(path, old, StandardCopyOption.REPLACE_EXISTING);
                getLogger().warning("config.yml was from an older version; it was moved to " + old.getFileName() + " and replaced.");
            }
        }
        saveDefaultConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        try (Reader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        return Settings.parse(yaml);
    }

    private Component text(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(settings.message(key), values.build());
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String waitText(long millis) {
        long minutes = Math.max(1, millis / 60_000);
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : minutes + "m";
    }

    // ---------------------------------------------------------------- the calendar

    private void open(Player player) {
        long now = System.currentTimeMillis();
        ClaimStore.State state = daily.store().get(player.getUniqueId());
        int days = settings.days().size();
        int today = Streak.nextDay(state.last(), state.day(), now, days);
        boolean ready = Streak.ready(state.last(), now);
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 27, mini.deserialize(settings.titles().getOrDefault("main", "Daily Reward")));
        holder.inventory = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.displayName(Component.text(" "));
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, pane);
        }
        for (int i = 0; i < days; i++) {
            int day = i + 1;
            boolean claimed = state.last() != 0 && !Streak.lost(state.last(), now) && day <= state.day() && !(ready && day == today && state.day() == days);
            boolean isToday = day == today;
            Material icon = isToday && ready ? Material.CHEST_MINECART : claimed ? Material.LIME_STAINED_GLASS_PANE : isToday ? Material.CLOCK : Material.GRAY_STAINED_GLASS_PANE;
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(line("Day " + day, isToday ? GREEN : claimed ? NamedTextColor.GREEN : NamedTextColor.GRAY).decorate(TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(line(Daily.describe(settings.days().get(i)), NamedTextColor.WHITE));
            if (isToday && ready) {
                lore.add(line("Click to claim", GREEN));
            } else if (isToday) {
                lore.add(line("Ready in " + waitText(Streak.until(state.last(), now)), GOLD));
            } else if (claimed) {
                lore.add(line("Claimed", NamedTextColor.GREEN));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(SLOTS[i], item);
        }
        ItemStack button = new ItemStack(ready ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta bm = button.getItemMeta();
        bm.displayName(ready ? line("CLAIM DAY " + today, GREEN).decorate(TextDecoration.BOLD) : line("Come back in " + waitText(Streak.until(state.last(), now)), NamedTextColor.GRAY));
        bm.lore(List.of(line("Streak: day " + (Streak.lost(state.last(), now) ? 0 : state.day()), NamedTextColor.WHITE)));
        button.setItemMeta(bm);
        inv.setItem(CLAIM, button);
        player.openInventory(inv);
    }

    private void claim(Player player) {
        Daily.Result r = daily.claim(player, System.currentTimeMillis());
        switch (r.status()) {
            case OK -> {
                if (r.lostStreak()) {
                    player.sendMessage(text("streak-lost"));
                }
                player.sendMessage(mini.deserialize(settings.message("claimed"), TagResolver.resolver(Placeholder.unparsed("day", Integer.toString(r.day())),
                        Placeholder.component("rewards", Component.text(r.given(), GOLD)))));
                player.playSound(player.getLocation(), "entity.player.levelup", 0.5f, 1.4f);
            }
            case WAIT -> player.sendMessage(text("wait", "remaining", waitText(r.remaining())));
            case PAYMENT_FAILED -> player.sendMessage(text("payment-failed"));
            default -> player.sendMessage(text("saved-failed"));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            open(player);
        } else {
            sender.sendMessage("Only players can claim daily rewards.");
        }
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            boolean today = event.getSlot() == CLAIM;
            for (int slot : SLOTS) {
                today |= slot == event.getSlot();
            }
            if (today) {
                claim(player);
                open(player);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && Streak.ready(daily.store().get(player.getUniqueId()).last(), System.currentTimeMillis())) {
                player.sendMessage(text("ready"));
            }
        }, 20L * 8);
    }
}
