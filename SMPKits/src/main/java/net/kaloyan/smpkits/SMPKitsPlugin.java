package net.kaloyan.smpkits;

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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** One kit per rank. The excluded groups (Guardian, the admin rank) get none. */
public final class SMPKitsPlugin extends JavaPlugin implements TabExecutor {

    private static final int CONFIG_VERSION = 8;
    private static final String ADMIN = "smpkits.admin";

    private volatile Settings settings;
    private Claims claims;
    private KitService service;
    private Menus menus;
    private final MiniMessage mini = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Kits. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        claims = new Claims(getDataFolder().toPath().resolve("claims.yml"));
        try {
            claims.load();
        } catch (IOException e) {
            // Starting empty would let players claim every kit again, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore claims.yml and restart. SMP-Kits is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        service = new KitService(() -> settings, claims, getLogger());
        menus = new Menus(() -> settings, service);
        getServer().getPluginManager().registerEvents(menus, this);
        for (String name : List.of("kit", "kitadmin")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        for (Kit kit : settings.kits().values()) {
            service.items(kit);     // logs a warning for every enchantment name this server does not know
        }
        getLogger().info("SMP-Kits ready: " + settings.kits().size() + " kit(s); no kits for " + settings.excludedGroups() + ".");
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

    private void say(CommandSender to, String key, Object... placeholders) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            Object v = placeholders[i + 1];
            String name = String.valueOf(placeholders[i]);
            list.add(name.equals("kit") ? Placeholder.parsed(name, String.valueOf(v)) : Placeholder.unparsed(name, String.valueOf(v)));
        }
        to.sendMessage(mini.deserialize(settings.message(key), TagResolver.resolver(list)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("kitadmin")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can claim kits.");
            return true;
        }
        if (args.length == 0) {
            if (service.excluded(player)) {
                say(player, "excluded");
            } else {
                menus.openMain(player);
            }
            return true;
        }
        boolean preview = args[0].equalsIgnoreCase("preview") && args.length > 1;
        Kit kit = settings.kits().get((preview ? args[1] : args[0]).toLowerCase(Locale.ROOT));
        if (kit == null) {
            say(player, "unknown", "name", preview ? args[1] : args[0]);
            return true;
        }
        if (preview) {
            menus.openPreview(player, kit);
            return true;
        }
        KitService.Result r = service.claim(player, kit, System.currentTimeMillis());
        String cooldown = kit.cooldownMillis() == Kit.ONCE ? "" : Durations.format(kit.cooldownMillis());
        switch (r.outcome()) {
            case OK -> say(player, kit.cooldownMillis() == Kit.ONCE ? "claimed-once" : "claimed", "kit", kit.name(), "cooldown", cooldown);
            case COOLDOWN -> say(player, "cooldown", "kit", kit.name(), "remaining", Durations.format(r.remaining()));
            case USED -> say(player, "already-once", "kit", kit.name());
            case NO_SPACE -> say(player, "no-space", "needed", r.needed());
            case EXCLUDED -> say(player, "excluded");
            case SAVE_FAILED -> say(player, "saved-failed");
            default -> say(player, "no-permission");
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> settings.kits().values().forEach(k -> sender.sendMessage(Component.text(k.id() + ": " + k.items().size() + " items, "
                    + (k.cooldownMillis() == Kit.ONCE ? "once" : Durations.format(k.cooldownMillis())) + ", permission " + k.permission(), NamedTextColor.WHITE)));
            case "give", "reset" -> {
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[1]) : null;
                Kit kit = args.length >= 3 ? settings.kits().get(args[2].toLowerCase(Locale.ROOT)) : null;
                if (target == null || kit == null) {
                    say(sender, "usage", "usage", "/kitadmin " + action + " <online player> <kit id>");
                    return;
                }
                if (action.equals("give")) {
                    service.give(target, kit);
                    say(sender, "admin-given", "kit", kit.name(), "player", target.getName());
                } else {
                    try {
                        claims.reset(target.getUniqueId(), kit.id());
                        say(sender, "admin-reset", "kit", kit.name(), "player", target.getName());
                    } catch (IOException e) {
                        say(sender, "saved-failed");
                    }
                }
                getLogger().info("ADMIN " + sender.getName() + " /kitadmin " + action + " " + target.getName() + " " + kit.id());
            }
            case "reload" -> {
                try {
                    settings = loadSettings();
                    sender.sendMessage(Component.text("SMP-Kits reloaded.", NamedTextColor.GREEN));
                } catch (Settings.Invalid e) {
                    sender.sendMessage(Component.text("config.yml is invalid, nothing changed:", NamedTextColor.RED));
                    e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem, NamedTextColor.RED)));
                } catch (IOException | InvalidConfigurationException e) {
                    sender.sendMessage(Component.text("config.yml cannot be read: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            default -> say(sender, "usage", "usage", "/kitadmin <list | give <player> <kit> | reset <player> <kit> | reload>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("kitadmin")) {
            if (args.length == 1) {
                return List.of("list", "give", "reset", "reload");
            }
            return args.length == 2 ? null : args.length == 3 ? new ArrayList<>(settings.kits().keySet()) : List.of();
        }
        if (args.length == 1 && sender instanceof Player p) {
            List<String> mine = new ArrayList<>();
            for (Kit k : settings.kits().values()) {
                if (p.hasPermission(k.permission())) {
                    mine.add(k.id());
                }
            }
            return mine;
        }
        return List.of();
    }
}
