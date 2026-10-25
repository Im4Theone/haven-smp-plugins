package net.kaloyan.smpdrill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kaloyan.smpdrill.TunnelShape.Axis;
import net.kaloyan.smpdrill.TunnelShape.Pos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

@SuppressWarnings("try")
class DrillTest {

    private static final Pos HIT = new Pos(100, 64, 200);

    // ---------------------------------------------------------------- the shape

    @Test
    void aWallOfFiveByFiveWithTheHitBlockFirst() {
        List<Pos> cells = TunnelShape.cells(Axis.Z, -1, HIT, 63, 2, 1);
        assertEquals(25, cells.size());
        assertEquals(HIT, cells.get(0));
        assertEquals(25, new HashSet<>(cells).size(), "no block twice");
        assertTrue(cells.stream().allMatch(p -> p.z() == 200), "one wall, perpendicular to the face");
        assertTrue(cells.stream().allMatch(p -> p.x() >= 98 && p.x() <= 102));
    }

    @Test
    void theTunnelFloorIsTheFloorThePlayerStandsOn() {
        // feet at 63, hitting a block at 64 (one up): the tunnel covers 63..67, never the ground below
        List<Pos> cells = TunnelShape.cells(Axis.X, 1, HIT, 63, 2, 1);
        assertEquals(63, cells.stream().mapToInt(Pos::y).min().orElseThrow());
        assertEquals(67, cells.stream().mapToInt(Pos::y).max().orElseThrow());
        assertTrue(cells.stream().allMatch(p -> p.x() == 100));
        assertTrue(cells.stream().allMatch(p -> p.z() >= 198 && p.z() <= 202));
        // a block far above the player's reach: centred on the block instead
        List<Pos> high = TunnelShape.cells(Axis.X, 1, new Pos(100, 80, 200), 63, 2, 1);
        assertEquals(78, high.stream().mapToInt(Pos::y).min().orElseThrow());
        assertEquals(82, high.stream().mapToInt(Pos::y).max().orElseThrow());
        // a block below the feet (the floor itself): centred on it
        List<Pos> floor = TunnelShape.cells(Axis.Z, 1, new Pos(100, 62, 200), 63, 2, 1);
        assertEquals(60, floor.stream().mapToInt(Pos::y).min().orElseThrow());
    }

    @Test
    void miningUpOrDownTakesAHorizontalSquare() {
        List<Pos> down = TunnelShape.cells(Axis.Y, -1, HIT, 70, 2, 1);
        assertEquals(25, down.size());
        assertTrue(down.stream().allMatch(p -> p.y() == 64));
        assertEquals(98, down.stream().mapToInt(Pos::x).min().orElseThrow());
        assertEquals(202, down.stream().mapToInt(Pos::z).max().orElseThrow());
    }

    @Test
    void depthGoesIntoTheWallAwayFromTheFace() {
        // hit from the south (face points +z): the wall continues towards -z
        List<Pos> cells = TunnelShape.cells(Axis.Z, 1, HIT, 63, 2, 3);
        assertEquals(75, cells.size());
        assertEquals(Set.of(200, 199, 198), Set.copyOf(cells.stream().map(Pos::z).toList()));
        List<Pos> north = TunnelShape.cells(Axis.Z, -1, HIT, 63, 2, 2);
        assertEquals(Set.of(200, 201), Set.copyOf(north.stream().map(Pos::z).toList()));
        assertEquals(9, TunnelShape.cells(Axis.Z, 1, HIT, 63, 1, 1).size(), "3x3");
        assertEquals(81, TunnelShape.cells(Axis.Z, 1, HIT, 63, 4, 1).size(), "9x9");
    }

    @Test
    void badInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TunnelShape.cells(Axis.Z, 0, HIT, 63, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> TunnelShape.cells(Axis.Z, 1, HIT, 63, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> TunnelShape.cells(Axis.Z, 1, HIT, 63, 2, 0));
    }

    // ---------------------------------------------------------------- settings

    private static YamlConfiguration shipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = DrillTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void theShippedConfigIsAMaxedFiveByFiveDrill() throws Exception {
        DrillSettings s = DrillSettings.parse(shipped());
        assertEquals(5, s.size());
        assertEquals(2, s.radius());
        assertEquals(1, s.depth());
        assertTrue(s.sneakMinesOne());
        assertEquals(Map.of("efficiency", 5, "unbreaking", 3, "fortune", 3, "mending", 1), s.enchantments());
        assertTrue(s.messages().containsKey("gave"));
    }

