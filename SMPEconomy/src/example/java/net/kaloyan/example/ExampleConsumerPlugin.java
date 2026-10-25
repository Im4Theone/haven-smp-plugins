package net.kaloyan.example;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpeconomy.api.EconomyService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Example plugin that uses SMP-Economy. Its plugin.yml would contain
 * {@code depend: [SMP-Core, SMP-Economy]}. Not packaged in the SMP-Economy jar; it exists so that the
 * API usage documented in the README is compiled on every build.
 */
public final class ExampleConsumerPlugin extends JavaPlugin {

    private EconomyService economy;

    @Override
    public void onEnable() {
        // 1. SMP-Core must be enabled before any of its classes are used.
        if (!getServer().getPluginManager().isPluginEnabled("SMP-Core")) {
            getLogger().severe("SMP-Core is not enabled; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SMPAPI core = getServer().getServicesManager().load(SMPAPI.class);
        if (core == null) {
            getLogger().severe("SMP-Core API is not available; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 2. SMP-Economy publishes EconomyService through SMP-Core's service registry.
        Optional<EconomyService> found = core.getServices().find(EconomyService.class);
        if (found.isEmpty()) {
            getLogger().severe("SMP-Economy is not available; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = found.get();
    }

    /** Shows a player their balance. */
    public void showBalance(Player player) {
        ExamplePayments.describeBalance(economy, player.getUniqueId())
                // The future completes on SMP-Economy's storage thread: go back to the main thread for Bukkit calls.
                .thenAccept(text -> getServer().getScheduler().runTask(this, () -> player.sendMessage(text)));
    }

    /** Pays {@code amount} from {@code from} to the player with UUID {@code to}. */
    public void pay(Player from, UUID to, BigDecimal amount) {
        ExamplePayments.pay(economy, from.getUniqueId(), to, amount)
                .thenAccept(text -> getServer().getScheduler().runTask(this, () -> from.sendMessage(text)));
    }
}
