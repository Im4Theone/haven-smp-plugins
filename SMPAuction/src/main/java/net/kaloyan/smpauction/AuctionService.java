package net.kaloyan.smpauction;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The rules of the auction house. Money rules: a buyer's or bidder's money is taken first and the change saved second; a payout is
 * removed from the owed list and saved first and paid second. So a crash can lose a payout but never pays twice, and a payment the
 * economy did not confirm is never retried. Items never exist twice: a sold item moves from the listing to the buyer's collection box
 * in one saved step. Every state change is saved before the method returns and undone if saving fails.
 */
public final class AuctionService {

    /** How an operation ended. */
    public enum Status {
        /** Done. */
        OK,
        /** The price is outside the allowed range. */
        BAD_PRICE,
        /** The listing length is not one of the allowed ones. */
        BAD_DURATION,
        /** The player has too many live listings. */
        LIMIT,
        /** That item (or this world or game mode) cannot be sold. */
        BLOCKED,
        /** Auctions with bids are switched off. */
        BIDDING_OFF,
        /** Not enough money. */
        BROKE,
        /** The economy is unavailable; nothing happened. */
        UNAVAILABLE,
        /** The economy did not confirm; the player may or may not have been charged. */
        UNSURE,
        /** Saving failed; nothing happened. */
        SAVE_FAILED,
        /** The listing is gone or has ended. */
        GONE,
        /** Players cannot buy or bid on their own listing. */
        OWN,
        /** That is not a Buy It Now listing / not an auction. */
        WRONG_TYPE,
        /** The bid is below the minimum (the minimum is in {@code cents}). */
        TOO_LOW,
        /** The player already has the highest bid. */
        ALREADY_HIGHEST,
        /** An auction with bids cannot be cancelled. */
        HAS_BIDS,
        /** The listing belongs to someone else. */
        NOT_OWNER
    }

    /**
     * @param status  how it ended
     * @param listing the listing concerned, if any
     * @param cents   the money involved in hundredths (charged, paid, or the minimum bid for {@code TOO_LOW})
     * @param pending {@code true} if money is owed but could not be paid out right now; it is paid later
     * @param other   the player who was outbid, if any
     * @param ref     the number of the collected item that was created, if any
     */
    public record Result(Status status, Listing listing, long cents, boolean pending, UUID other, long ref) {
        static Result of(Status status) {
            return new Result(status, null, 0, false, null, 0);
        }
    }

    /**
     * @param listing the listing that ended
     * @param sold    whether it sold (to the highest bidder) or went back to its seller
     * @param cents   the sale price, zero if not sold
     */
    public record Ended(Listing listing, boolean sold, long cents) {
    }

    private final AuctionStore store;
    private final Bank bank;
    private final Supplier<Settings> settings;
    private final Logger log;
    private final LongSupplier clock;

    /**
     * @param store    the data
     * @param bank     money
     * @param settings the current settings
     * @param log      where problems are reported
     * @param clock    epoch milliseconds
     */
    public AuctionService(AuctionStore store, Bank bank, Supplier<Settings> settings, Logger log, LongSupplier clock) {
        this.store = store;
        this.bank = bank;
        this.settings = settings;
        this.log = log;
        this.clock = clock;
    }

    /** @return the data */
    public AuctionStore store() {
        return store;
    }

    private boolean live(Listing l) {
        return store.listing(l.id()) == l;
    }

    /**
     * @param l a listing
     * @return the lowest bid allowed now (the starting bid if nobody bid yet)
     */
    public long minimumBid(Listing l) {
        if (l.bids() == 0) {
            return l.price();
        }
        Settings s = settings.get();
        long byPercent = BigDecimal.valueOf(l.bid()).multiply(s.incrementPercent()).movePointLeft(2).setScale(0, RoundingMode.CEILING).longValueExact();
        return l.bid() + Math.max(byPercent, s.incrementCents());
    }

    // ---------------------------------------------------------------- listing

