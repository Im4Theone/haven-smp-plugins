package net.kaloyan.smpmarket.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kaloyan.smpmarket.config.ConfigManager;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.model.TxState;
import net.kaloyan.smpmarket.testutil.FakeItems;
import net.kaloyan.smpmarket.testutil.Fixture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Mocked integration tests: real Brigadier tree, real service and SQLite, fake economy and inventory, mocked Bukkit
 * senders. These do not run inside a Paper server.
 */
class MarketCommandsTest {

    @TempDir
    Path dir;

    Fixture f;
    CommandDispatcher<CommandSourceStack> dispatcher;
    final ItemSnapshot sword = FakeItems.item("minecraft:diamond_sword", 1, "sword");

    @BeforeEach
    void setUp() throws Exception {
        f = new Fixture(dir);
        rebuild();
    }

    /** (Re)builds the command tree against the fixture's current service, e.g. after a simulated restart. */
    private void rebuild() throws Exception {
        Path config = dir.resolve("config.yml");
        Files.writeString(config, "");
        ConfigManager manager = new ConfigManager(config, Runnable::run, f.log.logger());
        manager.loadInitial();
        MarketCommands commands = new MarketCommands(f.service, manager, new Messages(() -> "<gray>[T]</gray>"),
                Runnable::run, f.clock, f.log.logger());
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(commands.build());
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    /** A mocked player holding exactly {@code permissions}; messages are appended to {@code inbox}. */
    private Player player(UUID id, String name, List<String> inbox, String... permissions) {
        Player p = mock(Player.class);
        Set<String> granted = Set.of(permissions);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.hasPermission(anyString())).thenAnswer(inv -> granted.contains(inv.<String>getArgument(0)));
        record(p, inbox);
        return p;
    }

    private static void record(CommandSender sender, List<String> inbox) {
        doAnswer(inv -> {
            inbox.add(PlainTextComponentSerializer.plainText().serialize(inv.<Component>getArgument(0)));
            return null;
        }).when(sender).sendMessage(any(Component.class));
    }

    private void run(CommandSender sender, String command) throws CommandSyntaxException {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        dispatcher.execute(command, source);
    }

    private static final String[] PLAYER_PERMS = {"smpmarket.use", "smpmarket.sell", "smpmarket.buy", "smpmarket.cancel",
        "smpmarket.collect"};

    /** Creates an uncertain payment: a reserved listing with a crashed purchase. */
    private MarketTx uncertainPayment() {
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = f.store.beginPurchase(id, f.buyer, "Buyer", false, 0, f.clock.millis()).tx();
        f.crashAndRestart();
        try {
            rebuild();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return f.store.findTx(tx.id()).orElseThrow();
    }

    @Test
    void ordinaryPlayersCannotInspectOrMutateAnythingAdministrative() throws Exception {
        MarketTx tx = uncertainPayment();
        List<String> inbox = new ArrayList<>();
        Player nobody = player(f.other, "Nobody", inbox, PLAYER_PERMS);

        for (String cmd : List.of("market admin issues", "market admin listings", "market admin listing 1",
                "market admin tx " + tx.id(), "market admin reload", "market admin recover",
                "market admin cancel 1 because I feel like it",
                "market admin resolve payment " + tx.id() + " paid because I said so",
                "market admin resolve delivery 1 retry because I said so",
                "market admin resolve escrow 1 activate because I said so",
                "market admin resolve fee 1 retry because I said so")) {
            inbox.clear();
            run(nobody, cmd);
            assertTrue(inbox.stream().anyMatch(m -> m.contains("permission")),
                    "'" + cmd + "' should be refused, got " + inbox);
        }

        assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(tx.id()).orElseThrow().state());
        assertEquals(ListingStatus.RESERVED, f.store.findListing(tx.listingId()).orElseThrow().status());
        assertEquals(0, f.store.auditCount(), "nothing was recorded because nothing happened");
    }

