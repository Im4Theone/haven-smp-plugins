package net.kaloyan.smpcore.log;

import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kaloyan.smpcore.api.CoreSettings;

/**
 * Debug output gated by the live {@code logging.debug} setting, so toggling it and reloading takes
 * effect immediately. Messages are written at INFO with a {@code [DEBUG]} marker because most servers
 * filter out FINE and below.
 */
public final class DebugLogger {

    private final Logger logger;
    private final Supplier<CoreSettings> settings;

    public DebugLogger(Logger logger, Supplier<CoreSettings> settings) {
        this.logger = logger;
        this.settings = settings;
    }

    /**
     * Logs {@code message} if debug logging is enabled.
     *
     * @param message the line to log
     */
    public void debug(Supplier<String> message) {
        if (settings.get().debug()) {
            logger.info("[DEBUG] " + message.get());
        }
    }
}
