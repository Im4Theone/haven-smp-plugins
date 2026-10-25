package net.kaloyan.smptravel;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/** A player who falls out of the world (below a set height) in the hub is sent back to the hub instead of dying in the void. */
public final class VoidRescue implements Listener {

    /**
     * @param enabled    whether the rescue is on
     * @param worlds     the worlds where it applies
     * @param minY       players below this height are rescued
     * @param skipCreative whether creative and spectator players are left alone
     * @param message    MiniMessage text shown above the hotbar
     */
    public record Config(boolean enabled, Set<String> worlds, double minY, boolean skipCreative, String message) {
    }

    private final Plugin plugin;
    private final Supplier<Config> config;
    private final Supplier<Location> destination;
    private final Map<UUID, Long> last = new HashMap<>();
    private boolean warned;

    /**
     * @param plugin      the owning plugin
     * @param config      the current settings
     * @param destination where rescued players go (the hub), or {@code null} if it is not known
     */
    public VoidRescue(Plugin plugin, Supplier<Config> config, Supplier<Location> destination) {
        this.plugin = plugin;
        this.config = config;
        this.destination = destination;
    }

    /**
     * @param c          the settings
     * @param world      the world the player is in
     * @param y          the player's height
     * @param creative   whether the player is in creative or spectator mode
     * @return whether the player should be sent back
     */
    static boolean falling(Config c, String world, double y, boolean creative) {
        return c.enabled() && c.worlds().contains(world) && y < c.minY() && !(creative && c.skipCreative());
    }

    private static boolean noClip(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        Config c = config.get();
        if (!falling(c, to.getWorld().getName(), to.getY(), noClip(player))) {
            return;
        }
        long now = System.currentTimeMillis();
        Long before = last.get(player.getUniqueId());
        if (before != null && now - before < 1500) {
            return;
        }
        Location hub = destination.get();
        if (hub == null || hub.getWorld() == null || hub.getY() <= c.minY()) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Void rescue: there is no usable hub spot (set it with /setspawn hub, higher than "
                        + c.minY() + "), so players falling out of the world are not rescued.");
            }
            return;
        }
        last.put(player.getUniqueId(), now);
        player.setFallDistance(0);
        player.setVelocity(new Vector(0, 0, 0));
        player.teleportAsync(hub).thenAccept(ok -> {
            if (ok) {
                player.setFallDistance(0);
                player.sendActionBar(MiniMessage.miniMessage().deserialize(c.message()));
            }
        });
    }

    /** Nobody dies of the void in these worlds (for example after an ender pearl or a teleport below the rescue height). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && event.getEntity() instanceof Player player) {
            Config c = config.get();
            if (c.enabled() && c.worlds().contains(player.getWorld().getName())) {
                event.setCancelled(true);
                Location hub = destination.get();
                if (hub != null && hub.getWorld() != null && hub.getY() > c.minY()) {
                    player.setFallDistance(0);
                    player.teleportAsync(hub);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        last.remove(event.getPlayer().getUniqueId());
    }

    /** @return the message above, for tests of the default text */
    static Component sample(Config c) {
        return MiniMessage.miniMessage().deserialize(c.message());
    }
}
