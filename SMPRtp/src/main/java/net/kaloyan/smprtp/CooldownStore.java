package net.kaloyan.smprtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Keeps cooldowns across restarts in {@code cooldowns.yml}; a write goes to a temporary file first. */
public final class CooldownStore {

    private final Path file;

    /** @param file the cooldowns file */
    public CooldownStore(Path file) {
        this.file = file;
    }

    /**
     * @param now the current time in epoch milliseconds
     * @return the cooldown end per player; entries that already ended are left out
     * @throws IOException if the file exists but cannot be read
     */
    public Map<UUID, Long> load(long now) throws IOException {
        Map<UUID, Long> out = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
        }
        var section = yaml.getConfigurationSection("cooldowns");
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            try {
                long end = section.getLong(key);
                if (end > now) {
                    out.put(UUID.fromString(key), end);
                }
            } catch (IllegalArgumentException e) {
                throw new IOException(file.getFileName() + ": '" + key + "' is not a UUID");
            }
        }
        return out;
    }

    /**
     * Writes the cooldowns that have not ended yet.
     *
     * @param ends the cooldown end per player
     * @param now  the current time in epoch milliseconds
     * @throws IOException if the file cannot be written; the previous file is then intact
     */
    public void save(Map<UUID, Long> ends, long now) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        ends.entrySet().stream().filter(e -> e.getValue() > now).sorted(Map.Entry.comparingByKey())
                .forEach(e -> yaml.set("cooldowns." + e.getKey(), e.getValue()));
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
}
