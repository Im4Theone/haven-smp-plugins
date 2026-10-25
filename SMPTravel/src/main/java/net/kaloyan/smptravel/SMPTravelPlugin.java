package net.kaloyan.smptravel;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Spawn and hub, homes, teleport requests and /back, all with a stand-still warm-up. */
public final class SMPTravelPlugin extends JavaPlugin implements Listener {

    private volatile TravelSettings settings;
    /** Read once when the plugin starts: the rescue checks it on every movement, so it must not touch the disk. */
    private volatile VoidRescue.Config voidSettings;
    private SpotStore store;
    private Teleporter teleporter;
    private TravelCommands commands;
    private final Messages messages = new Messages();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = load();
        } catch (TravelSettings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Travel. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messages.texts(settings.messages());
        store = new SpotStore(getDataFolder().toPath().resolve("travel.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let players overwrite their saved homes, so refuse to run instead.
            getLogger().severe("travel.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Travel is disabled so no homes are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        teleporter = new Teleporter(this, messages, () -> settings.warmupSeconds());
        commands = new TravelCommands(store, teleporter, messages, () -> settings);
        for (String name : List.of("spawn", "hub", "setspawn", "sethome", "home", "homes", "delhome", "tpa", "tpahere",
                "tpaccept", "tpdeny", "tpcancel", "back")) {
            PluginCommand command = getCommand(name);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        voidSettings = voidConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new VoidRescue(this, () -> voidSettings, this::hubSpot), this);
        getLogger().info("SMP-Travel ready (warm-up " + settings.warmupSeconds() + " s).");
    }

    /** The void rescue settings (a missing section means: on, in the hub world, below height 20). */
    private VoidRescue.Config voidConfig() {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            return new VoidRescue.Config(false, java.util.Set.of(), 0, true, "");
        }
        List<String> worlds = yaml.getStringList("void-rescue.worlds");
        return new VoidRescue.Config(yaml.getBoolean("void-rescue.enabled", true),
                new java.util.HashSet<>(worlds.isEmpty() ? List.of(settings.hubWorld()) : worlds), yaml.getDouble("void-rescue.min-y", 20),
                yaml.getBoolean("void-rescue.skip-creative", true),
                yaml.getString("void-rescue.message", "<#FBBF24>You fell out of the world - back to the hub."));
    }

    /** Where the hub is: the spot saved with /setspawn hub, else the configured respawn place, else the hub world's spawn. */
    private Location hubSpot() {
        Location saved = store.server("hub").map(Spot::toLocation).orElse(null);
        if (saved != null) {
            return saved;
        }
        Location configured = settings.respawn().toLocation();
        if (configured != null) {
            return configured;
        }
        var world = Bukkit.getWorld(settings.hubWorld());
        return world == null ? null : world.getSpawnLocation().add(0.5, 0, 0.5);
    }

    private TravelSettings load() throws TravelSettings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new TravelSettings.Invalid(List.of("config.yml cannot be read: " + e.getMessage()));
        }
        return TravelSettings.parse(yaml);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            teleporter.markHurt(player.getUniqueId());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        teleporter.rememberBack(event.getEntity().getUniqueId(), Spot.of(event.getEntity().getLocation()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        teleporter.forget(event.getPlayer().getUniqueId());
        commands.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Where players come back after dying: by default the hub for everybody, even with a bed. The spot saved with
     * {@code /setspawn hub} wins; without one the location from the config is used. If that world is not loaded, respawning stays
     * vanilla so nobody is sent into the void.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        boolean bed = event.isBedSpawn() || event.isAnchorSpawn();
        Optional<Spot> saved = store.server("hub");
        Respawn.Place place = Respawn.choose(settings.respawnMode(), bed, saved.isPresent());
        if (place == Respawn.Place.VANILLA) {
            return;
        }
        Location destination = (place == Respawn.Place.SAVED_HUB ? saved.get() : settings.respawn()).toLocation();
        if (destination != null) {
            event.setRespawnLocation(destination);
        }
    }

    /** New players start in the hub (when it exists), without a warm-up. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore() || !settings.firstJoinHub()) {
            return;
        }
        Location hub = hubSpot();
        if (hub != null) {
            Location destination = hub;
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    player.teleportAsync(destination);
                }
            });
        }
    }
}
