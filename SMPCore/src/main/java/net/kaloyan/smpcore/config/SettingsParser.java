package net.kaloyan.smpcore.config;

import java.util.ArrayList;
import java.util.List;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Turns a raw configuration tree into a validated {@link CoreSettings}. Missing (or null) keys take
 * their value from {@link CoreSettings#defaults()}; present keys must have the right type and range.
 */
final class SettingsParser {

    static final String KEY_PREFIX = "prefix";
    static final String KEY_DEBUG = "logging.debug";
    static final String KEY_LOG_JOIN = "logging.player-join";
    static final String KEY_LOG_QUIT = "logging.player-quit";

    static final int MAX_PREFIX_LENGTH = 100;

    // Strict mode rejects unknown or unclosed tags instead of silently printing them as text.
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();

    private SettingsParser() {
    }

    /**
     * Validates {@code config} and builds settings from it.
     *
     * @param config the loaded configuration; must not contain defaults of its own
     * @return the validated settings
     * @throws InvalidConfigException if any value is invalid; lists all problems
     */
    static CoreSettings parse(ConfigurationSection config) throws InvalidConfigException {
        CoreSettings defaults = CoreSettings.defaults();
        List<String> problems = new ArrayList<>();

        String prefix = readPrefix(config, defaults.prefix(), problems);
        boolean debug = readBoolean(config, KEY_DEBUG, defaults.debug(), problems);
        boolean logJoin = readBoolean(config, KEY_LOG_JOIN, defaults.logPlayerJoin(), problems);
        boolean logQuit = readBoolean(config, KEY_LOG_QUIT, defaults.logPlayerQuit(), problems);

        if (!problems.isEmpty()) {
            throw new InvalidConfigException(problems);
        }
        return new CoreSettings(prefix, debug, logJoin, logQuit);
    }

    private static String readPrefix(ConfigurationSection config, String fallback, List<String> problems) {
        Object raw = config.get(KEY_PREFIX);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof String prefix)) {
            problems.add("'" + KEY_PREFIX + "' must be text, but was: " + raw);
            return fallback;
        }
        if (prefix.isBlank()) {
            problems.add("'" + KEY_PREFIX + "' must not be blank");
            return fallback;
        }
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            problems.add("'" + KEY_PREFIX + "' is " + prefix.length() + " characters long; the maximum is "
                    + MAX_PREFIX_LENGTH);
            return fallback;
        }
        try {
            STRICT_MINI_MESSAGE.deserialize(prefix);
        } catch (ParsingException e) {
            problems.add("'" + KEY_PREFIX + "' is not valid MiniMessage: " + e.getMessage());
            return fallback;
        }
        return prefix;
    }

    private static boolean readBoolean(ConfigurationSection config, String key, boolean fallback,
                                       List<String> problems) {
        Object raw = config.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        problems.add("'" + key + "' must be true or false, but was: " + raw);
        return fallback;
    }
}
