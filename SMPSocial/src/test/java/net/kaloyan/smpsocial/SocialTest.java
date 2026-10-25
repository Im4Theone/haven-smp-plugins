package net.kaloyan.smpsocial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SocialTest {

    @TempDir
    Path dir;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private static final long MIN = 60_000L;

    @Test
    void ignoringIsOneWayAndToggles() throws IOException {
        Ignores i = new Ignores(dir.resolve("ignores.yml"));
        assertFalse(i.ignores(a, b));
        assertTrue(i.toggle(a, b));
        assertTrue(i.ignores(a, b));
        assertFalse(i.ignores(b, a), "b does not ignore a");
        assertEquals(Set.of(b), i.of(a));
        assertFalse(i.toggle(a, b));
        assertFalse(i.ignores(a, b));
    }

    @Test
    void ignoresSurviveARestartAndAFailedSaveChangesNothing() throws IOException {
        Path file = dir.resolve("ignores.yml");
        Ignores i = new Ignores(file);
        i.toggle(a, b);
        Ignores again = new Ignores(file);
        again.load();
        assertTrue(again.ignores(a, b));
        Files.createDirectory(dir.resolve("ignores.yml.tmp"));
        assertThrows(IOException.class, () -> again.toggle(b, a));
        assertFalse(again.ignores(b, a));
        assertThrows(IOException.class, () -> again.toggle(a, b));
        assertTrue(again.ignores(a, b), "the failed removal was undone");
    }

    @Test
    void aDamagedFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("ignores.yml"), "players:\n  nope:\n    - x\n");
        assertThrows(IOException.class, () -> new Ignores(dir.resolve("ignores.yml")).load());
    }

    @Test
    void idlePlayersAreAwayAndActivityBringsThemBack() {
        AfkTracker t = new AfkTracker();
        Set<UUID> online = Set.of(a, b);
        assertTrue(t.check(online, Set.of(), 0, 5 * MIN).isEmpty(), "just joined");
        assertTrue(t.check(online, Set.of(), 4 * MIN, 5 * MIN).isEmpty());
        assertFalse(t.activity(a, 4 * MIN + 1), "a was not away");
        assertEquals(Set.of(b), t.check(online, Set.of(), 5 * MIN, 5 * MIN), "only b has been idle for five minutes");
        assertTrue(t.isAway(b));
        assertFalse(t.isAway(a));
        assertTrue(t.check(online, Set.of(), 6 * MIN, 5 * MIN).isEmpty(), "b is already away, so nobody changed");
        assertTrue(t.activity(b, 7 * MIN), "b is back");
        assertFalse(t.isAway(b));
    }

    @Test
    void exemptPlayersAndAServerWithoutAutoAwayNeverGoAway() {
        AfkTracker t = new AfkTracker();
        t.check(Set.of(a, b), Set.of(), 0, 5 * MIN);
        assertEquals(Set.of(b), t.check(Set.of(a, b), Set.of(a), 10 * MIN, 5 * MIN));
        AfkTracker never = new AfkTracker();
        never.check(Set.of(a), Set.of(), 0, 0);
        assertTrue(never.check(Set.of(a), Set.of(), 999 * MIN, 0).isEmpty());
    }

    @Test
    void awayByHandAndPlayersWhoLeft() {
        AfkTracker t = new AfkTracker();
        assertTrue(t.toggle(a, 0));
        assertTrue(t.isAway(a));
        assertFalse(t.toggle(a, 10));
        assertFalse(t.isAway(a));
        t.toggle(b, 0);
        t.check(Set.of(a), Set.of(), 1, 5 * MIN);
        assertFalse(t.isAway(b), "b left the server");
    }
}
