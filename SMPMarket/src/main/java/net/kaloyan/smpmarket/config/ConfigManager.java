package net.kaloyan.smpmarket.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Owns the active {@link MarketSettings}. Settings are read from {@code config.yml} by this class directly
 * rather than through {@code JavaPlugin#reloadConfig()}, because the latter silently replaces a malformed file
 * with an empty configuration.
 *
 * <p>A reload is all-or-nothing: the file is parsed and validated first, and only a fully valid result
 * replaces the active settings.
 */
public final class ConfigManager {

    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;

    private volatile MarketSettings current;

    /**
     * @param file       path of {@code config.yml}
     * @param ioExecutor runs the blocking file read during {@link #reload()}; must not be the main thread
     * @param logger     receives reload warnings and unexpected errors
     */
    public ConfigManager(Path file, Executor ioExecutor, Logger logger) {
        this.file = file;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
    }

    /**
     * Reads and validates the file synchronously. Intended for plugin startup only.
     *
     * @return the settings, which are now active
     * @throws InvalidConfigException if the file is missing, unreadable or invalid
     */
    public MarketSettings loadInitial() throws InvalidConfigException {
        MarketSettings loaded = read();
        current = loaded;
        return loaded;
    }

    /**
     * Returns the active settings snapshot.
     *
     * @return the current settings
     * @throws IllegalStateException if {@link #loadInitial()} has not succeeded yet
     */
    public MarketSettings current() {
        MarketSettings snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("Configuration has not been loaded yet");
        }
        return snapshot;
    }

    /**
     * Re-reads {@code config.yml} on the I/O executor. An invalid file is reported through
     * {@link ReloadResult#success()}, never as an exception, and leaves the active settings untouched.
     *
     * @return the outcome of the reload
     */
    public CompletableFuture<ReloadResult> reload() {
        current(); // fail fast if not loaded
        return CompletableFuture.supplyAsync(this::reloadNow, ioExecutor);
    }

    private synchronized ReloadResult reloadNow() {
        try {
            MarketSettings loaded = read();
            current = loaded;
            return ReloadResult.applied(loaded);
        } catch (InvalidConfigException e) {
            logger.warning("Configuration reload rejected, keeping previous settings: " + e.getMessage());
            return ReloadResult.rejected(current, e.problems());
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Unexpected error while reloading configuration; keeping previous settings", e);
            return ReloadResult.rejected(current,
                    List.of("Unexpected error (" + e.getClass().getSimpleName() + "); see the server console"));
        }
    }

    private MarketSettings read() throws InvalidConfigException {
        if (!Files.isRegularFile(file)) {
            throw new InvalidConfigException(List.of("Configuration file not found: " + file));
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException e) {
            throw new InvalidConfigException(List.of("Could not read " + file + ": " + e.getMessage()), e);
        } catch (InvalidConfigurationException e) {
            throw new InvalidConfigException(List.of("config.yml is not valid YAML: " + e.getMessage()), e);
        }
        return SettingsParser.parse(yaml);
    }
}
