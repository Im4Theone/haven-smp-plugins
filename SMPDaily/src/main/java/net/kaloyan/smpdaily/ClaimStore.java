package net.kaloyan.smpdaily;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** The last claim of every player ({@code daily.yml}). A change is saved before it counts and undone if saving fails. */
public final class ClaimStore {

    /**
     * @param day  the day of the cycle that was claimed last (1-based)
     * @param last when, epoch milliseconds
     */
    public record State(int day, long last) {
        /** The state of a player who never claimed. */
        public static final State NEVER = new State(0, 0);
    }

    private final Path file;
    private final Map<UUID, State> states = new HashMap<>();

    /** @param file the data file */
    public ClaimStore(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public void load() throws IOException {
        states.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    states.put(UUID.fromString(key), new State(players.getInt(key + ".day"), players.getLong(key + ".last")));
                }
            }
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        states.forEach((id, s) -> {
            yaml.set("players." + id + ".day", s.day());
            yaml.set("players." + id + ".last", s.last());
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

    /** @return the last claim of a player ({@link State#NEVER} if none) */
    public State get(UUID id) {
        return states.getOrDefault(id, State.NEVER);
    }

    /**
     * Notes a claim and saves.
     *
     * @param id    the player
     * @param state the new state
     * @throws IOException if saving failed; the old state is then back
     */
    public void set(UUID id, State state) throws IOException {
        State before = states.put(id, state);
        try {
            write();
        } catch (IOException e) {
            if (before == null) {
                states.remove(id);
            } else {
                states.put(id, before);
            }
            throw e;
        }
    }
}
