package net.kaloyan.smpcore.api;

import java.util.Objects;

/**
 * Immutable snapshot of SMP-Core's validated configuration.
 *
 * <p>A snapshot never changes. After {@code /smpcore reload} a new snapshot is published, so
 * callers should fetch {@link SMPAPI#getSettings()} each time they need a value instead of
 * caching the result.
 *
 * @param prefix        chat prefix as a MiniMessage string; never blank
 * @param debug         whether extra diagnostic logging is enabled
 * @param logPlayerJoin whether player joins are logged to the console
 * @param logPlayerQuit whether player quits are logged to the console
 */
public record CoreSettings(String prefix, boolean debug, boolean logPlayerJoin, boolean logPlayerQuit) {

    /** Default chat prefix, identical to the value in the bundled {@code config.yml}. */
    public static final String DEFAULT_PREFIX = "<dark_aqua>[SMP]</dark_aqua>";

    public CoreSettings {
        Objects.requireNonNull(prefix, "prefix");
    }

    /**
     * Returns the settings used for every key that is missing from {@code config.yml}.
     *
     * @return the default settings
     */
    public static CoreSettings defaults() {
        return new CoreSettings(DEFAULT_PREFIX, false, true, true);
    }
}
