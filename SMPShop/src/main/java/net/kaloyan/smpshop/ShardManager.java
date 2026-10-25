package net.kaloyan.smpshop;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The Shards currency, separate from money. Balances are kept in memory and saved to {@code shards.yml} in the same format
 * the old DonutShop used ({@code balances.<uuid>: n}). Every change is {@code synchronized}; saving is atomic (temporary file,
 * then move) and retried if it fails, so the file never holds a half-written state.
 *
 * <p>Other plugins (SMP-Scoreboard, SMP-Crates) reach this through reflection: {@link #get}, {@link #add}, {@link #tryTake}.
 */
public final class ShardManager {

    private final Path file;
    private final Logger log;
    private final Map<UUID, Long> balances = new HashMap<>();
    private boolean dirty;

    /**
     * @param file the {@code shards.yml} path
     * @param log  where problems are reported
     */
    public ShardManager(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read, in which case nothing may be saved over it
     */
    public synchronized void load() throws IOException {
        balances.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (InvalidConfigurationException e) {
            throw new IOException("shards.yml is damaged: " + e.getMessage(), e);
        }
        var section = yaml.getConfigurationSection("balances");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                long value = section.getLong(key);
                if (value > 0) {
                    balances.put(UUID.fromString(key), value);
                }
            } catch (IllegalArgumentException e) {
                log.warning("Ignoring invalid entry in shards.yml: " + key);
            }
        }
    }

    /**
     * @param id the player
     * @return the shards of a player, zero or more
     */
    public synchronized long get(UUID id) {
        return balances.getOrDefault(id, 0L);
    }

    /**
     * @param id     the player
     * @param amount shards to add, positive
     * @return the new balance
     */
    public synchronized long add(UUID id, long amount) {
        if (amount <= 0) {
            return get(id);
        }
        long have = get(id);
        long updated = have > Long.MAX_VALUE - amount ? Long.MAX_VALUE : have + amount;
        balances.put(id, updated);
        dirty = true;
        return updated;
    }

    /**
     * Takes shards only if the player has all of them.
     *
     * @param id     the player
     * @param amount a positive amount
     * @return whether they were taken
     */
    public synchronized boolean tryTake(UUID id, long amount) {
        long current = get(id);
        if (amount <= 0 || current < amount) {
            return false;
        }
        if (current == amount) {
            balances.remove(id);
        } else {
            balances.put(id, current - amount);
        }
        dirty = true;
        return true;
    }

    /**
     * Sets a balance (administrators).
     *
     * @param id     the player
     * @param amount zero or more
     */
    public synchronized void set(UUID id, long amount) {
        if (amount <= 0) {
            balances.remove(id);
        } else {
            balances.put(id, amount);
        }
        dirty = true;
    }

    /** Writes the file if anything changed since the last write. Safe to call from any thread. */
    public synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        balances.forEach((id, value) -> yaml.set("balances." + id, value));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temp, yaml.saveToString());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not save shards.yml; the shards are held in memory and the save is retried", e);
        }
    }
}
