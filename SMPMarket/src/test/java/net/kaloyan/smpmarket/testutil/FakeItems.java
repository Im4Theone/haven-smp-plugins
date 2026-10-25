package net.kaloyan.smpmarket.testutil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.service.ItemGateway;

/**
 * In-memory players with a main hand and a bounded inventory, standing in for Bukkit. Items are byte payloads, so
 * "no duplication" can be asserted by counting identical payloads across every hand and inventory.
 */
public final class FakeItems implements ItemGateway {

    /** One fake player. */
    public static final class Player {
        public ItemSnapshot hand;
        public final List<ItemSnapshot> inventory = new ArrayList<>();
        public int capacity = 36;
        public boolean online = true;
    }

    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    public final List<String> notifications = new CopyOnWriteArrayList<>();
    /** Runs on the gateway thread just before {@link #removeExact} looks at the inventory. */
    public volatile Consumer<UUID> beforeRemove = id -> { };
    /** Thread name every call must be made on, or {@code null} to not check. */
    public volatile String requiredThread;
    public final List<String> violations = new CopyOnWriteArrayList<>();

    /** Adds a player and returns the id. */
    public UUID addPlayer() {
        UUID id = UUID.randomUUID();
        players.put(id, new Player());
        return id;
    }

    public Player player(UUID id) {
        return players.get(id);
    }

    /** A distinctive item whose payload contains every byte value. */
    public static ItemSnapshot item(String material, int amount, String seed) {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + seed.hashCode());
        }
        return new ItemSnapshot(payload, material, amount, seed);
    }

    /** How many copies of {@code item} exist in all hands and inventories. */
    public int count(ItemSnapshot item) {
        int total = 0;
        byte[] wanted = item.payload();
        for (Player p : players.values()) {
            if (p.hand != null && Arrays.equals(p.hand.payload(), wanted)) {
                total++;
            }
            for (ItemSnapshot s : p.inventory) {
                if (Arrays.equals(s.payload(), wanted)) {
                    total++;
                }
            }
        }
        return total;
    }

    private void check() {
        String required = requiredThread;
        if (required != null && !Thread.currentThread().getName().equals(required)) {
            violations.add("ItemGateway used on " + Thread.currentThread().getName());
        }
    }

    @Override
    public Optional<ItemSnapshot> readMainHand(UUID player) {
        check();
        Player p = players.get(player);
        return p == null || !p.online ? Optional.empty() : Optional.ofNullable(p.hand);
    }

    @Override
    public boolean removeExact(UUID player, byte[] payload) {
        check();
        beforeRemove.accept(player);
        Player p = players.get(player);
        if (p == null || !p.online) {
            return false;
        }
        if (p.hand != null && Arrays.equals(p.hand.payload(), payload)) {
            p.hand = null;
            return true;
        }
        for (int i = 0; i < p.inventory.size(); i++) {
            if (Arrays.equals(p.inventory.get(i).payload(), payload)) {
                p.inventory.remove(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public GiveResult give(UUID player, byte[] payload) {
        check();
        Player p = players.get(player);
        if (p == null || !p.online) {
            return GiveResult.OFFLINE;
        }
        if (payload.length > 0 && payload[0] == 0x7f && payload.length < 4) {
            return GiveResult.INVALID_ITEM;
        }
        if (p.inventory.size() >= p.capacity) {
            return GiveResult.NO_ROOM;
        }
        p.inventory.add(new ItemSnapshot(payload, "minecraft:stone", 1, ""));
        return GiveResult.DELIVERED;
    }

    @Override
    public boolean isOnline(UUID player) {
        check();
        Player p = players.get(player);
        return p != null && p.online;
    }

    @Override
    public void notifyPlayer(UUID player, String message) {
        check();
        notifications.add(player + ": " + message);
    }
}
