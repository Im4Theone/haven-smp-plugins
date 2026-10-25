package net.kaloyan.smpvault;

import java.util.Optional;
import java.util.logging.Level;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers SMP-Economy as the Vault economy so every plugin that uses Vault is paid through SMP-Economy.
 * It is enabled after Vault, SMP-Core and SMP-Economy and before the plugins that list it in {@code loadbefore}.
 */
public final class SMPVaultBridge extends JavaPlugin {

    private static final long MAIN_WAIT_MS = 2_000;
    private static final long OTHER_WAIT_MS = 10_000;

    private Economy provider;

    @Override
    public void onEnable() {
        for (String dependency : new String[] {"SMP-Core", "SMP-Economy", "Vault"}) {
            if (!getServer().getPluginManager().isPluginEnabled(dependency)) {
                getLogger().severe(dependency + " is not enabled; disabling SMP-VaultBridge.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        SMPAPI core = getServer().getServicesManager().load(SMPAPI.class);
        Optional<EconomyService> economy = core == null ? Optional.empty() : core.getServices().find(EconomyService.class);
        if (economy.isEmpty()) {
            getLogger().severe("SMP-Economy did not publish its EconomyService; disabling SMP-VaultBridge.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        provider = new SmpVaultEconomy(economy.get(), getLogger(), Bukkit::isPrimaryThread, name -> {
            OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
            return Optional.ofNullable(known).map(OfflinePlayer::getUniqueId);
        }, MAIN_WAIT_MS, OTHER_WAIT_MS);
        getServer().getServicesManager().register(Economy.class, provider, this, ServicePriority.Highest);
        getLogger().info("Vault economy is now SMP-Economy (" + economy.get().getCurrencySymbol() + ").");
    }

    @Override
    public void onDisable() {
        if (provider != null) {
            try {
                getServer().getServicesManager().unregister(Economy.class, provider);
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not unregister the Vault economy", e);
            }
            provider = null;
        }
    }
}
