package net.kaloyan.smprtp;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Random teleport in the overworld, the nether and the end. */
public final class SMPRtpPlugin extends JavaPlugin implements Listener {

    private static final int CONFIG_VERSION = 3;

    private RtpService service;

    @Override
    public void onEnable() {
        replaceOutdatedConfig();
        saveDefaultConfig();
        RtpSettings settings;
        try {
            settings = load();
        } catch (RtpSettings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-RTP. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service = new RtpService(this, settings);
        RtpMenu menu = new RtpMenu(service);
        getServer().getPluginManager().registerEvents(menu, this);
        RtpCommand command = new RtpCommand(service, this::reloadSettings, menu);
        getCommand("rtp").setExecutor(command);
        getCommand("rtp").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(this, this);
        settings.worlds().values().forEach(w -> {
            if (w.enabled() && getServer().getWorld(w.world()) == null) {
                getLogger().warning("World '" + w.world() + "' (" + w.key() + ") is not loaded; /rtp " + w.key()
                        + " will say it is unavailable until it is.");
            }
        });
        getLogger().info("SMP-RTP ready: " + String.join(", ", settings.worlds().keySet()) + ".");
    }

    /** A config from an older version has other keys (max-radius instead of size), so it is set aside. */
    private void replaceOutdatedConfig() {
        java.nio.file.Path file = getDataFolder().toPath().resolve("config.yml");
        if (!java.nio.file.Files.isRegularFile(file)
                || YamlConfiguration.loadConfiguration(file.toFile()).getInt("config-version", 0) >= CONFIG_VERSION) {
            return;
        }
        java.nio.file.Path old = file.resolveSibling("config.old-" + System.currentTimeMillis() + ".yml");
        try {
            java.nio.file.Files.move(file, old);
            getLogger().warning("config.yml was from an older SMP-RTP; it was saved as " + old.getFileName()
                    + " and replaced with the current defaults.");
        } catch (IOException e) {
            getLogger().warning("Could not replace the outdated config.yml: " + e.getMessage());
        }
    }

    private RtpSettings load() throws RtpSettings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new RtpSettings.Invalid(java.util.List.of("config.yml cannot be read: " + e.getMessage()));
        }
        return RtpSettings.parse(yaml);
    }

    private void reloadSettings() {
        try {
            service.settings(load());
        } catch (RtpSettings.Invalid e) {
            getLogger().warning("Reload rejected, the previous settings stay active:");
            e.problems().forEach(problem -> getLogger().warning(" - " + problem));
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            service.markHurt(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }
}
