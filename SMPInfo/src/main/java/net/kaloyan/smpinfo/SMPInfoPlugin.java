package net.kaloyan.smpinfo;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Join and quit messages, the server list text, an announcer and info commands such as /discord and /rules. */
public final class SMPInfoPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 2;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();

    private volatile Settings settings;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Set<String> registered = new HashSet<>();
    private BukkitTask announcer;
    private int next;

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Info. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(RankMenu.LISTENER, this);
        getCommand("smpinfo").setExecutor(this);
        getCommand("ranks").setExecutor(this);
        registerInfoCommands();
        startAnnouncer();
        getLogger().info("SMP-Info ready: " + registered.size() + " info command(s), " + settings.announcements().size() + " announcement(s).");
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

    // ---------------------------------------------------------------- info commands

    private void registerInfoCommands() {
        for (var entry : settings.commands().entrySet()) {
            String name = entry.getKey();
            if (!registered.add(name)) {
                continue;
            }
            Command command = new Command(name, entry.getValue().description(), "/" + name, List.of()) {
                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    Settings.Info info = settings.commands().get(name);
                    if (info == null) {
                        sender.sendMessage(Component.text("That command was removed from the config.", NamedTextColor.RED));
                        return true;
                    }
                    TagResolver values = TagResolver.resolver(Placeholder.unparsed("player", sender.getName()),
                            Placeholder.unparsed("server", settings.serverName()));
                    info.lines().forEach(line -> sender.sendMessage(mini.deserialize(line, values)));
                    return true;
                }

                @Override
                public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    return List.of();
                }
            };
            getServer().getCommandMap().register("smpinfo", command);
        }
    }

    // ---------------------------------------------------------------- announcer

    private void startAnnouncer() {
        if (announcer != null) {
            announcer.cancel();
            announcer = null;
        }
        if (!settings.announcerOn() || settings.announcements().isEmpty()) {
            return;
        }
        announcer = getServer().getScheduler().runTaskTimer(this, () -> {
            List<String> list = settings.announcements();
            if (list.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) {
                return;
            }
            next = Announcements.next(next, list.size());
            Component line = mini.deserialize(settings.announcerPrefix() + list.get(next));
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(line));
        }, settings.announcerTicks(), settings.announcerTicks());
    }

    // ---------------------------------------------------------------- messages

    private Component prefix(Player player) {
        String prefix = null;
        try {
            RegisteredServiceProvider<Chat> provider = Bukkit.getServicesManager().getRegistration(Chat.class);
            prefix = provider == null ? null : provider.getProvider().getPlayerPrefix(player);
        } catch (LinkageError e) {
            // Vault is not installed: no prefix.
        }
        return prefix == null || prefix.isBlank() ? Component.empty() : LEGACY.deserialize(prefix.trim()).append(Component.space());
    }

    private Component render(String template, Player player, int count) {
        return mini.deserialize(template, TagResolver.resolver(Placeholder.unparsed("player", player.getName()),
                Placeholder.component("prefix", prefix(player)), Placeholder.unparsed("count", Integer.toString(count)),
                Placeholder.unparsed("server", settings.serverName())));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Settings s = settings;
        Player player = event.getPlayer();
        event.joinMessage(s.join().isBlank() ? null : render(s.join(), player, 0));
        if (!player.hasPlayedBefore() && !s.firstJoin().isBlank()) {
            int count = Bukkit.getOfflinePlayers().length;
            Component welcome = render(s.firstJoin(), player, count);
            Bukkit.getScheduler().runTask(this, () -> Bukkit.broadcast(welcome));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Settings s = settings;
        event.quitMessage(s.quit().isBlank() ? null : render(s.quit(), event.getPlayer(), 0));
    }

    @EventHandler
    public void onPing(PaperServerListPingEvent event) {
        List<String> motd = settings.motd();
        if (motd.isEmpty()) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        motd.forEach(line -> lines.add(mini.deserialize(line, Placeholder.unparsed("server", settings.serverName()))));
        event.motd(lines.size() == 1 ? lines.get(0) : lines.get(0).append(Component.newline()).append(lines.get(1)));
    }

    // ---------------------------------------------------------------- /smpinfo

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("ranks")) {
            if (sender instanceof Player player) {
                RankMenu.open(player, settings.ranks(), settings.serverName());
            } else {
                sender.sendMessage("Only players can open the ranks menu.");
            }
            return true;
        }
        if (!sender.hasPermission("smpinfo.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            try {
                settings = loadSettings();
                registerInfoCommands();
                startAnnouncer();
                sender.sendMessage(Component.text("SMP-Info reloaded (new command names need a restart).", NamedTextColor.GREEN));
            } catch (Settings.Invalid e) {
                sender.sendMessage(Component.text("config.yml is invalid, nothing changed:", NamedTextColor.RED));
                e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem, NamedTextColor.RED)));
            } catch (IOException | InvalidConfigurationException e) {
                sender.sendMessage(Component.text("config.yml cannot be read: " + e.getMessage(), NamedTextColor.RED));
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("announce")) {
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(mini.deserialize(settings.announcerPrefix()
                    + (settings.announcements().isEmpty() ? "" : settings.announcements().get(Announcements.next(next, settings.announcements().size()))))));
        } else {
            sender.sendMessage(Component.text("Usage: /smpinfo <reload | announce>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return command.getName().equals("smpinfo") && args.length == 1 && sender.hasPermission("smpinfo.admin") ? List.of("reload", "announce") : List.of();
    }
}
