package net.kaloyan.smpcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kaloyan.smpcore.api.ServiceRegistry;
import net.kaloyan.smpcore.config.ConfigManager;
import net.kaloyan.smpcore.service.SimpleServiceRegistry;
import net.kaloyan.smpcore.testutil.LogCapture;
import net.kaloyan.smpcore.testutil.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreApiTest {

    interface Economy {
    }

    @TempDir
    Path dir;

    private ConfigManager config;
    private SimpleServiceRegistry registry;
    private CoreApi api;

    @BeforeEach
    void setUp() throws Exception {
        config = TestSupport.loadedConfig(dir, "logging:\n  debug: true\n", new LogCapture().logger());
        registry = new SimpleServiceRegistry();
        api = new CoreApi("1.0.0", config, registry);
    }

    @Test
    void exposesVersionSettingsAndServicesWhileActive() {
        assertEquals("1.0.0", api.getVersion());
        assertEquals(config.current(), api.getSettings());
        assertSame(registry, api.getServices());
    }

    @Test
    void settingsReflectReloads() throws Exception {
        TestSupport.writeConfig(dir, "logging:\n  debug: false\n");
        config.reload().get();

        CoreSettings after = api.getSettings();

        assertEquals(false, after.debug());
    }

    @Test
    void invalidatedApiRejectsEveryCall() {
        api.invalidate();

        assertThrows(IllegalStateException.class, api::getVersion);
        assertThrows(IllegalStateException.class, api::getSettings);
        assertThrows(IllegalStateException.class, api::getServices);
    }

    @Test
    void registryHandleObtainedEarlierDiesWithTheRegistry() {
        ServiceRegistry held = api.getServices();
        held.register(TestSupport.plugin("Alpha"), Economy.class, new Economy() { });

        // What SMPCore#onDisable does through Lifecycle: registry shutdown, then API invalidation.
        registry.shutdown();
        api.invalidate();

        assertThrows(IllegalStateException.class, () -> held.get(Economy.class));
        assertThrows(IllegalStateException.class, api::getServices);
    }
}
