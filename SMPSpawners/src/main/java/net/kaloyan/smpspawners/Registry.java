package net.kaloyan.smpspawners;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * All stacked spawners and the file they are kept in ({@code spawners.yml}, the format of the old ISpawners, so its data can be used
 * as it is). Changes that matter to a player (placing, stacking, taking out, selling) are written before the method returns and are
 * undone if the write fails; production only marks the data as changed and is saved every half minute. Main thread only.
 */
public final class Registry {

    private final Path file;
    private final Map<Spawner.Pos, Spawner> spawners = new LinkedHashMap<>();
    private boolean dirty;

    /** @param file the data file */
    public Registry(Path file) {
        this.file = file;
    }

    /**
     * Replaces everything in memory with the file's contents.
     *
     * @return notes about entries that were skipped (empty if all were read)
     * @throws IOException if the file exists but cannot be read; memory is then left as it was
     */
    public List<String> load() throws IOException {
        Map<Spawner.Pos, Spawner> loaded = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
            }
            ConfigurationSection section = yaml.getConfigurationSection("spawners");
            if (section != null) {
                for (String id : section.getKeys(false)) {
                    ConfigurationSection e = section.getConfigurationSection(id);
                    if (e == null || e.getString("world") == null || e.getString("type") == null || !e.contains("x") || !e.contains("y")
                            || !e.contains("z")) {
                        throw new IOException(file.getFileName() + ": spawner '" + id + "' is incomplete");
                    }
                    Spawner s = new Spawner(new Spawner.Pos(e.getString("world"), e.getInt("x"), e.getInt("y"), e.getInt("z")),
                            e.getString("type").toUpperCase(java.util.Locale.ROOT), Math.max(1, e.getInt("stack", 1)));
                    s.xp(e.getLong("xp", 0));
                    ConfigurationSection drops = e.getConfigurationSection("drops");
                    if (drops != null) {
                        for (String item : drops.getKeys(false)) {
                            s.addLoot(item.toUpperCase(java.util.Locale.ROOT), drops.getLong(item));
                        }
                    }
                    loaded.put(s.pos(), s);
                }
            }
        }
        spawners.clear();
        spawners.putAll(loaded);
        dirty = false;
        return notes;
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        int n = 0;
        for (Spawner s : spawners.values()) {
            String base = "spawners.s" + (n++);
            yaml.set(base + ".world", s.pos().world());
            yaml.set(base + ".x", s.pos().x());
            yaml.set(base + ".y", s.pos().y());
            yaml.set(base + ".z", s.pos().z());
            yaml.set(base + ".type", s.kind());
            yaml.set(base + ".stack", s.stack());
            yaml.set(base + ".xp", s.xp());
            s.loot().forEach((item, amount) -> yaml.set(base + ".drops." + item, amount));
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
        dirty = false;
    }

    /** @return the spawner at a place, or {@code null} */
    public Spawner at(Spawner.Pos pos) {
        return spawners.get(pos);
    }

    /** @return every spawner (live objects) */
    public Collection<Spawner> all() {
        return List.copyOf(spawners.values());
    }

    /**
     * Registers a new spawner and saves.
     *
     * @throws IOException if saving failed; the spawner is then not registered
     */
    public void add(Spawner spawner) throws IOException {
        Spawner old = spawners.put(spawner.pos(), spawner);
        try {
            write();
        } catch (IOException e) {
            if (old == null) {
                spawners.remove(spawner.pos());
            } else {
                spawners.put(spawner.pos(), old);
            }
            throw e;
        }
    }

    /**
     * Removes a spawner and saves.
     *
     * @return {@code false} if there was none
     * @throws IOException if saving failed; the spawner is then still registered
     */
    public boolean remove(Spawner.Pos pos) throws IOException {
        Spawner old = spawners.remove(pos);
        if (old == null) {
            return false;
        }
        try {
            write();
        } catch (IOException e) {
            spawners.put(pos, old);
            throw e;
        }
        return true;
    }

    /**
     * Saves after a change to one spawner's contents; on failure the caller's {@code undo} puts the contents back.
     *
     * @param undo restores the spawner to what it was before the change
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

    /** Marks the data as changed by production; {@link #saveIfDirty()} writes it later. */
    public void markDirty() {
        dirty = true;
    }

    /**
     * Writes the file if production changed something since the last write.
     *
     * @throws IOException if the write failed (it is tried again next time)
     */
    public void saveIfDirty() throws IOException {
        if (dirty) {
            write();
        }
    }
}
