package net.kaloyan.smpspawners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The listener with Bukkit mocked: stacking, taking out, and what is protected. */
class ListenerTest {

    @TempDir
    Path dir;

    private World world;
    private Block block;
    private Registry registry;
    private SpawnerItems items;
    private Actions actions;
    private Menus menus;
    private Texts texts;
    private Settings settings;
    private SpawnerListener listener;
    private Spawner spawner;
    private boolean silk;

    @BeforeEach
    void setUp() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ListenerTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        yaml.set("max_stacked_spawners", 10);
        settings = Settings.parse(yaml);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        block = block(5);
        registry = new Registry(dir.resolve("spawners.yml"));
        spawner = new Spawner(SpawnerListener.pos(block), "BLAZE", 4);
        registry.add(spawner);
        items = mock(SpawnerItems.class);
        actions = mock(Actions.class);
        menus = mock(Menus.class);
        texts = mock(Texts.class);
        silk = true;
        listener = new SpawnerListener(registry, () -> settings, items, actions, menus, texts, tool -> silk);
        listener.placeable = m -> m == Material.STONE;
    }

    private Block block(int x) {
        Block b = mock(Block.class);
        when(b.getWorld()).thenReturn(world);
        when(b.getX()).thenReturn(x);
        when(b.getY()).thenReturn(64);
        when(b.getZ()).thenReturn(0);
        when(b.getType()).thenReturn(Material.SPAWNER);
        return b;
    }

    private record Rig(Player player, ItemStack hand, int[] amount) {
    }

    private Rig player(String heldKind, int amount, boolean sneaking) {
        ItemStack hand = mock(ItemStack.class);
        int[] count = {amount};
        when(hand.getAmount()).thenAnswer(i -> count[0]);
        org.mockito.Mockito.doAnswer(i -> {
            count[0] = i.getArgument(0);
            return null;
        }).when(hand).setAmount(org.mockito.ArgumentMatchers.anyInt());
        when(hand.getType()).thenReturn(Material.SPAWNER);
        when(items.kindOf(hand)).thenReturn(heldKind);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(hand);
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isSneaking()).thenReturn(sneaking);
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        return new Rig(player, hand, count);
    }

    private PlayerInteractEvent click(Rig rig) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(rig.player());
        return event;
    }

    // ---------------------------------------------------------------- stacking

    @Test
    void aMatchingItemStacksOneAtATimeAndTheWholeHandWhenSneaking() throws Exception {
        Rig one = player("BLAZE", 5, false);
        listener.onInteract(click(one));
        assertEquals(5, spawner.stack());
        assertEquals(4, one.amount()[0], "one item was used");

        Rig all = player("BLAZE", 3, true);
        listener.onInteract(click(all));
        assertEquals(8, spawner.stack());
        assertEquals(0, all.amount()[0]);
        verify(menus, never()).openMain(any(), any());
    }

    @Test
    void theStackNeverGoesOverTheLimitAndOnlyTheItemsThatFitAreUsed() {
        Rig lots = player("BLAZE", 20, true);       // the limit is 10 and 4 are stacked: 6 fit
        listener.onInteract(click(lots));
        assertEquals(10, spawner.stack());
        assertEquals(14, lots.amount()[0]);
        Rig more = player("BLAZE", 5, false);
        listener.onInteract(click(more));
        assertEquals(10, spawner.stack(), "full");
        assertEquals(5, more.amount()[0], "nothing used");
    }

    @Test
    void aDifferentCreaturesSpawnerIsRefused() {
        Rig pig = player("PIG", 3, true);
        listener.onInteract(click(pig));
        assertEquals(4, spawner.stack());
        assertEquals(3, pig.amount()[0]);
    }

    @Test
    void ifSavingFailsTheItemsComeBackAndTheStackIsUnchanged() throws Exception {
        Files.createDirectory(dir.resolve("spawners.yml.tmp"));       // saving is impossible now
        Rig rig = player("BLAZE", 3, true);
        listener.onInteract(click(rig));
        assertEquals(4, spawner.stack());
        assertEquals(3, rig.amount()[0], "the items were given back");
    }

    @Test
    void anEmptyHandOpensTheMenuAndOtherInteractionIsDenied() {
        Rig empty = player(null, 1, false);
        when(empty.hand().getType()).thenReturn(Material.AIR);
        PlayerInteractEvent event = click(empty);
        listener.onInteract(event);
        verify(menus).openMain(empty.player(), spawner);
        verify(event).setUseInteractedBlock(Event.Result.DENY);
    }

    @Test
    void sneakPlacingABlockAgainstTheSpawnerStillWorks() {
        Rig stone = player(null, 8, true);
        when(stone.hand().getType()).thenReturn(Material.STONE);
        PlayerInteractEvent event = click(stone);
        listener.onInteract(event);
        verify(event, never()).setUseInteractedBlock(any());
        verify(menus, never()).openMain(any(), any());
    }

    // ---------------------------------------------------------------- breaking

    private BlockBreakEvent breakEvent(Rig rig) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(rig.player());
        return event;
    }

    @Test
    void withoutSilkTouchNothingChanges() {
        silk = false;
        Rig rig = player(null, 1, false);
        BlockBreakEvent event = breakEvent(rig);
        listener.onBreak(event);
        verify(event).setCancelled(true);
        assertEquals(4, spawner.stack());
        verify(rig.player()).sendActionBar(any(Component.class));
        verify(block, never()).setType(any());
    }

    @Test
    void breakingTakesOneOutOfTheStackAndGivesItAsAnItem() {
        ItemStack drop = mock(ItemStack.class);
        when(items.create("BLAZE", 1)).thenReturn(drop);
        Rig rig = player(null, 1, false);
        listener.onBreak(breakEvent(rig));
        assertEquals(3, spawner.stack());
        verify(rig.player().getInventory()).addItem(drop);
        verify(block, never()).setType(any());
    }

    @Test
    void sneakBreakingTakesABatchButNeverMoreThanTheStack() {
        ItemStack three = mock(ItemStack.class);
        when(items.create("BLAZE", 3)).thenReturn(three);
        settings = rebuild(3);
        Rig rig = player(null, 1, true);
        listener.onBreak(breakEvent(rig));
        assertEquals(1, spawner.stack());
        verify(rig.player().getInventory()).addItem(three);
    }

    private Settings rebuild(int batch) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            try (var in = ListenerTest.class.getResourceAsStream("/config.yml")) {
                yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            yaml.set("sneak_break_batch", batch);
            return Settings.parse(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void takingTheLastOneRemovesTheBlockAndPaysOutTheLootAndExperience() throws Exception {
        spawner.stack(1);
        spawner.addLoot("BLAZE_ROD", 50);
        spawner.xp(700);
        ItemStack drop = mock(ItemStack.class);
        when(items.create("BLAZE", 1)).thenReturn(drop);
        Rig rig = player(null, 1, false);
        listener.onBreak(breakEvent(rig));
        assertNull(registry.at(SpawnerListener.pos(block)));
        verify(block).setType(Material.AIR);
        verify(actions).payOut(rig.player(), Map.of("BLAZE_ROD", 50L), 700);
        Registry reloaded = new Registry(dir.resolve("spawners.yml"));
        reloaded.load();
        assertTrue(reloaded.all().isEmpty(), "the removal was saved");
    }

    @Test
    void ifSavingFailsTheSpawnerStaysAndNothingIsGiven() throws Exception {
        Files.createDirectory(dir.resolve("spawners.yml.tmp"));
        Rig rig = player(null, 1, false);
        listener.onBreak(breakEvent(rig));
        assertEquals(4, spawner.stack());
        verify(rig.player().getInventory(), never()).addItem(any(ItemStack.class));
        spawner.stack(1);
        listener.onBreak(breakEvent(rig));
        assertEquals(spawner, registry.at(SpawnerListener.pos(block)), "the last spawner is still registered");
        verify(actions, never()).payOut(any(), any(), anyLong());
    }

    @Test
    void aSpawnerThatIsNotStackedIsLeftToVanilla() {
        Block plain = block(99);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(plain);
        listener.onBreak(event);
        verify(event, never()).setCancelled(true);
    }

    // ---------------------------------------------------------------- protection

    @Test
    void explosionsAndPistonsLeaveStackedSpawnersAlone() {
        Block other = block(99);
        List<Block> list = new ArrayList<>(List.of(block, other));
        EntityExplodeEvent explode = mock(EntityExplodeEvent.class);
        when(explode.blockList()).thenReturn(list);
        listener.onExplode(explode);
        assertEquals(List.of(other), list);

        List<Block> list2 = new ArrayList<>(List.of(block, other));
        BlockExplodeEvent bed = mock(BlockExplodeEvent.class);
        when(bed.blockList()).thenReturn(list2);
        listener.onBlockExplode(bed);
        assertEquals(List.of(other), list2);

        BlockPistonExtendEvent push = mock(BlockPistonExtendEvent.class);
        when(push.getBlocks()).thenReturn(List.of(block));
        listener.onPistonExtend(push);
        verify(push).setCancelled(true);
        BlockPistonExtendEvent harmless = mock(BlockPistonExtendEvent.class);
        when(harmless.getBlocks()).thenReturn(List.of(other));
        listener.onPistonExtend(harmless);
        verify(harmless, never()).setCancelled(true);
    }

    @Test
    void stackedSpawnersNeverSpawnMobs() {
        var spawnerBlock = mock(org.bukkit.block.CreatureSpawner.class);
        when(spawnerBlock.getBlock()).thenReturn(block);
        var event = mock(org.bukkit.event.entity.SpawnerSpawnEvent.class);
        when(event.getSpawner()).thenReturn(spawnerBlock);
        listener.onSpawnerSpawn(event);
        verify(event).setCancelled(true);
        var plain = mock(org.bukkit.block.CreatureSpawner.class);
        Block other = block(98);
        when(plain.getBlock()).thenReturn(other);
        var vanilla = mock(org.bukkit.event.entity.SpawnerSpawnEvent.class);
        when(vanilla.getSpawner()).thenReturn(plain);
        listener.onSpawnerSpawn(vanilla);
        verify(vanilla, never()).setCancelled(true);
    }
}
