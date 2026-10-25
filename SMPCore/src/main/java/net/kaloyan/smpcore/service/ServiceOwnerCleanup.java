package net.kaloyan.smpcore.service;

import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * Removes a plugin's service registrations when that plugin is disabled, so stale services from a
 * disabled plugin can never be handed out.
 */
public final class ServiceOwnerCleanup implements Listener {

    private final SimpleServiceRegistry registry;
    private final Logger logger;

    public ServiceOwnerCleanup(SimpleServiceRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        int removed = registry.unregisterAll(event.getPlugin());
        if (removed > 0) {
            logger.info("Removed " + removed + " shared service(s) registered by " + event.getPlugin().getName());
        }
    }
}
