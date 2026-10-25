package net.kaloyan.smpcore.testutil;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import net.kaloyan.smpcore.config.ConfigManager;
import net.kaloyan.smpcore.config.InvalidConfigException;
import org.bukkit.plugin.Plugin;

/** Small factories shared by tests. */
public final class TestSupport {

    private TestSupport() {
    }

    /** Writes {@code yaml} to {@code <dir>/config.yml}. */
    public static Path writeConfig(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);
        return file;
    }

    /** A manager over {@code <dir>/config.yml} that runs its I/O inline, already loaded from that file. */
    public static ConfigManager loadedConfig(Path dir, String yaml, Logger logger)
            throws IOException, InvalidConfigException {
        Path file = writeConfig(dir, yaml);
        ConfigManager manager = new ConfigManager(file, Runnable::run, logger);
        manager.loadInitial();
        return manager;
    }

    /** A mock plugin that only knows its name. */
    public static Plugin plugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }
}
