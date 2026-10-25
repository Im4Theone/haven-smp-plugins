package net.kaloyan.smprtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /rtp [overworld|nether|end]}, plus {@code /rtp scout <world>} and {@code /rtp reload} for admins. */
public final class RtpCommand implements TabExecutor {

    private static final String ADMIN = "smprtp.admin";

    private final RtpService service;
    private final Runnable reload;
    private final RtpMenu menu;

    /**
     * @param service the random teleport service
     * @param reload  reloads the configuration
     * @param menu    the world menu that a bare /rtp opens
     */
    public RtpCommand(RtpService service, Runnable reload, RtpMenu menu) {
        this.service = service;
        this.reload = reload;
        this.menu = menu;
    }

    /** Maps what a player typed to a destination key, or {@code null}. */
    static String keyOf(String typed) {
        return switch (typed.toLowerCase(Locale.ROOT)) {
            case "overworld", "world", "over", "normal" -> "overworld";
            case "nether", "hell" -> "nether";
            case "end", "the_end", "theend" -> "end";
            default -> null;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ADMIN)) {
                msg(sender, "You don't have permission to do that.", NamedTextColor.RED);
            } else {
                reload.run();
                msg(sender, "SMP-RTP reloaded.", NamedTextColor.GREEN);
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("player")) {
            sendPlayer(sender, args);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("scout")) {
            scout(sender, args);
            return true;
        }
        if (!(sender instanceof Player player)) {
            msg(sender, "Only players can use /rtp. Admins can use /rtp scout <overworld|nether|end>.", NamedTextColor.RED);
            return true;
        }
        if (!player.hasPermission("smprtp.use")) {
            msg(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return true;
        }
        RtpSettings settings = service.settings();
        if (args.length == 0) {
            menu.open(player);
            return true;
        }
        String key = keyOf(args[0]);
        if (key == null) {
            msg(sender, "Usage: /" + label + " [overworld|nether|end]", NamedTextColor.RED);
            return true;
        }
        RtpWorld target = settings.worlds().get(key);
        if (!player.hasPermission("smprtp.world." + key)) {
            service.send(player, "no-permission-world", "world", key);
            return true;
        }
        service.start(player, target);
        return true;
    }

    private void sendPlayer(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            msg(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        Player target = args.length < 2 ? null : Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(sender, "Usage: /rtp player <online player> [overworld|nether|end]", NamedTextColor.RED);
            return;
        }
        String key = args.length >= 3 ? keyOf(args[2]) : service.settings().defaultWorld();
        if (key == null) {
            msg(sender, "Unknown world '" + args[2] + "'. Use overworld, nether or end.", NamedTextColor.RED);
            return;
        }
        service.start(target, service.settings().worlds().get(key), true);
        msg(sender, "Sending " + target.getName() + " to a random spot in the " + key + " (no cooldown, warm-up or cost).", NamedTextColor.GREEN);
    }

    private void scout(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            msg(sender, "You don't have permission to do that.", NamedTextColor.RED);
            return;
        }
        String key = args.length < 2 ? null : keyOf(args[1]);
        if (key == null) {
            msg(sender, "Usage: /rtp scout <overworld|nether|end>  (finds a spot and prints it; nobody is teleported)", NamedTextColor.RED);
            return;
        }
        RtpWorld target = service.settings().worlds().get(key);
        World world = Bukkit.getWorld(target.world());
        if (world == null) {
            msg(sender, "The world '" + target.world() + "' is not loaded.", NamedTextColor.RED);
            return;
        }
        long started = System.nanoTime();
        msg(sender, "Scouting " + key + " (" + world.getName() + ")...", NamedTextColor.YELLOW);
        service.findSpot(target, world, () -> true, found -> {
            long ms = (System.nanoTime() - started) / 1_000_000;
            Optional<org.bukkit.Location> spot = found;
            if (spot.isEmpty()) {
                msg(sender, "SCOUT " + key + ": no safe spot found in " + ms + " ms.", NamedTextColor.RED);
            } else {
                org.bukkit.Location l = spot.get();
                msg(sender, "SCOUT " + key + ": " + world.getName() + " " + l.getBlockX() + " " + l.getBlockY() + " "
                        + l.getBlockZ() + " (" + ms + " ms)", NamedTextColor.GREEN);
            }
        });
    }

    private static void msg(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String key : RtpSettings.KEYS) {
                if (sender.hasPermission("smprtp.world." + key)) {
                    options.add(key);
                }
            }
            if (sender.hasPermission(ADMIN)) {
                options.addAll(List.of("scout", "player", "reload"));
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("scout") && sender.hasPermission(ADMIN)) {
            options.addAll(RtpSettings.KEYS);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("player") && sender.hasPermission(ADMIN)) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("player") && sender.hasPermission(ADMIN)) {
            options.addAll(RtpSettings.KEYS);
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
