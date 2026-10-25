package net.kaloyan.smporders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The rules of the marketplace. Money rules: a buyer's money is taken first and the order saved second; a payout is removed from
 * the owed list and saved first and paid second. So a crash can lose a payout but never pays twice, and a payment the economy did
 * not confirm is never retried. Every state change is saved before the method returns and undone if saving fails.
 */
public final class OrderService {

    /** How an operation ended. */
    public enum Status {
        /** Done. */
        OK,
        /** The player has too many open orders. */
        MAX_ORDERS,
        /** The price is outside the allowed range. */
        BAD_PRICE,
        /** The amount is outside the allowed range. */
        BAD_AMOUNT,
        /** That item cannot be ordered. */
        BLOCKED,
        /** Not enough money. */
        BROKE,
        /** The economy is unavailable; nothing happened. */
        UNAVAILABLE,
        /** The economy did not confirm; the buyer may or may not have been charged. */
        UNSURE,
        /** Saving failed; nothing happened. */
        SAVE_FAILED,
        /** The order is not open any more. */
        NOT_OPEN,
        /** Players cannot deliver to their own order. */
        OWN_ORDER,
        /** The order belongs to someone else. */
        NOT_OWNER,
        /** There was nothing to do. */
        NOTHING
    }

    /**
     * @param status  how it ended
     * @param order   the order concerned, if any
     * @param cents   the money involved in hundredths (charged, refunded or paid), or the number of items collected
     * @param pending {@code true} if money is owed but could not be paid out right now; it is paid later
     */
    public record Result(Status status, Order order, long cents, boolean pending) {
        static Result of(Status status) {
            return new Result(status, null, 0, false);
        }
    }

    private final OrderStore store;
    private final Bank bank;
    private final Supplier<Settings> settings;
    private final Logger log;
    private final LongSupplier clock;

    /**
     * @param store    the orders
     * @param bank     money
     * @param settings the current settings
     * @param log      where problems are reported
     * @param clock    epoch milliseconds
     */
    public OrderService(OrderStore store, Bank bank, Supplier<Settings> settings, Logger log, LongSupplier clock) {
        this.store = store;
        this.bank = bank;
        this.settings = settings;
        this.log = log;
        this.clock = clock;
    }

    /** @return the store */
    public OrderStore store() {
        return store;
    }

    // ---------------------------------------------------------------- creating

    /**
     * Makes a buy order: the money for all items plus the tax is taken now.
     *
     * @param owner      the buyer
     * @param ownerName  the buyer's name
     * @param key        what is wanted
     * @param amount     how many
     * @param priceCents the price of one item in hundredths
     * @return the result; {@code cents} is what the buyer was charged, tax included
     */
    public Result create(UUID owner, String ownerName, ItemKey key, int amount, long priceCents) {
        Settings s = settings.get();
        if (s.blocked().contains(key.material())) {
            return Result.of(Status.BLOCKED);
        }
        if (amount < 1 || amount > s.maxAmount()) {
            return Result.of(Status.BAD_AMOUNT);
        }
        if (priceCents < s.minPriceCents() || priceCents > s.maxPriceCents()) {
            return Result.of(Status.BAD_PRICE);
        }
        if (store.openCount(owner) >= s.maxActive()) {
            return Result.of(Status.MAX_ORDERS);
        }
        long total = Math.multiplyExact(priceCents, (long) amount);
        long charge = total + s.tax(total);
        switch (bank.withdraw(owner, charge)) {
            case INSUFFICIENT:
                return Result.of(Status.BROKE);
            case FAILED:
                return Result.of(Status.UNAVAILABLE);
            case UNCERTAIN:
                log.warning("Payment of " + charge + " cents by " + ownerName + " for a new order was not confirmed; no order was made.");
                return Result.of(Status.UNSURE);
            default:
                break;
        }
        long now = clock.getAsLong();
        Order order = new Order(store.nextId(), owner, ownerName, key, amount, priceCents, now, now + s.expiryDays() * 86_400_000L);
        store.put(order);
        try {
            store.commit(() -> store.drop(order.id()));
        } catch (IOException e) {
            log.severe("Could not save orders.yml: " + e.getMessage());
            if (bank.deposit(owner, charge) != Bank.Result.PAID) {
                log.severe("Could not give " + charge + " cents back to " + ownerName + " after a failed save; please do it by hand.");
            }
            return Result.of(Status.SAVE_FAILED);
        }
        return new Result(Status.OK, order, charge, false);
    }

    // ---------------------------------------------------------------- delivering

    /**
     * Records a delivery and pays the deliverer from the buyer's escrow. The caller has already taken the items from the player and
     * gives them back if this does not return {@code OK}.
     *
     * @param deliverer who delivers
     * @param order     the order
     * @param count     how many items
     * @return the result; {@code cents} is what the deliverer earns
     */
    public Result deliver(UUID deliverer, Order order, int count) {
        if (!order.open() || store.get(order.id()) != order) {
            return Result.of(Status.NOT_OPEN);
        }
        if (order.owner().equals(deliverer)) {
            return Result.of(Status.OWN_ORDER);
        }
        if (count < 1 || count > order.remaining()) {
            return Result.of(Status.BAD_AMOUNT);
        }
        int filled = order.filled();
        int waiting = order.collectable();
        Order.Closed closed = order.closed();
        long earned = order.priceCents() * count;
        order.filled(filled + count);
        order.collectable(waiting + count);
        if (order.filled() == order.amount()) {
            order.closed(Order.Closed.COMPLETE);
        }
        store.owe(deliverer, earned);
        try {
            store.commit(() -> {
                order.filled(filled);
                order.collectable(waiting);
                order.closed(closed);
                undoOwe(deliverer, earned);
            });
        } catch (IOException e) {
            log.severe("Could not save orders.yml: " + e.getMessage());
            return Result.of(Status.SAVE_FAILED);
        }
        boolean paid = payout(deliverer);
        return new Result(Status.OK, order, earned, !paid);
    }

