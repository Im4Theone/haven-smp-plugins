package net.kaloyan.smpstats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsTest {

    @TempDir
    Path dir;

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private StatsStore filled() {
        StatsStore s = new StatsStore(dir.resolve("stats.yml"));
        s.kill(a, "Alice");
        s.kill(a, "Alice");
        s.kill(b, "bob");
        s.kill(c, "Carol");
        s.death(c, "Carol");
        s.minutes(b, "bob", 90);
        s.block(a, "Alice");
        return s;
    }

    @Test
    void rankingIsByNumberThenNameAndLeavesOutZeros() {
        StatsStore s = filled();
        List<StatsStore.Row> kills = s.top(StatsStore.Entry::kills, 10);
        assertEquals(List.of("Alice", "bob", "Carol"), kills.stream().map(StatsStore.Row::name).toList());
        assertEquals(2, kills.get(0).value());
        assertEquals(List.of("Carol"), s.top(StatsStore.Entry::deaths, 10).stream().map(StatsStore.Row::name).toList());
        assertEquals(1, s.top(StatsStore.Entry::kills, 1).size());
        assertEquals(List.of("bob"), s.top(StatsStore.Entry::minutes, 10).stream().map(StatsStore.Row::name).toList());
    }

    @Test
    void outsideNumbersAreRankedToo() {
        StatsStore s = filled();
        List<StatsStore.Row> rows = s.rank(Map.of(a, 500L, b, 9000L, c, 0L), 10);
        assertEquals(List.of("bob", "Alice"), rows.stream().map(StatsStore.Row::name).toList());
    }

    @Test
    void killDeathRatio() {
        StatsStore s = filled();
        assertEquals(2.0, s.get(a).kdr());
        assertEquals(1.0, s.get(c).kdr());
        s.kill(c, "Carol");
        s.kill(c, "Carol");
        assertEquals(3.0, s.get(c).kdr());
        s.death(c, "Carol");
        assertEquals(1.5, s.get(c).kdr());
    }

    @Test
    void countersSurviveARestartAndANameChange() throws IOException {
        StatsStore s = filled();
        s.saveIfDirty();
        StatsStore again = new StatsStore(dir.resolve("stats.yml"));
        again.load();
        assertEquals(2, again.get(a).kills());
        assertEquals(90, again.get(b).minutes());
        again.kill(a, "AliceNew");
        assertEquals("AliceNew", again.name(a));
        assertEquals(3, again.get(a).kills());
        assertNull(again.get(UUID.randomUUID()));
    }

    @Test
    void aDamagedFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("stats.yml"), "players:\n  nope:\n    kills: 1\n");
        assertThrows(IOException.class, () -> new StatsStore(dir.resolve("stats.yml")).load());
    }

    @Test
    void numbersAreWrittenForPlayers() {
        assertEquals("12,345", Fmt.number(12345));
        assertEquals("950", Fmt.compact(950));
        assertEquals("12.5K", Fmt.compact(12_500));
        assertEquals("3.2M", Fmt.compact(3_250_000));
        assertEquals("1B", Fmt.compact(1_000_000_000L));
        assertEquals("3d 4h", Fmt.playtime(3 * 1440 + 4 * 60 + 7));
        assertEquals("5h 10m", Fmt.playtime(310));
        assertEquals("12m", Fmt.playtime(12));
    }

    @Test
    void commandsAndWordsFindTheirBoard() {
        assertEquals(Stat.MONEY, Stat.of("baltop"));
        assertEquals(Stat.MONEY, Stat.of("balance"));
        assertEquals(Stat.KILLS, Stat.of("KILLS"));
        assertEquals(Stat.PLAYTIME, Stat.of("playtop"));
        assertNull(Stat.of("nonsense"));
    }
}
