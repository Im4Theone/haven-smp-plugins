package net.kaloyan.smpcatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** The violation points of players, with the last few things each one did. Points fade away slowly. Saved in {@code suspects.yml}. */
public final class Suspects {

    /** One thing a player did. */
    public record Event(long time, String what) {
    }

    /** What is known about one player. */
    public static final class Suspect {
        private final UUID id;
        private String name;
        private double points;
        private long updated;
        private int fired;
        private int hiddenRare;
        private final List<Event> events = new ArrayList<>();

        Suspect(UUID id, String name, long now) {
            this.id = id;
            this.name = name;
            this.updated = now;
        }

        /** @return the player */
        public UUID id() {
            return id;
        }

        /** @return the player's last known name */
        public String name() {
            return name;
        }

        /** @return how many hidden rare ores they mined in total */
        public int hiddenRare() {
            return hiddenRare;
        }

        /** @return the last things they did, oldest first */
        public List<Event> events() {
            return List.copyOf(events);
        }
    }

    private static final int EVENT_LIMIT = 20;

    private final Path file;
    private final double decayPerHour;
    private final Map<UUID, Suspect> suspects = new HashMap<>();
    private boolean dirty;

    /**
     * @param file         the data file
     * @param decayPerHour points lost per hour
     */
    public Suspects(Path file, double decayPerHour) {
        this.file = file;
        this.decayPerHour = decayPerHour;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public void load() throws IOException {
        suspects.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
        }
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        try {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                Suspect x = new Suspect(UUID.fromString(key), s.getString("name", "?"), s.getLong("updated"));
                x.points = s.getDouble("points");
                x.fired = s.getInt("fired");
                x.hiddenRare = s.getInt("hidden-rare");
                for (String line : s.getStringList("events")) {
                    int cut = line.indexOf('|');
                    x.events.add(new Event(Long.parseLong(line.substring(0, cut)), line.substring(cut + 1)));
                }
                suspects.put(x.id, x);
            }
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    /** Writes the file if anything changed. */
    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Suspect x : suspects.values()) {
            String b = "players." + x.id;
            yaml.set(b + ".name", x.name);
            yaml.set(b + ".points", x.points);
            yaml.set(b + ".updated", x.updated);
            yaml.set(b + ".fired", x.fired);
            yaml.set(b + ".hidden-rare", x.hiddenRare);
            List<String> lines = new ArrayList<>();
            x.events.forEach(e -> lines.add(e.time() + "|" + e.what()));
            yaml.set(b + ".events", lines);
        }
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
            // the data stays in memory and the next save tries again
            dirty = true;
        }
    }

    private void fade(Suspect x, long now) {
        double hours = Math.max(0, now - x.updated) / 3_600_000.0;
        x.points = Math.max(0, x.points - hours * decayPerHour);
        x.updated = now;
    }

    /**
     * @param id   the player
     * @param now  the time, epoch milliseconds
     * @return their points now, zero if unknown
     */
    public double points(UUID id, long now) {
        Suspect x = suspects.get(id);
        if (x == null) {
            return 0;
        }
        fade(x, now);
        return x.points;
    }

    /** @return what is known about a player, or {@code null} */
    public Suspect get(UUID id) {
        return suspects.get(id);
    }

    /** @return everyone with a record, highest points first */
    public List<Suspect> ranked(long now) {
        List<Suspect> list = new ArrayList<>(suspects.values());
        list.forEach(x -> fade(x, now));
        list.sort((a, b) -> Double.compare(b.points, a.points));
        return list;
    }

    /**
     * Adds points and notes what happened.
     *
     * @param id         the player
     * @param name       their name
     * @param now        the time
     * @param points     points to add (may be zero to only note the event)
     * @param what       what they did
     * @param thresholds the point levels at which something should happen, ascending
     * @return the indexes of the thresholds this change crossed for the first time
     */
    public List<Integer> add(UUID id, String name, long now, double points, String what, List<Double> thresholds) {
        Suspect x = suspects.computeIfAbsent(id, k -> new Suspect(id, name, now));
        fade(x, now);
        x.name = name;
        x.points += points;
        x.events.add(new Event(now, what));
        while (x.events.size() > EVENT_LIMIT) {
            x.events.remove(0);
        }
        List<Integer> crossed = new ArrayList<>();
        for (int i = x.fired; i < thresholds.size(); i++) {
            if (x.points >= thresholds.get(i)) {
                crossed.add(i);
                x.fired = i + 1;
            }
        }
        dirty = true;
        return crossed;
    }

    /**
     * Counts a hidden rare ore for the lifetime statistics.
     *
     * @param id   the player
     * @param name their name
     * @param now  the time
     */
    public void countHiddenRare(UUID id, String name, long now) {
        suspects.computeIfAbsent(id, k -> new Suspect(id, name, now)).hiddenRare++;
        dirty = true;
    }

    /**
     * Clears a player's points and history.
     *
     * @param id the player
     * @return whether there was anything
     */
    public boolean clear(UUID id) {
        boolean had = suspects.remove(id) != null;
        dirty = dirty || had;
        return had;
    }
}
