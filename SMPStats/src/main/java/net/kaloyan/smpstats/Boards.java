package net.kaloyan.smpstats;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kaloyan.smpeconomy.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** The leaderboards. Money and shards are read now and then (balances come from the economy asynchronously) and kept for a few minutes. */
public final class Boards {

    private static final int ALL = 100_000;

    private final StatsStore store;
    private final EconomyService economy;
    private volatile Map<UUID, Long> money = Map.of();
    private volatile Map<UUID, Long> shards = Map.of();
    private volatile long refreshed;

    /**
     * @param store   the counters
     * @param economy the economy
     */
    public Boards(StatsStore store, EconomyService economy) {
        this.store = store;
        this.economy = economy;
    }

    /** @return when the money and shard numbers were last read, epoch milliseconds (0 if never) */
    public long refreshed() {
        return refreshed;
    }

    /** Reads every known player's balance and shards. Call on the main thread. */
    public void refresh() {
        List<UUID> players = store.players();
        Map<UUID, Long> shardNow = new HashMap<>();
        Object manager = shardManager();
        if (manager != null) {
            try {
                Method get = manager.getClass().getMethod("get", UUID.class);
                for (UUID id : players) {
                    shardNow.put(id, (long) get.invoke(manager, id));
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                shardNow.clear();
            }
        }
        shards = shardNow;
        Map<UUID, Long> moneyNow = new ConcurrentHashMap<>();
        CompletableFuture<?>[] futures = players.stream().map(id -> economy.getBalance(id).thenAccept(balance -> {
            BigDecimal b = balance.orElse(BigDecimal.ZERO);
            moneyNow.put(id, b.setScale(0, java.math.RoundingMode.DOWN).longValue());
        }).exceptionally(error -> null)).toArray(CompletableFuture<?>[]::new);
        CompletableFuture.allOf(futures).thenRun(() -> {
            money = Map.copyOf(moneyNow);
            refreshed = System.currentTimeMillis();
        });
    }

    private static Object shardManager() {
        Plugin shop = Bukkit.getPluginManager().getPlugin("SMP-Shop");
        if (shop == null || !shop.isEnabled()) {
            return null;
        }
        try {
            return shop.getClass().getMethod("getShardManager").invoke(shop);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * @param stat  the statistic
     * @param limit how many players at most
     * @return the best players for it
     */
    public List<StatsStore.Row> top(Stat stat, int limit) {
        return switch (stat) {
            case MONEY -> store.rank(money, limit);
            case SHARDS -> store.rank(shards, limit);
            case KILLS -> store.top(StatsStore.Entry::kills, limit);
            case DEATHS -> store.top(StatsStore.Entry::deaths, limit);
            case PLAYTIME -> store.top(StatsStore.Entry::minutes, limit);
            case BLOCKS -> store.top(StatsStore.Entry::blocks, limit);
            case MOBS -> store.top(StatsStore.Entry::mobs, limit);
        };
    }

    /**
     * @param stat   the statistic
     * @param player the player
     * @return the player's place (1 = best) or 0 if they are not ranked
     */
    public int placeOf(Stat stat, UUID player) {
        List<StatsStore.Row> all = top(stat, ALL);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).player().equals(player)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * @param stat the statistic
     * @param v    a number of that statistic
     * @return the number as shown to players
     */
    public static String show(Stat stat, long v) {
        return switch (stat) {
            case MONEY -> "$" + Fmt.compact(v);
            case PLAYTIME -> Fmt.playtime(v);
            default -> Fmt.number(v);
        };
    }

    /**
     * @param stat   the statistic
     * @param player the player
     * @return the player's number (0 if unknown)
     */
    public long valueOf(Stat stat, UUID player) {
        StatsStore.Entry e = store.get(player);
        return switch (stat) {
            case MONEY -> money.getOrDefault(player, 0L);
            case SHARDS -> shards.getOrDefault(player, 0L);
            case KILLS -> e == null ? 0 : e.kills();
            case DEATHS -> e == null ? 0 : e.deaths();
            case PLAYTIME -> e == null ? 0 : e.minutes();
            case BLOCKS -> e == null ? 0 : e.blocks();
            case MOBS -> e == null ? 0 : e.mobs();
        };
    }
}
