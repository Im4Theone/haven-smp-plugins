package net.kaloyan.smpshop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShopTest {

    @TempDir
    Path dir;

    private final UUID id = UUID.randomUUID();
    private Player player;
    private ShardManager shards;
    private Bank.Result withdraw = Bank.Result.PAID;
    private Bank.Result deposit = Bank.Result.PAID;
    private long withdrawn;
    private long deposited;
    private int notFitting;
    private boolean crateOk = true;
    private boolean spawnerOk = true;
    private Seller seller;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Steve");
        shards = new ShardManager(dir.resolve("shards.yml"), Logger.getLogger("test"));
        Bank bank = new Bank() {
            @Override
            public Result withdraw(Player p, long cents) {
                if (withdraw == Result.PAID) {
                    withdrawn += cents;
                }
                return withdraw;
            }

            @Override
            public Result deposit(Player p, long cents) {
                if (deposit == Result.PAID) {
                    deposited += cents;
                }
                return deposit;
            }
        };
        Deliveries deliveries = new Deliveries() {
            @Override
            public int items(Player p, Material item, int amount) {
                return Math.min(notFitting, amount);
            }

            @Override
            public boolean crates(UUID p, String crate, int amount) {
                return crateOk;
            }

            @Override
            public boolean spawners(UUID p, String creature, int amount) {
                return spawnerOk;
            }
        };
        seller = new Seller(bank, shards, deliveries, Logger.getLogger("test"));
    }

    private static final Catalog.Entry PEARL = new Catalog.Entry(Material.ENDER_PEARL, 250);
    private static final Catalog.Offer KEY = new Catalog.Offer(Catalog.Kind.KEY, "gold", "Gold", Material.TRIPWIRE_HOOK, 100);

    // ---------------------------------------------------------------- items for money

    @Test
    void anItemIsPaidThenDelivered() {
        Seller.Outcome o = seller.buyItem(player, PEARL, 4);
        assertEquals(Seller.Status.DONE, o.status());
        assertEquals(4, o.given());
        assertEquals(1000, o.charged());
        assertEquals(1000, withdrawn);
        assertEquals(0, deposited);
    }

    @Test
    void whatDoesNotFitIsRefundedExactly() {
        notFitting = 3;
        Seller.Outcome o = seller.buyItem(player, PEARL, 10);
        assertEquals(7, o.given());
        assertEquals(1750, o.charged());
        assertEquals(750, deposited);
        assertEquals(2500, withdrawn);
    }

    @Test
    void aFullInventoryChargesNothing() {
        notFitting = 5;
        Seller.Outcome o = seller.buyItem(player, PEARL, 5);
        assertEquals(Seller.Status.NO_ROOM, o.status());
        assertEquals(withdrawn, deposited);
    }

    @Test
    void notEnoughMoneyDeliversNothing() {
        withdraw = Bank.Result.INSUFFICIENT;
        assertEquals(Seller.Status.BROKE, seller.buyItem(player, PEARL, 1).status());
    }

    @Test
    void anUnconfirmedPaymentDeliversNothingAndRefundsNothing() {
        withdraw = Bank.Result.UNCERTAIN;
        Seller.Outcome o = seller.buyItem(player, PEARL, 1);
        assertEquals(Seller.Status.UNSURE, o.status());
        assertEquals(0, deposited);
    }

    @Test
    void anEconomyFailureIsUnavailable() {
        withdraw = Bank.Result.FAILED;
        assertEquals(Seller.Status.UNAVAILABLE, seller.buyItem(player, PEARL, 1).status());
    }

    // ---------------------------------------------------------------- shards

    @Test
    void keysAreBoughtWithShards() {
        shards.add(id, 250);
        Seller.Outcome o = seller.buyOffer(player, KEY, 2);
        assertEquals(Seller.Status.DONE, o.status());
        assertEquals(200, o.charged());
        assertEquals(50, shards.get(id));
    }

    @Test
    void tooFewShardsBuyNothing() {
        shards.add(id, 99);
        assertEquals(Seller.Status.BROKE, seller.buyOffer(player, KEY, 1).status());
        assertEquals(99, shards.get(id));
    }

    @Test
    void ifTheKeyCannotBeDeliveredTheShardsComeBack() {
        shards.add(id, 100);
        crateOk = false;
        assertEquals(Seller.Status.UNAVAILABLE, seller.buyOffer(player, KEY, 1).status());
        assertEquals(100, shards.get(id));
    }

    @Test
    void spawnersAreDeliveredThroughTheirOwnPlugin() {
        Catalog.Offer pig = new Catalog.Offer(Catalog.Kind.SPAWNER, "PIG", "Pig", Material.PIG_SPAWN_EGG, 250);
        shards.add(id, 500);
        spawnerOk = false;
        assertEquals(Seller.Status.UNAVAILABLE, seller.buyOffer(player, pig, 1).status());
        assertEquals(500, shards.get(id));
        spawnerOk = true;
        assertEquals(Seller.Status.DONE, seller.buyOffer(player, pig, 2).status());
        assertEquals(0, shards.get(id));
    }

    @Test
    void shardsSurviveARestartAndOldFilesWork() throws IOException {
        shards.add(id, 70);
        shards.saveIfDirty();
        ShardManager again = new ShardManager(dir.resolve("shards.yml"), Logger.getLogger("test"));
        again.load();
        assertEquals(70, again.get(id));
        assertTrue(again.tryTake(id, 70));
        assertFalse(again.tryTake(id, 1));
        assertEquals(0, again.get(id));
    }

    @Test
    void aDamagedShardsFileIsNeverOverwritten() throws IOException {
        Files.writeString(dir.resolve("shards.yml"), "balances: [unclosed\n  - x: : :");
        ShardManager damaged = new ShardManager(dir.resolve("shards.yml"), Logger.getLogger("test"));
        assertThrows(IOException.class, damaged::load);
    }

    // ---------------------------------------------------------------- the built-in config

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ShopTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void theBuiltInConfigIsValid() throws Exception {
        Catalog catalog = Catalog.parse(bundled());
        assertEquals(4, catalog.categories().size());
        assertEquals(5, catalog.keys().size());
        assertEquals(8, catalog.spawners().size());
        assertEquals(1_250_000, catalog.categories().get("gear").items().get(4).cents());
    }

    @Test
    void badConfigListsEveryProblem() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("categories.end.slot", 99);
        yaml.set("shards.chance-percent", 500);
        java.util.List<java.util.Map<String, Object>> keys = new java.util.ArrayList<>();
        keys.add(java.util.Map.of("id", "xx", "name", "X", "icon", "NOT_AN_ITEM", "price", 1.5));
        yaml.set("shard-shop.keys", keys);
        Catalog.Invalid e = assertThrows(Catalog.Invalid.class, () -> Catalog.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("categories.end.slot"), all);
        assertTrue(all.contains("chance-percent"), all);
        assertTrue(all.contains("NOT_AN_ITEM"), all);
        assertTrue(all.contains("whole number of shards"), all);
    }
}
