package net.kaloyan.smpcore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import net.kaloyan.smpcore.testutil.LogCapture;
import org.junit.jupiter.api.Test;

class LifecycleTest {

    private final LogCapture log = new LogCapture();
    private final Lifecycle lifecycle = new Lifecycle(log.logger());
    private final List<String> order = new ArrayList<>();

    @Test
    void cleanupsRunInReverseOrderOfRegistration() {
        lifecycle.onClose("first", () -> order.add("first"));
        lifecycle.onClose("second", () -> order.add("second"));
        lifecycle.onClose("third", () -> order.add("third"));

        int failures = lifecycle.closeAll();

        assertEquals(List.of("third", "second", "first"), order);
        assertEquals(0, failures);
    }

    @Test
    void aFailingCleanupIsLoggedAndTheRestStillRun() {
        lifecycle.onClose("first", () -> order.add("first"));
        lifecycle.onClose("broken", () -> {
            throw new IllegalStateException("boom");
        });
        lifecycle.onClose("third", () -> order.add("third"));

        int failures = lifecycle.closeAll();

        assertEquals(List.of("third", "first"), order);
        assertEquals(1, failures);
        assertEquals(1, log.messages(Level.SEVERE).size());
        assertTrue(log.messages(Level.SEVERE).get(0).contains("broken"));
    }

    @Test
    void closeAllIsIdempotent() {
        lifecycle.onClose("only", () -> order.add("only"));

        lifecycle.closeAll();
        lifecycle.closeAll();

        assertEquals(List.of("only"), order);
    }

    @Test
    void partialStartupIsRolledBackCompletely() {
        // Mirrors SMPCore#onEnable: each successful step registers a cleanup; a later step fails.
        List<String> live = new ArrayList<>();
        try {
            live.add("registry");
            lifecycle.onClose("registry", () -> live.remove("registry"));
            live.add("api");
            lifecycle.onClose("api", () -> live.remove("api"));
            throw new IllegalStateException("listener registration failed");
        } catch (IllegalStateException e) {
            lifecycle.closeAll();
        }

        assertTrue(live.isEmpty(), "nothing may stay initialized after a failed startup: " + live);
    }
}
