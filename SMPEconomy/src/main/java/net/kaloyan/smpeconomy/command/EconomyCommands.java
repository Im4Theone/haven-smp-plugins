package net.kaloyan.smpeconomy.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.config.ConfigManager;
import net.kaloyan.smpeconomy.config.ReloadResult;
import net.kaloyan.smpeconomy.money.InvalidAmountException;
import net.kaloyan.smpeconomy.money.Money;
import net.kaloyan.smpeconomy.service.EconomyServiceImpl;
import net.kaloyan.smpeconomy.storage.AccountRef;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /balance}, {@code /pay} and {@code /eco} command trees. Permissions are checked inside every
 * executor so a sender without access gets an explanation instead of Brigadier's generic error.
 *
 * <p>Threading: executors run on the main thread. Database work is queued on the storage thread by
 * {@link EconomyServiceImpl}; the outcome is reported back to the sender on {@code mainThread}, and only
 * after the transaction has committed or failed.
 */
public final class EconomyCommands {

    /** View your own balance. */
    public static final String PERM_BALANCE = "smpeconomy.balance";
    /** View another player's balance. */
    public static final String PERM_BALANCE_OTHERS = "smpeconomy.balance.others";
    /** Use {@code /pay}. */
    public static final String PERM_PAY = "smpeconomy.pay";
    /** {@code /eco balance}. */
    public static final String PERM_ADMIN_BALANCE = "smpeconomy.admin.balance";
    /** {@code /eco set}. */
    public static final String PERM_ADMIN_SET = "smpeconomy.admin.set";
    /** {@code /eco add}. */
    public static final String PERM_ADMIN_ADD = "smpeconomy.admin.add";
    /** {@code /eco take}. */
    public static final String PERM_ADMIN_TAKE = "smpeconomy.admin.take";
    /** {@code /eco reload}. */
    public static final String PERM_ADMIN_RELOAD = "smpeconomy.admin.reload";

    private static final String USAGE_BALANCE = "/balance [player]";
    private static final String USAGE_PAY = "/pay <player> <amount>";
    private static final String USAGE_ECO_BALANCE = "/eco balance <player>";
    private static final String USAGE_ECO_SET = "/eco set <player> <amount>";
    private static final String USAGE_ECO_ADD = "/eco add <player> <amount>";
    private static final String USAGE_ECO_TAKE = "/eco take <player> <amount>";
    private static final String USAGE_ECO_RELOAD = "/eco reload";

    private static final String NO_ACCOUNT_SELF =
            "You do not have an economy account yet. Try rejoining; if this persists, contact an administrator.";

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private enum Change { SET, ADD, TAKE }

    private record Found(AccountRef account, Optional<BigDecimal> balance) {
    }

    private final Server server;
    private final EconomyServiceImpl service;
    private final ConfigManager config;
    private final Messages messages;
    private final Executor mainThread;
    private final Logger logger;

