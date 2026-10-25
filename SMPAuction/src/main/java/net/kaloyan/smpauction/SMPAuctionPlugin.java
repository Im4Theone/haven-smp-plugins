package net.kaloyan.smpauction;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** The Haven SMP auction house: Buy It Now and bidding, a collection box, history, fees and tax. */
public final class SMPAuctionPlugin extends JavaPlugin implements TabExecutor {

    private static final int CONFIG_VERSION = 2;
    private static final String ADMIN = "smpauction.admin";

    private volatile Settings settings;
    private AuctionService service;
    private Menus menus;

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Auction. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        AuctionStore store = new AuctionStore(getDataFolder().toPath().resolve("auction.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe every listing, item and payment, so refuse to run instead.
            getLogger().severe("auction.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Auction is disabled so no items or money are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service = new AuctionService(store, Bank.vault(), () -> settings, getLogger(), System::currentTimeMillis);
        Prompts prompts = new Prompts(this);
        menus = new Menus(this, service, () -> settings, prompts);
        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getCommand("ah").setExecutor(this);
        getCommand("ah").setTabCompleter(this);
        menus.announce(service.expireDue());
        service.payAll();
        getServer().getScheduler().runTaskTimer(this, () -> {
            menus.announce(service.expireDue());
            service.payAll();
        }, 20L * 30, 20L * 30);
        getLogger().info("SMP-Auction ready: " + store.listings().size() + " listing(s).");
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reload") || action.equals("info") || action.equals("cancel")) {
            return admin(sender, action, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use the auction house.");
            return true;
        }
        switch (action) {
            case "" -> menus.openBrowse(player, "");
            case "sell", "auction" -> sell(player, args, action.equals("auction"));
            case "mine", "listings" -> menus.openMine(player);
            case "collect", "box" -> menus.openCollect(player);
            case "history" -> menus.openHistory(player);
            default -> menus.openBrowse(player, String.join(" ", args));
        }
        return true;
    }

    private void sell(Player player, String[] args, boolean auction) {
        if (args.length < 2) {
            if (auction) {
                menus.say(player, "usage-sell");
            } else {
                menus.openSell(player);
            }
            return;
        }
        long cents = Amounts.cents(args[1]);
        if (cents < 1) {
            menus.say(player, "invalid-price");
            return;
        }
        boolean bid = auction || args.length > 2 && args[2].equalsIgnoreCase("auction");
        menus.quickSell(player, cents, bid ? Listing.Type.AUCTION : Listing.Type.BIN);
    }

    private boolean admin(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        switch (action) {
            case "reload" -> {
                try {
                    settings = loadSettings();
                    sender.sendMessage(Component.text("SMP-Auction reloaded.", NamedTextColor.GREEN));
                } catch (Settings.Invalid e) {
                    sender.sendMessage(Component.text("config.yml is invalid, nothing changed:", NamedTextColor.RED));
                    e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem, NamedTextColor.RED)));
                } catch (IOException | InvalidConfigurationException e) {
                    sender.sendMessage(Component.text("config.yml cannot be read: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "info" -> {
                long owed = service.store().owed().values().stream().mapToLong(Long::longValue).sum();
                sender.sendMessage(Component.text(service.store().listings().size() + " live listing(s), " + service.store().history().size()
                        + " finished sale(s) on record, " + Amounts.money(owed) + " waiting to be paid out.", NamedTextColor.WHITE));
            }
            default -> {
                Listing l = null;
                if (args.length == 2) {
                    try {
                        l = service.store().listing(Long.parseLong(args[1]));
                    } catch (NumberFormatException ignored) {
                        // reported below
                    }
                }
                if (l == null) {
                    sender.sendMessage(Component.text("Usage: /ah cancel <listing number>", NamedTextColor.RED));
                    return true;
                }
                AuctionService.Result r = service.cancel(l.seller(), true, l);
                boolean ok = r.status() == AuctionService.Status.OK;
                sender.sendMessage(Component.text(ok ? "Cancelled; the item is in " + l.sellerName() + "'s collection box."
                        : "Could not cancel: " + r.status(), ok ? NamedTextColor.GREEN : NamedTextColor.RED));
                getLogger().info("ADMIN " + sender.getName() + " cancelled listing " + l.id());
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return sender.hasPermission(ADMIN) ? List.of("sell", "auction", "mine", "collect", "history", "reload", "info", "cancel")
                    : List.of("sell", "auction", "mine", "collect", "history");
        }
        return List.of();
    }
}
