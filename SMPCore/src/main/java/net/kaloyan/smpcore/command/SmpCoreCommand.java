package net.kaloyan.smpcore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpcore.config.ConfigManager;
import net.kaloyan.smpcore.config.ReloadResult;
import net.kaloyan.smpcore.log.DebugLogger;
import org.bukkit.command.CommandSender;

/**
 * The {@code /smpcore} command tree. Permissions are checked inside every executor so that a sender
 * without access gets an explanation rather than Brigadier's generic "unknown command" error.
 */
public final class SmpCoreCommand {

    /** Permission for {@code /smpcore info}. */
    public static final String PERMISSION_INFO = "smpcore.info";
    /** Permission for {@code /smpcore reload}. */
    public static final String PERMISSION_ADMIN = "smpcore.admin";

    private static final String USAGE_INFO = "/smpcore info";
    private static final String USAGE_RELOAD = "/smpcore reload";

    private final String version;
    private final BooleanSupplier enabled;
    private final ConfigManager config;
    private final Messages messages;
    private final Executor mainThread;
    private final Logger logger;
    private final DebugLogger debug;

    /**
     * @param version    plugin version shown by {@code info}
     * @param enabled    reports whether the plugin is currently enabled
     * @param config     used by {@code reload}
     * @param messages   message sender
     * @param mainThread runs reload feedback back on the server main thread
     * @param logger     console logger for reload outcomes
     * @param debug      debug logger
     */
    public SmpCoreCommand(String version, BooleanSupplier enabled, ConfigManager config, Messages messages,
                          Executor mainThread, Logger logger, DebugLogger debug) {
        this.version = version;
        this.enabled = enabled;
        this.config = config;
        this.messages = messages;
        this.mainThread = mainThread;
        this.logger = logger;
        this.debug = debug;
    }

    /**
     * Builds the command tree, ready to hand to Paper's command registrar.
     *
     * @return the root {@code smpcore} node
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("smpcore")
                .requires(source -> source.getSender().hasPermission(PERMISSION_INFO) || source.getSender().hasPermission(PERMISSION_ADMIN))
                .executes(this::usage)
                .then(Commands.literal("info")
                        .executes(this::info)
                        .then(extraArguments(PERMISSION_INFO, USAGE_INFO)))
                .then(Commands.literal("reload")
                        .executes(this::reload)
                        .then(extraArguments(PERMISSION_ADMIN, USAGE_RELOAD)))
                // Literals win over this when they match, so this only sees unknown subcommands.
                .then(Commands.argument("subcommand", StringArgumentType.greedyString())
                        .executes(this::unknownSubcommand))
                .build();
    }

    private int usage(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<String> available = availableUsages(sender);
        if (available.isEmpty()) {
            messages.error(sender, "You do not have permission to use any SMP-Core commands.");
            return 0;
        }
        messages.info(sender, "Usage: " + String.join(" | ", available));
        return Command.SINGLE_SUCCESS;
    }

    private int unknownSubcommand(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String given = StringArgumentType.getString(context, "subcommand");
        List<String> available = availableUsages(sender);
        if (available.isEmpty()) {
            messages.error(sender, "You do not have permission to use any SMP-Core commands.");
        } else {
            messages.error(sender, "Unknown subcommand '" + given + "'. Usage: " + String.join(" | ", available));
        }
        return 0;
    }

    private int info(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!hasPermission(sender, PERMISSION_INFO)) {
            return 0;
        }
        messages.info(sender, "SMP-Core v" + version);
        messages.info(sender, "Status: " + (enabled.getAsBoolean() ? "enabled" : "disabled"));
        messages.info(sender, "Debug logging: " + (config.current().debug() ? "on" : "off"));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!hasPermission(sender, PERMISSION_ADMIN)) {
            return 0;
        }
        String who = sender.getName();
        debug.debug(() -> "Reload requested by " + who);
        messages.info(sender, "Reloading configuration...");
        config.reload().whenCompleteAsync((result, error) -> reportReload(sender, who, result, error), mainThread);
        return Command.SINGLE_SUCCESS;
    }

    private void reportReload(CommandSender sender, String who, ReloadResult result, Throwable error) {
        if (error != null) {
            logger.log(Level.SEVERE, "Configuration reload requested by " + who + " failed unexpectedly", error);
            messages.error(sender, "Reload failed unexpectedly; the previous settings remain active. See the console.");
        } else if (result.success()) {
            logger.info("Configuration reloaded by " + who);
            messages.success(sender, "Configuration reloaded.");
        } else {
            messages.error(sender, "Reload rejected; the previous settings remain active.");
            result.problems().forEach(problem -> messages.error(sender, " - " + problem));
        }
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> extraArguments(String permission, String usage) {
        return Commands.argument("extra", StringArgumentType.greedyString()).executes(context -> {
            CommandSender sender = context.getSource().getSender();
            if (hasPermission(sender, permission)) {
                messages.error(sender, "Too many arguments. Usage: " + usage);
            }
            return 0;
        });
    }

    /** Checks {@code permission}; tells the sender what is missing if they lack it. */
    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages.error(sender, "You do not have permission to do that (requires " + permission + ").");
        return false;
    }

    private List<String> availableUsages(CommandSender sender) {
        List<String> usages = new ArrayList<>();
        if (sender.hasPermission(PERMISSION_INFO)) {
            usages.add(USAGE_INFO);
        }
        if (sender.hasPermission(PERMISSION_ADMIN)) {
            usages.add(USAGE_RELOAD);
        }
        return usages;
    }
}
