package net.kaloyan.smpmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import net.kaloyan.smpmarket.model.DeliveryStatus;
import net.kaloyan.smpmarket.model.EscrowResolution;
import net.kaloyan.smpmarket.model.FeeState;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.MarketTx;
import net.kaloyan.smpmarket.model.TxState;
import net.kaloyan.smpmarket.service.MarketResults.BuyStatus;
import net.kaloyan.smpmarket.storage.MarketStore;
import net.kaloyan.smpmarket.testutil.FakeEconomy;
import net.kaloyan.smpmarket.testutil.FakeItems;
import net.kaloyan.smpmarket.testutil.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash-recovery tests. A crash is simulated by writing the durable state a crashed run would have left (through the
 * store, exactly as the service does), then reopening the same database file.
 */
class RecoveryTest {

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

    private MarketTx beginPurchase(long listingId) {
        return f.store.beginPurchase(listingId, f.buyer, "Buyer", false, 0, f.clock.millis()).tx();
    }

    @Test
    void restartWithActiveListingsKeepsThemBuyable() {
        long id = f.list(f.seller, sword, 1000);
        f.restart();
        f.restart();
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
        assertEquals(BuyStatus.PURCHASED, f.buy(f.buyer, id).status());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void crashBetweenEscrowAndActivationBecomesUncertainAndNothingIsAutomatic() {
        MarketStore.EscrowStart start = f.store.insertPendingEscrow(f.seller, "Seller", sword, 1000, 10, f.clock.millis());

        f.crashAndRestart();

        assertEquals(ListingStatus.ESCROW_UNCERTAIN, f.store.findListing(start.listingId()).orElseThrow().status());
        assertEquals(0, f.store.browse(f.clock.millis(), 0, 10).size(), "not for sale");
        assertEquals(0, f.store.pendingDeliveryCount(f.seller), "not returned either");
        assertEquals(1, f.store.issues().escrows().size());
    }

    @Test
    void escrowResolutionsActivateReturnAndAbortEachProduceTheRightThing() {
        long a = f.store.insertPendingEscrow(f.seller, "S", FakeItems.item("minecraft:stone", 1, "a"), 100, 10, 1).listingId();
        long r = f.store.insertPendingEscrow(f.seller, "S", FakeItems.item("minecraft:stone", 1, "r"), 100, 10, 1).listingId();
        long x = f.store.insertPendingEscrow(f.seller, "S", FakeItems.item("minecraft:stone", 1, "x"), 100, 10, 1).listingId();
        f.crashAndRestart();

        assertTrue(f.service.resolveEscrow("Admin", a, EscrowResolution.ACTIVATE, "item was removed").join());
        assertTrue(f.service.resolveEscrow("Admin", r, EscrowResolution.RETURN, "give it back").join());
        assertTrue(f.service.resolveEscrow("Admin", x, EscrowResolution.ABORT, "player still had it").join());

        assertEquals(ListingStatus.ACTIVE, f.store.findListing(a).orElseThrow().status());
        assertEquals(ListingStatus.CANCELLED, f.store.findListing(r).orElseThrow().status());
        assertEquals(ListingStatus.ABORTED, f.store.findListing(x).orElseThrow().status());
        assertEquals(1, f.items.player(f.seller).inventory.size(), "only the returned one became an item");
        assertTrue(f.store.findDeliveryForListing(x).isEmpty(), "abort creates no item");
        assertFalse(f.service.resolveEscrow("Admin", a, EscrowResolution.RETURN, "again").join(), "not uncertain any more");
        assertEquals(3, f.store.auditCount());
    }

    @Test
    void crashBeforeTheEconomyCallBecomesUncertainAndIsNeverPaidAutomatically() {
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = beginPurchase(id);

        f.crashAndRestart();
        f.crashAndRestart(); // repeated recovery attempts

        assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(tx.id()).orElseThrow().state());
        assertEquals(ListingStatus.RESERVED, f.store.findListing(id).orElseThrow().status());
        assertEquals(0, f.economy.transfers.get() + f.economy.withdrawals.get() + f.economy.deposits.get(),
                "recovery never touches the economy");
        assertEquals(0, f.items.count(sword));
        f.service.sweep().join();
        assertEquals(TxState.PAYMENT_UNCERTAIN, f.store.findTx(tx.id()).orElseThrow().state());
    }

