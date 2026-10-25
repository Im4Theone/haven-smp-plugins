package net.kaloyan.smpeconomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import org.junit.jupiter.api.Test;

class LifecycleTest {

    private final LogCapture log = new LogCapture();
    private final Lifecycle lifecycle = new Lifecycle(log.logger());

    @Test
    void cleanupsRunInReverseOrderOfRegistration() {
        List<String> order = new ArrayList<>();
        lifecycle.onClose("database", () -> order.add("database"));
        lifecycle.onClose("storage thread", () -> order.add("storage thread"));
        lifecycle.onClose("service", () -> order.add("service"));

        assertEquals(0, lifecycle.closeAll());

        // The service is withdrawn first, then storage drains, and only then is the database closed.
        assertEquals(List.of("service", "storage thread", "database"), order);
    }

    @Test
    void aFailingCleanupIsLoggedAndTheRestStillRun() {
        List<String> order = new ArrayList<>();
        lifecycle.onClose("database", () -> order.add("database"));
        lifecycle.onClose("broken", () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(1, lifecycle.closeAll());

        assertEquals(List.of("database"), order);
        assertTrue(log.messages(Level.SEVERE).stream().anyMatch(m -> m.contains("broken")));
    }

    @Test
    void closeAllIsIdempotent() {
        List<String> order = new ArrayList<>();
        lifecycle.onClose("once", () -> order.add("once"));

        lifecycle.closeAll();
        lifecycle.closeAll();

        assertEquals(List.of("once"), order);
    }
}
