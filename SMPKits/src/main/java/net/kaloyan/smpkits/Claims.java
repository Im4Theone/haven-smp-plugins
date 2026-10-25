package net.kaloyan.smpkits;

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

/** When each player last claimed each kit, in {@code claims.yml}. Each change is saved before it counts and undone if saving fails. */
public final class Claims {

    /** How a player's claim of a kit looks right now. */
    public enum State {
        /** The kit can be claimed. */
        READY,
        /** The kit is on cooldown. */
        COOLDOWN,
        /** The kit was claimed and can be claimed once only. */
        USED
    }

    /**
     * @param state     the state
     * @param remaining for {@code COOLDOWN}, how many milliseconds are left
     */
    public record Status(State state, long remaining) {
    }

    private final Path file;
    private final Map<UUID, Map<String, Long>> last = new HashMap<>();

    /** @param file the data file */
    public Claims(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public void load() throws IOException {
        last.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection claims = yaml.getConfigurationSection("claims");
            if (claims != null) {
                for (String player : claims.getKeys(false)) {
                    ConfigurationSection s = claims.getConfigurationSection(player);
                    Map<String, Long> kits = new HashMap<>();
                    for (String kit : s.getKeys(false)) {
                        kits.put(kit, s.getLong(kit));
                    }
                    last.put(UUID.fromString(player), kits);
                }
            }
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        last.forEach((player, kits) -> kits.forEach((kit, time) -> yaml.set("claims." + player + "." + kit, time)));
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
     * @param player who
     * @param kit    the kit
     * @param now    the time
     * @return whether the player can claim the kit now
     */
    public Status status(UUID player, Kit kit, long now) {
        Long time = last.getOrDefault(player, Map.of()).get(kit.id());
        if (time == null) {
            return new Status(State.READY, 0);
        }
        if (kit.cooldownMillis() == Kit.ONCE) {
            return new Status(State.USED, 0);
        }
        long left = time + kit.cooldownMillis() - now;
        return left > 0 ? new Status(State.COOLDOWN, left) : new Status(State.READY, 0);
    }

    /**
     * Notes a claim and saves.
     *
     * @param player who
     * @param kit    the kit id
     * @param now    the time
     * @throws IOException if saving failed; the claim is then not noted
     */
    public void claim(UUID player, String kit, long now) throws IOException {
        Long before = last.computeIfAbsent(player, p -> new HashMap<>()).put(kit, now);
        try {
            write();
        } catch (IOException e) {
            if (before == null) {
                last.get(player).remove(kit);
            } else {
                last.get(player).put(kit, before);
            }
            throw e;
        }
    }

    /**
     * Forgets a claim so the kit can be claimed again, and saves.
     *
     * @param player who
     * @param kit    the kit id
     * @return whether there was a claim
     * @throws IOException if saving failed; the claim is then still noted
     */
    public boolean reset(UUID player, String kit) throws IOException {
        Map<String, Long> kits = last.get(player);
        Long before = kits == null ? null : kits.remove(kit);
        if (before == null) {
            return false;
        }
        try {
            write();
        } catch (IOException e) {
            kits.put(kit, before);
            throw e;
        }
        return true;
    }
}
