package net.kaloyan.smpcore;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpcore.command.Messages;
import net.kaloyan.smpcore.command.SmpCoreCommand;
import net.kaloyan.smpcore.config.ConfigManager;
import net.kaloyan.smpcore.config.InvalidConfigException;
import net.kaloyan.smpcore.log.DebugLogger;
import net.kaloyan.smpcore.player.PlayerLifecycleListener;
import net.kaloyan.smpcore.service.ServiceOwnerCleanup;
import net.kaloyan.smpcore.service.SimpleServiceRegistry;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SMP-Core plugin entry point. Wires the services together in a fixed order and records a cleanup for
 * each step, so a failed startup or a shutdown releases everything in reverse order. The public API
 * is published to other plugins as the very last step that can fail.
 */
public final class SMPCore extends JavaPlugin {

    private static final String COMMAND_DESCRIPTION = "SMP-Core information and administration";

    /** Non-null only while the plugin is fully started. */
    private Lifecycle lifecycle;

    @Override
    public void onEnable() {
        Lifecycle started = new Lifecycle(getLogger());
        try {
            start(started);
        } catch (InvalidConfigException e) {
            getLogger().severe("SMP-Core cannot start because config.yml is invalid. Fix the following and restart:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            abortStartup(started);
            return;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "SMP-Core failed to start: " + e.getMessage(), e);
            abortStartup(started);
            return;
        }
        lifecycle = started;
    }

    @Override
    public void onDisable() {
        if (lifecycle == null) {
            return; // startup failed and was already rolled back
        }
        lifecycle.closeAll();
        lifecycle = null;
        getLogger().info("SMP-Core disabled.");
    }

    private void abortStartup(Lifecycle partial) {
        partial.closeAll();
        getServer().getPluginManager().disablePlugin(this);
    }

    private void start(Lifecycle started) throws InvalidConfigException {
        DependencyValidator.requireAvailable(DependencyValidator::isClassPresent);

        saveDefaultConfig();
        Executor ioThread = task -> getServer().getScheduler().runTaskAsynchronously(this, task);
        Executor mainThread = task -> getServer().getScheduler().runTask(this, task);

        ConfigManager config = new ConfigManager(getDataFolder().toPath().resolve("config.yml"), ioThread, getLogger());
        CoreSettings initial = config.loadInitial();
        DebugLogger debug = new DebugLogger(getLogger(), config::current);
        debug.debug(() -> "Configuration loaded: " + initial);

        SimpleServiceRegistry services = new SimpleServiceRegistry();
        started.onClose("service registry", services::shutdown);

        CoreApi api = new CoreApi(getPluginMeta().getVersion(), config, services);
        started.onClose("API handle", api::invalidate);

        ServiceOwnerCleanup ownerCleanup = new ServiceOwnerCleanup(services, getLogger());
        PlayerLifecycleListener players = new PlayerLifecycleListener(getLogger(), config::current, debug);
        getServer().getPluginManager().registerEvents(ownerCleanup, this);
        started.onClose("service owner cleanup listener", () -> HandlerList.unregisterAll(ownerCleanup));
        getServer().getPluginManager().registerEvents(players, this);
        started.onClose("player listener", () -> HandlerList.unregisterAll(players));

        getServer().getServicesManager().register(SMPAPI.class, api, this, ServicePriority.Normal);
        started.onClose("API publication", () -> getServer().getServicesManager().unregister(SMPAPI.class, api));

        SmpCoreCommand command = new SmpCoreCommand(getPluginMeta().getVersion(), this::isEnabled, config,
                new Messages(config::current), mainThread, getLogger(), debug);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(command.build(), COMMAND_DESCRIPTION));

        getLogger().info("SMP-Core v" + getPluginMeta().getVersion() + " enabled on "
                + getServer().getName() + " " + getServer().getMinecraftVersion()
                + " (debug=" + initial.debug() + ")");
    }
}
