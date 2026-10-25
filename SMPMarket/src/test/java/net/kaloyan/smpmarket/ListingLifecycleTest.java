package net.kaloyan.smpmarket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.kaloyan.smpmarket.model.DeliveryStatus;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.model.Listing;
import net.kaloyan.smpmarket.model.ListingStatus;
import net.kaloyan.smpmarket.model.Price;
import net.kaloyan.smpmarket.service.MarketResults.CancelResult;
import net.kaloyan.smpmarket.service.MarketResults.CancelStatus;
import net.kaloyan.smpmarket.service.MarketResults.CollectResult;
import net.kaloyan.smpmarket.service.MarketResults.SellResult;
import net.kaloyan.smpmarket.service.MarketResults.SellStatus;
import net.kaloyan.smpmarket.service.MarketService;
import net.kaloyan.smpmarket.testutil.FakeItems;
import net.kaloyan.smpmarket.testutil.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests (real SQLite, fake economy and inventory): listing, cancellation, expiry and delivery. */
class ListingLifecycleTest {

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

    @Test
    void successfulListingTakesTheItemAndStoresItsExactBytes() {
        f.items.player(f.seller).hand = sword;
        SellResult result = f.service.sell(f.seller, "Seller", 1250).join();

        assertEquals(SellStatus.LISTED, result.status());
        assertNull(f.items.player(f.seller).hand, "the item must leave the hand");
        assertEquals(0, f.items.count(sword), "and exist nowhere in the world except the listing");
        Listing listing = f.store.findListing(result.listingId()).orElseThrow();
        assertEquals(ListingStatus.ACTIVE, listing.status());
        assertEquals(1250, listing.priceMinor());
        assertArrayEquals(sword.payload(), listing.item().payload());
        assertEquals(f.clock.millis() + Duration.ofHours(72).toMillis(), listing.expiresAt());
    }

