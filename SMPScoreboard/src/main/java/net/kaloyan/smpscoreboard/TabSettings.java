package net.kaloyan.smpscoreboard;

import java.util.List;

/**
 * Validated tab list settings.
 *
 * @param enabled    whether the plugin manages the tab list
 * @param header     MiniMessage lines above the player list
 * @param footer     MiniMessage lines below the player list
 * @param nameFormat MiniMessage template for each entry with {@code <prefix>} and {@code <name>}
 * @param sortByRank whether higher LuckPerms groups are listed first
 */
public record TabSettings(boolean enabled, List<String> header, List<String> footer, String nameFormat,
                          boolean sortByRank) {

    public TabSettings {
        header = List.copyOf(header);
        footer = List.copyOf(footer);
    }
}
