package net.kaloyan.smppunish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated configuration.
 *
 * @param serverName     shown on screens
 * @param appeal         shown on the ban screen
 * @param publicBroadcast whether punishments are announced to everybody
 * @param maxDays        longest ban or mute (in days) for staff without {@code smppunish.long}
 * @param banScreen      the lines of the ban screen
 * @param kickScreen     the lines of the kick screen
 * @param messages       MiniMessage texts by key
 * @param mutedCommands  commands a muted player cannot use
 * @param presets        reason shortcuts
 * @param warnExpiryDays days until a warning stops counting
 * @param escalation     what happens at which number of warnings
 * @param pageSize       entries per page of a history
 */
public record Settings(String serverName, String appeal, boolean publicBroadcast, int maxDays, List<String> banScreen, List<String> kickScreen,
                       Map<String, String> messages, Set<String> mutedCommands, Map<String, Preset> presets, int warnExpiryDays,
                       List<Escalation> escalation, int pageSize) {

    /**
     * A reason shortcut.
     *
     * @param reason         the reason text
     * @param durationMillis how long, or {@link Durations#PERMANENT}
     */
    public record Preset(String reason, long durationMillis) {
    }

    /**
     * An automatic step.
     *
     * @param warns          the number of warnings that triggers it
     * @param type           what to do (a ban, a mute or a kick)
     * @param durationMillis for a ban or mute, how long
     */
    public record Escalation(int warns, Punishment.Type type, long durationMillis) {
    }

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
        List<String> ban = screen(problems, mini, "screens.ban", config.getStringList("screens.ban"));
        List<String> kick = screen(problems, mini, "screens.kick", config.getStringList("screens.kick"));
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection m = config.getConfigurationSection("messages");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                String text = m.getString(key, "");
                markup(problems, mini, "message '" + key + "'", text);
                messages.put(key, text);
            }
        }
        Set<String> commands = new TreeSet<>();
        for (String c : config.getStringList("muted-commands")) {
            commands.add(c.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }
        Map<String, Preset> presets = new LinkedHashMap<>();
        ConfigurationSection ps = config.getConfigurationSection("presets");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                String reason = ps.getString(key + ".reason", "");
                long duration = Durations.parse(String.valueOf(ps.get(key + ".duration", "perm")));
                if (reason.isBlank() || duration == Durations.INVALID || Durations.parse(key) != Durations.INVALID) {
                    problems.add("'presets." + key + "' needs a reason and a valid duration, and its name must not look like a duration");
                } else {
                    presets.put(key.toLowerCase(Locale.ROOT), new Preset(reason, duration));
                }
            }
        }
        List<Escalation> escalation = new ArrayList<>();
        for (Map<?, ?> e : config.getMapList("warnings.escalation")) {
            Object warns = e.get("warns");
            Punishment.Type type = null;
            try {
                type = Punishment.Type.valueOf(String.valueOf(e.get("action")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // reported below
            }
            long duration = e.get("duration") == null ? Durations.PERMANENT : Durations.parse(String.valueOf(e.get("duration")));
            if (!(warns instanceof Number n) || n.intValue() < 1 || type == null || type == Punishment.Type.WARN || duration == Durations.INVALID) {
                problems.add("'warnings.escalation' entries need 'warns' (1 or more), 'action' (KICK, MUTE or BAN) and a valid 'duration'");
            } else {
                escalation.add(new Escalation(n.intValue(), type, duration));
            }
        }
        int maxDays = config.getInt("max-days-without-long-permission", 30);
        int expiry = config.getInt("warnings.expiry-days", 30);
        int page = config.getInt("history.page-size", 8);
        if (maxDays < 1 || maxDays > 36500 || expiry < 1 || expiry > 36500 || page < 3 || page > 28) {
            problems.add("'max-days-without-long-permission' and 'warnings.expiry-days' must be 1..36500, 'history.page-size' 3..28");
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new Settings(config.getString("server-name", "the server"), config.getString("appeal", ""), config.getBoolean("public-broadcast", true),
                maxDays, ban, kick, messages, commands, presets, expiry, escalation, page);
    }

    private static List<String> screen(List<String> problems, MiniMessage mini, String where, List<String> lines) {
        if (lines.isEmpty()) {
            problems.add("'" + where + "' needs at least one line");
        }
        lines.forEach(line -> markup(problems, mini, where, line));
        return lines;
    }

    private static void markup(List<String> problems, MiniMessage mini, String where, String text) {
        try {
            mini.deserialize(text);
        } catch (RuntimeException e) {
            problems.add(where + " is invalid: " + e.getMessage());
        }
    }
}
