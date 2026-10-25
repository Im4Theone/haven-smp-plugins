package net.kaloyan.smpspawners;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Stackable spawners that produce items and experience into storage instead of mobs, with a menu to collect and sell. Commands:
 * {@code /spawners} (also {@code /ispawners} and {@code /isp}, so the shop and the crates keep working).
 */
public final class SMPSpawnersPlugin extends JavaPlugin implements TabExecutor {

    private volatile Settings settings;
    private Registry registry;
    private SpawnerItems items;
    private Texts texts;
    private final Random random = new Random();
    private long lastWarning;

    @Override
    public void onEnable() {
        replaceOldConfig();
        saveDefaultConfig();
        try {
            settings = load();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Spawners. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String id : settings.kinds().keySet()) {
            try {
                EntityType.valueOf(id);
            } catch (IllegalArgumentException e) {
                getLogger().severe("config.yml: '" + id + "' is not a creature on this server; disabling SMP-Spawners.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        Path data = getDataFolder().toPath().resolve("spawners.yml");
        importOldData(data);
        registry = new Registry(data);
        try {
            registry.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe every stored spawner, so refuse to run instead.
            getLogger().severe("spawners.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Spawners is disabled so no spawner data is lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        texts = new Texts(() -> settings);
        items = new SpawnerItems(this, () -> settings);
        Payments payments = new Payments();
        Actions actions = new Actions(registry, () -> settings, texts, payments, getLogger());
        Menus menus = new Menus(registry, actions, () -> settings);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(new SpawnerListener(registry, () -> settings, items, actions, menus, texts), this);
        getCommand("spawners").setExecutor(this);
        getCommand("spawners").setTabCompleter(this);
        schedule();
        getLogger().info("SMP-Spawners ready: " + registry.all().size() + " stacked spawner(s), " + settings.kinds().size() + " creatures.");
    }

    @Override
    public void onDisable() {
        if (registry != null) {
            try {
                registry.saveIfDirty();
            } catch (IOException e) {
                getLogger().severe("The last production could not be saved: " + e.getMessage());
            }
        }
    }

    /** Uses the data of the old ISpawners plugin once, if this plugin has none yet (same file format; the old file is left in place). */
    private void importOldData(Path target) {
        Path old = getDataFolder().toPath().resolveSibling("ISpawners").resolve("spawners.yml");
        if (Files.isRegularFile(target) || !Files.isRegularFile(old)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(old, target, StandardCopyOption.COPY_ATTRIBUTES);
            getLogger().info("Copied the stacked spawners of the old ISpawners (" + old + "); the old file was not changed.");
        } catch (IOException e) {
            getLogger().warning("Could not copy the old ISpawners data: " + e.getMessage());
        }
    }

    /** A config from before the price update (no config-version 2) is moved aside so the new prices are used; the old one is kept. */
    private void replaceOldConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        int version = 0;
        try {
            yaml.load(file);
            version = yaml.getInt("config-version", 0);
        } catch (IOException | InvalidConfigurationException e) {
            version = 0;
        }
        if (version < 2) {
            String name = "config.old-" + System.currentTimeMillis() + ".yml";
            try {
                Files.move(file.toPath(), file.toPath().resolveSibling(name));
                getLogger().warning("config.yml was from an older version; it was moved to " + name + " and replaced by the new one (new sell prices).");
            } catch (IOException e) {
                getLogger().warning("Could not replace the old config.yml: " + e.getMessage());
            }
        }
    }

    private Settings load() throws Settings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new Settings.Invalid(List.of("config.yml cannot be read: " + e.getMessage()));
        }
        return Settings.parse(yaml);
    }

    // ---------------------------------------------------------------- production

    private int taskId = -1;
    private long lastSave = System.currentTimeMillis();

    private void schedule() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        taskId = Bukkit.getScheduler().runTaskTimer(this, this::produce, 100L, settings.refreshTicks()).getTaskId();
    }

    private void produce() {
        Settings s = settings;
        List<Spawner> gone = new ArrayList<>();
        double range = (double) s.activationRange() * s.activationRange();
        for (Spawner spawner : registry.all()) {
            World world = Bukkit.getWorld(spawner.pos().world());
            Kind kind = s.kinds().get(spawner.kind());
            if (world == null || kind == null) {
                continue;
            }
            int cx = spawner.pos().x() >> 4;
            int cz = spawner.pos().z() >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            if (world.getBlockAt(spawner.pos().x(), spawner.pos().y(), spawner.pos().z()).getType() != Material.SPAWNER) {
                gone.add(spawner);          // somebody removed the block without us (WorldEdit, a plugin): forget it
                continue;
            }
            Location center = new Location(world, spawner.pos().x() + 0.5, spawner.pos().y() + 0.5, spawner.pos().z() + 0.5);
            boolean near = false;
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= range) {
                    near = true;
                    break;
                }
            }
            if (near) {
                spawner.produce(kind, s, random);
                registry.markDirty();
            }
        }
        try {
            for (Spawner g : gone) {
                registry.remove(g.pos());
                getLogger().warning("The spawner block at " + g.pos() + " no longer exists; its stored loot (" + g.totalItems() + " items) was dropped.");
            }
            long now = System.currentTimeMillis();
            if (now - lastSave >= 30_000 || !gone.isEmpty()) {
                lastSave = now;
                registry.saveIfDirty();
            }
        } catch (IOException e) {
            long failedAt = System.currentTimeMillis();
            if (failedAt - lastWarning > 60_000) {
                lastWarning = failedAt;
                getLogger().severe("Spawner data could not be saved (it is tried again): " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> texts.send(sender, "info", "count", registry.all().size(), "kinds", String.join(", ", settings.kinds().keySet()));
            case "give" -> give(sender, args);
            case "givespawner" -> giveSelf(sender, args);
            case "setstacksize" -> setStack(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("smpspawners.admin")) {
                    texts.send(sender, "no-permission");
                    return true;
                }
                try {
                    settings = load();
                    schedule();
                    texts.send(sender, "reloaded");
                } catch (Settings.Invalid e) {
                    getLogger().warning("Reload rejected, the previous settings stay active:");
                    e.problems().forEach(problem -> getLogger().warning(" - " + problem));
                    texts.send(sender, "reload-rejected");
                }
            }
            default -> texts.send(sender, "usage");
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("smpspawners.admin") || sender.hasPermission("ispawners.admin")) {
            return true;
        }
        texts.send(sender, "no-permission");
        return false;
    }

