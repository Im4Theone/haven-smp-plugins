package net.kaloyan.smpstats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** The counters of every player ({@code stats.yml}) and the ranking of players by a number. All methods are synchronized. */
public final class StatsStore {

    /** What is counted for one player. */
    public static final class Entry {
        private String name;
        private long kills;
        private long deaths;
        private long mobs;
        private long blocks;
        private long minutes;

        Entry(String name) {
            this.name = name;
        }

        /** @return the player's last known name */
        public String name() {
            return name;
        }

        /** @return players killed */
        public long kills() {
            return kills;
        }

        /** @return deaths */
        public long deaths() {
            return deaths;
        }

        /** @return mobs killed */
        public long mobs() {
            return mobs;
        }

        /** @return blocks broken */
        public long blocks() {
            return blocks;
        }

        /** @return minutes played */
        public long minutes() {
            return minutes;
        }

        /** @return kills per death; with no deaths, the number of kills */
        public double kdr() {
            return deaths == 0 ? kills : Math.round(kills * 100.0 / deaths) / 100.0;
        }
    }

    /**
     * One line of a leaderboard.
     *
     * @param player the player
     * @param name   their name
     * @param value  the number
     */
    public record Row(UUID player, String name, long value) {
    }

    private final Path file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private boolean dirty;

    /** @param file the data file */
    public StatsStore(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public synchronized void load() throws IOException {
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    ConfigurationSection s = players.getConfigurationSection(key);
                    Entry e = new Entry(s.getString("name", "?"));
                    e.kills = s.getLong("kills");
                    e.deaths = s.getLong("deaths");
                    e.mobs = s.getLong("mobs");
                    e.blocks = s.getLong("blocks");
                    e.minutes = s.getLong("minutes");
                    entries.put(UUID.fromString(key), e);
                }
            }
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    /** Writes the file if anything changed. Safe to call from any thread. */
    public synchronized void saveIfDirty() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        entries.forEach((id, e) -> {
            String b = "players." + id;
            yaml.set(b + ".name", e.name);
            yaml.set(b + ".kills", e.kills);
            yaml.set(b + ".deaths", e.deaths);
            yaml.set(b + ".mobs", e.mobs);
            yaml.set(b + ".blocks", e.blocks);
            yaml.set(b + ".minutes", e.minutes);
        });
        try {
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
            dirty = false;
        } catch (IOException e) {
            dirty = true;       // the counters stay in memory; the next save tries again
        }
    }

    private Entry entry(UUID id, String name) {
        Entry e = entries.computeIfAbsent(id, k -> new Entry(name));
        e.name = name;
        return e;
    }

    /** Notes a player's name (so a player with no other counter is still known by name). */
    public synchronized void seen(UUID id, String name) {
        entry(id, name);
        dirty = true;
    }

    /** Adds a kill. */
    public synchronized void kill(UUID id, String name) {
        entry(id, name).kills++;
        dirty = true;
    }

    /** Adds a death. */
    public synchronized void death(UUID id, String name) {
        entry(id, name).deaths++;
        dirty = true;
    }

    /** Adds a mob kill. */
    public synchronized void mob(UUID id, String name) {
        entry(id, name).mobs++;
        dirty = true;
    }

    /** Adds a broken block. */
    public synchronized void block(UUID id, String name) {
        entry(id, name).blocks++;
        dirty = true;
    }

    /** Adds minutes of playtime. */
    public synchronized void minutes(UUID id, String name, long minutes) {
        entry(id, name).minutes += minutes;
        dirty = true;
    }

    /** @return what is counted for the player, or {@code null} if nothing is known */
    public synchronized Entry get(UUID id) {
        return entries.get(id);
    }

    /** @return every player that has a counter */
    public synchronized List<UUID> players() {
        return new ArrayList<>(entries.keySet());
    }

    /** @return the last known name of a player */
    public synchronized String name(UUID id) {
        Entry e = entries.get(id);
        return e == null ? "?" : e.name;
    }

    /**
     * Ranks players by a number.
     *
     * @param value how to read the number from a player's counters
     * @param limit how many players at most
     * @return the best players first; equal numbers are ordered by name; players with 0 are left out
     */
    public synchronized List<Row> top(ToLongFunction<Entry> value, int limit) {
        List<Row> rows = new ArrayList<>();
        entries.forEach((id, e) -> {
            long v = value.applyAsLong(e);
            if (v > 0) {
                rows.add(new Row(id, e.name, v));
            }
        });
        rows.sort(Comparator.comparingLong(Row::value).reversed().thenComparing(r -> r.name().toLowerCase(Locale.ROOT)));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /**
     * Ranks players by numbers kept elsewhere (money, shards).
     *
     * @param values the numbers by player
     * @param limit  how many players at most
     * @return the best players first; players with 0 are left out
     */
    public synchronized List<Row> rank(Map<UUID, Long> values, int limit) {
        List<Row> rows = new ArrayList<>();
        values.forEach((id, v) -> {
            if (v > 0) {
                rows.add(new Row(id, name(id), v));
            }
        });
        rows.sort(Comparator.comparingLong(Row::value).reversed().thenComparing(r -> r.name().toLowerCase(Locale.ROOT)));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }
}
