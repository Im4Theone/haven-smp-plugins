package net.kaloyan.smpeconomy.command;

import static net.kaloyan.smpeconomy.testutil.TestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kaloyan.smpeconomy.config.ConfigManager;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.log.DebugLogger;
import net.kaloyan.smpeconomy.service.EconomyServiceImpl;
import net.kaloyan.smpeconomy.storage.EconomyStore;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Command-level unit tests: real SQLite store and service, real Brigadier trees, mocked Bukkit senders and
 * server. These do not run inside a Paper server (see README for the manual server smoke test).
 */
class EconomyCommandsTest {

    private static final String PAY = EconomyCommands.PERM_PAY;
    private static final String BAL = EconomyCommands.PERM_BALANCE;
    private static final String BAL_OTHERS = EconomyCommands.PERM_BALANCE_OTHERS;

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final List<Runnable> ioTasks = new ArrayList<>();
    private final Deque<Runnable> storageQueue = new ArrayDeque<>();
    private final Map<String, Player> online = new HashMap<>();
    private final Map<UUID, Player> onlineById = new HashMap<>();
    private boolean deferStorage;

    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private ConfigManager config;
    private EconomyStore store;
    private EconomyServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.writeConfig(dir, """
                currency:
                  symbol: "$"
                balances:
                  starting: "100.00"
                  maximum: "1000.00"
                transactions:
                  maximum-amount: "500.00"
                """);
        config = new ConfigManager(dir.resolve("config.yml"), ioTasks::add, log.logger());
        config.loadInitial();
        store = TestSupport.openStore(dir, config::current, log);
        service = new EconomyServiceImpl(store, task -> {
            if (deferStorage) {
                storageQueue.add(task);
            } else {
                task.run();
            }
        }, config::current, new DebugLogger(log.logger(), config::current));

        Server server = mock(Server.class);
        when(server.getPlayerExact(anyString())).thenAnswer(
                invocation -> online.get(((String) invocation.getArgument(0)).toLowerCase(Locale.ROOT)));
        when(server.getPlayer(any(UUID.class))).thenAnswer(invocation -> onlineById.get(invocation.<UUID>getArgument(0)));
        doReturn(online.values()).when(server).getOnlinePlayers();

        EconomyCommands commands = new EconomyCommands(server, service, config,
                new Messages(() -> "<dark_aqua>[SMP]</dark_aqua>"), Runnable::run, log.logger());
        dispatcher.getRoot().addChild(commands.buildBalance());
        dispatcher.getRoot().addChild(commands.buildPay());
        dispatcher.getRoot().addChild(commands.buildEco());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    // ---------------------------------------------------------------- fixtures

    /** An online player that has joined (account created) and holds exactly {@code permissions}. */
    private Player player(String name, String... permissions) {
        return player(name, true, permissions);
    }

