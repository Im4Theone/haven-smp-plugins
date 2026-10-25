package net.kaloyan.smpbounty;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Put money on a player's head: the killer collects the whole bounty. The money is held until then. */
public final class SMPBountyPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int CONFIG_VERSION = 1;

    private BountyStore store;
    private Bank bank;
    private long minCents;
    private BigDecimal taxPercent;
    private Map<String, String> messages = new LinkedHashMap<>();
    private final MiniMessage mini = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        try {
            loadSettings();
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml is invalid; disabling SMP-Bounty: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml cannot be read: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        store = new BountyStore(getDataFolder().toPath().resolve("bounties.yml"));
        try {
            store.load();
        } catch (IOException e) {
            // Starting empty would let the next save wipe the held money, so refuse to run instead.
            getLogger().severe(e.getMessage());
            getLogger().severe("Fix or restore bounties.yml and restart. SMP-Bounty is disabled so no bounty money is lost.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bank = Bank.vault();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("bounty").setExecutor(this);
        getCommand("bounty").setTabCompleter(this);
        getServer().getScheduler().runTaskTimer(this, this::payAll, 20L * 30, 20L * 60);
        getLogger().info("SMP-Bounty ready: " + store.ranked().size() + " bounty(ies).");
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
        BigDecimal min = new BigDecimal(String.valueOf(yaml.get("min-amount", 1000)));
        BigDecimal tax = new BigDecimal(String.valueOf(yaml.get("tax-percent", 10)));
        if (min.signum() <= 0 || min.stripTrailingZeros().scale() > 2 || tax.signum() < 0 || tax.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("'min-amount' must be above 0 (2 decimals at most) and 'tax-percent' 0..100");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String text = section.getString(key, "");
                mini.deserialize(text);     // throws if the markup is broken
                texts.put(key, text);
            }
        }
        minCents = min.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        taxPercent = tax;
        messages = texts;
    }

    // ---------------------------------------------------------------- helpers

    private void say(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(render(key, placeholders));
    }

    private Component render(String key, Object... placeholders) {
        TagResolver.Builder values = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            values.resolver(Placeholder.unparsed(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1])));
        }
        return mini.deserialize(messages.getOrDefault(key, key), values.build());
    }

    private long tax(long cents) {
        return BigDecimal.valueOf(cents).multiply(taxPercent).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private void payout(UUID id) {
        long cents;
        try {
            cents = store.takeOwed(id);
        } catch (IOException e) {
            getLogger().severe("Could not save bounties.yml before paying: " + e.getMessage());
            return;
        }
        if (cents <= 0) {
            return;
        }
        switch (bank.deposit(id, cents)) {
            case PAID -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.sendMessage(render("claimed-you", "amount", Amounts.money(cents)));
                }
            }
            case UNCERTAIN -> getLogger().severe("Paying " + cents + " cents to " + id + " (bounty) was not confirmed; it is not retried. Check their balance.");
            default -> store.owe(id, cents);
        }
    }

    private void payAll() {
        for (UUID id : store.owedPlayers()) {
            payout(id);
        }
    }

    // ---------------------------------------------------------------- the kill

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        BountyStore.Bounty bounty = store.get(victim.getUniqueId());
        if (killer == null || killer.equals(victim) || bounty == null) {
            return;
        }
        if (bounty.contributed(killer.getUniqueId())) {
            say(killer, "own-claim");
            return;
        }
        long total;
        try {
            total = store.collect(victim.getUniqueId(), killer.getUniqueId());
        } catch (IOException e) {
            getLogger().severe("Could not save bounties.yml: " + e.getMessage());
            return;
        }
        Bukkit.broadcast(render("claimed", "killer", killer.getName(), "target", victim.getName(), "amount", Amounts.money(total)));
        payout(killer.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) {
                return;
            }
            payout(p.getUniqueId());
            BountyStore.Bounty b = store.get(p.getUniqueId());
            if (b != null) {
                say(p, "on-you", "total", Amounts.money(b.total()));
            }
        }, 60L);
    }

    // ---------------------------------------------------------------- /bounty

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> list(sender);
            case "check" -> check(sender, args);
            case "set", "add", "place" -> set(sender, args);
            default -> say(sender, "usage");
        }
        return true;
    }

    private void list(CommandSender sender) {
        List<BountyStore.Bounty> ranked = store.ranked();
        if (ranked.isEmpty()) {
            say(sender, "none");
            return;
        }
        say(sender, "header");
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            BountyStore.Bounty b = ranked.get(i);
            say(sender, "line", "rank", i + 1, "target", b.targetName(), "total", Amounts.money(b.total()));
        }
    }

    private OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null && cached.getName() != null ? cached : null;
    }

    private void check(CommandSender sender, String[] args) {
        OfflinePlayer target = args.length == 2 ? find(args[1]) : null;
        if (target == null) {
            say(sender, args.length == 2 ? "unknown-player" : "usage", "name", args.length == 2 ? args[1] : "");
            return;
        }
        BountyStore.Bounty b = store.get(target.getUniqueId());
        if (b == null) {
            say(sender, "check-none", "target", target.getName());
        } else {
            say(sender, "check", "target", target.getName(), "total", Amounts.money(b.total()));
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can set a bounty.");
            return;
        }
        if (args.length != 3) {
            say(sender, "usage");
            return;
        }
        OfflinePlayer target = find(args[1]);
        if (target == null) {
            say(sender, "unknown-player", "name", args[1]);
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            say(sender, "own-bounty");
            return;
        }
        long cents = Amounts.cents(args[2]);
        if (cents < 1) {
            say(sender, "bad-amount");
            return;
        }
        if (cents < minCents) {
            say(sender, "too-low", "min", Amounts.money(minCents));
            return;
        }
        long tax = tax(cents);
        switch (bank.withdraw(player.getUniqueId(), cents + tax)) {
            case INSUFFICIENT -> {
                say(sender, "not-enough-money", "cost", Amounts.money(cents + tax), "tax", Amounts.money(tax));
                return;
            }
            case FAILED -> {
                say(sender, "failed");
                return;
            }
            case UNCERTAIN -> {
                getLogger().warning("Payment of " + (cents + tax) + " cents by " + player.getName() + " for a bounty was not confirmed; no bounty was set.");
                say(sender, "payment-unsure");
                return;
            }
            default -> { }
        }
        BountyStore.Bounty b;
        try {
            b = store.add(target.getUniqueId(), target.getName(), player.getUniqueId(), cents);
        } catch (IOException e) {
            getLogger().severe("Could not save bounties.yml: " + e.getMessage());
            if (bank.deposit(player.getUniqueId(), cents + tax) != Bank.Result.PAID) {
                getLogger().severe("Could not give " + (cents + tax) + " cents back to " + player.getName() + "; please do it by hand.");
            }
            say(sender, "failed");
            return;
        }
        Bukkit.broadcast(render("set", "setter", player.getName(), "target", target.getName(), "amount", Amounts.money(cents), "total", Amounts.money(b.total())));
        Player online = target.getPlayer();
        if (online != null) {
            say(online, "on-you", "total", Amounts.money(b.total()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("set", "list", "check");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("check"))) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        return List.of();
    }
}
