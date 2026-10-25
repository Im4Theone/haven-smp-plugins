package net.kaloyan.smpauth;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Register and login for players without a bought account. Players whose UUID was made by Mojang (verified by FastLogin) walk in. Nobody
 * who is not logged in can do anything (see {@link Guard}). Passwords are only ever stored as salted hashes and are hashed off the main thread.
 */
public final class SMPAuthPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 1;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private volatile Settings settings;
    private AccountStore store;
    /** Wrong passwords per address (strict) and per account name (loose: otherwise anybody could lock a player out of their own account). */
    private Throttle ipThrottle;
    private Throttle nameThrottle;
    private final Sessions sessions = new Sessions();
    private Mojang mojang;
    private volatile Gate gate;
    private volatile LoginHook hook;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> joined = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> wrong = new ConcurrentHashMap<>();
    private final Map<UUID, Long> reminded = new ConcurrentHashMap<>();
    private final MiniMessage mini = MiniMessage.miniMessage();

    @Override
    public void onLoad() {
        // As early as possible, so not even the first command of the first player can be logged with its password.
        try {
            PasswordLogFilter.install();
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("Could not install the password log filter: " + e);
        }
    }

    @Override
    public void onEnable() {
        try {
            settings = loadSettings();
        } catch (Settings.Invalid e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Auth. Fix the following:");
            e.problems().forEach(problem -> getLogger().severe(" - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store = new AccountStore(getDataFolder().toPath().resolve("auth.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let anybody register somebody else's name, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore auth.yml and restart. SMP-Auth is disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        ipThrottle = new Throttle(settings.maxAttempts(), Duration.ofMinutes(10).toMillis(), settings.lockMillis());
        nameThrottle = new Throttle(settings.maxAttempts() * 4, Duration.ofMinutes(10).toMillis(), settings.lockMillis());
        if (getServer().getOnlineMode()) {
            getLogger().warning("online-mode is true: only bought accounts can join, so there is nothing to log in. Set online-mode=false for players without one.");
        } else {
            getLogger().info("online-mode is false: players without a bought account register and log in; bought accounts are verified with Mojang and walk in.");
        }
        setupPremium();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new Guard(this::locked, this::remind), this);
        for (String name : List.of("register", "login", "changepassword", "logout", "unregister", "auth", "premium")) {
            getCommand(name).setExecutor(this);
            getCommand(name).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::timeouts, 40L, 40L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, store::saveIfDirty, 20L * 60, 20L * 60);
        for (Player p : Bukkit.getOnlinePlayers()) {
            enter(p);
        }
        getLogger().info("SMP-Auth ready: " + store.size() + " account(s).");
    }

    @Override
    public void onDisable() {
        if (hook != null) {
            hook.remove();
        }
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

    // ---------------------------------------------------------------- who is who

    /**
     * @param id a player
     * @return whether Mojang made this UUID, which only happens for players verified as owning a bought account
     */
    public static boolean verified(UUID id) {
        return id.version() == 4;
    }

    /**
     * @param id a player
     * @return whether the player is logged in (for other plugins, through reflection)
     */
    public boolean isAuthenticated(UUID id) {
        return authenticated.contains(id);
    }

    private boolean locked(Player player) {
        return !authenticated.contains(player.getUniqueId());
    }

    private static String ip(Player player) {
        var address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    private boolean staff(Player player) {
        return player.isOp() || player.hasPermission("smpauth.admin");
    }

    private Component text(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(settings.message(key), values.build());
    }

    private void say(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(text(key, placeholders));
    }

    private void remind(Player player) {
        long now = System.currentTimeMillis();
        Long last = reminded.get(player.getUniqueId());
        if (last == null || now - last > 2000) {
            reminded.put(player.getUniqueId(), now);
            say(player, "commands-locked");
        }
    }

    // ---------------------------------------------------------------- joining

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String name = event.getName();
        if (!NAME.matcher(name).matches()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, text("invalid-name"));
            return;
        }
        if (getServer().getOnlineMode() || verified(event.getUniqueId())) {
            return;
        }
        LoginHook active = hook;
        LoginHook.Verified proof = active == null || event.getAddress() == null ? null
                : active.take(name, event.getAddress().getHostAddress(), System.currentTimeMillis());
        if (proof != null) {
            // Mojang confirmed this client owns the account: it gets the account's real UUID and skin instead of the name based ones.
            PlayerProfile profile = Bukkit.createProfile(proof.profile().id(), proof.profile().name());
            for (Mojang.Property p : proof.profile().properties()) {
                profile.setProperty(new ProfileProperty(p.name(), p.value(), p.signature()));
            }
            event.setPlayerProfile(profile);
            store.markPremium(proof.profile().name());
            return;
        }
        AccountStore.Account account = store.get(name);
        if (account != null && !account.name().equals(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, text("wrong-case", "name", account.name()));
            return;
        }
        if (settings.protectNames() && store.isPremiumName(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, text("premium-name"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        enter(event.getPlayer());
    }

    /** Decides whether a player who just arrived is let in or has to log in. */
    private void enter(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        if (getServer().getOnlineMode()) {
            authenticated.add(id);
            return;
        }
        if (verified(id)) {
            store.markPremium(name);
            if (settings.premiumAuto()) {
                authenticated.add(id);
                say(player, "premium");
                return;
            }
        }
        AccountStore.Account account = store.get(name);
        String ip = ip(player);
        if (account != null && !staff(player) && sessions.valid(name, ip, now)) {
            authenticated.add(id);
            store.touch(name, ip, now);
            say(player, "session");
            return;
        }
        restrict(player, account != null);
    }

    private void restrict(Player player, boolean registered) {
        authenticated.remove(player.getUniqueId());
        joined.put(player.getUniqueId(), System.currentTimeMillis());
        wrong.remove(player.getUniqueId());
        if (settings.blindness()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        say(player, registered ? "login-prompt" : "register-prompt");
        player.showTitle(Title.title(text(registered ? "title-login" : "title-register"), text(registered ? "subtitle-login" : "subtitle-register"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(60), Duration.ZERO)));
    }

    private void release(Player player, String messageKey) {
        UUID id = player.getUniqueId();
        authenticated.add(id);
        joined.remove(id);
        wrong.remove(id);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.clearTitle();
        say(player, messageKey);
        long now = System.currentTimeMillis();
        String ip = ip(player);
        if (!staff(player)) {
            sessions.start(player.getName(), ip, now, settings.sessionMillis());
        }
        store.touch(player.getName(), ip, now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (authenticated.remove(id) && store.get(player.getName()) != null && !staff(player)) {
            sessions.start(player.getName(), ip(player), System.currentTimeMillis(), settings.sessionMillis());
        }
        joined.remove(id);
        wrong.remove(id);
        busy.remove(id);
        reminded.remove(id);
    }

    private void timeouts() {
        long now = System.currentTimeMillis();
        long limit = settings.timeoutSeconds() * 1000L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long since = joined.get(player.getUniqueId());
            if (since != null && locked(player) && now - since > limit) {
                player.kick(text("timeout"));
            }
        }
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("auth")) {
            admin(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }
        switch (command.getName()) {
            case "register" -> register(player, args);
            case "login" -> login(player, args);
            case "changepassword" -> change(player, args);
            case "unregister" -> unregister(player, args);
            case "premium" -> premium(player);
            default -> logout(player);
        }
        return true;
    }

    private String problem(Passwords.Problem p) {
        return switch (p) {
            case TOO_SHORT -> "too-short";
            case TOO_LONG -> "too-long";
            case SAME_AS_NAME -> "same-as-name";
            case TOO_COMMON -> "too-common";
            case BAD_CHARACTERS -> "bad-characters";
            default -> "";
        };
    }

    private boolean refusePassword(CommandSender to, String password, String name) {
        Passwords.Problem p = Passwords.check(password, name, settings.minLength(), settings.maxLength());
        if (p == Passwords.Problem.NONE) {
            return false;
        }
        say(to, problem(p), "min", settings.minLength(), "max", settings.maxLength());
        return true;
    }

    private void register(Player player, String[] args) {
        if (verified(player.getUniqueId()) && authenticated.contains(player.getUniqueId()) || getServer().getOnlineMode()) {
            say(player, "already-logged-in");
            return;
        }
        if (store.get(player.getName()) != null) {
            say(player, "already-registered");
            return;
        }
        if (args.length != 2) {
            say(player, "usage-register");
            return;
        }
        if (!args[0].equals(args[1])) {
            say(player, "mismatch");
            return;
        }
        if (refusePassword(player, args[0], player.getName())) {
            return;
        }
        String ip = ip(player);
        if (settings.maxPerIp() > 0 && store.countByIp(ip) >= settings.maxPerIp()) {
            say(player, "too-many-accounts");
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            say(player, "busy");
            return;
        }
        String password = args[0];
        String name = player.getName();
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String hash = Passwords.hash(password);
            Bukkit.getScheduler().runTask(this, () -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online == null) {
                    return;
                }
                try {
                    store.register(name, hash, ip, System.currentTimeMillis());
                    release(online, "registered");
                } catch (IOException e) {
                    getLogger().severe("Could not save auth.yml: " + e.getMessage());
                    say(online, "save-failed");
                }
            });
        });
    }

    private void login(Player player, String[] args) {
        UUID id = player.getUniqueId();
        if (authenticated.contains(id)) {
            say(player, "already-logged-in");
            return;
        }
        AccountStore.Account account = store.get(player.getName());
        if (account == null) {
            say(player, "not-registered");
            return;
        }
        if (args.length != 1) {
            say(player, "usage-login");
            return;
        }
        long now = System.currentTimeMillis();
        String ip = ip(player);
        String nameKey = "name:" + player.getName().toLowerCase(Locale.ROOT);
        String ipKey = "ip:" + ip;
        long lockedFor = Math.max(nameThrottle.locked(nameKey, now), ipThrottle.locked(ipKey, now));
        if (lockedFor > 0) {
            say(player, "locked", "minutes", Math.max(1, (lockedFor + 59_999) / 60_000));
            return;
        }
        if (!busy.add(id)) {
            say(player, "busy");
            return;
        }
        String password = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean right = Passwords.verify(password, account.hash());
            Bukkit.getScheduler().runTask(this, () -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online == null) {
                    return;
                }
                if (right) {
                    nameThrottle.clear(nameKey);
                    ipThrottle.clear(ipKey);
                    release(online, "logged-in");
                    return;
                }
                long at = System.currentTimeMillis();
                boolean lockedNow = nameThrottle.fail(nameKey, at) | ipThrottle.fail(ipKey, at);
                int count = wrong.merge(id, 1, Integer::sum);
                if (lockedNow || count >= settings.maxAttempts()) {
                    online.kick(text("too-many-attempts"));
                    getLogger().warning(online.getName() + " was kicked after " + count + " wrong passwords (" + ip + ").");
                } else {
                    say(online, "wrong-password", "left", settings.maxAttempts() - count);
                }
            });
        });
    }

    private void change(Player player, String[] args) {
        UUID id = player.getUniqueId();
        AccountStore.Account account = store.get(player.getName());
        if (!authenticated.contains(id)) {
            say(player, "not-logged-in");
            return;
        }
        if (account == null) {
            say(player, "not-registered");
            return;
        }
        if (args.length != 2) {
            say(player, "usage-change");
            return;
        }
        if (refusePassword(player, args[1], player.getName())) {
            return;
        }
        if (!busy.add(id)) {
            say(player, "busy");
            return;
        }
        String oldPassword = args[0];
        String newPassword = args[1];
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean right = Passwords.verify(oldPassword, account.hash());
            String hash = right ? Passwords.hash(newPassword) : null;
            Bukkit.getScheduler().runTask(this, () -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online == null) {
                    return;
                }
                if (!right) {
                    long at = System.currentTimeMillis();
                    boolean lockedNow = nameThrottle.fail("name:" + online.getName().toLowerCase(Locale.ROOT), at);
                    if (lockedNow) {
                        online.kick(text("too-many-attempts"));
                    } else {
                        say(online, "wrong-password", "left", "?");
                    }
                    return;
                }
                try {
                    store.setHash(online.getName(), hash);
                    sessions.end(online.getName());
                    say(online, "password-changed");
                } catch (IOException e) {
                    getLogger().severe("Could not save auth.yml: " + e.getMessage());
                    say(online, "save-failed");
                }
            });
        });
    }

    private void unregister(Player player, String[] args) {
        UUID id = player.getUniqueId();
        AccountStore.Account account = store.get(player.getName());
        if (!authenticated.contains(id) || account == null) {
            say(player, account == null ? "not-registered" : "not-logged-in");
            return;
        }
        if (args.length != 1) {
            say(player, "usage-unregister");
            return;
        }
        if (!busy.add(id)) {
            say(player, "busy");
            return;
        }
        String password = args[0];
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            boolean right = Passwords.verify(password, account.hash());
            Bukkit.getScheduler().runTask(this, () -> {
                busy.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online == null) {
                    return;
                }
                if (!right) {
                    boolean lockedNow = nameThrottle.fail("name:" + online.getName().toLowerCase(Locale.ROOT), System.currentTimeMillis());
                    if (lockedNow) {
                        online.kick(text("too-many-attempts"));
                    } else {
                        say(online, "wrong-password", "left", "?");
                    }
                    return;
                }
                try {
                    store.remove(online.getName());
                    sessions.end(online.getName());
                    online.kick(text("unregistered"));
                } catch (IOException e) {
                    getLogger().severe("Could not save auth.yml: " + e.getMessage());
                    say(online, "save-failed");
                }
            });
        });
    }

    /** Sets up the check of bought accounts: the Mojang client, the decision which names need it, and (a moment later) the hook on the connections. */
    private void setupPremium() {
        if (getServer().getOnlineMode() || !settings.verify()) {
            return;
        }
        Duration wait = Duration.ofSeconds(settings.verifySeconds());
        mojang = new Mojang(settings.lookupUrl(), settings.sessionUrl(), wait);
        gate = new Gate(true, settings.detect(), store::isPremiumName, name -> store.get(name) != null, mojang::lookup,
                new Throttle(20, 60_000L, 60_000L));
        // The server only starts listening after the plugins of this load order are enabled, so the hook goes in on the first tick.
        getServer().getScheduler().runTask(this, () -> {
            try {
                hook = LoginHook.install(mojang, gate, this::text, getLogger(), settings.verifySeconds());
                getLogger().info("Bought accounts are verified with Mojang (players type /premium once, or are recognised by their name).");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                getLogger().severe("Could not hook into the server's login, so bought accounts cannot be verified and every player has to "
                        + "register and log in. This Minecraft version may need an update of SMP-Auth. Reason: " + e);
            }
        });
    }

    private void premium(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        if (!authenticated.contains(id)) {
            say(player, "not-logged-in");
            return;
        }
        if (verified(id) || store.isPremiumName(name)) {
            say(player, "premium-already");
            return;
        }
        if (hook == null || mojang == null) {
            say(player, "premium-off");
            return;
        }
        if (!busy.add(id)) {
            say(player, "busy");
            return;
        }
        mojang.lookup(name).whenComplete((answer, error) -> Bukkit.getScheduler().runTask(this, () -> {
            busy.remove(id);
            Player online = Bukkit.getPlayer(id);
            if (online == null) {
                return;
            }
            if (error != null || answer == Mojang.Lookup.ERROR) {
                say(online, "premium-error");
            } else if (answer == Mojang.Lookup.MISSING) {
                say(online, "premium-none", "name", name);
            } else {
                store.markPremium(name);
                gate.forget(name);
                Bukkit.getScheduler().runTaskAsynchronously(this, store::saveIfDirty);
                say(online, "premium-set");
            }
        }));
    }

    private void logout(Player player) {
        UUID id = player.getUniqueId();
        if (!authenticated.contains(id) || store.get(player.getName()) == null) {
            say(player, "not-logged-in");
            return;
        }
        sessions.end(player.getName());
        restrict(player, true);
        say(player, "logged-out");
    }

    // ---------------------------------------------------------------- /auth (staff)

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("smpauth.admin")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "unregister" -> {
                    if (args.length != 2) {
                        usage(sender);
                        return;
                    }
                    boolean had = store.remove(args[1]);
                    sessions.end(args[1]);
                    sender.sendMessage(Component.text(had ? "Deleted the account of " + args[1] + "." : args[1] + " has no account.",
                            had ? NamedTextColor.GREEN : NamedTextColor.RED));
                    log(sender, "unregistered " + args[1]);
                }
                case "setpassword" -> {
                    if (args.length != 3) {
                        usage(sender);
                        return;
                    }
                    AccountStore.Account account = store.get(args[1]);
                    if (account == null) {
                        sender.sendMessage(Component.text(args[1] + " has no account.", NamedTextColor.RED));
                        return;
                    }
                    if (refusePassword(sender, args[2], account.name())) {
                        return;
                    }
                    String hash = Passwords.hash(args[2]);
                    store.setHash(account.name(), hash);
                    sessions.end(account.name());
                    sender.sendMessage(Component.text("The password of " + account.name() + " was changed.", NamedTextColor.GREEN));
                    log(sender, "changed the password of " + account.name());
                }
                case "forcelogin" -> {
                    Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : null;
                    if (target == null) {
                        usage(sender);
                        return;
                    }
                    release(target, "logged-in");
                    sender.sendMessage(Component.text(target.getName() + " is logged in.", NamedTextColor.GREEN));
                    log(sender, "forced the login of " + target.getName());
                }
                case "premium", "cracked" -> {
                    if (args.length != 2) {
                        usage(sender);
                        return;
                    }
                    boolean on = action.equals("premium");
                    if (on) {
                        store.markPremium(args[1]);
                    } else if (!store.unmarkPremium(args[1])) {
                        sender.sendMessage(Component.text(args[1] + " was not marked as a bought account.", NamedTextColor.RED));
                        return;
                    }
                    if (gate != null) {
                        gate.forget(args[1]);
                    }
                    store.saveIfDirty();
                    sender.sendMessage(Component.text(args[1] + (on ? " has to prove a bought account from now on." : " is not treated as a bought account any more."),
                            NamedTextColor.GREEN));
                    log(sender, (on ? "marked " : "unmarked ") + args[1] + " as a bought account");
                }
                case "uuid" -> {
                    Player target = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : null;
                    if (target == null) {
                        usage(sender);
                        return;
                    }
                    UUID id = target.getUniqueId();
                    sender.sendMessage(Component.text(target.getName() + ": " + id + " - " + (verified(id)
                            ? "a Mojang UUID (version 4): verified as a bought account, no password needed"
                            : "a name based UUID (version 3): not verified, has to log in"), verified(id) ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                }
                case "info" -> {
                    AccountStore.Account a = args.length == 2 ? store.get(args[1]) : null;
                    if (a == null) {
                        sender.sendMessage(Component.text("No account" + (args.length == 2 ? " for " + args[1] : "") + ".", NamedTextColor.RED));
                        return;
                    }
                    sender.sendMessage(Component.text(a.name() + ": registered " + new java.util.Date(a.registered()) + ", last login "
                            + new java.util.Date(a.lastLogin()) + " from " + a.lastIp(), NamedTextColor.WHITE));
                }
                case "reload" -> {
                    try {
                        settings = loadSettings();
                        sender.sendMessage(Component.text("SMP-Auth reloaded (the attempt limits apply after a restart).", NamedTextColor.GREEN));
                    } catch (Settings.Invalid e) {
                        sender.sendMessage(Component.text("config.yml is invalid, nothing changed:", NamedTextColor.RED));
                        e.problems().forEach(problem -> sender.sendMessage(Component.text(" - " + problem, NamedTextColor.RED)));
                    } catch (IOException | InvalidConfigurationException e) {
                        sender.sendMessage(Component.text("config.yml cannot be read: " + e.getMessage(), NamedTextColor.RED));
                    }
                }
                default -> usage(sender);
            }
        } catch (IOException e) {
            getLogger().severe("Could not save auth.yml: " + e.getMessage());
            sender.sendMessage(Component.text("That could not be saved, nothing changed.", NamedTextColor.RED));
        }
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /auth unregister <name> | setpassword <name> <password> | forcelogin <player> | info <name> | uuid <player> | premium <name> | cracked <name> | reload", NamedTextColor.RED));
    }

    private void log(CommandSender sender, String what) {
        getLogger().info("ADMIN " + sender.getName() + " " + what);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equals("auth") && args.length == 1) {
            return List.of("unregister", "setpassword", "forcelogin", "info", "uuid", "premium", "cracked", "reload");
        }
        return List.of();
    }
}
