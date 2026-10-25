package net.kaloyan.smpcrates;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kaloyan.smpcrates.CrateStore.CrateBlock;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** {@code /crates}: the menu for players, and give/take/setblock/reload for admins. */
public final class CratesCommand implements TabExecutor {

    private static final String ADMIN = "smpcrates.admin";
    private static final List<String> PLAYER_SUBS = List.of("open", "preview", "balance", "timer");
    private static final List<String> ADMIN_SUBS = List.of("give", "take", "setblock", "removeblock", "reload");

    private final CrateService service;
    private final CrateStore store;
    private final Menus menus;
    private final Messages messages;
    private final java.util.function.Supplier<CratesSettings> settings;
    private final Runnable reload;

    /**
     * @param service  the crate rules
     * @param store    balances and crate blocks
     * @param menus    the menus
     * @param messages the texts
     * @param settings the current settings
     * @param reload   reloads the configuration
     */
    public CratesCommand(CrateService service, CrateStore store, Menus menus, Messages messages,
                         java.util.function.Supplier<CratesSettings> settings, Runnable reload) {
        this.service = service;
        this.store = store;
        this.menus = menus;
        this.messages = messages;
        this.settings = settings;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "menu" -> {
                    if (sender instanceof Player p) {
                        menus.openMain(p);
                    } else {
                        messages.send(sender, "not-player");
                    }
                }
                case "open", "preview" -> openOrPreview(sender, sub, args);
                case "balance" -> balance(sender, args);
                case "timer" -> timer(sender);
                case "give" -> give(sender, args, true);
                case "take" -> give(sender, args, false);
                case "setblock" -> setBlock(sender, args);
                case "removeblock" -> removeBlock(sender);
                case "reload" -> {
                    if (admin(sender)) {
                        reload.run();
                        messages.send(sender, "reloaded");
                    }
                }
                default -> messages.send(sender, "usage");
            }
        } catch (IOException e) {
            messages.send(sender, "save-failed");
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        messages.send(sender, "no-permission");
        return false;
    }

    private Optional<CrateType> crate(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            messages.send(sender, "usage");
            return Optional.empty();
        }
        Optional<CrateType> type = service.type(args[index].toLowerCase(Locale.ROOT));
        if (type.isEmpty()) {
            messages.send(sender, "unknown-crate", "crates", String.join(", ", settings.get().crates().keySet()));
        }
        return type;
    }

    private void openOrPreview(CommandSender sender, String sub, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "not-player");
            return;
        }
        crate(sender, args, 1).ifPresent(type -> {
            if (sub.equals("open")) {
                menus.open(player, type);
            } else {
                menus.openPreview(player, type);
            }
        });
    }

    private void balance(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 2 && !(sender instanceof Player p && p.getName().equalsIgnoreCase(args[1]))) {
            if (!admin(sender)) {
                return;
            }
            target = lookup(args[1]);
            if (target == null) {
                messages.send(sender, "unknown-player", "player", args[1]);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            messages.send(sender, "not-player");
            return;
        }
        var mine = store.balances(target.getUniqueId());
        if (mine.isEmpty()) {
            messages.send(sender, "balance-none", "player", String.valueOf(target.getName()));
            return;
        }
        for (CrateType type : settings.get().crates().values()) {
            int n = mine.getOrDefault(type.id(), 0);
            if (n > 0) {
                messages.send(sender, "balance-line", "crate", messages.parse(type.display()), "count", Integer.toString(n));
            }
        }
    }

    private void timer(CommandSender sender) {
        if (!settings.get().hourlyEnabled()) {
            messages.send(sender, "no-timer");
            return;
        }
        messages.send(sender, "timer", "time", HourlyClock.format(service.hourly().untilNext(System.currentTimeMillis())));
    }

    private OfflinePlayer lookup(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    private void give(CommandSender sender, String[] args, boolean give) throws IOException {
        if (!admin(sender)) {
            return;
        }
        if (args.length < 3) {
            messages.send(sender, "usage");
            return;
        }
        OfflinePlayer target = lookup(args[1]);
        if (target == null) {
            messages.send(sender, "unknown-player", "player", args[1]);
            return;
        }
        Optional<CrateType> type = crate(sender, args, 2);
        if (type.isEmpty()) {
            return;
        }
        int amount;
        try {
            amount = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > 100_000) {
            messages.send(sender, "bad-amount");
            return;
        }
        UUID id = target.getUniqueId();
        Object crateName = messages.parse(type.get().display());
        if (give) {
            service.give(id, type.get().id(), amount);
            messages.send(sender, "gave", "player", String.valueOf(target.getName()), "count", Integer.toString(amount), "crate", crateName);
            if (target.getPlayer() != null) {
                messages.send(target.getPlayer(), "received", "count", Integer.toString(amount), "crate", crateName);
            }
        } else if (store.tryTake(id, type.get().id(), amount)) {
            messages.send(sender, "took", "player", String.valueOf(target.getName()), "count", Integer.toString(amount), "crate", crateName);
        } else {
            messages.send(sender, "not-enough", "player", String.valueOf(target.getName()));
        }
    }

    private void setBlock(CommandSender sender, String[] args) throws IOException {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "not-player");
            return;
        }
        Optional<CrateType> type = crate(sender, args, 1);
        Block block = player.getTargetBlockExact(6);
        if (type.isEmpty()) {
            return;
        }
        if (block == null) {
            messages.send(sender, "look-at-block");
            return;
        }
        store.putBlock(new CrateBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), type.get().id()));
        messages.send(sender, "block-set", "crate", messages.parse(type.get().display()));
    }

    private void removeBlock(CommandSender sender) throws IOException {
        if (!admin(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "not-player");
            return;
        }
        Block block = player.getTargetBlockExact(6);
        if (block == null) {
            messages.send(sender, "look-at-block");
        } else if (store.removeBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
            messages.send(sender, "block-removed");
        } else {
            messages.send(sender, "block-not-crate");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (sender.hasPermission(ADMIN)) {
                options.addAll(ADMIN_SUBS);
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "open", "preview", "setblock" -> options.addAll(settings.get().crates().keySet());
                case "give", "take", "balance" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                default -> { }
            }
        } else if (args.length == 3 && List.of("give", "take").contains(args[0].toLowerCase(Locale.ROOT))) {
            options.addAll(settings.get().crates().keySet());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
