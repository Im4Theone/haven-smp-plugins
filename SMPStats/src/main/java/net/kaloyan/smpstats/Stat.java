package net.kaloyan.smpstats;

import java.util.Locale;
import org.bukkit.Material;

/** A statistic with a leaderboard. */
public enum Stat {
    /** The balance of the server currency. */
    MONEY("Money", Material.GOLD_INGOT, "baltop"),
    /** Players killed. */
    KILLS("Kills", Material.DIAMOND_SWORD, "killtop"),
    /** Times died. */
    DEATHS("Deaths", Material.SKELETON_SKULL, "deathtop"),
    /** Time played. */
    PLAYTIME("Playtime", Material.CLOCK, "playtop"),
    /** Blocks broken. */
    BLOCKS("Blocks mined", Material.DIAMOND_PICKAXE, "blocktop"),
    /** Mobs killed. */
    MOBS("Mobs killed", Material.ZOMBIE_HEAD, "mobtop"),
    /** Shards collected. */
    SHARDS("Shards", Material.AMETHYST_SHARD, "shardtop");

    private final String title;
    private final Material icon;
    private final String command;

    Stat(String title, Material icon, String command) {
        this.title = title;
        this.icon = icon;
        this.command = command;
    }

    /** @return the name shown to players */
    public String title() {
        return title;
    }

    /** @return the icon in the menu */
    public Material icon() {
        return icon;
    }

    /** @return the shortcut command that opens this board */
    public String command() {
        return command;
    }

    /** @return the word typed after /leaderboard */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param text what a player typed
     * @return the matching statistic, or {@code null}
     */
    public static Stat of(String text) {
        for (Stat s : values()) {
            if (s.id().equals(text.toLowerCase(Locale.ROOT)) || s.command.equals(text.toLowerCase(Locale.ROOT))) {
                return s;
            }
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "balance", "bal", "coins" -> MONEY;
            case "kill" -> KILLS;
            case "death" -> DEATHS;
            case "time", "playtimes", "hours" -> PLAYTIME;
            case "mined", "block" -> BLOCKS;
            case "mob" -> MOBS;
            case "shard" -> SHARDS;
            default -> null;
        };
    }
}
