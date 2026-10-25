package net.kaloyan.smpprotect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The listener with Bukkit mocked: who is stopped, who is not, and what is cancelled. */
class ProtectionListenerTest {

    @TempDir
    Path dir;

    private World hub;
    private World survival;
    private RegionManager manager;
    private ProtectionListener listener;
    private final UUID member = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        hub = mock(World.class);
        when(hub.getName()).thenReturn("hub");
        survival = mock(World.class);
        when(survival.getName()).thenReturn("world");
        manager = new RegionManager(new RegionStore(dir.resolve("regions.yml")));
        Region region = Region.wholeWorld("hub", "hub");
        for (Flag flag : Flag.values()) {
            region = region.withFlag(flag, false);
        }
        manager.put(region.withMember(member, true));
        Guard guard = new Guard(manager);
        listener = new ProtectionListener(guard, manager);
    }

    private Player player(UUID id, boolean bypass) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.hasPermission(Guard.BYPASS)).thenReturn(bypass);
        return p;
    }

    private Block block(World world, int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getLocation()).thenReturn(new Location(world, x, y, z));
        when(block.getWorld()).thenReturn(world);
        return block;
    }

    @Test
    void strangersCannotBreakOrPlaceInTheHubButCanInOtherWorlds() {
        Player stranger = player(UUID.randomUUID(), false);
        BlockBreakEvent inHub = new BlockBreakEvent(block(hub, 0, 100, 0), stranger);
        listener.onBreak(inHub);
        assertTrue(inHub.isCancelled());
        verify(stranger).sendActionBar(any(Component.class));

        BlockBreakEvent elsewhere = new BlockBreakEvent(block(survival, 0, 100, 0), stranger);
        listener.onBreak(elsewhere);
        assertFalse(elsewhere.isCancelled());

        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getPlayer()).thenReturn(stranger);
        Block placed = block(hub, 1, 100, 1);
        when(place.getBlock()).thenReturn(placed);
        listener.onPlace(place);
        verify(place).setCancelled(true);
    }

    @Test
    void membersAndBypassPlayersAreNotStopped() {
        BlockBreakEvent byMember = new BlockBreakEvent(block(hub, 0, 100, 0), player(member, false));
        listener.onBreak(byMember);
        assertFalse(byMember.isCancelled());

        Player staff = player(UUID.randomUUID(), true);
        BlockBreakEvent byStaff = new BlockBreakEvent(block(hub, 0, 100, 0), staff);
        listener.onBreak(byStaff);
        assertFalse(byStaff.isCancelled());
        verify(staff, never()).sendActionBar(any(Component.class));
    }

    @Test
    void pvpIsStoppedEvenForMembers() {
        Player attacker = player(member, false);
        Player victim = player(UUID.randomUUID(), false);
        when(victim.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        when(attacker.getLocation()).thenReturn(new Location(hub, 1, 100, 0));
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        listener.onDamageByEntity(event);
        verify(event).setCancelled(true);
    }

    @Test
    void hittingAMonsterIsAlwaysAllowed() {
        Player attacker = player(UUID.randomUUID(), false);
        Zombie zombie = mock(Zombie.class);
        when(zombie.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(zombie);
        listener.onDamageByEntity(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void armorStandsAndItemFramesFollowEntityDamageButVillagersFollowInteract() throws IOException {
        // like the hub preset: entity-damage denied, interact allowed
        Region open = Region.wholeWorld("hub", "hub").withFlag(Flag.ENTITY_DAMAGE, false).withFlag(Flag.INTERACT, true);
        manager.put(open);
        Player stranger = player(UUID.randomUUID(), false);

        org.bukkit.entity.ArmorStand stand = mock(org.bukkit.entity.ArmorStand.class);
        when(stand.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        org.bukkit.event.player.PlayerArmorStandManipulateEvent strip = mock(org.bukkit.event.player.PlayerArmorStandManipulateEvent.class);
        when(strip.getPlayer()).thenReturn(stranger);
        when(strip.getRightClicked()).thenReturn(stand);
        listener.onArmorStand(strip);
        verify(strip).setCancelled(true);

        org.bukkit.entity.ItemFrame frame = mock(org.bukkit.entity.ItemFrame.class);
        when(frame.getLocation()).thenReturn(new Location(hub, 1, 100, 0));
        org.bukkit.event.player.PlayerInteractEntityEvent put = mock(org.bukkit.event.player.PlayerInteractEntityEvent.class);
        when(put.getPlayer()).thenReturn(stranger);
        when(put.getRightClicked()).thenReturn(frame);
        listener.onInteractEntity(put);
        verify(put).setCancelled(true);

        org.bukkit.entity.Villager villager = mock(org.bukkit.entity.Villager.class);
        when(villager.getLocation()).thenReturn(new Location(hub, 2, 100, 0));
        org.bukkit.event.player.PlayerInteractEntityEvent trade = mock(org.bukkit.event.player.PlayerInteractEntityEvent.class);
        when(trade.getPlayer()).thenReturn(stranger);
        when(trade.getRightClicked()).thenReturn(villager);
        listener.onInteractEntity(trade);
        verify(trade, never()).setCancelled(true);
    }

    @Test
    void explosionsOnlyLoseTheBlocksInsideProtectedRegions() {
        Block inHub = block(hub, 0, 100, 0);
        Block outside = block(survival, 0, 100, 0);
        EntityExplodeEvent event = mock(EntityExplodeEvent.class);
        List<Block> blocks = new ArrayList<>(List.of(inHub, outside));
        when(event.blockList()).thenReturn(blocks);
        listener.onEntityExplode(event);
        assertEquals(List.of(outside), blocks);
    }

    @Test
    void naturalSpawnsAreCancelledButSpawnerAndCommandSpawnsAreNot() {
        Entity zombie = mock(Zombie.class);
        when(zombie.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        CreatureSpawnEvent natural = mock(CreatureSpawnEvent.class);
        when(natural.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
        when(natural.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        listener.onSpawn(natural);
        verify(natural).setCancelled(true);

        CreatureSpawnEvent custom = mock(CreatureSpawnEvent.class);
        when(custom.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.CUSTOM);
        when(custom.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        listener.onSpawn(custom);
        verify(custom, never()).setCancelled(true);

        CreatureSpawnEvent spawner = mock(CreatureSpawnEvent.class);
        when(spawner.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.SPAWNER);
        when(spawner.getLocation()).thenReturn(new Location(hub, 0, 100, 0));
        listener.onSpawn(spawner);
        verify(spawner, never()).setCancelled(true);
    }
}
