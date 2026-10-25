package net.kaloyan.smptravel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Teleports a player after a warm-up: they must stand still (moving or taking damage cancels). One teleport per player
 * at a time. Remembers where the player was, for {@code /back}. Main thread only.
 */
public final class Teleporter {

    private final Plugin plugin;
    private final Messages messages;
    private final IntSupplier warmupSeconds;
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> hurt = new HashSet<>();
    private final Map<UUID, Spot> back = new HashMap<>();

    /**
     * @param plugin        the owning plugin
     * @param messages      where texts come from
     * @param warmupSeconds how long a player must stand still
     */
    public Teleporter(Plugin plugin, Messages messages, IntSupplier warmupSeconds) {
        this.plugin = plugin;
        this.messages = messages;
        this.warmupSeconds = warmupSeconds;
    }

    /** @return {@code true} if the player is waiting for a teleport */
    public boolean busy(UUID id) {
        return busy.contains(id);
    }

    /** Called when a player takes damage: it cancels their waiting teleport. */
    public void markHurt(UUID id) {
        if (busy.contains(id)) {
            hurt.add(id);
        }
    }

    /** Forgets a player who left. */
    public void forget(UUID id) {
        busy.remove(id);
        hurt.remove(id);
    }

    /** @return where the player was before their last teleport or death, if known */
    public Spot back(UUID id) {
        return back.get(id);
    }

    /**
     * Remembers a position for {@code /back}.
     *
     * @param id   the player
     * @param spot where they were
     */
    public void rememberBack(UUID id, Spot spot) {
        back.put(id, spot);
    }

    /**
     * Teleports a player after the warm-up.
     *
     * @param player      who moves
     * @param destination where to
     * @param what        a short name for the destination, used in messages (e.g. {@code home}, {@code spawn})
     */
    public void teleport(Player player, Location destination, String what) {
        UUID id = player.getUniqueId();
        if (!busy.add(id)) {
            messages.send(player, "busy");
            return;
        }
        hurt.remove(id);
        int seconds = warmupSeconds.getAsInt();
        if (seconds <= 0) {
            go(player, destination, what);
            return;
        }
        Location start = player.getLocation();
        int[] ticksLeft = {seconds * 20};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !busy.contains(id)) {
                task[0].cancel();
                forget(id);
                return;
            }
            if (hurt.remove(id)) {
                task[0].cancel();
                busy.remove(id);
                messages.send(player, "cancelled-damage");
                return;
            }
            Location now = player.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 0.25) {
                task[0].cancel();
                busy.remove(id);
                messages.send(player, "cancelled-move");
                return;
            }
            if (ticksLeft[0] % 20 == 0 && ticksLeft[0] > 0) {
                player.sendActionBar(messages.render("warmup", "seconds", Integer.toString(ticksLeft[0] / 20), "what", what));
            }
            ticksLeft[0] -= 5;
            if (ticksLeft[0] <= 0) {
                task[0].cancel();
                go(player, destination, what);
            }
        }, 0L, 5L);
    }

    private void go(Player player, Location destination, String what) {
        UUID id = player.getUniqueId();
        Spot before = Spot.of(player.getLocation());
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.COMMAND).whenComplete((ok, failure) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (failure != null || !Boolean.TRUE.equals(ok)) {
                        messages.send(player, "teleport-failed");
                        return;
                    }
                    back.put(id, before);
                    messages.send(player, "teleported", "what", what);
                }));
    }
}
