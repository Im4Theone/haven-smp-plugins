package net.kaloyan.smpcatch;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Catches x-ray cheaters with decoys and statistics, and gives staff vanish and freeze. */
public final class SMPCatchPlugin extends JavaPlugin implements TabExecutor {

    private static final int CONFIG_VERSION = 1;
    private static final String ADMIN = "smpcatch.admin";

    private volatile Settings settings;
    private TrapStore store;
    private Suspects suspects;
    private Alerts alerts;
    private Placer placer;
    private TrapListener listener;
    private Vanish vanish;
    private Freeze freeze;
    private NamespacedKey baitKey;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Catch. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Path data = getDataFolder().toPath();
        store = new TrapStore(data.resolve("traps.yml"));
        suspects = new Suspects(data.resolve("suspects.yml"), settings.decayPerHour());
        try {
            store.load();
            suspects.load();
        } catch (IOException e) {
            // Starting empty would let the next save overwrite the file, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Catch is disabled so no data is lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        baitKey = new NamespacedKey(this, "bait");
        alerts = new Alerts(() -> settings, suspects, data.resolve("alerts.log"), getLogger());
        placer = new Placer(store, () -> settings, baitKey);
        listener = new TrapListener(this, store, suspects, alerts, placer, () -> settings, baitKey);
        vanish = new Vanish(this, () -> settings, data.resolve("vanish.yml"));
        if (settings.vanishPersist()) {
            vanish.load();
        }
        freeze = new Freeze(() -> settings, alerts);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(vanish, this);
        getServer().getPluginManager().registerEvents(freeze, this);
        for (String name : List.of("trap", "v", "freeze")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            store.saveIfDirty();
            suspects.saveIfDirty();
        }, 20L * 30, 20L * 30);
        getServer().getScheduler().runTaskTimer(this, vanish::remind, 40L, 40L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanish.isVanished(p.getUniqueId())) {
                vanish.set(p, true, false);
            }
        }
        getLogger().info("SMP-Catch ready: " + store.all().size() + " decoy(s), " + vanish.online().size() + " vanished.");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.saveIfDirty();
        }
        if (suspects != null) {
            suspects.saveIfDirty();
        }
        if (vanish != null) {
            vanish.revealAll();     // nobody stays hidden while the plugin is not running; they are hidden again when it starts
        }
    }

    /** Loads config.yml, replacing one from an older version (the old file is kept) and filling gaps from the built-in one. */
    private Settings loadSettings() throws Settings.Invalid, IOException, InvalidConfigurationException {
        Path path = getDataFolder().toPath().resolve("config.yml");
        if (Files.isRegularFile(path)) {
            YamlConfiguration current = new YamlConfiguration();
            current.load(path.toFile());
            if (current.getInt("config-version", 0) < CONFIG_VERSION) {
                Path old = path.resolveSibling("config.old-" + System.currentTimeMillis() + ".yml");
                Files.move(path, old, StandardCopyOption.REPLACE_EXISTING);
                getLogger().warning("config.yml was from an older version; it was moved to " + old.getFileName() + " and replaced.");
            }
        }
        saveDefaultConfig();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        try (Reader reader = new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        return Settings.parse(yaml);
    }

    // ---------------------------------------------------------------- for other plugins (through reflection)

    /**
     * @param id a player
     * @return whether they are vanished (SMP-Scoreboard hides them from its counts)
     */
    public boolean isVanished(UUID id) {
        return vanish != null && vanish.isVanished(id);
    }

    /** @return how many players are online and not vanished */
    public int visibleOnlineCount() {
        return Bukkit.getOnlinePlayers().size() - (vanish == null ? 0 : vanish.onlineCount());
    }

    // ---------------------------------------------------------------- commands

    private static void say(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }

    private Player find(CommandSender sender, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null || sender instanceof Player viewer && !viewer.canSee(p)) {
            say(sender, "No such player online: " + name, NamedTextColor.RED);
            return null;
        }
        return p;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName()) {
            case "v" -> vanishCommand(sender, args);
            case "freeze" -> freezeCommand(sender, args);
            default -> trapCommand(sender, args);
        }
        return true;
    }

    private void vanishCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcatch.vanish")) {
            say(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("list")) {
            List<String> names = new ArrayList<>();
            vanish.online().forEach(p -> names.add(p.getName()));
            say(sender, names.isEmpty() ? "Nobody is vanished." : "Vanished: " + String.join(", ", names), NamedTextColor.WHITE);
            return;
        }
        Player target;
        Boolean want = null;
        if (args.length > 0 && !args[0].equalsIgnoreCase("on") && !args[0].equalsIgnoreCase("off")) {
            if (!sender.hasPermission("smpcatch.vanish.others")) {
                say(sender, "You can only vanish yourself.", NamedTextColor.RED);
                return;
            }
            target = find(sender, args[0]);
            if (target == null) {
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
            if (args.length > 0) {
                want = args[0].equalsIgnoreCase("on");
            }
        } else {
            say(sender, "Usage: /v <player>", NamedTextColor.RED);
            return;
        }
        boolean on = want != null ? want : !vanish.isVanished(target.getUniqueId());
        vanish.set(target, on, true);
        if (!target.equals(sender)) {
            say(sender, target.getName() + (on ? " is vanished." : " is visible."), NamedTextColor.GREEN);
        }
    }

    private void freezeCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpcatch.freeze")) {
            say(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        if (args.length != 1) {
            say(sender, "Usage: /freeze <player>", NamedTextColor.RED);
            return;
        }
        Player target = find(sender, args[0]);
        if (target == null) {
            return;
        }
        boolean now = freeze.toggle(target);
        say(sender, target.getName() + (now ? " is frozen." : " is released."), NamedTextColor.GREEN);
        getLogger().info("ADMIN " + sender.getName() + (now ? " froze " : " released ") + target.getName());
    }

    private void trapCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            say(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "vein" -> vein(sender, args);
                case "stash" -> stash(sender, args);
                case "list" -> list(sender, args);
                case "tp" -> tp(sender, args);
                case "remove" -> remove(sender, args);
                case "removeall" -> removeAll(sender, args);
                case "suspects" -> suspectList(sender);
                case "stats" -> stats(sender, args);
                case "forgive" -> forgive(sender, args);
                case "bait" -> bait(sender, args);
                case "alerts" -> {
                    if (sender instanceof Player p) {
                        boolean on = alerts.toggle(p.getUniqueId());
                        p.sendMessage(MiniMessage.miniMessage().deserialize(settings.message(on ? "alerts-on" : "alerts-off")));
                    }
                }
                case "reload" -> reload(sender);
                default -> say(sender, "Usage: /trap <vein <ore> [count] [radius] | stash [chest|barrel|shulker] [radius] | list | tp <id> | remove <id> | "
                        + "removeall confirm | suspects | stats <player> | forgive <player> | bait <player> | alerts | reload>", NamedTextColor.RED);
            }
        } catch (IOException e) {
            say(sender, "Could not save: " + e.getMessage(), NamedTextColor.RED);
        }
    }

    private int number(String[] args, int index, int fallback, int min, int max) {
        if (args.length <= index) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(args[index])));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The middle of the area for a decoy: the player's position, or {@code world x z} typed after the other arguments (for the console). */
    private Location origin(CommandSender sender, String[] args, int index) {
        if (args.length >= index + 3) {
            World world = Bukkit.getWorld(args[index]);
            try {
                if (world != null) {
                    return new Location(world, Integer.parseInt(args[index + 1]), 64, Integer.parseInt(args[index + 2]));
                }
            } catch (NumberFormatException ignored) {
                // reported below
            }
            say(sender, "Unknown world or coordinates: " + args[index] + " " + args[index + 1] + " " + args[index + 2], NamedTextColor.RED);
            return null;
        }
        if (sender instanceof Player player) {
            return player.getLocation();
        }
        say(sender, "From the console add: <world> <x> <z> at the end.", NamedTextColor.RED);
        return null;
    }

    private void vein(CommandSender sender, String[] args) throws IOException {
        Location at = origin(sender, args, 4);
        if (at == null) {
            return;
        }
        World world = at.getWorld();
        Ore ore = args.length > 1 ? Ore.find(args[1], world.getEnvironment()) : null;
        if (ore == null) {
            say(sender, "Usage: /trap vein <" + String.join("|", Ore.names(world.getEnvironment())) + "> [count 1-20] [radius 8-128] [world x z]. "
                    + "(The ores that exist depend on the dimension; netherite means debris.)", NamedTextColor.RED);
            return;
        }
        int count = number(args, 2, 1, 1, 20);
        int radius = number(args, 3, 48, 8, 128);
        List<TrapStore.Trap> placed = placer.veins(world, at.getBlockX(), at.getBlockZ(), radius, ore, count, random);
        say(sender, "Placed " + placed.size() + " of " + count + " " + ore.name() + " vein(s) hidden inside solid rock"
                + (placed.size() < count ? " (no hidden spot found for the rest; try a bigger radius or a place with more rock)" : "") + ".",
                placed.isEmpty() ? NamedTextColor.RED : NamedTextColor.GREEN);
        for (TrapStore.Trap t : placed) {
            TrapStore.Part p = t.focus();
            say(sender, "  #" + t.id() + " at " + p.x() + " " + p.y() + " " + p.z() + " (" + t.parts().size() + " blocks)", NamedTextColor.GRAY);
        }
    }

    private void stash(CommandSender sender, String[] args) throws IOException {
        Material container = Material.CHEST;
        int next = 1;
        if (args.length > 1) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "chest" -> next = 2;
                case "barrel" -> {
                    container = Material.BARREL;
                    next = 2;
                }
                case "shulker" -> {
                    container = Material.SHULKER_BOX;
                    next = 2;
                }
                default -> { }
            }
        }
        int radius = number(args, next, 48, 8, 128);
        Location at = origin(sender, args, next + 1);
        if (at == null) {
            return;
        }
        TrapStore.Trap trap = placer.stash(at.getWorld(), at.getBlockX(), at.getBlockZ(), radius, container, random);
        if (trap == null) {
            say(sender, "No hidden spot found within " + radius + " blocks. Try a bigger radius or somewhere with more solid rock.", NamedTextColor.RED);
            return;
        }
        TrapStore.Part p = trap.focus();
        say(sender, "Stash #" + trap.id() + " built at " + p.x() + " " + p.y() + " " + p.z() + ". It is sealed inside solid rock.", NamedTextColor.GREEN);
    }

    private void list(CommandSender sender, String[] args) {
        List<TrapStore.Trap> all = store.all();
        int per = 10;
        int pages = Math.max(1, (all.size() + per - 1) / per);
        int page = number(args, 1, 1, 1, pages);
        say(sender, "Decoys: " + all.size() + " (page " + page + "/" + pages + ")", NamedTextColor.GOLD);
        for (int i = (page - 1) * per; i < Math.min(all.size(), page * per); i++) {
            TrapStore.Trap t = all.get(i);
            TrapStore.Part p = t.focus();
            sender.sendMessage(Component.text("#" + t.id() + " " + t.kind().toLowerCase(Locale.ROOT) + " " + t.type() + " " + t.world() + " " + p.x() + " "
                    + p.y() + " " + p.z() + " triggered " + t.triggers() + "x", NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/trap tp " + t.id())));
        }
    }

    private void tp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length != 2) {
            say(sender, "Usage: /trap tp <id> (players only)", NamedTextColor.RED);
            return;
        }
        TrapStore.Trap t = trapArg(args[1]);
        World world = t == null ? null : Bukkit.getWorld(t.world());
        if (t == null || world == null) {
            say(sender, "No such decoy.", NamedTextColor.RED);
            return;
        }
        TrapStore.Part p = t.focus();
        player.teleportAsync(new Location(world, p.x() + 0.5, p.y() + 1, p.z() + 0.5));
        say(sender, "Teleporting. The decoy is inside rock: dig, or use spectator mode.", NamedTextColor.GRAY);
    }

    private TrapStore.Trap trapArg(String text) {
        try {
            return store.get(Integer.parseInt(text.replace("#", "")));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void remove(CommandSender sender, String[] args) {
        TrapStore.Trap t = args.length == 2 ? trapArg(args[1]) : null;
        if (t == null) {
            say(sender, "Usage: /trap remove <id>", NamedTextColor.RED);
            return;
        }
        say(sender, placer.remove(t) ? "Removed decoy #" + t.id() + "." : "Could not save the list.", NamedTextColor.GREEN);
    }

    private void removeAll(CommandSender sender, String[] args) {
        if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
            say(sender, "This removes every decoy from the world. Type /trap removeall confirm", NamedTextColor.YELLOW);
            return;
        }
        int n = 0;
        for (TrapStore.Trap t : store.all()) {
            if (placer.remove(t)) {
                n++;
            }
        }
        say(sender, "Removed " + n + " decoy(s).", NamedTextColor.GREEN);
        getLogger().info("ADMIN " + sender.getName() + " removed all " + n + " decoys");
    }

    private void suspectList(CommandSender sender) {
        long now = System.currentTimeMillis();
        List<Suspects.Suspect> ranked = suspects.ranked(now);
        say(sender, "Players with points:", NamedTextColor.GOLD);
        int shown = 0;
        for (Suspects.Suspect x : ranked) {
            double points = suspects.points(x.id(), now);
            if (points <= 0 || shown++ >= 10) {
                continue;
            }
            sender.sendMessage(Component.text(String.format("%.1f  %s  (hidden rare ores mined: %d)", points, x.name(), x.hiddenRare()), NamedTextColor.WHITE)
                    .clickEvent(ClickEvent.runCommand("/trap stats " + x.name())));
        }
        if (shown == 0) {
            say(sender, "Nobody has points.", NamedTextColor.GRAY);
        }
    }

    private OfflinePlayer offline(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer p = online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
        if (p == null) {
            say(sender, "Unknown player: " + name, NamedTextColor.RED);
        }
        return p;
    }

    private void stats(CommandSender sender, String[] args) {
        OfflinePlayer p = args.length == 2 ? offline(sender, args[1]) : null;
        if (p == null) {
            if (args.length != 2) {
                say(sender, "Usage: /trap stats <player>", NamedTextColor.RED);
            }
            return;
        }
        long now = System.currentTimeMillis();
        Suspects.Suspect x = suspects.get(p.getUniqueId());
        if (x == null) {
            say(sender, p.getName() + " has no record.", NamedTextColor.GRAY);
            return;
        }
        say(sender, p.getName() + ": " + String.format("%.1f", suspects.points(x.id(), now)) + " points, " + x.hiddenRare()
                + " hidden rare ores mined in total. Last events:", NamedTextColor.GOLD);
        for (Suspects.Event e : x.events()) {
            say(sender, "  " + duration(now - e.time()) + " ago: " + e.what(), NamedTextColor.WHITE);
        }
    }

    private static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes >= 1440) {
            return minutes / 1440 + "d " + minutes % 1440 / 60 + "h";
        }
        return minutes >= 60 ? minutes / 60 + "h " + minutes % 60 + "m" : Math.max(1, minutes) + "m";
    }

    private void forgive(CommandSender sender, String[] args) {
        OfflinePlayer p = args.length == 2 ? offline(sender, args[1]) : null;
        if (p == null) {
            return;
        }
        say(sender, suspects.clear(p.getUniqueId()) ? "Cleared the record of " + p.getName() + "." : p.getName() + " had no record.", NamedTextColor.GREEN);
        getLogger().info("ADMIN " + sender.getName() + " cleared the record of " + p.getName());
    }

    private void bait(CommandSender sender, String[] args) {
        Player target = args.length == 2 ? find(sender, args[1]) : null;
        if (target == null) {
            return;
        }
        int inventory = count(target.getInventory().getContents());
        int ender = count(target.getEnderChest().getContents());
        say(sender, target.getName() + " has " + inventory + " stash item(s) in the inventory and " + ender + " in the ender chest.",
                inventory + ender > 0 ? NamedTextColor.RED : NamedTextColor.GREEN);
    }

    private int count(ItemStack[] items) {
        int n = 0;
        for (ItemStack i : items) {
            if (listener.isBait(i)) {
                n += i.getAmount();
            }
        }
        return n;
    }

    private void reload(CommandSender sender) {
        try {
            settings = loadSettings();
            say(sender, "SMP-Catch reloaded (the point decay and the statistics windows of players already mining apply after a restart).", NamedTextColor.GREEN);
        } catch (Settings.Invalid e) {
            say(sender, "config.yml is invalid, nothing changed:", NamedTextColor.RED);
            e.problems().forEach(problem -> say(sender, " - " + problem, NamedTextColor.RED));
        } catch (IOException | InvalidConfigurationException e) {
            say(sender, "config.yml cannot be read: " + e.getMessage(), NamedTextColor.RED);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("trap") && args.length == 1) {
            return List.of("vein", "stash", "list", "tp", "remove", "removeall", "suspects", "stats", "forgive", "bait", "alerts", "reload");
        }
        if (command.getName().equals("trap") && args.length == 2 && args[0].equalsIgnoreCase("vein") && sender instanceof Player p) {
            return Ore.names(p.getWorld().getEnvironment());
        }
        if (command.getName().equals("trap") && args.length == 2 && args[0].equalsIgnoreCase("stash")) {
            return List.of("chest", "barrel", "shulker");
        }
        if (command.getName().equals("v") && args.length == 1) {
            return List.of("on", "off", "list");
        }
        return args.length == 1 ? null : List.of();
    }
}