    /**
     * Lists an item. The caller has already taken the item from the player and gives it back if this does not return {@code OK}.
     *
     * @param seller    who sells
     * @param name      the seller's name
     * @param goods     the item
     * @param type      BIN or auction
     * @param price     the price or starting bid in hundredths
     * @param hours     how long it stays listed
     * @param limit     how many live listings the seller may have
     * @param material  the item's material (checked against the blacklist)
     * @return the result; {@code cents} is the listing fee charged
     */
    public Result list(UUID seller, String name, Listing.Goods goods, Listing.Type type, long price, int hours, int limit,
                       org.bukkit.Material material) {
        Settings s = settings.get();
        if (s.blacklist().contains(material)) {
            return Result.of(Status.BLOCKED);
        }
        if (type == Listing.Type.AUCTION && !s.bidding()) {
            return Result.of(Status.BIDDING_OFF);
        }
        if (price < s.minCents() || price > s.maxCents()) {
            return Result.of(Status.BAD_PRICE);
        }
        if (!s.durations().contains(hours)) {
            return Result.of(Status.BAD_DURATION);
        }
        if (store.listingsOf(seller).size() >= limit) {
            return Result.of(Status.LIMIT);
        }
        long fee = s.fee(price);
        if (fee > 0) {
            switch (bank.withdraw(seller, fee)) {
                case INSUFFICIENT:
                    return Result.of(Status.BROKE);
                case FAILED:
                    return Result.of(Status.UNAVAILABLE);
                case UNCERTAIN:
                    log.warning("Payment of a " + fee + " cent listing fee by " + name + " was not confirmed; nothing was listed.");
                    return Result.of(Status.UNSURE);
                default:
                    break;
            }
        }
        long now = clock.getAsLong();
        Listing listing = new Listing(store.newId(), seller, name, goods, type, price, now, now + hours * 3_600_000L);
        store.put(listing);
        try {
            store.commit(() -> store.drop(listing.id()));
        } catch (IOException e) {
            log.severe("Could not save auction.yml: " + e.getMessage());
            if (fee > 0 && bank.deposit(seller, fee) != Bank.Result.PAID) {
                log.severe("Could not give " + fee + " cents back to " + name + " after a failed save; please do it by hand.");
            }
            return Result.of(Status.SAVE_FAILED);
        }
        return new Result(Status.OK, listing, fee, false, null, 0);
    }

    // ---------------------------------------------------------------- buying

    /**
     * Buys a Buy It Now listing. The item goes to the buyer's collection box.
     *
     * @param buyer     who buys
     * @param buyerName the buyer's name
     * @param l         the listing
     * @return the result; {@code cents} is the price, {@code ref} the collected item
     */
    public Result buy(UUID buyer, String buyerName, Listing l) {
        if (!live(l) || l.expires() <= clock.getAsLong()) {
            return Result.of(Status.GONE);
        }
        if (l.type() != Listing.Type.BIN) {
            return Result.of(Status.WRONG_TYPE);
        }
        if (l.seller().equals(buyer)) {
            return Result.of(Status.OWN);
        }
        switch (bank.withdraw(buyer, l.price())) {
            case INSUFFICIENT:
                return Result.of(Status.BROKE);
            case FAILED:
                return Result.of(Status.UNAVAILABLE);
            case UNCERTAIN:
                log.warning("Payment of " + l.price() + " cents by " + buyerName + " was not confirmed; nothing was bought.");
                return Result.of(Status.UNSURE);
            default:
                break;
        }
        Settle settle = settle(l, buyer, buyerName, l.price());
        try {
            store.commit(settle.undo);
        } catch (IOException e) {
            log.severe("Could not save auction.yml: " + e.getMessage());
            if (bank.deposit(buyer, l.price()) != Bank.Result.PAID) {
                log.severe("Could not give " + l.price() + " cents back to " + buyerName + " after a failed save; please do it by hand.");
            }
            return Result.of(Status.SAVE_FAILED);
        }
        boolean paid = payout(l.seller());
        return new Result(Status.OK, l, l.price(), !paid, null, settle.collectedId);
    }

    private static final class Settle {
        Runnable undo;
        long collectedId;
    }

