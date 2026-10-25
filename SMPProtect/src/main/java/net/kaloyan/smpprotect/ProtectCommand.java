package net.kaloyan.smpprotect;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.kaloyan.smpprotect.Selections.Selection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /protect}: defines regions and edits their flags and people. */
public final class ProtectCommand implements TabExecutor {

    private static final String PERMISSION = "smpprotect.admin";
    private static final List<String> SUBCOMMANDS = List.of("wand", "pos1", "pos2", "define", "redefine", "remove", "list",
            "info", "flag", "flags", "priority", "greeting", "farewell", "addowner", "removeowner", "addmember", "removemember", "preset", "reload");
    private static final List<String> NAME_FIRST = List.of("redefine", "remove", "info", "flag", "priority", "greeting", "farewell", "addowner",
            "removeowner", "addmember", "removemember", "preset");
    private static final List<Flag> HUB_PRESET = List.of(Flag.BUILD, Flag.BREAK, Flag.CONTAINER, Flag.ENTITY_DAMAGE,
            Flag.PVP, Flag.DAMAGE, Flag.HUNGER, Flag.MOB_SPAWN, Flag.MOB_GRIEF, Flag.EXPLOSION, Flag.FIRE);

    private final RegionManager manager;
    private final Selections selections;
    private final Runnable reload;

    /**
     * @param manager    the regions
     * @param selections the wand selections
     * @param reload     reloads regions and config
     */
    public ProtectCommand(RegionManager manager, Selections selections, Runnable reload) {
        this.manager = manager;
        this.selections = selections;
        this.reload = reload;
    }

