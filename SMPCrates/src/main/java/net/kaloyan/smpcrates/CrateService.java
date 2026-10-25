package net.kaloyan.smpcrates;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The rules: handing out crates, the hourly round, and opening. Contains no Bukkit calls except through
 * {@link Delivery}, so it can be tested without a server. Main thread only.
 */
public final class CrateService {

    /** How a reward reaches a player. */
    public interface Delivery {
        /** @return {@code true} if the reward can be handed over right now (e.g. there is room in the inventory) */
        boolean canDeliver(UUID player, Reward reward);

        /** @return {@code true} if the reward was handed over; {@code false} if it was not (nothing was given) */
        boolean deliver(UUID player, Reward reward);
    }

    /** What happened when someone tried to open a crate. */
    public enum Outcome { OPENED, UNKNOWN_CRATE, NONE_LEFT, CANNOT_DELIVER, DELIVERY_FAILED, SAVE_FAILED }

    /**
     * Result of {@link #open}.
     *
     * @param outcome what happened
     * @param reward  the reward, only when {@code outcome} is {@link Outcome#OPENED}
     */
    public record OpenResult(Outcome outcome, Reward reward) {
    }

    private final Supplier<CratesSettings> settings;
    private final CrateStore store;
    private final Delivery delivery;
    private final Random random;
    private final LongSupplier clock;

    /**
     * @param settings the current settings
     * @param store    balances
     * @param delivery hands rewards to players
     * @param random   the random source
     * @param clock    current time in epoch milliseconds
     */
    public CrateService(Supplier<CratesSettings> settings, CrateStore store, Delivery delivery, Random random, LongSupplier clock) {
        this.settings = settings;
        this.store = store;
        this.delivery = delivery;
        this.random = random;
        this.clock = clock;
    }

    /** @return the clock that defines the hourly rounds */
    public HourlyClock hourly() {
        return new HourlyClock(settings.get().intervalMinutes());
    }

    /**
     * Gives crates (from the shop, an admin or a reward).
     *
     * @return {@code false} if the crate type does not exist or the amount is not positive; the player gets nothing then
     * @throws IOException if the data could not be saved; the player gets nothing then
     */
    public boolean give(UUID player, String type, int amount) throws IOException {
        if (amount < 1 || !settings.get().crates().containsKey(type)) {
            return false;
        }
        store.add(player, type, amount);
        return true;
    }

    /**
     * Gives the same crates to a group of players (a keyall by hand).
     *
     * @return {@code false} if the crate type does not exist or the amount is not positive
     * @throws IOException if the data could not be saved; nobody got anything then
     */
    public boolean giveAll(Collection<UUID> players, String type, int amount) throws IOException {
        if (amount < 1 || !settings.get().crates().containsKey(type)) {
            return false;
        }
        store.addToAll(players, type, amount);
        return true;
    }

    /**
     * Gives the free crate of the current round to every player in the list who has not had it. Call every few seconds with the
     * online players and again whenever somebody joins.
     *
     * @param players the players to consider
     * @return the players who got one
     * @throws IOException if the data could not be saved; nobody got one then
     */
    public List<UUID> hourly(Collection<UUID> players) throws IOException {
        CratesSettings s = settings.get();
        if (!s.hourlyEnabled()) {
            return List.of();
        }
        return store.grantRound(players, s.hourlyCrate(), hourly().round(clock.getAsLong()));
    }

    /**
     * Opens one crate. The crate is taken and the reward handed over in one step (the animation shown to the player is only
     * decoration of an outcome that is already final), so a crash can never eat a crate or give a reward twice. If handing
     * over fails the crate is given back.
     *
     * @param player the player
     * @param type   the crate type
     * @return what happened
     */
    public OpenResult open(UUID player, String type) {
        CrateType crate = settings.get().crates().get(type);
        if (crate == null) {
            return new OpenResult(Outcome.UNKNOWN_CRATE, null);
        }
        if (store.balance(player, type) < 1) {
            return new OpenResult(Outcome.NONE_LEFT, null);
        }
        Reward reward = crate.pick(random);
        if (!delivery.canDeliver(player, reward)) {
            return new OpenResult(Outcome.CANNOT_DELIVER, reward);
        }
        try {
            if (!store.tryTake(player, type, 1)) {
                return new OpenResult(Outcome.NONE_LEFT, null);
            }
        } catch (IOException e) {
            return new OpenResult(Outcome.SAVE_FAILED, null);
        }
        boolean delivered;
        try {
            delivered = delivery.deliver(player, reward);
        } catch (RuntimeException e) {
            delivered = false;
        }
        if (!delivered) {
            try {
                store.add(player, type, 1);
            } catch (IOException e) {
                // The crate is gone and the reward was not given; the caller logs this loudly.
                return new OpenResult(Outcome.SAVE_FAILED, reward);
            }
            return new OpenResult(Outcome.DELIVERY_FAILED, reward);
        }
        return new OpenResult(Outcome.OPENED, reward);
    }

    /** @return the crate type, if it exists */
    public Optional<CrateType> type(String id) {
        return Optional.ofNullable(settings.get().crates().get(id));
    }
}
