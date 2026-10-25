package net.kaloyan.smpscoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Owns one sidebar per player. Everything here runs on the main thread. Money is read from SMP-Economy
 * asynchronously into a cache, so the sidebar never waits for the database; it may show a balance that is one refresh
 * old.
 */
public final class BoardManager {

    private static final class Board {
        final Scoreboard scoreboard;
        final Objective objective;
        Component lastTitle;
        List<Component> lastLines = List.of();

        Board(Scoreboard scoreboard, Objective objective) {
            this.scoreboard = scoreboard;
            this.objective = objective;
        }
    }

    private final Plugin plugin;
    private final EconomyService economy;
    private final LuckPermsHook luckPerms;
    private final BoardRenderer renderer = new BoardRenderer();
    private final TabList tabList;
    private final Map<UUID, Board> boards = new ConcurrentHashMap<>();
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final NamespacedKey streakKey;
    private final NamespacedKey hiddenKey;
    private volatile BoardSettings settings;

    private Method cratesSeconds;
    private Method cratesTotal;
    private Method shardManagerGetter;
    private Method shardGet;

    /**
     * @param plugin    the plugin
     * @param economy   SMP-Economy's service
     * @param luckPerms LuckPerms, or {@code null} when not installed
     * @param settings  the validated settings
     */
    public BoardManager(Plugin plugin, EconomyService economy, LuckPermsHook luckPerms, BoardSettings settings) {
        this.plugin = plugin;
        this.economy = economy;
        this.luckPerms = luckPerms;
        this.settings = settings;
        this.tabList = new TabList(luckPerms);
        this.streakKey = new NamespacedKey(plugin, "streak");
        this.hiddenKey = new NamespacedKey(plugin, "hidden");
    }

    /**
     * Redraws one player's tab list now.
     *
     * @param player the player
     */
    public void updateTab(Player player) {
        TabSettings tab = settings.tab();
        if (tab.enabled()) {
            tabList.update(player, tab);
        }
    }

    public void settings(BoardSettings settings) {
        this.settings = settings;
        boards.values().forEach(b -> {
            b.lastTitle = null;
            b.lastLines = List.of();
        });
    }

    // ---------------------------------------------------------------- per-player data

    public int streak(Player player) {
        return player.getPersistentDataContainer().getOrDefault(streakKey, PersistentDataType.INTEGER, 0);
    }

    public void setStreak(Player player, int value) {
        player.getPersistentDataContainer().set(streakKey, PersistentDataType.INTEGER, Math.max(0, value));
    }

    public boolean hidden(Player player) {
        return player.getPersistentDataContainer().getOrDefault(hiddenKey, PersistentDataType.BYTE, (byte) 0) != 0;
    }

    /**
     * Shows or hides the player's sidebar.
     *
     * @param player the player
     * @return {@code true} if the sidebar is now visible
     */
    public boolean toggle(Player player) {
        boolean nowHidden = !hidden(player);
        player.getPersistentDataContainer().set(hiddenKey, PersistentDataType.BYTE, (byte) (nowHidden ? 1 : 0));
        if (nowHidden) {
            remove(player);
        } else {
            update(player);
        }
        return !nowHidden;
    }

