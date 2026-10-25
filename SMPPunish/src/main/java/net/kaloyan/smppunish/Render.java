package net.kaloyan.smppunish;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Builds the screens and messages. Everything a player or staff member typed is inserted as plain text, so it can never add colours or click events. */
public final class Render {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Render() {
    }

    /**
     * @param p   an entry
     * @param now the time
     * @param s   the settings (for the server name and appeal)
     * @param extra more placeholders as name, value pairs
     * @return the placeholders for screens and messages
     */
    public static TagResolver values(Punishment p, long now, Settings s, Object... extra) {
        List<TagResolver> list = new ArrayList<>();
        list.add(Placeholder.unparsed("target", p.targetName()));
        list.add(Placeholder.unparsed("staff", p.staff()));
        list.add(Placeholder.unparsed("reason", p.reason().isBlank() ? "No reason given" : p.reason()));
        list.add(Placeholder.unparsed("id", Long.toString(p.id())));
        list.add(Placeholder.unparsed("duration", p.expires() == 0 ? "permanent" : Durations.format(p.expires() - p.created())));
        list.add(Placeholder.unparsed("expires", p.expires() == 0 ? "Never" : DATE.format(Instant.ofEpochMilli(p.expires()))));
        list.add(Placeholder.unparsed("remaining", p.expires() == 0 ? "permanent" : Durations.format(p.expires() - now)));
        list.add(Placeholder.unparsed("appeal", s.appeal()));
        list.add(Placeholder.unparsed("server", s.serverName()));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            list.add(Placeholder.unparsed(String.valueOf(extra[i]), String.valueOf(extra[i + 1])));
        }
        return TagResolver.resolver(list);
    }

    /**
     * @param lines     MiniMessage lines
     * @param resolvers the placeholders
     * @return the lines joined into one component
     */
    public static Component screen(List<String> lines, TagResolver resolvers) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            out = out.append(MINI.deserialize(lines.get(i), resolvers));
            if (i + 1 < lines.size()) {
                out = out.append(Component.newline());
            }
        }
        return out;
    }

    /**
     * @param text      one MiniMessage text
     * @param resolvers the placeholders
     * @return the component
     */
    public static Component text(String text, TagResolver resolvers) {
        return MINI.deserialize(text, resolvers);
    }

    /**
     * @param millis epoch milliseconds
     * @return a readable UTC date
     */
    public static String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }
}
