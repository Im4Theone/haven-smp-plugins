package net.kaloyan.smpcore;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records a cleanup action for every startup step that succeeds, and undoes them in reverse order.
 * This guarantees that a startup that fails halfway, and a normal shutdown, release exactly what was
 * acquired. A failing cleanup is logged and does not stop the remaining ones from running.
 */
final class Lifecycle {

    private record Cleanup(String name, Runnable action) {
    }

    private final Deque<Cleanup> cleanups = new ArrayDeque<>();
    private final Logger logger;

    Lifecycle(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers a cleanup to run when {@link #closeAll()} is called.
     *
     * @param name   description used in log messages
     * @param action the cleanup
     */
    void onClose(String name, Runnable action) {
        cleanups.push(new Cleanup(name, action));
    }

    /**
     * Runs all registered cleanups, newest first, then forgets them. Safe to call more than once.
     *
     * @return how many cleanups threw an exception
     */
    int closeAll() {
        int failures = 0;
        while (!cleanups.isEmpty()) {
            Cleanup cleanup = cleanups.pop();
            try {
                cleanup.action().run();
            } catch (RuntimeException e) {
                failures++;
                logger.log(Level.SEVERE, "Cleanup step '" + cleanup.name() + "' failed", e);
            }
        }
        return failures;
    }
}
