package net.kaloyan.smpdrill;

import java.util.List;
import java.util.function.Supplier;
import net.kaloyan.smpdrill.TunnelShape.Axis;
import net.kaloyan.smpdrill.TunnelShape.Pos;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Breaks the rest of the tunnel when a player mines a block with the Amethyst Drill. Every extra block goes through a normal
 * {@link BlockBreakEvent} first, so region protection (SMP-Protect) and every other plugin can refuse it exactly as they would
 * for a block broken by hand. Main thread only.
 */
public final class DrillListener implements Listener {

    private final Plugin plugin;
    private final DrillItem drill;
    private final Supplier<DrillSettings> settings;
    private final java.util.function.Predicate<Block> breakable;
    private boolean drilling;      // set while our own break events are fired, so they are not drilled again

    /**
     * @param plugin   the owning plugin
     * @param drill    recognises the drill
     * @param settings the current settings
     */
    public DrillListener(Plugin plugin, DrillItem drill, Supplier<DrillSettings> settings) {
        this(plugin, drill, settings, DrillListener::breakable);
    }

    /**
     * @param plugin    the owning plugin
     * @param drill     recognises the drill
     * @param settings  the current settings
     * @param breakable decides which blocks may be broken (tests replace it; the real rule needs a running server)
     */
    DrillListener(Plugin plugin, DrillItem drill, Supplier<DrillSettings> settings, java.util.function.Predicate<Block> breakable) {
        this.plugin = plugin;
        this.drill = drill;
        this.settings = settings;
        this.breakable = breakable;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (drilling) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        DrillSettings s = settings.get();
        if (!drill.isDrill(tool) || (s.sneakMinesOne() && player.isSneaking()) || player.getGameMode() == GameMode.SPECTATOR
                || s.disabledWorlds().contains(event.getBlock().getWorld().getName())) {
            return;
        }
        Block hit = event.getBlock();
        BlockFace face = faceOf(player, hit);
        Axis axis;
        int sign;
        switch (face) {
            case UP -> { axis = Axis.Y; sign = 1; }
            case DOWN -> { axis = Axis.Y; sign = -1; }
            case EAST -> { axis = Axis.X; sign = 1; }
            case WEST -> { axis = Axis.X; sign = -1; }
            case SOUTH -> { axis = Axis.Z; sign = 1; }
            default -> { axis = Axis.Z; sign = -1; }
        }
        List<Pos> cells = TunnelShape.cells(axis, sign, new Pos(hit.getX(), hit.getY(), hit.getZ()),
                player.getLocation().getBlockY(), s.radius(), s.depth());
        int broken = 0;
        drilling = true;
        try {
            for (Pos p : cells.subList(1, cells.size())) {      // the first cell is the block the player is breaking anyway
                Block block = hit.getWorld().getBlockAt(p.x(), p.y(), p.z());
                if (!breakable.test(block)) {
                    continue;
                }
                BlockBreakEvent each = new BlockBreakEvent(block, player);
                Bukkit.getPluginManager().callEvent(each);
                if (each.isCancelled()) {
                    continue;
                }
                if (player.getGameMode() == GameMode.CREATIVE || !each.isDropItems()) {
                    block.setType(Material.AIR);
                } else {
                    block.breakNaturally(tool, true, true);
                }
                broken++;
            }
        } finally {
            drilling = false;
        }
        if (broken > 0 && s.wearPerBlock() > 0 && player.getGameMode() != GameMode.CREATIVE) {
            int wear = broken * s.wearPerBlock();
            // after the normal tool damage of this swing, so the two do not overwrite each other
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack now = player.getInventory().getItemInMainHand();
                if (drill.isDrill(now)) {
                    player.getInventory().setItemInMainHand(now.damage(wear, player));
                }
            });
        }
    }

    /** The face of the block the player is mining; falls back to the way they look if the ray does not hit the block. */
    private static BlockFace faceOf(Player player, Block hit) {
        Block target = player.getTargetBlockExact(8);
        BlockFace face = target != null && target.equals(hit) ? player.getTargetBlockFace(8) : null;
        if (face != null && face != BlockFace.SELF) {
            return face;
        }
        float pitch = player.getLocation().getPitch();
        if (pitch > 60) {
            return BlockFace.UP;
        }
        if (pitch < -60) {
            return BlockFace.DOWN;
        }
        BlockFace facing = player.getFacing();
        return facing.getOppositeFace();
    }

    /** Whether the drill may break this block: not air or liquid, not bedrock-like, not a chest, sign, spawner or other block entity. */
    static boolean breakable(Block block) {
        Material type = block.getType();
        if (type.isAir() || block.isLiquid() || type == Material.SPAWNER || type == Material.TRIAL_SPAWNER || type.getHardness() < 0) {
            return false;
        }
        return !(block.getState(false) instanceof TileState);
    }
}
