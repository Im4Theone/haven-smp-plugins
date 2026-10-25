package net.kaloyan.smpcrates;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kaloyan.smpcrates.CrateStore.CrateBlock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Crates: a free one for everybody every hour (the timer is the server's clock), crates from the shop, a menu with preview and
 * a short opening animation, and crate blocks. The reward is decided and handed over when the crate is taken, so the animation
 * is only decoration.
 */
public final class SMPCratesPlugin extends JavaPlugin implements Listener {

    private volatile CratesSettings settings;
    private CrateStore store;
    private CrateService service;
    private Menus menus;
    private Messages messages;
    private Items items;
    private long lastWarning;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = load();
        } catch (CratesSettings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Crates. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        List<String> unknown = Items.unknownEnchantments(settings);
        if (!unknown.isEmpty()) {
            getLogger().severe("config.yml is invalid; disabling SMP-Crates. Fix the following:");
            unknown.forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store = new CrateStore(getDataFolder().toPath().resolve("data.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe everybody's crates, so refuse to run instead.
            getLogger().severe("data.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Crates is disabled so no crates are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages = new Messages(() -> settings);
        items = new Items(messages);
        service = new CrateService(() -> settings, store, new BukkitDelivery(this, items), new Random(), System::currentTimeMillis);
        menus = new Menus(this, service, store, messages, items, () -> settings);
        CratesCommand command = new CratesCommand(service, store, menus, messages, () -> settings, this::reloadSettings);
        getCommand("crates").setExecutor(command);
        getCommand("crates").setTabCompleter(command);
        KeyallCommand keyall = new KeyallCommand(service, messages, () -> settings);
        getCommand("keyall").setExecutor(keyall);
        getCommand("keyall").setTabCompleter(keyall);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 40L, 20L);
        getLogger().info("SMP-Crates ready: " + settings.crates().size() + " crate type(s), hourly "
                + (settings.hourlyEnabled() ? settings.hourlyCrate() + " every " + settings.intervalMinutes() + " min" : "off") + ".");
    }

    private CratesSettings load() throws CratesSettings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new CratesSettings.Invalid(List.of("config.yml cannot be read: " + e.getMessage()));
        }
        return CratesSettings.parse(yaml);
    }

    private void reloadSettings() {
        try {
            CratesSettings fresh = load();
            List<String> unknown = Items.unknownEnchantments(fresh);
            if (!unknown.isEmpty()) {
                unknown.forEach(p -> getLogger().warning("Reload rejected, the previous settings stay active: " + p));
                return;
            }
            settings = fresh;
        } catch (CratesSettings.Invalid e) {
            getLogger().warning("Reload rejected, the previous settings stay active:");
            e.problems().forEach(problem -> getLogger().warning(" - " + problem));
        }
    }

    // ---------------------------------------------------------------- hourly crates

    private void tick() {
        if (!settings.hourlyEnabled()) {
            return;
        }
        try {
            List<UUID> granted = service.hourly(Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toList()));
            Object crateName = messages.parse(settings.crates().get(settings.hourlyCrate()).display());
            for (UUID id : granted) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    messages.send(player, "hourly", "crate", crateName);
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
                }
            }
            if (!granted.isEmpty()) {
                Bukkit.broadcast(messages.render("keyall", "crate", crateName,
                        "time", HourlyClock.format(service.hourly().untilNext(System.currentTimeMillis()))));
                getLogger().info("Keyall: " + granted.size() + " player(s) received a " + settings.hourlyCrate() + " crate.");
            }
        } catch (IOException e) {
            warnSaveFailure(e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !settings.hourlyEnabled()) {
                return;
            }
            // Only with catch-up on: a player who was away during a round still gets that round's crate (never more than one).
            if (!settings.catchUp()) {
                return;
            }
            try {
                if (!service.hourly(List.of(id)).isEmpty()) {
                    messages.send(player, "hourly", "crate", messages.parse(settings.crates().get(settings.hourlyCrate()).display()));
                }
            } catch (IOException e) {
                warnSaveFailure(e);
            }
        }, 40L);
    }

    private void warnSaveFailure(IOException e) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000) {
            lastWarning = now;
            getLogger().severe("Crate data could not be saved, nobody got their hourly crate this time: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- crate blocks

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        var b = event.getClickedBlock();
        Optional<CrateBlock> crateBlock = store.blockAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (crateBlock.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        service.type(crateBlock.get().crate()).ifPresent(type -> {
            if (action == Action.RIGHT_CLICK_BLOCK) {
                menus.open(event.getPlayer(), type);
            } else {
                menus.openPreview(event.getPlayer(), type);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        var b = event.getBlock();
        if (store.blockAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()).isPresent()) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "block-protected");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> store.blockAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> store.blockAt(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()).isPresent());
    }

    // ---------------------------------------------------------------- for other plugins (DonutShop, the scoreboard)

    /**
     * Gives crates, for example after a shop purchase. Used by DonutShop and SMP-Scoreboard through reflection.
     *
     * @param player the player
     * @param type   the crate type id
     * @param amount how many
     * @return {@code true} only if the crates were saved to the player
     */
    public boolean giveCrates(UUID player, String type, int amount) {
        try {
            return service.give(player, type.toLowerCase(Locale.ROOT), amount);
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Could not give " + amount + " " + type + " crate(s) to " + player + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * @param type the crate type id
     * @return whether such a crate type exists
     */
    public boolean hasCrateType(String type) {
        return settings.crates().containsKey(type.toLowerCase(Locale.ROOT));
    }

    /** @return seconds until the next free crate, or {@code -1} if the hourly crate is switched off */
    public long secondsUntilNext() {
        return settings.hourlyEnabled() ? (service.hourly().untilNext(System.currentTimeMillis()) + 999) / 1000 : -1;
    }

    /**
     * @param player the player
     * @return how many crates of all types the player has
     */
    public int totalCrates(UUID player) {
        return store.balances(player).values().stream().mapToInt(Integer::intValue).sum();
    }
}
