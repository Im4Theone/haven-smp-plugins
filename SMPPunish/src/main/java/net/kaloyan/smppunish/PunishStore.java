package net.kaloyan.smppunish;

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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Every punishment and every player the server has seen, in {@code punishments.yml}. Changes are written before the method returns and
 * undone if the write fails. All methods are synchronized because the login check reads from another thread.
 */
public final class PunishStore {

    /**
     * What is known about a player.
     *
     * @param id       the player
     * @param name     their last name
     * @param ip       their last address
     * @param lastSeen when they were last seen, epoch milliseconds
     */
    public record Known(UUID id, String name, String ip, long lastSeen) {
    }

    private final Path file;
    private final Map<Long, Punishment> all = new LinkedHashMap<>();
    private final Map<UUID, Known> known = new HashMap<>();
    private long nextId = 1;
    private boolean dirty;

    /** @param file the data file */
    public PunishStore(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public synchronized void load() throws IOException {
        all.clear();
        known.clear();
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
        try {
            ConfigurationSection list = yaml.getConfigurationSection("punishments");
            if (list != null) {
                for (String key : list.getKeys(false)) {
                    ConfigurationSection s = list.getConfigurationSection(key);
                    Punishment p = new Punishment(Long.parseLong(key), Punishment.Type.valueOf(s.getString("type", "")),
                            UUID.fromString(s.getString("target", "")), s.getString("target-name", "?"), s.getString("ip"), s.getString("staff", "?"),
                            s.getString("reason", ""), s.getLong("created"), s.getLong("expires"));
                    if (!s.getBoolean("active", true)) {
                        p.lift(s.getString("removed-by", ""), s.getLong("removed-at"), s.getString("remove-reason", ""));
                    }
                    all.put(p.id(), p);
                    nextId = Math.max(nextId, p.id() + 1);
                }
            }
            ConfigurationSection players = yaml.getConfigurationSection("players");
            if (players != null) {
                for (String key : players.getKeys(false)) {
                    ConfigurationSection s = players.getConfigurationSection(key);
                    known.put(UUID.fromString(key), new Known(UUID.fromString(key), s.getString("name", "?"), s.getString("ip"), s.getLong("last-seen")));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Punishment p : all.values()) {
            String b = "punishments." + p.id();
            yaml.set(b + ".type", p.type().name());
            yaml.set(b + ".target", p.target().toString());
            yaml.set(b + ".target-name", p.targetName());
            yaml.set(b + ".ip", p.ip());
            yaml.set(b + ".staff", p.staff());
            yaml.set(b + ".reason", p.reason());
            yaml.set(b + ".created", p.created());
            yaml.set(b + ".expires", p.expires());
            if (!p.active()) {
                yaml.set(b + ".active", false);
                yaml.set(b + ".removed-by", p.removedBy());
                yaml.set(b + ".removed-at", p.removedAt());
                yaml.set(b + ".remove-reason", p.removeReason());
            }
        }
        for (Known k : known.values()) {
            String b = "players." + k.id();
            yaml.set(b + ".name", k.name());
            yaml.set(b + ".ip", k.ip());
            yaml.set(b + ".last-seen", k.lastSeen());
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

    /** @return the number for the next entry */
    public synchronized long nextId() {
        return nextId;
    }

    /**
     * Adds an entry and saves.
     *
     * @param p the entry
     * @throws IOException if saving failed; the entry is then not added
     */
    public synchronized void add(Punishment p) throws IOException {
        long before = nextId;
        all.put(p.id(), p);
        nextId = Math.max(nextId, p.id() + 1);
        try {
            write();
        } catch (IOException e) {
            all.remove(p.id());
            nextId = before;
            throw e;
        }
    }

    /**
     * Lifts an entry and saves.
     *
     * @param p      the entry
     * @param by     who lifts it
     * @param at     when
     * @param reason why
     * @throws IOException if saving failed; the entry is then still in force
     */
    public synchronized void lift(Punishment p, String by, long at, String reason) throws IOException {
        boolean was = p.active();
        String b = p.removedBy();
        long a = p.removedAt();
        String r = p.removeReason();
        p.lift(by, at, reason);
        try {
            write();
        } catch (IOException e) {
            p.restore(was, b, a, r);
            throw e;
        }
    }

    /** @return the entry with that number, or {@code null} */
    public synchronized Punishment get(long id) {
        return all.get(id);
    }

    /** @return every entry of a player, newest first */
    public synchronized List<Punishment> of(UUID player) {
        List<Punishment> out = new ArrayList<>();
        for (Punishment p : all.values()) {
            if (p.target().equals(player)) {
                out.add(p);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * @param type the kind
     * @param now  the time
     * @return every entry of that kind that is in force, newest first
     */
    public synchronized List<Punishment> inForce(Punishment.Type type, long now) {
        List<Punishment> out = new ArrayList<>();
        for (Punishment p : all.values()) {
            if (p.type() == type && p.inForce(now)) {
                out.add(p);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Finds the ban that keeps someone out.
     *
     * @param player the player logging in
     * @param ip     their address (may be {@code null})
     * @param now    the time
     * @return the ban in force against the player or their address, or {@code null}
     */
    public synchronized Punishment banFor(UUID player, String ip, long now) {
        Punishment found = null;
        for (Punishment p : all.values()) {
            if (p.type() == Punishment.Type.BAN && p.inForce(now) && (p.target().equals(player) || ip != null && ip.equals(p.ip()))) {
                if (found == null || p.expires() == 0 || found.expires() != 0 && p.expires() > found.expires()) {
                    found = p;
                }
            }
        }
        return found;
    }

    /**
     * @param player the player
     * @param now    the time
     * @return the mute in force against the player, or {@code null}
     */
    public synchronized Punishment muteFor(UUID player, long now) {
        for (Punishment p : all.values()) {
            if (p.type() == Punishment.Type.MUTE && p.inForce(now) && p.target().equals(player)) {
                return p;
            }
        }
        return null;
    }

    /**
     * @param player     the player
     * @param now        the time
     * @param expiryDays warnings older than this many days do not count
     * @return how many warnings count against the player
     */
    public synchronized int warnings(UUID player, long now, int expiryDays) {
        int n = 0;
        long limit = now - expiryDays * 86_400_000L;
        for (Punishment p : all.values()) {
            if (p.type() == Punishment.Type.WARN && p.active() && p.target().equals(player) && p.created() >= limit) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- players seen

    /**
     * Notes that a player was seen; saved later by {@link #saveIfDirty()}.
     *
     * @param id   the player
     * @param name their name
     * @param ip   their address
     * @param now  the time
     */
    public synchronized void seen(UUID id, String name, String ip, long now) {
        known.put(id, new Known(id, name, ip, now));
        dirty = true;
    }

    /** @return the known player with that name (any case), or {@code null} */
    public synchronized Known byName(String name) {
        Known best = null;
        for (Known k : known.values()) {
            if (k.name().equalsIgnoreCase(name) && (best == null || k.lastSeen() > best.lastSeen())) {
                best = k;
            }
        }
        return best;
    }

    /** @return the known player, or {@code null} */
    public synchronized Known byId(UUID id) {
        return known.get(id);
    }

    /** @return the other known players that last used the same address */
    public synchronized List<Known> sameAddress(UUID id) {
        Known me = known.get(id);
        List<Known> out = new ArrayList<>();
        if (me != null && me.ip() != null) {
            for (Known k : known.values()) {
                if (!k.id().equals(id) && me.ip().equals(k.ip())) {
                    out.add(k);
                }
            }
        }
        out.sort((a, b) -> a.name().toLowerCase(Locale.ROOT).compareTo(b.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Writes the file if anything about players seen changed. */
    public synchronized void saveIfDirty() {
        if (dirty) {
            try {
                write();
            } catch (IOException e) {
                dirty = true;
            }
        }
    }
}