    @Test
    void itemSerializationSurvivesTheDatabaseByteForByte() {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }
        ItemSnapshot odd = new ItemSnapshot(allBytes, "minecraft:enchanted_book", 3, "Sharpness 5, \"quoted\" ünïcode");
        long id = f.list(f.seller, odd, 100);
        f.restart();
        Listing back = f.store.findListing(id).orElseThrow();
        assertEquals(odd, back.item());
        assertArrayEquals(allBytes, back.item().payload());
    }

    @Test
    void emptyHandIsRejected() {
        f.items.player(f.seller).hand = null;
        assertEquals(SellStatus.EMPTY_HAND, f.service.sell(f.seller, "Seller", 100).join().status());
        assertEquals(0, f.store.ownerListings(f.seller).size());
    }

    @Test
    void invalidPricesAreRejectedBeforeTheItemIsTouched() {
        f.items.player(f.seller).hand = sword;
        assertEquals(SellStatus.PRICE_TOO_LOW, f.service.sell(f.seller, "Seller", 0).join().status());
        assertEquals(SellStatus.PRICE_TOO_LOW, f.service.sell(f.seller, "Seller", -5).join().status());
        assertEquals(SellStatus.PRICE_TOO_HIGH, f.service.sell(f.seller, "Seller", 100_000_001).join().status());
        f.economy.maxTransaction = new java.math.BigDecimal("50.00");
        assertEquals(SellStatus.ECONOMY_LIMIT, f.service.sell(f.seller, "Seller", 5_001).join().status());
        assertEquals(sword, f.items.player(f.seller).hand, "the item stays in the hand");
        assertEquals(0, f.store.adminListings(null, 0, 10).size(), "no listing row was written");
    }

    @Test
    void priceTextParsingRejectsEverythingButPlainTwoDecimalAmounts() throws Exception {
        assertEquals(1250, Price.parseMinor("12.50"));
        assertEquals(1200, Price.parseMinor("12"));
        assertEquals(5, Price.parseMinor("0.05"));
        for (String bad : List.of("", "-1", "+1", "1.234", "1e3", "abc", "1,000", " 1", "1 ", ".5", "5.", "NaN",
                "99999999999999999", "1000000000000000.01")) {
            try {
                Price.parseMinor(bad);
                throw new AssertionError("accepted: '" + bad + "'");
            } catch (Price.InvalidPriceException expected) {
                // rejected as required
            }
        }
    }

    @Test
    void listingLimitIsEnforced() {
        f.settings(Fixture.settings(2, Duration.ofHours(72), 1, 100_000_000, 1, 64, 0, false, List.of()));
        f.list(f.seller, FakeItems.item("minecraft:stone", 1, "a"), 100);
        f.list(f.seller, FakeItems.item("minecraft:stone", 1, "b"), 100);
        ItemSnapshot third = FakeItems.item("minecraft:stone", 1, "c");
        f.items.player(f.seller).hand = third;

        SellResult result = f.service.sell(f.seller, "Seller", 100).join();

        assertEquals(SellStatus.LIMIT_REACHED, result.status());
        assertEquals(third, f.items.player(f.seller).hand, "a refused listing leaves the item alone");
        assertEquals(2, f.store.ownerListings(f.seller).size());
    }

    @Test
    void stackSizeAndRestrictedItemsAreRejected() {
        f.settings(Fixture.settings(10, Duration.ofHours(72), 1, 100_000_000, 2, 16, 0, false, List.of("*_SHULKER_BOX", "BEDROCK")));
        f.items.player(f.seller).hand = FakeItems.item("minecraft:stone", 1, "one");
        assertEquals(SellStatus.AMOUNT_OUT_OF_RANGE, f.service.sell(f.seller, "S", 100).join().status());
        f.items.player(f.seller).hand = FakeItems.item("minecraft:stone", 17, "many");
        assertEquals(SellStatus.AMOUNT_OUT_OF_RANGE, f.service.sell(f.seller, "S", 100).join().status());
        f.items.player(f.seller).hand = FakeItems.item("minecraft:red_shulker_box", 2, "box");
        assertEquals(SellStatus.ITEM_RESTRICTED, f.service.sell(f.seller, "S", 100).join().status());
        f.items.player(f.seller).hand = FakeItems.item("minecraft:bedrock", 2, "bed");
        assertEquals(SellStatus.ITEM_RESTRICTED, f.service.sell(f.seller, "S", 100).join().status());
        f.items.player(f.seller).hand = FakeItems.item("minecraft:stone", 2, "ok");
        assertEquals(SellStatus.LISTED, f.service.sell(f.seller, "S", 100).join().status());
    }

    @Test
    void itemThatChangesBetweenCommandAndRemovalAbortsTheListingWithoutLosingAnything() {
        f.items.player(f.seller).hand = sword;
        ItemSnapshot other = FakeItems.item("minecraft:stone", 1, "swapped");
        f.items.beforeRemove = id -> f.items.player(id).hand = other; // the player swaps items in that window

        SellResult result = f.service.sell(f.seller, "Seller", 100).join();

        assertEquals(SellStatus.ITEM_CHANGED, result.status());
        assertEquals(other, f.items.player(f.seller).hand);
        List<Listing> all = f.store.adminListings(null, 0, 10);
        assertEquals(1, all.size());
        assertEquals(ListingStatus.ABORTED, all.get(0).status());
        assertEquals(0, f.store.pendingDeliveryCount(f.seller), "an aborted listing never owes an item");
    }

    @Test
    void overlappingSellsOfTheSamePlayerAreRefused() {
        QueueExecutor storage = new QueueExecutor();
        MarketService queued = f.newService(f.store, storage, Fixture.INLINE);
        f.items.player(f.seller).hand = sword;

        CompletableFuture<SellResult> first = queued.sell(f.seller, "Seller", 100);
        CompletableFuture<SellResult> second = queued.sell(f.seller, "Seller", 100);

        assertEquals(SellStatus.BUSY, second.join().status());
        storage.runAll();
        assertEquals(SellStatus.LISTED, first.join().status());
        assertEquals(1, f.store.ownerListings(f.seller).size(), "the item was listed once");
        assertEquals(0, f.items.count(sword));
    }

    @Test
    void ownerCancelReturnsTheExactItemAndOnlyOnce() {
        long id = f.list(f.seller, sword, 100);

        CancelResult first = f.service.cancel(f.seller, false, id).join();
        CancelResult second = f.service.cancel(f.seller, false, id).join();

        assertEquals(CancelStatus.CANCELLED, first.status());
        assertTrue(first.delivered());
        assertEquals(CancelStatus.NOT_ACTIVE, second.status());
        assertEquals(1, f.items.count(sword), "exactly one item came back");
        assertEquals(ListingStatus.CANCELLED, f.store.findListing(id).orElseThrow().status());
        assertEquals(DeliveryStatus.DELIVERED, f.store.findDeliveryForListing(id).orElseThrow().status());
    }

    @Test
    void cancelByANonOwnerIsRefusedAndChangesNothing() {
        long id = f.list(f.seller, sword, 100);

        CancelResult result = f.service.cancel(f.other, false, id).join();

        assertEquals(CancelStatus.NOT_OWNER, result.status());
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());
        assertEquals(0, f.items.count(sword));
        assertEquals(CancelStatus.NOT_FOUND, f.service.cancel(f.seller, false, 9999).join().status());
    }

    @Test
    void administratorCancelReturnsTheItemToItsOwnerNotToTheAdministrator() {
        long id = f.list(f.seller, sword, 100);

        CancelResult result = f.service.adminCancel(f.other, "Admin", id, "rule violation").join();

        assertEquals(CancelStatus.CANCELLED, result.status());
        assertEquals(1, f.items.player(f.seller).inventory.size());
        assertEquals(0, f.items.player(f.other).inventory.size());
        assertTrue(f.store.auditTrail().get(0).startsWith("Admin|ADMIN_CANCEL|" + id));
    }

    @Test
    void fullInventoryKeepsTheItemPendingUntilThereIsRoom() {
        long id = f.list(f.seller, sword, 100);
        f.items.player(f.seller).capacity = 0;

        CancelResult cancelled = f.service.cancel(f.seller, false, id).join();

        assertEquals(CancelStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.delivered());
        assertEquals(0, f.items.count(sword));
        assertEquals(1, f.store.pendingDeliveryCount(f.seller), "the item is persisted, not dropped");

        CollectResult stillFull = f.service.collect(f.seller).join();
        assertEquals(0, stillFull.delivered());
        assertEquals(1, stillFull.remaining());

        f.items.player(f.seller).capacity = 36;
        CollectResult ok = f.service.collect(f.seller).join();
        assertEquals(1, ok.delivered());
        assertEquals(0, ok.remaining());
        assertEquals(1, f.items.count(sword));
        assertEquals(0, f.service.collect(f.seller).join().delivered(), "a repeated collect delivers nothing more");
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void offlineOwnerGetsTheItemAtTheNextCollect() {
        long id = f.list(f.seller, sword, 100);
        f.items.player(f.seller).online = false;

        CancelResult cancelled = f.service.cancel(f.seller, false, id).join();
        assertEquals(CancelStatus.CANCELLED, cancelled.status());
        assertFalse(cancelled.delivered());
        assertEquals(1, f.store.pendingDeliveryCount(f.seller));

        f.restart(); // survives a restart too
        f.items.player(f.seller).online = true;
        assertEquals(1, f.service.collect(f.seller).join().delivered());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void listingsExpireByStoredTimeAndTheItemIsReturned() {
        long id = f.list(f.seller, sword, 100);

        f.clock.advance(Duration.ofHours(71));
        assertEquals(0, f.service.sweep().join());
        assertEquals(ListingStatus.ACTIVE, f.store.findListing(id).orElseThrow().status());

        f.clock.advance(Duration.ofHours(2));
        assertEquals(1, f.service.sweep().join());
        assertEquals(0, f.service.sweep().join(), "sweeping twice expires nothing twice");
        assertEquals(ListingStatus.EXPIRED, f.store.findListing(id).orElseThrow().status());
        assertEquals(1, f.items.count(sword), "delivered to the online owner");
    }

    @Test
    void expiryDoesNotDependOnInMemoryTimersAndSurvivesARestart() {
        long id = f.list(f.seller, sword, 100);
        f.items.player(f.seller).online = false;
        f.clock.advance(Duration.ofHours(100));

        f.restart(); // recovery performs the sweep

        assertEquals(ListingStatus.EXPIRED, f.store.findListing(id).orElseThrow().status());
        assertEquals(1, f.store.pendingDeliveryCount(f.seller));
        f.items.player(f.seller).online = true;
        assertEquals(1, f.service.collect(f.seller).join().delivered());
        assertEquals(1, f.items.count(sword));
    }

    @Test
    void browseIsPaginatedNewestFirstAndClampsOutOfRangePages() {
        f.settings(Fixture.settings(50, Duration.ofHours(72), 1, 100_000_000, 1, 64, 0, false, List.of()));
        long newest = 0;
        for (int i = 0; i < 12; i++) {
            newest = f.list(f.seller, FakeItems.item("minecraft:stone", 1, "p" + i), 100);
        }
        var page1 = f.service.browse(1).join();
        var page2 = f.service.browse(2).join();
        assertEquals(MarketService.PAGE_SIZE, page1.items().size());
        assertEquals(4, page2.items().size());
        assertEquals(2, page1.pages());
        assertEquals(newest, page1.items().get(0).id(), "newest first");
        var clamped = f.service.browse(999).join();
        assertEquals(2, clamped.page(), "an out-of-range page is clamped, not an error");
        assertEquals(1, f.service.browse(-3).join().page());
    }

    /** Executor that keeps tasks until told to run them, to hold an operation "in flight". */
    static final class QueueExecutor implements Executor {
        private final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    next = tasks.poll();
                }
                if (next == null) {
                    return;
                }
                next.run();
            }
        }
    }
}