    /** Players with a /pay in flight; a second /pay from the same player is refused until it finishes. */
    private final Set<UUID> payInFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param server     used to find online players
     * @param service    the economy
     * @param config     used by {@code /eco reload}
     * @param messages   message sender
     * @param mainThread runs result reporting back on the server main thread
     * @param logger     console logger for administrative actions and unexpected errors
     */
    public EconomyCommands(Server server, EconomyServiceImpl service, ConfigManager config, Messages messages,
                           Executor mainThread, Logger logger) {
        this.server = server;
        this.service = service;
        this.config = config;
        this.messages = messages;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    // ---------------------------------------------------------------- command trees

    /**
     * Builds {@code /balance [player]}.
     *
     * @return the root node
     */
    public LiteralCommandNode<CommandSourceStack> buildBalance() {
        return Commands.literal("balance")
                .executes(this::balanceSelf)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> suggestPlayers(context, builder, PERM_BALANCE_OTHERS, false))
                        .executes(this::balanceOther))
                .build();
    }

    /**
     * Builds {@code /pay <player> <amount>}.
     *
     * @return the root node
     */
    public LiteralCommandNode<CommandSourceStack> buildPay() {
        return Commands.literal("pay")
                .executes(context -> usage(context, PERM_PAY, USAGE_PAY))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> suggestPlayers(context, builder, PERM_PAY, true))
                        .executes(context -> usage(context, PERM_PAY, USAGE_PAY))
                        .then(Commands.argument("amount", StringArgumentType.greedyString())
                                .executes(this::pay)))
                .build();
    }

    /**
     * Builds {@code /eco}.
     *
     * @return the root node
     */
    public LiteralCommandNode<CommandSourceStack> buildEco() {
        return Commands.literal("eco")
                .requires(source -> !availableEcoUsages(source.getSender()).isEmpty())
                .executes(this::ecoUsage)
                .then(Commands.literal("balance")
                        .executes(context -> usage(context, PERM_ADMIN_BALANCE, USAGE_ECO_BALANCE))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> suggestPlayers(context, builder, PERM_ADMIN_BALANCE, false))
                                .executes(this::ecoBalance)))
                .then(modifyNode("set", PERM_ADMIN_SET, USAGE_ECO_SET, Change.SET))
                .then(modifyNode("add", PERM_ADMIN_ADD, USAGE_ECO_ADD, Change.ADD))
                .then(modifyNode("take", PERM_ADMIN_TAKE, USAGE_ECO_TAKE, Change.TAKE))
                .then(Commands.literal("reload").executes(this::ecoReload))
                // Literals win when they match, so this only sees unknown subcommands.
                .then(Commands.argument("subcommand", StringArgumentType.greedyString())
                        .executes(this::ecoUnknown))
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> modifyNode(
            String name, String permission, String usage, Change change) {
        return Commands.literal(name)
                .executes(context -> usage(context, permission, usage))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> suggestPlayers(context, builder, permission, false))
                        .executes(context -> usage(context, permission, usage))
                        .then(Commands.argument("amount", StringArgumentType.greedyString())
                                .executes(context -> ecoModify(context, permission, change))));
    }

    // ---------------------------------------------------------------- /balance

    private int balanceSelf(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Usage: /balance <player>");
            return 0;
        }
        if (!hasPermission(sender, PERM_BALANCE)) {
            return 0;
        }
        reply(service.getBalance(player.getUniqueId()), sender, "balance lookup", balance -> {
            if (balance.isPresent()) {
                messages.info(sender, "Your balance: " + service.format(balance.get()));
            } else {
                messages.error(sender, NO_ACCOUNT_SELF);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int balanceOther(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String input = StringArgumentType.getString(context, "player");
        if (sender instanceof Player self && self.getName().equalsIgnoreCase(input)) {
            return balanceSelf(context);
        }
        if (!hasPermission(sender, PERM_BALANCE_OTHERS)) {
            return 0;
        }
        reply(lookup(input), sender, "balance lookup", found -> {
            if (found.isEmpty()) {
                messages.error(sender, unknownPlayer(input));
            } else if (found.get().balance().isEmpty()) {
                messages.error(sender, found.get().account().displayName() + " has no economy account.");
            } else {
                messages.info(sender, found.get().account().displayName() + "'s balance: "
                        + service.format(found.get().balance().get()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- /pay

    private int pay(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player payer)) {
            messages.error(sender, "Only players can pay. Console: use /eco add or /eco take.");
            return 0;
        }
        if (!hasPermission(sender, PERM_PAY)) {
            return 0;
        }
        String input = StringArgumentType.getString(context, "player");
        BigDecimal amount;
        try {
            amount = Money.parse(StringArgumentType.getString(context, "amount"));
            Money.toPositiveMinor(amount); // refuse zero and negative amounts before anything is queued
        } catch (InvalidAmountException e) {
            messages.error(sender, e.getMessage());
            return 0;
        }
        UUID payerId = payer.getUniqueId();
        if (!payInFlight.add(payerId)) {
            messages.error(sender, "Your previous payment is still being processed. Please wait a moment.");
            return 0;
        }
        CompletableFuture<Optional<AccountOutcome>> outcome;
        try {
            outcome = resolve(input).thenCompose(target -> {
                if (target.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.<AccountOutcome>empty());
                }
                return service.transfer(payerId, target.get().uuid(), amount)
                        .thenApply(result -> Optional.of(new AccountOutcome(target.get(), result)));
            });
        } catch (RuntimeException e) {
            payInFlight.remove(payerId);
            throw e;
        }
        // The guard is released as soon as the transaction has finished, before any message is sent.
        CompletableFuture<Optional<AccountOutcome>> finished =
                outcome.whenComplete((value, error) -> payInFlight.remove(payerId));
        reply(finished, sender, "payment", result -> reportPayment(payer, amount, input, result));
        return Command.SINGLE_SUCCESS;
    }

    private record AccountOutcome(AccountRef target, TransactionResult result) {
    }

    private void reportPayment(Player payer, BigDecimal amount, String input, Optional<AccountOutcome> outcome) {
        if (outcome.isEmpty()) {
            messages.error(payer, unknownPlayer(input));
            return;
        }
        AccountRef target = outcome.get().target();
        TransactionResult result = outcome.get().result();
        String shown = service.format(amount);
        switch (result.status()) {
            case SUCCESS -> {
                messages.success(payer, "You paid " + target.displayName() + " " + shown + ". Your new balance: "
                        + service.format(result.balance()));
                Player recipient = server.getPlayer(target.uuid());
                if (recipient != null) {
                    messages.success(recipient, payer.getName() + " paid you " + shown + ". Your new balance: "
                            + service.format(result.counterpartBalance()));
                }
            }
            case INSUFFICIENT_FUNDS -> messages.error(payer, "You do not have enough money to pay " + shown + ".");
            case SELF_TRANSFER -> messages.error(payer, "You cannot pay yourself.");
            case SOURCE_ACCOUNT_NOT_FOUND -> messages.error(payer, NO_ACCOUNT_SELF);
            case TARGET_ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND ->
                    messages.error(payer, target.displayName() + " has no economy account.");
            case AMOUNT_EXCEEDS_LIMIT -> messages.error(payer,
                    "You can pay at most " + service.format(service.getMaximumTransactionAmount()) + " at a time.");
            case BALANCE_LIMIT_EXCEEDED -> messages.error(payer,
                    target.displayName() + " cannot receive that much; their balance would exceed the maximum of "
                            + service.format(service.getMaximumBalance()) + ".");
            case INVALID_AMOUNT -> messages.error(payer, result.message());
            case STORAGE_ERROR -> messages.error(payer, "The payment failed because of a database error and was not "
                    + "completed. Check your balance before trying again; an administrator can check the console.");
        }
    }

    // ---------------------------------------------------------------- /eco

    private int ecoUsage(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<String> available = availableEcoUsages(sender);
        if (available.isEmpty()) {
            messages.error(sender, "You do not have permission to use any /eco commands.");
            return 0;
        }
        messages.info(sender, "Usage: " + String.join(" | ", available));
        return Command.SINGLE_SUCCESS;
    }

    private int ecoUnknown(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        String given = StringArgumentType.getString(context, "subcommand");
        List<String> available = availableEcoUsages(sender);
        if (available.isEmpty()) {
            messages.error(sender, "You do not have permission to use any /eco commands.");
        } else {
            messages.error(sender, "Unknown subcommand '" + given + "'. Usage: " + String.join(" | ", available));
        }
        return 0;
    }

    private List<String> availableEcoUsages(CommandSender sender) {
        List<String> usages = new ArrayList<>();
        if (sender.hasPermission(PERM_ADMIN_BALANCE)) {
            usages.add(USAGE_ECO_BALANCE);
        }
        if (sender.hasPermission(PERM_ADMIN_SET)) {
            usages.add(USAGE_ECO_SET);
        }
        if (sender.hasPermission(PERM_ADMIN_ADD)) {
            usages.add(USAGE_ECO_ADD);
        }
        if (sender.hasPermission(PERM_ADMIN_TAKE)) {
            usages.add(USAGE_ECO_TAKE);
        }
        if (sender.hasPermission(PERM_ADMIN_RELOAD)) {
            usages.add(USAGE_ECO_RELOAD);
        }
        return usages;
    }

    private int ecoBalance(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!hasPermission(sender, PERM_ADMIN_BALANCE)) {
            return 0;
        }
        String input = StringArgumentType.getString(context, "player");
        reply(lookup(input), sender, "admin balance lookup", found -> {
            if (found.isEmpty()) {
                messages.error(sender, unknownPlayer(input));
            } else if (found.get().balance().isEmpty()) {
                messages.error(sender, describe(found.get().account()) + " has no economy account.");
            } else {
                messages.info(sender, describe(found.get().account()) + " has "
                        + service.format(found.get().balance().get()));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int ecoModify(CommandContext<CommandSourceStack> context, String permission, Change change) {
        CommandSender sender = context.getSource().getSender();
        if (!hasPermission(sender, permission)) {
            return 0;
        }
        String input = StringArgumentType.getString(context, "player");
        BigDecimal amount;
        try {
            amount = Money.parse(StringArgumentType.getString(context, "amount"));
        } catch (InvalidAmountException e) {
            messages.error(sender, e.getMessage());
            return 0;
        }
        CompletableFuture<Optional<AccountOutcome>> outcome = resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<AccountOutcome>empty());
            }
            UUID id = target.get().uuid();
            CompletableFuture<TransactionResult> result = switch (change) {
                case SET -> service.setBalance(id, amount);
                case ADD -> service.adminAdd(id, amount);
                case TAKE -> service.adminTake(id, amount);
            };
            return result.thenApply(r -> Optional.of(new AccountOutcome(target.get(), r)));
        });
        reply(outcome, sender, "administrative balance change",
                found -> reportChange(sender, change, amount, input, found));
        return Command.SINGLE_SUCCESS;
    }

    private void reportChange(CommandSender sender, Change change, BigDecimal amount, String input,
                              Optional<AccountOutcome> outcome) {
        if (outcome.isEmpty()) {
            messages.error(sender, unknownPlayer(input));
            return;
        }
        AccountRef target = outcome.get().target();
        TransactionResult result = outcome.get().result();
        String shown = service.format(amount);
        switch (result.status()) {
            case SUCCESS -> {
                String newBalance = service.format(result.balance());
                String text = switch (change) {
                    case SET -> "Set the balance of " + describe(target) + " to " + newBalance + ".";
                    case ADD -> "Added " + shown + " to " + describe(target) + ". New balance: " + newBalance + ".";
                    case TAKE -> "Took " + shown + " from " + describe(target) + ". New balance: " + newBalance + ".";
                };
                messages.success(sender, text);
                logger.info("[ECO] " + actor(sender) + " " + switch (change) {
                    case SET -> "set the balance of " + describe(target) + " to " + newBalance;
                    case ADD -> "added " + shown + " to " + describe(target) + " (new balance " + newBalance + ")";
                    case TAKE -> "took " + shown + " from " + describe(target) + " (new balance " + newBalance + ")";
                });
                Player online = server.getPlayer(target.uuid());
                if (online != null && !online.equals(sender)) {
                    messages.info(online, "An administrator changed your balance. New balance: " + newBalance);
                }
            }
            case ACCOUNT_NOT_FOUND, SOURCE_ACCOUNT_NOT_FOUND, TARGET_ACCOUNT_NOT_FOUND ->
                    messages.error(sender, describe(target) + " has no economy account.");
            case INSUFFICIENT_FUNDS -> messages.error(sender,
                    describe(target) + " has less than " + shown + "; balances cannot go below zero.");
            case BALANCE_LIMIT_EXCEEDED -> messages.error(sender, "That would put the balance above the maximum of "
                    + service.format(service.getMaximumBalance()) + ".");
            case INVALID_AMOUNT -> messages.error(sender, result.message());
            case AMOUNT_EXCEEDS_LIMIT, SELF_TRANSFER -> messages.error(sender, result.message());
            case STORAGE_ERROR -> messages.error(sender,
                    "The change failed because of a database error and was not applied. See the console.");
        }
    }

    private int ecoReload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!hasPermission(sender, PERM_ADMIN_RELOAD)) {
            return 0;
        }
        String who = actor(sender);
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

    // ---------------------------------------------------------------- shared helpers

    /** Reports an unexpected failure of {@code future} instead of leaving the sender waiting forever. */
    private <T> void reply(CompletableFuture<T> future, CommandSender sender, String what, Consumer<T> onSuccess) {
        future.whenCompleteAsync((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                logger.log(Level.SEVERE, "The " + what + " requested by " + sender.getName() + " failed", cause);
                messages.error(sender, "Something went wrong and the " + what + " could not be completed. See the console.");
            } else {
                onSuccess.accept(value);
            }
        }, mainThread);
    }

    /**
     * Finds the account a typed name or UUID refers to. Must be called on the main thread. Never creates
     * an account. An online player is matched by their live name; otherwise the stored last known name
     * (or the UUID, if one was typed) is looked up.
     */
    private CompletableFuture<Optional<AccountRef>> resolve(String input) {
        Player online = server.getPlayerExact(input);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new AccountRef(online.getUniqueId(), online.getName())));
        }
        if (UUID_TEXT.matcher(input).matches()) {
            return service.findAccount(UUID.fromString(input));
        }
        return service.findAccountByName(input);
    }

    private CompletableFuture<Optional<Found>> lookup(String input) {
        return resolve(input).thenCompose(account -> {
            if (account.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Found>empty());
            }
            return service.getBalance(account.get().uuid())
                    .thenApply(balance -> Optional.of(new Found(account.get(), balance)));
        });
    }

    private int usage(CommandContext<CommandSourceStack> context, String permission, String usage) {
        CommandSender sender = context.getSource().getSender();
        if (hasPermission(sender, permission)) {
            messages.error(sender, "Usage: " + usage);
        }
        return 0;
    }

    /** Checks {@code permission}; tells the sender what is missing if they lack it. */
    private boolean hasPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages.error(sender, "You do not have permission to do that (requires " + permission + ").");
        return false;
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context,
                                                          SuggestionsBuilder builder, String permission,
                                                          boolean excludeSelf) {
        CommandSender sender = context.getSource().getSender();
        if (sender.hasPermission(permission)) {
            String typed = builder.getRemainingLowerCase();
            for (Player player : server.getOnlinePlayers()) {
                String name = player.getName();
                boolean self = excludeSelf && name.equalsIgnoreCase(sender.getName());
                if (!self && name.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(name);
                }
            }
        }
        return builder.buildFuture();
    }

    private static String unknownPlayer(String input) {
        return "No economy account is known for '" + input + "'. They must have joined the server at least once.";
    }

    private static String describe(AccountRef account) {
        return account.lastName() != null ? account.lastName() + " (" + account.uuid() + ")" : account.uuid().toString();
    }

    private static String actor(CommandSender sender) {
        return sender instanceof Player player ? player.getName() + " (" + player.getUniqueId() + ")" : sender.getName();
    }
}
