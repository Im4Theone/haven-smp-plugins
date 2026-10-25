package net.kaloyan.smpmarket.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpmarket.config.ConfigManager;
import net.kaloyan.smpmarket.model.Delivery;
import net.kaloyan.smpmarket.model.EscrowResolution;
import net.kaloyan.smpmarket.model.Listing;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.model.Price;
import net.kaloyan.smpmarket.service.MarketResults.BuyResult;
import net.kaloyan.smpmarket.service.MarketResults.CancelResult;
import net.kaloyan.smpmarket.service.MarketResults.CollectResult;
import net.kaloyan.smpmarket.service.MarketResults.SellResult;
import net.kaloyan.smpmarket.service.MarketService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /market} command tree. Permissions are checked inside every executor so a sender without access gets
 * an explanation instead of Brigadier's generic error.
 *
 * <p>Threading: executors run on the main thread. All work is queued on {@link MarketService}; the outcome is
 * reported back on {@code mainThread}, and only after the underlying operation has committed or failed.
 */
public final class MarketCommands {

    public static final String PERM_USE = "smpmarket.use";
    public static final String PERM_SELL = "smpmarket.sell";
    public static final String PERM_BUY = "smpmarket.buy";
    public static final String PERM_CANCEL = "smpmarket.cancel";
    public static final String PERM_COLLECT = "smpmarket.collect";
    public static final String PERM_ADMIN_INSPECT = "smpmarket.admin.inspect";
    public static final String PERM_ADMIN_CANCEL = "smpmarket.admin.cancel";
    public static final String PERM_ADMIN_RECOVER = "smpmarket.admin.recover";
    public static final String PERM_ADMIN_RELOAD = "smpmarket.admin.reload";

    private static final UUID CONSOLE_ID = new UUID(0L, 0L);
    private static final int MIN_REASON_LENGTH = 5;

    private static final String NO_PERMISSION = "You do not have permission to do that.";
    private static final String INTERNAL_ERROR = "Something went wrong. Nothing was lost; an administrator can check "
            + "the server console.";

    private final MarketService service;
    private final ConfigManager config;
    private final Messages messages;
    private final Executor mainThread;
    private final Clock clock;
    private final Logger logger;

    /**
     * @param service    the market
     * @param config     used by {@code /market admin reload}
     * @param messages   message sender
     * @param mainThread runs result reporting on the server main thread
     * @param clock      UTC time source for "expires in" text
     * @param logger     console logger for unexpected errors
     */
    public MarketCommands(MarketService service, ConfigManager config, Messages messages, Executor mainThread,
                          Clock clock, Logger logger) {
        this.service = service;
        this.config = config;
        this.messages = messages;
        this.mainThread = mainThread;
        this.clock = clock;
        this.logger = logger;
    }

    // ---------------------------------------------------------------- command tree

