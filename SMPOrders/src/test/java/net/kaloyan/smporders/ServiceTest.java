package net.kaloyan.smporders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceTest {

    @TempDir
    Path dir;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final Map<UUID, Long> money = new HashMap<>();
    private Bank.Result withdrawResult;
    private Bank.Result depositResult;
    private long now = 1_000_000L;
    private OrderStore store;
    private OrderService service;
    private static final ItemKey DIAMOND = ItemKey.plain(Material.DIAMOND);

    @BeforeEach
    void setUp() throws Exception {
        money.put(alice, 100_000L);       // $1000.00
        money.put(bob, 0L);
        store = new OrderStore(dir.resolve("orders.yml"));
        service = newService();
    }

    private OrderService newService() throws Exception {
        Settings settings = Settings.parse(new YamlConfiguration());
        Bank bank = new Bank() {
            @Override
            public Result withdraw(UUID p, long cents) {
                if (withdrawResult != null) {
                    return withdrawResult;
                }
                if (money.getOrDefault(p, 0L) < cents) {
                    return Result.INSUFFICIENT;
                }
                money.merge(p, -cents, Long::sum);
                return Result.PAID;
            }

            @Override
            public Result deposit(UUID p, long cents) {
                if (depositResult != null) {
                    return depositResult;
                }
                money.merge(p, cents, Long::sum);
                return Result.PAID;
            }
        };
        return new OrderService(store, bank, () -> settings, Logger.getLogger("test"), () -> now);
    }

    private Order order(int amount, long price) {
        OrderService.Result r = service.create(alice, "Alice", DIAMOND, amount, price);
        assertEquals(OrderService.Status.OK, r.status());
        return r.order();
    }

    // ---------------------------------------------------------------- creating

    @Test
    void creatingTakesThePriceAndTheTaxAndSaves() throws Exception {
        OrderService.Result r = service.create(alice, "Alice", DIAMOND, 10, 1000);       // 10 x $10.00 = $100 + 5% tax
        assertEquals(OrderService.Status.OK, r.status());
        assertEquals(10_500, r.cents());
        assertEquals(100_000 - 10_500, money.get(alice));
        OrderStore again = new OrderStore(dir.resolve("orders.yml"));
        again.load();
        Order o = again.get(r.order().id());
        assertEquals(10, o.amount());
        assertEquals(DIAMOND, o.item());
        assertEquals(1000, o.priceCents());
        assertEquals(now + 7 * 86_400_000L, o.expires());
    }

    @Test
    void limitsAreEnforced() {
        assertEquals(OrderService.Status.BAD_PRICE, service.create(alice, "A", DIAMOND, 1, 0).status());
        assertEquals(OrderService.Status.BAD_PRICE, service.create(alice, "A", DIAMOND, 1, 5_000_000_000_0L).status());
        assertEquals(OrderService.Status.BAD_AMOUNT, service.create(alice, "A", DIAMOND, 0, 100).status());
        assertEquals(OrderService.Status.BAD_AMOUNT, service.create(alice, "A", DIAMOND, 100_001, 100).status());
        for (int i = 0; i < 10; i++) {
            order(1, 100);
        }
        assertEquals(OrderService.Status.MAX_ORDERS, service.create(alice, "A", DIAMOND, 1, 100).status());
        assertEquals(100_000 - 10 * 105, money.get(alice));
    }

    @Test
    void tooLittleMoneyMakesNoOrder() {
        assertEquals(OrderService.Status.BROKE, service.create(alice, "A", DIAMOND, 1000, 100_000).status());
        assertEquals(0, store.size());
    }

    @Test
    void anUnconfirmedPaymentMakesNoOrderAndRefundsNothing() {
        withdrawResult = Bank.Result.UNCERTAIN;
        assertEquals(OrderService.Status.UNSURE, service.create(alice, "A", DIAMOND, 1, 100).status());
        assertEquals(0, store.size());
        assertEquals(100_000, money.get(alice));
    }

    @Test
    void ifSavingFailsTheMoneyComesBack() throws Exception {
        Files.createDirectory(dir.resolve("orders.yml.tmp"));
        assertEquals(OrderService.Status.SAVE_FAILED, service.create(alice, "A", DIAMOND, 1, 1000).status());
        assertEquals(0, store.size());
        assertEquals(100_000, money.get(alice));
    }

    // ---------------------------------------------------------------- delivering

    @Test
    void aDeliveryPaysTheDelivererFromTheEscrow() {
        Order o = order(10, 1000);
        OrderService.Result r = service.deliver(bob, o, 4);
        assertEquals(OrderService.Status.OK, r.status());
        assertEquals(4000, r.cents());
        assertFalse(r.pending());
        assertEquals(4000, money.get(bob));
        assertEquals(4, o.filled());
        assertEquals(4, o.collectable());
        assertTrue(o.open());
        assertEquals(6000, o.escrowCents());
    }

    @Test
    void theLastDeliveryCompletesTheOrder() {
        Order o = order(2, 500);
        service.deliver(bob, o, 2);
        assertEquals(Order.Closed.COMPLETE, o.closed());
        assertFalse(o.open());
        assertEquals(OrderService.Status.NOT_OPEN, service.deliver(bob, o, 1).status());
    }

    @Test
    void deliveryRules() {
        Order o = order(5, 100);
        assertEquals(OrderService.Status.OWN_ORDER, service.deliver(alice, o, 1).status());
        assertEquals(OrderService.Status.BAD_AMOUNT, service.deliver(bob, o, 6).status());
        assertEquals(OrderService.Status.BAD_AMOUNT, service.deliver(bob, o, 0).status());
        assertEquals(0, o.filled());
        assertEquals(0, money.get(bob));
    }

    @Test
    void ifSavingFailsNothingIsDelivered() throws Exception {
        Order o = order(5, 100);
        Files.createDirectory(dir.resolve("orders.yml.tmp"));
        assertEquals(OrderService.Status.SAVE_FAILED, service.deliver(bob, o, 3).status());
        assertEquals(0, o.filled());
        assertEquals(0, store.owed(bob));
        assertEquals(0, money.get(bob));
    }

    @Test
    void aPayoutThatFailsIsKeptAndPaidLater() {
        Order o = order(5, 100);
        depositResult = Bank.Result.FAILED;
        OrderService.Result r = service.deliver(bob, o, 5);
        assertEquals(OrderService.Status.OK, r.status());
        assertTrue(r.pending());
        assertEquals(500, store.owed(bob));
        assertEquals(0, money.get(bob));
        depositResult = null;
        service.payAll();
        assertEquals(500, money.get(bob));
        assertEquals(0, store.owed(bob));
    }

    @Test
    void anUnconfirmedPayoutIsNotPaidAgain() {
        Order o = order(5, 100);
        depositResult = Bank.Result.UNCERTAIN;
        service.deliver(bob, o, 5);
        assertEquals(0, store.owed(bob));
        depositResult = null;
        service.payAll();
        assertEquals(0, money.get(bob));
    }

    // ---------------------------------------------------------------- cancelling

    @Test
    void cancellingRefundsTheUndeliveredPartWithoutTheTax() {
        Order o = order(10, 1000);
        service.deliver(bob, o, 4);
        long before = money.get(alice);
        OrderService.Result r = service.cancel(alice, false, o);
        assertEquals(OrderService.Status.OK, r.status());
        assertEquals(6000, r.cents());
        assertEquals(before + 6000, money.get(alice));
        assertEquals(Order.Closed.CANCELLED, o.closed());
        assertEquals(o, store.get(o.id()), "delivered items still wait to be collected");
        assertEquals(OrderService.Status.NOT_OPEN, service.cancel(alice, false, o).status());
    }

    @Test
    void cancellingWithNothingDeliveredRemovesTheOrder() {
        Order o = order(3, 100);
        service.cancel(alice, false, o);
        assertNull(store.get(o.id()));
        assertEquals(100_000 - 15, money.get(alice));       // the tax (5% of 300) stays paid
    }

    @Test
    void onlyTheOwnerOrAnAdminCancels() {
        Order o = order(3, 100);
        assertEquals(OrderService.Status.NOT_OWNER, service.cancel(bob, false, o).status());
        assertEquals(OrderService.Status.OK, service.cancel(bob, true, o).status());
        assertEquals(100_000 - 15, money.get(alice), "the refund goes to the owner");
    }

    // ---------------------------------------------------------------- collecting

    @Test
    void collectingGivesTheItemsAndKeepsWhatDoesNotFit() {
        Order o = order(10, 100);
        service.deliver(bob, o, 10);
        OrderService.Result r = service.collect(alice, o, n -> 3);        // 3 did not fit
        assertEquals(OrderService.Status.OK, r.status());
        assertEquals(7, r.cents());
        assertEquals(3, o.collectable());
        assertEquals(o, store.get(o.id()));
        service.collect(alice, o, n -> 0);
        assertNull(store.get(o.id()), "finished and collected: gone");
    }

    @Test
    void onlyTheBuyerCollects() {
        Order o = order(2, 100);
        service.deliver(bob, o, 2);
        assertEquals(OrderService.Status.NOT_OWNER, service.collect(bob, o, n -> 0).status());
        assertEquals(2, o.collectable());
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void expiredOrdersAreRefundedOnce() {
        Order o = order(10, 1000);
        service.deliver(bob, o, 1);
        long before = money.get(alice);
        now += 8 * 86_400_000L;
        List<Order> expired = service.expireDue();
        assertEquals(List.of(o), expired);
        assertEquals(before + 9000, money.get(alice));
        assertEquals(Order.Closed.EXPIRED, o.closed());
        assertTrue(service.expireDue().isEmpty());
        assertEquals(before + 9000, money.get(alice));
    }

    @Test
    void openOrdersDoNotExpireEarly() {
        order(1, 100);
        now += 6 * 86_400_000L;
        assertTrue(service.expireDue().isEmpty());
    }

    // ---------------------------------------------------------------- the file

    @Test
    void everythingSurvivesARestart() throws Exception {
        Order o = order(10, 1000);
        service.deliver(bob, o, 4);
        depositResult = Bank.Result.FAILED;
        service.deliver(bob, o, 1);
        OrderStore again = new OrderStore(dir.resolve("orders.yml"));
        again.load();
        Order loaded = again.get(o.id());
        assertEquals(5, loaded.filled());
        assertEquals(5, loaded.collectable());
        assertEquals(1000, again.owed(bob));
        assertEquals(o.id() + 1, again.nextId());
    }

    @Test
    void aDamagedFileIsNeverReplaced() throws Exception {
        Files.writeString(dir.resolve("orders.yml"), "orders:\n  '1':\n    owner: nope\n");
        OrderStore damaged = new OrderStore(dir.resolve("orders.yml"));
        assertThrows(IOException.class, damaged::load);
    }

    @Test
    void itemKeysRoundTrip() {
        ItemKey key = ItemKey.decode("DIAMOND_SWORD|unbreaking:3,sharpness:5");
        assertEquals("DIAMOND_SWORD|sharpness:5,unbreaking:3", key.encode());
        assertEquals("Diamond Sword (Sharpness 5, Unbreaking 3)", key.label());
        assertEquals(key, ItemKey.decode(key.encode()));
        assertNull(ItemKey.decode("NOT_AN_ITEM"));
        assertNull(ItemKey.decode("DIAMOND|bad:x"));
        assertNull(ItemKey.decode("DIAMOND|sharpness:0"));
    }
}
