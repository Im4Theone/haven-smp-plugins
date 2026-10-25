package net.kaloyan.smpcatch;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Holds a suspect in place so staff can look at them. A frozen player can still look around and talk, but nothing else. */
public final class Freeze implements Listener {

    private final Supplier<Settings> settings;
    private final Alerts alerts;
    private final Set<UUID> frozen = new HashSet<>();
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param settings the current settings
     * @param alerts   used to tell staff when a frozen player logs out
     */
    public Freeze(Supplier<Settings> settings, Alerts alerts) {
        this.settings = settings;
        this.alerts = alerts;
    }

    /** @return whether the player is frozen */
    public boolean isFrozen(UUID id) {
        return frozen.contains(id);
    }

    /**
     * Freezes or releases a player.
     *
     * @param player who
     * @return {@code true} if they are frozen now
     */
    public boolean toggle(Player player) {
        if (frozen.remove(player.getUniqueId())) {
            player.sendMessage(mini.deserialize(settings.get().message("unfrozen")));
            player.clearTitle();
            return false;
        }
        frozen.add(player.getUniqueId());
        player.sendMessage(mini.deserialize(settings.get().message("frozen")));
        player.showTitle(Title.title(Component.text("FROZEN", NamedTextColor.RED), Component.text("Wait for staff", NamedTextColor.GRAY)));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    private void block(org.bukkit.event.Cancellable event, Player player) {
        if (frozen.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        block(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        block(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        block(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        block(event, event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        boolean talk = command.startsWith("/msg ") || command.startsWith("/r ") || command.startsWith("/w ") || command.startsWith("/tell ");
        if (!talk) {
            block(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            block(event, p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (frozen.contains(player.getUniqueId())) {
            alerts.raise(player, "freeze-quit", "logged out while frozen", 0, player.getLocation());
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(Alerts.PERMISSION)) {
                    staff.sendMessage(Component.text(player.getName() + " logged out while frozen.", NamedTextColor.RED));
                }
            }
        }
    }
}