    private void give(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 3) {
            texts.send(sender, "usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            texts.send(sender, "not-online", "player", args[1]);
            return;
        }
        deliver(sender, target, args[2], args.length >= 4 ? args[3] : "1");
    }

    private void giveSelf(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            texts.send(sender, "not-player");
            return;
        }
        if (args.length < 2) {
            texts.send(sender, "usage");
            return;
        }
        deliver(sender, player, args[1], args.length >= 3 ? args[2] : "1");
    }

    private void deliver(CommandSender sender, Player target, String kindText, String amountText) {
        String id = kindText.toUpperCase(Locale.ROOT);
        if (!settings.kinds().containsKey(id)) {
            texts.send(sender, "unknown-kind", "kinds", String.join(", ", settings.kinds().keySet()));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(amountText);
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > 2304) {
            texts.send(sender, "bad-amount");
            return;
        }
        int left = amount;
        while (left > 0) {
            int part = Math.min(left, Material.SPAWNER.getMaxStackSize());
            for (var rest : target.getInventory().addItem(items.create(id, part)).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), rest);       // never lose the item
            }
            left -= part;
        }
        texts.send(sender, "gave", "player", target.getName(), "count", amount, "kind", Texts.pretty(id));
        if (sender != target) {
            texts.send(target, "received", "count", amount, "kind", Texts.pretty(id));
        }
    }

    private void setStack(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            texts.send(sender, "not-player");
            return;
        }
        Block block = player.getTargetBlockExact(6);
        Spawner spawner = block == null ? null : registry.at(SpawnerListener.pos(block));
        if (spawner == null) {
            texts.send(sender, "look-at-spawner");
            return;
        }
        int size;
        try {
            size = args.length >= 2 ? Integer.parseInt(args[1]) : 0;
        } catch (NumberFormatException e) {
            size = 0;
        }
        if (size < 1 || size > settings.maxStack()) {
            texts.send(sender, "bad-stack", "max", settings.maxStack());
            return;
        }
        int before = spawner.stack();
        spawner.stack(size);
        try {
            registry.commit(() -> spawner.stack(before));
            texts.send(sender, "stack-set", "size", size);
        } catch (IOException e) {
            texts.send(sender, "save-failed");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        boolean admin = sender.hasPermission("smpspawners.admin") || sender.hasPermission("ispawners.admin");
        if (args.length == 1) {
            options.add("info");
            if (admin) {
                options.addAll(List.of("give", "givespawner", "setstacksize", "reload"));
            }
        } else if (admin && args.length == 2 && args[0].equalsIgnoreCase("give")) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (admin && ((args.length == 3 && args[0].equalsIgnoreCase("give")) || (args.length == 2 && args[0].equalsIgnoreCase("givespawner")))) {
            settings.kinds().keySet().forEach(k -> options.add(k.toLowerCase(Locale.ROOT)));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    /**
     * For other plugins through reflection (the shop and the crates also use the {@code /ispawners give} command).
     *
     * @param player the player
     * @param kind   the creature id
     * @param amount how many
     * @return {@code true} if the items were handed over (or dropped at the player's feet)
     */
    public boolean giveSpawners(UUID player, String kind, int amount) {
        Player target = Bukkit.getPlayer(player);
        String id = kind.toUpperCase(Locale.ROOT);
        if (target == null || !settings.kinds().containsKey(id) || amount < 1 || amount > 2304) {
            return false;
        }
        deliver(Bukkit.getConsoleSender(), target, id, Integer.toString(amount));
        return true;
    }
}
