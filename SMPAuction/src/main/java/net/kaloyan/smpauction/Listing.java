package net.kaloyan.smpauction;

import java.util.UUID;

/** An item for sale: either Buy It Now at a fixed price, or an auction that goes to the highest bid when time runs out. */
public final class Listing {

    /** The kind of sale. */
    public enum Type { BIN, AUCTION }

    /**
     * An item in a form that can be saved: the full item as text, plus what is needed to show and filter it without opening it.
     *
     * @param data     the whole item (Paper's serialised bytes, Base64)
     * @param material the item's material name
     * @param amount   how many
     * @param label    a readable name, for example {@code 16 Diamond Sword}
     */
    public record Goods(String data, String material, int amount, String label) {
    }

    private final long id;
    private final UUID seller;
    private final String sellerName;
    private final Goods goods;
    private final Type type;
    private final long price;
    private final long created;
    private long expires;
    private long bid;
    private UUID bidder;
    private String bidderName = "";
    private int bids;

    /**
     * @param id         the listing number
     * @param seller     who sells
     * @param sellerName the seller's name
     * @param goods      the item
     * @param type       BIN or auction
     * @param price      the price (BIN) or the starting bid (auction), in hundredths
     * @param created    when it was listed, epoch milliseconds
     * @param expires    when it ends, epoch milliseconds
     */
    public Listing(long id, UUID seller, String sellerName, Goods goods, Type type, long price, long created, long expires) {
        this.id = id;
        this.seller = seller;
        this.sellerName = sellerName;
        this.goods = goods;
        this.type = type;
        this.price = price;
        this.created = created;
        this.expires = expires;
    }

    /** @return the listing number */
    public long id() {
        return id;
    }

    /** @return the seller */
    public UUID seller() {
        return seller;
    }

    /** @return the seller's name */
    public String sellerName() {
        return sellerName;
    }

    /** @return the item */
    public Goods goods() {
        return goods;
    }

    /** @return BIN or auction */
    public Type type() {
        return type;
    }

    /** @return the price, or the starting bid of an auction, in hundredths */
    public long price() {
        return price;
    }

    /** @return when it was listed */
    public long created() {
        return created;
    }

    /** @return when it ends */
    public long expires() {
        return expires;
    }

    /** @param value when it ends */
    public void expires(long value) {
        expires = value;
    }

    /** @return the highest bid in hundredths, zero if none */
    public long bid() {
        return bid;
    }

    /** @return the highest bidder, or {@code null} */
    public UUID bidder() {
        return bidder;
    }

    /** @return the highest bidder's name */
    public String bidderName() {
        return bidderName;
    }

    /** @return how many bids were placed */
    public int bids() {
        return bids;
    }

    /**
     * Sets the winning bid.
     *
     * @param cents the bid
     * @param who   the bidder
     * @param name  the bidder's name
     * @param count the number of bids
     */
    public void setBid(long cents, UUID who, String name, int count) {
        bid = cents;
        bidder = who;
        bidderName = name == null ? "" : name;
        bids = count;
    }

    /** @return what the item currently costs: the price, or the highest bid of an auction */
    public long current() {
        return type == Type.AUCTION && bids > 0 ? bid : price;
    }
}