    /** Moves a sold listing into the buyer's collection box, pays the seller (minus tax) and records the sale. The caller commits. */
    private Settle settle(Listing l, UUID buyer, String buyerName, long price) {
        long tax = settings.get().tax(price);
        long net = price - tax;
        Settle result = new Settle();
        AuctionStore.Collected collected = new AuctionStore.Collected(store.newId(), buyer, l.goods(), "bought");
        AuctionStore.Sale sale = new AuctionStore.Sale(store.newId(), clock.getAsLong(), l.seller(), l.sellerName(), buyer, buyerName,
                l.goods(), price, l.type());
        AuctionStore.Unseen before = store.unseen(l.seller());
        store.drop(l.id());
        store.put(collected);
        if (net > 0) {
            store.owe(l.seller(), net);
        }
        store.record(sale);
        store.note(l.seller(), net);
        result.collectedId = collected.id();
        result.undo = () -> {
            store.put(l);
            store.dropCollected(collected.id());
            undoOwe(l.seller(), net);
            store.unrecord(sale);
            store.setUnseen(l.seller(), before);
        };
        return result;
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

    // ---------------------------------------------------------------- bidding

    /**
     * Places a bid. The bid is taken from the bidder at once; an earlier bidder gets theirs back.
     *
     * @param bidder who bids
     * @param name   the bidder's name
     * @param l      the auction
     * @param cents  the bid in hundredths
     * @return the result; {@code cents} is the bid (or the minimum for {@code TOO_LOW}), {@code other} the outbid player
     */
    public Result bid(UUID bidder, String name, Listing l, long cents) {
        if (!live(l) || l.expires() <= clock.getAsLong()) {
            return Result.of(Status.GONE);
        }
        if (l.type() != Listing.Type.AUCTION) {
            return Result.of(Status.WRONG_TYPE);
        }
        if (!settings.get().bidding()) {
            return Result.of(Status.BIDDING_OFF);
        }
        if (l.seller().equals(bidder)) {
            return Result.of(Status.OWN);
        }
        if (bidder.equals(l.bidder())) {
            return Result.of(Status.ALREADY_HIGHEST);
        }
        long minimum = minimumBid(l);
        if (cents < minimum) {
            return new Result(Status.TOO_LOW, l, minimum, false, null, 0);
        }
        if (cents > settings.get().maxCents()) {
            return Result.of(Status.BAD_PRICE);
        }
        switch (bank.withdraw(bidder, cents)) {
            case INSUFFICIENT:
                return Result.of(Status.BROKE);
            case FAILED:
                return Result.of(Status.UNAVAILABLE);
            case UNCERTAIN:
                log.warning("Payment of a " + cents + " cent bid by " + name + " was not confirmed; no bid was placed.");
                return Result.of(Status.UNSURE);
            default:
                break;
        }
        long oldBid = l.bid();
        UUID oldBidder = l.bidder();
        String oldName = l.bidderName();
        int oldBids = l.bids();
        long oldExpires = l.expires();
        long now = clock.getAsLong();
        l.setBid(cents, bidder, name, oldBids + 1);
        long snipe = settings.get().antiSnipeMillis();
        if (snipe > 0 && l.expires() - now < snipe) {
            l.expires(now + snipe);
        }
        if (oldBidder != null) {
            store.owe(oldBidder, oldBid);
        }
        try {
            store.commit(() -> {
                l.setBid(oldBid, oldBidder, oldName, oldBids);
                l.expires(oldExpires);
                if (oldBidder != null) {
                    undoOwe(oldBidder, oldBid);
                }
            });
        } catch (IOException e) {
            log.severe("Could not save auction.yml: " + e.getMessage());
            if (bank.deposit(bidder, cents) != Bank.Result.PAID) {
                log.severe("Could not give " + cents + " cents back to " + name + " after a failed save; please do it by hand.");
            }
            return Result.of(Status.SAVE_FAILED);
        }
        boolean paid = oldBidder == null || payout(oldBidder);
        return new Result(Status.OK, l, cents, !paid, oldBidder, 0);
    }

    // ---------------------------------------------------------------- cancelling

    /**
     * Takes a listing off the market; the item goes to the seller's collection box. An auction with bids can only be cancelled by an
     * administrator, and then the highest bidder is refunded.
     *
     * @param actor who cancels
     * @param admin whether the actor may cancel other players' listings and auctions with bids
     * @param l     the listing
     * @return the result; {@code cents} is what was refunded to a bidder, {@code ref} the collected item
     */
    public Result cancel(UUID actor, boolean admin, Listing l) {
        if (!l.seller().equals(actor) && !admin) {
            return Result.of(Status.NOT_OWNER);
        }
        if (!live(l)) {
            return Result.of(Status.GONE);
        }
        if (l.bids() > 0 && !admin) {
            return Result.of(Status.HAS_BIDS);
        }
        UUID bidder = l.bidder();
        long bid = l.bid();
        AuctionStore.Collected collected = new AuctionStore.Collected(store.newId(), l.seller(), l.goods(), "cancelled");
        store.drop(l.id());
        store.put(collected);
        if (bidder != null) {
            store.owe(bidder, bid);
        }
        try {
            store.commit(() -> {
                store.put(l);
                store.dropCollected(collected.id());
                if (bidder != null) {
                    undoOwe(bidder, bid);
                }
            });
        } catch (IOException e) {
            log.severe("Could not save auction.yml: " + e.getMessage());
            return Result.of(Status.SAVE_FAILED);
        }
        boolean paid = bidder == null || payout(bidder);
        return new Result(Status.OK, l, bid, !paid, bidder, collected.id());
    }

    // ---------------------------------------------------------------- expiry

    /**
     * Ends every listing whose time is up: auctions with bids are sold to the highest bidder, everything else goes back to its seller.
     * Call regularly.
     *
     * @return what ended
     */
    public List<Ended> expireDue() {
        long now = clock.getAsLong();
        List<Listing> due = new ArrayList<>();
        for (Listing l : store.listings()) {
            if (l.expires() <= now) {
                due.add(l);
            }
        }
        List<Ended> ended = new ArrayList<>();
        for (Listing l : due) {
            if (l.type() == Listing.Type.AUCTION && l.bids() > 0) {
                Settle settle = settle(l, l.bidder(), l.bidderName(), l.bid());
                try {
                    store.commit(settle.undo);
                } catch (IOException e) {
                    log.severe("Could not save auction.yml: " + e.getMessage());
                    continue;
                }
                payout(l.seller());
                ended.add(new Ended(l, true, l.bid()));
            } else {
                AuctionStore.Collected back = new AuctionStore.Collected(store.newId(), l.seller(), l.goods(), "expired");
                store.drop(l.id());
                store.put(back);
                try {
                    store.commit(() -> {
                        store.put(l);
                        store.dropCollected(back.id());
                    });
                } catch (IOException e) {
                    log.severe("Could not save auction.yml: " + e.getMessage());
                    continue;
                }
                ended.add(new Ended(l, false, 0));
            }
        }
        return ended;
    }

    // ---------------------------------------------------------------- collection box and notes

    /**
     * Takes an item out of a player's collection box. The caller hands it to the player and calls {@link #restore} if it did not fit.
     *
     * @param owner who collects
     * @param id    the waiting item's number
     * @return the item, or {@code null} if it is not there or not theirs, or saving failed
     */
    public AuctionStore.Collected claim(UUID owner, long id) {
        AuctionStore.Collected c = store.collected(id);
        if (c == null || !c.owner().equals(owner)) {
            return null;
        }
        store.dropCollected(id);
        try {
            store.commit(() -> store.put(c));
        } catch (IOException e) {
            log.severe("Could not save auction.yml: " + e.getMessage());
            return null;
        }
        return c;
    }

    /**
     * Puts a claimed item back into its owner's collection box.
     *
     * @param c the item
     * @return whether it was saved
     */
    public boolean restore(AuctionStore.Collected c) {
        store.put(c);
        try {
            store.commit(() -> store.dropCollected(c.id()));
            return true;
        } catch (IOException e) {
            log.severe("Could not save a " + c.goods().label() + " for " + c.owner() + " back into auction.yml: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns and clears what a seller missed while offline.
     *
     * @param player the seller
     * @return the summary, or {@code null} if there was none
     */
    public AuctionStore.Unseen takeUnseen(UUID player) {
        AuctionStore.Unseen u = store.unseen(player);
        if (u == null) {
            return null;
        }
        store.setUnseen(player, null);
        try {
            store.commit(() -> store.setUnseen(player, u));
        } catch (IOException e) {
            return null;
        }
        return u;
    }

    // ---------------------------------------------------------------- payouts

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
            log.severe("Could not save auction.yml before paying " + cents + " cents: " + e.getMessage());
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