    private long shards(UUID id) {
        Plugin shop = Bukkit.getPluginManager().getPlugin("SMP-Shop");
        if (shop == null) {
            shop = Bukkit.getPluginManager().getPlugin("DonutShop");
        }
        if (shop == null || !shop.isEnabled()) {
            return 0;
        }
        try {
            if (shardManagerGetter == null) {
                shardManagerGetter = shop.getClass().getMethod("getShardManager");
                shardGet = shardManagerGetter.getReturnType().getMethod("get", UUID.class);
            }
            Object manager = shardManagerGetter.invoke(shop);
            return manager == null ? 0 : (long) shardGet.invoke(manager, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private Plugin cratesPlugin() {
        Plugin crates = Bukkit.getPluginManager().getPlugin("SMP-Crates");
        return crates != null && crates.isEnabled() ? crates : null;
    }

    private int crates(UUID id) {
        Plugin crates = cratesPlugin();
        if (crates == null) {
            return 0;
        }
        try {
            if (cratesTotal == null) {
                cratesTotal = crates.getClass().getMethod("totalCrates", UUID.class);
            }
            return (int) cratesTotal.invoke(crates, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** {@code mm:ss} (or {@code h:mm:ss}) until the next free crate; {@code -} if SMP-Crates is missing or the timer is off. */
    private String crateTimer() {
        Plugin crates = cratesPlugin();
        if (crates == null) {
            return "-";
        }
        try {
            if (cratesSeconds == null) {
                cratesSeconds = crates.getClass().getMethod("secondsUntilNext");
            }
            long seconds = (long) cratesSeconds.invoke(crates);
            return Formats.countdown(seconds);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "-";
        }
    }

    private Component rank(Player player) {
        if (luckPerms == null) {
            return Component.text("Member", NamedTextColor.GRAY);
        }
        String prefix = luckPerms.prefix(player);
        if (prefix != null && !prefix.isBlank()) {
            return Prefixes.parse(prefix.trim());
        }
        String group = luckPerms.group(player);
        return Component.text(group.isEmpty() ? "Member" : Character.toUpperCase(group.charAt(0)) + group.substring(1),
                NamedTextColor.GRAY);
    }

    private BoardRenderer.Stats stats(Player player) {
        BigDecimal balance = balances.getOrDefault(player.getUniqueId(), BigDecimal.ZERO);
        return new BoardRenderer.Stats(player.getName(), Formats.money(economy.getCurrencySymbol(), balance),
                shards(player.getUniqueId()), player.getStatistic(Statistic.PLAYER_KILLS),
                player.getStatistic(Statistic.DEATHS), streak(player), rank(player),
                Formats.playtime(player.getStatistic(Statistic.PLAY_ONE_MINUTE)),
                Visible.online(), player.getPing(), crates(player.getUniqueId()), crateTimer());
    }

    // ---------------------------------------------------------------- rendering

    /** Refreshes the cached balances (asynchronously) and redraws every sidebar. Call on the main thread. */
    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            economy.getBalance(id).whenComplete((value, error) -> {
                if (error == null && value != null) {
                    balances.put(id, value.orElse(BigDecimal.ZERO));
                }
            });
            try {
                updateTab(player);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not update the tab list of " + player.getName(), e);
            }
            if (!hidden(player)) {
                try {
                    update(player);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not update the scoreboard of " + player.getName(), e);
                }
            }
        }
    }

    /**
     * Draws a player's sidebar, creating it if needed. Only lines that changed are sent again.
     *
     * @param player the player
     */
    public void update(Player player) {
        BoardSettings current = settings;
        Board board = boards.computeIfAbsent(player.getUniqueId(), id -> create(player));
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
        Component title = renderer.title(current);
        if (!title.equals(board.lastTitle)) {
            board.objective.displayName(title);
            board.lastTitle = title;
        }
        List<Component> lines = renderer.lines(current, stats(player));
        for (int i = 0; i < lines.size(); i++) {
            String entry = entry(i);
            Team team = board.scoreboard.getTeam("l" + i);
            if (team == null) {
                team = board.scoreboard.registerNewTeam("l" + i);
                team.addEntry(entry);
            }
            if (i >= board.lastLines.size() || !lines.get(i).equals(board.lastLines.get(i))) {
                team.prefix(lines.get(i));
            }
            board.objective.getScore(entry).setScore(lines.size() - i);
        }
        for (int i = lines.size(); i < board.lastLines.size(); i++) {
            Team old = board.scoreboard.getTeam("l" + i);
            if (old != null) {
                board.scoreboard.resetScores(entry(i));
                old.unregister();
            }
        }
        board.lastLines = new ArrayList<>(lines);
    }

    private Board create(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("smp", Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank()); // hide the red score numbers
        return new Board(scoreboard, objective);
    }

    /** A unique invisible entry per line (colour codes only), so identical lines never merge. */
    private static String entry(int index) {
        return "§" + Integer.toHexString(index) + "§r";
    }

    /**
     * Removes a player's sidebar and gives them the server's main scoreboard back.
     *
     * @param player the player
     */
    public void remove(Player player) {
        Board board = boards.remove(player.getUniqueId());
        balances.remove(player.getUniqueId());
        if (board != null && player.isOnline()) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** Removes every sidebar (plugin shutdown). */
    public void removeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            tabList.reset(player);
            remove(player);
        }
        boards.clear();
    }
}
