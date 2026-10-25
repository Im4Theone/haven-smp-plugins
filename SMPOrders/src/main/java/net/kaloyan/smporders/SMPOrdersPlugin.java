package net.kaloyan.smporders;

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

/** A buy-order marketplace: players post what they want to buy, others deliver it and are paid from the buyer's escrow. */
public final class SMPOrdersPlugin extends JavaPlugin implements TabExecutor {

    private static final int CONFIG_VERSION = 2;
    private static final String ADMIN = "smporders.admin";

    private volatile Settings settings;
    private OrderService service;
    private Menus menus;

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Orders. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        OrderStore store = new OrderStore(getDataFolder().toPath().resolve("orders.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe every order and every escrowed payment, so refuse to run instead.
            getLogger().severe("orders.yml cannot be read: " + e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Orders is disabled so no orders or money are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service = new OrderService(store, Bank.vault(), () -> settings, getLogger(), System::currentTimeMillis);
        Prompts prompts = new Prompts(this);
        menus = new Menus(this, service, () -> settings, prompts);
        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getCommand("order").setExecutor(this);
        getCommand("order").setTabCompleter(this);
        getCommand("ordersadmin").setExecutor(this);
        getCommand("ordersadmin").setTabCompleter(this);
        service.expireDue();
        service.payAll();
        getServer().getScheduler().runTaskTimer(this, () -> {
            service.expireDue();
            service.payAll();
        }, 20L * 60, 20L * 60);
        getLogger().info("SMP-Orders ready: " + store.size() + " order(s).");
    }

    @Override
    public void onDisable() {
        if (menus != null) {
            menus.closeAll();
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("ordersadmin")) {
            return admin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use orders.");
            return true;
        }
        menus.openMain(player, String.join(" ", args));
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                try {
                    settings = loadSettings();
                    sender.sendMessage(Component.text("SMP-Orders reloaded.", NamedTextColor.GREEN));
                } catch (Settings.Invalid e) {
                    sender.sendMessage(Component.text("config.yml is invalid, nothing changed:", NamedTextColor.RED));
                    e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem, NamedTextColor.RED)));
                } catch (IOException | InvalidConfigurationException e) {
                    sender.sendMessage(Component.text("config.yml cannot be read: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "info" -> {
                long owed = service.store().owed().values().stream().mapToLong(Long::longValue).sum();
                long open = service.store().view().stream().filter(Order::open).count();
                sender.sendMessage(Component.text(open + " open order(s), " + service.store().size() + " in total, "
                        + Amounts.money(owed) + " waiting to be paid out.", NamedTextColor.WHITE));
            }
            case "cancel" -> {
                Order order = args.length == 2 ? parse(args[1]) : null;
                if (order == null) {
                    sender.sendMessage(Component.text("Usage: /ordersadmin cancel <order number>", NamedTextColor.RED));
                    return true;
                }
                OrderService.Result r = service.cancel(order.owner(), true, order);
                sender.sendMessage(Component.text(r.status() == OrderService.Status.OK
                        ? "Cancelled; " + Amounts.money(r.cents()) + " refunded to " + order.ownerName() + "."
                        : "Could not cancel: " + r.status(), r.status() == OrderService.Status.OK ? NamedTextColor.GREEN : NamedTextColor.RED));
                getLogger().info("ADMIN " + sender.getName() + " cancelled order " + order.id());
            }
            default -> sender.sendMessage(Component.text("Usage: /ordersadmin <reload | info | cancel <order number>>", NamedTextColor.RED));
        }
        return true;
    }

    private Order parse(String text) {
        try {
            return service.store().get(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("ordersadmin") && args.length == 1 && sender.hasPermission(ADMIN)) {
            return List.of("reload", "info", "cancel");
        }
        return List.of();
    }
}
