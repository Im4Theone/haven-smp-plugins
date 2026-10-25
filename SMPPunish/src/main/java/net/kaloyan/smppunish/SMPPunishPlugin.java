package net.kaloyan.smppunish;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Custom kicks, bans, mutes and warnings, with screens and messages from the config and a record for every player. */
public final class SMPPunishPlugin extends JavaPlugin implements TabExecutor, Listener {

    private static final int CONFIG_VERSION = 1;
    private static final String CONSOLE = "Console";

    private volatile Settings settings;
    private PunishStore store;
    private Punisher punisher;

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Punish. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store = new PunishStore(getDataFolder().toPath().resolve("punishments.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe every ban, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore the file and restart. SMP-Punish is disabled so no bans are lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        punisher = new Punisher(store, () -> settings, System::currentTimeMillis, getLogger());
        getServer().getPluginManager().registerEvents(this, this);
        for (String name : List.of("ban", "tempban", "ipban", "unban", "kick", "mute", "unmute", "warn", "history", "punishment", "banlist", "alts", "punish")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimerAsynchronously(this, store::saveIfDirty, 20L * 60, 20L * 60);
        getLogger().info("SMP-Punish ready: " + store.inForce(Punishment.Type.BAN, System.currentTimeMillis()).size() + " ban(s) in force.");
    }

    @Override
    public void onDisable() {
        if (store != null) {
            store.saveIfDirty();
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

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        long now = System.currentTimeMillis();
        InetAddress address = event.getAddress();
        Punishment ban = store.banFor(event.getUniqueId(), address == null ? null : address.getHostAddress(), now);
        if (ban != null) {
            Settings s = settings;
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Render.screen(s.banScreen(), Render.values(ban, now, s)));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        var address = p.getAddress();
        store.seen(p.getUniqueId(), p.getName(), address == null ? null : address.getAddress().getHostAddress(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Punishment mute = store.muteFor(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        if (mute != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Render.text(settings.message("muted"), Render.values(mute, System.currentTimeMillis(), settings)));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent event) {
        String first = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        if (colon >= 0) {
            first = first.substring(colon + 1);
        }
        if (settings.mutedCommands().contains(first) && store.muteFor(event.getPlayer().getUniqueId(), System.currentTimeMillis()) != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Render.text(settings.message("muted-command"), TagResolver.empty()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private void send(CommandSender to, String key, Object... placeholders) {
        List<TagResolver> list = new ArrayList<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            list.add(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        to.sendMessage(Render.text(settings.message(key), TagResolver.resolver(list)));
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        send(sender, "no-permission");
        return false;
    }

    private Punisher.Target resolve(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            var address = online.getAddress();
            return new Punisher.Target(online.getUniqueId(), online.getName(), address == null ? null : address.getAddress().getHostAddress());
        }
        PunishStore.Known known = store.byName(name);
        if (known != null) {
            return new Punisher.Target(known.id(), known.name(), known.ip());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.getName() != null) {
            return new Punisher.Target(cached.getUniqueId(), cached.getName(), null);
        }
        send(sender, "unknown-player", "name", name);
        return null;
    }

    private boolean exempt(CommandSender sender, Punisher.Target t) {
        Player online = Bukkit.getPlayer(t.id());
        if (online != null && online.hasPermission("smppunish.exempt") && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
            send(sender, "exempt");
            return true;
        }
        return false;
    }

    /** What was typed after the player's name. */
    private record Spec(long duration, String reason, boolean silent) {
    }

    private Spec spec(CommandSender sender, String command, String target, String[] args, int from, boolean durationRequired, long defaultDuration) {
        List<String> tokens = new ArrayList<>(List.of(args).subList(Math.min(from, args.length), args.length));
        boolean silent = !tokens.isEmpty() && tokens.get(tokens.size() - 1).equalsIgnoreCase("-s");
        if (silent) {
            tokens.remove(tokens.size() - 1);
        }
        long duration = defaultDuration;
        String reason = String.join(" ", tokens);
        if (!tokens.isEmpty()) {
            long parsed = Durations.parse(tokens.get(0));
            String rest = String.join(" ", tokens.subList(1, tokens.size()));
            Settings.Preset preset = settings.presets().get(tokens.get(0).toLowerCase(Locale.ROOT));
            if (parsed != Durations.INVALID) {
                duration = parsed;
                reason = rest;
            } else if (preset != null) {
                duration = preset.durationMillis();
                reason = rest.isBlank() ? preset.reason() : preset.reason() + " - " + rest;
            } else if (durationRequired) {
                send(sender, tokens.get(0).matches("\\d.*") ? "bad-duration" : "need-duration", "command", command, "target", target);
                return null;
            }
        } else if (durationRequired) {
            send(sender, "need-duration", "command", command, "target", target);
            return null;
        }
        boolean limited = command.equals("ban") || command.equals("tempban") || command.equals("ipban") || command.equals("mute");
        if (limited && !sender.hasPermission("smppunish.long")
                && (duration == Durations.PERMANENT || duration > settings.maxDays() * 86_400_000L)) {
            send(sender, "too-long", "max", settings.maxDays());
            return null;
        }
        return new Spec(duration, reason, silent);
    }

    private void announce(String key, Punishment p, boolean silent, Object... extra) {
        long now = System.currentTimeMillis();
        Component line = Render.text(settings.message(key), Render.values(p, now, settings, extra));
        if (settings.publicBroadcast() && !silent) {
            Bukkit.broadcast(line);
        } else {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("smppunish.notify")) {
                    staff.sendMessage(line);
                }
            }
            Bukkit.getConsoleSender().sendMessage(line);
        }
        getLogger().info(key.toUpperCase(Locale.ROOT) + " #" + p.id() + " " + p.targetName() + " by " + p.staff() + ": " + p.reason());
    }

    private void kickOnline(UUID id, List<String> screen, Punishment p) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            online.kick(Render.screen(screen, Render.values(p, System.currentTimeMillis(), settings)));
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName();
        String staff = sender instanceof Player p ? p.getName() : CONSOLE;
        switch (name) {
            case "ban", "tempban", "ipban" -> ban(sender, staff, name, args);
            case "unban" -> unban(sender, staff, args);
            case "kick" -> kick(sender, staff, args);
            case "mute" -> mute(sender, staff, args);
            case "unmute" -> unmute(sender, staff, args);
            case "warn" -> warn(sender, staff, args);
            case "history" -> history(sender, args);
            case "punishment" -> punishment(sender, staff, args);
            case "banlist" -> banlist(sender, args);
            case "alts" -> alts(sender, args);
            default -> reload(sender);
        }
        return true;
    }

    private void usage(CommandSender sender, String usage) {
        send(sender, "usage", "usage", usage);
    }

    private void ban(CommandSender sender, String staff, String name, String[] args) {
        if (!allowed(sender, "smppunish.ban")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/" + name + " <player> " + (name.equals("tempban") ? "<time>" : "[time|preset]") + " [reason] [-s]");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null || exempt(sender, t)) {
            return;
        }
        Spec spec = spec(sender, name, t.name(), args, 1, name.equals("tempban"), Durations.PERMANENT);
        if (spec == null) {
            return;
        }
        Punisher.Result r = punisher.ban(t, staff, spec.reason(), spec.duration(), name.equals("ipban"));
        switch (r.status()) {
            case OK -> {
                kickOnline(t.id(), settings.banScreen(), r.entry());
                announce("ban", r.entry(), spec.silent());
            }
            case ALREADY -> send(sender, "already-banned", "target", t.name(), "id", r.entry().id());
            default -> send(sender, "saved-failed");
        }
    }

    private void unban(CommandSender sender, String staff, String[] args) {
        if (!allowed(sender, "smppunish.unban")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/unban <player> [reason]");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null) {
            return;
        }
        List<Punishment> lifted = punisher.lift(Punishment.Type.BAN, t.id(), staff, String.join(" ", List.of(args).subList(1, args.length)));
        if (lifted.isEmpty()) {
            send(sender, "not-banned", "target", t.name());
        } else {
            announce("unban", lifted.get(0), false);
        }
    }

    private void kick(CommandSender sender, String staff, String[] args) {
        if (!allowed(sender, "smppunish.kick")) {
            return;
        }
        Player online = args.length < 1 ? null : Bukkit.getPlayerExact(args[0]);
        if (online == null) {
            if (args.length < 1) {
                usage(sender, "/kick <player> [reason] [-s]");
            } else {
                send(sender, "not-online", "name", args[0]);
            }
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null || exempt(sender, t)) {
            return;
        }
        Spec spec = spec(sender, "kick", t.name(), args, 1, false, Durations.PERMANENT);
        if (spec == null) {
            return;
        }
        Punisher.Result r = punisher.kick(t, staff, spec.reason());
        if (r.status() != Punisher.Status.OK) {
            send(sender, "saved-failed");
            return;
        }
        kickOnline(t.id(), settings.kickScreen(), r.entry());
        announce("kick", r.entry(), spec.silent());
    }

    private void mute(CommandSender sender, String staff, String[] args) {
        if (!allowed(sender, "smppunish.mute")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/mute <player> [time|preset] [reason] [-s]");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null || exempt(sender, t)) {
            return;
        }
        Spec spec = spec(sender, "mute", t.name(), args, 1, false, Durations.PERMANENT);
        if (spec == null) {
            return;
        }
        Punisher.Result r = punisher.mute(t, staff, spec.reason(), spec.duration());
        switch (r.status()) {
            case OK -> {
                Player online = Bukkit.getPlayer(t.id());
                if (online != null) {
                    online.sendMessage(Render.text(settings.message("muted"), Render.values(r.entry(), System.currentTimeMillis(), settings)));
                }
                announce("mute", r.entry(), spec.silent());
            }
            case ALREADY -> send(sender, "already-muted", "target", t.name(), "id", r.entry().id());
            default -> send(sender, "saved-failed");
        }
    }

    private void unmute(CommandSender sender, String staff, String[] args) {
        if (!allowed(sender, "smppunish.mute")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/unmute <player> [reason]");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null) {
            return;
        }
        List<Punishment> lifted = punisher.lift(Punishment.Type.MUTE, t.id(), staff, String.join(" ", List.of(args).subList(1, args.length)));
        if (lifted.isEmpty()) {
            send(sender, "not-muted", "target", t.name());
            return;
        }
        Player online = Bukkit.getPlayer(t.id());
        if (online != null) {
            online.sendMessage(Render.text(settings.message("unmuted-notice"), TagResolver.empty()));
        }
        announce("unmute", lifted.get(0), false);
    }

    private void warn(CommandSender sender, String staff, String[] args) {
        if (!allowed(sender, "smppunish.warn")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "/warn <player> <reason or preset> [-s]");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null || exempt(sender, t)) {
            return;
        }
        Spec spec = spec(sender, "warn", t.name(), args, 1, false, Durations.PERMANENT);
        if (spec == null) {
            return;
        }
        Punisher.Result r = punisher.warn(t, staff, spec.reason());
        if (r.status() != Punisher.Status.OK) {
            send(sender, "saved-failed");
            return;
        }
        Player online = Bukkit.getPlayer(t.id());
        if (online != null) {
            online.sendMessage(Render.text(settings.message("warned"), Render.values(r.entry(), System.currentTimeMillis(), settings, "count", r.count())));
        }
        announce("warn", r.entry(), spec.silent());
        Settings.Escalation next = r.next();
        if (next != null) {
            String why = "Automatic: " + r.count() + " warnings (last: " + spec.reason() + ")";
            switch (next.type()) {
                case BAN -> {
                    Punisher.Result b = punisher.ban(t, "Automatic", why, next.durationMillis(), false);
                    if (b.status() == Punisher.Status.OK) {
                        kickOnline(t.id(), settings.banScreen(), b.entry());
                        announce("ban", b.entry(), false);
                    }
                }
                case MUTE -> {
                    Punisher.Result m = punisher.mute(t, "Automatic", why, next.durationMillis());
                    if (m.status() == Punisher.Status.OK) {
                        announce("mute", m.entry(), false);
                    }
                }
                default -> {
                    Punisher.Result k = punisher.kick(t, "Automatic", why);
                    if (k.status() == Punisher.Status.OK) {
                        kickOnline(t.id(), settings.kickScreen(), k.entry());
                        announce("kick", k.entry(), false);
                    }
                }
            }
        }
    }

    private static String describe(Punishment p, long now) {
        String state = p.type() == Punishment.Type.KICK ? "" : !p.active() ? " [lifted by " + p.removedBy() + "]" : p.inForce(now) ? " [active]" : " [expired]";
        String length = p.type() == Punishment.Type.BAN || p.type() == Punishment.Type.MUTE
                ? " (" + (p.expires() == 0 ? "permanent" : Durations.format(p.expires() - p.created())) + ")" : "";
        return "#" + p.id() + " " + p.type() + length + " " + Render.date(p.created()) + " by " + p.staff() + ": "
                + (p.reason().isBlank() ? "no reason" : p.reason()) + state;
    }

    private void history(CommandSender sender, String[] args) {
        if (!allowed(sender, "smppunish.history")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/history <player> [page]");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null) {
            return;
        }
        List<Punishment> all = store.of(t.id());
        int per = settings.pageSize();
        int pages = Math.max(1, (all.size() + per - 1) / per);
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Math.min(pages, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        sender.sendMessage(Component.text(t.name() + ": " + all.size() + " entr" + (all.size() == 1 ? "y" : "ies") + " (page " + page + "/" + pages + ")",
                net.kyori.adventure.text.format.NamedTextColor.GOLD));
        long now = System.currentTimeMillis();
        for (int i = (page - 1) * per; i < Math.min(all.size(), page * per); i++) {
            sender.sendMessage(Component.text(describe(all.get(i), now), net.kyori.adventure.text.format.NamedTextColor.WHITE));
        }
    }

    private void punishment(CommandSender sender, String staff, String[] args) {
        if (!allowed(sender, "smppunish.history")) {
            return;
        }
        long id = -1;
        if (args.length >= 1) {
            try {
                id = Long.parseLong(args[args.length > 1 && args[0].equalsIgnoreCase("remove") ? 1 : 0].replace("#", ""));
            } catch (NumberFormatException ignored) {
                // usage below
            }
        }
        Punishment p = id < 0 ? null : store.get(id);
        if (p == null) {
            usage(sender, "/punishment <number>  or  /punishment remove <number> [reason]");
            return;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            if (!allowed(sender, "smppunish.unban")) {
                return;
            }
            Punisher.Result r = punisher.liftById(p.id(), staff, String.join(" ", List.of(args).subList(2, args.length)));
            sender.sendMessage(Component.text(r.status() == Punisher.Status.OK ? "Lifted #" + p.id() + "." : "Could not lift #" + p.id() + ": " + r.status(),
                    r.status() == Punisher.Status.OK ? net.kyori.adventure.text.format.NamedTextColor.GREEN : net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(p.targetName() + ": " + describe(p, System.currentTimeMillis())
                + (p.removedAt() > 0 ? " - lifted " + Render.date(p.removedAt()) + (p.removeReason().isBlank() ? "" : " (" + p.removeReason() + ")") : ""),
                net.kyori.adventure.text.format.NamedTextColor.WHITE));
    }

    private void banlist(CommandSender sender, String[] args) {
        if (!allowed(sender, "smppunish.history")) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Punishment> bans = store.inForce(Punishment.Type.BAN, now);
        int per = settings.pageSize();
        int pages = Math.max(1, (bans.size() + per - 1) / per);
        int page = 1;
        if (args.length > 0) {
            try {
                page = Math.max(1, Math.min(pages, Integer.parseInt(args[0])));
            } catch (NumberFormatException ignored) {
                // first page
            }
        }
        sender.sendMessage(Component.text("Bans in force: " + bans.size() + " (page " + page + "/" + pages + ")", net.kyori.adventure.text.format.NamedTextColor.GOLD));
        for (int i = (page - 1) * per; i < Math.min(bans.size(), page * per); i++) {
            Punishment p = bans.get(i);
            sender.sendMessage(Component.text("#" + p.id() + " " + p.targetName() + " - " + (p.expires() == 0 ? "permanent" : Durations.format(p.expires() - now) + " left")
                    + " - " + p.reason(), net.kyori.adventure.text.format.NamedTextColor.WHITE));
        }
    }

    private void alts(CommandSender sender, String[] args) {
        if (!allowed(sender, "smppunish.history")) {
            return;
        }
        if (args.length < 1) {
            usage(sender, "/alts <player>");
            return;
        }
        Punisher.Target t = resolve(sender, args[0]);
        if (t == null) {
            return;
        }
        List<PunishStore.Known> same = store.sameAddress(t.id());
        if (same.isEmpty()) {
            sender.sendMessage(Component.text("No other player used the same address as " + t.name() + ".", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return;
        }
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (PunishStore.Known k : same) {
            names.add(k.name() + (store.banFor(k.id(), null, now) != null ? " (banned)" : ""));
        }
        sender.sendMessage(Component.text("Same address as " + t.name() + ": " + String.join(", ", names), net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    private void reload(CommandSender sender) {
        if (!allowed(sender, "smppunish.admin")) {
            return;
        }
        try {
            settings = loadSettings();
            sender.sendMessage(Component.text("SMP-Punish reloaded.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        } catch (Settings.Invalid e) {
            sender.sendMessage(Component.text("config.yml is invalid, nothing changed:", net.kyori.adventure.text.format.NamedTextColor.RED));
            e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem, net.kyori.adventure.text.format.NamedTextColor.RED)));
        } catch (IOException | InvalidConfigurationException e) {
            sender.sendMessage(Component.text("config.yml cannot be read: " + e.getMessage(), net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName();
        if (args.length == 1 && !name.equals("banlist") && !name.equals("punish") && !name.equals("punishment")) {
            return null;
        }
        if (args.length == 2 && (name.equals("ban") || name.equals("tempban") || name.equals("mute") || name.equals("ipban") || name.equals("warn"))) {
            List<String> options = new ArrayList<>(settings.presets().keySet());
            options.addAll(List.of("30m", "1h", "1d", "7d", "30d", "perm"));
            return options;
        }
        return List.of();
    }
}
