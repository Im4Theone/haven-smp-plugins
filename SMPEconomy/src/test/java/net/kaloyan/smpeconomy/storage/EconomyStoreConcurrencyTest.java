package net.kaloyan.smpeconomy.storage;

import static net.kaloyan.smpeconomy.testutil.TestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.kaloyan.smpeconomy.api.TransactionResult;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hammers the store from many threads. Two kinds of contention are covered: many threads sharing one store
 * (one connection) and several independent stores (separate connections, as if separate processes) on the
 * same file, where only SQLite's {@code BEGIN IMMEDIATE} write lock and busy timeout keep the data correct.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EconomyStoreConcurrencyTest {

    // Big enough that the limits never interfere with the property under test.
    private static final EconomySettings SETTINGS =
            new EconomySettings("$", 10_000L, 100_000_000_000L, 100_000_000_000L, false);

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final List<EconomyStore> stores = new ArrayList<>();
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
        stores.forEach(EconomyStore::close);
    }

    private EconomyStore openStore() {
        EconomyStore store = EconomyStore.open(dir.resolve("economy.db"), () -> SETTINGS, log.logger());
        stores.add(store);
        return store;
    }

    /** Runs all tasks at the same instant and returns their results. */
    private <T> List<T> raceAll(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        // One thread per task, all parked on the same latch, so they really do start together.
        pool = Executors.newFixedThreadPool(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not start");
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(90, TimeUnit.SECONDS));
        }
        return results;
    }

    private static long count(List<TransactionResult> results, Status status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    private static BigDecimal total(EconomyStore store, UUID... ids) {
        BigDecimal sum = BigDecimal.ZERO;
        for (UUID id : ids) {
            sum = sum.add(store.balance(id).orElseThrow());
        }
        return sum;
    }

    @Test
    void concurrentTransfersFromOneAccountCannotOverspendIt() throws Exception {
        EconomyStore store = openStore();
        UUID payer = TestSupport.account(store, "Payer", "100.00");
        UUID payee = TestSupport.account(store, "Payee", "0.00");

        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            tasks.add(() -> store.transfer(payer, payee, money("10.00")));
        }
        List<TransactionResult> results = raceAll(tasks);

        assertEquals(10, count(results, Status.SUCCESS), "100.00 can fund exactly ten payments of 10.00");
        assertEquals(50, count(results, Status.INSUFFICIENT_FUNDS));
        assertEquals(0, BigDecimal.ZERO.compareTo(store.balance(payer).orElseThrow()));
        assertEquals(0, money("100.00").compareTo(store.balance(payee).orElseThrow()));
    }

    @Test
    void concurrentTransfersOverSeparateConnectionsCannotOverspendEither() throws Exception {
        EconomyStore a = openStore();
        EconomyStore b = openStore();
        EconomyStore c = openStore();
        UUID payer = TestSupport.account(a, "Payer", "100.00");
        UUID payee = TestSupport.account(a, "Payee", "0.00");
        EconomyStore[] connections = {a, b, c};

        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            EconomyStore via = connections[i % connections.length];
            tasks.add(() -> via.transfer(payer, payee, money("10.00")));
        }
        List<TransactionResult> results = raceAll(tasks);

        assertEquals(10, count(results, Status.SUCCESS), results.toString());
        assertEquals(35, count(results, Status.INSUFFICIENT_FUNDS), results.toString());
        assertEquals(0, count(results, Status.STORAGE_ERROR), "no operation may fail merely because of lock contention");
        assertEquals(0, BigDecimal.ZERO.compareTo(a.balance(payer).orElseThrow()));
        assertEquals(0, money("100.00").compareTo(b.balance(payee).orElseThrow()));
    }

    @Test
    void concurrentWithdrawalsCannotTakeMoreThanTheBalance() throws Exception {
        EconomyStore store = openStore();
        UUID id = TestSupport.account(store, "Alex", "100.00");

        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> store.withdraw(id, money("5.00")));
        }
        List<TransactionResult> results = raceAll(tasks);

        assertEquals(20, count(results, Status.SUCCESS));
        assertEquals(20, count(results, Status.INSUFFICIENT_FUNDS));
        assertEquals(0, BigDecimal.ZERO.compareTo(store.balance(id).orElseThrow()));
    }

    @Test
    void randomTransfersInAllDirectionsConserveTheTotalAndNeverGoNegative() throws Exception {
        EconomyStore a = openStore();
        EconomyStore b = openStore();
        UUID[] ids = new UUID[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = TestSupport.account(a, "P" + i, "50.00");
        }
        BigDecimal expectedTotal = money("250.00");

        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        Random random = new Random(12345);
        for (int i = 0; i < 200; i++) {
            UUID from = ids[random.nextInt(ids.length)];
            UUID to = ids[random.nextInt(ids.length)];
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(8000), 2); // 0.01 .. 80.00
            EconomyStore via = i % 2 == 0 ? a : b;
            tasks.add(() -> via.transfer(from, to, amount));
        }
        List<TransactionResult> results = raceAll(tasks);

        assertEquals(0, count(results, Status.STORAGE_ERROR));
        assertEquals(0, expectedTotal.compareTo(total(a, ids)), "money must be neither created nor destroyed");
        for (UUID id : ids) {
            assertTrue(a.balance(id).orElseThrow().signum() >= 0, "balance went negative");
        }
    }

    @Test
    void concurrentInitializationGrantsTheStartingBalanceExactlyOnce() throws Exception {
        EconomyStore a = openStore();
        EconomyStore b = openStore();
        UUID id = UUID.randomUUID();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            EconomyStore via = i % 2 == 0 ? a : b;
            tasks.add(() -> via.initializeAccount(id, "Alex"));
        }
        List<Boolean> created = raceAll(tasks);

        assertEquals(1, created.stream().filter(Boolean::booleanValue).count(), "exactly one caller creates the account");
        assertEquals(0, money("100.00").compareTo(a.balance(id).orElseThrow()), "the starting balance is granted once");
    }

    @Test
    void initializationRacingWithSpendingNeverRefillsTheBalance() throws Exception {
        EconomyStore store = openStore();
        UUID id = UUID.randomUUID();
        store.initializeAccount(id, "Alex");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> store.initializeAccount(id, "Alex"));
            tasks.add(() -> store.withdraw(id, money("10.00")));
        }
        raceAll(tasks);

        // 100.00 start, ten successful withdrawals of 10.00, and no refill from the 30 re-initializations.
        assertEquals(0, BigDecimal.ZERO.compareTo(store.balance(id).orElseThrow()));
    }

    @Test
    void concurrentDepositsAreAllCounted() throws Exception {
        EconomyStore a = openStore();
        EconomyStore b = openStore();
        UUID id = TestSupport.account(a, "Alex", "0.00");

        List<Callable<TransactionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            EconomyStore via = i % 2 == 0 ? a : b;
            tasks.add(() -> via.deposit(id, money("1.25")));
        }
        List<TransactionResult> results = raceAll(tasks);

        assertEquals(100, count(results, Status.SUCCESS));
        assertEquals(0, money("125.00").compareTo(a.balance(id).orElseThrow()), "no lost updates");
    }
}