    @Test
    void badValuesAreAllReported() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("tunnel.size", 4);
        yaml.set("tunnel.depth", 0);
        yaml.set("tunnel.wear-per-extra-block", 99);
        yaml.set("item.enchantments.efficiency", 0);
        DrillSettings.Invalid e = assertThrows(DrillSettings.Invalid.class, () -> DrillSettings.parse(yaml));
        assertEquals(4, e.problems().size(), e.problems().toString());
    }

    // ---------------------------------------------------------------- the listener (Bukkit mocked)

    private record Rig(DrillListener listener, Player player, Block hit, World world, ItemStack tool, MockedStatic<Bukkit> bukkit,
                       PluginManager manager, java.util.Map<Pos, Block> blocks) {
    }

    private Rig rig(boolean holdingDrill, boolean sneaking) throws Exception {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        java.util.Map<Pos, Block> blocks = new java.util.HashMap<>();
        for (int x = 98; x <= 102; x++) {
            for (int y = 60; y <= 70; y++) {
                for (int z = 197; z <= 203; z++) {
                    Block b = mock(Block.class);
                    when(b.getWorld()).thenReturn(world);
                    when(b.getX()).thenReturn(x);
                    when(b.getY()).thenReturn(y);
                    when(b.getZ()).thenReturn(z);
                    blocks.put(new Pos(x, y, z), b);
                    when(world.getBlockAt(x, y, z)).thenReturn(b);
                }
            }
        }
        Block hit = blocks.get(HIT);
        ItemStack tool = mock(ItemStack.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(tool);
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isSneaking()).thenReturn(sneaking);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        Location feet = mock(Location.class);
        when(feet.getBlockY()).thenReturn(63);
        when(player.getLocation()).thenReturn(feet);
        when(player.getTargetBlockExact(8)).thenReturn(hit);
        when(player.getTargetBlockFace(8)).thenReturn(BlockFace.NORTH);
        DrillItem item = mock(DrillItem.class);
        when(item.isDrill(tool)).thenReturn(holdingDrill);
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        PluginManager manager = mock(PluginManager.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        DrillSettings settings = DrillSettings.parse(shipped());
        DrillListener listener = new DrillListener(mock(Plugin.class), item, () -> settings, b -> true);
        return new Rig(listener, player, hit, world, tool, bukkit, manager, blocks);
    }

    @Test
    void oneSwingBreaksTheRestOfTheWallAndAsksEveryBlockFirst() throws Exception {
        Rig r = rig(true, false);
        try (MockedStatic<Bukkit> ignored = r.bukkit()) {
            List<BlockBreakEvent> asked = new java.util.ArrayList<>();
            org.mockito.Mockito.doAnswer(call -> {
                BlockBreakEvent each = call.getArgument(0);
                asked.add(each);
                r.listener().onBreak(each);        // our own events must not be drilled again
                return null;
            }).when(r.manager()).callEvent(any(BlockBreakEvent.class));

            r.listener().onBreak(new BlockBreakEvent(r.hit(), r.player()));

            assertEquals(24, asked.size(), "25 blocks minus the one the player breaks anyway");
            assertFalse(asked.stream().anyMatch(e -> e.getBlock() == r.hit()));
            for (Block b : r.blocks().values()) {
                boolean inWall = b.getZ() == 200 && b.getX() >= 98 && b.getX() <= 102 && b.getY() >= 63 && b.getY() <= 67 && b != r.hit();
                verify(b, inWall ? times(1) : never()).breakNaturally(r.tool(), true, true);
            }
            verify(r.hit(), never()).breakNaturally(any(ItemStack.class), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
        }
    }

    @Test
    void aBlockAnotherPluginRefusesIsLeftAlone() throws Exception {
        Rig r = rig(true, false);
        try (MockedStatic<Bukkit> ignored = r.bukkit()) {
            Block protectedBlock = r.blocks().get(new Pos(101, 65, 200));
            org.mockito.Mockito.doAnswer(call -> {
                BlockBreakEvent each = call.getArgument(0);
                if (each.getBlock() == protectedBlock) {
                    each.setCancelled(true);       // e.g. SMP-Protect
                }
                return null;
            }).when(r.manager()).callEvent(any(BlockBreakEvent.class));
            r.listener().onBreak(new BlockBreakEvent(r.hit(), r.player()));
            verify(protectedBlock, never()).breakNaturally(any(ItemStack.class), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean());
            verify(protectedBlock, never()).setType(any());
            verify(r.blocks().get(new Pos(102, 65, 200)), times(1)).breakNaturally(r.tool(), true, true);
        }
    }

    @Test
    void sneakingOrAnotherToolMinesOneBlock() throws Exception {
        Rig sneaking = rig(true, true);
        try (MockedStatic<Bukkit> ignored = sneaking.bukkit()) {
            sneaking.listener().onBreak(new BlockBreakEvent(sneaking.hit(), sneaking.player()));
            verify(sneaking.manager(), never()).callEvent(any(BlockBreakEvent.class));
        }
        Rig plain = rig(false, false);
        try (MockedStatic<Bukkit> ignored = plain.bukkit()) {
            plain.listener().onBreak(new BlockBreakEvent(plain.hit(), plain.player()));
            verify(plain.manager(), never()).callEvent(any(BlockBreakEvent.class));
        }
    }
}