    /**
     * Builds {@code /market}.
     *
     * @return the root node
     */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("market")
                .executes(this::status)
                .then(Commands.literal("help").executes(this::help))
                .then(Commands.literal("sell")
                        .executes(ctx -> usage(ctx, PERM_SELL, "/market sell <price>"))
                        .then(Commands.argument("price", StringArgumentType.word()).executes(this::sell)))
                .then(Commands.literal("listings").executes(this::listings))
                .then(Commands.literal("browse")
                        .executes(ctx -> browse(ctx, "1"))
                        .then(Commands.argument("page", StringArgumentType.word())
                                .executes(ctx -> browse(ctx, StringArgumentType.getString(ctx, "page")))))
                .then(Commands.literal("buy")
                        .executes(ctx -> usage(ctx, PERM_BUY, "/market buy <listing-id>"))
                        .then(Commands.argument("id", StringArgumentType.word()).executes(this::buy)))
                .then(Commands.literal("cancel")
                        .executes(ctx -> usage(ctx, PERM_CANCEL, "/market cancel <listing-id>"))
                        .then(Commands.argument("id", StringArgumentType.word()).executes(this::cancel)))
                .then(Commands.literal("collect").executes(this::collect))
                .then(Commands.literal("history")
                        .executes(ctx -> history(ctx, "1"))
                        .then(Commands.argument("page", StringArgumentType.word())
                                .executes(ctx -> history(ctx, StringArgumentType.getString(ctx, "page")))))
                .then(adminNode())
                // Literals win when they match, so this only sees unknown subcommands.
                .then(Commands.argument("subcommand", StringArgumentType.greedyString()).executes(this::unknown))
                .build();
    }

    private LiteralArgumentBuilder<CommandSourceStack> adminNode() {
        return Commands.literal("admin")
                .executes(this::adminHelp)
                .then(Commands.literal("listings")
                        .executes(ctx -> adminListings(ctx, "1"))
                        .then(Commands.argument("page", StringArgumentType.word())
                                .executes(ctx -> adminListings(ctx, StringArgumentType.getString(ctx, "page")))))
                .then(Commands.literal("listing")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(this::adminListing)))
                .then(Commands.literal("tx")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(this::adminTx)))
                .then(Commands.literal("issues").executes(this::adminIssues))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(this::adminCancel))))
                .then(Commands.literal("recover").executes(this::adminRecover))
                .then(Commands.literal("reload").executes(this::adminReload))
                .then(Commands.literal("resolve")
                        .executes(this::adminHelp)
                        .then(resolveNode("payment", "paid|unpaid"))
                        .then(resolveNode("delivery", "delivered|retry"))
                        .then(resolveNode("escrow", "activate|return|abort"))
                        .then(resolveNode("fee", "collected|retry")))
                .then(Commands.argument("subcommand", StringArgumentType.greedyString()).executes(this::adminHelp));
    }

    private LiteralArgumentBuilder<CommandSourceStack> resolveNode(String kind, String outcomes) {
        return Commands.literal(kind)
                .executes(ctx -> usage(ctx, PERM_ADMIN_RECOVER, "/market admin resolve " + kind + " <id> <" + outcomes
                        + "> <reason>"))
                .then(Commands.argument("id", StringArgumentType.word())
                        .then(Commands.argument("outcome", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> resolve(ctx, kind)))));
    }

    // ---------------------------------------------------------------- player commands

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_USE)) {
            return 0;
        }
        help(ctx);
        reply(service.activeCount(), sender, "status", count -> messages.info(sender, "Active listings: " + count));
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_USE)) {
            return 0;
        }
        messages.info(sender, "Market commands:");
        messages.line(sender, "/market sell <price> - list the item in your hand for a total price");
        messages.line(sender, "/market listings - your active listings");
        messages.line(sender, "/market browse [page] - browse active listings");
        messages.line(sender, "/market buy <listing-id> - buy a listing");
        messages.line(sender, "/market cancel <listing-id> - cancel your listing");
        messages.line(sender, "/market collect - collect returned items and purchases");
        messages.line(sender, "/market history [page] - your recent sales and purchases");
        if (sender.hasPermission(PERM_ADMIN_INSPECT)) {
            messages.line(sender, "/market admin - administration");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int unknown(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        messages.error(sender, "Unknown subcommand '" + StringArgumentType.getString(ctx, "subcommand")
                + "'. Use /market help.");
        return 0;
    }

    private int usage(CommandContext<CommandSourceStack> ctx, String permission, String usage) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, permission)) {
            return 0;
        }
        messages.error(sender, "Usage: " + usage);
        return 0;
    }

    private int sell(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Only players can list items.");
            return 0;
        }
        if (!allowed(sender, PERM_SELL)) {
            return 0;
        }
        long price;
        try {
            price = Price.parseMinor(StringArgumentType.getString(ctx, "price"));
        } catch (Price.InvalidPriceException e) {
            messages.error(sender, e.getMessage());
            return 0;
        }
        reply(service.sell(player.getUniqueId(), player.getName(), price), sender, "sell", result -> reportSell(sender, result));
        return Command.SINGLE_SUCCESS;
    }

    private void reportSell(CommandSender sender, SellResult r) {
        switch (r.status()) {
            case LISTED -> messages.success(sender, "Listed " + r.item().display() + " as #" + r.listingId() + " for "
                    + service.format(r.price()) + ". It expires in " + remaining(r.expiresAt()) + ".");
            case EMPTY_HAND -> messages.error(sender, "Hold the item you want to sell in your main hand.");
            case PRICE_TOO_LOW -> messages.error(sender, "The minimum price is " + service.format(r.bound()) + ".");
            case PRICE_TOO_HIGH -> messages.error(sender, "The maximum price is " + service.format(r.bound()) + ".");
            case ECONOMY_LIMIT -> messages.error(sender, "The economy does not allow a single payment above "
                    + service.format(r.bound()) + ".");
            case AMOUNT_OUT_OF_RANGE -> messages.error(sender, "That stack size cannot be listed (limit " + r.bound() + ").");
            case ITEM_RESTRICTED -> messages.error(sender, "That item cannot be listed on the market.");
            case LIMIT_REACHED -> messages.error(sender, "You already have the maximum number of listings (" + r.bound() + ").");
            case BUSY -> messages.error(sender, "Your previous listing is still being processed.");
            case ITEM_CHANGED -> messages.error(sender, "The item left your inventory before it could be listed. "
                    + "Nothing was listed.");
            case ERROR -> messages.error(sender, "The listing failed because of a database error. Your item was not touched.");
            case ERROR_NEEDS_ADMIN -> messages.error(sender, "Your item was taken but the listing could not be completed. "
                    + "It is safely stored; please contact an administrator.");
        }
    }

    private int listings(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Only players have listings.");
            return 0;
        }
        if (!allowed(sender, PERM_USE)) {
            return 0;
        }
        reply(service.myListings(player.getUniqueId()), sender, "listings", list -> {
            if (list.isEmpty()) {
                messages.info(sender, "You have no active listings.");
                return;
            }
            messages.info(sender, "Your listings:");
            for (Listing l : list) {
                messages.line(sender, "#" + l.id() + " " + l.item().display() + " - " + service.format(l.priceMinor())
                        + " - " + switch (l.status()) {
                            case ACTIVE -> "expires in " + remaining(l.expiresAt());
                            case RESERVED -> "being purchased";
                            default -> "awaiting administrator";
                        });
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int browse(CommandContext<CommandSourceStack> ctx, String pageText) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_USE)) {
            return 0;
        }
        Integer page = parsePage(sender, pageText);
        if (page == null) {
            return 0;
        }
        reply(service.browse(page), sender, "browse", result -> {
            if (result.total() == 0) {
                messages.info(sender, "There are no active listings.");
                return;
            }
            messages.info(sender, "Listings (page " + result.page() + "/" + result.pages() + ", " + result.total() + " total):");
            for (Listing l : result.items()) {
                messages.line(sender, "#" + l.id() + " " + l.item().display() + " - " + service.format(l.priceMinor())
                        + " - seller " + l.ownerName() + " - " + remaining(l.expiresAt()) + " left");
            }
            messages.line(sender, "Buy with /market buy <id>");
        });
        return Command.SINGLE_SUCCESS;
    }

    private int buy(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Only players can buy.");
            return 0;
        }
        if (!allowed(sender, PERM_BUY)) {
            return 0;
        }
        Long id = parseId(sender, StringArgumentType.getString(ctx, "id"));
        if (id == null) {
            return 0;
        }
        reply(service.buy(player.getUniqueId(), player.getName(), id), sender, "buy", result -> reportBuy(sender, id, result));
        return Command.SINGLE_SUCCESS;
    }

    private void reportBuy(CommandSender sender, long id, BuyResult r) {
        switch (r.status()) {
            case PURCHASED -> {
                MarketTx tx = r.tx();
                messages.success(sender, "You bought " + r.item().display() + " (#" + id + ") for "
                        + service.format(tx.priceMinor()) + " from " + tx.sellerName() + ".");
                if (r.delivered()) {
                    messages.success(sender, "The item is in your inventory.");
                } else {
                    messages.info(sender, "Your inventory is full or delivery is pending. The item is safe; "
                            + "make room and use /market collect.");
                }
            }
            case NOT_FOUND -> messages.error(sender, "There is no listing #" + id + ".");
            case EXPIRED -> messages.error(sender, "Listing #" + id + " has expired.");
            case UNAVAILABLE -> messages.error(sender, "Listing #" + id + " is not available (" + describe(r.listingStatus())
                    + ").");
            case SELF_PURCHASE -> messages.error(sender, "You cannot buy your own listing.");
            case BUSY -> messages.error(sender, "A purchase is already in progress. Please wait a moment.");
            case INSUFFICIENT_FUNDS -> messages.error(sender, "You do not have enough money. You were not charged.");
            case NO_ACCOUNT_BUYER -> messages.error(sender, "You do not have an economy account. You were not charged.");
            case NO_ACCOUNT_SELLER -> messages.error(sender, "The seller has no economy account. You were not charged.");
            case SELLER_BALANCE_LIMIT -> messages.error(sender, "The seller cannot receive that much money right now. "
                    + "You were not charged.");
            case PRICE_EXCEEDS_ECONOMY_LIMIT -> messages.error(sender, "The economy does not allow a single payment of "
                    + "that size. You were not charged.");
            case ECONOMY_UNAVAILABLE -> messages.error(sender, "The economy could not process the payment. "
                    + "You were not charged; try again later.");
            case PAYMENT_UNCERTAIN -> messages.error(sender, "The payment result could not be confirmed. The listing is on "
                    + "hold and nothing will be retried automatically. Check your balance and tell an administrator "
                    + "(transaction #" + r.tx().id() + ").");
            case PAID_BUT_UNRECORDED -> messages.error(sender, "You were charged but the purchase could not be recorded. "
                    + "Your payment is safe - tell an administrator (transaction #" + r.tx().id() + ").");
            case ERROR -> messages.error(sender, "The purchase failed because of a database error. You were not charged.");
        }
    }

    private int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_CANCEL)) {
            return 0;
        }
        Long id = parseId(sender, StringArgumentType.getString(ctx, "id"));
        if (id == null) {
            return 0;
        }
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Only players have listings. Administrators use /market admin cancel <id> <reason>.");
            return 0;
        }
        // Ownership is enforced by the store. Administrators go through the audited /market admin cancel instead.
        CompletableFuture<CancelResult> future = service.cancel(player.getUniqueId(), false, id);
        reply(future, sender, "cancel", result -> reportCancel(sender, id, result));
        return Command.SINGLE_SUCCESS;
    }

    private void reportCancel(CommandSender sender, long id, CancelResult r) {
        switch (r.status()) {
            case CANCELLED -> {
                messages.success(sender, "Listing #" + id + " was cancelled (" + r.item().display() + ").");
                if (!r.delivered()) {
                    messages.info(sender, "The item is stored for its owner; use /market collect when there is room.");
                }
            }
            case NOT_FOUND -> messages.error(sender, "There is no listing #" + id + ".");
            case NOT_OWNER -> messages.error(sender, "Listing #" + id + " is not yours.");
            case NOT_ACTIVE -> messages.error(sender, "Listing #" + id + " cannot be cancelled (" + describe(r.listingStatus())
                    + ").");
            case ERROR -> messages.error(sender, "The cancellation failed because of a database error. Nothing changed.");
        }
    }

    private int collect(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Only players can collect items.");
            return 0;
        }
        if (!allowed(sender, PERM_COLLECT)) {
            return 0;
        }
        reply(service.collect(player.getUniqueId()), sender, "collect", result -> reportCollect(sender, result));
        return Command.SINGLE_SUCCESS;
    }

    private void reportCollect(CommandSender sender, CollectResult r) {
        if (r.error()) {
            messages.error(sender, "Collecting failed because of a database error. Nothing was lost; try again later.");
        } else if (r.delivered() == 0 && r.remaining() == 0) {
            messages.info(sender, "You have nothing to collect.");
        } else if (r.delivered() == 0) {
            messages.error(sender, "Your inventory has no room. " + r.remaining() + " item(s) are waiting.");
        } else {
            messages.success(sender, "Collected " + r.delivered() + " item(s)."
                    + (r.remaining() > 0 ? " " + r.remaining() + " more are waiting (inventory full)." : ""));
        }
    }

    private int history(CommandContext<CommandSourceStack> ctx, String pageText) {
        CommandSender sender = sender(ctx);
        if (!(sender instanceof Player player)) {
            messages.error(sender, "Only players have a history.");
            return 0;
        }
        if (!allowed(sender, PERM_USE)) {
            return 0;
        }
        Integer page = parsePage(sender, pageText);
        if (page == null) {
            return 0;
        }
        UUID self = player.getUniqueId();
        reply(service.history(self, page), sender, "history", result -> {
            if (result.total() == 0) {
                messages.info(sender, "You have no sales or purchases yet.");
                return;
            }
            messages.info(sender, "History (page " + result.page() + "/" + result.pages() + "):");
            for (MarketTx tx : result.items()) {
                boolean bought = tx.buyer().equals(self);
                messages.line(sender, Instant.ofEpochMilli(tx.completedAt()).toString().substring(0, 16).replace('T', ' ')
                        + " UTC  " + (bought ? "BOUGHT from " + tx.sellerName() : "SOLD to " + tx.buyerName())
                        + "  listing #" + tx.listingId() + "  " + service.format(tx.priceMinor())
                        + (!bought && tx.feeMinor() > 0 ? " (fee " + service.format(tx.feeMinor()) + ")" : ""));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- administration

    private int adminHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!sender.hasPermission(PERM_ADMIN_INSPECT) && !sender.hasPermission(PERM_ADMIN_RECOVER)
                && !sender.hasPermission(PERM_ADMIN_RELOAD) && !sender.hasPermission(PERM_ADMIN_CANCEL)) {
            messages.error(sender, NO_PERMISSION);
            return 0;
        }
        messages.info(sender, "Market administration:");
        messages.line(sender, "/market admin listings [page] | listing <id> | tx <id> | issues   (inspect)");
        messages.line(sender, "/market admin cancel <id> <reason>   (cancel any active listing)");
        messages.line(sender, "/market admin recover   (expire due listings, retry fees, deliver to online owners)");
        messages.line(sender, "/market admin resolve payment <tx> <paid|unpaid> <reason>");
        messages.line(sender, "/market admin resolve delivery <id> <delivered|retry> <reason>");
        messages.line(sender, "/market admin resolve escrow <listing> <activate|return|abort> <reason>");
        messages.line(sender, "/market admin resolve fee <tx> <collected|retry> <reason>");
        messages.line(sender, "/market admin reload");
        return Command.SINGLE_SUCCESS;
    }

    private int adminListings(CommandContext<CommandSourceStack> ctx, String pageText) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_INSPECT)) {
            return 0;
        }
        Integer page = parsePage(sender, pageText);
        if (page == null) {
            return 0;
        }
        reply(service.adminListings(null, page), sender, "admin listings", list -> {
            messages.info(sender, "All listings, newest first (page " + page + "):");
            for (Listing l : list) {
                messages.line(sender, "#" + l.id() + " " + l.status() + " " + l.item().display() + " "
                        + service.format(l.priceMinor()) + " owner " + l.ownerName());
            }
            if (list.isEmpty()) {
                messages.line(sender, "(nothing on this page)");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int adminListing(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_INSPECT)) {
            return 0;
        }
        Long id = parseId(sender, StringArgumentType.getString(ctx, "id"));
        if (id == null) {
            return 0;
        }
        reply(service.inspectListing(id), sender, "admin listing", view -> {
            if (view.isEmpty()) {
                messages.error(sender, "There is no listing #" + id + ".");
                return;
            }
            Listing l = view.get().listing();
            messages.info(sender, "Listing #" + l.id() + " " + l.status() + " owner " + l.ownerName() + " (" + l.owner() + ")");
            messages.line(sender, l.item().display() + " price " + service.format(l.priceMinor()));
            messages.line(sender, "created " + Instant.ofEpochMilli(l.createdAt()) + " expires "
                    + (l.expiresAt() == 0 ? "-" : Instant.ofEpochMilli(l.expiresAt()).toString()));
            Delivery d = view.get().delivery();
            if (d != null) {
                messages.line(sender, "delivery #" + d.id() + " to " + d.owner() + " " + d.reason() + " " + d.status());
            }
            for (MarketTx tx : view.get().transactions()) {
                messages.line(sender, txLine(tx));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int adminTx(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_INSPECT)) {
            return 0;
        }
        Long id = parseId(sender, StringArgumentType.getString(ctx, "id"));
        if (id == null) {
            return 0;
        }
        reply(service.inspectTx(id), sender, "admin tx", tx -> {
            if (tx.isEmpty()) {
                messages.error(sender, "There is no transaction #" + id + ".");
                return;
            }
            MarketTx t = tx.get();
            messages.info(sender, txLine(t));
            messages.line(sender, "buyer " + t.buyerName() + " (" + t.buyer() + ") seller " + t.sellerName() + " ("
                    + t.seller() + ")");
            messages.line(sender, "created " + Instant.ofEpochMilli(t.createdAt()) + " updated "
                    + Instant.ofEpochMilli(t.updatedAt()) + (t.failureReason() == null ? "" : " reason: " + t.failureReason()));
        });
        return Command.SINGLE_SUCCESS;
    }

    private String txLine(MarketTx t) {
        return "tx #" + t.id() + " listing #" + t.listingId() + " " + t.state() + " fee " + service.format(t.feeMinor())
                + " (" + t.feeState() + ") price " + service.format(t.priceMinor());
    }

    private int adminIssues(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_INSPECT)) {
            return 0;
        }
        reply(service.issues(), sender, "admin issues", issues -> {
            if (issues.isEmpty()) {
                messages.success(sender, "Nothing needs attention.");
                return;
            }
            for (Listing l : issues.escrows()) {
                messages.line(sender, "ESCROW listing #" + l.id() + " " + l.status() + " owner " + l.ownerName() + " "
                        + l.item().display() + "  -> resolve escrow " + l.id() + " <activate|return|abort>");
            }
            for (MarketTx t : issues.payments()) {
                messages.line(sender, "PAYMENT " + txLine(t) + "  buyer " + t.buyerName() + " seller " + t.sellerName()
                        + "  -> resolve payment " + t.id() + " <paid|unpaid>");
            }
            for (Delivery d : issues.deliveries()) {
                messages.line(sender, "DELIVERY #" + d.id() + " listing #" + d.listingId() + " owner " + d.owner() + " "
                        + d.item().display() + "  -> resolve delivery " + d.id() + " <delivered|retry>");
            }
            for (MarketTx t : issues.fees()) {
                messages.line(sender, "FEE " + txLine(t) + "  -> resolve fee " + t.id() + " <collected|retry>");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int adminCancel(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_CANCEL)) {
            return 0;
        }
        Long id = parseId(sender, StringArgumentType.getString(ctx, "id"));
        String reason = reason(sender, ctx);
        if (id == null || reason == null) {
            return 0;
        }
        UUID actor = sender instanceof Player p ? p.getUniqueId() : CONSOLE_ID;
        reply(service.adminCancel(actor, sender.getName(), id, reason), sender, "admin cancel",
                result -> reportCancel(sender, id, result));
        return Command.SINGLE_SUCCESS;
    }

    private int adminRecover(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_RECOVER)) {
            return 0;
        }
        reply(service.sweep(), sender, "admin recover", expired -> {
            logger.info("ADMIN actor=" + sender.getName() + " action=recover expired=" + expired);
            messages.success(sender, "Sweep finished: " + expired + " listing(s) expired. Use /market admin issues for "
                    + "anything that needs a decision.");
        });
        return Command.SINGLE_SUCCESS;
    }

    private int adminReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_RELOAD)) {
            return 0;
        }
        reply(config.reload(), sender, "admin reload", result -> {
            if (result.success()) {
                logger.info("ADMIN actor=" + sender.getName() + " action=reload applied=true");
                messages.success(sender, "Configuration reloaded.");
            } else {
                messages.error(sender, "Reload rejected; the previous configuration stays active:");
                result.problems().forEach(problem -> messages.line(sender, " - " + problem));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int resolve(CommandContext<CommandSourceStack> ctx, String kind) {
        CommandSender sender = sender(ctx);
        if (!allowed(sender, PERM_ADMIN_RECOVER)) {
            return 0;
        }
        Long id = parseId(sender, StringArgumentType.getString(ctx, "id"));
        String outcome = StringArgumentType.getString(ctx, "outcome").toLowerCase(Locale.ROOT);
        String reason = reason(sender, ctx);
        if (id == null || reason == null) {
            return 0;
        }
        String actor = sender.getName();
        CompletableFuture<Boolean> future;
        switch (kind) {
            case "payment" -> {
                if (!oneOf(sender, outcome, "paid", "unpaid")) {
                    return 0;
                }
                future = service.resolvePayment(actor, id, outcome.equals("paid"), reason);
            }
            case "delivery" -> {
                if (!oneOf(sender, outcome, "delivered", "retry")) {
                    return 0;
                }
                future = service.resolveDelivery(actor, id, outcome.equals("delivered"), reason);
            }
            case "escrow" -> {
                EscrowResolution resolution = switch (outcome) {
                    case "activate" -> EscrowResolution.ACTIVATE;
                    case "return" -> EscrowResolution.RETURN;
                    case "abort" -> EscrowResolution.ABORT;
                    default -> null;
                };
                if (resolution == null) {
                    messages.error(sender, "The outcome must be 'activate', 'return' or 'abort'.");
                    return 0;
                }
                future = service.resolveEscrow(actor, id, resolution, reason);
            }
            default -> {
                if (!oneOf(sender, outcome, "collected", "retry")) {
                    return 0;
                }
                future = service.resolveFee(actor, id, outcome.equals("collected"), reason);
            }
        }
        reply(future, sender, "admin resolve " + kind, applied -> {
            if (applied) {
                messages.success(sender, "Resolved " + kind + " #" + id + " as '" + outcome + "'. Recorded in the audit log.");
            } else {
                messages.error(sender, "Nothing changed: " + kind + " #" + id + " does not exist or is not in an uncertain state.");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private boolean oneOf(CommandSender sender, String outcome, String first, String second) {
        if (outcome.equals(first) || outcome.equals(second)) {
            return true;
        }
        messages.error(sender, "The outcome must be '" + first + "' or '" + second + "'.");
        return false;
    }

    private String reason(CommandSender sender, CommandContext<CommandSourceStack> ctx) {
        String reason = StringArgumentType.getString(ctx, "reason").trim();
        if (reason.length() < MIN_REASON_LENGTH) {
            messages.error(sender, "Give a reason of at least " + MIN_REASON_LENGTH + " characters; it is written to the audit log.");
            return null;
        }
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    // ---------------------------------------------------------------- helpers

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages.error(sender, NO_PERMISSION);
        return false;
    }

    private Long parseId(CommandSender sender, String text) {
        try {
            long id = Long.parseLong(text);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        messages.error(sender, "'" + text + "' is not a valid listing id.");
        return null;
    }

    private Integer parsePage(CommandSender sender, String text) {
        try {
            int page = Integer.parseInt(text);
            if (page >= 1) {
                return page;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        messages.error(sender, "'" + text + "' is not a valid page number.");
        return null;
    }

    private static String describe(ListingStatus status) {
        if (status == null) {
            return "unavailable";
        }
        return switch (status) {
            case SOLD -> "already sold";
            case CANCELLED -> "cancelled";
            case EXPIRED -> "expired";
            case RESERVED -> "someone is buying it right now";
            case ACTIVE -> "available";
            default -> "not on sale";
        };
    }

    private String remaining(long expiresAt) {
        long minutes = Math.max(0, (expiresAt - clock.millis()) / 60_000);
        long days = minutes / (60 * 24);
        long hours = (minutes / 60) % 24;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    /** Reports the outcome on the main thread; unexpected failures become a generic message plus a console stack trace. */
    private <T> void reply(CompletableFuture<T> future, CommandSender sender, String what, Consumer<T> onSuccess) {
        future.whenComplete((value, error) -> mainThread.execute(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                logger.log(Level.SEVERE, "Market command '" + what + "' failed", cause);
                messages.error(sender, INTERNAL_ERROR);
                return;
            }
            try {
                onSuccess.accept(value);
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, "Reporting '" + what + "' failed", e);
            }
        }));
    }
}
