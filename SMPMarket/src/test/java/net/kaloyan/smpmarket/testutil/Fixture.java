package net.kaloyan.smpmarket.testutil;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kaloyan.smpmarket.config.MarketSettings;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.service.MarketResults.BuyResult;
import net.kaloyan.smpmarket.service.MarketResults.SellResult;
import net.kaloyan.smpmarket.service.MarketService;
import net.kaloyan.smpmarket.storage.MarketStore;

/**
 * A complete market on a temporary database with deterministic collaborators. Storage and "main thread" are both
 * inline (same-thread) executors, so every call has finished when it returns and tests are deterministic.
 * {@link #restart()} reopens the same database file, which is how crashes are simulated: whatever state was
 * left in the file is exactly what a restarted server would find.
 */
public final class Fixture implements AutoCloseable {

    public static final Executor INLINE = Runnable::run;

    public final Path dir;
    public final LogCapture log = new LogCapture();
    public final MutableClock clock = new MutableClock();
    public final FakeEconomy economy = new FakeEconomy();
    public final FakeItems items = new FakeItems();
    public final AtomicReference<MarketSettings> settings = new AtomicReference<>(MarketSettings.defaults());
    public MarketStore store;
    public MarketService service;

    public final UUID seller;
    public final UUID buyer;
    public final UUID other;

    public Fixture(Path dir) {
        this.dir = dir;
        this.store = MarketStore.open(dir.resolve("market.db"));
        this.seller = items.addPlayer();
        this.buyer = items.addPlayer();
        this.other = items.addPlayer();
        economy.mode = FakeEconomy.Mode.NORMAL;
        accounts();
        this.service = newService(store, INLINE, INLINE);
        service.recoverOnStartup().join();
    }

    private void accounts() {
        // Players are given accounts under their own UUIDs.
        for (UUID id : new UUID[] {seller, buyer, other}) {
            economy.account(id, "1000.00");
        }
    }

    public MarketService newService(MarketStore withStore, Executor storage, Executor mainThread) {
        return new MarketService(withStore, economy, items, settings::get, storage, mainThread, clock, log.logger());
    }

    /** Simulates a server restart: reopen the same file and run startup recovery. */
    public void restart() {
        service.concludeOpenClaims();
        store.close();
        store = MarketStore.open(dir.resolve("market.db"));
        service = newService(store, INLINE, INLINE);
        service.recoverOnStartup().join();
    }

    /** Simulates a crash: reopen the file without any orderly shutdown work. */
    public void crashAndRestart() {
        store.close();
        store = MarketStore.open(dir.resolve("market.db"));
        service = newService(store, INLINE, INLINE);
        service.recoverOnStartup().join();
    }

    public void settings(MarketSettings changed) {
        settings.set(changed);
    }

    /** Gives {@code owner} {@code item} in the main hand, lists it, and returns the listing id. */
    public long list(UUID owner, ItemSnapshot item, long priceMinor) {
        items.player(owner).hand = item;
        SellResult result = service.sell(owner, "Seller", priceMinor).join();
        if (result.status() != net.kaloyan.smpmarket.service.MarketResults.SellStatus.LISTED) {
            throw new IllegalStateException("listing failed: " + result.status());
        }
        return result.listingId();
    }

    public BuyResult buy(UUID who, long listingId) {
        return service.buy(who, "Buyer", listingId).join();
    }

    public MarketSettings withSettings(java.util.function.UnaryOperator<MarketSettings> change) {
        MarketSettings next = change.apply(settings.get());
        settings.set(next);
        return next;
    }

    public static MarketSettings settings(int maxPerPlayer, Duration duration, long minPrice, long maxPrice, int minAmount,
                                          int maxAmount, int feeHundredths, boolean self, java.util.List<String> blocked) {
        return new MarketSettings(maxPerPlayer, duration, minPrice, maxPrice, minAmount, maxAmount, feeHundredths, self,
                blocked, false);
    }

    public static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        store.close();
    }
}
