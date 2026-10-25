package net.kaloyan.smporders;

import java.util.UUID;

/** One buy order. The buyer's money for the items not yet delivered is held by the plugin (escrow). */
public final class Order {

    /** Why an order is no longer open. */
    public enum Closed { NO, COMPLETE, CANCELLED, EXPIRED }

    private final long id;
    private final UUID owner;
    private final String ownerName;
    private final ItemKey item;
    private final int amount;
    private final long priceCents;
    private final long created;
    private final long expires;
    private int filled;
    private int collectable;
    private Closed closed = Closed.NO;

    /**
     * @param id         the order number
     * @param owner      the buyer
     * @param ownerName  the buyer's name when the order was made
     * @param item       what is wanted
     * @param amount     how many
     * @param priceCents what the buyer pays for one item, in hundredths
     * @param created    when it was made, epoch milliseconds
     * @param expires    when it expires, epoch milliseconds
     */
    public Order(long id, UUID owner, String ownerName, ItemKey item, int amount, long priceCents, long created, long expires) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.item = item;
        this.amount = amount;
        this.priceCents = priceCents;
        this.created = created;
        this.expires = expires;
    }

    /** @return the order number */
    public long id() {
        return id;
    }

    /** @return the buyer */
    public UUID owner() {
        return owner;
    }

    /** @return the buyer's name */
    public String ownerName() {
        return ownerName;
    }

    /** @return what is wanted */
    public ItemKey item() {
        return item;
    }

    /** @return how many were ordered */
    public int amount() {
        return amount;
    }

    /** @return the price of one item in hundredths */
    public long priceCents() {
        return priceCents;
    }

    /** @return when it was made */
    public long created() {
        return created;
    }

    /** @return when it expires */
    public long expires() {
        return expires;
    }

    /** @return how many were delivered so far */
    public int filled() {
        return filled;
    }

    /** @param value how many were delivered */
    public void filled(int value) {
        filled = value;
    }

    /** @return how many delivered items wait for the buyer to collect them */
    public int collectable() {
        return collectable;
    }

    /** @param value how many wait */
    public void collectable(int value) {
        collectable = value;
    }

    /** @return why it is closed, or {@code NO} */
    public Closed closed() {
        return closed;
    }

    /** @param value the new state */
    public void closed(Closed value) {
        closed = value;
    }

    /** @return whether other players can still deliver to it */
    public boolean open() {
        return closed == Closed.NO && filled < amount;
    }

    /** @return how many items are still wanted */
    public int remaining() {
        return amount - filled;
    }

    /** @return what is still held for the buyer if the order ended now (the price of the items not yet delivered) */
    public long escrowCents() {
        return closed == Closed.NO ? (long) remaining() * priceCents : 0;
    }
}
