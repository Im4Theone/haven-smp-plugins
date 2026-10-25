package net.kaloyan.smpscoreboard;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpeconomy.api.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Sidebar with money, shards, kills, deaths, kill streak, LuckPerms rank, playtime and online/ping, plus chat
 * formatted with the LuckPerms prefix. Money comes from SMP-Economy, shards from DonutShop (if installed).
 */
public final class SMPScoreboard extends JavaPlugin implements Listener {

    private static final String ADMIN = "smpscoreboard.admin";

    private BoardManager boards;
    private BukkitTask task;
    private LuckPermsHook luckPerms;
    private volatile BoardSettings settings;

    @Override
    public void onEnable() {
        for (String dependency : List.of("SMP-Core", "SMP-Economy")) {
            if (!getServer().getPluginManager().isPluginEnabled(dependency)) {
                getLogger().severe(dependency + " is not enabled; disabling SMP-Scoreboard.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }
        SMPAPI core = getServer().getServicesManager().load(SMPAPI.class);
        Optional<EconomyService> economy = core == null ? Optional.empty() : core.getServices().find(EconomyService.class);
        if (economy.isEmpty()) {
            getLogger().severe("SMP-Economy did not publish its EconomyService; disabling SMP-Scoreboard.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        try {
            settings = load();
        } catch (BoardSettings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Scoreboard. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        luckPerms = LuckPermsHook.create().orElse(null);
        getLogger().info(luckPerms == null ? "LuckPerms not found: ranks show as Member and chat is unchanged."
                : "LuckPerms found: ranks and chat prefixes enabled.");
        boards = new BoardManager(this, economy.get(), luckPerms, settings);
        getServer().getPluginManager().registerEvents(this, this);
        schedule();
        for (Player player : getServer().getOnlinePlayers()) {
            if (!boards.hidden(player)) {
                boards.update(player);
            }
        }
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        if (boards != null) {
            boards.removeAll();
        }
    }

    private void schedule() {
        if (task != null) {
            task.cancel();
        }
        long ticks = settings.updateTicks();
        task = getServer().getScheduler().runTaskTimer(this, boards::updateAll, 20L, ticks);
    }

    /** Reads and validates {@code config.yml} without touching the active settings. */
    private BoardSettings load() throws BoardSettings.Invalid {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
        } catch (java.io.IOException | InvalidConfigurationException e) {
            throw new BoardSettings.Invalid(List.of("config.yml cannot be read: " + e.getMessage()));
        }
        // Sections added in newer versions (tab list, welcome title) fall back to the shipped defaults, so an older
        // config.yml keeps working and keeps its own edits.
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in != null) {
                YamlConfiguration defaults = new YamlConfiguration();
                defaults.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                yaml.setDefaults(defaults);
            }
        } catch (java.io.IOException | InvalidConfigurationException e) {
            getLogger().warning("Could not read the default config from the jar: " + e.getMessage());
        }
        return BoardSettings.parse(yaml);
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String join = settings.joinMessage();
        if (!join.isEmpty()) {
            event.joinMessage(MiniMessage.miniMessage().deserialize(join,
                    Placeholder.unparsed("name", event.getPlayer().getName())));
        }
        boards.updateTab(event.getPlayer());
        WelcomeSettings welcome = settings.welcome();
        if (welcome.enabled() && !welcome.title().isEmpty()) {
            MiniMessage mini = MiniMessage.miniMessage();
            TagResolver name = Placeholder.unparsed("name", event.getPlayer().getName());
            event.getPlayer().showTitle(Title.title(mini.deserialize(welcome.title(), name),
                    welcome.subtitle().isEmpty() ? Component.empty() : mini.deserialize(welcome.subtitle(), name),
                    Title.Times.times(Duration.ofMillis(welcome.fadeInMs()), Duration.ofMillis(welcome.stayMs()),
                            Duration.ofMillis(welcome.fadeOutMs()))));
        }
        if (!boards.hidden(event.getPlayer())) {
            boards.update(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String quit = settings.quitMessage();
        if (!quit.isEmpty()) {
            event.quitMessage(MiniMessage.miniMessage().deserialize(quit,
                    Placeholder.unparsed("name", event.getPlayer().getName())));
        }
        boards.remove(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        boards.setStreak(victim, 0);
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            boards.setStreak(killer, boards.streak(killer) + 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncChatEvent event) {
        BoardSettings current = settings;
        if (!current.chatEnabled() || luckPerms == null) {
            return;
        }
        MiniMessage mini = MiniMessage.miniMessage();
        event.renderer(ChatRenderer.viewerUnaware((source, name, message) -> {
            String prefix = luckPerms.prefix(source);
            Component prefixComponent = prefix == null || prefix.isBlank() ? Component.empty()
                    : Prefixes.parse(prefix).append(Component.space());
            return mini.deserialize(current.chatFormat(), Placeholder.component("prefix", prefixComponent),
                    Placeholder.component("name", name), Placeholder.component("message", message));
        }));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ADMIN)) {
                sender.sendMessage(Component.text("You don't have permission to do that."));
                return true;
            }
            try {
                settings = load();
                boards.settings(settings);
                schedule();
                sender.sendMessage(Component.text("SMP-Scoreboard reloaded."));
            } catch (BoardSettings.Invalid e) {
                sender.sendMessage(Component.text("Reload rejected; the previous settings stay active:"));
                e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem)));
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have a scoreboard."));
            return true;
        }
        boolean visible = boards.toggle(player);
        player.sendMessage(Component.text(visible ? "Scoreboard shown." : "Scoreboard hidden."));
        return true;
    }
}
