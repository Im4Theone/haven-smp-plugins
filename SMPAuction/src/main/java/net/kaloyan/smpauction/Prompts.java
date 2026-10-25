package net.kaloyan.smpauction;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Asks a player to type something in chat. The typed line is swallowed (nobody else sees it) and handed to a callback. */
public final class Prompts implements Listener {

    private static final long TIMEOUT_MILLIS = 60_000L;

    private record Pending(Consumer<String> callback, Runnable onCancel, long until) {
    }

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    /** @param plugin the owning plugin */
    public Prompts(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Waits for the player's next chat line.
     *
     * @param player   who is asked
     * @param callback gets the line, on the main thread
     * @param onCancel runs on the main thread if the player types {@code cancel}
     */
    public void ask(Player player, Consumer<String> callback, Runnable onCancel) {
        synchronized (pending) {
            pending.put(player.getUniqueId(), new Pending(callback, onCancel, System.currentTimeMillis() + TIMEOUT_MILLIS));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Pending p;
        synchronized (pending) {
            p = pending.remove(event.getPlayer().getUniqueId());
        }
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        if (System.currentTimeMillis() > p.until()) {
            return;
        }
        String line = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (line.equalsIgnoreCase("cancel")) {
                p.onCancel().run();
            } else {
                p.callback().accept(line);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        synchronized (pending) {
            pending.remove(event.getPlayer().getUniqueId());
        }
    }
}
