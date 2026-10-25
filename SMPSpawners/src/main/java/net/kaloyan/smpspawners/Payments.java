package net.kaloyan.smpspawners;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Pays players through Vault, which is your SMP-Economy (SMP-VaultBridge). */
public final class Payments {

    /** What happened to a payment. */
    public enum Result {
        /** The money was paid. */
        PAID,
        /** The money was definitely not paid. */
        FAILED,
        /** The economy did not answer in time: the money may or may not have been paid, so nobody may be refunded or paid again. */
        UNCERTAIN
    }

    /** @return {@code true} if an economy is available */
    public boolean available() {
        return economy() != null;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    /**
     * @param player who is paid
     * @param cents  the amount in hundredths of the currency, positive
     * @return the outcome
     */
    public Result deposit(Player player, long cents) {
        Economy economy = economy();
        if (economy == null || cents <= 0) {
            return Result.FAILED;
        }
        EconomyResponse response = economy.depositPlayer(player, cents / 100.0);
        if (response.transactionSuccess()) {
            return Result.PAID;
        }
        String error = response.errorMessage == null ? "" : response.errorMessage.toUpperCase(java.util.Locale.ROOT);
        return error.contains("UNCONFIRMED") ? Result.UNCERTAIN : Result.FAILED;
    }
}
