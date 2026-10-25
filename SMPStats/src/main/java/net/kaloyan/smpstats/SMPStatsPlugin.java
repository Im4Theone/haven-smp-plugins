package net.kaloyan.smpstats;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Counts kills, deaths, mobs, blocks and playtime, and shows leaderboards (/leaderboard, /baltop ...) and /stats. */
public final class SMPStatsPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final TextColor GREEN = TextColor.color(0x00F986);
    private static final TextColor GOLD = TextColor.color(0xFBBF24);

    private StatsStore store;
    private Boards boards;
    private Menus menus;
    private EconomyService economy;

    @Override
    public void onEnable() {
        SMPAPI core = getServer().getServicesManager().load(SMPAPI.class);
        Optional<EconomyService> found = core == null ? Optional.empty() : core.getServices().find(EconomyService.class);
        if (found.isEmpty()) {
            getLogger().severe("SMP-Economy did not publish its EconomyService; disabling SMP-Stats.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = found.get();
        store = new StatsStore(getDataFolder().toPath().resolve("stats.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe every counter, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore stats.yml and restart. SMP-Stats is disabled so no counters are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && store.get(p.getUniqueId()) == null) {
                store.seen(p.getUniqueId(), p.getName());       // everybody who ever joined can appear on the money board
            }
        }
        boards = new Boards(store, economy);
        menus = new Menus(boards);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menus, this);
        for (String name : List.of("leaderboard", "stats", "baltop", "killtop", "deathtop", "playtop", "blocktop", "mobtop", "shardtop")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, boards::refresh, 20L * 5, 20L * 60 * 5);
        getServer().getScheduler().runTaskTimer(this, this::tickPlaytime, 20L * 60, 20L * 60);
        getServer().getScheduler().runTaskTimerAsynchronously(this, store::saveIfDirty, 20L * 60, 20L * 60);
        getLogger().info("SMP-Stats ready: " + store.players().size() + " player(s) known.");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.saveIfDirty();
        }
    }

    // ---------------------------------------------------------------- counting

    private void tickPlaytime() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Afk.is(p.getUniqueId())) {
                store.minutes(p.getUniqueId(), p.getName(), 1);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        store.seen(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        store.death(victim.getUniqueId(), victim.getName());
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            store.kill(killer.getUniqueId(), killer.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null && !(event.getEntity() instanceof Player)) {
            store.mob(killer.getUniqueId(), killer.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (p.getGameMode() == GameMode.SURVIVAL) {
            store.block(p.getUniqueId(), p.getName());
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName();
        if (name.equals("stats")) {
            stats(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can open the leaderboards.");
            return true;
        }
        if (name.equals("leaderboard")) {
            Stat stat = args.length > 0 ? Stat.of(args[0]) : null;
            if (stat == null) {
                menus.openMain(player);
            } else {
                menus.openBoard(player, stat);
            }
            return true;
        }
        Stat stat = Stat.of(name);
        if (stat != null) {
            menus.openBoard(player, stat);
        }
        return true;
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color);
    }

    private void stats(CommandSender sender, String[] args) {
        UUID id;
        String name;
        if (args.length == 0 && sender instanceof Player p) {
            id = p.getUniqueId();
            name = p.getName();
        } else if (args.length > 0) {
            Player online = Bukkit.getPlayerExact(args[0]);
            OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[0]);
            if (target == null || store.get(target.getUniqueId()) == null) {
                sender.sendMessage(line("No stats for " + args[0] + ".", NamedTextColor.RED));
                return;
            }
            id = target.getUniqueId();
            name = store.name(id);
        } else {
            sender.sendMessage(line("Usage: /stats <player>", NamedTextColor.RED));
            return;
        }
        StatsStore.Entry e = store.get(id);
        if (e == null) {
            sender.sendMessage(line("No stats for " + name + " yet.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(line(name + "'s stats", GREEN));
        sender.sendMessage(line("Kills " + Fmt.number(e.kills()) + "   Deaths " + Fmt.number(e.deaths()) + "   K/D " + e.kdr(), NamedTextColor.WHITE));
        sender.sendMessage(line("Mobs killed " + Fmt.number(e.mobs()) + "   Blocks mined " + Fmt.number(e.blocks()), NamedTextColor.WHITE));
        sender.sendMessage(line("Playtime " + Fmt.playtime(e.minutes()), NamedTextColor.WHITE));
        economy.getBalance(id).thenAccept(balance -> {
            BigDecimal b = balance.orElse(BigDecimal.ZERO);
            Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(line("Money " + economy.format(b) + "   Shards " + Fmt.number(boards.valueOf(Stat.SHARDS, id))
                    + "   Money place #" + boards.placeOf(Stat.MONEY, id), GOLD)));
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("leaderboard") && args.length == 1) {
            return java.util.Arrays.stream(Stat.values()).map(Stat::id).toList();
        }
        return command.getName().equals("stats") && args.length == 1 ? null : List.of();
    }
}
