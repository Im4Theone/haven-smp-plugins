package net.kaloyan.smpcore.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpcore.api.CoreSettings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Owns the active {@link CoreSettings}. Settings are read from {@code config.yml} by this class
 * directly rather than through {@code JavaPlugin#reloadConfig()}, because the latter silently
 * replaces a malformed file with an empty configuration, which would discard the admin's settings.
 *
 * <p>A reload is all-or-nothing: the file is parsed and validated first, and only a fully valid
 * result replaces the active settings.
 */
public final class ConfigManager {

    private final Path file;
    private final Executor ioExecutor;
    private final Logger logger;

    private volatile CoreSettings current;

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
     * Reads and validates the file synchronously. Intended for plugin startup only, where blocking
     * is acceptable and an invalid file must abort startup.
     *
     * @return the settings, which are now active
     * @throws InvalidConfigException if the file is missing, unreadable or invalid
     */
    public CoreSettings loadInitial() throws InvalidConfigException {
        CoreSettings loaded = read();
        current = loaded;
        return loaded;
    }

    /**
     * Returns the active settings snapshot.
     *
     * @return the current settings
     * @throws IllegalStateException if {@link #loadInitial()} has not succeeded yet
     */
    public CoreSettings current() {
        CoreSettings snapshot = current;
        if (snapshot == null) {
            throw new IllegalStateException("Configuration has not been loaded yet");
        }
        return snapshot;
    }

    /**
     * Re-reads {@code config.yml} on the I/O executor. The returned future completes on that
     * executor's thread; callers that touch Bukkit state must hop back to the main thread themselves.
     * The future never completes exceptionally because of an invalid file; that is reported through
     * {@link ReloadResult#success()}.
     *
     * @return the outcome of the reload
     * @throws IllegalStateException if {@link #loadInitial()} has not succeeded yet
     */
    public CompletableFuture<ReloadResult> reload() {
        current(); // fail fast if not loaded
        return CompletableFuture.supplyAsync(this::reloadNow, ioExecutor);
    }

    private synchronized ReloadResult reloadNow() {
        try {
            CoreSettings loaded = read();
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

    private CoreSettings read() throws InvalidConfigException {
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
