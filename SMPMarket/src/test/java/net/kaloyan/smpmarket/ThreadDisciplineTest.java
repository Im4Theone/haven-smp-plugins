package net.kaloyan.smpmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.service.MarketResults.BuyStatus;
import net.kaloyan.smpmarket.service.MarketResults.SellStatus;
import net.kaloyan.smpmarket.service.MarketService;
import net.kaloyan.smpmarket.testutil.FakeItems;
import net.kaloyan.smpmarket.testutil.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the threading rules with two real, named threads: database work off the main thread, inventory on it. */
class ThreadDisciplineTest {

    @TempDir
    Path dir;

    @Test
    void databaseOnlyOnTheStorageThreadAndInventoryOnlyOnTheMainThread() throws Exception {
        Fixture f = new Fixture(dir);
        ExecutorService storage = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-storage"));
        ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-main"));
        List<String> dbThreads = new CopyOnWriteArrayList<>();
        try {
            f.store.setFaultInjector(op -> dbThreads.add(Thread.currentThread().getName()));
            f.items.requiredThread = "test-main";
            MarketService service = f.newService(f.store, storage, main);
            ItemSnapshot sword = FakeItems.item("minecraft:diamond_sword", 1, "sword");

            // /market sell runs on the main thread, as a command executor does.
            f.items.player(f.seller).hand = null;
            main.submit(() -> f.items.player(f.seller).hand = sword).get();
            SellStatus sell = main.submit(() -> service.sell(f.seller, "Seller", 1000)).get().get(10, TimeUnit.SECONDS).status();
            assertEquals(SellStatus.LISTED, sell);

            assertEquals(BuyStatus.PURCHASED, service.buy(f.buyer, "Buyer", 1).get(10, TimeUnit.SECONDS).status());
            f.items.player(f.seller).hand = null;
            main.submit(() -> f.items.player(f.seller).hand = FakeItems.item("minecraft:stone", 1, "s2")).get();
            main.submit(() -> service.sell(f.seller, "Seller", 100)).get().get(10, TimeUnit.SECONDS);
            service.cancel(f.seller, false, 2).get(10, TimeUnit.SECONDS);
            service.collect(f.seller).get(10, TimeUnit.SECONDS);
            service.sweep().get(10, TimeUnit.SECONDS);
            service.browse(1).get(10, TimeUnit.SECONDS);
            service.history(f.buyer, 1).get(10, TimeUnit.SECONDS);
            f.clock.advance(Duration.ofHours(1));

            assertTrue(!dbThreads.isEmpty());
            assertTrue(dbThreads.stream().allMatch("test-storage"::equals), "database used on " + dbThreads);
            assertEquals(List.of(), f.items.violations, "inventory/player access happened off the main thread");
        } finally {
            f.store.setFaultInjector(null);
            storage.shutdown();
            main.shutdown();
            f.close();
        }
    }
}
