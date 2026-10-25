package net.kaloyan.smpprotect;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Enforces the flags. Every handler runs late and ignores events another plugin already cancelled. Players with
 * {@code smpprotect.bypass} are never stopped.
 */
public final class ProtectionListener implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL = Set.of(CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.RAID, CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION, CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.MOUNT, CreatureSpawnEvent.SpawnReason.TRAP,
            CreatureSpawnEvent.SpawnReason.LIGHTNING, CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK);

    private final Guard guard;
    private final RegionManager manager;

    /**
     * @param guard   the checks
     * @param manager the regions
     */
    public ProtectionListener(Guard guard, RegionManager manager) {
        this.guard = guard;
        this.manager = manager;
    }

    private void cancelIf(boolean deny, Cancellable event) {
        if (deny) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancelIf(guard.denies(event.getPlayer(), event.getBlock().getLocation(), Flag.BUILD), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancelIf(guard.denies(event.getPlayer(), event.getBlock().getLocation(), Flag.BREAK), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        cancelIf(guard.denies(event.getPlayer(), event.getBlock().getLocation(), Flag.BUILD), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        cancelIf(guard.denies(event.getPlayer(), event.getBlock().getLocation(), Flag.BREAK), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        cancelIf(guard.denies(event.getPlayer(), event.getEntity().getLocation(), Flag.ENTITY_DAMAGE), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (event.getPlayer() != null) {
            cancelIf(guard.denies(event.getPlayer(), event.getBlock().getLocation(), Flag.BUILD), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            cancelIf(guard.denies(event.getPlayer(), event.getBlock().getLocation(), Flag.BUILD), event);
        } else {
            cancelIf(guard.deniesEnvironment(event.getBlock().getLocation(), Flag.FIRE), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        cancelIf(guard.deniesEnvironment(event.getBlock().getLocation(), Flag.FIRE), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (event.getNewState().getType() == Material.FIRE || event.getNewState().getType() == Material.SOUL_FIRE) {
            cancelIf(guard.deniesEnvironment(event.getBlock().getLocation(), Flag.FIRE), event);
        }
    }

    // ---------------------------------------------------------------- interaction

    // Material#isInteractable is deprecated without a replacement; it is still the only complete list of clickable blocks.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            cancelIf(guard.denies(player, block.getLocation(), Flag.INTERACT), event);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (isContainer(block)) {
            cancelIf(guard.denies(player, block.getLocation(), Flag.CONTAINER), event);
            return;
        }
        ItemStack held = event.getItem();
        if (held != null && placesSomething(held.getType())
                && guard.denies(player, block.getRelative(event.getBlockFace()).getLocation(), Flag.BUILD)) {
            event.setCancelled(true);
            return;
        }
        if (block.getType().isInteractable()) {
            cancelIf(guard.denies(player, block.getLocation(), Flag.INTERACT), event);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isContainer(Block block) {
        Material type = block.getType();
        if (type == Material.LECTERN || type == Material.CHISELED_BOOKSHELF || type == Material.DECORATED_POT) {
            return true;
        }
        if (!type.isInteractable()) {
            return false;
        }
        BlockState state = block.getState(false);
        return state instanceof Container;
    }

    /** Items that change the world without a block-place event. */
    private static boolean placesSomething(Material type) {
        String name = type.name();
        return name.endsWith("_SPAWN_EGG") || type == Material.FLINT_AND_STEEL || type == Material.FIRE_CHARGE
                || type == Material.BONE_MEAL;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        cancelIf(guard.denies(event.getPlayer(), entity.getLocation(), flagFor(entity)), event);
    }

    /**
     * Right clicking an entity: containers on wheels or water are CONTAINER; item frames and armor stands (put an item in,
     * rotate it, swap its armor) are decoration and fall under ENTITY_DAMAGE; everything else (villagers, horses) is INTERACT,
     * so a hub can keep its villager shops usable while its decorations stay untouched.
     */
    private static Flag flagFor(Entity entity) {
        if (entity instanceof StorageMinecart || entity instanceof HopperMinecart || entity instanceof org.bukkit.entity.ChestBoat) {
            return Flag.CONTAINER;
        }
        if (entity instanceof org.bukkit.entity.ItemFrame || entity instanceof ArmorStand) {
            return Flag.ENTITY_DAMAGE;
        }
        return Flag.INTERACT;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        cancelIf(guard.denies(event.getPlayer(), event.getRightClicked().getLocation(), Flag.ENTITY_DAMAGE), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            cancelIf(guard.denies(event.getPlayer(), event.getEntity().getLocation(), Flag.BUILD), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            cancelIf(guard.denies(event.getPlayer(), event.getEntity().getLocation(), Flag.BUILD), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Location at = event.getEntity().getLocation();
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player) {
            cancelIf(guard.denies(player, at, Flag.BREAK), event);
        } else if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            cancelIf(guard.deniesEnvironment(at, Flag.EXPLOSION), event);
        } else if (event.getCause() == HangingBreakEvent.RemoveCause.ENTITY
                || event.getCause() == HangingBreakEvent.RemoveCause.DEFAULT) {
            cancelIf(guard.deniesEnvironment(at, Flag.BREAK), event);
        }
    }

    // ---------------------------------------------------------------- damage

    private static Player playerBehind(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = playerBehind(event.getDamager());
        if (attacker == null) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim instanceof Player target) {
            if (!target.equals(attacker) && (guard.denies(attacker, target.getLocation(), Flag.PVP)
                    || guard.denies(attacker, attacker.getLocation(), Flag.PVP))) {
                event.setCancelled(true);
            }
            return;
        }
        boolean protectedKind = !(victim instanceof Enemy) && (victim instanceof org.bukkit.entity.LivingEntity
                || victim instanceof Hanging || victim instanceof Vehicle || victim instanceof ArmorStand);
        if (protectedKind) {
            cancelIf(guard.denies(attacker, victim.getLocation(), Flag.ENTITY_DAMAGE), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.SUICIDE) {
            return;
        }
        cancelIf(guard.deniesEnvironment(player.getLocation(), Flag.DAMAGE), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && event.getFoodLevel() < player.getFoodLevel()) {
            cancelIf(guard.deniesEnvironment(player.getLocation(), Flag.HUNGER), event);
        }
    }

    // ---------------------------------------------------------------- environment

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (NATURAL.contains(event.getSpawnReason())) {
            cancelIf(guard.deniesEnvironment(event.getLocation(), Flag.MOB_SPAWN), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> guard.deniesEnvironment(block.getLocation(), Flag.EXPLOSION));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> guard.deniesEnvironment(block.getLocation(), Flag.EXPLOSION));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player || event.getEntity() instanceof FallingBlock) {
            return;
        }
        cancelIf(guard.deniesEnvironment(event.getBlock().getLocation(), Flag.MOB_GRIEF), event);
    }

    // ---------------------------------------------------------------- liquids and pistons

    private Set<String> namesAt(Block block) {
        return manager.index().regionsAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()).stream()
                .map(Region::name).collect(Collectors.toSet());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (!manager.index().hasRegions(to.getWorld().getName())) {
            return;
        }
        if (guard.deniesEnvironment(to.getLocation(), Flag.BUILD) && !namesAt(from).equals(namesAt(to))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        cancelIf(pistonCrossesProtection(event.getBlock(), event.getBlocks(), event.getDirection(), true), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        cancelIf(pistonCrossesProtection(event.getBlock(), event.getBlocks(), event.getDirection(), false), event);
    }

    /**
     * A piston may not push into, pull out of, or move blocks across the edge of a region that denies building or
     * breaking, unless the piston stands in the same regions as the blocks it moves.
     */
    private boolean pistonCrossesProtection(Block piston, List<Block> moved, BlockFace direction, boolean extend) {
        if (!manager.index().hasRegions(piston.getWorld().getName())) {
            return false;
        }
        Set<String> pistonRegions = namesAt(piston);
        if (extend) {
            Block head = piston.getRelative(direction);
            if (!namesAt(head).equals(pistonRegions) && guard.deniesEnvironment(head.getLocation(), Flag.BUILD)) {
                return true;
            }
        }
        for (Block block : moved) {
            Set<String> source = namesAt(block);
            if (!source.equals(pistonRegions) && guard.deniesEnvironment(block.getLocation(), Flag.BREAK)) {
                return true;
            }
            if (extend) {
                Block destination = block.getRelative(direction);
                if (!namesAt(destination).equals(pistonRegions) && guard.deniesEnvironment(destination.getLocation(), Flag.BUILD)) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guard.forget(event.getPlayer().getUniqueId());
    }
}
