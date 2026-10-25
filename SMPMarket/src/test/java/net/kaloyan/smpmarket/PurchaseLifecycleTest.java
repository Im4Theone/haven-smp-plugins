package net.kaloyan.smpmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kaloyan.smpeconomy.api.TransactionResult.Status;
import net.kaloyan.smpmarket.model.DeliveryStatus;
import net.kaloyan.smpmarket.model.FeeState;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.model.Price;
import net.kaloyan.smpmarket.model.TxState;
import net.kaloyan.smpmarket.service.MarketResults.BuyResult;
import net.kaloyan.smpmarket.service.MarketResults.BuyStatus;
import net.kaloyan.smpmarket.service.MarketResults.CancelStatus;
import net.kaloyan.smpmarket.service.MarketService;
import net.kaloyan.smpmarket.storage.StorageException;
import net.kaloyan.smpmarket.testutil.FakeEconomy;
import net.kaloyan.smpmarket.testutil.FakeItems;
import net.kaloyan.smpmarket.testutil.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests (real SQLite, fake economy and inventory): the purchase workflow and its failure modes. */
class PurchaseLifecycleTest {

    @TempDir
    Path dir;

    Fixture f;
    final ItemSnapshot sword = FakeItems.item("minecraft:diamond_sword", 1, "sword");

    @BeforeEach
    void setUp() {
        f = new Fixture(dir);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private long sellerBalance() {
        return f.economy.balance(f.seller);
    }

    private long buyerBalance() {
        return f.economy.balance(f.buyer);
    }

    // ------------------------------------------------------------------ success

    @Test
    void successfulPurchaseMovesMoneyOnceAndTheItemOnce() {
        long id = f.list(f.seller, sword, 1000);

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.PURCHASED, result.status());
        assertTrue(result.delivered());
        assertEquals(100_000 - 1000, buyerBalance());
        assertEquals(100_000 + 1000, sellerBalance());
        assertEquals(1, f.economy.transfers.get());
        assertEquals(0, f.economy.withdrawals.get(), "no fee configured");
        assertEquals(0, f.economy.deposits.get(), "a purchase never needs a deposit or refund");
        assertEquals(1, f.items.count(sword));
        assertEquals(1, f.items.player(f.buyer).inventory.size());
        assertEquals(ListingStatus.SOLD, f.store.findListing(id).orElseThrow().status());
        MarketTx tx = f.store.findTx(result.tx().id()).orElseThrow();
        assertEquals(TxState.COMPLETED, tx.state());
        assertEquals(DeliveryStatus.DELIVERED, f.store.findDeliveryForListing(id).orElseThrow().status());
    }

    @Test
    void sellerAndBuyerSeeTheSaleInTheirHistory() {
        long id = f.list(f.seller, sword, 1000);
        f.buy(f.buyer, id);

        assertEquals(1, f.service.history(f.seller, 1).join().total());
        assertEquals(1, f.service.history(f.buyer, 1).join().total());
        assertEquals(0, f.service.history(f.other, 1).join().total());
        assertTrue(f.items.notifications.stream().anyMatch(n -> n.startsWith(f.seller + ": Your listing #" + id)),
                "an online seller is told immediately");
    }

    @Test
    void offlineSellerHearsAboutTheSaleOnJoinExactlyOnce() {
        long id = f.list(f.seller, sword, 1000);
        f.items.player(f.seller).online = false;
        f.buy(f.buyer, id);

        var first = f.service.onJoin(f.seller).join();
        assertEquals(1, first.unnotifiedSales().size());
        f.service.markSalesNotified(first.unnotifiedSales());
        assertEquals(0, f.service.onJoin(f.seller).join().unnotifiedSales().size());
    }

    @Test
    void historyIsBoundedToTheMostRecentEntries() {
        f.settings(Fixture.settings(500, Duration.ofHours(72), 1, 100_000_000, 1, 64, 0, false, List.of()));
        f.economy.account(f.buyer, "100000.00");
        for (int i = 0; i < MarketService.HISTORY_LIMIT + 5; i++) {
            f.buy(f.buyer, f.list(f.seller, FakeItems.item("minecraft:stone", 1, "h" + i), 100));
        }
        var page = f.service.history(f.buyer, 1).join();
        assertEquals(MarketService.HISTORY_LIMIT, page.total());
        assertEquals(MarketService.PAGE_SIZE, page.items().size());
        assertEquals(13, page.pages());
        assertEquals(MarketService.HISTORY_LIMIT % MarketService.PAGE_SIZE, f.service.history(f.buyer, 13).join().items().size());
    }

