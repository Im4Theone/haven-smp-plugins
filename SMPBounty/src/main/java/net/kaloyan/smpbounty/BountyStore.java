package net.kaloyan.smpbounty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** The bounties and the money owed to players, in {@code bounties.yml}. Changes are saved before they count and undone if saving fails. */
public final class BountyStore {

    /** The bounty on one player: what each person put on it. */
    public static final class Bounty {
        private final UUID target;
        private String targetName;
        private final Map<UUID, Long> from = new LinkedHashMap<>();

        Bounty(UUID target, String targetName) {
            this.target = target;
            this.targetName = targetName;
        }

        /** @return the player the bounty is on */
        public UUID target() {
            return target;
        }

        /** @return their name */
        public String targetName() {
            return targetName;
        }

        /** @return the total in hundredths */
        public long total() {
            return from.values().stream().mapToLong(Long::longValue).sum();
        }

        /** @return whether this person put money on it */
        public boolean contributed(UUID who) {
            return from.containsKey(who);
        }
    }

    private final Path file;
    private final Map<UUID, Bounty> bounties = new LinkedHashMap<>();
    private final Map<UUID, Long> owed = new HashMap<>();

    /** @param file the data file */
    public BountyStore(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public void load() throws IOException {
        bounties.clear();
        owed.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection list = yaml.getConfigurationSection("bounties");
            if (list != null) {
                for (String key : list.getKeys(false)) {
                    ConfigurationSection s = list.getConfigurationSection(key);
                    Bounty b = new Bounty(UUID.fromString(key), s.getString("name", "?"));
                    ConfigurationSection from = s.getConfigurationSection("from");
                    if (from != null) {
                        for (String who : from.getKeys(false)) {
                            long cents = from.getLong(who);
                            if (cents < 1) {
                                throw new IllegalArgumentException("a contribution is not positive");
                            }
                            b.from.put(UUID.fromString(who), cents);
                        }
                    }
                    if (b.from.isEmpty()) {
                        throw new IllegalArgumentException("bounty " + key + " has no money");
                    }
                    bounties.put(b.target, b);
                }
            }
            ConfigurationSection o = yaml.getConfigurationSection("owed");
            if (o != null) {
                for (String key : o.getKeys(false)) {
                    owed.put(UUID.fromString(key), o.getLong(key));
                }
            }
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        bounties.forEach((id, b) -> {
            yaml.set("bounties." + id + ".name", b.targetName);
            b.from.forEach((who, cents) -> yaml.set("bounties." + id + ".from." + who, cents));
        });
        owed.forEach((id, cents) -> yaml.set("owed." + id, cents));
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

    /** @return the bounty on a player, or {@code null} */
    public Bounty get(UUID target) {
        return bounties.get(target);
    }

    /** @return every bounty, the biggest first */
    public List<Bounty> ranked() {
        List<Bounty> list = new ArrayList<>(bounties.values());
        list.sort(Comparator.comparingLong(Bounty::total).reversed().thenComparing(b -> b.targetName.toLowerCase(Locale.ROOT)));
        return list;
    }

    /** @return what is owed to a player */
    public long owed(UUID id) {
        return owed.getOrDefault(id, 0L);
    }

    /** @return the players owed money */
    public List<UUID> owedPlayers() {
        return new ArrayList<>(owed.keySet());
    }

    /**
     * Adds money to the bounty on a player and saves.
     *
     * @param target     the player
     * @param targetName their name
     * @param from       who pays
     * @param cents      how much
     * @return the bounty
     * @throws IOException if saving failed; the bounty is then as before
     */
    public Bounty add(UUID target, String targetName, UUID from, long cents) throws IOException {
        Bounty existing = bounties.get(target);
        Bounty b = existing != null ? existing : new Bounty(target, targetName);
        Long before = b.from.get(from);
        String nameBefore = b.targetName;
        b.targetName = targetName;
        b.from.merge(from, cents, Long::sum);
        bounties.put(target, b);
        try {
            write();
        } catch (IOException e) {
            if (before == null) {
                b.from.remove(from);
            } else {
                b.from.put(from, before);
            }
            b.targetName = nameBefore;
            if (existing == null) {
                bounties.remove(target);
            }
            throw e;
        }
        return b;
    }

    /**
     * Removes the bounty on a player and owes its total to the killer, in one save.
     *
     * @param target the player who was killed
     * @param killer who gets the money
     * @return the money in hundredths, or 0 if there was no bounty
     * @throws IOException if saving failed; nothing changed
     */
    public long collect(UUID target, UUID killer) throws IOException {
        Bounty b = bounties.remove(target);
        if (b == null) {
            return 0;
        }
        long total = b.total();
        owed.merge(killer, total, Long::sum);
        try {
            write();
        } catch (IOException e) {
            bounties.put(target, b);
            long left = owed.merge(killer, -total, Long::sum);
            if (left <= 0) {
                owed.remove(killer);
            }
            throw e;
        }
        return total;
    }

    /**
     * Takes and returns what is owed to a player, saved before it is paid.
     *
     * @param id the player
     * @return the amount, 0 if nothing
     * @throws IOException if saving failed; the amount stays owed
     */
    public long takeOwed(UUID id) throws IOException {
        Long cents = owed.remove(id);
        if (cents == null) {
            return 0;
        }
        try {
            write();
        } catch (IOException e) {
            owed.put(id, cents);
            throw e;
        }
        return cents;
    }

    /**
     * Puts money back on the owed list (a payment definitely failed).
     *
     * @param id    the player
     * @param cents the amount
     */
    public void owe(UUID id, long cents) {
        owed.merge(id, cents, Long::sum);
        try {
            write();
        } catch (IOException e) {
            // stays in memory; the next change writes it
        }
    }
}