    @Test
    void uncertainPaymentResolvedAsPaidDeliversExactlyOnce() {
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = beginPurchase(id);
        f.crashAndRestart();

        assertTrue(f.service.resolvePayment("Admin", tx.id(), true, "balances show the transfer").join());
        assertFalse(f.service.resolvePayment("Admin", tx.id(), true, "again").join(), "a second resolution is refused");
        assertFalse(f.service.resolvePayment("Admin", tx.id(), false, "contradiction").join());
        f.restart();
        f.service.collect(f.buyer).join();

        assertEquals(1, f.items.count(sword));
        assertEquals(ListingStatus.SOLD, f.store.findListing(id).orElseThrow().status());
        assertEquals(TxState.COMPLETED, f.store.findTx(tx.id()).orElseThrow().state());
        assertEquals(0, f.economy.transfers.get(), "the administrator attests; the plugin never pays on recovery");
        assertTrue(f.store.auditTrail().get(0).startsWith("Admin|RESOLVE_PAYMENT_PAID|" + id + "|" + tx.id()));
    }

    @Test
    void uncertainPaymentResolvedAsUnpaidReopensTheListingWithoutAnItem() {
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = beginPurchase(id);
        f.crashAndRestart();

        assertTrue(f.service.resolvePayment("Admin", tx.id(), false, "buyer balance unchanged").join());

        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
        assertEquals(TxState.FAILED, f.store.findTx(tx.id()).orElseThrow().state());
        assertTrue(f.store.findDeliveryForListing(id).isEmpty());
        assertEquals(BuyStatus.PURCHASED, f.buy(f.other, id).status(), "and it can be sold again, once");
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void confirmedPaymentWithPendingDeliverySurvivesRestartsAndDeliversOnce() {
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = beginPurchase(id);
        assertTrue(f.store.confirmPayment(tx.id(), f.clock.millis()));
        assertFalse(f.store.confirmPayment(tx.id(), f.clock.millis()), "confirming twice is a no-op");

        f.crashAndRestart();
        f.crashAndRestart();

        assertEquals(TxState.DELIVERY_PENDING, f.store.findTx(tx.id()).orElseThrow().state());
        assertEquals(1, f.service.collect(f.buyer).join().delivered());
        assertEquals(0, f.service.collect(f.buyer).join().delivered());
        assertEquals(1, f.items.count(sword));
        assertEquals(TxState.COMPLETED, f.store.findTx(tx.id()).orElseThrow().state());
    }

    @Test
    void crashDuringDeliveryNeverDeliversAutomaticallyAgainAndAnAdminDecides() {
        long id = f.list(f.seller, sword, 1000);
        f.service.cancel(f.seller, false, id).join(); // delivered to the seller
        long id2 = f.list(f.seller, FakeItems.item("minecraft:stone", 1, "second"), 1000);
        f.items.player(f.seller).online = false;
        f.service.cancel(f.seller, false, id2).join(); // stays pending (owner offline)
        // A crashed run had claimed the pending delivery:
        var claimed = f.store.claimDeliveries(f.seller, "dead-run", 10, f.clock.millis());
        assertEquals(1, claimed.size());
        f.items.player(f.seller).online = true;

        f.crashAndRestart();
        f.crashAndRestart();

        long deliveryId = claimed.get(0).id();
        assertEquals(DeliveryStatus.UNCERTAIN, f.store.findDelivery(deliveryId).orElseThrow().status());
        assertEquals(0, f.service.collect(f.seller).join().delivered(), "never redelivered automatically");
        assertEquals(1, f.store.issues().deliveries().size());

        assertTrue(f.service.resolveDelivery("Admin", deliveryId, false, "player confirms nothing arrived").join());
        assertEquals(1, f.service.collect(f.seller).join().delivered());
        assertEquals(0, f.service.collect(f.seller).join().delivered());
        assertFalse(f.service.resolveDelivery("Admin", deliveryId, true, "late").join());
    }

    @Test
    void uncertainDeliveryResolvedAsDeliveredNeverCreatesAnItem() {
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = beginPurchase(id);
        f.store.confirmPayment(tx.id(), f.clock.millis());
        var claimed = f.store.claimDeliveries(f.buyer, "dead-run", 10, f.clock.millis());
        f.crashAndRestart();

        assertTrue(f.service.resolveDelivery("Admin", claimed.get(0).id(), true, "buyer has it").join());

        assertEquals(0, f.items.count(sword));
        assertEquals(0, f.service.collect(f.buyer).join().delivered());
        assertEquals(TxState.COMPLETED, f.store.findTx(tx.id()).orElseThrow().state());
    }

    @Test
    void crashDuringFeeCollectionBecomesUncertainAndIsResolvedByAnAdmin() {
        f.settings(Fixture.settings(10, java.time.Duration.ofHours(72), 1, 100_000_000, 1, 64, 1000, false, java.util.List.of()));
        long id = f.list(f.seller, sword, 1000);
        MarketTx tx = beginPurchase(id);
        f.store.confirmPayment(tx.id(), f.clock.millis());
        // beginPurchase above used fee 0; start a fresh listing with a real fee
        long id2 = f.list(f.seller, FakeItems.item("minecraft:stone", 1, "fee"), 1000);
        MarketTx feeTx = f.store.beginPurchase(id2, f.buyer, "Buyer", false, 1000, f.clock.millis()).tx();
        f.store.confirmPayment(feeTx.id(), f.clock.millis());
        assertTrue(f.store.beginFee(feeTx.id(), f.clock.millis()));

        f.crashAndRestart();
        f.service.sweep().join();

        assertEquals(FeeState.UNCERTAIN, f.store.findTx(feeTx.id()).orElseThrow().feeState());
        assertEquals(0, f.economy.withdrawals.get(), "no automatic retry");
        assertTrue(f.service.resolveFee("Admin", feeTx.id(), false, "seller balance unchanged").join());
        f.service.sweep().join();
        assertEquals(1, f.economy.withdrawals.get());
        assertEquals(FeeState.COLLECTED, f.store.findTx(feeTx.id()).orElseThrow().feeState());
    }

    @Test
    void recoveryIsIdempotentAndReportsOnlyTheFirstTime() {
        long id = f.list(f.seller, sword, 1000);
        beginPurchase(id);
        f.store.insertPendingEscrow(f.seller, "S", FakeItems.item("minecraft:stone", 1, "e"), 100, 10, 1);
        f.store.close();
        f.store = MarketStore.open(dir.resolve("market.db"));

        MarketStore.RestartRecovery first = f.store.recoverAfterRestart(1);
        MarketStore.RestartRecovery second = f.store.recoverAfterRestart(2);

        assertEquals(1, first.payments());
        assertEquals(1, first.escrows());
        assertFalse(second.any());
    }

    @Test
    void noItemIsEverDuplicatedAcrossAMixOfOperationsAndRestarts() {
        UUID[] people = {f.seller, f.buyer, f.other};
        ItemSnapshot[] things = new ItemSnapshot[6];
        for (int i = 0; i < things.length; i++) {
            things[i] = FakeItems.item("minecraft:stone", 1, "mix" + i);
            f.list(f.seller, things[i], 100 + i);
        }
        f.buy(f.buyer, 1);
        f.service.cancel(f.seller, false, 2).join();
        f.items.player(f.other).capacity = 0;
        f.buy(f.other, 3);
        f.restart();
        f.economy.mode = FakeEconomy.Mode.THROW_AFTER_APPLY;
        f.buy(f.buyer, 4);
        f.economy.mode = FakeEconomy.Mode.NORMAL;
        f.crashAndRestart();
        f.items.player(f.other).capacity = 36;
        for (UUID p : people) {
            f.service.collect(p).join();
        }
        f.service.sweep().join();

        for (int i = 0; i < things.length; i++) {
            long inWorld = f.items.count(things[i]);
            long owed = f.store.pendingDeliveryCount(f.seller) + f.store.pendingDeliveryCount(f.buyer)
                    + f.store.pendingDeliveryCount(f.other);
            assertTrue(inWorld <= 1, "item " + i + " exists " + inWorld + " times");
            assertTrue(owed >= 0);
        }
        // Items 1,2,3 left the listing system; 4 is held by the uncertain payment; 5,6 are still for sale.
        assertEquals(1, f.items.count(things[0]));
        assertEquals(1, f.items.count(things[1]));
        assertEquals(1, f.items.count(things[2]));
        assertEquals(0, f.items.count(things[3]), "held in the reserved listing until an administrator decides");
    }
}
