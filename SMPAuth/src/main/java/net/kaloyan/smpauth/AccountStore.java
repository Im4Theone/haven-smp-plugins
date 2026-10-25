package net.kaloyan.smpauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * The accounts of players who play without a bought account ({@code auth.yml}), and the names that belong to bought accounts. Accounts are
 * kept by lower case name. Changes to accounts are saved before they count and undone if saving fails. All methods are synchronized
 * because the login check runs off the main thread.
 */
public final class AccountStore {

    /**
     * One account.
     *
     * @param name       the name with the capitals it was registered with
     * @param hash       the password hash
     * @param registered when it was made, epoch milliseconds
     * @param lastLogin  the last login, epoch milliseconds
     * @param lastIp     the address of the last login
     */
    public record Account(String name, String hash, long registered, long lastLogin, String lastIp) {
    }

    private final Path file;
    private final Map<String, Account> accounts = new HashMap<>();
    private final Set<String> premium = new HashSet<>();
    private boolean dirty;

    /** @param file the data file */
    public AccountStore(Path file) {
        this.file = file;
    }

    /**
     * Reads the file if it exists.
     *
     * @throws IOException if it cannot be read
     */
    public synchronized void load() throws IOException {
        accounts.clear();
        premium.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            ConfigurationSection list = yaml.getConfigurationSection("accounts");
            if (list != null) {
                for (String key : list.getKeys(false)) {
                    ConfigurationSection s = list.getConfigurationSection(key);
                    String hash = s.getString("hash");
                    String name = s.getString("name");
                    if (hash == null || name == null || !name.toLowerCase(Locale.ROOT).equals(key)) {
                        throw new IllegalArgumentException("account '" + key + "' is incomplete");
                    }
                    accounts.put(key, new Account(name, hash, s.getLong("registered"), s.getLong("last-login"), s.getString("last-ip")));
                }
            }
            for (String name : yaml.getStringList("premium-names")) {
                premium.add(name.toLowerCase(Locale.ROOT));
            }
        } catch (InvalidConfigurationException | RuntimeException e) {
            throw new IOException(file.getFileName() + " is damaged: " + e.getMessage(), e);
        }
    }

    private void write() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        accounts.forEach((key, a) -> {
            yaml.set("accounts." + key + ".name", a.name());
            yaml.set("accounts." + key + ".hash", a.hash());
            yaml.set("accounts." + key + ".registered", a.registered());
            yaml.set("accounts." + key + ".last-login", a.lastLogin());
            yaml.set("accounts." + key + ".last-ip", a.lastIp());
        });
        yaml.set("premium-names", premium.stream().sorted().toList());
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

    /** Writes the file if the last login times or the premium names changed. */
    public synchronized void saveIfDirty() {
        if (dirty) {
            try {
                write();
            } catch (IOException e) {
                dirty = true;
            }
        }
    }

    /** @return the account of a name (any case), or {@code null} */
    public synchronized Account get(String name) {
        return accounts.get(name.toLowerCase(Locale.ROOT));
    }

    /** @return how many accounts there are */
    public synchronized int size() {
        return accounts.size();
    }

    /** @return how many accounts last logged in from this address */
    public synchronized int countByIp(String ip) {
        return (int) accounts.values().stream().filter(a -> ip != null && ip.equals(a.lastIp())).count();
    }

    /**
     * Makes an account and saves.
     *
     * @param name the name
     * @param hash the password hash
     * @param ip   the address
     * @param now  the time
     * @throws IOException if saving failed; there is then no account
     */
    public synchronized void register(String name, String hash, String ip, long now) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        accounts.put(key, new Account(name, hash, now, now, ip));
        try {
            write();
        } catch (IOException e) {
            accounts.remove(key);
            throw e;
        }
    }

    /**
     * Changes a password and saves.
     *
     * @param name the name
     * @param hash the new hash
     * @throws IOException if saving failed; the old password stays
     */
    public synchronized void setHash(String name, String hash) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        Account old = accounts.get(key);
        if (old == null) {
            return;
        }
        accounts.put(key, new Account(old.name(), hash, old.registered(), old.lastLogin(), old.lastIp()));
        try {
            write();
        } catch (IOException e) {
            accounts.put(key, old);
            throw e;
        }
    }

    /**
     * Deletes an account and saves.
     *
     * @param name the name
     * @return whether there was one
     * @throws IOException if saving failed; the account stays
     */
    public synchronized boolean remove(String name) throws IOException {
        String key = name.toLowerCase(Locale.ROOT);
        Account old = accounts.remove(key);
        if (old == null) {
            return false;
        }
        try {
            write();
        } catch (IOException e) {
            accounts.put(key, old);
            throw e;
        }
        return true;
    }

    /** Notes a login (saved later). */
    public synchronized void touch(String name, String ip, long now) {
        String key = name.toLowerCase(Locale.ROOT);
        Account old = accounts.get(key);
        if (old != null) {
            accounts.put(key, new Account(old.name(), old.hash(), old.registered(), now, ip));
            dirty = true;
        }
    }

    /** Notes that a name belongs to a bought account (saved later). */
    public synchronized void markPremium(String name) {
        if (premium.add(name.toLowerCase(Locale.ROOT))) {
            dirty = true;
        }
    }

    /**
     * Forgets that a name belongs to a bought account (saved later).
     *
     * @return whether it was known
     */
    public synchronized boolean unmarkPremium(String name) {
        boolean had = premium.remove(name.toLowerCase(Locale.ROOT));
        dirty |= had;
        return had;
    }

    /** @return whether a bought account was seen with this name */
    public synchronized boolean isPremiumName(String name) {
        return premium.contains(name.toLowerCase(Locale.ROOT));
    }
}
