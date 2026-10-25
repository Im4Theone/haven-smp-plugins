package net.kaloyan.smpauction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final UUID alice = UUID.randomUUID();     // seller
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final Map<UUID, Long> money = new HashMap<>();
    private Bank.Result withdrawResult;
    private Bank.Result depositResult;
    private long now = 1_000_000_000L;
    private AuctionStore store;
    private AuctionService service;
    private Settings settings;
    private static final Listing.Goods SWORD = new Listing.Goods("data", "DIAMOND_SWORD", 1, "Diamond Sword");

    @BeforeEach
    void setUp() throws Exception {
        money.put(alice, 0L);
        money.put(bob, 100_000L);       // $1000.00
        money.put(carol, 100_000L);
        store = new AuctionStore(dir.resolve("auction.yml"));
        service = newService();
    }

    private AuctionService newService() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ServiceTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        settings = Settings.parse(yaml);
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
        return new AuctionService(store, bank, () -> settings, Logger.getLogger("test"), () -> now);
    }

    private Listing list(Listing.Type type, long price) {
        AuctionService.Result r = service.list(alice, "Alice", SWORD, type, price, 48, 5, Material.DIAMOND_SWORD);
        assertEquals(AuctionService.Status.OK, r.status());
        return r.listing();
    }

    // ---------------------------------------------------------------- listing

    @Test
    void listingRulesAreEnforced() {
        assertEquals(AuctionService.Status.BAD_PRICE, service.list(alice, "A", SWORD, Listing.Type.BIN, 999, 48, 5, Material.DIAMOND_SWORD).status());
        assertEquals(AuctionService.Status.BAD_DURATION, service.list(alice, "A", SWORD, Listing.Type.BIN, 5000, 5, 5, Material.DIAMOND_SWORD).status());
        assertEquals(AuctionService.Status.BLOCKED, service.list(alice, "A", SWORD, Listing.Type.BIN, 5000, 48, 5, Material.BEDROCK).status());
        for (int i = 0; i < 5; i++) {
            list(Listing.Type.BIN, 5000);
        }
        assertEquals(AuctionService.Status.LIMIT, service.list(alice, "A", SWORD, Listing.Type.BIN, 5000, 48, 5, Material.DIAMOND_SWORD).status());
        assertEquals(5, store.listingsOf(alice).size());
    }

    @Test
    void aListingFeeIsChargedAndLostIfSavingFails() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ServiceTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        yaml.set("economy.listing-fee.percent", 2);
        yaml.set("economy.listing-fee.flat", 1);
        settings = Settings.parse(yaml);
        money.put(alice, 10_000L);
        AuctionService.Result r = service.list(alice, "A", SWORD, Listing.Type.BIN, 10_000, 48, 5, Material.DIAMOND_SWORD);   // $100: fee 1 + 2 = $3
        assertEquals(300, r.cents());
        assertEquals(9_700, money.get(alice));
        Files.createDirectory(dir.resolve("auction.yml.tmp"));
        assertEquals(AuctionService.Status.SAVE_FAILED, service.list(alice, "A", SWORD, Listing.Type.BIN, 10_000, 48, 5, Material.DIAMOND_SWORD).status());
        assertEquals(9_700, money.get(alice), "the fee came back");
        assertEquals(1, store.listings().size());
    }

    // ---------------------------------------------------------------- buying

    @Test
    void buyingMovesTheItemToTheBuyerAndPaysTheSellerMinusTax() {
        Listing l = list(Listing.Type.BIN, 10_000);        // $100
        AuctionService.Result r = service.buy(bob, "Bob", l);
        assertEquals(AuctionService.Status.OK, r.status());
        assertEquals(100_000 - 10_000, money.get(bob));
        assertEquals(9_500, money.get(alice), "5% tax");
        assertNull(store.listing(l.id()));
        List<AuctionStore.Collected> box = store.collectedOf(bob);
        assertEquals(1, box.size());
        assertEquals(SWORD, box.get(0).goods());
        assertEquals(1, store.history().size());
        assertEquals(10_000, store.history().get(0).cents());
        assertEquals(1, store.unseen(alice).sales());
    }

    @Test
    void buyingRules() {
        Listing l = list(Listing.Type.BIN, 10_000);
        assertEquals(AuctionService.Status.OWN, service.buy(alice, "Alice", l).status());
        money.put(bob, 5L);
        assertEquals(AuctionService.Status.BROKE, service.buy(bob, "Bob", l).status());
        assertNotNull(store.listing(l.id()));
        money.put(bob, 100_000L);
        now += 49 * 3_600_000L;
        assertEquals(AuctionService.Status.GONE, service.buy(bob, "Bob", l).status());
        Listing auction = list(Listing.Type.AUCTION, 10_000);
        assertEquals(AuctionService.Status.WRONG_TYPE, service.buy(bob, "Bob", auction).status());
    }

    @Test
    void twoBuyersCannotBothBuy() {
        Listing l = list(Listing.Type.BIN, 10_000);
        assertEquals(AuctionService.Status.OK, service.buy(bob, "Bob", l).status());
        assertEquals(AuctionService.Status.GONE, service.buy(carol, "Carol", l).status());
        assertEquals(100_000, money.get(carol));
    }

    @Test
    void anUnconfirmedPaymentBuysNothing() {
        Listing l = list(Listing.Type.BIN, 10_000);
        withdrawResult = Bank.Result.UNCERTAIN;
        assertEquals(AuctionService.Status.UNSURE, service.buy(bob, "Bob", l).status());
        assertNotNull(store.listing(l.id()));
        assertTrue(store.collectedOf(bob).isEmpty());
    }

    @Test
    void ifSavingFailsTheBuyerIsRefundedAndTheListingStays() throws Exception {
        Listing l = list(Listing.Type.BIN, 10_000);
        Files.createDirectory(dir.resolve("auction.yml.tmp"));
        assertEquals(AuctionService.Status.SAVE_FAILED, service.buy(bob, "Bob", l).status());
        assertEquals(100_000, money.get(bob));
        assertNotNull(store.listing(l.id()));
        assertTrue(store.collectedOf(bob).isEmpty());
        assertEquals(0, store.owed(alice));
        assertTrue(store.history().isEmpty());
        assertNull(store.unseen(alice));
    }

    @Test
    void aFailedPayoutIsKeptAndPaidLater() {
        Listing l = list(Listing.Type.BIN, 10_000);
        depositResult = Bank.Result.FAILED;
        AuctionService.Result r = service.buy(bob, "Bob", l);
        assertTrue(r.pending());
        assertEquals(9_500, store.owed(alice));
        depositResult = null;
        service.payAll();
        assertEquals(9_500, money.get(alice));
        assertEquals(0, store.owed(alice));
    }

    // ---------------------------------------------------------------- bidding

    @Test
    void bidsAreEscrowedAndTheOutbidPlayerGetsTheirMoneyBack() {
        Listing l = list(Listing.Type.AUCTION, 10_000);
        assertEquals(AuctionService.Status.TOO_LOW, service.bid(bob, "Bob", l, 9_000).status());
        assertEquals(10_000, service.bid(bob, "Bob", l, 9_000).cents());
        AuctionService.Result first = service.bid(bob, "Bob", l, 10_000);
        assertEquals(AuctionService.Status.OK, first.status());
        assertEquals(90_000, money.get(bob));
        assertEquals(10_500, service.minimumBid(l), "5% on top");
        assertEquals(AuctionService.Status.TOO_LOW, service.bid(carol, "Carol", l, 10_499).status());
        assertEquals(AuctionService.Status.ALREADY_HIGHEST, service.bid(bob, "Bob", l, 20_000).status());
        AuctionService.Result second = service.bid(carol, "Carol", l, 10_500);
        assertEquals(bob, second.other());
        assertEquals(100_000, money.get(bob), "refunded");
        assertEquals(100_000 - 10_500, money.get(carol));
        assertEquals("Carol", l.bidderName());
        assertEquals(2, l.bids());
    }

    @Test
    void sellersCannotBidAndBidsCannotBeCheaperThanTheStart() {
        Listing l = list(Listing.Type.AUCTION, 10_000);
        money.put(alice, 100_000L);
        assertEquals(AuctionService.Status.OWN, service.bid(alice, "Alice", l, 20_000).status());
        money.put(bob, 100L);
        assertEquals(AuctionService.Status.BROKE, service.bid(bob, "Bob", l, 10_000).status());
    }

    @Test
    void aLateBidExtendsTheAuction() {
        Listing l = list(Listing.Type.AUCTION, 10_000);
        now = l.expires() - 10_000;
        service.bid(bob, "Bob", l, 10_000);
        assertEquals(now + 30_000, l.expires());
        long early = l.expires();
        now = early - 29_000;
        service.bid(carol, "Carol", l, 20_000);
        assertEquals(now + 30_000, l.expires());
    }

    @Test
    void ifSavingFailsTheBidIsRefundedAndNothingChanges() throws Exception {
        Listing l = list(Listing.Type.AUCTION, 10_000);
        service.bid(bob, "Bob", l, 10_000);
        Files.createDirectory(dir.resolve("auction.yml.tmp"));
        assertEquals(AuctionService.Status.SAVE_FAILED, service.bid(carol, "Carol", l, 20_000).status());
        assertEquals(100_000, money.get(carol));
        assertEquals(bob, l.bidder());
        assertEquals(10_000, l.bid());
        assertEquals(0, store.owed(bob));
    }

    @Test
    void anAuctionWithBidsSellsToTheHighestBidderWhenItEnds() {
        Listing l = list(Listing.Type.AUCTION, 10_000);
        service.bid(bob, "Bob", l, 10_000);
        service.bid(carol, "Carol", l, 20_000);
        now = l.expires() + 1;
        List<AuctionService.Ended> ended = service.expireDue();
        assertEquals(1, ended.size());
        assertTrue(ended.get(0).sold());
        assertEquals(20_000, ended.get(0).cents());
        assertEquals(19_000, money.get(alice), "5% tax");
        assertEquals(1, store.collectedOf(carol).size());
        assertTrue(store.collectedOf(bob).isEmpty());
        assertNull(store.listing(l.id()));
        assertTrue(service.expireDue().isEmpty());
    }

    @Test
    void unsoldListingsGoBackToTheSeller() {
        Listing bin = list(Listing.Type.BIN, 10_000);
        Listing auction = list(Listing.Type.AUCTION, 10_000);
        now = bin.expires() + 1;
        List<AuctionService.Ended> ended = service.expireDue();
        assertEquals(2, ended.size());
        assertFalse(ended.get(0).sold());
        assertEquals(2, store.collectedOf(alice).size());
        assertEquals(0, money.get(alice));
        assertNull(store.listing(auction.id()));
    }

    // ---------------------------------------------------------------- cancelling and collecting

    @Test
    void sellersCancelAndGetTheItemBack() {
        Listing l = list(Listing.Type.BIN, 10_000);
        assertEquals(AuctionService.Status.NOT_OWNER, service.cancel(bob, false, l).status());
        AuctionService.Result r = service.cancel(alice, false, l);
        assertEquals(AuctionService.Status.OK, r.status());
        assertEquals(1, store.collectedOf(alice).size());
        assertEquals(AuctionService.Status.GONE, service.cancel(alice, false, l).status());
    }

    @Test
    void anAuctionWithBidsOnlyAnAdminCancelsAndTheBidderIsRefunded() {
        Listing l = list(Listing.Type.AUCTION, 10_000);
        service.bid(bob, "Bob", l, 10_000);
        assertEquals(AuctionService.Status.HAS_BIDS, service.cancel(alice, false, l).status());
        assertEquals(AuctionService.Status.OK, service.cancel(carol, true, l).status());
        assertEquals(100_000, money.get(bob));
        assertEquals(1, store.collectedOf(alice).size());
    }

    @Test
    void claimingRemovesTheItemAndRestoreBringsItBack() {
        Listing l = list(Listing.Type.BIN, 10_000);
        service.buy(bob, "Bob", l);
        long id = store.collectedOf(bob).get(0).id();
        assertNull(service.claim(carol, id), "not theirs");
        AuctionStore.Collected c = service.claim(bob, id);
        assertNotNull(c);
        assertTrue(store.collectedOf(bob).isEmpty());
        assertTrue(service.restore(c));
        assertEquals(1, store.collectedOf(bob).size());
    }

    @Test
    void whatASellerMissedIsReportedOnce() {
        Listing l = list(Listing.Type.BIN, 10_000);
        service.buy(bob, "Bob", l);
        AuctionStore.Unseen u = service.takeUnseen(alice);
        assertEquals(1, u.sales());
        assertEquals(9_500, u.cents());
        assertNull(service.takeUnseen(alice));
    }

    // ---------------------------------------------------------------- the file

    @Test
    void everythingSurvivesARestart() throws Exception {
        Listing bin = list(Listing.Type.BIN, 10_000);
        Listing auction = list(Listing.Type.AUCTION, 20_000);
        service.bid(bob, "Bob", auction, 20_000);
        depositResult = Bank.Result.FAILED;
        service.buy(carol, "Carol", bin);
        AuctionStore again = new AuctionStore(dir.resolve("auction.yml"));
        again.load();
        Listing loaded = again.listing(auction.id());
        assertEquals(20_000, loaded.bid());
        assertEquals(bob, loaded.bidder());
        assertEquals(1, loaded.bids());
        assertEquals(SWORD, loaded.goods());
        assertEquals(1, again.collectedOf(carol).size());
        assertEquals(1, again.history().size());
        assertEquals(9_500, again.owed(alice));
        assertEquals(1, again.unseen(alice).sales());
        assertTrue(again.newId() > auction.id());
    }

    @Test
    void aDamagedFileIsNeverReplaced() throws Exception {
        Files.writeString(dir.resolve("auction.yml"), "listings:\n  '1':\n    seller: nope\n");
        AuctionStore damaged = new AuctionStore(dir.resolve("auction.yml"));
        assertThrows(IOException.class, damaged::load);
    }

    @Test
    void permissionTiersRaiseTheLimit() {
        assertEquals(5, settings.limit(p -> false));
        assertEquals(25, settings.limit(p -> p.equals("smpauction.limit.25") || p.equals("smpauction.limit.10")));
        assertEquals(Integer.MAX_VALUE, settings.limit(p -> p.equals("smpauction.limit.unlimited")));
    }
}
