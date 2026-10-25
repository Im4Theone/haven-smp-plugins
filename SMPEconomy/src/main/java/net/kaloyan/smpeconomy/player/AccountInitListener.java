package net.kaloyan.smpeconomy.player;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpeconomy.service.EconomyServiceImpl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Creates a player's account when their join has been processed. {@link PlayerJoinEvent} only fires for
 * logins that were actually accepted (not banned, not whitelisted out, not kicked), so no account is
 * created for a rejected connection. The database work is queued on the storage thread; this handler
 * never blocks the main thread. Commands issued right after joining queue behind it, so they see the account.
 *
 * <p>Creation is idempotent: an existing account is never changed except for its last known name, so
 * reconnecting or renaming never grants the starting balance again.
 */
public final class AccountInitListener implements Listener {

    private final EconomyServiceImpl service;
    private final Logger logger;

    public AccountInitListener(EconomyServiceImpl service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String name = player.getName();
        service.initializeAccount(id, name).whenComplete((created, error) -> {
            if (error != null) {
                // Logged by UUID only. The player has no account until they rejoin and this succeeds.
                logger.log(Level.SEVERE, "Could not initialize the economy account for " + id
                        + "; they will have no balance until a later join succeeds", error);
            }
        });
    }
}
