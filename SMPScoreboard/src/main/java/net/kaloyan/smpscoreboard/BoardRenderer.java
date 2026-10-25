package net.kaloyan.smpscoreboard;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Turns the line templates into components. Values are inserted as plain text, so a player named {@code <red>} or a
 * rank prefix cannot inject formatting tags into the sidebar.
 */
public final class BoardRenderer {

    /**
     * What one sidebar shows.
     *
     * @param name     player name
     * @param money    formatted money
     * @param shards   shards
     * @param kills    player kills
     * @param deaths   deaths
     * @param streak   current kill streak
     * @param rank     rank as a component (LuckPerms prefix or group name)
     * @param playtime formatted playtime
     * @param online   players online
     * @param ping     ping in milliseconds
     * @param crates   crates the player owns (SMP-Crates; 0 if it is not installed)
     * @param crateTimer time until the next free crate, e.g. {@code 12:34}, or {@code -} if there is none
     */
    public record Stats(String name, String money, long shards, int kills, int deaths, int streak, Component rank,
                        String playtime, int online, int ping, int crates, String crateTimer) {
    }

    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * Renders the title.
     *
     * @param settings the settings
     * @return the title
     */
    public Component title(BoardSettings settings) {
        return mini.deserialize(settings.title());
    }

    /**
     * Renders every line.
     *
     * @param settings the settings
     * @param stats    the values
     * @return one component per template line, top to bottom
     */
    public List<Component> lines(BoardSettings settings, Stats stats) {
        TagResolver values = TagResolver.resolver(
                Placeholder.unparsed("name", stats.name()),
                Placeholder.unparsed("money", stats.money()),
                Placeholder.unparsed("shards", Long.toString(stats.shards())),
                Placeholder.unparsed("kills", Integer.toString(stats.kills())),
                Placeholder.unparsed("deaths", Integer.toString(stats.deaths())),
                Placeholder.unparsed("streak", Integer.toString(stats.streak())),
                Placeholder.component("rank", stats.rank()),
                Placeholder.unparsed("playtime", stats.playtime()),
                Placeholder.unparsed("online", Integer.toString(stats.online())),
                Placeholder.unparsed("ping", Integer.toString(stats.ping())),
                Placeholder.unparsed("crates", Integer.toString(stats.crates())),
                Placeholder.unparsed("crate_timer", stats.crateTimer()),
                Placeholder.unparsed("keyall", stats.crateTimer()));
        List<Component> out = new ArrayList<>();
        for (String template : settings.lines()) {
            out.add(template.isEmpty() ? Component.empty() : mini.deserialize(template, values));
        }
        return out;
    }
}
