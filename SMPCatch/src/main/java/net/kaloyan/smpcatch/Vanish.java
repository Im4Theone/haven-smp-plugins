package net.kaloyan.smpcatch;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.Plugin;

/**
 * Staff vanish. A vanished player cannot be seen by anyone without {@code smpcatch.vanish.see}, is removed from their tab list and
 * from the server list count, makes no join or quit message, and (by default) cannot be hurt, targeted or pick things up.
 */
public final class Vanish implements Listener {

    /** Staff with this permission can see vanished players. */
    public static final String SEE = "smpcatch.vanish.see";

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final Path file;
    private final Set<UUID> vanished = new HashSet<>();
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param plugin   the owning plugin
     * @param settings the current settings
     * @param file     where vanished staff are remembered
     */
    public Vanish(Plugin plugin, Supplier<Settings> settings, Path file) {
        this.plugin = plugin;
        this.settings = settings;
        this.file = file;
    }

    /** Reads the remembered staff, if the file exists and persistence is on. */
    public void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            for (String id : yaml.getStringList("vanished")) {
                vanished.add(UUID.fromString(id));
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | IllegalArgumentException e) {
            plugin.getLogger().warning("vanish.yml could not be read, nobody is vanished: " + e.getMessage());
        }
    }

    private void save() {
        if (!settings.get().vanishPersist()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        vanished.forEach(id -> ids.add(id.toString()));
        yaml.set("vanished", ids);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save vanish.yml: " + e.getMessage());
        }
    }

    /** @return whether the player is vanished */
    public boolean isVanished(UUID id) {
        return vanished.contains(id);
    }

    /** @return how many vanished players are online right now */
    public int onlineCount() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanished.contains(p.getUniqueId())) {
                n++;
            }
        }
        return n;
    }

    /** @return the vanished players who are online */
    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanished.contains(p.getUniqueId())) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * Vanishes or shows a player.
     *
     * @param player who
     * @param on     {@code true} to vanish, {@code false} to show
     * @param loud   whether to send the confirmation and fake join or leave message
     */
    public void set(Player player, boolean on, boolean loud) {
        boolean was = vanished.contains(player.getUniqueId());
        if (on) {
            vanished.add(player.getUniqueId());
            apply(player);
        } else {
            vanished.remove(player.getUniqueId());
            reveal(player);
        }
        if (was != on) {
            save();
        }
        if (loud && was != on) {
            player.sendMessage(mini.deserialize(settings.get().message(on ? "vanish-on" : "vanish-off")));
            if (settings.get().fakeMessages()) {
                Component fake = Component.translatable(on ? "multiplayer.player.left" : "multiplayer.player.joined", NamedTextColor.YELLOW,
                        player.displayName());
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (!other.equals(player) && !other.hasPermission(SEE)) {
                        other.sendMessage(fake);
                    }
                }
            }
        }
    }

    private void apply(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && !other.hasPermission(SEE)) {
                other.hidePlayer(plugin, player);
            }
        }
        player.playerListName(Component.text("[V] ", NamedTextColor.GRAY).append(player.name()));
        player.setSleepingIgnored(true);
    }

    private void reveal(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player)) {
                other.showPlayer(plugin, player);
            }
        }
        player.playerListName(null);
        player.setSleepingIgnored(false);
    }

    /** Shows the vanish reminder above the hotbar of vanished players. Call regularly. */
    public void remind() {
        Component bar = mini.deserialize(settings.get().message("vanish-bar"));
        for (Player p : online()) {
            p.sendActionBar(bar);
        }
    }

    /** Shows everybody again (used when the plugin stops, so nobody stays hidden without the plugin). */
    public void revealAll() {
        for (Player p : online()) {
            reveal(p);
        }
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        for (Player p : online()) {
            if (!p.equals(joined) && !joined.hasPermission(SEE)) {
                joined.hidePlayer(plugin, p);
            }
        }
        if (vanished.contains(joined.getUniqueId())) {
            if (settings.get().vanishPersist() && joined.hasPermission("smpcatch.vanish")) {
                event.joinMessage(null);
                apply(joined);
                joined.sendMessage(mini.deserialize(settings.get().message("vanish-on")));
            } else {
                vanished.remove(joined.getUniqueId());
                save();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
            if (!settings.get().vanishPersist()) {
                vanished.remove(event.getPlayer().getUniqueId());
            }
        }
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            event.deathMessage(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (settings.get().vanishSafe() && event.getTarget() instanceof Player p && vanished.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (settings.get().vanishSafe() && event.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (settings.get().vanishSafe() && event.getEntity() instanceof Player p && vanished.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStep(PlayerInteractEvent event) {
        if (settings.get().vanishSafe() && event.getAction() == Action.PHYSICAL && vanished.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPing(PaperServerListPingEvent event) {
        int hidden = onlineCount();
        if (hidden > 0) {
            event.setNumPlayers(Math.max(0, event.getNumPlayers() - hidden));
            event.getListedPlayers().removeIf(p -> vanished.contains(p.id()));
        }
    }
}