    @Test
    void inspectPermissionAloneCannotResolve() throws Exception {
        MarketTx tx = uncertainPayment();
        List<String> inbox = new ArrayList<>();
        Player inspector = player(f.other, "Inspector", inbox, "smpmarket.admin.inspect");

        run(inspector, "market admin issues");
        assertTrue(inbox.stream().anyMatch(m -> m.contains("PAYMENT")), inbox.toString());

        inbox.clear();
        run(inspector, "market admin resolve payment " + tx.id() + " paid verified by balances");
        assertTrue(inbox.stream().anyMatch(m -> m.contains("permission")));
        assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(tx.id()).orElseThrow().state());
    }

    @Test
    void authorizedAdministratorCanResolveAndItIsAudited() throws Exception {
        MarketTx tx = uncertainPayment();
        List<String> inbox = new ArrayList<>();
        Player admin = player(f.other, "Admin", inbox, "smpmarket.admin.inspect", "smpmarket.admin.recover");

        run(admin, "market admin resolve payment " + tx.id() + " paid verified by balances");

        assertTrue(inbox.stream().anyMatch(m -> m.contains("Resolved payment")), inbox.toString());
        assertEquals(TxState.COMPLETED, f.store.findTx(tx.id()).orElseThrow().state(), "the online buyer was handed the item");
        assertEquals(1, f.store.auditCount());
        assertTrue(f.store.auditTrail().get(0).startsWith("Admin|RESOLVE_PAYMENT_PAID|" + tx.listingId() + "|" + tx.id()));
        assertTrue(f.log.allMessages().stream().anyMatch(m -> m.contains("ADMIN actor=Admin")
                && m.contains("tx=" + tx.id()) && m.contains("verified by balances")), "logged with actor, tx and reason");
    }

    @Test
    void administrativeResolutionsRequireARealReason() throws Exception {
        MarketTx tx = uncertainPayment();
        List<String> inbox = new ArrayList<>();
        Player admin = player(f.other, "Admin", inbox, "smpmarket.admin.recover");

        run(admin, "market admin resolve payment " + tx.id() + " paid ok");
        assertTrue(inbox.stream().anyMatch(m -> m.contains("reason")), inbox.toString());
        run(admin, "market admin resolve payment " + tx.id() + " maybe because reasons");
        assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(tx.id()).orElseThrow().state());
    }

    @Test
    void consoleMayAdministerButCannotSell() throws Exception {
        MarketTx tx = uncertainPayment();
        List<String> inbox = new ArrayList<>();
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        when(console.getName()).thenReturn("CONSOLE");
        record(console, inbox);

        run(console, "market sell 10");
        assertTrue(inbox.stream().anyMatch(m -> m.contains("Only players")));
        run(console, "market admin resolve payment " + tx.id() + " unpaid verified by balances");
        assertEquals(TxState.FAILED, f.store.findTx(tx.id()).orElseThrow().state());
    }

    @Test
    void invalidArgumentsAreRejectedBeforeTouchingAnything() throws Exception {
        List<String> inbox = new ArrayList<>();
        Player p = player(f.seller, "Seller", inbox, PLAYER_PERMS);
        f.items.player(f.seller).hand = sword;

        for (String cmd : List.of("market sell 0", "market sell -5", "market sell 1.234", "market sell abc",
                "market sell 1e3", "market sell 99999999999999999999", "market buy abc", "market buy -1",
                "market buy 0", "market cancel x", "market browse 0", "market browse x", "market history -1")) {
            inbox.clear();
            run(p, cmd);
            assertFalse(inbox.isEmpty(), "'" + cmd + "' must explain the problem");
            assertTrue(inbox.stream().noneMatch(m -> m.contains("Listed")), cmd);
        }
        assertEquals(sword, f.items.player(f.seller).hand, "the item was never touched");
        assertEquals(0, f.store.adminListings(null, 0, 10).size());
    }

    @Test
    void playersCanSellBrowseBuyAndCancelThroughCommands() throws Exception {
        List<String> sellerInbox = new ArrayList<>();
        List<String> buyerInbox = new ArrayList<>();
        Player seller = player(f.seller, "Seller", sellerInbox, PLAYER_PERMS);
        Player buyer = player(f.buyer, "Buyer", buyerInbox, PLAYER_PERMS);
        f.items.player(f.seller).hand = sword;

        run(seller, "market sell 12.50");
        assertTrue(sellerInbox.stream().anyMatch(m -> m.contains("Listed") && m.contains("$12.50")), sellerInbox.toString());

        run(buyer, "market browse");
        assertTrue(buyerInbox.stream().anyMatch(m -> m.contains("#1") && m.contains("seller Seller")), buyerInbox.toString());

        run(buyer, "market buy 1");
        assertTrue(buyerInbox.stream().anyMatch(m -> m.contains("You bought")), buyerInbox.toString());
        assertEquals(1, f.items.count(sword));

        buyerInbox.clear();
        run(seller, "market cancel 1");
        assertTrue(sellerInbox.stream().anyMatch(m -> m.contains("cannot be cancelled")), sellerInbox.toString());
        run(buyer, "market history");
        assertTrue(buyerInbox.stream().anyMatch(m -> m.contains("BOUGHT from Seller")), buyerInbox.toString());
    }

    @Test
    void anotherPlayersListingCannotBeCancelledThroughTheNormalCommand() throws Exception {
        long id = f.list(f.seller, sword, 1000);
        List<String> inbox = new ArrayList<>();
        // Even a player holding the admin permission must use the audited admin command.
        Player other = player(f.other, "Other", inbox, "smpmarket.cancel", "smpmarket.admin.cancel");

        run(other, "market cancel " + id);

        assertTrue(inbox.stream().anyMatch(m -> m.contains("not yours")), inbox.toString());
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
    }

    @Test
    void permissionsDeclaredInPluginYmlMatchTheOnesTheCodeEnforces() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/plugin.yml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String node : List.of(MarketCommands.PERM_USE, MarketCommands.PERM_SELL, MarketCommands.PERM_BUY,
                MarketCommands.PERM_CANCEL, MarketCommands.PERM_COLLECT, MarketCommands.PERM_ADMIN_INSPECT,
                MarketCommands.PERM_ADMIN_CANCEL, MarketCommands.PERM_ADMIN_RECOVER, MarketCommands.PERM_ADMIN_RELOAD)) {
            assertTrue(yml.contains("  " + node + ":"), node + " is missing from plugin.yml");
        }
        assertTrue(yml.contains("depend: [SMP-Core, SMP-Economy]"));
        assertTrue(yml.contains("name: SMP-Market"));
    }
}
