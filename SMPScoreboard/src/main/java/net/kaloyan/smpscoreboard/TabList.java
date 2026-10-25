package net.kaloyan.smpscoreboard;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Header, footer, prefixed names and rank ordering of the player list. Runs on the main thread. Values are inserted as
 * plain text or finished components, so a player name cannot inject formatting tags.
 */
public final class TabList {

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final LuckPermsHook luckPerms;

    /**
     * @param luckPerms LuckPerms, or {@code null} when not installed
     */
    public TabList(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    /**
     * Refreshes one player's view of the list.
     *
     * @param player the player
     * @param tab    the tab settings
     */
    public void update(Player player, TabSettings tab) {
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        TagResolver values = TagResolver.resolver(
                Placeholder.unparsed("name", player.getName()),
                Placeholder.unparsed("online", Integer.toString(Visible.online())),
                Placeholder.unparsed("max", Integer.toString(Bukkit.getMaxPlayers())),
                Placeholder.component("ping", Component.text(player.getPing(), pingColor(player.getPing()))),
                Placeholder.component("tps", Component.text(String.format("%.1f", tps), tpsColor(tps))));
        player.sendPlayerListHeaderAndFooter(join(tab.header(), values), join(tab.footer(), values));

        String prefix = luckPerms == null ? null : luckPerms.prefix(player);
        Component prefixComponent = prefix == null || prefix.isBlank() ? Component.empty()
                : Prefixes.parse(prefix.trim()).append(Component.space());
        if (Visible.vanished(player.getUniqueId())) {
            prefixComponent = Component.text("[V] ", net.kyori.adventure.text.format.NamedTextColor.GRAY).append(prefixComponent);
        }
        player.playerListName(mini.deserialize(tab.nameFormat(), Placeholder.component("prefix", prefixComponent),
                Placeholder.unparsed("name", player.getName())));
        player.setPlayerListOrder(tab.sortByRank() && luckPerms != null ? luckPerms.weight(player) : 0);
    }

    /**
     * Puts the player list back to the server's default.
     *
     * @param player the player
     */
    public void reset(Player player) {
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        player.playerListName(null);
        player.setPlayerListOrder(0);
    }

    private Component join(List<String> lines, TagResolver values) {
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.appendNewline();
            }
            String line = lines.get(i);
            out = out.append(line.isEmpty() ? Component.empty() : mini.deserialize(line, values));
        }
        return out;
    }

    static TextColor pingColor(int ping) {
        return TextColor.fromHexString(ping < 60 ? "#4ADE80" : ping < 120 ? "#FBBF24" : ping < 200 ? "#FB923C" : "#F87171");
    }

    static TextColor tpsColor(double tps) {
        return TextColor.fromHexString(tps >= 19 ? "#4ADE80" : tps >= 15 ? "#FBBF24" : "#F87171");
    }
}
