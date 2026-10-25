package net.kaloyan.smpeconomy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigManagerTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final List<Runnable> ioTasks = new ArrayList<>();
    private ConfigManager manager;

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.writeConfig(dir, "currency:\n  symbol: \"$\"\nbalances:\n  starting: \"100.00\"\n");
        manager = new ConfigManager(dir.resolve("config.yml"), ioTasks::add, log.logger());
    }

    private void runIo() {
        List<Runnable> tasks = new ArrayList<>(ioTasks);
        ioTasks.clear();
        tasks.forEach(Runnable::run);
    }

    private ReloadResult reload() {
        var future = manager.reload();
        runIo();
        return future.join();
    }

    @Test
    void initialLoadPublishesSettings() throws Exception {
        EconomySettings loaded = manager.loadInitial();

        assertEquals(loaded, manager.current());
        assertEquals(10_000L, loaded.startingMinor());
    }

    @Test
    void currentThrowsBeforeInitialLoad() {
        assertThrows(IllegalStateException.class, manager::current);
    }

    @Test
    void initialLoadFailsWithEveryProblemListedWhenInvalid() throws Exception {
        TestSupport.writeConfig(dir, "balances:\n  starting: \"-5\"\ncurrency:\n  symbol: \"\"\n");

        InvalidConfigException e = assertThrows(InvalidConfigException.class, manager::loadInitial);

        assertEquals(2, e.problems().size());
        assertThrows(IllegalStateException.class, manager::current, "nothing may be published after a failed load");
    }

    @Test
    void initialLoadFailsOnMalformedYamlAndOnMissingFile() throws Exception {
        TestSupport.writeConfig(dir, "balances: [unclosed\n");
        assertTrue(assertThrows(InvalidConfigException.class, manager::loadInitial).getMessage().contains("not valid YAML"));

        Files.delete(dir.resolve("config.yml"));
        assertTrue(assertThrows(InvalidConfigException.class, manager::loadInitial).getMessage().contains("not found"));
    }

    @Test
    void reloadAppliesAFullyValidFile() throws Exception {
        manager.loadInitial();
        TestSupport.writeConfig(dir, "currency:\n  symbol: \"€\"\nbalances:\n  starting: \"5.00\"\nlogging:\n  debug: true\n");

        ReloadResult result = reload();

        assertTrue(result.success());
        assertEquals("€", manager.current().symbol());
        assertEquals(500L, manager.current().startingMinor());
        assertTrue(manager.current().debug());
    }

    @Test
    void failedReloadPreservesTheActiveSettingsCompletely() throws Exception {
        EconomySettings before = manager.loadInitial();
        // One valid change (symbol, debug) next to one invalid value: nothing may be applied.
        TestSupport.writeConfig(dir, "currency:\n  symbol: \"€\"\nlogging:\n  debug: true\nbalances:\n  starting: \"1.999\"\n");

        ReloadResult result = reload();

        assertFalse(result.success());
        assertEquals(1, result.problems().size());
        assertEquals(before, manager.current(), "no field of a partly valid file may leak into the active settings");
        assertEquals(before, result.settings());
        assertTrue(log.messages(Level.WARNING).stream().anyMatch(m -> m.contains("reload rejected")));
    }

    @Test
    void reloadWithMalformedYamlOrDeletedFileKeepsPreviousSettings() throws Exception {
        EconomySettings before = manager.loadInitial();

        TestSupport.writeConfig(dir, "currency: [unclosed\n");
        assertFalse(reload().success());
        assertEquals(before, manager.current());

        Files.delete(dir.resolve("config.yml"));
        ReloadResult missing = reload();
        assertFalse(missing.success());
        assertTrue(missing.problems().get(0).contains("not found"));
        assertEquals(before, manager.current());
    }

    @Test
    void reloadRecoversOnceTheFileIsFixed() throws Exception {
        manager.loadInitial();
        TestSupport.writeConfig(dir, "balances:\n  starting: \"nope\"\n");
        assertFalse(reload().success());

        TestSupport.writeConfig(dir, "balances:\n  starting: \"7.00\"\n");

        assertTrue(reload().success());
        assertEquals(700L, manager.current().startingMinor());
    }

    @Test
    void reloadDoesNotTouchTheFileUntilTheIoExecutorRuns() throws Exception {
        manager.loadInitial();
        TestSupport.writeConfig(dir, "balances:\n  starting: \"7.00\"\n");

        var future = manager.reload();

        assertEquals(1, ioTasks.size());
        assertFalse(future.isDone());
        assertEquals(10_000L, manager.current().startingMinor());
    }
}