    private Player player(String name, boolean hasAccount, String... permissions) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getName()).thenReturn(name);
        when(player.getUniqueId()).thenReturn(id);
        for (String permission : permissions) {
            when(player.hasPermission(permission)).thenReturn(true);
        }
        online.put(name.toLowerCase(Locale.ROOT), player);
        onlineById.put(id, player);
        if (hasAccount) {
            store.initializeAccount(id, name);
        }
        return player;
    }

    /** A player who is not online but has an account. */
    private UUID offlineAccount(String name, String balance) {
        return TestSupport.account(store, name, balance);
    }

    private static CommandSender console() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(console.hasPermission(anyString())).thenReturn(true);
        return console;
    }

    private int run(CommandSender sender, String input) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return dispatcher.execute(input, source);
    }

    private static String sent(CommandSender sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .reduce("", (a, b) -> a + b + "\n");
    }

    private BigDecimal balance(Player player) {
        return store.balance(player.getUniqueId()).orElseThrow();
    }

    private void assertBalance(String expected, Player player) {
        assertEquals(0, money(expected).compareTo(balance(player)), "balance of " + player.getName() + " was " + balance(player));
    }

    private void drainStorage() {
        while (!storageQueue.isEmpty()) {
            storageQueue.poll().run();
        }
    }

    private void runIo() {
        List<Runnable> tasks = new ArrayList<>(ioTasks);
        ioTasks.clear();
        tasks.forEach(Runnable::run);
    }

    // ---------------------------------------------------------------- /balance

    @Test
    void balanceShowsTheSendersOwnBalanceWithThePrefix() throws Exception {
        Player alex = player("Alex", BAL);

        assertEquals(1, run(alex, "balance"));

        String output = sent(alex);
        assertTrue(output.startsWith("[SMP] "), output);
        assertTrue(output.contains("Your balance: $100.00"), output);
    }

    @Test
    void balanceIsDeniedWithoutThePermission() throws Exception {
        Player alex = player("Alex");

        assertEquals(0, run(alex, "balance"));

        assertTrue(sent(alex).contains(BAL), sent(alex));
        assertFalse(sent(alex).contains("$"), "a denied command must reveal nothing");
    }

    @Test
    void balanceOfAnotherPlayerNeedsTheDedicatedPermission() throws Exception {
        Player alex = player("Alex", BAL);
        player("Bob");
        UUID cara = offlineAccount("Cara", "42.50");

        assertEquals(0, run(alex, "balance Bob"));
        assertEquals(0, run(alex, "balance Cara"));

        assertTrue(sent(alex).contains(BAL_OTHERS), sent(alex));
        assertFalse(sent(alex).contains("42.50"), sent(alex));
        assertTrue(store.hasAccount(cara));
    }

    @Test
    void balanceOfAnotherPlayerWorksForOnlineAndOfflinePlayersWhenPermitted() throws Exception {
        Player alex = player("Alex", BAL, BAL_OTHERS);
        player("Bob");
        offlineAccount("Cara", "42.50");

        assertEquals(1, run(alex, "balance Bob"));
        assertEquals(1, run(alex, "balance cara")); // offline, resolved through the stored name, case-insensitive

        String output = sent(alex);
        assertTrue(output.contains("Bob's balance: $100.00"), output);
        assertTrue(output.contains("Cara's balance: $42.50"), output);
    }

    @Test
    void lookingUpAnUnknownPlayerNeverCreatesAnAccount() throws Exception {
        Player alex = player("Alex", BAL, BAL_OTHERS);

        run(alex, "balance Nobody");
        run(alex, "balance " + UUID.randomUUID());

        assertTrue(sent(alex).contains("No economy account is known for 'Nobody'"), sent(alex));
        assertTrue(store.findAccountByName("Nobody").isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM accounts"), "only Alex's own account may exist");
    }

    @Test
    void anOnlinePlayerWithoutAnAccountIsReportedNotCreated() throws Exception {
        Player alex = player("Alex", BAL, BAL_OTHERS);
        Player ghost = player("Ghost", false);

        run(alex, "balance Ghost");

        assertTrue(sent(alex).contains("Ghost has no economy account"), sent(alex));
        assertFalse(store.hasAccount(ghost.getUniqueId()));
    }

    @Test
    void aPlayerMayLookThemselvesUpByNameWithoutTheOthersPermission() throws Exception {
        Player alex = player("Alex", BAL);

        assertEquals(1, run(alex, "balance alex"));

        assertTrue(sent(alex).contains("Your balance: $100.00"), sent(alex));
    }

    @Test
    void consoleMustNameAPlayerButCanLookOneUp() throws Exception {
        CommandSender console = console();
        player("Bob");

        assertEquals(0, run(console, "balance"));
        assertEquals(1, run(console, "balance Bob"));

        assertTrue(sent(console).contains("Usage: /balance <player>"), sent(console));
        assertTrue(sent(console).contains("Bob's balance: $100.00"), sent(console));
    }

    @Test
    void ownBalanceReportsAMissingAccountInsteadOfZero() throws Exception {
        Player alex = player("Alex", false, BAL);

        run(alex, "balance");

        assertTrue(sent(alex).contains("do not have an economy account"), sent(alex));
        assertFalse(store.hasAccount(alex.getUniqueId()), "querying must not create the account");
    }

    // ---------------------------------------------------------------- /pay

    @Test
    void payMovesMoneyAtomicallyAndNotifiesBothPlayers() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);

        assertEquals(1, run(alex, "pay Bob 25.50"));

        assertBalance("74.50", alex);
        assertBalance("125.50", bob);
        assertTrue(sent(alex).contains("You paid Bob $25.50. Your new balance: $74.50"), sent(alex));
        assertTrue(sent(bob).contains("Alex paid you $25.50. Your new balance: $125.50"), sent(bob));
    }

    @Test
    void payToAnOfflinePlayerWorksThroughTheStoredName() throws Exception {
        Player alex = player("Alex", PAY);
        UUID cara = offlineAccount("Cara", "10.00");

        assertEquals(1, run(alex, "pay CARA 5"));

        assertEquals(0, money("15.00").compareTo(store.balance(cara).orElseThrow()));
        assertTrue(sent(alex).contains("You paid Cara $5.00"), sent(alex));
    }

    @Test
    void successIsNotReportedUntilTheTransactionHasRun() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);
        deferStorage = true;

        assertEquals(1, run(alex, "pay Bob 10"));

        assertEquals("", sent(alex), "no message may precede the commit");
        assertEquals("", sent(bob));
        assertBalance("100.00", alex);

        drainStorage();

        assertTrue(sent(alex).contains("You paid Bob $10.00"), sent(alex));
        assertBalance("90.00", alex);
        assertBalance("110.00", bob);
    }

    @Test
    void aSecondPayWhileTheFirstIsStillInFlightIsRefusedSoNothingIsPaidTwice() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);
        deferStorage = true;

        assertEquals(1, run(alex, "pay Bob 10"));
        assertEquals(0, run(alex, "pay Bob 10"));
        assertEquals(0, run(alex, "pay Bob 10"));
        drainStorage();

        assertBalance("90.00", alex);
        assertBalance("110.00", bob);
        assertTrue(sent(alex).contains("still being processed"), sent(alex));

        // Once the payment has finished the player can pay again.
        deferStorage = false;
        assertEquals(1, run(alex, "pay Bob 10"));
        assertBalance("80.00", alex);
    }

    @Test
    void theInFlightGuardIsReleasedEvenWhenThePaymentFails() throws Exception {
        Player alex = player("Alex", PAY);
        player("Bob", PAY);

        assertEquals(1, run(alex, "pay Bob 400"));  // insufficient funds
        assertEquals(1, run(alex, "pay Bob 10"));   // must not be blocked by the earlier failure

        assertTrue(sent(alex).contains("do not have enough money"), sent(alex));
        assertTrue(sent(alex).contains("You paid Bob $10.00"), sent(alex));
    }

    @Test
    void payIsRejectedForEveryInvalidAmountAndMovesNothing() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);

        for (String bad : new String[] {"abc", "-5", "0", "0.00", "1.005", "1e3", "1,000", "5 extra", "99999999999999999999", "NaN"}) {
            assertEquals(0, run(alex, "pay Bob " + bad), bad);
        }

        assertBalance("100.00", alex);
        assertBalance("100.00", bob);
        assertEquals(10, sent(alex).lines().count(), "one error line per rejected command: " + sent(alex));
    }

    @Test
    void payRejectsInsufficientFundsSelfPaymentAndUnknownRecipients() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);

        run(alex, "pay Bob 100.01");
        run(alex, "pay alex 5");
        run(alex, "pay Nobody 5");
        run(alex, "pay " + alex.getUniqueId() + " 5");

        String output = sent(alex);
        assertTrue(output.contains("do not have enough money to pay $100.01"), output);
        assertEquals(2, output.split("cannot pay yourself", -1).length - 1, output);
        assertTrue(output.contains("No economy account is known for 'Nobody'"), output);
        assertBalance("100.00", alex);
        assertBalance("100.00", bob);
        assertTrue(store.findAccountByName("Nobody").isEmpty());
    }

    @Test
    void payEnforcesTheTransactionAndBalanceLimits() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);
        store.setBalance(alex.getUniqueId(), money("1000.00"));
        store.setBalance(bob.getUniqueId(), money("900.00"));

        run(alex, "pay Bob 500.01");
        run(alex, "pay Bob 100.01");

        assertTrue(sent(alex).contains("at most $500.00"), sent(alex));
        assertTrue(sent(alex).contains("cannot receive that much"), sent(alex));
        assertBalance("1000.00", alex);
        assertBalance("900.00", bob);
    }

    @Test
    void payIsDeniedWithoutThePermissionAndConsoleCannotPay() throws Exception {
        Player alex = player("Alex");
        Player bob = player("Bob", PAY);
        CommandSender console = console();

        assertEquals(0, run(alex, "pay Bob 5"));
        assertEquals(0, run(console, "pay Bob 5"));

        assertTrue(sent(alex).contains(PAY), sent(alex));
        assertTrue(sent(console).contains("Only players can pay"), sent(console));
        assertBalance("100.00", alex);
        assertBalance("100.00", bob);
    }

    @Test
    void payWithMissingArgumentsShowsUsage() throws Exception {
        Player alex = player("Alex", PAY);

        run(alex, "pay");
        run(alex, "pay Bob");

        assertEquals(2, sent(alex).split("Usage: /pay <player> <amount>", -1).length - 1, sent(alex));
    }

    @Test
    void paymentFromAPlayerWithoutAnAccountFailsClearly() throws Exception {
        Player alex = player("Alex", false, PAY);
        Player bob = player("Bob", PAY);

        run(alex, "pay Bob 5");

        assertTrue(sent(alex).contains("do not have an economy account"), sent(alex));
        assertBalance("100.00", bob);
        assertFalse(store.hasAccount(alex.getUniqueId()));
    }

    @Test
    void aDatabaseFailureIsReportedAsAFailureNeverAsASuccess() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);
        UUID alexId = alex.getUniqueId();
        UUID bobId = bob.getUniqueId();
        store.close();

        run(alex, "pay Bob 5");

        String output = sent(alex);
        assertFalse(output.contains("You paid"), output);
        assertTrue(output.contains("Something went wrong") || output.contains("database error"), output);
        assertEquals("", sent(bob), "the recipient must not be told about a payment that did not happen");
        store = TestSupport.openStore(dir, config::current, log);
        assertEquals(0, money("100.00").compareTo(store.balance(alexId).orElseThrow()));
        assertEquals(0, money("100.00").compareTo(store.balance(bobId).orElseThrow()));
    }

    // ---------------------------------------------------------------- /eco permissions

    @Test
    void ordinaryPlayersCannotSeeOrRunAnyAdminSubcommandAndNothingChanges() throws Exception {
        Player alex = player("Alex", BAL, PAY);
        Player bob = player("Bob");

        // The /eco command is hidden from players without any admin permission, so it does not even parse for them.
        for (String command : new String[] {"eco balance Bob", "eco set Bob 999", "eco add Bob 50", "eco take Bob 50",
                "eco reload", "eco", "eco bogus"}) {
            org.junit.jupiter.api.Assertions.assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                    () -> run(alex, command), command);
        }

        assertBalance("100.00", bob);
        assertTrue(ioTasks.isEmpty(), "a denied reload must not even read the file");
        assertEquals("", sent(alex), "nothing is revealed to the player");
        assertTrue(log.messages(Level.INFO).stream().noneMatch(m -> m.contains("[ECO]")));
    }

    @Test
    void eachAdminPermissionGrantsOnlyItsOwnSubcommand() throws Exception {
        Player addOnly = player("AddOnly", EconomyCommands.PERM_ADMIN_ADD);
        Player bob = player("Bob");

        assertEquals(1, run(addOnly, "eco add Bob 10"));
        assertEquals(0, run(addOnly, "eco take Bob 10"));
        assertEquals(0, run(addOnly, "eco set Bob 0"));
        assertEquals(0, run(addOnly, "eco balance Bob"));
        assertEquals(0, run(addOnly, "eco reload"));

        assertBalance("110.00", bob);
    }

    @Test
    void usageListsOnlyWhatTheSenderMayDo() throws Exception {
        Player addOnly = player("AddOnly", EconomyCommands.PERM_ADMIN_ADD);

        run(addOnly, "eco");
        run(addOnly, "eco nonsense");

        String output = sent(addOnly);
        assertTrue(output.contains("/eco add <player> <amount>"), output);
        assertFalse(output.contains("/eco take"), output);
        assertTrue(output.contains("Unknown subcommand 'nonsense'"), output);
    }

    // ---------------------------------------------------------------- /eco operations

    @Test
    void ecoBalanceShowsTheUuidAndBalanceToAdminsAndConsole() throws Exception {
        Player admin = player("Admin", EconomyCommands.PERM_ADMIN_BALANCE);
        Player bob = player("Bob");
        CommandSender console = console();

        assertEquals(1, run(admin, "eco balance bob"));
        assertEquals(1, run(console, "eco balance Bob"));
        run(console, "eco balance Nobody");

        assertTrue(sent(admin).contains("Bob (" + bob.getUniqueId() + ") has $100.00"), sent(admin));
        assertTrue(sent(console).contains("Bob (" + bob.getUniqueId() + ") has $100.00"), sent(console));
        assertTrue(sent(console).contains("No economy account is known for 'Nobody'"), sent(console));
    }

    @Test
    void ecoAddTakeAndSetChangeBalancesAndAreLoggedWithActorTargetAndAmount() throws Exception {
        Player admin = player("Admin", EconomyCommands.PERM_ADMIN_ADD, EconomyCommands.PERM_ADMIN_TAKE,
                EconomyCommands.PERM_ADMIN_SET);
        Player bob = player("Bob");

        assertEquals(1, run(admin, "eco add Bob 50.25"));
        assertBalance("150.25", bob);
        assertEquals(1, run(admin, "eco take Bob 0.25"));
        assertBalance("150.00", bob);
        assertEquals(1, run(admin, "eco set Bob 12.34"));
        assertBalance("12.34", bob);

        String output = sent(admin);
        assertTrue(output.contains("Added $50.25 to Bob (" + bob.getUniqueId() + "). New balance: $150.25."), output);
        assertTrue(output.contains("Took $0.25 from Bob"), output);
        assertTrue(output.contains("Set the balance of Bob (" + bob.getUniqueId() + ") to $12.34."), output);
        String actor = "Admin (" + admin.getUniqueId() + ")";
        List<String> logged = log.messages(Level.INFO).stream().filter(m -> m.startsWith("[ECO]")).toList();
        assertEquals(3, logged.size(), logged.toString());
        assertTrue(logged.get(0).contains(actor) && logged.get(0).contains("added $50.25")
                && logged.get(0).contains(bob.getUniqueId().toString()), logged.get(0));
        assertTrue(logged.get(1).contains(actor) && logged.get(1).contains("took $0.25"), logged.get(1));
        assertTrue(logged.get(2).contains(actor) && logged.get(2).contains("set the balance") && logged.get(2).contains("$12.34"),
                logged.get(2));
        assertTrue(sent(bob).contains("An administrator changed your balance. New balance: $12.34"), sent(bob));
    }

    @Test
    void consoleCanRunAdminCommandsAndIsNamedInTheLog() throws Exception {
        CommandSender console = console();
        Player bob = player("Bob");

        assertEquals(1, run(console, "eco add Bob 5"));

        assertBalance("105.00", bob);
        assertTrue(log.messages(Level.INFO).stream().anyMatch(m -> m.startsWith("[ECO] CONSOLE added $5.00 to Bob")),
                log.allMessages().toString());
    }

    @Test
    void ecoWorksOnOfflinePlayersByNameOrUuid() throws Exception {
        CommandSender console = console();
        UUID cara = offlineAccount("Cara", "10.00");

        assertEquals(1, run(console, "eco add cara 1"));
        assertEquals(1, run(console, "eco add " + cara + " 1"));

        assertEquals(0, money("12.00").compareTo(store.balance(cara).orElseThrow()));
    }

    @Test
    void ecoNeverPermitsANegativeOrOversizedBalanceAndFailuresAreNotLoggedAsChanges() throws Exception {
        CommandSender console = console();
        Player bob = player("Bob");

        run(console, "eco set Bob -1");
        run(console, "eco set Bob 1000.01");
        run(console, "eco set Bob 1.234");
        run(console, "eco take Bob 100.01");
        run(console, "eco add Bob 900.01");
        run(console, "eco add Bob 0");
        run(console, "eco take Bob -5");
        run(console, "eco add Bob lots");

        assertBalance("100.00", bob);
        String output = sent(console);
        assertTrue(output.contains("cannot be negative"), output);
        assertTrue(output.contains("above the maximum of $1,000.00"), output);
        assertTrue(output.contains("at most 2 decimal places"), output);
        assertTrue(output.contains("less than $100.01; balances cannot go below zero"), output);
        assertTrue(output.contains("greater than zero"), output);
        assertTrue(log.messages(Level.INFO).stream().noneMatch(m -> m.startsWith("[ECO]")), "nothing changed, nothing logged");
    }

    @Test
    void ecoSetToZeroIsAllowedAndAdminOperationsAreNotLimitedByTheTransactionMaximum() throws Exception {
        CommandSender console = console();
        Player bob = player("Bob");

        assertEquals(1, run(console, "eco set Bob 0"));
        assertEquals(1, run(console, "eco add Bob 900"));   // above the 500.00 per-transaction limit

        assertBalance("900.00", bob);
    }

    @Test
    void ecoOnAnUnknownPlayerOrAnAccountlessPlayerChangesNothing() throws Exception {
        CommandSender console = console();
        Player ghost = player("Ghost", false);

        run(console, "eco add Nobody 5");
        run(console, "eco set Ghost 5");

        assertTrue(sent(console).contains("No economy account is known for 'Nobody'"), sent(console));
        assertTrue(sent(console).contains("Ghost (" + ghost.getUniqueId() + ") has no economy account"), sent(console));
        assertFalse(store.hasAccount(ghost.getUniqueId()));
    }

    @Test
    void ecoWithMissingArgumentsShowsUsage() throws Exception {
        CommandSender console = console();

        run(console, "eco set");
        run(console, "eco add Bob");
        run(console, "eco take");
        run(console, "eco balance");

        String output = sent(console);
        assertTrue(output.contains("Usage: /eco set <player> <amount>"), output);
        assertTrue(output.contains("Usage: /eco add <player> <amount>"), output);
        assertTrue(output.contains("Usage: /eco take <player> <amount>"), output);
        assertTrue(output.contains("Usage: /eco balance <player>"), output);
    }

    // ---------------------------------------------------------------- /eco reload

    @Test
    void ecoReloadAppliesAValidFileOnlyAfterTheAsyncReadAndTellsTheAdmin() throws Exception {
        Player admin = player("Admin", EconomyCommands.PERM_ADMIN_RELOAD);
        TestSupport.writeConfig(dir, "currency:\n  symbol: \"€\"\nbalances:\n  starting: \"5.00\"\n");

        assertEquals(1, run(admin, "eco reload"));

        assertEquals(1, ioTasks.size(), "the file read must be scheduled off-thread");
        assertEquals("$", config.current().symbol(), "nothing may change before the read happens");
        runIo();
        assertEquals("€", config.current().symbol());
        assertTrue(sent(admin).contains("Configuration reloaded."), sent(admin));
        assertTrue(log.messages(Level.INFO).stream().anyMatch(m -> m.contains("reloaded by Admin (")));
    }

    @Test
    void ecoReloadRejectsAnInvalidFileAndKeepsTheActiveConfiguration() throws Exception {
        Player admin = player("Admin", EconomyCommands.PERM_ADMIN_RELOAD);
        EconomySettings before = config.current();
        TestSupport.writeConfig(dir, "currency:\n  symbol: \"€\"\nbalances:\n  starting: \"-5\"\n  maximum: \"1.234\"\n");

        run(admin, "eco reload");
        runIo();

        String output = sent(admin);
        assertTrue(output.contains("Reload rejected; the previous settings remain active."), output);
        assertTrue(output.contains("balances.starting"), output);
        assertTrue(output.contains("balances.maximum"), output);
        assertEquals(before, config.current());
    }

    @Test
    void newSettingsTakeEffectOnTheNextPaymentWithoutRestart() throws Exception {
        Player alex = player("Alex", PAY);
        Player bob = player("Bob", PAY);
        TestSupport.writeConfig(dir, "currency:\n  symbol: \"€\"\ntransactions:\n  maximum-amount: \"10.00\"\n");
        CommandSender console = console();
        run(console, "eco reload");
        runIo();

        run(alex, "pay Bob 10.01");
        run(alex, "pay Bob 10.00");

        assertTrue(sent(alex).contains("at most €10.00"), sent(alex));
        assertTrue(sent(alex).contains("You paid Bob €10.00"), sent(alex));
        assertBalance("90.00", alex);
        assertBalance("110.00", bob);
    }

    // ---------------------------------------------------------------- tab completion

    @Test
    void tabCompletionSuggestsOnlinePlayersAndHonoursPermissions() throws Exception {
        Player alex = player("Alex", PAY, BAL, BAL_OTHERS);
        player("Bob");
        player("Cara");
        Player nobody = player("Nobody");

        assertEquals(List.of("Bob", "Cara", "Nobody"), suggestions(alex, "pay "));
        assertEquals(List.of("Bob"), suggestions(alex, "pay b"));
        assertEquals(List.of("Alex", "Bob", "Cara", "Nobody"), suggestions(alex, "balance "));
        assertEquals(List.of(), suggestions(nobody, "pay "), "no suggestions without the permission");
        assertEquals(List.of(), suggestions(nobody, "balance "));
        assertEquals(List.of(), suggestions(alex, "eco balance "), "admin-only completion needs the admin permission");
    }

    private List<String> suggestions(CommandSender sender, String input) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(sender);
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source)).get().getList().stream()
                .map(suggestion -> suggestion.getText()).sorted().toList();
    }

    // ---------------------------------------------------------------- helpers

    private long count(String sql) throws Exception {
        try (java.sql.Connection raw = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("economy.db"));
             java.sql.Statement statement = raw.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
