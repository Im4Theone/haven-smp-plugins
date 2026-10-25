package net.kaloyan.smpdaily;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyTest {

    private static final long H = 3_600_000L;

    @TempDir
    Path dir;

    @Test
    void aRewardIsReadyOncePerTwentyFourHours() {
        assertTrue(Streak.ready(0, 1000));
        assertFalse(Streak.ready(1000, 1000 + 23 * H));
        assertTrue(Streak.ready(1000, 1000 + 24 * H));
        assertEquals(1000 + 24 * H - (1000 + 5 * H), Streak.until(1000, 1000 + 5 * H));
        assertEquals(0, Streak.until(1000, 1000 + 30 * H));
        assertEquals(0, Streak.until(0, 5));
    }

    @Test
    void theStreakGoesOnWithinFortyEightHoursAndRestartsAfterwards() {
        assertEquals(1, Streak.nextDay(0, 0, 5000, 7));
        assertEquals(2, Streak.nextDay(1000, 1, 1000 + 24 * H, 7));
        assertEquals(2, Streak.nextDay(1000, 1, 1000 + 48 * H, 7), "exactly on the limit still counts");
        assertEquals(1, Streak.nextDay(1000, 5, 1000 + 49 * H, 7), "a missed day starts again");
        assertEquals(1, Streak.nextDay(1000, 7, 1000 + 25 * H, 7), "after day 7 the cycle starts again");
        assertEquals(1, Streak.nextDay(1000, 3, 1000 + 25 * H, 3));
        assertTrue(Streak.lost(1000, 1000 + 49 * H));
        assertFalse(Streak.lost(1000, 1000 + 48 * H));
        assertFalse(Streak.lost(0, 1000 + 480 * H));
    }

    @Test
    void claimsSurviveARestartAndAFailedSaveChangesNothing() throws IOException {
        Path file = dir.resolve("daily.yml");
        ClaimStore store = new ClaimStore(file);
        UUID id = UUID.randomUUID();
        store.set(id, new ClaimStore.State(3, 5000));
        ClaimStore again = new ClaimStore(file);
        again.load();
        assertEquals(new ClaimStore.State(3, 5000), again.get(id));
        assertEquals(ClaimStore.State.NEVER, again.get(UUID.randomUUID()));
        Files.createDirectory(dir.resolve("daily.yml.tmp"));
        assertThrows(IOException.class, () -> again.set(id, new ClaimStore.State(4, 9000)));
        assertEquals(new ClaimStore.State(3, 5000), again.get(id));
    }

    @Test
    void aDamagedFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("daily.yml"), "players:\n  nope:\n    day: 1\n");
        assertThrows(IOException.class, () -> new ClaimStore(dir.resolve("daily.yml")).load());
    }

    @Test
    void theBuiltInConfigIsValidAndBadOnesAreExplained() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = DailyTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Settings s = Settings.parse(yaml);
        assertEquals(7, s.days().size());
        assertEquals(250_000, s.days().get(0).cents());
        assertEquals(Map.of("gold", 1, "common", 3), s.days().get(6).crates());
        assertEquals("$2,500, 2 shards", Daily.describe(s.days().get(0)));
        assertEquals("$3,500, 3 shards, 1 common crate", Daily.describe(s.days().get(1)));
        yaml.set("days", List.of(Map.of("money", -5), Map.of("shards", 1.5), Map.of()));
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("days[1].money") && all.contains("days[2].shards") && all.contains("days[3]"), all);
    }
}
