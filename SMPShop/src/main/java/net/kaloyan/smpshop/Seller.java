package net.kaloyan.smpshop;

import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/**
 * The purchases. Money and shards are always taken first and the goods handed over second; whatever could not be handed
 * over is paid back. Everything runs on the main thread, so nothing interleaves. A payment the economy did not confirm
 * is never refunded or retried.
 */
public final class Seller {

    /** How a purchase ended. */
    public enum Status {
        /** Bought. */
        DONE,
        /** Not enough money or shards. */
        BROKE,
        /** The inventory had no room at all; nothing was charged. */
        NO_ROOM,
        /** The economy or the plugin that delivers is unavailable; nothing was charged. */
        UNAVAILABLE,
        /** The economy did not confirm; the player may or may not have been charged. */
        UNSURE
    }

    /**
     * @param status  what happened
     * @param given   how many were delivered
     * @param charged what that cost, in hundredths of money or in shards
     */
    public record Outcome(Status status, int given, long charged) {
    }

    private final Bank bank;
    private final ShardManager shards;
    private final Deliveries deliveries;
    private final Logger log;

    /**
     * @param bank       money
     * @param shards     shards
     * @param deliveries the goods
     * @param log        where odd things are reported
     */
    public Seller(Bank bank, ShardManager shards, Deliveries deliveries, Logger log) {
        this.bank = bank;
        this.shards = shards;
        this.deliveries = deliveries;
        this.log = log;
    }

    /**
     * Buys items for money.
     *
     * @param player who buys
     * @param entry  what
     * @param amount how many, 1 or more
     * @return the outcome
     */
    public Outcome buyItem(Player player, Catalog.Entry entry, int amount) {
        long total;
        try {
            total = Math.multiplyExact(entry.cents(), (long) amount);
        } catch (ArithmeticException e) {
            return new Outcome(Status.UNAVAILABLE, 0, 0);
        }
        switch (bank.withdraw(player, total)) {
            case INSUFFICIENT:
                return new Outcome(Status.BROKE, 0, 0);
            case FAILED:
                return new Outcome(Status.UNAVAILABLE, 0, 0);
            case UNCERTAIN:
                log.warning("Payment of " + total + " cents by " + player.getName() + " was not confirmed; nothing was delivered.");
                return new Outcome(Status.UNSURE, 0, 0);
            default:
                break;
        }
        int left = deliveries.items(player, entry.material(), amount);
        int given = amount - left;
        if (left > 0) {
            Bank.Result refund = bank.deposit(player, entry.cents() * left);
            if (refund != Bank.Result.PAID) {
                log.severe("Could not refund " + entry.cents() * left + " cents to " + player.getName() + " (" + refund + ").");
            }
        }
        return given == 0 ? new Outcome(Status.NO_ROOM, 0, 0) : new Outcome(Status.DONE, given, entry.cents() * given);
    }

    /**
     * Buys crates or spawners for shards.
     *
     * @param player who buys
     * @param offer  what
     * @param amount how many, 1 or more
     * @return the outcome
     */
    public Outcome buyOffer(Player player, Catalog.Offer offer, int amount) {
        UUID id = player.getUniqueId();
        long total;
        try {
            total = Math.multiplyExact(offer.shards(), (long) amount);
        } catch (ArithmeticException e) {
            return new Outcome(Status.UNAVAILABLE, 0, 0);
        }
        if (!shards.tryTake(id, total)) {
            return new Outcome(Status.BROKE, 0, 0);
        }
        boolean given = offer.kind() == Catalog.Kind.KEY
                ? deliveries.crates(id, offer.id(), amount)
                : deliveries.spawners(id, offer.id(), amount);
        if (!given) {
            shards.add(id, total);
            return new Outcome(Status.UNAVAILABLE, 0, 0);
        }
        return new Outcome(Status.DONE, amount, total);
    }
}
