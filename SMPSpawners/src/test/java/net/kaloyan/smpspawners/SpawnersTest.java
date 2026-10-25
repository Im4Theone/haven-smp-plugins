package net.kaloyan.smpspawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpawnersTest {

    @TempDir
    Path dir;

    private static YamlConfiguration shipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = SpawnersTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static Spawner.Pos at(int x) {
        return new Spawner.Pos("world", x, 64, 0);
    }

    // ---------------------------------------------------------------- settings

    @Test
    void theShippedConfigIsValid() throws Exception {
        Settings s = Settings.parse(shipped());
        assertEquals(8, s.kinds().size());
        assertEquals(80, s.refreshTicks());
        assertEquals(500, s.maxStack());
        assertTrue(s.requireSilk());
        assertEquals(10200L, s.pricesCents().get("IRON_INGOT"));
        assertEquals(2125L, s.pricesCents().get("ROTTEN_FLESH"));
        assertEquals(4250L, s.pricesCents().get("GUNPOWDER"));
        assertEquals(15000L, s.capacity(1));
        assertEquals(7_500_000L, s.capacity(500));
        assertEquals(3, s.kinds().get("SPIDER").totalWeight());
        assertTrue(s.messages().containsKey("sold"));
    }

    @Test
    void everyMessageTheCodeUsesExists() throws Exception {
        Settings s = Settings.parse(shipped());
        java.util.regex.Pattern use = java.util.regex.Pattern.compile("texts\\.send\\([^,\"]+,\\s*\"([a-z-]+)\"");
        int found = 0;
        for (String file : List.of("Actions", "SpawnerListener", "SMPSpawnersPlugin")) {
            String source = Files.readString(Path.of("src/main/java/net/kaloyan/smpspawners/" + file + ".java"));
            var m = use.matcher(source);
            while (m.find()) {
                assertTrue(s.messages().containsKey(m.group(1)), "missing message: " + m.group(1));
                found++;
            }
        }
        assertTrue(found > 20, "found " + found);
    }

    @Test
    void badConfigsAreRejectedWithEveryProblem() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("refresh_time", 1);
        yaml.set("max_items_multiplier", 0);
        yaml.set("max_stacked_spawners", 0);
        yaml.set("spawners.CREEPER.loot", List.of(Map.of("item", "NOT_AN_ITEM", "weight", 1)));
        yaml.set("spawners.ZOMBIE.loot", List.of(Map.of("item", "BONE", "min", 5, "max", 2)));
        yaml.set("spawners.PIG.loot", List.of());
        yaml.set("prices.BONE", "1.234");
        yaml.set("prices.FAKE_ITEM", 3);
        yaml.set("messages.sold", "<click:run_command:/x>unterminated");
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        assertTrue(e.problems().size() >= 7, e.problems().toString());
    }

    @Test
    void aDropCanBeJustAnItemName() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("spawners.SKELETON.loot", List.of("bone"));
        Kind.Drop d = Settings.parse(yaml).kinds().get("SKELETON").drops().get(0);
        assertEquals(new Kind.Drop("BONE", 1, 1, 1), d);
    }

    // ---------------------------------------------------------------- production

    @Test
    void chancesFollowTheWeights() throws Exception {
        Kind spider = Settings.parse(shipped()).kinds().get("SPIDER");
        assertEquals(33.33, spider.percent(spider.drops().get(0)), 0.01);
        assertEquals(66.67, spider.percent(spider.drops().get(1)), 0.01);
        Random random = new Random(5);
        int eyes = 0;
        int rolls = 90_000;
        for (int i = 0; i < rolls; i++) {
            if (spider.pick(random).item().equals("SPIDER_EYE")) {
                eyes++;
            }
        }
        assertEquals(1.0 / 3, eyes / (double) rolls, 0.01);
    }

    @Test
    void oneRoundAddsOnePickPerStackedSpawnerAndExperience() throws Exception {
        Settings s = Settings.parse(shipped());
        Kind pig = s.kinds().get("PIG");            // PORKCHOP 1..3, always
        Spawner one = new Spawner(at(0), "PIG", 1);
        long added = one.produce(pig, s, new Random(1));
        assertTrue(added >= 1 && added <= 3);
        assertEquals(2, one.xp());
        assertEquals(added, one.loot().get("PORKCHOP"));

        Spawner hundred = new Spawner(at(1), "PIG", 100);
        long many = hundred.produce(pig, s, new Random(1));
        assertTrue(many >= 100 && many <= 300, "100 picks of 1..3: " + many);
        assertEquals(200, hundred.xp());
    }

    @Test
    void aFullSpawnerStopsProducingAndARoundNeverGoesOverTheCapacity() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("max_items", 10);
        yaml.set("max_items_multiplier", 1.0);
        Settings s = Settings.parse(yaml);
        Spawner spawner = new Spawner(at(0), "PIG", 1);
        Random random = new Random(3);
        for (int i = 0; i < 50; i++) {
            spawner.produce(s.kinds().get("PIG"), s, random);
            assertTrue(spawner.totalItems() <= 10, "capacity exceeded: " + spawner.totalItems());
        }
        assertEquals(10, spawner.totalItems());
        long xp = spawner.xp();
        assertEquals(0, spawner.produce(s.kinds().get("PIG"), s, random), "full: nothing more");
        assertEquals(xp, spawner.xp(), "and no more experience either");
        assertEquals(100, spawner.fillPercent(s));
    }

    @Test
    void takingLootIsExactAndNeverNegative() {
        Spawner spawner = new Spawner(at(0), "ZOMBIE", 3);
        spawner.addLoot("ROTTEN_FLESH", 10);
        spawner.addLoot("BONE", 0);
        spawner.addLoot("BONE", -5);
        assertEquals(Map.of("ROTTEN_FLESH", 10L), spawner.loot());
        assertEquals(4, spawner.takeLoot("ROTTEN_FLESH", 4));
        assertEquals(6, spawner.takeLoot("ROTTEN_FLESH", 100));
        assertEquals(0, spawner.takeLoot("ROTTEN_FLESH", 1));
        assertEquals(0, spawner.totalItems());
        assertThrows(IllegalArgumentException.class, () -> new Spawner(at(1), "PIG", 0));
        assertThrows(IllegalArgumentException.class, () -> spawner.stack(0));
    }

    // ---------------------------------------------------------------- the file

    @Test
    void spawnersSurviveARestart() throws Exception {
        Path file = dir.resolve("spawners.yml");
        Registry registry = new Registry(file);
        Spawner a = new Spawner(at(0), "BLAZE", 12);
        a.addLoot("BLAZE_ROD", 345);
        a.xp(1438);
        registry.add(a);
        registry.add(new Spawner(at(5), "PIG", 1));
        Registry again = new Registry(file);
        again.load();
        assertEquals(2, again.all().size());
        Spawner back = again.at(at(0));
        assertEquals("BLAZE", back.kind());
        assertEquals(12, back.stack());
        assertEquals(1438, back.xp());
        assertEquals(Map.of("BLAZE_ROD", 345L), back.loot());
        assertNull(again.at(at(99)));
        assertFalse(Files.exists(dir.resolve("spawners.yml.tmp")));
    }

    @Test
    void theFormatOfTheOldIspawnersIsReadAsItIs() throws Exception {
        Path file = dir.resolve("spawners.yml");
        Files.writeString(file, "spawners:\n  abc:\n    world: world\n    x: 10\n    y: 64\n    z: -5\n    type: blaze\n    stack: 7\n    xp: 99\n"
                + "    drops:\n      BLAZE_ROD: 12\n  kept-1:\n    world: world\n    x: 1\n    y: 2\n    z: 3\n    type: PIG\n");
        Registry registry = new Registry(file);
        registry.load();
        Spawner s = registry.at(new Spawner.Pos("world", 10, 64, -5));
        assertEquals("BLAZE", s.kind());
        assertEquals(7, s.stack());
        assertEquals(12, s.loot().get("BLAZE_ROD"));
        assertEquals(1, registry.at(new Spawner.Pos("world", 1, 2, 3)).stack(), "a missing stack means 1");
    }

    @Test
    void aFailedSaveChangesNothing() throws Exception {
        Path file = dir.resolve("spawners.yml");
        Registry registry = new Registry(file);
        Spawner a = new Spawner(at(0), "PIG", 2);
        registry.add(a);
        Files.createDirectory(dir.resolve("spawners.yml.tmp"));      // from now on saving is impossible
        assertThrows(IOException.class, () -> registry.add(new Spawner(at(1), "COW", 1)));
        assertNull(registry.at(at(1)));
        assertThrows(IOException.class, () -> registry.remove(at(0)));
        assertEquals(a, registry.at(at(0)));
        a.stack(9);
        assertThrows(IOException.class, () -> registry.commit(() -> a.stack(2)));
        assertEquals(2, a.stack(), "the undo ran");
    }

    @Test
    void aDamagedFileIsAnErrorNotAnEmptyRegistry() throws Exception {
        Path file = dir.resolve("spawners.yml");
        Registry registry = new Registry(file);
        registry.add(new Spawner(at(0), "PIG", 1));
        Files.writeString(file, "spawners: [broken\n");
        assertThrows(IOException.class, registry::load);
        assertEquals(1, registry.all().size(), "memory untouched");
        Files.writeString(file, "spawners:\n  a:\n    world: w\n    x: 1\n");
        assertThrows(IOException.class, registry::load);
    }

    @Test
    void productionIsSavedLaterButNotLost() throws Exception {
        Path file = dir.resolve("spawners.yml");
        Registry registry = new Registry(file);
        Spawner s = new Spawner(at(0), "PIG", 1);
        registry.add(s);
        s.addLoot("PORKCHOP", 40);
        registry.markDirty();
        Registry stale = new Registry(file);
        stale.load();
        assertEquals(0, stale.at(at(0)).totalItems(), "not written yet");
        registry.saveIfDirty();
        stale.load();
        assertEquals(40, stale.at(at(0)).totalItems());
    }

    // ---------------------------------------------------------------- texts

    @Test
    void theItemDescriptionShowsExperienceAndTheChances() throws Exception {
        Kind spider = Settings.parse(shipped()).kinds().get("SPIDER");
        List<String> lines = SpawnerItems.description(spider).stream().map(c -> PlainTextComponentSerializer.plainText().serialize(c)).toList();
        assertEquals(List.of("", "DETAIL:", " \u25aa EXP give: 5", "", "DROPS:", " \u25aa 0-1 Spider Eye (33.3%)", " \u25aa 0-2 String (66.7%)"), lines);
        Kind pig = Settings.parse(shipped()).kinds().get("PIG");
        assertTrue(PlainTextComponentSerializer.plainText().serialize(SpawnerItems.description(pig).get(5)).contains("1-3 Porkchop (100%)"));
    }

    @Test
    void moneyAndNamesReadNaturally() {
        assertEquals("2.5", Texts.money(250));
        assertEquals("1,234", Texts.money(123400));
        assertEquals("0.05", Texts.money(5));
        assertEquals("Iron Golem", Texts.pretty("IRON_GOLEM"));
        assertEquals("Blaze Rod", Texts.pretty("BLAZE_ROD"));
        assertEquals("33.3%", Texts.percent(33.333));
        assertEquals("50%", Texts.percent(50));
    }
}
