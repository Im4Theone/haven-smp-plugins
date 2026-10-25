package net.kaloyan.smpcrates;

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
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Crate balances, the hourly round each player last got, and the placed crate blocks, in {@code data.yml}. Every change is
 * written to disk before the method returns; if the write fails the change is undone and an {@link IOException} is thrown, so
 * memory and file never disagree. Main thread only.
 */
public final class CrateStore {

    /**
     * A block that opens a crate when right clicked.
     *
     * @param world the world's name
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @param crate the crate type it opens
     */
    public record CrateBlock(String world, int x, int y, int z, String crate) {
    }

    private final Path file;
    private final Map<UUID, Map<String, Integer>> balances = new LinkedHashMap<>();
    private final Map<UUID, Long> rounds = new HashMap<>();
    private final List<CrateBlock> blocks = new ArrayList<>();

    /** @param file the data file */
    public CrateStore(Path file) {
        this.file = file;
    }

    /**
     * Replaces everything in memory with the file's contents.
     *
     * @throws IOException if the file exists but cannot be read; memory is then left as it was
     */
    public void load() throws IOException {
        Map<UUID, Map<String, Integer>> b = new LinkedHashMap<>();
        Map<UUID, Long> r = new HashMap<>();
        List<CrateBlock> list = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
            }
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String id : players.getKeys(false)) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        throw new IOException(file.getFileName() + ": '" + id + "' is not a UUID");
                    }
                    ConfigurationSection p = players.getConfigurationSection(id);
                    if (p == null) {
                        continue;
                    }
                    r.put(uuid, p.getLong("round", Long.MIN_VALUE));
                    Map<String, Integer> mine = new TreeMap<>();
                    ConfigurationSection c = p.getConfigurationSection("crates");
                    if (c != null) {
                        for (String type : c.getKeys(false)) {
                            int n = c.getInt(type);
                            if (n < 0) {
                                throw new IOException(file.getFileName() + ": negative crate count for " + id + "/" + type);
                            }
                            if (n > 0) {
                                mine.put(type, n);
                            }
                        }
                    }
                    b.put(uuid, mine);
                }
            }
            for (Map<?, ?> m : yaml.getMapList("blocks")) {
                try {
                    list.add(new CrateBlock(String.valueOf(m.get("world")), ((Number) m.get("x")).intValue(),
                            ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue(), String.valueOf(m.get("crate"))));
                } catch (RuntimeException e) {
                    throw new IOException(file.getFileName() + ": a crate block entry is incomplete");
                }
            }
        }
        balances.clear();
        balances.putAll(b);
        rounds.clear();
        rounds.putAll(r);
        blocks.clear();
        blocks.addAll(list);
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        new TreeMap<>(rounds).forEach((id, round) -> {
            yaml.set("players." + id + ".round", round);
        });
        new TreeMap<>(balances).forEach((id, mine) -> mine.forEach((type, n) -> {
            if (n > 0) {
                yaml.set("players." + id + ".crates." + type, n);
            }
        }));
        List<Map<String, Object>> list = new ArrayList<>();
        for (CrateBlock b : blocks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", b.world());
            m.put("x", b.x());
            m.put("y", b.y());
            m.put("z", b.z());
            m.put("crate", b.crate());
            list.add(m);
        }
        yaml.set("blocks", list);
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

    // ---------------------------------------------------------------- balances

    /** @return how many crates of a type the player has */
    public int balance(UUID player, String type) {
        return balances.getOrDefault(player, Map.of()).getOrDefault(type, 0);
    }

    /** @return all the player's crates by type (a copy) */
    public Map<String, Integer> balances(UUID player) {
        return new TreeMap<>(balances.getOrDefault(player, Map.of()));
    }

    /** @return the hourly round the player last received, or {@link Long#MIN_VALUE} if never */
    public long lastRound(UUID player) {
        return rounds.getOrDefault(player, Long.MIN_VALUE);
    }

    /**
     * Gives crates.
     *
     * @param player the player
     * @param type   the crate type
     * @param amount a positive amount
     * @throws IOException if saving failed; nothing changed then
     */
    public void add(UUID player, String type, int amount) throws IOException {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        int before = balance(player, type);
        long total = (long) before + amount;
        if (total > 1_000_000) {
            throw new IOException("a player cannot hold more than 1,000,000 crates of one type");
        }
        set(player, type, (int) total);
        try {
            save();
        } catch (IOException e) {
            set(player, type, before);
            throw e;
        }
    }

    /**
     * Takes crates if the player has enough.
     *
     * @return {@code false} if the player has fewer than {@code amount}
     * @throws IOException if saving failed; nothing changed then
     */
    public boolean tryTake(UUID player, String type, int amount) throws IOException {
        int before = balance(player, type);
        if (amount < 1 || before < amount) {
            return false;
        }
        set(player, type, before - amount);
        try {
            save();
        } catch (IOException e) {
            set(player, type, before);
            throw e;
        }
        return true;
    }

    private void set(UUID player, String type, int value) {
        if (value <= 0) {
            Map<String, Integer> mine = balances.get(player);
            if (mine != null) {
                mine.remove(type);
            }
        } else {
            balances.computeIfAbsent(player, id -> new TreeMap<>()).put(type, value);
        }
    }

    /**
     * Gives one crate of a type to each player who has not had this round yet, and remembers the round, with a single save.
     *
     * @param players the players to consider
     * @param type    the crate type
     * @param round   the current round
     * @return the players who received a crate
     * @throws IOException if saving failed; nothing changed then
     */
    public List<UUID> grantRound(Collection<UUID> players, String type, long round) throws IOException {
        List<UUID> granted = new ArrayList<>();
        Map<UUID, Long> oldRounds = new HashMap<>();
        for (UUID id : players) {
            if (lastRound(id) < round && !oldRounds.containsKey(id)) {
                oldRounds.put(id, rounds.get(id));
                granted.add(id);
            }
        }
        if (granted.isEmpty()) {
            return granted;
        }
        for (UUID id : granted) {
            set(id, type, balance(id, type) + 1);
            rounds.put(id, round);
        }
        try {
            save();
        } catch (IOException e) {
            for (UUID id : granted) {
                set(id, type, balance(id, type) - 1);
                Long old = oldRounds.get(id);
                if (old == null) {
                    rounds.remove(id);
                } else {
                    rounds.put(id, old);
                }
            }
            throw e;
        }
        return granted;
    }

    /**
     * Gives every listed player the same crates with one save.
     *
     * @param players the players
     * @param type    the crate type
     * @param amount  how many each
     * @throws IOException if saving failed; nobody got anything then
     */
    public void addToAll(Collection<UUID> players, String type, int amount) throws IOException {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        List<UUID> distinct = players.stream().distinct().toList();
        for (UUID id : distinct) {
            if ((long) balance(id, type) + amount > 1_000_000) {
                throw new IOException("a player cannot hold more than 1,000,000 crates of one type");
            }
        }
        for (UUID id : distinct) {
            set(id, type, balance(id, type) + amount);
        }
        try {
            save();
        } catch (IOException e) {
            for (UUID id : distinct) {
                set(id, type, balance(id, type) - amount);
            }
            throw e;
        }
    }

    // ---------------------------------------------------------------- crate blocks

    /** @return the placed crate blocks (a copy) */
    public List<CrateBlock> blocks() {
        return List.copyOf(blocks);
    }

    /**
     * Registers a block, replacing a crate block at the same place.
     *
     * @throws IOException if saving failed; nothing changed then
     */
    public void putBlock(CrateBlock block) throws IOException {
        List<CrateBlock> before = new ArrayList<>(blocks);
        blocks.removeIf(b -> same(b, block));
        blocks.add(block);
        try {
            save();
        } catch (IOException e) {
            blocks.clear();
            blocks.addAll(before);
            throw e;
        }
    }

    /**
     * @return {@code false} if no crate block was there
     * @throws IOException if saving failed; nothing changed then
     */
    public boolean removeBlock(String world, int x, int y, int z) throws IOException {
        List<CrateBlock> before = new ArrayList<>(blocks);
        if (!blocks.removeIf(b -> b.world().equals(world) && b.x() == x && b.y() == y && b.z() == z)) {
            return false;
        }
        try {
            save();
        } catch (IOException e) {
            blocks.clear();
            blocks.addAll(before);
            throw e;
        }
        return true;
    }

    private static boolean same(CrateBlock a, CrateBlock b) {
        return a.world().equals(b.world()) && a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    /** @return the crate block at a position, if any */
    public java.util.Optional<CrateBlock> blockAt(String world, int x, int y, int z) {
        for (CrateBlock b : blocks) {
            if (b.world().equals(world) && b.x() == x && b.y() == y && b.z() == z) {
                return java.util.Optional.of(b);
            }
        }
        return java.util.Optional.empty();
    }
}
