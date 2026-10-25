package net.kaloyan.smpeconomy.service;

import static net.kaloyan.smpeconomy.testutil.TestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.log.DebugLogger;
import net.kaloyan.smpeconomy.storage.EconomyStore;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EconomyServiceImplTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final AtomicReference<EconomySettings> settings = new AtomicReference<>(TestSupport.smallLimits());
    private ExecutorService storageThread;
    private EconomyStore store;
    private EconomyServiceImpl service;

    private void startStack() {
        store = TestSupport.openStore(dir, settings::get, log);
        storageThread = Executors.newSingleThreadExecutor(task -> new Thread(task, "test-storage"));
        service = new EconomyServiceImpl(store, storageThread, settings::get, new DebugLogger(log.logger(), settings::get));
    }

    /** Emulates a plugin disable: queued work finishes, then the database is closed. */
    private void stopStack() throws InterruptedException {
        storageThread.shutdown();
        assertTrue(storageThread.awaitTermination(10, TimeUnit.SECONDS));
        store.close();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (storageThread != null && !storageThread.isShutdown()) {
            stopStack();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private UUID newAccount(String name, String balance) throws Exception {
        UUID id = UUID.randomUUID();
        await(service.initializeAccount(id, name));
        assertTrue(await(service.setBalance(id, money(balance))).isSuccess());
        return id;
    }

    // ---------------------------------------------------------------- the public API

    @Test
    void balanceDepositWithdrawAndTransferWorkThroughTheApi() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "50.00");
        UUID sam = newAccount("Sam", "10.00");

        assertEquals(Optional.of(money("50.00")), await(service.getBalance(alex)).map(b -> b.setScale(2)));
        assertTrue(await(service.hasAccount(alex)));
        assertEquals(0, money("60.00").compareTo(await(service.deposit(alex, money("10.00"))).balance()));
        assertEquals(0, money("55.00").compareTo(await(service.withdraw(alex, money("5.00"))).balance()));
        TransactionResult transfer = await(service.transfer(alex, sam, money("20.00")));

        assertTrue(transfer.isSuccess());
        assertEquals(0, money("35.00").compareTo(transfer.balance()));
        assertEquals(0, money("30.00").compareTo(transfer.counterpartBalance()));
    }

    @Test
    void failuresCarryExplicitStatusesAndNeverChangeMoney() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "10.00");
        UUID ghost = UUID.randomUUID();

        assertEquals(Status.INSUFFICIENT_FUNDS, await(service.withdraw(alex, money("10.01"))).status());
        assertEquals(Status.INVALID_AMOUNT, await(service.deposit(alex, money("-1"))).status());
        assertEquals(Status.INVALID_AMOUNT, await(service.deposit(alex, null)).status());
        assertEquals(Status.ACCOUNT_NOT_FOUND, await(service.deposit(ghost, money("1"))).status());
        assertEquals(Status.SELF_TRANSFER, await(service.transfer(alex, alex, money("1"))).status());
        assertEquals(Status.TARGET_ACCOUNT_NOT_FOUND, await(service.transfer(alex, ghost, money("1"))).status());
        assertEquals(Status.SOURCE_ACCOUNT_NOT_FOUND, await(service.transfer(ghost, alex, money("1"))).status());
        assertEquals(Status.AMOUNT_EXCEEDS_LIMIT, await(service.deposit(alex, money("500.01"))).status());

        assertEquals(0, money("10.00").compareTo(await(service.getBalance(alex)).orElseThrow()));
        assertFalse(await(service.hasAccount(ghost)), "failed calls must not create accounts");
    }

    @Test
    void hasFundsAnswersWithoutChangingAnything() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "10.00");

        assertTrue(await(service.hasFunds(alex, money("10.00"))));
        assertTrue(await(service.hasFunds(alex, money("0"))));
        assertFalse(await(service.hasFunds(alex, money("10.01"))));
        assertFalse(await(service.hasFunds(alex, money("-1"))));
        assertFalse(await(service.hasFunds(alex, money("0.001"))));
        assertFalse(await(service.hasFunds(alex, null)));
        assertFalse(await(service.hasFunds(UUID.randomUUID(), money("1"))));
        assertEquals(0, money("10.00").compareTo(await(service.getBalance(alex)).orElseThrow()));
    }

    @Test
    void currencyFormattingAndLimitsFollowTheLiveConfiguration() throws Exception {
        startStack();

        assertEquals(2, service.getFractionDigits());
        assertEquals("$", service.getCurrencySymbol());
        assertEquals("$1,234.50", service.format(money("1234.5")));
        assertEquals(0, money("1000.00").compareTo(service.getMaximumBalance()));
        assertEquals(0, money("500.00").compareTo(service.getMaximumTransactionAmount()));

        settings.set(new EconomySettings("€", 0L, 200_000L, 20_000L, false));

        assertEquals("€2.00", service.format(money("2")));
        assertEquals(0, money("2000.00").compareTo(service.getMaximumBalance()));
        assertEquals(0, money("200.00").compareTo(service.getMaximumTransactionAmount()));
        assertThrows(IllegalArgumentException.class, () -> service.format(money("1.001")));
    }

    @Test
    void nullIdentifiersAreProgrammingErrorsReportedImmediately() throws Exception {
        startStack();

        assertThrows(NullPointerException.class, () -> service.getBalance(null));
        assertThrows(NullPointerException.class, () -> service.transfer(null, UUID.randomUUID(), money("1")));
    }

    // ---------------------------------------------------------------- threading

    @Test
    void databaseWorkRunsOnTheStorageThreadNotOnTheCaller() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "10.00");
        AtomicReference<String> completedOn = new AtomicReference<>();
        // Park the storage thread so the callback is attached before the lookup can complete.
        CountDownLatch gate = new CountDownLatch(1);
        storageThread.execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<Optional<BigDecimal>> lookup = service.getBalance(alex).thenApply(balance -> {
            completedOn.set(Thread.currentThread().getName());
            return balance;
        });
        assertFalse(lookup.isDone(), "the caller must not wait for the database");
        gate.countDown();
        await(lookup);

        assertEquals("test-storage", completedOn.get());
        assertNotEquals(Thread.currentThread().getName(), completedOn.get());
    }

    @Test
    void callsReturnImmediatelyAndAreExecutedInSubmissionOrder() throws Exception {
        store = TestSupport.openStore(dir, settings::get, log);
        Deque<Runnable> queue = new ArrayDeque<>();
        service = new EconomyServiceImpl(store, queue::add, settings::get, new DebugLogger(log.logger(), settings::get));
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        store.initializeAccount(alex, "Alex");
        store.initializeAccount(sam, "Sam");

        CompletableFuture<TransactionResult> first = service.transfer(alex, sam, money("100.00"));
        CompletableFuture<TransactionResult> second = service.transfer(alex, sam, money("0.01"));

        assertFalse(first.isDone(), "the call must return before any database work has happened");
        assertEquals(2, queue.size());
        queue.poll().run();
        queue.poll().run();
        assertTrue(first.get().isSuccess());
        assertEquals(Status.INSUFFICIENT_FUNDS, second.get().status(), "the second call saw the first call's effect");
        store.close();
    }

    @Test
    void callsAfterShutdownFailTheirFutureInsteadOfPretendingToWork() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "10.00");
        stopStack();

        CompletableFuture<TransactionResult> late = service.deposit(alex, money("1"));
        ExecutionException e = assertThrows(ExecutionException.class, () -> late.get(5, TimeUnit.SECONDS));

        assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
    }

    @Test
    void operationsQueuedBeforeShutdownStillCompleteBeforeTheDatabaseCloses() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "0.00");
        CompletableFuture<TransactionResult> last = null;
        for (int i = 0; i < 50; i++) {
            last = service.deposit(alex, money("1.00"));
        }

        stopStack();

        assertTrue(last.isDone() && last.get().isSuccess());
        try (EconomyStore reopened = TestSupport.openStore(dir, settings::get, log)) {
            assertEquals(0, money("50.00").compareTo(reopened.balance(alex).orElseThrow()));
        }
    }

    // ---------------------------------------------------------------- failure handling and restart

    @Test
    void aBrokenDatabaseNeverProducesASuccessfulResult() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "10.00");
        store.close(); // simulates the database becoming unusable underneath the service

        TransactionResult deposit = await(service.deposit(alex, money("1")));
        TransactionResult transfer = await(service.transfer(alex, UUID.randomUUID(), money("1")));

        assertEquals(Status.STORAGE_ERROR, deposit.status());
        assertEquals(Status.STORAGE_ERROR, transfer.status());
        assertFalse(deposit.isSuccess());
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(service.getBalance(alex)));
        assertTrue(e.getCause() instanceof RuntimeException);
    }

    @Test
    void balancesAndAccountsSurviveAPluginRestartAndStartingBalanceIsNotGrantedAgain() throws Exception {
        startStack();
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        assertTrue(await(service.initializeAccount(alex, "Alex")));
        assertTrue(await(service.initializeAccount(sam, "Sam")));
        assertTrue(await(service.transfer(alex, sam, money("37.25"))).isSuccess());
        stopStack(); // plugin disabled

        startStack(); // server restarted, plugin enabled again over the same data folder
        assertFalse(await(service.initializeAccount(alex, "Alex")), "existing account must not be recreated");
        assertFalse(await(service.initializeAccount(sam, "SamRenamed")));

        assertEquals(0, money("62.75").compareTo(await(service.getBalance(alex)).orElseThrow()));
        assertEquals(0, money("137.25").compareTo(await(service.getBalance(sam)).orElseThrow()));
        assertEquals(sam, await(service.findAccountByName("samrenamed")).orElseThrow().uuid());
    }

    @Test
    void debugLoggingIsOffByDefaultAndRecordsTransactionsWhenEnabled() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "10.00");
        await(service.deposit(alex, money("1.00")));
        assertTrue(log.allMessages().stream().noneMatch(m -> m.contains("[DEBUG]")),
                "ordinary transactions must not be logged without debug: " + log.allMessages());

        settings.set(new EconomySettings("$", 10_000L, 100_000L, 50_000L, true));
        await(service.deposit(alex, money("1.00")));

        assertTrue(log.allMessages().stream().anyMatch(m -> m.contains("[DEBUG]") && m.contains("deposit")));
    }

    @Test
    void amountsAreNeverRoundedAlongTheWay() throws Exception {
        startStack();
        UUID alex = newAccount("Alex", "0.00");

        assertEquals(Status.INVALID_AMOUNT, await(service.deposit(alex, new BigDecimal("0.005"))).status());
        assertTrue(await(service.deposit(alex, new BigDecimal("0.10"))).isSuccess());
        assertTrue(await(service.deposit(alex, new BigDecimal("0.20"))).isSuccess());

        assertEquals(0, new BigDecimal("0.30").compareTo(await(service.getBalance(alex)).orElseThrow()),
                "0.10 + 0.20 must be exactly 0.30 (no binary floating point anywhere)");
    }
}