    // ---------------------------------------------------------------- cancelling

    /**
     * Cancels an open order and refunds the price of the items not yet delivered (the tax is not refunded). Items already
     * delivered stay available to collect.
     *
     * @param actor who cancels
     * @param admin whether the actor may cancel other players' orders
     * @param order the order
     * @return the result; {@code cents} is the refund
     */
    public Result cancel(UUID actor, boolean admin, Order order) {
        if (!order.owner().equals(actor) && !admin) {
            return Result.of(Status.NOT_OWNER);
        }
        if (!order.open() || store.get(order.id()) != order) {
            return Result.of(Status.NOT_OPEN);
        }
        return close(order, Order.Closed.CANCELLED);
    }

    private Result close(Order order, Order.Closed reason) {
        long refund = order.escrowCents();
        Order.Closed before = order.closed();
        order.closed(reason);
        boolean pruned = order.collectable() == 0;
        if (pruned) {
            store.drop(order.id());
        }
        if (refund > 0) {
            store.owe(order.owner(), refund);
        }
        try {
            store.commit(() -> {
                order.closed(before);
                if (pruned) {
                    store.put(order);
                }
                undoOwe(order.owner(), refund);
            });
        } catch (IOException e) {
            log.severe("Could not save orders.yml: " + e.getMessage());
            return Result.of(Status.SAVE_FAILED);
        }
        boolean paid = refund == 0 || payout(order.owner());
        return new Result(Status.OK, order, refund, !paid);
    }

    private void undoOwe(UUID player, long cents) {
        if (cents <= 0) {
            return;
        }
        long left = store.takeOwed(player) - cents;
        if (left > 0) {
            store.owe(player, left);
        }
    }

    // ---------------------------------------------------------------- collecting

    /**
     * Hands the delivered items to their buyer.
     *
     * @param actor who collects
     * @param order the order
     * @param give  hands out that many items and returns how many did not fit
     * @return the result; {@code cents} is the number of items handed over
     */
    public Result collect(UUID actor, Order order, IntUnaryOperator give) {
        if (!order.owner().equals(actor)) {
            return Result.of(Status.NOT_OWNER);
        }
        int n = order.collectable();
        if (n == 0 || store.get(order.id()) != order) {
            return Result.of(Status.NOTHING);
        }
        order.collectable(0);
        boolean pruned = order.closed() != Order.Closed.NO;
        if (pruned) {
            store.drop(order.id());
        }
        try {
            store.commit(() -> {
                order.collectable(n);
                if (pruned) {
                    store.put(order);
                }
            });
        } catch (IOException e) {
            log.severe("Could not save orders.yml: " + e.getMessage());
            return Result.of(Status.SAVE_FAILED);
        }
        int left = Math.max(0, Math.min(n, give.applyAsInt(n)));
        if (left > 0) {
            order.collectable(left);
            store.put(order);
            try {
                store.commit(() -> { });
            } catch (IOException e) {
                log.severe("Could not save " + left + " uncollected " + order.item().label() + " for " + order.ownerName() + ": " + e.getMessage());
            }
        }
        return new Result(Status.OK, order, n - left, false);
    }

    // ---------------------------------------------------------------- expiry and payouts

    /**
     * Closes every order whose time is up and refunds what is still held for it. Call regularly.
     *
     * @return the orders that expired
     */
    public List<Order> expireDue() {
        long now = clock.getAsLong();
        List<Order> due = new ArrayList<>();
        for (Order o : store.view()) {
            if (o.closed() == Order.Closed.NO && o.expires() <= now) {
                due.add(o);
            }
        }
        List<Order> done = new ArrayList<>();
        for (Order o : due) {
            if (close(o, Order.Closed.EXPIRED).status() == Status.OK) {
                done.add(o);
            }
        }
        return done;
    }

    /**
     * Pays out everything a player is owed.
     *
     * @param player who is owed
     * @return {@code true} if nothing is left owed (paid, or the economy could not confirm and the amount was written off)
     */
    public boolean payout(UUID player) {
        long cents = store.takeOwed(player);
        if (cents <= 0) {
            return true;
        }
        try {
            store.commit(() -> store.owe(player, cents));
        } catch (IOException e) {
            log.severe("Could not save orders.yml before paying " + cents + " cents: " + e.getMessage());
            return false;
        }
        Bank.Result result = bank.deposit(player, cents);
        if (result == Bank.Result.PAID) {
            return true;
        }
        if (result == Bank.Result.UNCERTAIN) {
            log.severe("Paying " + cents + " cents to " + player + " was not confirmed; it is not retried. Check their balance.");
            return true;
        }
        store.owe(player, cents);
        try {
            store.commit(() -> { });
        } catch (IOException e) {
            log.severe("Could not record that " + cents + " cents are still owed to " + player + ": " + e.getMessage());
        }
        return false;
    }

    /** Retries the payouts that failed earlier, for everyone. */
    public void payAll() {
        for (UUID player : store.owed().keySet()) {
            payout(player);
        }
    }
}
