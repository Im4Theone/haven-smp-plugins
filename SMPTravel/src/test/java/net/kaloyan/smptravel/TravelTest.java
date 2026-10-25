package net.kaloyan.smptravel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TravelTest {

    @TempDir
    Path dir;

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    // ---------------------------------------------------------------- homes and server spots

    @Test
    void homesAndServerSpotsSurviveARestart() throws IOException {
        Path file = dir.resolve("travel.yml");
        SpotStore store = new SpotStore(file);
        store.setHome(ALEX, "base", new Spot("world", 10.5, 64, -3.5, 90f, 10f));
        store.setHome(ALEX, "farm", new Spot("world_nether", 1, 70, 2, 0f, 0f));
        store.setHome(BEA, "home", new Spot("world", 0, 80, 0, 180f, 0f));
        store.setServer("hub", new Spot("hub", 0.5, 101, 0.5, 0f, 0f));

        SpotStore again = new SpotStore(file);
        again.load();
        assertEquals(java.util.Set.of("base", "farm"), again.homes(ALEX).keySet());
        assertEquals(new Spot("world", 10.5, 64, -3.5, 90f, 10f), again.homes(ALEX).get("base"));
        assertEquals(1, again.homes(BEA).size());
        assertEquals("hub", again.server("hub").orElseThrow().world());
        assertTrue(again.server("spawn").isEmpty());
        assertFalse(Files.exists(dir.resolve("travel.yml.tmp")));
    }

    @Test
    void replacingAndDeletingHomes() throws IOException {
        SpotStore store = new SpotStore(dir.resolve("travel.yml"));
        store.setHome(ALEX, "base", new Spot("world", 1, 1, 1, 0, 0));
        store.setHome(ALEX, "base", new Spot("world", 2, 2, 2, 0, 0));
        assertEquals(1, store.homes(ALEX).size());
        assertEquals(2.0, store.homes(ALEX).get("base").x());
        assertFalse(store.deleteHome(ALEX, "nope"));
        assertFalse(store.deleteHome(BEA, "base"));
        assertTrue(store.deleteHome(ALEX, "base"));
        assertTrue(store.homes(ALEX).isEmpty());
    }

    @Test
    void aFailedSaveChangesNothingInMemory() throws IOException {
        Path file = dir.resolve("travel.yml");
        SpotStore store = new SpotStore(file);
        store.setHome(ALEX, "base", new Spot("world", 1, 1, 1, 0, 0));
        Files.createDirectory(dir.resolve("travel.yml.tmp")); // the temporary file can no longer be created
        assertThrows(IOException.class, () -> store.setHome(ALEX, "farm", new Spot("world", 5, 5, 5, 0, 0)));
        assertEquals(java.util.Set.of("base"), store.homes(ALEX).keySet());
        assertThrows(IOException.class, () -> store.deleteHome(ALEX, "base"));
        assertTrue(store.homes(ALEX).containsKey("base"));
        assertThrows(IOException.class, () -> store.setServer("spawn", new Spot("world", 0, 0, 0, 0, 0)));
        assertTrue(store.server("spawn").isEmpty());
    }

    @Test
    void anInvalidFileIsAnErrorAndMemoryKeepsWhatItHad() throws IOException {
        Path file = dir.resolve("travel.yml");
        SpotStore store = new SpotStore(file);
        store.setHome(ALEX, "base", new Spot("world", 1, 1, 1, 0, 0));
        Files.writeString(file, "homes: [broken\n");
        assertThrows(IOException.class, store::load);
        assertTrue(store.homes(ALEX).containsKey("base"), "memory untouched");
        Files.writeString(file, "homes:\n  not-a-uuid:\n    x:\n      world: w\n");
        assertThrows(IOException.class, store::load);
        Files.writeString(file, "homes:\n  " + ALEX + ":\n    base:\n      world: w\n      x: 1\n");
        assertThrows(IOException.class, store::load, "a home without y and z is incomplete");
    }

    @Test
    void homeNamesAreValidated() {
        for (String ok : new String[] {"home", "base-2", "a", "x_y", "0123456789abcdef"}) {
            assertTrue(ok.matches(SpotStore.NAME_PATTERN), ok);
        }
        for (String bad : new String[] {"", "Base", "with space", "way-too-long-name-here", "a/b", "ä"}) {
            assertFalse(bad.matches(SpotStore.NAME_PATTERN), bad);
        }
    }

    // ---------------------------------------------------------------- tpa

    @Test
    void aRequestIsSeenOnceAndExpires() {
        TpaRequests r = new TpaRequests();
        r.send(ALEX, BEA, false, 1_000, 60_000);
        assertEquals(ALEX, r.peek(BEA, 2_000).orElseThrow().from());
        assertTrue(r.take(BEA, 2_000).isPresent());
        assertTrue(r.take(BEA, 2_000).isEmpty(), "taken requests are gone");
        r.send(ALEX, BEA, true, 1_000, 60_000);
        assertTrue(r.take(BEA, 61_001).isEmpty(), "expired");
    }

    @Test
    void aNewRequestReplacesTheOldOneAndSendersCanCancel() {
        TpaRequests r = new TpaRequests();
        UUID carl = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        r.send(ALEX, BEA, false, 0, 60_000);
        r.send(carl, BEA, true, 10, 60_000);
        assertEquals(carl, r.peek(BEA, 20).orElseThrow().from());
        assertTrue(r.peek(BEA, 20).orElseThrow().here());
        assertTrue(r.cancelFrom(ALEX).isEmpty(), "Alex's request was replaced");
        assertEquals(BEA, r.cancelFrom(carl).orElseThrow());
        assertTrue(r.peek(BEA, 20).isEmpty());
    }

    @Test
    void forgettingAPlayerDropsRequestsToAndFromThem() {
        TpaRequests r = new TpaRequests();
        r.send(ALEX, BEA, false, 0, 60_000);
        r.send(BEA, ALEX, false, 0, 60_000);
        r.forget(ALEX);
        assertTrue(r.peek(BEA, 1).isEmpty());
        assertTrue(r.peek(ALEX, 1).isEmpty());
    }

    // ---------------------------------------------------------------- settings

    private static YamlConfiguration shipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = TravelTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void theShippedConfigIsValidAndEveryUsedMessageExists() throws Exception {
        TravelSettings s = TravelSettings.parse(shipped());
        assertEquals(5, s.warmupSeconds());
        assertEquals(1, s.defaultHomes());
        assertTrue(s.firstJoinHub());
        String sources = Files.readString(Path.of("src/main/java/net/kaloyan/smptravel/TravelCommands.java"))
                + Files.readString(Path.of("src/main/java/net/kaloyan/smptravel/Teleporter.java"));
        var used = java.util.regex.Pattern.compile("(?:messages\\.send|messages\\.render)\\([^,\"]+,\\s*\"([a-z-]+)\"").matcher(sources);
        int found = 0;
        while (used.find()) {
            assertTrue(s.messages().containsKey(used.group(1)), "missing message: " + used.group(1));
            found++;
        }
        assertTrue(found > 20, "found " + found + " message uses");
        for (String key : new String[] {"spawn-missing", "hub-missing", "tpa-received", "tpa-received-here"}) {
            assertTrue(s.messages().containsKey(key), key);
        }
    }

    @Test
    void badValuesAreAllReported() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("warmup-seconds", 999);
        yaml.set("tpa.expire-seconds", 1);
        yaml.set("homes.default", -1);
        yaml.set("spawn-world", "");
        TravelSettings.Invalid e = assertThrows(TravelSettings.Invalid.class, () -> TravelSettings.parse(yaml));
        assertEquals(4, e.problems().size(), e.problems().toString());
    }

    @Test
    void homeLimitIsTheHighestOfTheDefaultAndThePermissions() {
        assertEquals(1, TravelSettings.homeLimit(1, n -> false));
        assertEquals(5, TravelSettings.homeLimit(1, n -> n == 5));
        assertEquals(10, TravelSettings.homeLimit(1, n -> n == 3 || n == 10 || n == 5));
        assertEquals(3, TravelSettings.homeLimit(3, n -> n == 2));
        assertEquals(100, TravelSettings.homeLimit(1, n -> true));
    }

    // ---------------------------------------------------------------- respawn

    @Test
    void everybodyRespawnsInTheHubByDefaultAtZeroHundredZero() throws Exception {
        TravelSettings s = TravelSettings.parse(shipped());
        assertEquals("hub", s.respawnMode());
        assertEquals(new Spot("hub", 0.5, 100, 0.5, 0, 0), s.respawn());
    }

    @Test
    void respawnModesDecideWhoKeepsTheirBed() {
        // hub: everybody goes to the hub, a saved hub spot wins over the configured location
        assertEquals(Respawn.Place.CONFIGURED, Respawn.choose("hub", false, false));
        assertEquals(Respawn.Place.CONFIGURED, Respawn.choose("hub", true, false), "even with a bed");
        assertEquals(Respawn.Place.SAVED_HUB, Respawn.choose("hub", true, true));
        // bed: keep the bed, everybody else goes to the hub
        assertEquals(Respawn.Place.VANILLA, Respawn.choose("bed", true, true));
        assertEquals(Respawn.Place.SAVED_HUB, Respawn.choose("bed", false, true));
        assertEquals(Respawn.Place.CONFIGURED, Respawn.choose("bed", false, false));
        // vanilla: untouched
        assertEquals(Respawn.Place.VANILLA, Respawn.choose("vanilla", false, true));
    }

    @Test
    void anUnknownRespawnModeIsRejected() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("respawn.mode", "everywhere");
        TravelSettings.Invalid e = assertThrows(TravelSettings.Invalid.class, () -> TravelSettings.parse(yaml));
        assertEquals(1, e.problems().size());
        YamlConfiguration old = shipped();
        old.set("respawn", null);       // a config written before this option existed behaves like the default
        assertEquals("hub", TravelSettings.parse(old).respawnMode());
    }
}
