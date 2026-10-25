package net.kaloyan.smprtp;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

/**
 * Random teleporting. Everything here runs on the main thread; chunks load asynchronously and the work continues on the
 * main thread when they are ready. A player can have one random teleport in progress at a time.
 */
public final class RtpService {

    private final Plugin plugin;
    private final SpotFinder finder = new SpotFinder(new Materials());
    private final Random random = new Random();
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Set<UUID> busy = new HashSet<>();
    private final Set<UUID> hurt = new HashSet<>();
    private final Map<UUID, Long> cooldownEnds = new HashMap<>();
    private final Set<UUID> forced = new HashSet<>();
    private final CooldownStore cooldowns;
    private final ProtectLink protect = new ProtectLink();
    private volatile RtpSettings settings;

    /**
     * @param plugin   the owning plugin
     * @param settings the validated settings
     */
    public RtpService(Plugin plugin, RtpSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.cooldowns = new CooldownStore(plugin.getDataFolder().toPath().resolve("cooldowns.yml"));
        try {
            cooldownEnds.putAll(cooldowns.load(System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("cooldowns.yml could not be read, starting with no cooldowns: " + e.getMessage());
        }
    }

    /** @param settings the new validated settings */
    public void settings(RtpSettings settings) {
        this.settings = settings;
    }

    /** @return the current settings */
    public RtpSettings settings() {
        return settings;
    }

    /**
     * Remembers that a player took damage, which cancels a waiting teleport.
     *
     * @param id the player
     */
    public void markHurt(UUID id) {
        if (busy.contains(id)) {
            hurt.add(id);
        }
    }

    /**
     * Forgets everything about a player (on quit).
     *
     * @param id the player
     */
    public void forget(UUID id) {
        busy.remove(id);
        forced.remove(id);
        hurt.remove(id);
        Long end = cooldownEnds.get(id);
        if (end != null && end <= System.currentTimeMillis()) {
            cooldownEnds.remove(id); // an expired cooldown needs no memory
        }
    }

    /** @return {@code true} if the player has a random teleport in progress */
    public boolean busy(UUID id) {
        return busy.contains(id);
    }

    /**
     * Tells a player something.
     *
     * @param player       the player
     * @param key          the message key from the config
     * @param placeholders extra values as name, value pairs
     */
    public void send(Player player, String key, String... placeholders) {
        player.sendMessage(render(key, placeholders));
    }

    private Component render(String key, String... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return mini.deserialize(settings.message(key), values.build());
    }

    // ---------------------------------------------------------------- player flow

    /**
     * Starts a random teleport for a player: checks, warm-up, search, payment, teleport.
     *
     * @param player the player
     * @param target the destination
     */
    public void start(Player player, RtpWorld target) {
        start(player, target, false);
    }

    /**
     * Starts a random teleport; a forced one (an admin sending a player) skips the cooldown, warm-up and cost and does
     * not start a cooldown.
     *
     * @param player the player
     * @param target the destination
     * @param force  {@code true} for an admin-initiated teleport
     */
    public void start(Player player, RtpWorld target, boolean force) {
        UUID id = player.getUniqueId();
        if (!target.enabled()) {
            send(player, "disabled", "world", target.key());
            return;
        }
        if (busy.contains(id)) {
            send(player, "busy");
            return;
        }
        World world = Bukkit.getWorld(target.world());
        if (world == null) {
            send(player, "no-world", "world", target.key());
            return;
        }
        boolean skipCooldown = force || player.hasPermission("smprtp.bypass.cooldown");
        long remaining = cooldownEnds.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (!skipCooldown && remaining > 0) {
            send(player, "cooldown", "seconds", Long.toString((remaining + 999) / 1000));
            return;
        }
        busy.add(id);
        hurt.remove(id);
        if (force) {
            forced.add(id);
        } else {
            forced.remove(id);
        }
        int warmup = force ? 0 : settings.warmupSeconds();
        if (warmup == 0) {
            search(player, target, world);
        } else {
            warmUp(player, target, world, warmup);
        }
    }

    private void warmUp(Player player, RtpWorld target, World world, int seconds) {
        UUID id = player.getUniqueId();
        Location start = player.getLocation();
        int[] ticksLeft = {seconds * 20};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !busy.contains(id)) {
                task[0].cancel();
                forget(id);
                return;
            }
            if (hurt.remove(id)) {
                task[0].cancel();
                busy.remove(id);
                send(player, "cancelled-damage");
                return;
            }
            Location now = player.getLocation();
            if (!now.getWorld().equals(start.getWorld()) || now.distanceSquared(start) > 0.25) {
                task[0].cancel();
                busy.remove(id);
                send(player, "cancelled-move");
                return;
            }
            if (ticksLeft[0] % 20 == 0 && ticksLeft[0] > 0) {
                player.sendActionBar(render("warmup", "seconds", Integer.toString(ticksLeft[0] / 20)));
            }
            ticksLeft[0] -= 5;
            if (ticksLeft[0] <= 0) {
                task[0].cancel();
                search(player, target, world);
            }
        }, 0L, 5L);
    }

    private void search(Player player, RtpWorld target, World world) {
        UUID id = player.getUniqueId();
        send(player, "searching");
        findSpot(target, world, () -> player.isOnline() && busy.contains(id), found -> {
            if (!player.isOnline() || !busy.contains(id)) {
                forget(id);
                return;
            }
            if (found.isEmpty()) {
                busy.remove(id);
                send(player, "no-spot");
                return;
            }
            finish(player, target, found.get());
        });
    }

    private void finish(Player player, RtpWorld target, Location spot) {
        UUID id = player.getUniqueId();
        boolean force = forced.remove(id);
        double cost = force || player.hasPermission("smprtp.bypass.cost") ? 0 : target.cost();
        Economy economy = null;
        if (cost > 0) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            economy = provider == null ? null : provider.getProvider();
            if (economy == null) {
                busy.remove(id);
                send(player, "cost-unavailable");
                return;
            }
            EconomyResponse paid = economy.withdrawPlayer(player, cost);
            if (!paid.transactionSuccess()) {
                busy.remove(id);
                send(player, "cost-failed", "cost", economy.format(cost));
                return;
            }
        }
        Economy bank = economy;
        Location destination = spot.clone();
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(player.getLocation().getPitch());
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, failure) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (failure != null || !Boolean.TRUE.equals(ok)) {
                        if (bank != null && cost > 0 && !bank.depositPlayer(player, cost).transactionSuccess()) {
                            plugin.getLogger().severe("Could not refund " + cost + " to " + player.getName()
                                    + " after a failed random teleport. Pay it back manually.");
                        }
                        send(player, "teleport-failed");
                        return;
                    }
                    if (!force) {
                        long now = System.currentTimeMillis();
                        cooldownEnds.put(id, now + settings.cooldownSeconds() * 1000L);
                        try {
                            cooldowns.save(cooldownEnds, now);
                        } catch (java.io.IOException e) {
                            plugin.getLogger().warning("cooldowns.yml could not be saved (the cooldown still applies until restart): " + e.getMessage());
                        }
                    }
                    send(player, "success", "world", target.key(), "x", Integer.toString(destination.getBlockX()),
                            "y", Integer.toString(destination.getBlockY()), "z", Integer.toString(destination.getBlockZ()),
                            "cost", bank == null || cost <= 0 ? "0" : bank.format(cost));
                }));
    }

    // ---------------------------------------------------------------- search

    /**
     * Finds a safe random spot without moving anybody.
     *
     * @param target     the destination
     * @param world      its world
     * @param stillWanted checked before each round; the search stops quietly when it turns false
     * @param done       called on the main thread with the spot (feet position, block centre), or empty
     */
    public void findSpot(RtpWorld target, World world, BooleanSupplier stillWanted, Consumer<Optional<Location>> done) {
        round(target, world, stillWanted, done, 0);
    }

    private void round(RtpWorld target, World world, BooleanSupplier stillWanted, Consumer<Optional<Location>> done,
                       int round) {
        if (round >= settings.rounds()) {
            done.accept(Optional.empty());
            return;
        }
        if (!stillWanted.getAsBoolean()) {
            done.accept(Optional.empty());
            return;
        }
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2 - 16;
        List<int[]> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < settings.candidates() * 4 && candidates.size() < settings.candidates(); i++) {
            int[] xz = target.sample(random);
            if (Math.abs(xz[0] + 0.5 - border.getCenter().getX()) < half && Math.abs(xz[1] + 0.5 - border.getCenter().getZ()) < half) {
                candidates.add(xz);
            }
        }
        if (candidates.isEmpty()) {
            done.accept(Optional.empty());
            return;
        }
        CompletableFuture<?>[] loads = new CompletableFuture<?>[candidates.size()];
        for (int i = 0; i < loads.length; i++) {
            loads[i] = world.getChunkAtAsync(candidates.get(i)[0] >> 4, candidates.get(i)[1] >> 4, true);
        }
        CompletableFuture.allOf(loads).whenComplete((ignored, failure) -> {
            Runnable evaluate = () -> {
                if (failure == null) {
                    for (int[] xz : candidates) {
                        Optional<Location> spot = evaluate(target, world, xz[0], xz[1]);
                        if (spot.isPresent()) {
                            done.accept(spot);
                            return;
                        }
                    }
                }
                round(target, world, stillWanted, done, round + 1);
            };
            if (Bukkit.isPrimaryThread()) {
                evaluate.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, evaluate);
            }
        });
    }

    private Optional<Location> evaluate(RtpWorld target, World world, int x, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return Optional.empty();
        }
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        SpotFinder.View view = (bx, by, bz) -> by < minHeight || by >= maxHeight ? Material.AIR
                : world.getBlockAt(bx, by, bz).getType();
        int from;
        int to;
        if (target.maxY() == -1) {
            from = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            to = from;
        } else {
            from = Math.min(target.maxY(), maxHeight - 3);
            to = Math.max(target.minY(), minHeight);
        }
        OptionalInt feet = finder.find(view, x, z, from, to);
        if (feet.isEmpty()) {
            return Optional.empty();
        }
        Location spot = new Location(world, x + 0.5, feet.getAsInt(), z + 0.5);
        if (!world.getWorldBorder().isInside(spot)) {
            return Optional.empty();
        }
        if (settings.avoidProtected() && protect.isProtected(world.getName(), x, feet.getAsInt(), z)) {
            return Optional.empty();
        }
        return Optional.of(spot);
    }
}
