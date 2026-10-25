package net.kaloyan.smpcore.player;

import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kaloyan.smpcore.log.DebugLogger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Observes player joins and quits. Players are identified by UUID; the name is logged only as a
 * convenience for reading the console and is never used as a key. Nothing is stored and no gameplay
 * state is touched. Whether lines are logged is read from the live settings on every event.
 */
public final class PlayerLifecycleListener implements Listener {

    private final Logger logger;
    private final Supplier<CoreSettings> settings;
    private final DebugLogger debug;

    public PlayerLifecycleListener(Logger logger, Supplier<CoreSettings> settings, DebugLogger debug) {
        this.logger = logger;
        this.settings = settings;
        this.debug = debug;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (settings.get().logPlayerJoin()) {
            logger.info("Player joined: " + player.getUniqueId() + " (" + player.getName() + ")");
        }
        debug.debug(() -> "Handled join of " + player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (settings.get().logPlayerQuit()) {
            logger.info("Player left: " + player.getUniqueId() + " (" + player.getName() + ")");
        }
        debug.debug(() -> "Handled quit of " + player.getUniqueId());
    }
}
