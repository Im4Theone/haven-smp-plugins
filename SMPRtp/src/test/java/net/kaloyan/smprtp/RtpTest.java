package net.kaloyan.smprtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RtpTest {

    /** A tiny world: a map of blocks, everything else is air. */
    private static final class Blocks implements SpotFinder.View {
        private final Map<String, Material> map = new HashMap<>();

        Blocks set(int x, int y, int z, Material m) {
            map.put(x + "," + y + "," + z, m);
            return this;
        }

        Blocks fill(int y, int from, int to, Material m) {
            for (int x = from; x <= to; x++) {
                for (int z = from; z <= to; z++) {
                    set(x, y, z, m);
                }
            }
            return this;
        }

        @Override
        public Material type(int x, int y, int z) {
            return map.getOrDefault(x + "," + y + "," + z, Material.AIR);
        }
    }

    private static final SpotFinder.Classifier RULES = new SpotFinder.Classifier() {
        private final Set<Material> solid = Set.of(Material.STONE, Material.GRASS_BLOCK, Material.NETHERRACK, Material.END_STONE);

        @Override
        public boolean standable(Material material) {
            return solid.contains(material);
        }

        @Override
        public boolean free(Material material) {
            return material == Material.AIR;
        }

        @Override
        public boolean hazard(Material material) {
            return material == Material.LAVA || material == Material.FIRE;
        }
    };

    private final SpotFinder finder = new SpotFinder(RULES);

    @Test
    void standsOnTheGroundWithTwoFreeBlocksAbove() {
        Blocks world = new Blocks().fill(64, -3, 3, Material.GRASS_BLOCK);
        assertEquals(OptionalInt.of(65), finder.find(world, 0, 0, 64, 64));
    }

    @Test
    void refusesNonStandableGroundWaterAndVoid() {
        Blocks water = new Blocks().fill(64, -3, 3, Material.WATER);
        assertTrue(finder.find(water, 0, 0, 64, 64).isEmpty(), "water");
        assertTrue(finder.find(new Blocks(), 0, 0, 64, 0).isEmpty(), "void column");
    }

    @Test
    void refusesALowCeiling() {
        Blocks cramped = new Blocks().fill(64, -3, 3, Material.STONE).set(0, 66, 0, Material.STONE);
        assertTrue(finder.find(cramped, 0, 0, 64, 64).isEmpty(), "head block is solid");
        Blocks fine = new Blocks().fill(64, -3, 3, Material.STONE).set(0, 67, 0, Material.STONE);
        assertEquals(OptionalInt.of(65), finder.find(fine, 0, 0, 64, 64));
    }

    @Test
    void refusesSpotsNextToLavaOrFire() {
        Blocks lava = new Blocks().fill(64, -3, 3, Material.STONE).set(1, 65, 0, Material.LAVA);
        assertTrue(finder.find(lava, 0, 0, 64, 64).isEmpty(), "lava beside the feet");
        Blocks lavaBelowSide = new Blocks().fill(64, -3, 3, Material.STONE).set(-1, 63, 1, Material.LAVA);
        assertTrue(finder.find(lavaBelowSide, 0, 0, 64, 64).isEmpty(), "lava diagonally below");
        Blocks far = new Blocks().fill(64, -5, 5, Material.STONE).set(3, 65, 0, Material.FIRE);
        assertEquals(OptionalInt.of(65), finder.find(far, 0, 0, 64, 64), "hazard two blocks away is fine");
    }

    @Test
    void scansDownThroughRockToACavernFloorLikeTheNether() {
        // rock from 40 up to the roof at 127, with a cavern: floor at 60, air 61..80
        Blocks nether = new Blocks();
        for (int y = 40; y <= 127; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    nether.set(dx, y, dz, y >= 61 && y <= 80 ? Material.AIR : Material.NETHERRACK);
                }
            }
        }
        assertEquals(OptionalInt.of(61), finder.find(nether, 0, 0, 120, 32), "the cavern floor, not the rock above it");
        assertTrue(finder.find(nether, 0, 0, 120, 70).isEmpty(), "a range that holds only air and rock above the floor has no spot");
        assertEquals(OptionalInt.of(61), finder.find(nether, 0, 0, 70, 32), "starting inside the cavern finds its floor");
    }

    @Test
    void samplesStayInsideTheSquareAndOutsideTheMinimumRadius() {
        RtpWorld end = new RtpWorld("end", "world_the_end", true, 100, -50, 1200, 5000, 0, 120, 0);
        Random random = new Random(42);
        double nearest = Double.MAX_VALUE;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 20_000; i++) {
            int[] xz = end.sample(random);
            nearest = Math.min(nearest, Math.hypot(xz[0] - 100, xz[1] + 50));
            minX = Math.min(minX, xz[0]);
            maxX = Math.max(maxX, xz[0]);
            minZ = Math.min(minZ, xz[1]);
            maxZ = Math.max(maxZ, xz[1]);
        }
        assertTrue(nearest >= 1200, "nearest " + nearest);
        assertTrue(minX >= 100 - 2500 && maxX <= 100 + 2500 && minZ >= -50 - 2500 && maxZ <= -50 + 2500, "inside 5000 x 5000");
        assertTrue(maxX - minX > 4900 && maxZ - minZ > 4900, "the whole square is used");
        RtpWorld anywhere = new RtpWorld("overworld", "world", true, 0, 0, 0, 5000, -64, -1, 0);
        int[] p = anywhere.sample(random);
        assertTrue(Math.abs(p[0]) <= 2500 && Math.abs(p[1]) <= 2500);
    }

    private static YamlConfiguration defaults() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = RtpTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void theShippedConfigIsValid() throws Exception {
        RtpSettings s = RtpSettings.parse(defaults());
        assertEquals(Set.of("overworld", "nether", "end"), s.worlds().keySet());
        assertEquals("world_nether", s.worlds().get("nether").world());
        assertEquals("world_the_end", s.worlds().get("end").world());
        assertEquals(-1, s.worlds().get("overworld").maxY());
        assertTrue(s.messages().containsKey("success"));
        assertEquals(60, s.cooldownSeconds());
        assertEquals(5, s.warmupSeconds());
        s.worlds().values().forEach(w -> assertEquals(5000, w.size(), w.key()));
    }

    @Test
    void badConfigsAreRejectedWithEveryProblem() throws Exception {
        YamlConfiguration yaml = defaults();
        yaml.set("worlds.nether.min-radius", 3000);
        yaml.set("worlds.end.world", "");
        yaml.set("worlds.overworld.cost", -1);
        yaml.set("default-world", "moon");
        yaml.set("warmup-seconds", 500);
        yaml.set("messages.success", "<red>unclosed <bold");
        RtpSettings.Invalid e = assertThrows(RtpSettings.Invalid.class, () -> RtpSettings.parse(yaml));
        assertTrue(e.problems().size() >= 5, e.problems().toString());
    }

    @Test
    void worldNamesTypedByPlayersAreUnderstood() {
        assertEquals("nether", RtpCommand.keyOf("Nether"));
        assertEquals("end", RtpCommand.keyOf("THE_END"));
        assertEquals("overworld", RtpCommand.keyOf("world"));
        assertNull(RtpCommand.keyOf("hub"));
        assertFalse("overworld".equals(RtpCommand.keyOf("end")));
    }
}
