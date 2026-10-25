package net.kaloyan.smpcore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.util.logging.Level;
import net.kaloyan.smpcore.testutil.LogCapture;
import net.kaloyan.smpcore.testutil.TestSupport;
import org.bukkit.Bukkit;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class ServiceOwnerCleanupTest {

    interface Economy {
    }

    interface Claims {
    }

    private final LogCapture log = new LogCapture();
    private final SimpleServiceRegistry registry = new SimpleServiceRegistry();
    private final ServiceOwnerCleanup cleanup = new ServiceOwnerCleanup(registry, log.logger());
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void stubBukkit() {
        // Server events ask Bukkit whether they are created on the primary thread; there is no server in unit tests.
        bukkit = mockStatic(Bukkit.class);
    }

    @AfterEach
    void releaseBukkit() {
        bukkit.close();
    }

    @Test
    void disablingAPluginRemovesItsServicesOnly() {
        Plugin alpha = TestSupport.plugin("Alpha");
        Plugin beta = TestSupport.plugin("Beta");
        registry.register(alpha, Economy.class, new Economy() { });
        registry.register(beta, Claims.class, new Claims() { });

        cleanup.onPluginDisable(new PluginDisableEvent(alpha));

        assertFalse(registry.isRegistered(Economy.class));
        assertTrue(registry.isRegistered(Claims.class));
        assertEquals(1, log.messages(Level.INFO).size());
    }

    @Test
    void disablingAPluginWithoutServicesIsSilent() {
        cleanup.onPluginDisable(new PluginDisableEvent(TestSupport.plugin("Gamma")));

        assertTrue(log.records().isEmpty());
    }

    @Test
    void eventAfterRegistryShutdownDoesNotThrow() {
        registry.shutdown();

        cleanup.onPluginDisable(new PluginDisableEvent(TestSupport.plugin("Alpha")));

        assertTrue(log.records().isEmpty());
    }
}
