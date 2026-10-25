package net.kaloyan.smpprotect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Reads and writes {@code regions.yml}. A write goes to a temporary file first, so a crash never leaves half a file. */
public final class RegionStore {

    private final Path file;

    /** @param file the regions file */
    public RegionStore(Path file) {
        this.file = file;
    }

    /**
     * @return every stored region (empty if the file does not exist yet)
     * @throws IOException if the file exists but cannot be read or is invalid; nothing is guessed or skipped silently
     */
    public List<Region> load() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException(file.getFileName() + " is not valid YAML: " + e.getMessage(), e);
        }
        ConfigurationSection root = yaml.getConfigurationSection("regions");
        List<Region> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null || s.getString("world") == null || !s.isList("min") || !s.isList("max")) {
                throw new IOException("region '" + name + "' in " + file.getFileName() + " is incomplete");
            }
            List<Integer> min = ints(s, "min", name);
            List<Integer> max = ints(s, "max", name);
            Map<Flag, Boolean> flags = new EnumMap<>(Flag.class);
            ConfigurationSection fs = s.getConfigurationSection("flags");
            if (fs != null) {
                for (String id : fs.getKeys(false)) {
                    Flag flag = Flag.parse(id).orElseThrow(() -> new IOException("region '" + name + "' has an unknown flag '" + id + "'"));
                    String value = fs.getString(id, "");
                    if (!value.equals("allow") && !value.equals("deny")) {
                        throw new IOException("region '" + name + "' flag '" + id + "' must be allow or deny, not '" + value + "'");
                    }
                    flags.put(flag, value.equals("allow"));
                }
            }
            try {
                out.add(new Region(name, s.getString("world"), min.get(0), min.get(1), min.get(2), max.get(0), max.get(1),
                        max.get(2), s.getInt("priority", 0), uuids(s, "owners", name), uuids(s, "members", name), flags, s.getString("greeting", ""), s.getString("farewell", "")));
            } catch (IllegalArgumentException e) {
                throw new IOException("region '" + name + "': " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static List<Integer> ints(ConfigurationSection s, String key, String name) throws IOException {
        List<Integer> list = s.getIntegerList(key);
        if (list.size() != 3) {
            throw new IOException("region '" + name + "': '" + key + "' needs three numbers");
        }
        return list;
    }

    private static Set<UUID> uuids(ConfigurationSection s, String key, String name) throws IOException {
        Set<UUID> out = new TreeSet<>();
        for (String text : s.getStringList(key)) {
            try {
                out.add(UUID.fromString(text));
            } catch (IllegalArgumentException e) {
                throw new IOException("region '" + name + "': '" + text + "' in '" + key + "' is not a UUID");
            }
        }
        return out;
    }

    /**
     * Writes all regions.
     *
     * @param regions the regions to store
     * @throws IOException if the file cannot be written; the previous file is then still intact
     */
    public void save(Collection<Region> regions) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Region> sorted = regions.stream().sorted(java.util.Comparator.comparing(Region::name)).toList();
        for (Region r : sorted) {
            String base = "regions." + r.name();
            yaml.set(base + ".world", r.world());
            yaml.set(base + ".min", List.of(r.minX(), r.minY(), r.minZ()));
            yaml.set(base + ".max", List.of(r.maxX(), r.maxY(), r.maxZ()));
            yaml.set(base + ".priority", r.priority());
            yaml.set(base + ".owners", r.owners().stream().map(UUID::toString).sorted().toList());
            yaml.set(base + ".members", r.members().stream().map(UUID::toString).sorted().toList());
            Map<String, String> flags = new LinkedHashMap<>();
            r.flags().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> flags.put(e.getKey().id(), e.getValue() ? "allow" : "deny"));
            yaml.set(base + ".flags", flags);
            if (!r.greeting().isEmpty()) {
                yaml.set(base + ".greeting", r.greeting());
            }
            if (!r.farewell().isEmpty()) {
                yaml.set(base + ".farewell", r.farewell());
            }
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
    }
}
