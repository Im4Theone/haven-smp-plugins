package net.kaloyan.smpcatch;

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
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatchTest {

    @TempDir
    Path dir;

    /** A block of rock from 0..20 in every direction, except the cells in {@code open}, which are air. */
    private static Spots.Grid rock(Set<String> open) {
        return new Spots.Grid() {
            private boolean in(int x, int y, int z) {
                return x >= 0 && x <= 20 && y >= 0 && y <= 20 && z >= 0 && z <= 20 && !open.contains(x + "," + y + "," + z);
            }

            @Override
            public boolean host(int x, int y, int z) {
                return in(x, y, z);
            }

            @Override
            public boolean solid(int x, int y, int z) {
                return in(x, y, z);
            }
        };
    }

    // ---------------------------------------------------------------- where decoys may go

    @Test
    void aVeinGrowsInsideRockAndNeverTouchesAnOpenBlock() {
        Set<String> cave = new HashSet<>();
        for (int x = 8; x <= 12; x++) {
            cave.add(x + ",10,10");       // a tunnel
        }
        Spots.Grid grid = rock(cave);
        Random rnd = new Random(7);
        for (int i = 0; i < 200; i++) {
            List<int[]> vein = Spots.vein(grid, rnd, 2 + rnd.nextInt(16), 2 + rnd.nextInt(16), 2 + rnd.nextInt(16), 6);
            for (int[] b : vein) {
                assertFalse(cave.contains(b[0] + "," + b[1] + "," + b[2]), "inside the tunnel");
                for (int[] d : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    String n = (b[0] + d[0]) + "," + (b[1] + d[1]) + "," + (b[2] + d[2]);
                    assertFalse(cave.contains(n), "next to the tunnel: " + n);
                }
            }
        }
    }

    @Test
    void aVeinIsRefusedAtTheSurfaceOrNextToACave() {
        Spots.Grid grid = rock(Set.of("10,10,10"));
        assertTrue(Spots.vein(grid, new Random(1), 0, 10, 10, 3).isEmpty(), "at the edge, the outside is air");
        assertTrue(Spots.vein(grid, new Random(1), 10, 10, 11, 3).isEmpty(), "next to the cave");
        assertTrue(Spots.vein(grid, new Random(1), 10, 10, 10, 3).isEmpty(), "in the open");
        assertFalse(Spots.vein(grid, new Random(1), 5, 5, 5, 3).isEmpty());
    }

    @Test
    void aVeinHasAtMostTheRequestedSizeAndIsConnected() {
        Spots.Grid grid = rock(Set.of());
        List<int[]> vein = Spots.vein(grid, new Random(3), 10, 10, 10, 5);
        assertTrue(vein.size() <= 5 && vein.size() >= 1);
        Set<String> cells = new HashSet<>();
        vein.forEach(b -> cells.add(b[0] + "," + b[1] + "," + b[2]));
        assertEquals(vein.size(), cells.size(), "no block twice");
        for (int[] b : vein.subList(1, vein.size())) {
            boolean touches = false;
            for (int[] o : vein) {
                touches |= o != b && Math.abs(o[0] - b[0]) + Math.abs(o[1] - b[1]) + Math.abs(o[2] - b[2]) == 1;
            }
            assertTrue(touches, "every block touches another");
        }
    }

    @Test
    void aChamberNeedsSolidRockOnAllSides() {
        assertTrue(Spots.chamberFits(rock(Set.of()), 10, 10, 10));
        assertFalse(Spots.chamberFits(rock(Set.of("12,11,10")), 10, 10, 10), "a hole in the shell");
        assertFalse(Spots.chamberFits(rock(Set.of()), 1, 10, 10), "too close to the edge");
        assertEquals(18, Spots.chamber(10, 10, 10).size());
    }

    // ---------------------------------------------------------------- statistics

    @Test
    void miningNormallyNeverAlerts() {
        XrayStats x = new XrayStats(300, 150, 0.04);
        Random rnd = new Random(5);
        for (int i = 0; i < 5000; i++) {
            assertFalse(x.record(rnd.nextInt(1000) < 5), "0.5% hidden rare ores is natural");     // 0.5 %
        }
    }

    @Test
    void tooManyHiddenRareOresAlertOnceAndStartOver() {
        XrayStats x = new XrayStats(300, 150, 0.04);
        int alerts = 0;
        for (int i = 0; i < 400; i++) {
            if (x.record(i % 10 == 0)) {       // 10 % hidden rare
                alerts++;
            }
        }
        assertTrue(alerts >= 1 && alerts <= 3, "alerts: " + alerts);
        assertTrue(x.samples() < 300, "the window was cleared after an alert");
    }

    @Test
    void aFewHiddenOresAtTheStartDoNotAlert() {
        XrayStats x = new XrayStats(300, 150, 0.04);
        assertFalse(x.record(true));
        assertFalse(x.record(true));
        assertFalse(x.record(true), "not enough samples yet");
    }

    // ---------------------------------------------------------------- points

    @Test
    void pointsAddUpFadeAndCrossLevelsOnce() {
        Suspects s = new Suspects(dir.resolve("suspects.yml"), 1.0);
        UUID id = UUID.randomUUID();
        List<Double> levels = List.of(3.0, 6.0);
        long t = 1_000_000L;
        assertEquals(List.of(), s.add(id, "Cheater", t, 2.0, "a", levels));
        assertEquals(List.of(0), s.add(id, "Cheater", t, 1.5, "b", levels));
        assertEquals(List.of(), s.add(id, "Cheater", t, 1.0, "c", levels), "level 0 only fires once");
        assertEquals(List.of(1), s.add(id, "Cheater", t, 3.0, "d", levels));
        assertEquals(7.5, s.points(id, t), 1e-9);
        assertEquals(5.5, s.points(id, t + 2 * 3_600_000L), 1e-9, "1 point per hour fades");
        assertEquals(0.0, s.points(id, t + 100 * 3_600_000L), 1e-9);
    }

    @Test
    void forgivingStartsOver() {
        Suspects s = new Suspects(dir.resolve("suspects.yml"), 0);
        UUID id = UUID.randomUUID();
        s.add(id, "X", 1, 10, "a", List.of(5.0));
        assertTrue(s.clear(id));
        assertEquals(List.of(0), s.add(id, "X", 2, 6, "b", List.of(5.0)), "the level can fire again");
        assertFalse(s.clear(UUID.randomUUID()));
    }

    @Test
    void suspectsSurviveARestart() throws IOException {
        Path file = dir.resolve("suspects.yml");
        Suspects s = new Suspects(file, 0.5);
        UUID id = UUID.randomUUID();
        s.add(id, "Cheater", 5_000, 4, "mined a | with a bar", List.of(3.0));
        s.countHiddenRare(id, "Cheater", 5_000);
        s.saveIfDirty();
        Suspects again = new Suspects(file, 0.5);
        again.load();
        assertEquals(4.0, again.points(id, 5_000), 1e-9);
        assertEquals(1, again.get(id).hiddenRare());
        assertEquals("mined a | with a bar", again.get(id).events().get(0).what());
        assertEquals(List.of(), again.add(id, "Cheater", 5_000, 1, "x", List.of(3.0)), "the level had already fired");
    }

    @Test
    void aDamagedSuspectsFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("suspects.yml"), "players:\n  nope:\n    points: 1\n");
        assertThrows(IOException.class, () -> new Suspects(dir.resolve("suspects.yml"), 0).load());
    }

    // ---------------------------------------------------------------- decoy list

    @Test
    void decoysAreFoundByBlockAndForgottenWhenMined() throws IOException {
        TrapStore store = new TrapStore(dir.resolve("traps.yml"));
        TrapStore.Part a = new TrapStore.Part(1, 2, 3, "STONE", "ore");
        TrapStore.Part b = new TrapStore.Part(1, 3, 3, "STONE", "ore");
        TrapStore.Part room = new TrapStore.Part(9, 9, 9, "STONE", "cavity");
        TrapStore.Trap vein = new TrapStore.Trap(store.nextId(), "VEIN", "diamond", "world", 10, List.of(a, b));
        store.add(vein);
        TrapStore.Trap stash = new TrapStore.Trap(store.nextId(), "STASH", "chest", "world", 11,
                List.of(room, new TrapStore.Part(8, 8, 8, "STONE", "container")));
        store.add(stash);
        assertEquals(vein, store.at("world", 1, 2, 3));
        assertNull(store.at("world", 9, 9, 9), "an empty room is not a trigger");
        assertNull(store.at("nether", 1, 2, 3));
        assertEquals(stash, store.at("world", 8, 8, 8));
        assertFalse(store.mined(vein, a));
        assertNull(store.at("world", 1, 2, 3));
        assertTrue(store.mined(vein, b), "the last fake ore is gone, so the decoy is too");
        assertNull(store.get(vein.id()));
        assertNotNull(store.get(stash.id()));
    }

    @Test
    void decoysSurviveARestartAndAFailedSaveAddsNothing() throws IOException {
        Path file = dir.resolve("traps.yml");
        TrapStore store = new TrapStore(file);
        TrapStore.Trap t = new TrapStore.Trap(store.nextId(), "VEIN", "debris", "world_nether", 5,
                List.of(new TrapStore.Part(1, 2, 3, "NETHERRACK", "ore")));
        store.add(t);
        store.triggered(t);
        store.saveIfDirty();
        TrapStore again = new TrapStore(file);
        again.load();
        assertEquals(1, again.get(t.id()).triggers());
        assertEquals("NETHERRACK", again.get(t.id()).focus().original());
        assertEquals(t.id() + 1, again.nextId());
        Files.createDirectory(dir.resolve("traps.yml.tmp"));
        TrapStore.Trap u = new TrapStore.Trap(again.nextId(), "VEIN", "gold", "world", 6, List.of(new TrapStore.Part(4, 5, 6, "STONE", "ore")));
        assertThrows(IOException.class, () -> again.add(u));
        assertNull(again.get(u.id()));
        assertNull(again.at("world", 4, 5, 6));
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void theBuiltInConfigIsValidAndBadOnesAreExplained() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = CatchTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Settings s = Settings.parse(yaml);
        assertEquals(2, s.thresholds().size());
        assertEquals(0.04, s.ratio(), 1e-9);
        assertTrue(s.rare().contains(org.bukkit.Material.ANCIENT_DEBRIS));
        assertEquals(6, s.loot().size());
        yaml.set("hosts", List.of("NOT_A_BLOCK"));
        yaml.set("stats.hidden-rare-percent", 0);
        yaml.set("auto-seed.types", List.of("unobtainium"));
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("NOT_A_BLOCK") && all.contains("hidden-rare-percent") && all.contains("unobtainium"), all);
    }

    @Test
    void oresDependOnTheDimension() {
        assertNotNull(Ore.find("diamond", World.Environment.NORMAL));
        assertNull(Ore.find("diamond", World.Environment.NETHER));
        assertEquals("debris", Ore.find("netherite", World.Environment.NETHER).name());
        assertNull(Ore.find("debris", World.Environment.THE_END));
        assertTrue(Ore.exists("quartz") && !Ore.exists("unobtainium"));
    }
}
