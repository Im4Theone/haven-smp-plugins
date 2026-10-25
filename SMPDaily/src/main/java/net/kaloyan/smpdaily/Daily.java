package net.kaloyan.smpdaily;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Claiming a reward. The claim is saved first; then the money is paid (if the economy definitely refuses, the claim is undone and nothing
 * else is given); then shards and crates are given. A crash can lose a reward but never gives it twice.
 */
public final class Daily {

    /** How a claim ended. */
    public enum Status {
        /** Claimed. */
        OK,
        /** Not ready yet. */
        WAIT,
        /** The money could not be paid; nothing was claimed. */
        PAYMENT_FAILED,
        /** Saving failed; nothing was claimed. */
        SAVE_FAILED
    }

    /**
     * @param status    how it ended
     * @param day       the day that was claimed (1-based), or the day that will be claimed next
     * @param remaining for {@code WAIT}: milliseconds until the next claim
     * @param lostStreak whether the streak was lost and started again at day 1
     * @param given     a readable summary of what was given
     */
    public record Result(Status status, int day, long remaining, boolean lostStreak, String given) {
    }

    private final ClaimStore store;
    private final Supplier<Settings> settings;
    private final Logger log;

    /**
     * @param store    the claims
     * @param settings the current settings
     * @param log      where problems are reported
     */
    public Daily(ClaimStore store, Supplier<Settings> settings, Logger log) {
        this.store = store;
        this.settings = settings;
        this.log = log;
    }

    /** @return the claims */
    public ClaimStore store() {
        return store;
    }

    /**
     * @param reward a reward
     * @return for example {@code $2,500, 2 shards, 1 common crate}
     */
    public static String describe(Settings.Reward reward) {
        List<String> parts = new ArrayList<>();
        if (reward.cents() > 0) {
            parts.add("$" + new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(reward.cents() / 100.0));
        }
        if (reward.shards() > 0) {
            parts.add(reward.shards() + " shard" + (reward.shards() == 1 ? "" : "s"));
        }
        reward.crates().forEach((id, n) -> parts.add(n + " " + id + " crate" + (n == 1 ? "" : "s")));
        return String.join(", ", parts);
    }

    /**
     * Claims the reward.
     *
     * @param player who claims
     * @param now    the time
     * @return how it ended
     */
    public Result claim(Player player, long now) {
        Settings s = settings.get();
        UUID id = player.getUniqueId();
        ClaimStore.State before = store.get(id);
        if (!Streak.ready(before.last(), now)) {
            return new Result(Status.WAIT, Streak.nextDay(before.last(), before.day(), now, s.days().size()), Streak.until(before.last(), now), false, "");
        }
        boolean lost = Streak.lost(before.last(), now) && before.day() > 1;
        int day = Streak.nextDay(before.last(), before.day(), now, s.days().size());
        Settings.Reward reward = s.days().get(day - 1);
        try {
            store.set(id, new ClaimStore.State(day, now));
        } catch (IOException e) {
            log.severe("Could not save daily.yml: " + e.getMessage());
            return new Result(Status.SAVE_FAILED, day, 0, false, "");
        }
        if (reward.cents() > 0 && !pay(player, reward.cents())) {
            try {
                store.set(id, before);
            } catch (IOException e) {
                log.severe("Could not undo the claim of " + player.getName() + " in daily.yml: " + e.getMessage());
            }
            return new Result(Status.PAYMENT_FAILED, day, 0, false, "");
        }
        if (reward.shards() > 0 && !shards(id, reward.shards())) {
            log.warning("SMP-Shop is not available, so " + player.getName() + " did not get " + reward.shards() + " shard(s).");
        }
        reward.crates().forEach((crate, n) -> {
            if (!crates(id, crate, n)) {
                log.warning("SMP-Crates is not available, so " + player.getName() + " did not get " + n + " " + crate + " crate(s).");
            }
        });
        return new Result(Status.OK, day, 0, lost, describe(reward));
    }

    private boolean pay(Player player, long cents) {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return false;
        }
        EconomyResponse response = provider.getProvider().depositPlayer(player, cents / 100.0);
        if (response.transactionSuccess()) {
            return true;
        }
        String error = response.errorMessage == null ? "" : response.errorMessage.toUpperCase(Locale.ROOT);
        if (error.contains("UNCONFIRMED")) {
            log.severe("Paying " + cents + " cents to " + player.getName() + " (daily reward) was not confirmed; it is not retried. Check their balance.");
            return true;
        }
        return false;
    }

    private boolean shards(UUID player, long amount) {
        Plugin shop = Bukkit.getPluginManager().getPlugin("SMP-Shop");
        if (shop == null || !shop.isEnabled()) {
            return false;
        }
        try {
            Object manager = shop.getClass().getMethod("getShardManager").invoke(shop);
            manager.getClass().getMethod("add", UUID.class, long.class).invoke(manager, player, amount);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private boolean crates(UUID player, String crate, int amount) {
        Plugin crates = Bukkit.getPluginManager().getPlugin("SMP-Crates");
        if (crates == null || !crates.isEnabled()) {
            return false;
        }
        try {
            return (boolean) crates.getClass().getMethod("giveCrates", UUID.class, String.class, int.class).invoke(crates, player, crate, amount);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
