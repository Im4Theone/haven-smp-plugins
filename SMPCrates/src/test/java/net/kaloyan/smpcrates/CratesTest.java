package net.kaloyan.smpcrates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kaloyan.smpcrates.CrateService.Delivery;
import net.kaloyan.smpcrates.CrateService.Outcome;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CratesTest {

    @TempDir
    Path dir;

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static YamlConfiguration shipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = CratesTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    /** A delivery that records what it was asked to do and can be told to refuse. */
    private static final class FakeDelivery implements Delivery {
        boolean room = true;
        boolean works = true;
        final List<Reward> given = new ArrayList<>();

        @Override
        public boolean canDeliver(UUID player, Reward reward) {
            return room;
        }

        @Override
        public boolean deliver(UUID player, Reward reward) {
            if (works) {
                given.add(reward);
            }
            return works;
        }
    }

    private CrateService service(CratesSettings settings, CrateStore store, Delivery delivery, AtomicLong clock) {
        return new CrateService(() -> settings, store, delivery, new Random(1), clock::get);
    }

    // ---------------------------------------------------------------- the clock

    @Test
    void roundsFollowTheWallClockAndTheTimerCountsDown() {
        HourlyClock clock = new HourlyClock(60);
        long hour = 3_600_000L;
        assertEquals(0, clock.round(0));
        assertEquals(0, clock.round(hour - 1));
        assertEquals(1, clock.round(hour));
        assertEquals(hour, clock.untilNext(0), "just after a round started a full hour remains");
        assertEquals(1, clock.untilNext(hour - 1));
        assertEquals(30 * 60_000L, clock.untilNext(hour + 30 * 60_000L));
        assertEquals("59:59", HourlyClock.format(59 * 60_000L + 59_000L));
        assertEquals("60:00", HourlyClock.format(hour), "a full round reads 60:00");
        assertEquals("00:00", HourlyClock.format(0));
        assertEquals("00:01", HourlyClock.format(1));
        assertThrows(IllegalArgumentException.class, () -> new HourlyClock(0));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void theShippedConfigIsValidAndEveryMessageThatIsUsedExists() throws Exception {
        CratesSettings s = CratesSettings.parse(shipped());
        assertEquals(List.of("common", "prime", "gold", "amethyst", "crimson"), List.copyOf(s.crates().keySet()));
        assertEquals("common", s.hourlyCrate());
        assertTrue(s.hourlyEnabled());
        assertFalse(s.catchUp(), "a real keyall: only players online at 0:00 get the key");
        for (CrateType crate : s.crates().values()) {
            assertTrue(crate.rewards().size() >= 8, crate.id());
            assertTrue(crate.rewards().stream().anyMatch(Reward::broadcast), crate.id() + " announces its best rewards");
        }
        Pattern used = Pattern.compile("(?:messages\\.(?:send|render)|send)\\(\\s*(?:[a-zA-Z.()]+),\\s*\"([a-z-]+)\"");
        Pattern used2 = Pattern.compile("messages\\.render\\(\"([a-z-]+)\"");
        int found = 0;
        for (String file : List.of("CratesCommand", "Menus", "SMPCratesPlugin", "KeyallCommand")) {
            String source = Files.readString(Path.of("src/main/java/net/kaloyan/smpcrates/" + file + ".java"));
            for (Pattern p : List.of(used, used2)) {
                Matcher m = p.matcher(source);
                while (m.find()) {
                    assertTrue(s.messages().containsKey(m.group(1)), "missing message: " + m.group(1));
                    found++;
                }
            }
        }
        assertTrue(found > 40, "found " + found + " message uses");
    }

    @Test
    void badConfigsAreRejectedWithEveryProblem() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("hourly.crate", "nope");
        yaml.set("hourly.interval-minutes", 0);
        yaml.set("crates.BadId.rewards", List.of());
        yaml.set("crates.common.material", "NOT_AN_ITEM");
        yaml.set("crates.prime.rewards", List.of(Map.of("type", "money", "amount", 0, "weight", 1),
                Map.of("type", "teleport", "weight", 1), Map.of("type", "item", "material", "AIR", "weight", 1),
                Map.of("type", "command", "command", "", "weight", 1)));
        yaml.set("messages.hourly", "<click:run_command:/x>unterminated");
        CratesSettings.Invalid e = assertThrows(CratesSettings.Invalid.class, () -> CratesSettings.parse(yaml));
        assertTrue(e.problems().size() >= 7, e.problems().toString());
    }

    @Test
    void rewardChancesFollowTheWeights() throws Exception {
        CrateType type = CratesSettings.parse(shipped()).crates().get("common");
        Random random = new Random(42);
        Map<Reward, Integer> counts = new HashMap<>();
        int rolls = 200_000;
        for (int i = 0; i < rolls; i++) {
            counts.merge(type.pick(random), 1, Integer::sum);
        }
        for (Reward reward : type.rewards()) {
            double expected = type.chancePercent(reward) / 100.0;
            double actual = counts.getOrDefault(reward, 0) / (double) rolls;
            assertEquals(expected, actual, 0.006, reward.toString());
        }
        assertEquals(100.0, type.rewards().stream().mapToDouble(type::chancePercent).sum(), 1e-6);
    }

    // ---------------------------------------------------------------- store

    @Test
    void balancesAndBlocksSurviveARestart() throws Exception {
        Path file = dir.resolve("data.yml");
        CrateStore store = new CrateStore(file);
        store.add(ALEX, "common", 3);
        store.add(ALEX, "gold", 1);
        store.add(BEA, "prime", 2);
        assertTrue(store.tryTake(ALEX, "common", 1));
        store.putBlock(new CrateStore.CrateBlock("hub", 1, 100, -2, "gold"));
        store.grantRound(List.of(BEA), "common", 5);

        CrateStore again = new CrateStore(file);
        again.load();
        assertEquals(Map.of("common", 2, "gold", 1), again.balances(ALEX));
        assertEquals(2, again.balance(BEA, "prime"));
        assertEquals(1, again.balance(BEA, "common"));
        assertEquals(5, again.lastRound(BEA));
        assertEquals(Long.MIN_VALUE, again.lastRound(UUID.randomUUID()));
        assertEquals("gold", again.blockAt("hub", 1, 100, -2).orElseThrow().crate());
        assertFalse(Files.exists(dir.resolve("data.yml.tmp")));
    }

    @Test
    void youCannotTakeMoreThanYouHaveAndAddingZeroIsRefused() throws Exception {
        CrateStore store = new CrateStore(dir.resolve("data.yml"));
        store.add(ALEX, "common", 2);
        assertFalse(store.tryTake(ALEX, "common", 3));
        assertFalse(store.tryTake(BEA, "common", 1));
        assertEquals(2, store.balance(ALEX, "common"));
        assertThrows(IllegalArgumentException.class, () -> store.add(ALEX, "common", 0));
        assertTrue(store.tryTake(ALEX, "common", 2));
        assertTrue(store.balances(ALEX).isEmpty(), "an empty balance is not kept");
    }

    @Test
    void aFailedSaveUndoesTheChange() throws Exception {
        Path file = dir.resolve("data.yml");
        CrateStore store = new CrateStore(file);
        store.add(ALEX, "common", 2);
        Files.createDirectory(dir.resolve("data.yml.tmp")); // saving is now impossible
        assertThrows(IOException.class, () -> store.add(ALEX, "common", 5));
        assertEquals(2, store.balance(ALEX, "common"));
        assertThrows(IOException.class, () -> store.tryTake(ALEX, "common", 1));
        assertEquals(2, store.balance(ALEX, "common"));
        assertThrows(IOException.class, () -> store.grantRound(List.of(BEA), "common", 9));
        assertEquals(0, store.balance(BEA, "common"));
        assertEquals(Long.MIN_VALUE, store.lastRound(BEA), "the round was not remembered either");
        assertThrows(IOException.class, () -> store.putBlock(new CrateStore.CrateBlock("hub", 0, 0, 0, "gold")));
        assertTrue(store.blocks().isEmpty());
    }

    @Test
    void aDamagedFileIsAnErrorNotAnEmptyStore() throws Exception {
        Path file = dir.resolve("data.yml");
        CrateStore store = new CrateStore(file);
        store.add(ALEX, "common", 1);
        Files.writeString(file, "players: [broken\n");
        assertThrows(IOException.class, store::load);
        assertEquals(1, store.balance(ALEX, "common"), "memory untouched");
        Files.writeString(file, "players:\n  not-a-uuid:\n    round: 1\n");
        assertThrows(IOException.class, store::load);
        Files.writeString(file, "players:\n  " + ALEX + ":\n    crates:\n      common: -4\n");
        assertThrows(IOException.class, store::load);
    }

    // ---------------------------------------------------------------- the hourly crate

    @Test
    void everybodyGetsOneCratePerRoundAndNeverTwice() throws Exception {
        CratesSettings s = CratesSettings.parse(shipped());
        CrateStore store = new CrateStore(dir.resolve("data.yml"));
        AtomicLong clock = new AtomicLong(10 * 3_600_000L + 5_000);          // round 10
        CrateService service = service(s, store, new FakeDelivery(), clock);

        assertEquals(List.of(ALEX, BEA), service.hourly(List.of(ALEX, BEA)));
        assertTrue(service.hourly(List.of(ALEX, BEA)).isEmpty(), "same round: nothing more");
        assertEquals(1, store.balance(ALEX, "common"));
        clock.set(10 * 3_600_000L + 3_599_000L);                              // still round 10
        assertTrue(service.hourly(List.of(ALEX)).isEmpty());
        clock.set(11 * 3_600_000L);                                           // round 11
        assertEquals(List.of(ALEX), service.hourly(List.of(ALEX)), "only Alex is online now");
        assertEquals(2, store.balance(ALEX, "common"));
        assertEquals(1, store.balance(BEA, "common"));
        clock.set(14 * 3_600_000L + 1);                                       // Bea was away for rounds 11 to 13
        assertEquals(List.of(BEA), service.hourly(List.of(BEA)));
        assertEquals(2, store.balance(BEA, "common"), "she gets one, not three");
        assertEquals(List.of(), service.hourly(List.of(BEA, BEA)), "listed twice is still once");
    }

    @Test
    void theHourlyCrateCanBeSwitchedOff() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("hourly.enabled", false);
        CrateService service = service(CratesSettings.parse(yaml), new CrateStore(dir.resolve("d.yml")), new FakeDelivery(), new AtomicLong(1));
        assertTrue(service.hourly(List.of(ALEX)).isEmpty());
    }

    // ---------------------------------------------------------------- opening

    @Test
    void openingTakesOneCrateAndGivesExactlyOneReward() throws Exception {
        CratesSettings s = CratesSettings.parse(shipped());
        CrateStore store = new CrateStore(dir.resolve("data.yml"));
        FakeDelivery delivery = new FakeDelivery();
        CrateService service = service(s, store, delivery, new AtomicLong(0));
        assertEquals(Outcome.NONE_LEFT, service.open(ALEX, "common").outcome());
        assertEquals(Outcome.UNKNOWN_CRATE, service.open(ALEX, "nope").outcome());
        assertTrue(service.give(ALEX, "common", 2));
        assertFalse(service.give(ALEX, "nope", 1));
        assertFalse(service.give(ALEX, "common", 0));

        CrateService.OpenResult first = service.open(ALEX, "common");
        assertEquals(Outcome.OPENED, first.outcome());
        assertNotNull(first.reward());
        assertEquals(1, store.balance(ALEX, "common"));
        assertEquals(List.of(first.reward()), delivery.given);
        service.open(ALEX, "common");
        assertEquals(0, store.balance(ALEX, "common"));
        assertEquals(2, delivery.given.size());
        assertEquals(Outcome.NONE_LEFT, service.open(ALEX, "common").outcome());
    }

    @Test
    void aFullInventoryOrAFailedDeliveryNeverCostsACrate() throws Exception {
        CratesSettings s = CratesSettings.parse(shipped());
        CrateStore store = new CrateStore(dir.resolve("data.yml"));
        FakeDelivery delivery = new FakeDelivery();
        CrateService service = service(s, store, delivery, new AtomicLong(0));
        service.give(ALEX, "gold", 1);

        delivery.room = false;
        assertEquals(Outcome.CANNOT_DELIVER, service.open(ALEX, "gold").outcome());
        assertEquals(1, store.balance(ALEX, "gold"));

        delivery.room = true;
        delivery.works = false;
        assertEquals(Outcome.DELIVERY_FAILED, service.open(ALEX, "gold").outcome());
        assertEquals(1, store.balance(ALEX, "gold"), "the crate was given back");
        assertTrue(delivery.given.isEmpty());

        delivery.works = true;
        assertEquals(Outcome.OPENED, service.open(ALEX, "gold").outcome());
        assertEquals(0, store.balance(ALEX, "gold"));
    }

    @Test
    void ifSavingFailsNothingIsGivenAndNothingIsLost() throws Exception {
        CratesSettings s = CratesSettings.parse(shipped());
        CrateStore store = new CrateStore(dir.resolve("data.yml"));
        FakeDelivery delivery = new FakeDelivery();
        CrateService service = service(s, store, delivery, new AtomicLong(0));
        service.give(ALEX, "prime", 1);
        Files.createDirectory(dir.resolve("data.yml.tmp"));
        assertEquals(Outcome.SAVE_FAILED, service.open(ALEX, "prime").outcome());
        assertEquals(1, store.balance(ALEX, "prime"));
        assertTrue(delivery.given.isEmpty(), "no reward without a saved crate");
        assertThrows(IOException.class, () -> service.give(ALEX, "prime", 1));
    }

    // ---------------------------------------------------------------- /keyall by hand

    @Test
    void keyallByHandGivesEveryoneTheSameAndNothingIfSavingFails() throws Exception {
        CratesSettings s = CratesSettings.parse(shipped());
        CrateStore store = new CrateStore(dir.resolve("data.yml"));
        CrateService service = service(s, store, new FakeDelivery(), new AtomicLong(0));
        store.add(ALEX, "gold", 1);
        assertTrue(service.giveAll(List.of(ALEX, BEA, ALEX), "gold", 3));
        assertEquals(4, store.balance(ALEX, "gold"), "listed twice still gets it once");
        assertEquals(3, store.balance(BEA, "gold"));
        assertFalse(service.giveAll(List.of(ALEX), "nope", 1));
        assertFalse(service.giveAll(List.of(ALEX), "gold", 0));

        Files.createDirectory(dir.resolve("data.yml.tmp"));
        assertThrows(IOException.class, () -> service.giveAll(List.of(ALEX, BEA), "gold", 5));
        assertEquals(4, store.balance(ALEX, "gold"));
        assertEquals(3, store.balance(BEA, "gold"));
    }

    @Test
    void onlyTheNamedOwnerWithThePermissionMayUseKeyall() {
        assertTrue(KeyallCommand.mayUse(true, false, "CONSOLE", List.of("Kaloyan")), "the console always may");
        assertFalse(KeyallCommand.mayUse(false, false, "Kaloyan", List.of()), "no permission, no keyall, even for an operator");
        assertTrue(KeyallCommand.mayUse(false, true, "Kaloyan", List.of()), "permission and no allow-list");
        assertTrue(KeyallCommand.mayUse(false, true, "kaloyan", List.of("Kaloyan")), "names are not case sensitive");
        assertFalse(KeyallCommand.mayUse(false, true, "Someone", List.of("Kaloyan")), "permission is not enough with an allow-list");
    }

    @Test
    void theAllowListMustHoldRealPlayerNames() throws Exception {
        YamlConfiguration yaml = shipped();
        assertTrue(CratesSettings.parse(yaml).keyallAllowed().isEmpty());
        yaml.set("keyall.allowed-players", List.of("Kaloyan_K"));
        assertEquals(List.of("Kaloyan_K"), CratesSettings.parse(yaml).keyallAllowed());
        yaml.set("keyall.allowed-players", List.of("not a name!"));
        assertThrows(CratesSettings.Invalid.class, () -> CratesSettings.parse(yaml));
    }
}
