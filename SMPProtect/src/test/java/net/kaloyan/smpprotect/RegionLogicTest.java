package net.kaloyan.smpprotect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionLogicTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BEA = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;

    private static RegionIndex index(Region... regions) {
        return new RegionIndex(List.of(regions));
    }

    @Test
    void nothingIsDeniedWithoutAFlagOrInAnotherWorld() {
        Region hub = Region.of("hub", "hub", -10, 0, -10, 10, 100, 10).withFlag(Flag.BUILD, false);
        RegionIndex index = index(hub);
        assertNull(index.query("hub", 0, 50, 0, Flag.BREAK, ALEX).decidedBy(), "BREAK has no flag");
        assertTrue(index.query("hub", 0, 50, 0, Flag.BREAK, ALEX).allowed());
        assertTrue(index.query("world", 0, 50, 0, Flag.BUILD, ALEX).allowed(), "other world");
        assertTrue(index.query("hub", 11, 50, 0, Flag.BUILD, ALEX).allowed(), "outside the cuboid");
        assertFalse(index.query("hub", 10, 100, -10, Flag.BUILD, ALEX).allowed(), "the corner block is inside");
        assertTrue(index.query("hub", 0, 101, 0, Flag.BUILD, ALEX).allowed(), "one above the top");
    }

    @Test
    void membersAndOwnersMayDoPlayerActionsButNotEveryoneElse() {
        Region hub = Region.of("hub", "hub", 0, 0, 0, 10, 10, 10).withFlag(Flag.BUILD, false).withFlag(Flag.PVP, false)
                .withOwner(ALEX, true).withMember(BEA, true);
        RegionIndex index = index(hub);
        assertTrue(index.query("hub", 1, 1, 1, Flag.BUILD, ALEX).allowed());
        assertTrue(index.query("hub", 1, 1, 1, Flag.BUILD, BEA).allowed());
        assertFalse(index.query("hub", 1, 1, 1, Flag.BUILD, UUID.randomUUID()).allowed());
        assertFalse(index.query("hub", 1, 1, 1, Flag.BUILD, null).allowed(), "nobody, e.g. a piston");
        assertFalse(index.query("hub", 1, 1, 1, Flag.PVP, ALEX).allowed(), "PvP is not a member privilege");
    }

    @Test
    void higherPriorityWinsThenTheSmallerRegion() {
        Region whole = Region.wholeWorld("everything", "hub").withFlag(Flag.BUILD, false);
        Region plot = Region.of("plot", "hub", 0, 0, 0, 5, 5, 5).withFlag(Flag.BUILD, true).withPriority(10);
        RegionIndex index = index(whole, plot);
        assertTrue(index.query("hub", 1, 1, 1, Flag.BUILD, UUID.randomUUID()).allowed());
        assertEquals("plot", index.query("hub", 1, 1, 1, Flag.BUILD, null).decidedBy().name());
        assertFalse(index.query("hub", 100, 1, 1, Flag.BUILD, null).allowed());

        Region small = Region.of("small", "hub", 0, 0, 0, 2, 2, 2).withFlag(Flag.PVP, false);
        Region big = Region.of("big", "hub", 0, 0, 0, 20, 20, 20).withFlag(Flag.PVP, true);
        assertEquals("small", index(big, small).query("hub", 1, 1, 1, Flag.PVP, null).decidedBy().name(), "same priority: smaller wins");
    }

    @Test
    void aRegionWithoutTheFlagDoesNotShadowOneBeneathIt() {
        Region outer = Region.wholeWorld("outer", "hub").withFlag(Flag.EXPLOSION, false);
        Region inner = Region.of("inner", "hub", 0, 0, 0, 5, 5, 5).withPriority(5).withFlag(Flag.FIRE, false);
        assertFalse(index(outer, inner).query("hub", 1, 1, 1, Flag.EXPLOSION, null).allowed());
    }

    @Test
    void wholeWorldRegionsCoverExtremeCoordinates() {
        Region whole = Region.wholeWorld("w", "hub").withFlag(Flag.MOB_SPAWN, false);
        assertTrue(whole.isWholeWorld());
        assertFalse(index(whole).query("hub", 29_999_999, -64, -29_999_999, Flag.MOB_SPAWN, null).allowed());
        assertFalse(index(whole).query("hub", 0, 4000, 0, Flag.MOB_SPAWN, null).allowed());
        assertTrue(whole.volume() > 0);
    }

    @Test
    void cornersInAnyOrderAreNormalisedAndBadBoundsRejected() {
        Region r = Region.of("r", "hub", 5, 9, 5, -5, 1, -5);
        assertEquals(-5, r.minX());
        assertEquals(9, r.maxY());
        assertThrows(IllegalArgumentException.class, () -> new Region("r", "hub", 1, 0, 0, 0, 0, 0, 0, java.util.Set.of(),
                java.util.Set.of(), java.util.Map.of(), "", ""));
    }

    @Test
    void flagNamesParseWithDashesOrUnderscores() {
        assertEquals(Flag.ENTITY_DAMAGE, Flag.parse("entity-damage").orElseThrow());
        assertEquals(Flag.ENTITY_DAMAGE, Flag.parse("ENTITY_DAMAGE").orElseThrow());
        assertTrue(Flag.parse("nonsense").isEmpty());
        assertEquals("mob-spawn", Flag.MOB_SPAWN.id());
    }

    @Test
    void storeRoundTripKeepsEverything() throws IOException {
        RegionStore store = new RegionStore(dir.resolve("regions.yml"));
        Region hub = Region.of("hub", "hub", -47, 92, -47, 47, 152, 47).withFlag(Flag.BUILD, false).withFlag(Flag.INTERACT, true)
                .withPriority(3).withOwner(ALEX, true).withMember(BEA, true).withGreeting("<gold>Welcome to <region>").withFarewell("Bye");
        Region whole = Region.wholeWorld("everything", "world").withFlag(Flag.MOB_SPAWN, false);
        store.save(List.of(hub, whole));
        List<Region> loaded = store.load();
        assertEquals(2, loaded.size());
        assertTrue(loaded.contains(hub));
        assertTrue(loaded.contains(whole));
        assertFalse(Files.exists(dir.resolve("regions.yml.tmp")), "temporary file is gone");
    }

    @Test
    void aMissingFileMeansNoRegionsButAnInvalidFileIsAnError() throws IOException {
        assertTrue(new RegionStore(dir.resolve("none.yml")).load().isEmpty());
        Path bad = dir.resolve("bad.yml");
        Files.writeString(bad, "regions:\n  x:\n    world: hub\n    min: [0, 0, 0]\n    max: [1, 1, 1]\n    flags:\n      fly: allow\n");
        assertThrows(IOException.class, () -> new RegionStore(bad).load(), "unknown flag");
        Files.writeString(bad, "regions:\n  x:\n    world: hub\n    min: [0, 0]\n    max: [1, 1, 1]\n");
        assertThrows(IOException.class, () -> new RegionStore(bad).load(), "bad coordinates");
        Files.writeString(bad, "regions:\n  x:\n    world: hub\n    min: [5, 5, 5]\n    max: [1, 1, 1]\n");
        assertThrows(IOException.class, () -> new RegionStore(bad).load(), "min above max");
        Files.writeString(bad, "regions:\n  x:\n    world: hub\n    min: [0, 0, 0]\n    max: [1, 1, 1]\n    flags:\n      build: maybe\n");
        assertThrows(IOException.class, () -> new RegionStore(bad).load(), "flag value");
        Files.writeString(bad, "regions: [unclosed\n");
        assertThrows(IOException.class, () -> new RegionStore(bad).load(), "broken yaml");
    }

    @Test
    void aFailedSaveChangesNothingInMemoryAndKeepsTheOldFile() throws IOException {
        Path file = dir.resolve("regions.yml");
        RegionManager manager = new RegionManager(new RegionStore(file));
        manager.put(Region.of("a", "hub", 0, 0, 0, 1, 1, 1).withFlag(Flag.BUILD, false));
        byte[] before = Files.readAllBytes(file);

        // make the temporary file impossible to create: a directory sits where it would go
        Files.createDirectory(dir.resolve("regions.yml.tmp"));
        assertThrows(IOException.class, () -> manager.put(Region.of("b", "hub", 5, 5, 5, 6, 6, 6)));
        assertTrue(manager.get("b").isEmpty(), "memory unchanged");
        assertNull(manager.index().query("hub", 5, 5, 5, Flag.BUILD, null).decidedBy());
        assertEquals(before.length, Files.readAllBytes(file).length, "file unchanged");
        assertThrows(IOException.class, () -> manager.remove("a"));
        assertTrue(manager.get("a").isPresent());
    }

    @Test
    void managerLoadReplacesMemoryOnlyWhenTheFileIsValid() throws IOException {
        Path file = dir.resolve("regions.yml");
        RegionManager manager = new RegionManager(new RegionStore(file));
        manager.put(Region.of("a", "hub", 0, 0, 0, 1, 1, 1));
        RegionIndex before = manager.index();
        Files.writeString(file, "regions: [broken\n");
        assertThrows(IOException.class, manager::load);
        assertSame(before, manager.index());
        assertTrue(manager.get("a").isPresent());
    }
}