    // ------------------------------------------------------------------ rejections

    @Test
    void insufficientFundsChargesNothingAndReopensTheListing() {
        long id = f.list(f.seller, sword, 1000);
        f.economy.account(f.buyer, "5.00");

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.INSUFFICIENT_FUNDS, result.status());
        assertEquals(500, buyerBalance());
        assertEquals(100_000, sellerBalance());
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
        assertEquals(TxState.FAILED, f.store.findTx(result.tx().id()).orElseThrow().state());
        assertEquals(0, f.items.count(sword), "the item is still in the listing");
        assertEquals(0, f.store.pendingDeliveryCount(f.buyer));

        f.economy.account(f.buyer, "50.00"); // after a top-up the very same listing can be bought
        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, id).status());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void selfPurchaseIsRejectedByDefault() {
        long id = f.list(f.seller, sword, 1000);

        assertEquals(BuyStatus.SELF_PURCHASE, f.buy(f.seller, id).status());

        assertEquals(0, f.economy.transfers.get());
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
    }

    @Test
    void selfPurchaseWhenAllowedMovesNoMoneyAndReturnsTheItem() {
        f.settings(Fixture.settings(10, Duration.ofHours(72), 1, 100_000_000, 1, 64, 500, true, List.of()));
        long id = f.list(f.seller, sword, 1000);

        BuyResult result = f.buy(f.seller, id);

        assertEquals(BuyStatus.PURCHASED, result.status());
        assertEquals(0, f.economy.transfers.get(), "SMP-Economy refuses self transfers and none is needed");
        assertEquals(0, f.economy.withdrawals.get(), "no fee on a purchase from yourself");
        assertEquals(100_000, sellerBalance());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void missingExpiredCancelledAndSoldListingsAreUnavailable() {
        assertEquals(BuyStatus.NOT_FOUND, f.buy(f.buyer, 4242).status());

        long expired = f.list(f.seller, sword, 1000);
        f.clock.advance(Duration.ofHours(73)); // not swept yet: expiry is judged from the stored time
        assertEquals(BuyStatus.EXPIRED, f.buy(f.buyer, expired).status());

        long cancelled = f.list(f.seller, FakeItems.item("minecraft:stone", 1, "c"), 1000);
        f.service.cancel(f.seller, false, cancelled).join();
        BuyResult cancelledResult = f.buy(f.buyer, cancelled);
        assertEquals(BuyStatus.UNAVAILABLE, cancelledResult.status());
        assertEquals(ListingStatus.CANCELLED, cancelledResult.listingStatus());

        long sold = f.list(f.seller, FakeItems.item("minecraft:stone", 1, "s"), 1000);
        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, sold).status());
        BuyResult soldResult = f.buy(f.other, sold);
        assertEquals(BuyStatus.UNAVAILABLE, soldResult.status());
        assertEquals(ListingStatus.SOLD, soldResult.listingStatus());

        assertEquals(1, f.economy.transfers.get(), "only the one real purchase reached the economy");
    }

    @Test
    void twoBuyersInSequenceOnlyTheFirstPays() {
        long id = f.list(f.seller, sword, 1000);

        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, id).status());
        assertEquals(BuyStatus.UNAVAILABLE, f.buy(f.other, id).status());

        assertEquals(100_000, f.economy.balance(f.other));
        assertEquals(1, f.economy.transfers.get());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void duplicateCommandExecutionChargesOnce() {
        long id = f.list(f.seller, sword, 1000);

        BuyResult first = f.buy(f.buyer, id);
        BuyResult second = f.buy(f.buyer, id);

        assertEquals(BuyStatus.PURCHASED, first.status());
        assertEquals(BuyStatus.UNAVAILABLE, second.status());
        assertEquals(100_000 - 1000, buyerBalance());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void aPurchaseThatIsStillInFlightRefusesTheDuplicate() {
        long id = f.list(f.seller, sword, 1000);
        ListingLifecycleTest.QueueExecutor storage = new ListingLifecycleTest.QueueExecutor();
        MarketService queued = f.newService(f.store, storage, Fixture.INLINE);

        CompletableFuture<BuyResult> first = queued.buy(f.buyer, "Buyer", id);
        CompletableFuture<BuyResult> second = queued.buy(f.buyer, "Buyer", id);
        CompletableFuture<BuyResult> thirdPerson = queued.buy(f.other, "Other", id);

        assertEquals(BuyStatus.BUSY, second.join().status());
        assertEquals(BuyStatus.BUSY, thirdPerson.join().status());
        assertEquals(0, f.economy.transfers.get(), "nothing reached the economy before the storage thread ran");
        storage.runAll();
        assertEquals(BuyStatus.PURCHASED, first.join().status());
        assertEquals(1, f.economy.transfers.get());
    }

    // ------------------------------------------------------------------ races

    @Test
    void manyBuyersOnRealThreadsExactlyOneWins() throws Exception {
        long id = f.list(f.seller, sword, 1000);
        int buyers = 24;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            UUID b = f.items.addPlayer();
            f.economy.account(b, "100.00");
            ids.add(b);
        }
        ExecutorService storage = Executors.newSingleThreadExecutor();
        ExecutorService main = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(buyers);
        try {
            MarketService real = f.newService(f.store, storage, main);
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<BuyResult>> results = new ArrayList<>();
            for (UUID b : ids) {
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return real.buy(b, "B", id).join();
                }, callers));
            }
            go.countDown();
            int purchased = 0;
            for (CompletableFuture<BuyResult> r : results) {
                if (r.get(20, TimeUnit.SECONDS).status() == BuyStatus.PURCHASED) {
                    purchased++;
                }
            }
            assertEquals(1, purchased);
            assertEquals(1, f.economy.transfers.get());
            assertEquals(1, f.items.count(sword));
            long total = sellerBalance();
            for (UUID b : ids) {
                total += f.economy.balance(b);
            }
            assertEquals(100_000 + 1000 + (buyers * 10_000L - 1000), total, "money is conserved");
        } finally {
            callers.shutdownNow();
            storage.shutdown();
            main.shutdown();
        }
    }

    @Test
    void cancelAndBuyRacingHaveExactlyOneWinnerAndNeverDuplicateTheItem() throws Exception {
        ExecutorService storage = Executors.newSingleThreadExecutor();
        ExecutorService main = Executors.newSingleThreadExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            MarketService real = f.newService(f.store, storage, main);
            f.items.player(f.buyer).capacity = 1000;
            f.items.player(f.seller).capacity = 1000;
            for (int round = 0; round < 40; round++) {
                ItemSnapshot item = FakeItems.item("minecraft:stone", 1, "race" + round);
                long id = f.list(f.seller, item, 100);
                CountDownLatch go = new CountDownLatch(1);
                CompletableFuture<BuyResult> buy = CompletableFuture.supplyAsync(() -> {
                    await(go);
                    return real.buy(f.buyer, "B", id).join();
                }, callers);
                CompletableFuture<CancelStatus> cancel = CompletableFuture.supplyAsync(() -> {
                    await(go);
                    return real.cancel(f.seller, false, id).join().status();
                }, callers);
                go.countDown();
                boolean bought = buy.get(20, TimeUnit.SECONDS).status() == BuyStatus.PURCHASED;
                boolean cancelled = cancel.get(20, TimeUnit.SECONDS) == CancelStatus.CANCELLED;
                assertTrue(bought ^ cancelled, "exactly one of buy/cancel wins (round " + round + ")");
                assertEquals(1, f.items.count(item), "the item exists exactly once (round " + round + ")");
                assertEquals(bought ? 1 : 0, f.items.player(f.buyer).inventory.stream()
                        .filter(s -> java.util.Arrays.equals(s.payload(), item.payload())).count());
            }
        } finally {
            callers.shutdownNow();
            storage.shutdown();
            main.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aReservedListingCannotBeCancelledOrExpiredOrBoughtAgain() {
        long id = f.list(f.seller, sword, 1000);
        f.economy.mode = FakeEconomy.Mode.THROW_AFTER_APPLY;
        assertEquals(BuyStatus.PAYMENT_UNCERTAIN, f.buy(f.buyer, id).status());

        assertEquals(CancelStatus.NOT_ACTIVE, f.service.cancel(f.seller, false, id).join().status());
        f.clock.advance(Duration.ofHours(100));
        assertEquals(0, f.service.sweep().join(), "a reserved listing is not expired out from under a payment");
        f.economy.mode = FakeEconomy.Mode.NORMAL;
        assertEquals(BuyStatus.UNAVAILABLE, f.buy(f.other, id).status());
        assertEquals(ListingStatus.RESERVED, f.store.findListing(id).orElseThrow().status());
        assertEquals(1, f.economy.transfers.get());
    }

    // ------------------------------------------------------------------ fee

    @Test
    void feeArithmeticRoundsDownToTheCentAndNeverExceedsThePrice() {
        assertEquals(25, Price.fee(1001, 250)); // 2.5% of 10.01 = 0.25025
        assertEquals(0, Price.fee(1, 5000));
        assertEquals(499, Price.fee(999, 5000));
        assertEquals(0, Price.fee(123456, 0));
        assertEquals(Price.HARD_MAX_MINOR / 2, Price.fee(Price.HARD_MAX_MINOR, 5000), "no overflow at the hard maximum");
    }

    @Test
    void feeIsWithdrawnFromTheSellerAfterTheSaleIsPaid() {
        f.settings(Fixture.settings(10, Duration.ofHours(72), 1, 100_000_000, 1, 64, 250, false, List.of()));
        long id = f.list(f.seller, sword, 1001);

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.PURCHASED, result.status());
        assertEquals(100_000 - 1001, buyerBalance());
        assertEquals(100_000 + 1001 - 25, sellerBalance(), "the seller nets price minus fee");
        assertEquals(1, f.economy.withdrawals.get());
        MarketTx tx = f.store.findTx(result.tx().id()).orElseThrow();
        assertEquals(25, tx.feeMinor());
        assertEquals(FeeState.COLLECTED, tx.feeState());
        f.service.sweep().join();
        assertEquals(1, f.economy.withdrawals.get(), "a collected fee is never collected again");
    }

    @Test
    void aFeeThatDefinitelyFailsDoesNotUndoTheSaleAndIsRetriedLater() {
        f.settings(Fixture.settings(10, Duration.ofHours(72), 1, 100_000_000, 1, 64, 1000, false, List.of()));
        long id = f.list(f.seller, sword, 1000);
        f.economy.withdrawMode = FakeEconomy.Mode.FAIL_STATUS;
        f.economy.failureStatus = Status.STORAGE_ERROR;

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.PURCHASED, result.status(), "the sale stands");
        assertEquals(1, f.items.count(sword));
        assertEquals(FeeState.PENDING, f.store.findTx(result.tx().id()).orElseThrow().feeState());

        f.economy.withdrawMode = FakeEconomy.Mode.NORMAL;
        f.service.sweep().join();
        assertEquals(FeeState.COLLECTED, f.store.findTx(result.tx().id()).orElseThrow().feeState());
        assertEquals(100_000 + 1000 - 100, sellerBalance());
        f.service.sweep().join();
        assertEquals(2, f.economy.withdrawals.get(), "one failed attempt and one successful attempt, no more");
    }

    @Test
    void anUncertainFeeIsNeverRetriedAutomatically() {
        f.settings(Fixture.settings(10, Duration.ofHours(72), 1, 100_000_000, 1, 64, 1000, false, List.of()));
        long id = f.list(f.seller, sword, 1000);
        f.economy.withdrawMode = FakeEconomy.Mode.THROW_AFTER_APPLY;

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.PURCHASED, result.status());
        assertEquals(FeeState.UNCERTAIN, f.store.findTx(result.tx().id()).orElseThrow().feeState());
        f.economy.withdrawMode = FakeEconomy.Mode.NORMAL;
        f.service.sweep().join();
        f.restart();
        assertEquals(1, f.economy.withdrawals.get(), "no second withdrawal: it could take the fee twice");
        assertEquals(100_000 + 1000 - 100, sellerBalance());
    }

    // ------------------------------------------------------------------ economy failures

    @Test
    void definiteEconomyFailuresNeverChargeAndAlwaysReopenTheListing() {
        long id = f.list(f.seller, sword, 1000);
        Object[][] cases = {
            {Status.STORAGE_ERROR, BuyStatus.ECONOMY_UNAVAILABLE},
            {Status.BALANCE_LIMIT_EXCEEDED, BuyStatus.SELLER_BALANCE_LIMIT},
            {Status.AMOUNT_EXCEEDS_LIMIT, BuyStatus.PRICE_EXCEEDS_ECONOMY_LIMIT},
            {Status.SOURCE_ACCOUNT_NOT_FOUND, BuyStatus.NO_ACCOUNT_BUYER},
            {Status.TARGET_ACCOUNT_NOT_FOUND, BuyStatus.NO_ACCOUNT_SELLER},
        };
        f.economy.mode = FakeEconomy.Mode.FAIL_STATUS;
        for (Object[] c : cases) {
            f.economy.failureStatus = (Status) c[0];
            BuyResult result = f.buy(f.buyer, id);
            assertEquals(c[1], result.status(), String.valueOf(c[0]));
            assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
            assertEquals(TxState.FAILED, f.store.findTx(result.tx().id()).orElseThrow().state());
        }
        assertEquals(100_000, buyerBalance());
        assertEquals(0, f.economy.deposits.get());
        f.economy.mode = FakeEconomy.Mode.NORMAL;
        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, id).status(), "and the listing is still buyable afterwards");
    }

    @Test
    void anEconomyThatIsShutDownIsProvablyNotPaidAndNotUncertain() {
        long id = f.list(f.seller, sword, 1000);
        f.economy.mode = FakeEconomy.Mode.REJECTED;

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.ECONOMY_UNAVAILABLE, result.status());
        assertEquals(TxState.FAILED, f.store.findTx(result.tx().id()).orElseThrow().state());
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
    }

    @Test
    void anUnprovableOutcomeIsUncertainAndIsNeverRetriedOrRefunded() {
        for (FakeEconomy.Mode mode : List.of(FakeEconomy.Mode.THROW_AFTER_APPLY, FakeEconomy.Mode.THROW_BEFORE_APPLY,
                FakeEconomy.Mode.NULL_RESULT)) {
            f.economy.mode = FakeEconomy.Mode.NORMAL;
            long id = f.list(f.seller, FakeItems.item("minecraft:stone", 1, "u" + mode), 100);
            int before = f.economy.transfers.get();
            f.economy.mode = mode;

            BuyResult result = f.buy(f.buyer, id);

            assertEquals(BuyStatus.PAYMENT_UNCERTAIN, result.status(), mode.name());
            assertEquals(before + 1, f.economy.transfers.get(), "exactly one attempt, never a retry");
            assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(result.tx().id()).orElseThrow().state());
            assertEquals(ListingStatus.RESERVED, f.store.findListing(id).orElseThrow().status());
            assertEquals(0, f.store.pendingDeliveryCount(f.buyer), "no item until a payment is proven");
        }
        assertEquals(0, f.economy.deposits.get(), "and no refund");
    }

    // ------------------------------------------------------------------ database failures

    @Test
    void databaseFailureBeforeThePaymentChargesNothing() {
        long id = f.list(f.seller, sword, 1000);
        f.store.setFaultInjector(op -> {
            if (op.equals("beginPurchase")) {
                throw new StorageException("disk full");
            }
        });

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.ERROR, result.status());
        assertEquals(0, f.economy.transfers.get(), "the economy is never called without a durable intent");
        f.store.setFaultInjector(null);
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, id).status());
    }

    @Test
    void aTransientDatabaseFailureAfterThePaymentIsRetriedAndTheSaleCompletes() {
        long id = f.list(f.seller, sword, 1000);
        AtomicInteger failures = new AtomicInteger(1);
        f.store.setFaultInjector(op -> {
            if (op.equals("confirmPayment") && failures.getAndDecrement() > 0) {
                throw new StorageException("database is locked");
            }
        });

        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, id).status());

        assertEquals(1, f.economy.transfers.get());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void aLastingDatabaseFailureAfterThePaymentLeavesARecoverableTransaction() {
        long id = f.list(f.seller, sword, 1000);
        f.store.setFaultInjector(op -> {
            if (op.equals("confirmPayment")) {
                throw new StorageException("database is gone");
            }
        });

        BuyResult result = f.buy(f.buyer, id);

        assertEquals(BuyStatus.PAID_BUT_UNRECORDED, result.status(), "the buyer is told the truth: charged, not recorded");
        assertEquals(100_000 - 1000, buyerBalance());
        assertEquals(0, f.items.count(sword));
        f.store.setFaultInjector(null);
        assertEquals(TxState.PAYMENT_PENDING, f.store.findTx(result.tx().id()).orElseThrow().state());
        assertTrue(f.log.messages(java.util.logging.Level.SEVERE).stream()
                .anyMatch(m -> m.contains("WAS CHARGED") && m.contains("Transaction " + result.tx().id())));

        f.crashAndRestart();
        assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(result.tx().id()).orElseThrow().state());
        assertTrue(f.service.resolvePayment("Admin", result.tx().id(), true, "buyer was charged, checked balances").join());
        assertEquals(1, f.items.count(sword), "the online buyer received the item when the administrator resolved it");
        assertEquals(0, f.service.collect(f.buyer).join().delivered(), "and a later collect finds nothing more");
        assertEquals(1, f.items.count(sword));
        assertEquals(1, f.economy.transfers.get(), "recovery never pays a second time");
    }

    @Test
    void aRefusedPaymentNeverDeliversAnItem() {
        long id = f.list(f.seller, sword, 1000);
        f.economy.mode = FakeEconomy.Mode.FAIL_STATUS;

        f.buy(f.buyer, id);

        assertTrue(f.items.player(f.buyer).inventory.isEmpty());
        assertFalse(f.store.findDeliveryForListing(id).isPresent());
    }
}
