package net.kaloyan.smptravel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kaloyan.smptravel.TpaRequests.Request;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** All commands of the plugin: spawn, hub, setspawn, homes, tpa and back. */
public final class TravelCommands implements TabExecutor {

    private final SpotStore store;
    private final Teleporter teleporter;
    private final Messages messages;
    private final Supplier<TravelSettings> settings;
    private final TpaRequests requests = new TpaRequests();
    private final Map<UUID, Long> lastRequest = new java.util.HashMap<>();

    /**
     * @param store      homes and the server locations
     * @param teleporter the warm-up teleporter
     * @param messages   the texts
     * @param settings   the current settings
     */
    public TravelCommands(SpotStore store, Teleporter teleporter, Messages messages, Supplier<TravelSettings> settings) {
        this.store = store;
        this.teleporter = teleporter;
        this.messages = messages;
        this.settings = settings;
    }

    /** Forgets a player who left. */
    public void forget(UUID id) {
        requests.forget(id);
        lastRequest.remove(id);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "not-player");
            return true;
        }
        try {
            switch (command.getName()) {
                case "spawn" -> toServerSpot(player, "spawn", settings.get().spawnWorld());
                case "hub" -> toServerSpot(player, "hub", settings.get().hubWorld());
                case "setspawn" -> setSpawn(player, args);
                case "sethome" -> setHome(player, args);
                case "home" -> home(player, args);
                case "homes" -> listHomes(player);
                case "delhome" -> delHome(player, args);
                case "tpa" -> request(player, args, false);
                case "tpahere" -> request(player, args, true);
                case "tpaccept" -> answer(player, true);
                case "tpdeny" -> answer(player, false);
                case "tpcancel" -> cancel(player);
                case "back" -> back(player);
                default -> messages.send(player, "unknown");
            }
        } catch (IOException e) {
            messages.send(player, "save-failed", "error", String.valueOf(e.getMessage()));
        }
        return true;
    }

    // ---------------------------------------------------------------- spawn and hub

    private void toServerSpot(Player player, String key, String fallbackWorld) {
        Optional<Spot> saved = store.server(key);
        Location destination = saved.map(Spot::toLocation).orElse(null);
        if (destination == null && key.equals("hub")) {
            destination = settings.get().respawn().toLocation();     // the hub place of the config (0 100 0), not the world's spawn point
        }
        if (destination == null) {
            World world = Bukkit.getWorld(fallbackWorld);
            if (world == null) {
                messages.send(player, key + "-missing", "world", fallbackWorld);
                return;
            }
            destination = world.getSpawnLocation().add(0.5, 0, 0.5);
        }
        teleporter.teleport(player, destination, key);
    }

    private void setSpawn(Player player, String[] args) throws IOException {
        if (!player.hasPermission("smptravel.admin")) {
            messages.send(player, "no-permission");
            return;
        }
        String key = args.length > 0 && args[0].equalsIgnoreCase("hub") ? "hub" : "spawn";
        store.setServer(key, Spot.of(player.getLocation()));
        messages.send(player, "set-spawn", "what", key);
    }

    // ---------------------------------------------------------------- homes

    private int limit(Player player) {
        if (player.hasPermission("smptravel.homes.unlimited")) {
            return Integer.MAX_VALUE;
        }
        return TravelSettings.homeLimit(settings.get().defaultHomes(), n -> player.hasPermission("smptravel.homes." + n));
    }

    private void setHome(Player player, String[] args) throws IOException {
        String name = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "home";
        if (!name.matches(SpotStore.NAME_PATTERN)) {
            messages.send(player, "bad-name");
            return;
        }
        Map<String, Spot> mine = store.homes(player.getUniqueId());
        int max = limit(player);
        if (!mine.containsKey(name) && mine.size() >= max) {
            messages.send(player, "home-limit", "limit", Integer.toString(max));
            return;
        }
        store.setHome(player.getUniqueId(), name, Spot.of(player.getLocation()));
        messages.send(player, "home-set", "name", name);
    }

    private void home(Player player, String[] args) {
        Map<String, Spot> mine = store.homes(player.getUniqueId());
        if (mine.isEmpty()) {
            messages.send(player, "home-none");
            return;
        }
        String name;
        if (args.length > 0) {
            name = args[0].toLowerCase(Locale.ROOT);
        } else if (mine.size() == 1) {
            name = mine.keySet().iterator().next();
        } else if (mine.containsKey("home")) {
            name = "home";
        } else {
            messages.send(player, "home-which", "homes", String.join(", ", mine.keySet()));
            return;
        }
        Spot spot = mine.get(name);
        if (spot == null) {
            messages.send(player, "home-unknown", "name", name, "homes", String.join(", ", mine.keySet()));
            return;
        }
        Location destination = spot.toLocation();
        if (destination == null) {
            messages.send(player, "home-world-missing", "name", name, "world", spot.world());
            return;
        }
        if (!isFree(destination)) {
            messages.send(player, "home-blocked", "name", name);
            return;
        }
        teleporter.teleport(player, destination, "home " + name);
    }

    private void listHomes(Player player) {
        Map<String, Spot> mine = store.homes(player.getUniqueId());
        int max = limit(player);
        if (mine.isEmpty()) {
            messages.send(player, "home-none");
        } else {
            messages.send(player, "home-list", "homes", String.join(", ", mine.keySet()), "count", Integer.toString(mine.size()),
                    "limit", max == Integer.MAX_VALUE ? "unlimited" : Integer.toString(max));
        }
    }

    private void delHome(Player player, String[] args) throws IOException {
        String name = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "home";
        if (store.deleteHome(player.getUniqueId(), name)) {
            messages.send(player, "home-deleted", "name", name);
        } else {
            messages.send(player, "home-unknown", "name", name, "homes", String.join(", ", store.homes(player.getUniqueId()).keySet()));
        }
    }

    /** A spot a player can stand in: their feet and head are not inside solid blocks. */
    private static boolean isFree(Location at) {
        return at.getBlock().isPassable() && at.clone().add(0, 1, 0).getBlock().isPassable();
    }

    // ---------------------------------------------------------------- tpa

    private void request(Player player, String[] args, boolean here) {
        if (args.length < 1) {
            messages.send(player, "tpa-usage", "command", here ? "tpahere" : "tpa");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(player, "tpa-offline", "player", args[0]);
            return;
        }
        if (target.equals(player)) {
            messages.send(player, "tpa-self");
            return;
        }
        long now = System.currentTimeMillis();
        long wait = lastRequest.getOrDefault(player.getUniqueId(), 0L) + settings.get().tpaCooldown() * 1000L - now;
        if (wait > 0) {
            messages.send(player, "tpa-cooldown", "seconds", Long.toString((wait + 999) / 1000));
            return;
        }
        lastRequest.put(player.getUniqueId(), now);
        requests.send(player.getUniqueId(), target.getUniqueId(), here, now, settings.get().tpaExpire() * 1000L);
        messages.send(player, "tpa-sent", "player", target.getName());
        messages.send(target, here ? "tpa-received-here" : "tpa-received", "player", player.getName(),
                "seconds", Integer.toString(settings.get().tpaExpire()));
    }

    private void answer(Player target, boolean accept) {
        Optional<Request> taken = requests.take(target.getUniqueId(), System.currentTimeMillis());
        if (taken.isEmpty()) {
            messages.send(target, "tpa-none");
            return;
        }
        Player from = Bukkit.getPlayer(taken.get().from());
        if (from == null) {
            messages.send(target, "tpa-offline", "player", "that player");
            return;
        }
        if (!accept) {
            messages.send(target, "tpa-denied-you", "player", from.getName());
            messages.send(from, "tpa-denied", "player", target.getName());
            return;
        }
        messages.send(target, "tpa-accepted-you", "player", from.getName());
        messages.send(from, "tpa-accepted", "player", target.getName());
        if (taken.get().here()) {
            teleporter.teleport(target, from.getLocation(), from.getName());
        } else {
            teleporter.teleport(from, target.getLocation(), target.getName());
        }
    }

    private void cancel(Player player) {
        Optional<UUID> target = requests.cancelFrom(player.getUniqueId());
        if (target.isPresent()) {
            messages.send(player, "tpa-cancelled");
        } else {
            messages.send(player, "tpa-none-sent");
        }
    }

    // ---------------------------------------------------------------- back

    private void back(Player player) {
        if (!player.hasPermission("smptravel.back")) {
            messages.send(player, "no-permission");
            return;
        }
        Spot spot = teleporter.back(player.getUniqueId());
        Location destination = spot == null ? null : spot.toLocation();
        if (destination == null) {
            messages.send(player, "back-none");
            return;
        }
        if (!isFree(destination)) {
            messages.send(player, "home-blocked", "name", "your last location");
            return;
        }
        teleporter.teleport(player, destination, "your last location");
    }

    // ---------------------------------------------------------------- completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        switch (command.getName()) {
            case "home", "delhome" -> options.addAll(store.homes(player.getUniqueId()).keySet());
            case "tpa", "tpahere" -> Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(player)).forEach(p -> options.add(p.getName()));
            case "setspawn" -> options.addAll(List.of("spawn", "hub"));
            default -> { }
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
