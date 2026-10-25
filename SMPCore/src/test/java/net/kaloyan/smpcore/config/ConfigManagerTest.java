package net.kaloyan.smpcore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kaloyan.smpcore.testutil.LogCapture;
import net.kaloyan.smpcore.testutil.TestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();

    private ConfigManager manager(Executor io) {
        return new ConfigManager(dir.resolve("config.yml"), io, log.logger());
    }

    @Test
    void currentThrowsBeforeInitialLoad() {
        ConfigManager manager = manager(Runnable::run);

        assertThrows(IllegalStateException.class, manager::current);
        assertThrows(IllegalStateException.class, manager::reload);
    }

    @Test
    void initialLoadPublishesSettings() throws Exception {
        TestSupport.writeConfig(dir, "logging:\n  debug: true\n");
        ConfigManager manager = manager(Runnable::run);

        CoreSettings loaded = manager.loadInitial();

        assertTrue(loaded.debug());
        assertEquals(loaded, manager.current());
    }

    @Test
    void initialLoadFailsWhenFileIsMissing() {
        InvalidConfigException e = assertThrows(InvalidConfigException.class, manager(Runnable::run)::loadInitial);

        assertTrue(e.problems().get(0).contains("not found"));
    }

    @Test
    void initialLoadFailsOnMalformedYaml() throws IOException {
        TestSupport.writeConfig(dir, "logging: [unclosed\n");

        InvalidConfigException e = assertThrows(InvalidConfigException.class, manager(Runnable::run)::loadInitial);

        assertTrue(e.problems().get(0).contains("not valid YAML"));
    }

    @Test
    void initialLoadFailureLeavesNothingPublished() throws IOException {
        TestSupport.writeConfig(dir, "logging:\n  debug: nope\n");
        ConfigManager manager = manager(Runnable::run);

        assertThrows(InvalidConfigException.class, manager::loadInitial);

        assertThrows(IllegalStateException.class, manager::current);
    }

    @Test
    void reloadAppliesValidChanges() throws Exception {
        ConfigManager manager = TestSupport.loadedConfig(dir, "logging:\n  debug: false\n", log.logger());
        TestSupport.writeConfig(dir, "logging:\n  debug: true\n");

        ReloadResult result = manager.reload().get();

        assertTrue(result.success());
        assertTrue(result.problems().isEmpty());
        assertTrue(manager.current().debug());
        assertEquals(manager.current(), result.settings());
    }

    @Test
    void reloadWithInvalidValuesKeepsPreviousSettings() throws Exception {
        ConfigManager manager = TestSupport.loadedConfig(dir, "logging:\n  debug: true\n", log.logger());
        CoreSettings before = manager.current();
        TestSupport.writeConfig(dir, "prefix: \"\"\nlogging:\n  debug: false\n");

        ReloadResult result = manager.reload().get();

        assertFalse(result.success());
        assertEquals(1, result.problems().size());
        assertEquals(before, result.settings());
        assertEquals(before, manager.current());
        assertFalse(log.messages(Level.WARNING).isEmpty(), "rejection should be logged");
    }

    @Test
    void reloadWithMalformedYamlKeepsPreviousSettings() throws Exception {
        ConfigManager manager = TestSupport.loadedConfig(dir, "logging:\n  debug: true\n", log.logger());
        CoreSettings before = manager.current();
        TestSupport.writeConfig(dir, "logging: [unclosed\n");

        ReloadResult result = manager.reload().get();

        assertFalse(result.success());
        assertTrue(result.problems().get(0).contains("not valid YAML"));
        assertEquals(before, manager.current());
    }

    @Test
    void reloadAfterFileWasDeletedKeepsPreviousSettings() throws Exception {
        ConfigManager manager = TestSupport.loadedConfig(dir, "logging:\n  debug: true\n", log.logger());
        CoreSettings before = manager.current();
        Files.delete(dir.resolve("config.yml"));

        ReloadResult result = manager.reload().get();

        assertFalse(result.success());
        assertTrue(result.problems().get(0).contains("not found"));
        assertEquals(before, manager.current());
    }

    @Test
    void reloadRecoversOnceTheFileIsFixed() throws Exception {
        ConfigManager manager = TestSupport.loadedConfig(dir, "logging:\n  debug: false\n", log.logger());
        TestSupport.writeConfig(dir, "logging:\n  debug: nope\n");
        assertFalse(manager.reload().get().success());

        TestSupport.writeConfig(dir, "logging:\n  debug: true\n");

        assertTrue(manager.reload().get().success());
        assertTrue(manager.current().debug());
    }

    @Test
    void reloadDoesNotTouchTheFileUntilTheIoExecutorRuns() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ConfigManager manager = new ConfigManager(dir.resolve("config.yml"), queued::add, log.logger());
        TestSupport.writeConfig(dir, "logging:\n  debug: false\n");
        manager.loadInitial();
        TestSupport.writeConfig(dir, "logging:\n  debug: true\n");

        var future = manager.reload();

        assertEquals(1, queued.size(), "file I/O must be handed to the I/O executor");
        assertFalse(future.isDone());
        assertFalse(manager.current().debug());

        queued.get(0).run();

        assertTrue(future.get().success());
        assertTrue(manager.current().debug());
    }
}
