package net.kaloyan.smpscoreboard;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Validated scoreboard and chat settings.
 *
 * @param title        MiniMessage title
 * @param updateTicks  how often the sidebar refreshes
 * @param lines        MiniMessage line templates, top to bottom (empty string = blank line)
 * @param chatEnabled  whether chat is formatted with the LuckPerms prefix
 * @param chatFormat   MiniMessage chat template with {@code <prefix>}, {@code <name>} and {@code <message>}
 * @param joinMessage  MiniMessage join message with {@code <name>}; empty = the server's default message
 * @param quitMessage  MiniMessage leave message with {@code <name>}; empty = the server's default message
 * @param tab          tab list settings
 * @param welcome      the title shown on join
 */
public record BoardSettings(String title, int updateTicks, List<String> lines, boolean chatEnabled, String chatFormat,
                            String joinMessage, String quitMessage, TabSettings tab, WelcomeSettings welcome) {

    /** The most lines the sidebar can show. */
    public static final int MAX_LINES = 15;

    public BoardSettings {
        lines = List.copyOf(lines);
    }

    /** Thrown with every problem found in the configuration. */
    public static final class Invalid extends Exception {
        private static final long serialVersionUID = 1L;
        private final String[] problems;

        Invalid(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = problems.toArray(String[]::new);
        }

        public List<String> problems() {
            return List.of(problems);
        }
    }

    /**
     * Validates a configuration.
     *
     * @param config the {@code config.yml} contents
     * @return the settings
     * @throws Invalid listing every problem
     */
    public static BoardSettings parse(ConfigurationSection config) throws Invalid {
        List<String> problems = new ArrayList<>();
        MiniMessage mini = MiniMessage.miniMessage();

        String title = config.getString("scoreboard.title");
        if (title == null || title.isBlank()) {
            problems.add("'scoreboard.title' must not be blank");
            title = "";
        }
        int ticks = config.getInt("scoreboard.update-ticks", 20);
        if (ticks < 5 || ticks > 200) {
            problems.add("'scoreboard.update-ticks' must be between 5 and 200, but was " + ticks);
        }
        List<String> lines = config.getStringList("scoreboard.lines");
        if (lines.isEmpty()) {
            problems.add("'scoreboard.lines' must contain at least one line");
        } else if (lines.size() > MAX_LINES) {
            problems.add("'scoreboard.lines' has " + lines.size() + " lines; the sidebar shows at most " + MAX_LINES);
        }
        String chatFormat = config.getString("chat.format", "<prefix><name><dark_gray> » <white><message>");
        boolean chatEnabled = config.getBoolean("chat.enabled", true);
        String join = config.getString("messages.join", "");
        String quit = config.getString("messages.quit", "");
        TabSettings tab = new TabSettings(config.getBoolean("tab.enabled", true), config.getStringList("tab.header"),
                config.getStringList("tab.footer"), config.getString("tab.name-format", "<prefix><name>"),
                config.getBoolean("tab.sort-by-rank", true));
        WelcomeSettings welcome = new WelcomeSettings(config.getBoolean("welcome.enabled", true),
                config.getString("welcome.title", ""), config.getString("welcome.subtitle", ""),
                config.getInt("welcome.fade-in-ms", 300), config.getInt("welcome.stay-ms", 1750),
                config.getInt("welcome.fade-out-ms", 500));
        if (welcome.fadeInMs() < 0 || welcome.stayMs() < 0 || welcome.fadeOutMs() < 0) {
            problems.add("'welcome' times must not be negative");
        }
        try {
            mini.deserialize(welcome.title());
            mini.deserialize(welcome.subtitle());
            tab.header().forEach(mini::deserialize);
            tab.footer().forEach(mini::deserialize);
            mini.deserialize(tab.nameFormat());
            mini.deserialize(join);
            mini.deserialize(quit);
            mini.deserialize(title);
            for (String line : lines) {
                mini.deserialize(line);
            }
            mini.deserialize(chatFormat);
        } catch (RuntimeException e) {
            problems.add("a MiniMessage template is invalid: " + e.getMessage());
        }
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return new BoardSettings(title, ticks, lines, chatEnabled, chatFormat, join, quit, tab, welcome);
    }
}
