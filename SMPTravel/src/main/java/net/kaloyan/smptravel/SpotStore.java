package net.kaloyan.smptravel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Homes (per player) and the two server locations (spawn and hub), kept in one {@code travel.yml}. Every change is
 * written to disk first and only then applied in memory, so a failed save changes nothing. Main thread only.
 */
public final class SpotStore {

    /** The home names a player may use. */
    public static final String NAME_PATTERN = "[a-z0-9_-]{1,16}";

    private final Path file;
    private final Map<UUID, Map<String, Spot>> homes = new LinkedHashMap<>();
    private final Map<String, Spot> server = new LinkedHashMap<>();

    /** @param file the data file */
    public SpotStore(Path file) {
        this.file = file;
    }

    /**
     * Replaces everything in memory with the file's contents.
     *
     * @throws IOException if the file exists but is unreadable or invalid; memory is then left as it was
     */
    public void load() throws IOException {
        Map<UUID, Map<String, Spot>> loadedHomes = new LinkedHashMap<>();
        Map<String, Spot> loadedServer = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
            }
            ConfigurationSection serverSection = yaml.getConfigurationSection("server");
            if (serverSection != null) {
                for (String key : serverSection.getKeys(false)) {
                    loadedServer.put(key, spot(serverSection.getConfigurationSection(key), "server." + key));
                }
            }
            ConfigurationSection players = yaml.getConfigurationSection("homes");
            if (players != null) {
                for (String id : players.getKeys(false)) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(id);
                    } catch (IllegalArgumentException e) {
                        throw new IOException(file.getFileName() + ": '" + id + "' is not a UUID");
                    }
                    Map<String, Spot> mine = new TreeMap<>();
                    ConfigurationSection list = players.getConfigurationSection(id);
                    if (list != null) {
                        for (String name : list.getKeys(false)) {
                            mine.put(name, spot(list.getConfigurationSection(name), "homes." + id + "." + name));
                        }
                    }
                    loadedHomes.put(uuid, mine);
                }
            }
        }
        homes.clear();
        homes.putAll(loadedHomes);
        server.clear();
        server.putAll(loadedServer);
    }

    private Spot spot(ConfigurationSection s, String where) throws IOException {
        if (s == null || s.getString("world") == null || !s.contains("x") || !s.contains("y") || !s.contains("z")) {
            throw new IOException(file.getFileName() + ": '" + where + "' is incomplete");
        }
        return new Spot(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private void write(YamlConfiguration yaml, String base, Spot s) {
        yaml.set(base + ".world", s.world());
        yaml.set(base + ".x", s.x());
        yaml.set(base + ".y", s.y());
        yaml.set(base + ".z", s.z());
        yaml.set(base + ".yaw", (double) s.yaw());
        yaml.set(base + ".pitch", (double) s.pitch());
    }

    private void save(Map<UUID, Map<String, Spot>> newHomes, Map<String, Spot> newServer) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        new TreeMap<>(newServer).forEach((key, s) -> write(yaml, "server." + key, s));
        new TreeMap<>(newHomes).forEach((id, mine) -> mine.forEach((name, s) -> write(yaml, "homes." + id + "." + name, s)));
        Atomic.write(file, yaml.saveToString());
    }

    // ---------------------------------------------------------------- homes

    /** @return the player's homes by name (a copy) */
    public Map<String, Spot> homes(UUID player) {
        return new TreeMap<>(homes.getOrDefault(player, Map.of()));
    }

    /**
     * Sets a home, replacing one with the same name.
     *
     * @param player the owner
     * @param name   the home name
     * @param spot   where it is
     * @throws IOException if saving failed; nothing changed then
     */
    public void setHome(UUID player, String name, Spot spot) throws IOException {
        Map<UUID, Map<String, Spot>> next = copy();
        next.computeIfAbsent(player, id -> new TreeMap<>()).put(name, spot);
        save(next, server);
        homes.clear();
        homes.putAll(next);
    }

    /**
     * @return {@code false} if the player had no such home
     * @throws IOException if saving failed; nothing changed then
     */
    public boolean deleteHome(UUID player, String name) throws IOException {
        if (!homes.getOrDefault(player, Map.of()).containsKey(name)) {
            return false;
        }
        Map<UUID, Map<String, Spot>> next = copy();
        next.get(player).remove(name);
        if (next.get(player).isEmpty()) {
            next.remove(player);
        }
        save(next, server);
        homes.clear();
        homes.putAll(next);
        return true;
    }

    private Map<UUID, Map<String, Spot>> copy() {
        Map<UUID, Map<String, Spot>> out = new LinkedHashMap<>();
        homes.forEach((id, mine) -> out.put(id, new TreeMap<>(mine)));
        return out;
    }

    // ---------------------------------------------------------------- server locations

    /** @param key {@code spawn} or {@code hub} */
    public java.util.Optional<Spot> server(String key) {
        return java.util.Optional.ofNullable(server.get(key));
    }

    /**
     * @param key  {@code spawn} or {@code hub}
     * @param spot the new location
     * @throws IOException if saving failed; nothing changed then
     */
    public void setServer(String key, Spot spot) throws IOException {
        Map<String, Spot> next = new LinkedHashMap<>(server);
        next.put(key, spot);
        save(homes, next);
        server.clear();
        server.putAll(next);
    }
}
