package net.kaloyan.smpeconomy.log;

import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kaloyan.smpeconomy.config.EconomySettings;

/**
 * Debug output gated by the live {@code logging.debug} setting, so toggling it and reloading takes
 * effect immediately. Messages are written at INFO with a {@code [DEBUG]} marker because most servers
 * filter out FINE and below. Thread-safe.
 */
public final class DebugLogger {

    private final Logger logger;
    private final Supplier<EconomySettings> settings;

    public DebugLogger(Logger logger, Supplier<EconomySettings> settings) {
        this.logger = logger;
        this.settings = settings;
    }

    /**
     * Logs {@code message} if debug logging is enabled.
     *
     * @param message the line to log; only built when debug is on
     */
    public void debug(Supplier<String> message) {
        if (settings.get().debug()) {
            logger.info("[DEBUG] " + message.get());
        }
    }
}
