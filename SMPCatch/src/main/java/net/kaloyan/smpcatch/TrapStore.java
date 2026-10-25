package net.kaloyan.smpcatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Every decoy that exists, and the file it is kept in ({@code traps.yml}). Main thread only. */
public final class TrapStore {

    /**
     * One block that belongs to a decoy.
     *
     * @param x        block x
     * @param y        block y
     * @param z        block z
     * @param original what the block was before (restored when the decoy is removed)
     * @param role     {@code ore} (a fake ore), {@code container} (the chest of a stash) or {@code cavity} (an empty room)
     */
    public record Part(int x, int y, int z, String original, String role) {
    }

    /** One decoy: a fake vein or a hidden stash. */
    public static final class Trap {
        private final int id;
        private final String kind;
        private final String type;
        private final String world;
        private final long created;
        private final List<Part> parts;
        private int triggers;

        /**
         * @param id      the number
         * @param kind    {@code VEIN} or {@code STASH}
         * @param type    what it imitates, for example {@code diamond}
         * @param world   the world name
         * @param created when it was made
         * @param parts   its blocks
         */
        public Trap(int id, String kind, String type, String world, long created, List<Part> parts) {
            this.id = id;
            this.kind = kind;
            this.type = type;
            this.world = world;
            this.created = created;
            this.parts = List.copyOf(parts);
        }

        /** @return the number */
        public int id() {
            return id;
        }

        /** @return {@code VEIN} or {@code STASH} */
        public String kind() {
            return kind;
        }

        /** @return what it imitates */
        public String type() {
            return type;
        }

        /** @return the world name */
        public String world() {
            return world;
        }

        /** @return when it was made */
        public long created() {
            return created;
        }

        /** @return its blocks */
        public List<Part> parts() {
            return parts;
        }

        /** @return how often someone triggered it */
        public int triggers() {
            return triggers;
        }

        /** @return the block to teleport to: the first fake ore or the container */
        public Part focus() {
            for (Part p : parts) {
                if (!p.role().equals("cavity")) {
                    return p;
                }
            }
            return parts.get(0);
        }
    }

    private final Path file;
    private final Map<Integer, Trap> traps = new LinkedHashMap<>();
    private final Map<String, Trap> index = new HashMap<>();
    private int nextId = 1;
    private boolean dirty;

    /** @param file the data file */
    public TrapStore(Path file) {
        this.file = file;
    }

    /**
     * @param world a world name
     * @param x     block x
     * @param y     block y
     * @param z     block z
     * @return a lookup key
     */
    static String key(String world, int x, int y, int z) {
        return world + ';' + x + ';' + y + ';' + z;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public void load() throws IOException {
        traps.clear();
        index.clear();
        nextId = 1;
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
        }
        ConfigurationSection section = yaml.getConfigurationSection("traps");
        if (section == null) {
            return;
        }
        try {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                List<Part> parts = new ArrayList<>();
                for (String line : s.getStringList("parts")) {
                    String[] f = line.split(",");
                    parts.add(new Part(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], f[4]));
                }
                if (parts.isEmpty()) {
                    throw new IllegalArgumentException("trap " + key + " has no blocks");
                }
                Trap t = new Trap(Integer.parseInt(key), s.getString("kind", "VEIN"), s.getString("type", "?"), s.getString("world", "?"),
                        s.getLong("created"), parts);
                t.triggers = s.getInt("triggers");
                register(t);
            }
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void register(Trap t) {
        traps.put(t.id, t);
        nextId = Math.max(nextId, t.id + 1);
        for (Part p : t.parts) {
            if (!p.role().equals("cavity")) {
                index.put(key(t.world, p.x(), p.y(), p.z()), t);
            }
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Trap t : traps.values()) {
            String b = "traps." + t.id;
            yaml.set(b + ".kind", t.kind);
            yaml.set(b + ".type", t.type);
            yaml.set(b + ".world", t.world);
            yaml.set(b + ".created", t.created);
            yaml.set(b + ".triggers", t.triggers);
            List<String> lines = new ArrayList<>();
            t.parts.forEach(p -> lines.add(p.x() + "," + p.y() + "," + p.z() + "," + p.original() + "," + p.role()));
            yaml.set(b + ".parts", lines);
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

    /** @return a number for the next decoy */
    public int nextId() {
        return nextId;
    }

    /**
     * Adds a decoy and saves.
     *
     * @param trap the decoy
     * @throws IOException if saving failed; the decoy is then not added
     */
    public void add(Trap trap) throws IOException {
        register(trap);
        try {
            write();
        } catch (IOException e) {
            forget(trap);
            throw e;
        }
    }

    private void forget(Trap trap) {
        traps.remove(trap.id);
        index.values().removeIf(t -> t == trap);
    }

    /**
     * Removes a decoy from the list (the world is not touched) and saves.
     *
     * @param id the number
     * @return the removed decoy, or {@code null}
     * @throws IOException if saving failed; the decoy is then still there
     */
    public Trap remove(int id) throws IOException {
        Trap t = traps.get(id);
        if (t == null) {
            return null;
        }
        forget(t);
        try {
            write();
        } catch (IOException e) {
            register(t);
            throw e;
        }
        return t;
    }

    /**
     * Forgets one fake ore that has been mined; the whole decoy goes when no fake ore or container is left.
     *
     * @param trap the decoy
     * @param part the block that was mined
     * @return whether the whole decoy is gone now
     */
    public boolean mined(Trap trap, Part part) {
        index.remove(key(trap.world, part.x(), part.y(), part.z()));
        boolean left = index.values().stream().anyMatch(t -> t == trap);
        if (!left) {
            traps.remove(trap.id);
        }
        dirty = true;
        return !left;
    }

    /** @return the decoy that has a fake ore or a container at this block, or {@code null} */
    public Trap at(String world, int x, int y, int z) {
        return index.get(key(world, x, y, z));
    }

    /** @return the decoy with that number, or {@code null} */
    public Trap get(int id) {
        return traps.get(id);
    }

    /** @return every decoy */
    public List<Trap> all() {
        return new ArrayList<>(traps.values());
    }

    /** Counts a trigger; saved later. */
    public void triggered(Trap trap) {
        trap.triggers++;
        dirty = true;
    }

    /** Writes the file if anything changed. */
    public void saveIfDirty() {
        if (dirty) {
            try {
                write();
            } catch (IOException e) {
                dirty = true;
            }
        }
    }
}