    private static void ok(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GREEN));
    }

    private static void err(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.RED));
    }

    private static void info(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            err(sender, "You don't have permission to do that.");
            return true;
        }
        if (args.length == 0) {
            info(sender, "/" + label + " <" + String.join("|", SUBCOMMANDS) + ">");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "wand" -> wand(sender);
                case "pos1" -> position(sender, 1);
                case "pos2" -> position(sender, 2);
                case "define" -> define(sender, args);
                case "redefine" -> redefine(sender, args);
                case "remove" -> remove(sender, args);
                case "list" -> list(sender, args);
                case "info" -> showInfo(sender, args);
                case "flag" -> flag(sender, args);
                case "flags" -> Arrays.stream(Flag.values()).forEach(f -> info(sender, f.id() + ": " + f.description()
                        + (f.memberExempt() ? " (owners and members are exempt)" : "")));
                case "priority" -> priority(sender, args);
                case "greeting" -> message(sender, args, true);
                case "farewell" -> message(sender, args, false);
                case "addowner" -> person(sender, args, true, true);
                case "removeowner" -> person(sender, args, true, false);
                case "addmember" -> person(sender, args, false, true);
                case "removemember" -> person(sender, args, false, false);
                case "preset" -> preset(sender, args);
                case "reload" -> {
                    reload.run();
                    ok(sender, "Reloaded regions and config.");
                }
                default -> err(sender, "Unknown subcommand '" + sub + "'. Try /" + label + " without arguments.");
            }
        } catch (IOException e) {
            err(sender, "Could not save regions.yml, so nothing was changed: " + e.getMessage());
        }
        return true;
    }

    private void wand(CommandSender sender) {
        if (sender instanceof Player player) {
            player.getInventory().addItem(selections.wand()).values().forEach(left ->
                    player.getWorld().dropItemNaturally(player.getLocation(), left));
            ok(sender, "Left click a block for corner 1, right click for corner 2, then /protect define <name>.");
        } else {
            err(sender, "Only players have a wand.");
        }
    }

    private void position(CommandSender sender, int which) {
        if (sender instanceof Player player) {
            selections.set(player, which, player.getLocation());
        } else {
            err(sender, "Only players can select corners.");
        }
    }

    private static boolean validName(String name) {
        return name.matches("[a-z0-9_-]{1,32}");
    }

    private void define(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) {
            err(sender, "Usage: /protect define <name> [fullheight]   or   /protect define <name> world [worldName]");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!validName(name)) {
            err(sender, "Names use only a-z, 0-9, _ and -, up to 32 characters.");
            return;
        }
        if (manager.get(name).isPresent()) {
            err(sender, "A region named '" + name + "' already exists. Use /protect redefine " + name + " or pick another name.");
            return;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("world")) {
            String worldName = args.length >= 4 ? args[3] : sender instanceof Player p ? p.getWorld().getName() : null;
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                err(sender, "Name the world: /protect define " + name + " world <worldName>");
                return;
            }
            manager.put(Region.wholeWorld(name, world.getName()));
            ok(sender, "Defined '" + name + "' covering the whole world '" + world.getName() + "'. It has no flags yet: try /protect preset " + name + " hub");
            return;
        }
        Optional<Region> region = fromSelection(sender, name, args.length >= 3 && args[2].equalsIgnoreCase("fullheight"));
        if (region.isEmpty()) {
            return;
        }
        manager.put(region.get());
        ok(sender, "Defined '" + name + "' (" + describeBounds(region.get()) + "). It has no flags yet: use /protect flag " + name
                + " <flag> <allow|deny>, or /protect preset " + name + " hub");
    }

    private void redefine(CommandSender sender, String[] args) throws IOException {
        Optional<Region> existing = existing(sender, args, 1);
        if (existing.isEmpty()) {
            return;
        }
        Optional<Region> fresh = fromSelection(sender, existing.get().name(), args.length >= 3 && args[2].equalsIgnoreCase("fullheight"));
        if (fresh.isEmpty()) {
            return;
        }
        if (!fresh.get().world().equals(existing.get().world())) {
            err(sender, "The selection is in another world than '" + existing.get().name() + "'. Remove and define it again instead.");
            return;
        }
        Region r = fresh.get();
        manager.put(existing.get().withBounds(r.minX(), r.minY(), r.minZ(), r.maxX(), r.maxY(), r.maxZ()));
        ok(sender, "Updated the bounds of '" + existing.get().name() + "' to " + describeBounds(r) + ".");
    }

    private Optional<Region> fromSelection(CommandSender sender, String name, boolean fullHeight) {
        if (!(sender instanceof Player player)) {
            err(sender, "Select corners in game first (the console can only define whole-world regions).");
            return Optional.empty();
        }
        Optional<Selection> selection = selections.get(player);
        if (selection.isEmpty()) {
            err(sender, "Select two corners in the same world first (/protect wand).");
            return Optional.empty();
        }
        Selection s = selection.get();
        World world = Bukkit.getWorld(s.first().world());
        if (world == null) {
            err(sender, "The selected world is not loaded.");
            return Optional.empty();
        }
        int y1 = fullHeight ? world.getMinHeight() : s.first().y();
        int y2 = fullHeight ? world.getMaxHeight() - 1 : s.second().y();
        return Optional.of(Region.of(name, s.first().world(), s.first().x(), y1, s.first().z(), s.second().x(), y2, s.second().z()));
    }

    private void remove(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) {
            err(sender, "Usage: /protect remove <name>");
        } else if (manager.remove(args[1].toLowerCase(Locale.ROOT))) {
            ok(sender, "Removed '" + args[1].toLowerCase(Locale.ROOT) + "'. The area is no longer protected by it.");
        } else {
            err(sender, "No region named '" + args[1] + "'.");
        }
    }

    private void list(CommandSender sender, String[] args) {
        List<Region> regions = manager.all().stream().filter(r -> args.length < 2 || r.world().equalsIgnoreCase(args[1]))
                .sorted(java.util.Comparator.comparing(Region::world).thenComparing(Region::name)).toList();
        if (regions.isEmpty()) {
            info(sender, "No regions" + (args.length >= 2 ? " in '" + args[1] + "'" : "") + ".");
            return;
        }
        info(sender, regions.size() + " region(s):");
        regions.forEach(r -> info(sender, " " + r.name() + " [" + r.world() + "] priority " + r.priority() + ", "
                + r.flags().size() + " flag(s), " + describeBounds(r)));
    }

    private void showInfo(CommandSender sender, String[] args) {
        Region region;
        if (args.length >= 2) {
            Optional<Region> found = manager.get(args[1].toLowerCase(Locale.ROOT));
            if (found.isEmpty()) {
                err(sender, "No region named '" + args[1] + "'.");
                return;
            }
            region = found.get();
        } else if (sender instanceof Player player) {
            List<Region> here = manager.index().regionsAt(player.getWorld().getName(), player.getLocation().getBlockX(),
                    player.getLocation().getBlockY(), player.getLocation().getBlockZ());
            if (here.isEmpty()) {
                info(sender, "No region here.");
                return;
            }
            info(sender, "Regions here, strongest first: " + here.stream().map(Region::name).toList());
            region = here.get(0);
        } else {
            err(sender, "Usage: /protect info <name>");
            return;
        }
        info(sender, region.name() + " [" + region.world() + "] " + describeBounds(region) + ", priority " + region.priority());
        info(sender, " owners: " + names(region.owners()) + "; members: " + names(region.members()));
        if (!region.greeting().isEmpty() || !region.farewell().isEmpty()) {
            info(sender, " greeting: " + (region.greeting().isEmpty() ? "none" : region.greeting()) + "; farewell: "
                    + (region.farewell().isEmpty() ? "none" : region.farewell()));
        }
        if (region.flags().isEmpty()) {
            info(sender, " flags: none (everything allowed)");
        }
        region.flags().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> info(sender, " " + e.getKey().id() + ": " + (e.getValue() ? "allow" : "deny")));
    }

    private static String names(java.util.Set<UUID> ids) {
        if (ids.isEmpty()) {
            return "none";
        }
        return ids.stream().map(id -> {
            String name = Bukkit.getOfflinePlayer(id).getName();
            return name == null ? id.toString() : name;
        }).sorted().toList().toString();
    }

    private static String describeBounds(Region r) {
        if (r.isWholeWorld()) {
            return "whole world";
        }
        return r.minX() + " " + r.minY() + " " + r.minZ() + " to " + r.maxX() + " " + r.maxY() + " " + r.maxZ();
    }

    private Optional<Region> existing(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            err(sender, "Name a region. /protect list shows them.");
            return Optional.empty();
        }
        Optional<Region> region = manager.get(args[index].toLowerCase(Locale.ROOT));
        if (region.isEmpty()) {
            err(sender, "No region named '" + args[index] + "'.");
        }
        return region;
    }

    private void flag(CommandSender sender, String[] args) throws IOException {
        if (args.length < 4) {
            err(sender, "Usage: /protect flag <region> <flag> <allow|deny|none>   (/protect flags lists them)");
            return;
        }
        Optional<Region> region = existing(sender, args, 1);
        Optional<Flag> flag = Flag.parse(args[2]);
        if (region.isEmpty()) {
            return;
        }
        if (flag.isEmpty()) {
            err(sender, "Unknown flag '" + args[2] + "'. /protect flags lists them.");
            return;
        }
        Boolean state = switch (args[3].toLowerCase(Locale.ROOT)) {
            case "allow" -> Boolean.TRUE;
            case "deny" -> Boolean.FALSE;
            case "none", "unset" -> null;
            default -> {
                err(sender, "The value must be allow, deny or none.");
                yield null;
            }
        };
        if (state == null && !args[3].equalsIgnoreCase("none") && !args[3].equalsIgnoreCase("unset")) {
            return;
        }
        manager.put(region.get().withFlag(flag.get(), state));
        ok(sender, region.get().name() + ": " + flag.get().id() + " is now " + (state == null ? "unset" : state ? "allow" : "deny") + ".");
    }

    private void priority(CommandSender sender, String[] args) throws IOException {
        Optional<Region> region = existing(sender, args, 1);
        if (region.isEmpty()) {
            return;
        }
        if (args.length < 3) {
            err(sender, "Usage: /protect priority <region> <number>");
            return;
        }
        try {
            manager.put(region.get().withPriority(Integer.parseInt(args[2])));
            ok(sender, region.get().name() + " now has priority " + args[2] + " (higher wins where regions overlap).");
        } catch (NumberFormatException e) {
            err(sender, "'" + args[2] + "' is not a number.");
        }
    }

    private void message(CommandSender sender, String[] args, boolean greeting) throws IOException {
        Optional<Region> region = existing(sender, args, 1);
        if (region.isEmpty()) {
            return;
        }
        String kind = greeting ? "greeting" : "farewell";
        if (args.length < 3) {
            err(sender, "Usage: /protect " + kind + " <region> <MiniMessage text | none>   (tags: <region> <player>)");
            return;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (text.equalsIgnoreCase("none")) {
            text = "";
        } else {
            try {
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(text);
            } catch (RuntimeException e) {
                err(sender, "That text is not valid MiniMessage: " + e.getMessage());
                return;
            }
        }
        manager.put(greeting ? region.get().withGreeting(text) : region.get().withFarewell(text));
        ok(sender, region.get().name() + ": " + kind + (text.isEmpty() ? " removed." : " set."));
    }

    private void person(CommandSender sender, String[] args, boolean owner, boolean add) throws IOException {
        Optional<Region> region = existing(sender, args, 1);
        if (region.isEmpty()) {
            return;
        }
        if (args.length < 3) {
            err(sender, "Usage: /protect " + args[0].toLowerCase(Locale.ROOT) + " <region> <player>");
            return;
        }
        OfflinePlayer player = Bukkit.getPlayerExact(args[2]);
        if (player == null) {
            player = Bukkit.getOfflinePlayerIfCached(args[2]);
        }
        if (player == null) {
            err(sender, "I don't know a player named '" + args[2] + "'. They must have joined this server before.");
            return;
        }
        Region updated = owner ? region.get().withOwner(player.getUniqueId(), add) : region.get().withMember(player.getUniqueId(), add);
        if (updated.equals(region.get())) {
            info(sender, "Nothing changed: " + args[2] + (add ? " already is" : " is not") + (owner ? " an owner" : " a member")
                    + " of " + region.get().name() + ".");
            return;
        }
        manager.put(updated);
        ok(sender, (add ? "Added " : "Removed ") + args[2] + (add ? " to " : " from ") + region.get().name()
                + (owner ? " as owner." : " as member."));
    }

    private void preset(CommandSender sender, String[] args) throws IOException {
        Optional<Region> region = existing(sender, args, 1);
        if (region.isEmpty()) {
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("hub")) {
            err(sender, "Usage: /protect preset <region> hub   (the only preset: no building, breaking, containers, PvP, "
                    + "damage, hunger, mob spawning, griefing, explosions or fire; doors, buttons and villagers still work)");
            return;
        }
        Region updated = region.get();
        for (Flag flag : HUB_PRESET) {
            updated = updated.withFlag(flag, Boolean.FALSE);
        }
        updated = updated.withFlag(Flag.INTERACT, Boolean.TRUE);
        manager.put(updated);
        ok(sender, "Applied the hub preset to '" + updated.name() + "'. Staff with smpprotect.bypass can still build; add owners or members for others.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && NAME_FIRST.contains(sub)) {
                manager.all().forEach(r -> options.add(r.name()));
            } else if (args.length == 2 && sub.equals("list")) {
                Bukkit.getWorlds().forEach(w -> options.add(w.getName()));
            } else if (sub.equals("define") && args.length == 3) {
                options.addAll(List.of("fullheight", "world"));
            } else if (sub.equals("define") && args.length == 4 && args[2].equalsIgnoreCase("world")) {
                Bukkit.getWorlds().forEach(w -> options.add(w.getName()));
            } else if (sub.equals("flag") && args.length == 3) {
                Arrays.stream(Flag.values()).forEach(f -> options.add(f.id()));
            } else if (sub.equals("flag") && args.length == 4) {
                options.addAll(List.of("allow", "deny", "none"));
            } else if (sub.equals("preset") && args.length == 3) {
                options.add("hub");
            } else if (sub.contains("owner") || sub.contains("member")) {
                if (args.length == 3) {
                    Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        UnaryOperator<String> lower = s -> s.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> lower.apply(o).startsWith(prefix)).sorted().toList();
    }
}
