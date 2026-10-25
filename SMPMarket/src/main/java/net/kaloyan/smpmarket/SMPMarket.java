package net.kaloyan.smpmarket;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpmarket.bukkit.BukkitItemGateway;
import net.kaloyan.smpmarket.bukkit.MarketListener;
import net.kaloyan.smpmarket.command.MarketCommands;
import net.kaloyan.smpmarket.command.Messages;
import net.kaloyan.smpmarket.config.ConfigManager;
import net.kaloyan.smpmarket.config.InvalidConfigException;
import net.kaloyan.smpmarket.config.MarketSettings;
import net.kaloyan.smpmarket.service.MarketService;
import net.kaloyan.smpmarket.storage.MarketStore;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * SMP-Market plugin entry point. Wires the parts together in a fixed order and records a cleanup for each step,
 * so a failed startup or a shutdown releases everything in reverse order.
 *
 * <p>Shutdown order (reverse of startup): stop the listener and the sweep timer, wait briefly for payments that are
 * already in flight, drain the storage thread, conclude open delivery claims, close the database.
 */
public final class SMPMarket extends JavaPlugin {

    private static final String CORE_PLUGIN_NAME = "SMP-Core";
    private static final String ECONOMY_PLUGIN_NAME = "SMP-Economy";
    private static final String DATABASE_FILE = "market.db";
    private static final long STORAGE_DRAIN_SECONDS = 30;
    private static final long QUIESCE_SECONDS = 10;
    private static final long SWEEP_INTERVAL_TICKS = 20L * 60;
    private static final String FALLBACK_PREFIX = "<dark_aqua>[SMP]</dark_aqua>";

    /** Non-null only while the plugin is fully started. */
    private Lifecycle lifecycle;

    @Override
    public void onEnable() {
        // Check before any dependency class is used: depend only guarantees the jars are present.
        for (String dependency : List.of(CORE_PLUGIN_NAME, ECONOMY_PLUGIN_NAME)) {
            if (!getServer().getPluginManager().isPluginEnabled(dependency)) {
                getLogger().severe(dependency + " is not enabled; disabling SMP-Market.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        Lifecycle started = new Lifecycle(getLogger());
        try {
            start(started);
        } catch (InvalidConfigException e) {
            getLogger().severe("SMP-Market cannot start because config.yml is invalid. Fix the following and restart:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            abortStartup(started);
            return;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "SMP-Market failed to start: " + e.getMessage(), e);
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
        getLogger().info("SMP-Market disabled.");
    }

    private void abortStartup(Lifecycle partial) {
        partial.closeAll();
        getServer().getPluginManager().disablePlugin(this);
    }

    private void start(Lifecycle started) throws InvalidConfigException {
        DependencyValidator.requireAvailable(DependencyValidator::isClassPresent);

        SMPAPI core = getServer().getServicesManager().load(SMPAPI.class);
        if (core == null) {
            throw new IllegalStateException("The SMP-Core API is not available");
        }
        Optional<EconomyService> economy = core.getServices().find(EconomyService.class);
        if (economy.isEmpty()) {
            throw new IllegalStateException("SMP-Economy did not publish its EconomyService (did it fail to start?)");
        }

        saveDefaultConfig();
        Executor mainThread = task -> {
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, task);
            }
        };
        Executor ioThread = task -> getServer().getScheduler().runTaskAsynchronously(this, task);

        ConfigManager config = new ConfigManager(getDataFolder().toPath().resolve("config.yml"), ioThread, getLogger());
        MarketSettings initial = config.loadInitial();
        if (initial.debug()) {
            getLogger().info("[DEBUG] Configuration loaded: " + initial);
        }

        MarketStore store = MarketStore.open(getDataFolder().toPath().resolve(DATABASE_FILE));
        started.onClose("database", store::close);

        ExecutorService storageThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "SMP-Market-Storage");
            thread.setDaemon(false); // pending state changes must finish before the JVM exits
            return thread;
        });

        MarketService service = new MarketService(store, economy.get(), new BukkitItemGateway(getServer(), getLogger()),
                config::current, storageThread, mainThread, Clock.systemUTC(), getLogger());

        // Shutdown steps, run newest first: drain storage, then conclude claims, then close the database.
        started.onClose("open delivery claims", () -> service.concludeOpenClaims());
        started.onClose("storage thread", () -> {
            service.awaitQuiescent(QUIESCE_SECONDS, TimeUnit.SECONDS);
            drain(storageThread);
        });

        // Recovery is queued first; the single storage thread runs it before anything submitted later.
        service.recoverOnStartup().whenComplete((report, error) -> {
            if (error != null) {
                getLogger().log(Level.SEVERE, "Startup recovery failed; the market may be inconsistent until the "
                        + "problem is fixed. Use /market admin issues.", error);
            } else if (report.needsAttention()) {
                getLogger().warning("Startup recovery found items that need an administrator: /market admin issues");
            }
        });

        Messages messages = new Messages(() -> corePrefix(core));
        MarketListener listener = new MarketListener(service, messages, mainThread, getLogger());
        getServer().getPluginManager().registerEvents(listener, this);
        started.onClose("join listener", () -> HandlerList.unregisterAll(listener));

        BukkitTask sweep = getServer().getScheduler().runTaskTimer(this, service::sweep, SWEEP_INTERVAL_TICKS,
                SWEEP_INTERVAL_TICKS);
        started.onClose("sweep timer", sweep::cancel);

        MarketCommands commands = new MarketCommands(service, config, messages, mainThread, Clock.systemUTC(), getLogger());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            registrar.register(commands.build(), "Player auction house", List.of("ah"));
        });

        getLogger().info("SMP-Market v" + getPluginMeta().getVersion() + " enabled (database "
                + getDataFolder().toPath().resolve(DATABASE_FILE) + ")");
    }

    /** Uses SMP-Core's chat prefix so messages look like the rest of the SMP; falls back if Core is gone. */
    private static String corePrefix(SMPAPI core) {
        try {
            return core.getSettings().prefix();
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return FALLBACK_PREFIX;
        }
    }

    private void drain(ExecutorService storageThread) {
        storageThread.shutdown();
        try {
            if (!storageThread.awaitTermination(STORAGE_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                int dropped = storageThread.shutdownNow().size();
                getLogger().severe("Market storage did not finish within " + STORAGE_DRAIN_SECONDS + "s; "
                        + dropped + " queued operation(s) were dropped. They will be recovered as uncertain on next start.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            int dropped = storageThread.shutdownNow().size();
            getLogger().severe("Interrupted while draining market storage; " + dropped + " queued operation(s) were dropped.");
        }
    }
}
