package net.kaloyan.smpsocial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Who ignores whom ({@code ignores.yml}). Reading is safe from any thread (chat is handled off the main thread); a change is saved before it counts. */
public final class Ignores {

    private final Path file;
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();

    /** @param file the data file */
    public Ignores(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public void load() throws IOException {
        ignored.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            var players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    Set<UUID> set = ConcurrentHashMap.newKeySet();
                    for (String other : players.getStringList(key)) {
                        set.add(UUID.fromString(other));
                    }
                    ignored.put(UUID.fromString(key), set);
                }
            }
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        ignored.forEach((who, set) -> {
            if (!set.isEmpty()) {
                yaml.set("players." + who, set.stream().map(UUID::toString).toList());
            }
        });
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
     * @param who   the player who may ignore
     * @param other the other player
     * @return whether {@code who} ignores {@code other}
     */
    public boolean ignores(UUID who, UUID other) {
        Set<UUID> set = ignored.get(who);
        return set != null && set.contains(other);
    }

    /** @return the players {@code who} ignores */
    public Set<UUID> of(UUID who) {
        Set<UUID> set = ignored.get(who);
        return set == null ? Set.of() : new HashSet<>(set);
    }

    /**
     * Starts or stops ignoring and saves.
     *
     * @param who   the player
     * @param other the other player
     * @return {@code true} if {@code who} ignores {@code other} now
     * @throws IOException if saving failed; nothing changed
     */
    public boolean toggle(UUID who, UUID other) throws IOException {
        Set<UUID> set = ignored.computeIfAbsent(who, k -> ConcurrentHashMap.newKeySet());
        boolean now = set.add(other);
        if (!now) {
            set.remove(other);
        }
        try {
            write();
        } catch (IOException e) {
            if (now) {
                set.remove(other);
            } else {
                set.add(other);
            }
            throw e;
        }
        return now;
    }

    /** @return every pair, for tests */
    Map<UUID, List<UUID>> snapshot() {
        Map<UUID, List<UUID>> out = new HashMap<>();
        ignored.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }
}
