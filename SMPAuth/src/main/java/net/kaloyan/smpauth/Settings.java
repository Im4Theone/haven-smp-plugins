package net.kaloyan.smpauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param premiumAuto    whether players verified as bought accounts skip the password
 * @param protectNames   whether a name of a bought account cannot be used by players without one
 * @param minLength      shortest password
 * @param maxLength      longest password
 * @param maxPerIp       accounts per address (0 = no limit)
 * @param timeoutSeconds seconds to log in
 * @param maxAttempts    wrong passwords before a kick and a lock
 * @param lockMillis     how long a name or address stays locked
 * @param blindness      whether players who are not logged in are blinded
 * @param sessionMillis  how long a login is remembered for the same address (0 = off)
 * @param verify         whether players with a bought account prove it to Mojang
 * @param detect         whether unknown names are looked up so bought accounts need no /premium
 * @param lookupUrl      address (name appended) that tells whether a bought account has a name
 * @param sessionUrl     Mojang's "has joined" address
 * @param verifySeconds  how long Mojang gets to answer and a client to answer the encryption request
 * @param messages       MiniMessage texts by key
 */
public record Settings(boolean premiumAuto, boolean protectNames, int minLength, int maxLength, int maxPerIp, int timeoutSeconds, int maxAttempts,
                       long lockMillis, boolean blindness, long sessionMillis, boolean verify, boolean detect, String lookupUrl, String sessionUrl,
                       int verifySeconds, Map<String, String> messages) {

    /** Every problem found in the configuration. */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        private final String[] problems;

        Invalid(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = problems.toArray(String[]::new);
        }

        /** @return the problems */
        public List<String> problems() {
            return List.of(problems);
        }
    }

    /** @return the text for a message key, or the key itself */
    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * @param config the configuration
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static Settings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();
        int min = config.getInt("password.min-length", 8);
        int max = config.getInt("password.max-length", 64);
        if (min < 4 || min > 64 || max < min || max > 128) {
            problems.add("'password.min-length' must be 4..64 and 'password.max-length' between that and 128");
        }
        int perIp = config.getInt("registration.max-accounts-per-ip", 3);
        if (perIp < 0 || perIp > 1000) {
            problems.add("'registration.max-accounts-per-ip' must be 0..1000");
        }
        int timeout = config.getInt("login.timeout-seconds", 60);
        if (timeout < 10 || timeout > 600) {
            problems.add("'login.timeout-seconds' must be 10..600");
        }
        int attempts = config.getInt("login.max-attempts", 5);
        if (attempts < 1 || attempts > 50) {
            problems.add("'login.max-attempts' must be 1..50");
        }
        int lock = config.getInt("login.lock-minutes", 10);
        if (lock < 1 || lock > 1440) {
            problems.add("'login.lock-minutes' must be 1..1440");
        }
        int session = config.getInt("session.minutes", 10);
        if (session < 0 || session > 1440) {
            problems.add("'session.minutes' must be 0..1440");
        }
        String lookup = config.getString("premium.lookup-url", "");
        String sessionUrl = config.getString("premium.session-url", "");
        if (!lookup.matches("https?://\\S+") || !sessionUrl.matches("https?://\\S+")) {
            problems.add("'premium.lookup-url' and 'premium.session-url' must be http(s) addresses");
        }
        int verifySeconds = config.getInt("premium.timeout-seconds", 10);
        if (verifySeconds < 3 || verifySeconds > 60) {
            problems.add("'premium.timeout-seconds' must be 3..60");
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection m = config.getConfigurationSection("messages");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                String text = m.getString(key, "");
                try {
                    mini.deserialize(text);
                } catch (RuntimeException e) {
                    problems.add("message '" + key + "' is invalid: " + e.getMessage());
                }
                messages.put(key, text);
            }
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(config.getBoolean("premium.auto-login", true), config.getBoolean("premium.protect-names", true), min, max, perIp, timeout,
                attempts, lock * 60_000L, config.getBoolean("login.blindness", true), session * 60_000L, config.getBoolean("premium.verify", true),
                config.getBoolean("premium.auto-detect", true), lookup, sessionUrl, verifySeconds, messages);
    }
}
