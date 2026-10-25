package net.kaloyan.smpmarket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.kaloyan.smpmarket.model.Delivery;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.storage.MarketStore;
import net.kaloyan.smpmarket.storage.StorageException;
import net.kaloyan.smpmarket.testutil.FakeItems;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for the SQLite store: schema, migrations, constraints, and contention. */
class StoreTest {

    @TempDir
    Path dir;

    @Test
    void schemaIsCreatedAtVersionOneAndReopenable() throws Exception {
        Path file = dir.resolve("market.db");
        MarketStore.open(file).close();
        MarketStore.open(file).close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            assertEquals(1, s.executeQuery("PRAGMA user_version").getInt(1));
        }
    }

    @Test
    void aDatabaseFromANewerVersionIsRefusedAndLeftUntouched() throws Exception {
        Path file = dir.resolve("market.db");
        MarketStore.open(file).close();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version = 99");
        }
        StorageException e = assertThrows(StorageException.class, () -> MarketStore.open(file));
        assertTrue(e.getMessage().contains("99"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            assertEquals(99, s.executeQuery("PRAGMA user_version").getInt(1));
        }
    }

    @Test
    void theDatabaseItselfRefusesASecondItemForOneListing() throws Exception {
        Path file = dir.resolve("market.db");
        try (MarketStore store = MarketStore.open(file)) {
            UUID owner = UUID.randomUUID();
            long id = store.insertPendingEscrow(owner, "O", FakeItems.item("minecraft:stone", 1, "x"), 100, 10, 1).listingId();
            store.activate(id, 1, 100);
            store.cancel(id, owner, false, 2);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
                SQLException e = assertThrows(SQLException.class, () -> s.execute(
                        "INSERT INTO deliveries (owner, listing_id, reason, status, item_payload, item_material, "
                                + "item_amount, item_description, created_at, updated_at) VALUES ('" + owner + "', " + id
                                + ", 'CANCELLED', 'PENDING', x'00', 'm', 1, '', 1, 1)"));
                assertTrue(e.getMessage().contains("UNIQUE"), e.getMessage());
            }
        }
    }

    @Test
    void concurrentClaimsHandOutEachItemExactlyOnce() throws Exception {
        try (MarketStore store = MarketStore.open(dir.resolve("market.db"))) {
            UUID owner = UUID.randomUUID();
            for (int i = 0; i < 20; i++) {
                ItemSnapshot item = FakeItems.item("minecraft:stone", 1, "c" + i);
                long id = store.insertPendingEscrow(owner, "O", item, 100, 100, 1).listingId();
                store.activate(id, 1, 100);
                store.cancel(id, owner, false, 2);
            }
            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<List<Delivery>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String token = "t" + i;
                futures.add(pool.submit(() -> {
                    go.await();
                    return store.claimDeliveries(owner, token, 20, 3);
                }));
            }
            go.countDown();
            int total = 0;
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Future<List<Delivery>> f : futures) {
                for (Delivery d : f.get()) {
                    total++;
                    assertTrue(ids.add(d.id()), "delivery " + d.id() + " was claimed twice");
                }
            }
            pool.shutdown();
            assertEquals(20, total);
        }
    }

    @Test
    void listingLimitCannotBeExceededByConcurrentSells() throws Exception {
        try (MarketStore store = MarketStore.open(dir.resolve("market.db"))) {
            UUID owner = UUID.randomUUID();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            List<Future<MarketStore.EscrowStart>> futures = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int n = i;
                futures.add(pool.submit(() -> store.insertPendingEscrow(owner, "O",
                        FakeItems.item("minecraft:stone", 1, "l" + n), 100, 5, 1)));
            }
            int accepted = 0;
            for (Future<MarketStore.EscrowStart> f : futures) {
                if (!f.get().limitReached()) {
                    accepted++;
                }
            }
            pool.shutdown();
            assertEquals(5, accepted);
        }
    }

    @Test
    void closedStoreFailsLoudlyInsteadOfLosingData() throws Exception {
        MarketStore store = MarketStore.open(dir.resolve("market.db"));
        store.close();
        assertThrows(StorageException.class, () -> store.insertPendingEscrow(UUID.randomUUID(), "O",
                FakeItems.item("minecraft:stone", 1, "z"), 100, 5, 1));
    }
}
