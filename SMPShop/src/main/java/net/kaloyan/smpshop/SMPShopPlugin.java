package net.kaloyan.smpshop;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** The Haven SMP shop: items for money (through Vault) and keys and spawners for Shards, the playtime currency. */
public final class SMPShopPlugin extends JavaPlugin implements TabExecutor {

    private static final int CONFIG_VERSION = 3;
    private static final String ADMIN = "smpshop.admin";

    private volatile Catalog catalog;
    private ShardManager shards;
    private ShopMenus menus;

    @Override
    public void onEnable() {
        try {
            catalog = loadCatalog();
        } catch (Catalog.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Shop. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Path file = getDataFolder().toPath().resolve("shards.yml");
        importOldShards(file);
        shards = new ShardManager(file, getLogger());
        try {
            shards.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe every balance, so refuse to run instead.
            getLogger().severe("shards.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Shop is disabled so no shards are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Seller seller = new Seller(Bank.vault(), shards, Deliveries.bukkit(), getLogger());
        menus = new ShopMenus(() -> catalog, shards, seller);
        getServer().getPluginManager().registerEvents(menus, this);
        getCommand("shop").setExecutor(this);
        getCommand("shards").setExecutor(this);
        getCommand("shards").setTabCompleter(this);
        long ticks = catalog.rewardMinutes() * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, this::rewardRound, ticks, ticks);
        getServer().getScheduler().runTaskTimerAsynchronously(this, shards::saveIfDirty, 20L * 30, 20L * 30);
        getLogger().info("SMP-Shop ready: " + catalog.categories().size() + " categories, " + catalog.keys().size() + " keys, "
                + catalog.spawners().size() + " spawners.");
    }

    @Override
    public void onDisable() {
        if (shards != null) {
            shards.saveIfDirty();
        }
    }

    /** @return the shard balances; SMP-Scoreboard and SMP-Crates call this through reflection */
    public ShardManager getShardManager() {
        return shards;
    }

    // ---------------------------------------------------------------- configuration

    /** Loads config.yml, replacing one from an older version (the old file is kept) and filling gaps from the built-in one. */
    private Catalog loadCatalog() throws Catalog.Invalid, IOException, InvalidConfigurationException {
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
        return Catalog.parse(yaml);
    }

    private void importOldShards(Path target) {
        Path old = getDataFolder().toPath().resolveSibling("DonutShop").resolve("shards.yml");
        if (Files.isRegularFile(target) || !Files.isRegularFile(old)) {
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(old, target);
            getLogger().info("Copied the shard balances of the old DonutShop (" + old + "); the old file was not changed.");
        } catch (IOException e) {
            getLogger().warning("Could not copy the old shards.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- shards

    private void rewardRound() {
        int chance = catalog.rewardChance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (Afk.is(player.getUniqueId()) || ThreadLocalRandom.current().nextInt(100) >= chance) {
                continue;
            }
            long total = shards.add(player.getUniqueId(), 1);
            player.sendMessage(MiniMessage.miniMessage().deserialize(catalog.message("shard-found"),
                    Placeholder.unparsed("total", Long.toString(total))));
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("shop")) {
            if (sender instanceof Player player) {
                menus.openMain(player);
            } else {
                sender.sendMessage("Only players can open the shop.");
            }
            return true;
        }
        return shardsCommand(sender, args);
    }

    private boolean shardsCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                player.sendMessage(MiniMessage.miniMessage().deserialize(catalog.message("shards-balance"),
                        Placeholder.unparsed("shards", Long.toString(shards.get(player.getUniqueId())))));
            } else {
                sender.sendMessage("Usage: /shards <give|take|set> <player> <amount>");
            }
            return true;
        }
        if (!sender.hasPermission(ADMIN) && !sender.hasPermission("donutshop.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length != 3 || !List.of("give", "take", "set").contains(action)) {
            sender.sendMessage(Component.text("Usage: /shards <give|take|set> <player> <amount>", NamedTextColor.RED));
            return true;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(args[1]);
        }
        if (target == null) {
            sender.sendMessage(Component.text("Unknown player: " + args[1], NamedTextColor.RED));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        if (amount < 0 || amount > 1_000_000_000L) {
            sender.sendMessage(Component.text("The amount must be a whole number from 0 to 1000000000.", NamedTextColor.RED));
            return true;
        }
        UUID id = target.getUniqueId();
        switch (action) {
            case "give" -> shards.add(id, amount);
            case "take" -> shards.set(id, Math.max(0, shards.get(id) - amount));
            default -> shards.set(id, amount);
        }
        getLogger().info("ADMIN " + sender.getName() + " /shards " + action + " " + target.getName() + " " + amount);
        sender.sendMessage(Component.text(target.getName() + " now has " + shards.get(id) + " Shards.", NamedTextColor.WHITE));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(ADMIN)) {
            return List.of("give", "take", "set");
        }
        return List.of();
    }
}
