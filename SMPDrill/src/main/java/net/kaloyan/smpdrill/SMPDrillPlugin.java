package net.kaloyan.smpdrill;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** The Amethyst Drill: a maxed netherite pickaxe that mines a 5x5 tunnel with every swing. */
public final class SMPDrillPlugin extends JavaPlugin implements TabExecutor {

    private volatile DrillSettings settings;
    private DrillItem drill;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = load();
        } catch (DrillSettings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Drill. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        List<String> unknown = DrillItem.unknownEnchantments(settings);
        if (!unknown.isEmpty()) {
            getLogger().severe("config.yml is invalid; disabling SMP-Drill. Fix the following:");
            unknown.forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        drill = new DrillItem(this);
        getServer().getPluginManager().registerEvents(new DrillListener(this, drill, () -> settings), this);
        getCommand("drill").setExecutor(this);
        getCommand("drill").setTabCompleter(this);
        getLogger().info("SMP-Drill ready: " + settings.size() + "x" + settings.size() + " tunnel, depth " + settings.depth() + ".");
    }

    private DrillSettings load() throws DrillSettings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException e) {
            throw new DrillSettings.Invalid(List.of("config.yml cannot be read: " + e.getMessage()));
        }
        return DrillSettings.parse(yaml);
    }

    private Component message(String key, Object... placeholders) {
        var values = net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return MiniMessage.miniMessage().deserialize(settings.messages().getOrDefault(key, key), values.build());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> sender.sendMessage(message("info", "size", settings.size(), "depth", settings.depth()));
            case "reload" -> {
                if (!sender.hasPermission("smpdrill.admin")) {
                    sender.sendMessage(message("no-permission"));
                    return true;
                }
                try {
                    DrillSettings fresh = load();
                    List<String> unknown = DrillItem.unknownEnchantments(fresh);
                    if (!unknown.isEmpty()) {
                        unknown.forEach(p -> getLogger().warning("Reload rejected, the previous settings stay active: " + p));
                        return true;
                    }
                    settings = fresh;
                    sender.sendMessage(message("reloaded"));
                } catch (DrillSettings.Invalid e) {
                    getLogger().warning("Reload rejected, the previous settings stay active:");
                    e.problems().forEach(problem -> getLogger().warning(" - " + problem));
                    sender.sendMessage(message("reload-rejected"));
                }
            }
            case "give" -> give(sender, args);
            default -> sender.sendMessage(message("usage"));
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpdrill.admin")) {
            sender.sendMessage(message("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(message("usage"));
            return;
        }
        OfflinePlayer found = Bukkit.getPlayerExact(args[1]);
        Player target = found == null ? null : found.getPlayer();
        if (target == null) {
            sender.sendMessage(message("not-online", "player", args[1]));
            return;
        }
        int amount;
        try {
            amount = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > 36) {
            sender.sendMessage(message("bad-amount"));
            return;
        }
        for (int i = 0; i < amount; i++) {
            ItemStack item = drill.create(settings);
            for (ItemStack left : target.getInventory().addItem(item).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), left);       // never lose the item
            }
        }
        sender.sendMessage(message("gave", "player", target.getName(), "count", amount));
        target.sendMessage(message("received", "count", amount));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("info");
            if (sender.hasPermission("smpdrill.admin")) {
                options.addAll(List.of("give", "reload"));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("smpdrill.admin")) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
