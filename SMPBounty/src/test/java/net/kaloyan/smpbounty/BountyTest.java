package net.kaloyan.smpbounty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BountyTest {

    @TempDir
    Path dir;

    private final UUID target = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID killer = UUID.randomUUID();

    @Test
    void moneyAddsUpAndIsRemembered() throws IOException {
        BountyStore s = new BountyStore(dir.resolve("bounties.yml"));
        s.add(target, "Target", alice, 500_000);
        BountyStore.Bounty b = s.add(target, "Target", bob, 250_000);
        s.add(target, "Target", alice, 100_000);
        assertEquals(850_000, b.total());
        assertTrue(b.contributed(alice) && b.contributed(bob) && !b.contributed(killer));
        BountyStore again = new BountyStore(dir.resolve("bounties.yml"));
        again.load();
        assertEquals(850_000, again.get(target).total());
        assertEquals("Target", again.get(target).targetName());
    }

    @Test
    void theKillerGetsEverythingExactlyOnce() throws IOException {
        BountyStore s = new BountyStore(dir.resolve("bounties.yml"));
        s.add(target, "Target", alice, 500_000);
        s.add(target, "Target", bob, 250_000);
        assertEquals(750_000, s.collect(target, killer));
        assertEquals(0, s.collect(target, killer), "no second payment");
        assertNull(s.get(target));
        assertEquals(750_000, s.owed(killer));
        assertEquals(750_000, s.takeOwed(killer));
        assertEquals(0, s.takeOwed(killer));
        BountyStore again = new BountyStore(dir.resolve("bounties.yml"));
        again.load();
        assertNull(again.get(target));
        assertEquals(0, again.owed(killer));
    }

    @Test
    void ifSavingFailsNothingMoves() throws IOException {
        BountyStore s = new BountyStore(dir.resolve("bounties.yml"));
        s.add(target, "Target", alice, 500_000);
        Files.createDirectory(dir.resolve("bounties.yml.tmp"));
        assertThrows(IOException.class, () -> s.collect(target, killer));
        assertEquals(500_000, s.get(target).total(), "the bounty is still there");
        assertEquals(0, s.owed(killer));
        assertThrows(IOException.class, () -> s.add(target, "Target", bob, 100_000));
        assertFalse(s.get(target).contributed(bob));
        assertEquals(500_000, s.get(target).total());
        assertThrows(IOException.class, () -> s.add(UUID.randomUUID(), "New", bob, 100_000));
        assertEquals(1, s.ranked().size());
    }

    @Test
    void theBiggestBountyIsFirst() throws IOException {
        BountyStore s = new BountyStore(dir.resolve("bounties.yml"));
        UUID other = UUID.randomUUID();
        s.add(target, "Target", alice, 100);
        s.add(other, "Other", alice, 900);
        assertEquals(List.of("Other", "Target"), s.ranked().stream().map(BountyStore.Bounty::targetName).toList());
    }

    @Test
    void aDamagedFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("bounties.yml"), "bounties:\n  nope:\n    name: x\n");
        assertThrows(IOException.class, () -> new BountyStore(dir.resolve("bounties.yml")).load());
    }
}
