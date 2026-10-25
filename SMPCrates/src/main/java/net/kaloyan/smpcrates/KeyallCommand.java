package net.kaloyan.smpcrates;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /keyall <crate> [amount]}: gives every player who is online a crate of the chosen type. Locked to the people the server
 * owner names: the permission {@code smpcrates.keyall} is held by nobody by default (not even operators), and if
 * {@code keyall.allowed-players} in the config is not empty, only those players (and the console) may use it at all.
 */
public final class KeyallCommand implements TabExecutor {

    /** The permission that unlocks the command; nobody has it unless it is given to them. */
    public static final String PERMISSION = "smpcrates.keyall";

    private final CrateService service;
    private final Messages messages;
    private final Supplier<CratesSettings> settings;

    /**
     * @param service  the crate rules
     * @param messages the texts
     * @param settings the current settings
     */
    public KeyallCommand(CrateService service, Messages messages, Supplier<CratesSettings> settings) {
        this.service = service;
        this.messages = messages;
        this.settings = settings;
    }

    /**
     * Who may use the command.
     *
     * @param console     whether the sender is the server console
     * @param hasPermission whether the sender has {@value #PERMISSION}
     * @param name        the sender's name
     * @param allowed     the configured allow-list (empty = no extra restriction)
     * @return {@code true} if the sender may use it
     */
    static boolean mayUse(boolean console, boolean hasPermission, String name, Collection<String> allowed) {
        if (console) {
            return true;
        }
        if (!hasPermission) {
            return false;
        }
        return allowed.isEmpty() || allowed.stream().anyMatch(a -> a.equalsIgnoreCase(name));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!mayUse(sender instanceof ConsoleCommandSender, sender.hasPermission(PERMISSION), sender.getName(),
                settings.get().keyallAllowed())) {
            messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 1) {
            messages.send(sender, "keyall-usage", "crates", String.join(", ", settings.get().crates().keySet()));
            return true;
        }
        Optional<CrateType> type = service.type(args[0].toLowerCase(Locale.ROOT));
        if (type.isEmpty()) {
            messages.send(sender, "unknown-crate", "crates", String.join(", ", settings.get().crates().keySet()));
            return true;
        }
        int amount;
        try {
            amount = args.length >= 2 ? Integer.parseInt(args[1]) : 1;
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > 100) {
            messages.send(sender, "keyall-bad-amount");
            return true;
        }
        List<UUID> everyone = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(p -> everyone.add(p.getUniqueId()));
        if (everyone.isEmpty()) {
            messages.send(sender, "keyall-nobody");
            return true;
        }
        try {
            service.giveAll(everyone, type.get().id(), amount);
        } catch (IOException e) {
            messages.send(sender, "save-failed");
            return true;
        }
        Component crate = messages.parse(type.get().display());
        Bukkit.broadcast(messages.render("keyall-manual", "player", sender.getName(), "count", Integer.toString(amount), "crate", crate));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
        messages.send(sender, "keyall-done", "players", Integer.toString(everyone.size()), "count", Integer.toString(amount), "crate", crate);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!mayUse(sender instanceof ConsoleCommandSender, sender.hasPermission(PERMISSION), sender.getName(), settings.get().keyallAllowed())
                || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return settings.get().crates().keySet().stream().filter(c -> c.startsWith(prefix)).toList();
    }
}
