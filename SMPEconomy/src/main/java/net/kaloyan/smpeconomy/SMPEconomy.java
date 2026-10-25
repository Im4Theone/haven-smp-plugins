package net.kaloyan.smpeconomy;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kaloyan.smpeconomy.command.EconomyCommands;
import net.kaloyan.smpeconomy.command.Messages;
import net.kaloyan.smpeconomy.config.ConfigManager;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.config.InvalidConfigException;
import net.kaloyan.smpeconomy.log.DebugLogger;
import net.kaloyan.smpeconomy.player.AccountInitListener;
import net.kaloyan.smpeconomy.service.EconomyServiceImpl;
import net.kaloyan.smpeconomy.storage.EconomyStore;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SMP-Economy plugin entry point. Wires the parts together in a fixed order and records a cleanup for each
 * step, so a failed startup or a shutdown releases everything in reverse order. The public service is
 * published through SMP-Core as the very last step that can fail.
 */
public final class SMPEconomy extends JavaPlugin {

    private static final String CORE_PLUGIN_NAME = "SMP-Core";
    private static final String DATABASE_FILE = "economy.db";
    private static final long STORAGE_DRAIN_SECONDS = 30;
    private static final String FALLBACK_PREFIX = "<dark_aqua>[SMP]</dark_aqua>";

    /** Non-null only while the plugin is fully started. */
    private Lifecycle lifecycle;

    @Override
    public void onEnable() {
        // Check before any SMP-Core class is used: depend only guarantees the jar is present.
        if (!getServer().getPluginManager().isPluginEnabled(CORE_PLUGIN_NAME)) {
            getLogger().severe(CORE_PLUGIN_NAME + " is not enabled; disabling SMP-Economy.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        enableWithCore();
    }

    private void enableWithCore() {
        SMPAPI core = getServer().getServicesManager().load(SMPAPI.class);
        if (core == null) {
            getLogger().severe("The SMP-Core API is not available; disabling SMP-Economy.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Lifecycle started = new Lifecycle(getLogger());
        try {
            start(started, core);
        } catch (InvalidConfigException e) {
            getLogger().severe("SMP-Economy cannot start because config.yml is invalid. Fix the following and restart:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            abortStartup(started);
            return;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "SMP-Economy failed to start: " + e.getMessage(), e);
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
        getLogger().info("SMP-Economy disabled.");
    }

    private void abortStartup(Lifecycle partial) {
        partial.closeAll();
        getServer().getPluginManager().disablePlugin(this);
    }

    private void start(Lifecycle started, SMPAPI core) throws InvalidConfigException {
        DependencyValidator.requireAvailable(DependencyValidator::isClassPresent);

        saveDefaultConfig();
        Executor mainThread = task -> {
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, task);
            }
        };
        Executor ioThread = task -> getServer().getScheduler().runTaskAsynchronously(this, task);

        ConfigManager config = new ConfigManager(getDataFolder().toPath().resolve("config.yml"), ioThread, getLogger());
        EconomySettings initial = config.loadInitial();
        DebugLogger debug = new DebugLogger(getLogger(), config::current);
        debug.debug(() -> "Configuration loaded: " + initial);

        EconomyStore store = EconomyStore.open(getDataFolder().toPath().resolve(DATABASE_FILE), config::current, getLogger());
        started.onClose("database", store::close);

        ExecutorService storageThread = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "SMP-Economy-Storage");
            thread.setDaemon(false); // pending transactions must finish before the JVM exits
            return thread;
        });
        // Runs before the database is closed (cleanups run in reverse order): queued work is finished first.
        started.onClose("storage thread", () -> drain(storageThread));

        EconomyServiceImpl service = new EconomyServiceImpl(store, storageThread, config::current, debug);

        AccountInitListener joins = new AccountInitListener(service, getLogger());
        getServer().getPluginManager().registerEvents(joins, this);
        started.onClose("join listener", () -> HandlerList.unregisterAll(joins));

        Messages messages = new Messages(() -> corePrefix(core));
        EconomyCommands commands = new EconomyCommands(getServer(), service, config, messages, mainThread, getLogger());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            registrar.register(commands.buildBalance(), "View a balance", List.of("bal"));
            registrar.register(commands.buildPay(), "Pay another player");
            registrar.register(commands.buildEco(), "Economy administration");
        });

        core.getServices().register(this, EconomyService.class, service);
        started.onClose("economy service registration", () -> core.getServices().unregister(this, EconomyService.class));

        getLogger().info("SMP-Economy v" + getPluginMeta().getVersion() + " enabled (currency "
                + initial.symbol() + ", database " + getDataFolder().toPath().resolve(DATABASE_FILE) + ")");
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
                getLogger().severe("Economy storage did not finish within " + STORAGE_DRAIN_SECONDS + "s; "
                        + dropped + " queued operation(s) were dropped.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            int dropped = storageThread.shutdownNow().size();
            getLogger().severe("Interrupted while draining economy storage; " + dropped
                    + " queued operation(s) were dropped.");
        }
    }
}
