package net.kaloyan.smporders;

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
 * Every order, plus the money owed to players that has not been paid out yet, in {@code orders.yml}. Each change that matters is
 * written before the method returns and undone if the write fails. Main thread only.
 */
public final class OrderStore {

    private final Path file;
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final Map<UUID, Long> owed = new HashMap<>();
    private long nextId = 1;

    /** @param file the data file */
    public OrderStore(Path file) {
        this.file = file;
    }

    /**
     * Replaces everything in memory with the file's contents.
     *
     * @throws IOException if the file exists but cannot be read; memory is then left as it was
     */
    public void load() throws IOException {
        Map<Long, Order> loadedOrders = new LinkedHashMap<>();
        Map<UUID, Long> loadedOwed = new HashMap<>();
        long next = 1;
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
            }
            next = yaml.getLong("next-id", 1);
            ConfigurationSection owedSection = yaml.getConfigurationSection("owed");
            if (owedSection != null) {
                for (String key : owedSection.getKeys(false)) {
                    try {
                        loadedOwed.put(UUID.fromString(key), owedSection.getLong(key));
                    } catch (IllegalArgumentException e) {
                        throw new IOException(file.getFileName() + ": 'owed' has an invalid player '" + key + "'", e);
                    }
                }
            }
            ConfigurationSection section = yaml.getConfigurationSection("orders");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    Order order = read(key, section.getConfigurationSection(key));
                    loadedOrders.put(order.id(), order);
                    next = Math.max(next, order.id() + 1);
                }
            }
        }
        orders.clear();
        orders.putAll(loadedOrders);
        owed.clear();
        owed.putAll(loadedOwed);
        nextId = next;
    }

    private Order read(String key, ConfigurationSection e) throws IOException {
        String where = file.getFileName() + ": order '" + key + "'";
        if (e == null) {
            throw new IOException(where + " is not a section");
        }
        try {
            ItemKey item = ItemKey.decode(e.getString("item"));
            if (item == null) {
                throw new IOException(where + " has an invalid item '" + e.getString("item") + "'");
            }
            Order order = new Order(Long.parseLong(key), UUID.fromString(e.getString("owner", "")), e.getString("owner-name", "?"), item,
                    e.getInt("amount"), e.getLong("price"), e.getLong("created"), e.getLong("expires"));
            order.filled(e.getInt("filled"));
            order.collectable(e.getInt("collectable"));
            order.closed(Order.Closed.valueOf(e.getString("closed", "NO")));
            if (order.amount() < 1 || order.priceCents() < 1 || order.filled() < 0 || order.filled() > order.amount()
                    || order.collectable() < 0 || order.collectable() > order.filled()) {
                throw new IOException(where + " has impossible numbers");
            }
            return order;
        } catch (IllegalArgumentException ex) {
            throw new IOException(where + " is damaged: " + ex.getMessage(), ex);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId);
        owed.forEach((id, cents) -> yaml.set("owed." + id, cents));
        for (Order o : orders.values()) {
            String base = "orders." + o.id();
            yaml.set(base + ".owner", o.owner().toString());
            yaml.set(base + ".owner-name", o.ownerName());
            yaml.set(base + ".item", o.item().encode());
            yaml.set(base + ".amount", o.amount());
            yaml.set(base + ".price", o.priceCents());
            yaml.set(base + ".created", o.created());
            yaml.set(base + ".expires", o.expires());
            yaml.set(base + ".filled", o.filled());
            yaml.set(base + ".collectable", o.collectable());
            yaml.set(base + ".closed", o.closed().name());
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

    /** @return the next free order number (not reserved until an order is added) */
    public long nextId() {
        return nextId;
    }

    /** @return the order with that number, or {@code null} */
    public Order get(long id) {
        return orders.get(id);
    }

    /** @return every order (a copy of the list; the orders are live) */
    public List<Order> all() {
        return new ArrayList<>(orders.values());
    }

    /** @return the orders of one player */
    public List<Order> of(UUID owner) {
        List<Order> out = new ArrayList<>();
        for (Order o : orders.values()) {
            if (o.owner().equals(owner)) {
                out.add(o);
            }
        }
        return out;
    }

    /** @return how many open orders a player has */
    public int openCount(UUID owner) {
        int n = 0;
        for (Order o : orders.values()) {
            if (o.owner().equals(owner) && o.open()) {
                n++;
            }
        }
        return n;
    }

    /** @return the money owed to each player */
    public Map<UUID, Long> owed() {
        return Map.copyOf(owed);
    }

    /** @return what is owed to a player */
    public long owed(UUID player) {
        return owed.getOrDefault(player, 0L);
    }

    /** @return the total number of orders held */
    public int size() {
        return orders.size();
    }

    /** @return the orders (live objects) in creation order, without copying */
    public Collection<Order> view() {
        return orders.values();
    }

    /** Puts an order into memory without saving; the caller must {@link #commit}. */
    void put(Order order) {
        orders.put(order.id(), order);
        nextId = Math.max(nextId, order.id() + 1);
    }

    /** Takes an order out of memory without saving; the caller must {@link #commit}. */
    Order drop(long id) {
        return orders.remove(id);
    }

    /**
     * Adds an order and saves.
     *
     * @throws IOException if saving failed; the order is then not added
     */
    public void add(Order order) throws IOException {
        long previousNext = nextId;
        orders.put(order.id(), order);
        nextId = Math.max(nextId, order.id() + 1);
        try {
            write();
        } catch (IOException e) {
            orders.remove(order.id());
            nextId = previousNext;
            throw e;
        }
    }

    /**
     * Removes an order and saves.
     *
     * @throws IOException if saving failed; the order is then still there
     */
    public void remove(long id) throws IOException {
        Order old = orders.remove(id);
        if (old == null) {
            return;
        }
        try {
            write();
        } catch (IOException e) {
            orders.put(id, old);
            throw e;
        }
    }

    /**
     * Saves after the caller changed orders or the owed money; on failure {@code undo} puts everything back.
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

    /**
     * Adds to what a player is owed. The caller must {@link #commit} afterwards.
     *
     * @param player who is owed
     * @param cents  a positive amount
     */
    public void owe(UUID player, long cents) {
        owed.merge(player, cents, Long::sum);
    }

    /**
     * Removes and returns what a player is owed. The caller must {@link #commit} afterwards.
     *
     * @param player who is owed
     * @return the amount, zero if nothing
     */
    public long takeOwed(UUID player) {
        Long cents = owed.remove(player);
        return cents == null ? 0 : cents;
    }
}
