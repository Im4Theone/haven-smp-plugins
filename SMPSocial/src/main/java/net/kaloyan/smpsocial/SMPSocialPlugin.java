package net.kaloyan.smpsocial;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Private messages, /reply, /ignore and /afk (away players get no shards and no playing time). */
public final class SMPSocialPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 1;

    private volatile Map<String, String> messages = new LinkedHashMap<>();
    private volatile long afkMillis;
    private Ignores ignores;
    private final AfkTracker afk = new AfkTracker();
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();
    private final MiniMessage mini = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        try {
            loadSettings();
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Social: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ignores = new Ignores(getDataFolder().toPath().resolve("ignores.yml"));
        try {
            ignores.load();
        } catch (IOException e) {
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore ignores.yml and restart. SMP-Social is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("msg", "reply", "ignore", "afk", "trash")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::checkAfk, 100L, 100L);
        getLogger().info("SMP-Social ready.");
    }

    /** Loads config.yml, replacing one from an older version (the old file is kept) and filling gaps from the built-in one. */
    private void loadSettings() throws IOException, InvalidConfigurationException {
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
        int minutes = yaml.getInt("afk.auto-minutes", 5);
        if (minutes < 0 || minutes > 1440) {
            throw new IllegalArgumentException("'afk.auto-minutes' must be 0..1440");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String text = section.getString(key, "");
                mini.deserialize(text);
                texts.put(key, text);
            }
        }
        messages = texts;
        afkMillis = minutes * 60_000L;
    }

    // ---------------------------------------------------------------- for other plugins (through reflection)

    /**
     * @param id a player
     * @return whether they are away (SMP-Shop and SMP-Stats skip them)
     */
    public boolean isAfk(UUID id) {
        return afk.isAway(id);
    }

    // ---------------------------------------------------------------- text

    private Component render(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(messages.getOrDefault(key, key), values.build());
    }

    private void say(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(render(key, placeholders));
    }

    // ---------------------------------------------------------------- away

    private void checkAfk() {
        Set<UUID> online = new HashSet<>();
        Set<UUID> exempt = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getUniqueId());
            if (p.hasPermission("smpsocial.afk.exempt")) {
                exempt.add(p.getUniqueId());
            }
        }
        for (UUID id : afk.check(online, exempt, System.currentTimeMillis(), afkMillis)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                say(p, "afk-you-on");
            }
        }
    }

    private void active(Player player) {
        if (afk.activity(player.getUniqueId(), System.currentTimeMillis())) {
            say(player, "afk-you-off");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLook(PlayerMoveEvent event) {
        if (event.getTo() != null && (event.getFrom().getYaw() != event.getTo().getYaw() || event.getFrom().getPitch() != event.getTo().getPitch())) {
            active(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        active(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            active(p);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            active(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandUse(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().toLowerCase(Locale.ROOT).startsWith("/afk")) {
            active(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        afk.forget(event.getPlayer().getUniqueId());
        lastPartner.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- chat: hide what an ignored player says

    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> active(sender));
        event.viewers().removeIf(v -> v instanceof Player p && !p.equals(sender) && ignores.ignores(p.getUniqueId(), sender.getUniqueId()));
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName()) {
            case "msg" -> message(sender, args, false);
            case "reply" -> message(sender, args, true);
            case "ignore" -> ignore(sender, args);
            case "trash" -> trash(sender);
            default -> toggleAfk(sender);
        }
        return true;
    }

    private void message(CommandSender sender, String[] args, boolean reply) {
        if (!(sender instanceof Player from)) {
            sender.sendMessage("Only players can send private messages.");
            return;
        }
        Player to;
        String text;
        if (reply) {
            if (args.length == 0) {
                say(from, "usage-reply");
                return;
            }
            UUID partner = lastPartner.get(from.getUniqueId());
            to = partner == null ? null : Bukkit.getPlayer(partner);
            if (partner == null) {
                say(from, "nobody-to-reply");
                return;
            }
            text = String.join(" ", args);
        } else {
            if (args.length < 2) {
                say(from, "usage-msg");
                return;
            }
            to = Bukkit.getPlayerExact(args[0]);
            text = String.join(" ", List.of(args).subList(1, args.length));
        }
        if (to == null || !from.canSee(to)) {
            say(from, "no-player", "name", reply ? "that player" : args[0]);
            return;
        }
        if (to.equals(from)) {
            say(from, "self");
            return;
        }
        if (ignores.ignores(to.getUniqueId(), from.getUniqueId())) {
            say(from, "blocked");
            return;
        }
        from.sendMessage(render("to", "player", to.getName(), "message", text));
        to.sendMessage(render("from", "player", from.getName(), "message", text));
        to.playSound(to.getLocation(), "entity.experience_orb.pickup", 0.4f, 1.6f);
        lastPartner.put(from.getUniqueId(), to.getUniqueId());
        lastPartner.put(to.getUniqueId(), from.getUniqueId());
    }

    private void ignore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can ignore.");
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<String> names = new ArrayList<>();
            for (UUID id : ignores.of(player.getUniqueId())) {
                OfflinePlayer o = Bukkit.getOfflinePlayer(id);
                names.add(o.getName() == null ? id.toString() : o.getName());
            }
            if (names.isEmpty()) {
                say(player, "ignore-none");
            } else {
                say(player, "ignore-list", "players", String.join(", ", names));
            }
            return;
        }
        Player online = Bukkit.getPlayerExact(args[0]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null || target.getName() == null) {
            say(player, "ignore-unknown", "name", args[0]);
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            say(player, "ignore-self");
            return;
        }
        if (online != null && online.hasPermission("smpsocial.unignorable") && !ignores.ignores(player.getUniqueId(), online.getUniqueId())) {
            say(player, "ignore-staff");
            return;
        }
        try {
            boolean now = ignores.toggle(player.getUniqueId(), target.getUniqueId());
            say(player, now ? "ignore-on" : "ignore-off", "player", target.getName());
        } catch (IOException e) {
            getLogger().severe("Could not save ignores.yml: " + e.getMessage());
            player.sendMessage(Component.text("That could not be saved."));
        }
    }

    /** The bin: a plain chest nobody else can open; what is inside when it closes is gone. */
    private static final class Bin implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inventory;

        @Override
        public org.bukkit.inventory.Inventory getInventory() {
            return inventory;
        }
    }

    private void trash(CommandSender sender) {
        if (sender instanceof Player player) {
            Bin bin = new Bin();
            bin.inventory = Bukkit.createInventory(bin, 36,
                    Component.text("ᴛʀᴀꜱʜ · items are deleted when you close", net.kyori.adventure.text.format.NamedTextColor.RED));
            player.openInventory(bin.inventory);
        }
    }

    private void toggleAfk(CommandSender sender) {
        if (sender instanceof Player player) {
            say(player, afk.toggle(player.getUniqueId(), System.currentTimeMillis()) ? "afk-you-on" : "afk-you-off");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName();
        boolean names = args.length == 1 && (name.equals("msg") || name.equals("ignore"));
        if (!names) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player viewer) || viewer.canSee(p)) {
                out.add(p.getName());
            }
        }
        return out;
    }

    /** @return the plain map of who ignores whom, for tests */
    Map<UUID, List<UUID>> snapshot() {
        return new HashMap<>(ignores.snapshot());
    }
}
