package net.kaloyan.smpauction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Everything the auction house remembers, in {@code auction.yml}: live listings, items waiting in collection boxes, money owed to
 * players, finished sales (the history) and what happened to offline sellers. Each change that matters is written before the
 * method returns and undone if the write fails. Main thread only.
 */
public final class AuctionStore {

    /** An item waiting for its owner to pick it up. */
    public record Collected(long id, UUID owner, Listing.Goods goods, String reason) {
    }

    /** A finished sale. */
    public record Sale(long id, long time, UUID seller, String sellerName, UUID buyer, String buyerName, Listing.Goods goods, long cents,
                       Listing.Type type) {
    }

    /** What a seller missed while offline. */
    public record Unseen(int sales, long cents) {
    }

    private static final int HISTORY_LIMIT = 1000;

    private final Path file;
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private final Map<Long, Collected> collected = new LinkedHashMap<>();
    private final Map<UUID, Long> owed = new HashMap<>();
    private final List<Sale> history = new ArrayList<>();
    private final Map<UUID, Unseen> unseen = new HashMap<>();
    private long nextId = 1;

    /** @param file the data file */
    public AuctionStore(Path file) {
        this.file = file;
    }

    // ---------------------------------------------------------------- reading

    /**
     * Replaces everything in memory with the file's contents.
     *
     * @throws IOException if the file exists but cannot be read; memory is then left as it was
     */
    public void load() throws IOException {
        Map<Long, Listing> l = new LinkedHashMap<>();
        Map<Long, Collected> c = new LinkedHashMap<>();
        Map<UUID, Long> o = new HashMap<>();
        List<Sale> h = new ArrayList<>();
        Map<UUID, Unseen> u = new HashMap<>();
        long next = 1;
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
            }
            try {
                next = yaml.getLong("next-id", 1);
                ConfigurationSection owedSection = yaml.getConfigurationSection("owed");
                if (owedSection != null) {
                    for (String key : owedSection.getKeys(false)) {
                        o.put(UUID.fromString(key), owedSection.getLong(key));
                    }
                }
                ConfigurationSection unseenSection = yaml.getConfigurationSection("unseen");
                if (unseenSection != null) {
                    for (String key : unseenSection.getKeys(false)) {
                        u.put(UUID.fromString(key), new Unseen(unseenSection.getInt(key + ".sales"), unseenSection.getLong(key + ".cents")));
                    }
                }
                forEach(yaml.getConfigurationSection("listings"), (key, s) -> {
                    Listing x = new Listing(Long.parseLong(key), UUID.fromString(req(s, "seller")), s.getString("seller-name", "?"), goods(s),
                            Listing.Type.valueOf(req(s, "type")), s.getLong("price"), s.getLong("created"), s.getLong("expires"));
                    if (s.getInt("bids") > 0) {
                        x.setBid(s.getLong("bid"), UUID.fromString(req(s, "bidder")), s.getString("bidder-name", "?"), s.getInt("bids"));
                    }
                    if (x.price() < 1) {
                        throw new IllegalArgumentException("price below 1");
                    }
                    l.put(x.id(), x);
                });
                forEach(yaml.getConfigurationSection("collected"), (key, s) ->
                        c.put(Long.parseLong(key), new Collected(Long.parseLong(key), UUID.fromString(req(s, "owner")), goods(s), s.getString("reason", ""))));
                forEach(yaml.getConfigurationSection("history"), (key, s) ->
                        h.add(new Sale(Long.parseLong(key), s.getLong("time"), UUID.fromString(req(s, "seller")), s.getString("seller-name", "?"),
                                UUID.fromString(req(s, "buyer")), s.getString("buyer-name", "?"), goods(s), s.getLong("cents"),
                                Listing.Type.valueOf(req(s, "type")))));
            } catch (IllegalArgumentException e) {
                throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
            }
            for (Listing x : l.values()) {
                next = Math.max(next, x.id() + 1);
            }
            for (long id : c.keySet()) {
                next = Math.max(next, id + 1);
            }
            for (Sale s : h) {
                next = Math.max(next, s.id() + 1);
            }
        }
        listings.clear();
        listings.putAll(l);
        collected.clear();
        collected.putAll(c);
        owed.clear();
        owed.putAll(o);
        history.clear();
        history.addAll(h);
        unseen.clear();
        unseen.putAll(u);
        nextId = next;
    }

    private interface Reader {
        void read(String key, ConfigurationSection section);
    }

    private static void forEach(ConfigurationSection section, Reader reader) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) {
                throw new IllegalArgumentException("'" + key + "' is not a section");
            }
            reader.read(key, s);
        }
    }

    private static String req(ConfigurationSection s, String key) {
        String v = s.getString(key);
        if (v == null) {
            throw new IllegalArgumentException("'" + s.getName() + "' lacks '" + key + "'");
        }
        return v;
    }

    private static Listing.Goods goods(ConfigurationSection s) {
        return new Listing.Goods(req(s, "data"), req(s, "material"), Math.max(1, s.getInt("amount", 1)), s.getString("label", "?"));
    }

    private static void put(YamlConfiguration yaml, String base, Listing.Goods g) {
        yaml.set(base + ".data", g.data());
        yaml.set(base + ".material", g.material());
        yaml.set(base + ".amount", g.amount());
        yaml.set(base + ".label", g.label());
    }

    // ---------------------------------------------------------------- writing

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        owed.forEach((id, cents) -> yaml.set("owed." + id, cents));
        unseen.forEach((id, x) -> {
            yaml.set("unseen." + id + ".sales", x.sales());
            yaml.set("unseen." + id + ".cents", x.cents());
        });
        for (Listing l : listings.values()) {
            String b = "listings." + l.id();
            yaml.set(b + ".seller", l.seller().toString());
            yaml.set(b + ".seller-name", l.sellerName());
            yaml.set(b + ".type", l.type().name());
            yaml.set(b + ".price", l.price());
            yaml.set(b + ".created", l.created());
            yaml.set(b + ".expires", l.expires());
            if (l.bids() > 0) {
                yaml.set(b + ".bid", l.bid());
                yaml.set(b + ".bidder", l.bidder().toString());
                yaml.set(b + ".bidder-name", l.bidderName());
                yaml.set(b + ".bids", l.bids());
            }
            put(yaml, b, l.goods());
        }
        for (Collected c : collected.values()) {
            String b = "collected." + c.id();
            yaml.set(b + ".owner", c.owner().toString());
            yaml.set(b + ".reason", c.reason());
            put(yaml, b, c.goods());
        }
        for (Sale s : history) {
            String b = "history." + s.id();
            yaml.set(b + ".time", s.time());
            yaml.set(b + ".seller", s.seller().toString());
            yaml.set(b + ".seller-name", s.sellerName());
            yaml.set(b + ".buyer", s.buyer().toString());
            yaml.set(b + ".buyer-name", s.buyerName());
            yaml.set(b + ".cents", s.cents());
            yaml.set(b + ".type", s.type().name());
            put(yaml, b, s.goods());
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Saves after the caller changed the data; on failure {@code undo} puts everything back.
     *
     * @param undo restores the previous state
     * @throws IOException if saving failed (after undoing)
     */
    public void commit(Runnable undo) throws IOException {
        try {
            write();
        } catch (IOException e) {
            undo.run();
            throw e;
        }
    }

    // ---------------------------------------------------------------- queries

    /** @return a fresh number for a listing, collected item or sale; the caller must use it */
    public long newId() {
        return nextId++;
    }

    /** @return the listing with that number, or {@code null} */
    public Listing listing(long id) {
        return listings.get(id);
    }

    /** @return every live listing (live objects) */
    public Collection<Listing> listings() {
        return listings.values();
    }

    /** @return the live listings of one seller */
    public List<Listing> listingsOf(UUID seller) {
        List<Listing> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            if (l.seller().equals(seller)) {
                out.add(l);
            }
        }
        return out;
    }

    /** @return the items waiting for a player */
    public List<Collected> collectedOf(UUID owner) {
        List<Collected> out = new ArrayList<>();
        for (Collected c : collected.values()) {
            if (c.owner().equals(owner)) {
                out.add(c);
            }
        }
        return out;
    }

    /** @return the waiting item with that number, or {@code null} */
    public Collected collected(long id) {
        return collected.get(id);
    }

    /** @return the finished sales, oldest first */
    public List<Sale> history() {
        return List.copyOf(history);
    }

    /** @return what a player is owed */
    public long owed(UUID player) {
        return owed.getOrDefault(player, 0L);
    }

    /** @return the money owed to each player */
    public Map<UUID, Long> owed() {
        return Map.copyOf(owed);
    }

    /** @return what a seller missed while offline, or {@code null} */
    public Unseen unseen(UUID player) {
        return unseen.get(player);
    }

    // ---------------------------------------------------------------- changes (the caller commits)

    /** Adds a listing in memory. */
    void put(Listing l) {
        listings.put(l.id(), l);
        nextId = Math.max(nextId, l.id() + 1);
    }

    /** Removes a listing in memory. */
    void drop(long id) {
        listings.remove(id);
    }

    /** Adds a waiting item in memory. */
    void put(Collected c) {
        collected.put(c.id(), c);
        nextId = Math.max(nextId, c.id() + 1);
    }

    /** Removes a waiting item in memory. */
    void dropCollected(long id) {
        collected.remove(id);
    }

    /** Adds to what a player is owed. */
    void owe(UUID player, long cents) {
        owed.merge(player, cents, Long::sum);
    }

    /** Takes and returns what a player is owed. */
    long takeOwed(UUID player) {
        Long cents = owed.remove(player);
        return cents == null ? 0 : cents;
    }

    /** Adds a finished sale, dropping the oldest ones beyond the limit. */
    void record(Sale sale) {
        history.add(sale);
        nextId = Math.max(nextId, sale.id() + 1);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
    }

    /** Takes the newest sale out again (used to undo {@link #record}). */
    void unrecord(Sale sale) {
        history.remove(sale);
    }

    /** Notes a sale made while the seller may be offline. */
    void note(UUID seller, long cents) {
        Unseen old = unseen.get(seller);
        unseen.put(seller, new Unseen((old == null ? 0 : old.sales()) + 1, (old == null ? 0 : old.cents()) + cents));
    }

    /** Puts a seller's notes back to what they were. */
    void setUnseen(UUID seller, Unseen value) {
        if (value == null) {
            unseen.remove(seller);
        } else {
            unseen.put(seller, value);
        }
    }
}
