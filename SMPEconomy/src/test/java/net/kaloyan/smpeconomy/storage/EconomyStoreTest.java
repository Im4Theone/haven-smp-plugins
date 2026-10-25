package net.kaloyan.smpeconomy.storage;

import static net.kaloyan.smpeconomy.testutil.TestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.money.Money;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs against a real SQLite file in a fresh temporary directory per test. */
class EconomyStoreTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final AtomicReference<EconomySettings> settings = new AtomicReference<>(TestSupport.smallLimits());
    private EconomyStore store;

    @BeforeEach
    void setUp() {
        store = TestSupport.openStore(dir, settings::get, log);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private UUID accountWith(String name, String balance) {
        return TestSupport.account(store, name, balance);
    }

    private BigDecimal balanceOf(UUID id) {
        return store.balance(id).orElseThrow();
    }

    private void assertStatus(Status expected, TransactionResult actual) {
        assertEquals(expected, actual.status(), actual.message());
    }

    private Connection rawConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("economy.db"));
    }

    // ---------------------------------------------------------------- account initialization

    @Test
    void newAccountReceivesTheConfiguredStartingBalance() {
        UUID id = UUID.randomUUID();

        assertTrue(store.initializeAccount(id, "Alex"));

        assertEquals(0, money("100.00").compareTo(balanceOf(id)));
    }

    @Test
    void startingBalanceIsGrantedExactlyOncePerUuid() {
        UUID id = UUID.randomUUID();
        assertTrue(store.initializeAccount(id, "Alex"));
        assertStatus(Status.SUCCESS, store.withdraw(id, money("60.00")));

        assertFalse(store.initializeAccount(id, "Alex"), "reconnecting must not create anything");
        assertFalse(store.initializeAccount(id, "Alex"));

        assertEquals(0, money("40.00").compareTo(balanceOf(id)), "a reconnect must not refill the balance");
    }

    @Test
    void renamingNeverGrantsTheStartingBalanceAgainAndUpdatesOnlyTheName() {
        UUID id = UUID.randomUUID();
        store.initializeAccount(id, "OldName");
        store.withdraw(id, money("100.00"));

        assertFalse(store.initializeAccount(id, "NewName"));

        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(id)));
        assertEquals("NewName", store.findAccount(id).orElseThrow().lastName());
        assertTrue(store.findAccountByName("OldName").isEmpty());
        assertEquals(id, store.findAccountByName("NewName").orElseThrow().uuid());
    }

    @Test
    void identityIsTheUuidAndNeverTheName() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.initializeAccount(first, "Steve");
        store.withdraw(first, money("30.00"));

        // A different player ends up with the name "Steve" (the first one renamed on Mojang's side).
        store.initializeAccount(second, "steve");

        assertEquals(second, store.findAccountByName("STEVE").orElseThrow().uuid(), "the current holder owns the name");
        assertEquals(0, money("70.00").compareTo(balanceOf(first)), "the original account keeps its money under its UUID");
        assertEquals(0, money("100.00").compareTo(balanceOf(second)));
        assertEquals(Optional.empty(), Optional.ofNullable(store.findAccount(first).orElseThrow().lastName()),
                "the stale name was released");
    }

    @Test
    void accountsWithoutAKnownNameAreStillFullAccounts() {
        UUID id = UUID.randomUUID();

        assertTrue(store.initializeAccount(id, null));
        assertTrue(store.initializeAccount(UUID.randomUUID(), "   "));

        assertNullName(id);
        assertEquals(0, money("100.00").compareTo(balanceOf(id)));
    }

    private void assertNullName(UUID id) {
        assertEquals(null, store.findAccount(id).orElseThrow().lastName());
    }

    @Test
    void changedStartingBalanceAppliesOnlyToAccountsCreatedAfterwards() {
        UUID early = UUID.randomUUID();
        store.initializeAccount(early, "Early");
        settings.set(new EconomySettings("$", 2_500L, 100_000L, 50_000L, false));

        UUID late = UUID.randomUUID();
        store.initializeAccount(late, "Late");
        store.initializeAccount(early, "Early");

        assertEquals(0, money("100.00").compareTo(balanceOf(early)));
        assertEquals(0, money("25.00").compareTo(balanceOf(late)));
    }

    @Test
    void lookupsNeverCreateAnAccount() {
        UUID unknown = UUID.randomUUID();

        assertTrue(store.balance(unknown).isEmpty());
        assertFalse(store.hasAccount(unknown));
        assertTrue(store.findAccount(unknown).isEmpty());
        assertTrue(store.findAccountByName("Nobody").isEmpty());

        assertFalse(store.hasAccount(unknown), "asking about an unknown player must not create them");
    }

    // ---------------------------------------------------------------- deposit / withdraw

    @Test
    void depositAndWithdrawChangeTheBalanceExactly() {
        UUID id = accountWith("Alex", "10.00");

        TransactionResult deposit = store.deposit(id, money("0.01"));
        TransactionResult withdraw = store.withdraw(id, money("5.00"));

        assertStatus(Status.SUCCESS, deposit);
        assertEquals(0, money("10.01").compareTo(deposit.balance()));
        assertStatus(Status.SUCCESS, withdraw);
        assertEquals(0, money("5.01").compareTo(withdraw.balance()));
        assertEquals(0, money("5.01").compareTo(balanceOf(id)));
    }

    @Test
    void invalidAmountsAreRejectedAndChangeNothing() {
        UUID id = accountWith("Alex", "10.00");

        for (BigDecimal bad : new BigDecimal[] {null, BigDecimal.ZERO, money("-1"), money("0.001"), money("1E-9"),
                money("1E+30"), Money.HARD_MAX.add(money("0.01"))}) {
            assertStatus(Status.INVALID_AMOUNT, store.deposit(id, bad));
            assertStatus(Status.INVALID_AMOUNT, store.withdraw(id, bad));
            assertStatus(Status.INVALID_AMOUNT, store.adminAdd(id, bad));
            assertStatus(Status.INVALID_AMOUNT, store.adminTake(id, bad));
        }

        assertEquals(0, money("10.00").compareTo(balanceOf(id)));
    }

    @Test
    void withdrawingMoreThanTheBalanceFailsAndWithdrawingExactlyTheBalanceEmptiesIt() {
        UUID id = accountWith("Alex", "10.00");

        assertStatus(Status.INSUFFICIENT_FUNDS, store.withdraw(id, money("10.01")));
        assertEquals(0, money("10.00").compareTo(balanceOf(id)));

        assertStatus(Status.SUCCESS, store.withdraw(id, money("10.00")));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(id)));
        assertStatus(Status.INSUFFICIENT_FUNDS, store.withdraw(id, money("0.01")));
    }

    @Test
    void missingAccountsAreReportedAndNeverCreated() {
        UUID ghost = UUID.randomUUID();

        assertStatus(Status.ACCOUNT_NOT_FOUND, store.deposit(ghost, money("1")));
        assertStatus(Status.ACCOUNT_NOT_FOUND, store.withdraw(ghost, money("1")));
        assertStatus(Status.ACCOUNT_NOT_FOUND, store.adminAdd(ghost, money("1")));
        assertStatus(Status.ACCOUNT_NOT_FOUND, store.adminTake(ghost, money("1")));
        assertStatus(Status.ACCOUNT_NOT_FOUND, store.setBalance(ghost, money("1")));

        assertFalse(store.hasAccount(ghost));
    }

    @Test
    void transactionLimitAppliesToPlayerOperationsButNotToAdminOnes() {
        UUID id = accountWith("Alex", "10.00");
        settings.set(new EconomySettings("$", 10_000L, 10_000_000L, 50_000L, false)); // max tx 500.00

        assertStatus(Status.AMOUNT_EXCEEDS_LIMIT, store.deposit(id, money("500.01")));
        assertStatus(Status.AMOUNT_EXCEEDS_LIMIT, store.withdraw(id, money("500.01")));
        assertStatus(Status.SUCCESS, store.deposit(id, money("500.00")));
        assertStatus(Status.SUCCESS, store.adminAdd(id, money("5000.00")));
        assertStatus(Status.SUCCESS, store.adminTake(id, money("5000.00")));
        assertEquals(0, money("510.00").compareTo(balanceOf(id)));
    }

    @Test
    void maximumBalanceCannotBeExceededButCanBeReachedExactly() {
        UUID id = accountWith("Alex", "900.00"); // max balance is 1000.00

        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.deposit(id, money("100.01")));
        assertEquals(0, money("900.00").compareTo(balanceOf(id)));
        assertStatus(Status.SUCCESS, store.deposit(id, money("100.00")));
        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.deposit(id, money("0.01")));
        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.adminAdd(id, money("0.01")));
    }

    @Test
    void loweringTheMaximumBalanceNeverShrinksExistingBalancesButBlocksFurtherCredits() {
        UUID id = accountWith("Alex", "900.00");
        settings.set(new EconomySettings("$", 1_000L, 50_000L, 50_000L, false)); // max balance now 500.00

        assertEquals(0, money("900.00").compareTo(balanceOf(id)));
        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.deposit(id, money("0.01")));
        assertStatus(Status.SUCCESS, store.withdraw(id, money("1.00")));
    }

    // ---------------------------------------------------------------- transfers

    @Test
    void successfulTransferMovesExactlyTheAmountBetweenTheTwoAccounts() {
        UUID payer = accountWith("Payer", "100.00");
        UUID payee = accountWith("Payee", "5.50");

        TransactionResult result = store.transfer(payer, payee, money("33.33"));

        assertStatus(Status.SUCCESS, result);
        assertEquals(0, money("66.67").compareTo(result.balance()));
        assertEquals(0, money("38.83").compareTo(result.counterpartBalance()));
        assertEquals(0, money("66.67").compareTo(balanceOf(payer)));
        assertEquals(0, money("38.83").compareTo(balanceOf(payee)));
    }

    @Test
    void transferWithInsufficientFundsChangesNeitherBalance() {
        UUID payer = accountWith("Payer", "10.00");
        UUID payee = accountWith("Payee", "10.00");

        assertStatus(Status.INSUFFICIENT_FUNDS, store.transfer(payer, payee, money("10.01")));

        assertEquals(0, money("10.00").compareTo(balanceOf(payer)));
        assertEquals(0, money("10.00").compareTo(balanceOf(payee)));
    }

    @Test
    void selfTransferIsRejectedExplicitly() {
        UUID id = accountWith("Alex", "10.00");

        assertStatus(Status.SELF_TRANSFER, store.transfer(id, id, money("1.00")));

        assertEquals(0, money("10.00").compareTo(balanceOf(id)));
    }

    @Test
    void selfTransferOfAnInvalidAmountReportsTheAmountProblemFirst() {
        UUID id = accountWith("Alex", "10.00");

        assertStatus(Status.INVALID_AMOUNT, store.transfer(id, id, money("-1")));
    }

    @Test
    void transferWithAMissingAccountNamesWhichSideIsMissingAndChangesNothing() {
        UUID real = accountWith("Real", "10.00");
        UUID ghost = UUID.randomUUID();

        assertStatus(Status.SOURCE_ACCOUNT_NOT_FOUND, store.transfer(ghost, real, money("1")));
        assertStatus(Status.TARGET_ACCOUNT_NOT_FOUND, store.transfer(real, ghost, money("1")));
        assertStatus(Status.SOURCE_ACCOUNT_NOT_FOUND, store.transfer(ghost, UUID.randomUUID(), money("1")));

        assertEquals(0, money("10.00").compareTo(balanceOf(real)));
        assertFalse(store.hasAccount(ghost));
    }

    @Test
    void transferRejectsInvalidAmountsAndAmountsOverTheTransactionLimit() {
        UUID payer = accountWith("Payer", "900.00");
        UUID payee = accountWith("Payee", "0.00");

        for (BigDecimal bad : new BigDecimal[] {null, BigDecimal.ZERO, money("-5"), money("1.005"), money("1E+30")}) {
            assertStatus(Status.INVALID_AMOUNT, store.transfer(payer, payee, bad));
        }
        assertStatus(Status.AMOUNT_EXCEEDS_LIMIT, store.transfer(payer, payee, money("500.01")));

        assertEquals(0, money("900.00").compareTo(balanceOf(payer)));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(payee)));
    }

    @Test
    void transferThatWouldPushTheRecipientOverTheMaximumBalanceChangesNeitherSide() {
        UUID payer = accountWith("Payer", "400.00");
        UUID payee = accountWith("Payee", "900.00"); // max balance 1000.00

        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.transfer(payer, payee, money("100.01")));

        assertEquals(0, money("400.00").compareTo(balanceOf(payer)));
        assertEquals(0, money("900.00").compareTo(balanceOf(payee)));
    }

    @Test
    void aFailureWhileCreditingTheRecipientRollsBackTheDebitToo() throws Exception {
        UUID payer = accountWith("Payer", "100.00");
        UUID payee = accountWith("Payee", "20.00");
        try (Connection raw = rawConnection(); Statement statement = raw.createStatement()) {
            // Fails the second UPDATE of the transfer, after the payer has already been debited.
            statement.execute("CREATE TRIGGER fail_credit BEFORE UPDATE ON accounts WHEN NEW.uuid = '" + payee
                    + "' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        }

        TransactionResult result = store.transfer(payer, payee, money("30.00"));

        assertStatus(Status.STORAGE_ERROR, result);
        assertEquals(null, result.balance(), "a failed transfer must not claim any resulting balance");
        assertEquals(0, money("100.00").compareTo(balanceOf(payer)), "the debit must have been rolled back");
        assertEquals(0, money("20.00").compareTo(balanceOf(payee)));
        assertTrue(log.messages(Level.SEVERE).stream().anyMatch(m -> m.contains(payer.toString())),
                "the failure must be logged: " + log.allMessages());

        // The store recovers: once the fault is gone the same transfer succeeds.
        try (Connection raw = rawConnection(); Statement statement = raw.createStatement()) {
            statement.execute("DROP TRIGGER fail_credit");
        }
        assertStatus(Status.SUCCESS, store.transfer(payer, payee, money("30.00")));
        assertEquals(0, money("70.00").compareTo(balanceOf(payer)));
        assertEquals(0, money("50.00").compareTo(balanceOf(payee)));
    }

    @Test
    void aFailureWhileDebitingThePayerChangesNothing() throws Exception {
        UUID payer = accountWith("Payer", "100.00");
        UUID payee = accountWith("Payee", "20.00");
        try (Connection raw = rawConnection(); Statement statement = raw.createStatement()) {
            statement.execute("CREATE TRIGGER fail_debit BEFORE UPDATE ON accounts WHEN NEW.uuid = '" + payer
                    + "' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
        }

        assertStatus(Status.STORAGE_ERROR, store.transfer(payer, payee, money("30.00")));
        assertStatus(Status.STORAGE_ERROR, store.withdraw(payer, money("1.00")));
        assertStatus(Status.STORAGE_ERROR, store.adminTake(payer, money("1.00")));
        assertStatus(Status.STORAGE_ERROR, store.setBalance(payer, money("1.00")));

        assertEquals(0, money("100.00").compareTo(balanceOf(payer)));
        assertEquals(0, money("20.00").compareTo(balanceOf(payee)));
    }

    // ---------------------------------------------------------------- administrative operations

    @Test
    void adminSetAddAndTakePreserveTheNonNegativeAndMaximumInvariants() {
        UUID id = accountWith("Alex", "50.00");

        assertStatus(Status.SUCCESS, store.setBalance(id, BigDecimal.ZERO));
        assertStatus(Status.INVALID_AMOUNT, store.setBalance(id, money("-0.01")));
        assertStatus(Status.INVALID_AMOUNT, store.setBalance(id, money("1.234")));
        assertStatus(Status.INVALID_AMOUNT, store.setBalance(id, null));
        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.setBalance(id, money("1000.01")));
        assertStatus(Status.INSUFFICIENT_FUNDS, store.adminTake(id, money("0.01")));
        assertStatus(Status.SUCCESS, store.adminAdd(id, money("1000.00")));
        assertStatus(Status.BALANCE_LIMIT_EXCEEDED, store.adminAdd(id, money("0.01")));
        assertStatus(Status.INSUFFICIENT_FUNDS, store.adminTake(id, money("1000.01")));
        assertStatus(Status.SUCCESS, store.adminTake(id, money("1000.00")));

        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(id)));
    }

    @Test
    void adminSetReturnsTheNewBalance() {
        UUID id = accountWith("Alex", "50.00");

        TransactionResult result = store.setBalance(id, money("12.34"));

        assertStatus(Status.SUCCESS, result);
        assertEquals(0, money("12.34").compareTo(result.balance()));
        assertEquals(0, money("12.34").compareTo(balanceOf(id)));
    }

    // ---------------------------------------------------------------- failures

    @Test
    void databaseConstraintIsALastLineOfDefenceAgainstNegativeBalances() throws Exception {
        UUID id = accountWith("Alex", "10.00");

        try (Connection raw = rawConnection(); Statement statement = raw.createStatement()) {
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE accounts SET balance = -1 WHERE uuid = '" + id + "'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE accounts SET balance = " + (Money.HARD_MAX_MINOR + 1) + " WHERE uuid = '" + id + "'"));
        }

        assertEquals(0, money("10.00").compareTo(balanceOf(id)));
    }

    @Test
    void usingAClosedStoreIsReportedNeverSilentlySuccessful() {
        UUID id = accountWith("Alex", "10.00");
        store.close();

        assertStatus(Status.STORAGE_ERROR, store.deposit(id, money("1")));
        assertStatus(Status.STORAGE_ERROR, store.transfer(id, UUID.randomUUID(), money("1")));
        assertThrows(StorageException.class, () -> store.balance(id));
        assertThrows(StorageException.class, () -> store.initializeAccount(UUID.randomUUID(), "X"));
        store.close(); // closing twice is harmless
    }

    @Test
    void aFileThatIsNotADatabaseIsRefusedWithAClearError() throws Exception {
        Path bogus = dir.resolve("bogus.db");
        Files.writeString(bogus, "this is definitely not an sqlite database, just text ".repeat(50));

        StorageException e = assertThrows(StorageException.class,
                () -> EconomyStore.open(bogus, settings::get, log.logger()));

        assertTrue(e.getMessage().contains("database"), e.getMessage());
        assertTrue(Files.readString(bogus).startsWith("this is definitely"), "the foreign file must not be modified");
    }

    @Test
    void nullArgumentsAreRejectedImmediately() {
        assertThrows(NullPointerException.class, () -> store.deposit(null, money("1")));
        assertThrows(NullPointerException.class, () -> store.transfer(null, UUID.randomUUID(), money("1")));
        assertThrows(NullPointerException.class, () -> store.transfer(UUID.randomUUID(), null, money("1")));
        assertThrows(NullPointerException.class, () -> store.balance(null));
    }
}
