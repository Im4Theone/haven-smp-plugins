package net.kaloyan.smpshop;

import java.util.Locale;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Money through Vault, which is your SMP-Economy (SMP-VaultBridge). Amounts are in hundredths. */
public interface Bank {

    /** What happened to a payment. */
    enum Result {
        /** Done. */
        PAID,
        /** The player does not have the money. */
        INSUFFICIENT,
        /** Definitely not done. */
        FAILED,
        /** The economy did not answer in time: it may or may not have happened, so nobody is refunded or charged again. */
        UNCERTAIN
    }

    /**
     * @param player who pays
     * @param cents  a positive amount
     * @return the outcome
     */
    Result withdraw(Player player, long cents);

    /**
     * @param player who is paid
     * @param cents  a positive amount
     * @return the outcome
     */
    Result deposit(Player player, long cents);

    /** @return the implementation that uses the Vault economy */
    static Bank vault() {
        return new Bank() {
            private Economy economy() {
                RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
                return provider == null ? null : provider.getProvider();
            }

            private Result outcome(EconomyResponse response) {
                if (response.transactionSuccess()) {
                    return Result.PAID;
                }
                String error = response.errorMessage == null ? "" : response.errorMessage.toUpperCase(Locale.ROOT);
                return error.contains("UNCONFIRMED") ? Result.UNCERTAIN : Result.FAILED;
            }

            @Override
            public Result withdraw(Player player, long cents) {
                Economy e = economy();
                if (e == null || cents <= 0) {
                    return Result.FAILED;
                }
                if (!e.has(player, cents / 100.0)) {
                    return Result.INSUFFICIENT;
                }
                return outcome(e.withdrawPlayer(player, cents / 100.0));
            }

            @Override
            public Result deposit(Player player, long cents) {
                Economy e = economy();
                return e == null || cents <= 0 ? Result.FAILED : outcome(e.depositPlayer(player, cents / 100.0));
            }
        };
    }
}
