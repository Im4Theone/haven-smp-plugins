package net.kaloyan.smpcatch;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Notices when somebody reaches a decoy, and keeps the mining statistics. */
public final class TrapListener implements Listener {

    private static final BlockFace[] SIDES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Plugin plugin;
    private final TrapStore store;
    private final Suspects suspects;
    private final Alerts alerts;
    private final Placer placer;
    private final Supplier<Settings> settings;
    private final NamespacedKey baitKey;
    private final Map<UUID, XrayStats> stats = new HashMap<>();
    private final Random random = new Random();

    /**
     * @param plugin   the owning plugin
     * @param store    the decoys
     * @param suspects the points
     * @param alerts   the alerts
     * @param placer   builds decoys
     * @param settings the current settings
     * @param baitKey  the invisible mark on stash items
     */
    public TrapListener(Plugin plugin, TrapStore store, Suspects suspects, Alerts alerts, Placer placer, Supplier<Settings> settings,
                        NamespacedKey baitKey) {
        this.plugin = plugin;
        this.store = store;
        this.suspects = suspects;
        this.alerts = alerts;
        this.placer = placer;
        this.settings = settings;
        this.baitKey = baitKey;
    }

    /** @return whether anything next to the block is open or see-through, so that a player could have seen the block */
    static boolean exposed(Block block) {
        for (BlockFace side : SIDES) {
            if (!block.getRelative(side).getType().isOccluding()) {
                return true;
            }
        }
        return false;
    }

    private static boolean exempt(Player player) {
        return player.hasPermission("smpcatch.bypass");
    }

    // ---------------------------------------------------------------- mining

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Settings s = settings.get();
        TrapStore.Trap trap = store.at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        if (trap != null) {
            TrapStore.Part part = null;
            for (TrapStore.Part p : trap.parts()) {
                if (p.x() == block.getX() && p.y() == block.getY() && p.z() == block.getZ()) {
                    part = p;
                    break;
                }
            }
            if (part != null) {
                caught(player, block, trap, part, event);
            }
            return;
        }
        if (!s.statsEnabled() || exempt(player) || player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Material type = block.getType();
        boolean rare = s.rare().contains(type);
        if (!rare && !s.bulk().contains(type)) {
            return;
        }
        boolean hidden = rare && !exposed(block);
        XrayStats x = stats.computeIfAbsent(player.getUniqueId(), id -> new XrayStats(s.window(), s.minSamples(), s.ratio()));
        if (hidden) {
            suspects.countHiddenRare(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        }
        if (x.record(hidden)) {
            alerts.raise(player, "stats", "mining statistics: too many hidden rare ores in the last blocks", s.pointsFor("xray-stats"),
                    block.getLocation());
        }
    }

    private void caught(Player player, Block block, TrapStore.Trap trap, TrapStore.Part part, BlockBreakEvent event) {
        Settings s = settings.get();
        boolean container = part.role().equals("container");
        boolean hidden = container || !exposed(block);
        store.triggered(trap);
        if (!container && !s.veinDrops()) {
            event.setDropItems(false);
        }
        store.mined(trap, part);
        if (exempt(player)) {
            return;
        }
        String name = block.getType().name();
        if (container) {
            alerts.raise(player, "break-" + trap.id(), "broke the stash chest of trap #" + trap.id(), s.pointsFor("stash-break"), block.getLocation());
        } else {
            alerts.raise(player, "vein-" + trap.id(), "mined " + name + " (trap #" + trap.id() + ", " + (hidden ? "hidden inside rock" : "visible")
                    + ")", s.pointsFor(hidden ? "vein-hidden" : "vein-visible"), block.getLocation());
        }
    }

    // ---------------------------------------------------------------- stashes

    private TrapStore.Trap stashAt(Location where) {
        if (where == null || where.getWorld() == null) {
            return null;
        }
        TrapStore.Trap t = store.at(where.getWorld().getName(), where.getBlockX(), where.getBlockY(), where.getBlockZ());
        return t != null && t.kind().equals("STASH") ? t : null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || exempt(player)) {
            return;
        }
        TrapStore.Trap trap = stashAt(event.getInventory().getLocation());
        if (trap != null) {
            store.triggered(trap);
            alerts.raise(player, "open-" + trap.id(), "opened the hidden stash of trap #" + trap.id(), settings.get().pointsFor("stash-open"),
                    player.getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTake(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || exempt(player) || event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        TrapStore.Trap trap = stashAt(event.getClickedInventory().getLocation());
        ItemStack item = event.getCurrentItem();
        if (trap != null && isBait(item)) {
            alerts.raise(player, "take-" + trap.id(), "took " + item.getAmount() + " " + item.getType().name().toLowerCase(Locale.ROOT)
                    + " out of the stash of trap #" + trap.id(), settings.get().pointsFor("bait-taken"), player.getLocation());
        }
    }

    /**
     * @param item an item
     * @return whether it came out of a stash (it carries the invisible mark)
     */
    public boolean isBait(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(baitKey, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- automatic decoys

    @EventHandler
    public void onChunk(ChunkLoadEvent event) {
        Settings s = settings.get();
        World world = event.getWorld();
        if (!event.isNewChunk() || !s.seedEnabled() || !s.seedWorlds().contains(world.getName()) || random.nextDouble() * 100 >= s.seedChance()
                || s.seedTypes().isEmpty()) {
            return;
        }
        int cx = event.getChunk().getX() * 16 + 8;
        int cz = event.getChunk().getZ() * 16 + 8;
        // later, when the neighbouring chunks are probably there too, so the rock around the decoy can be checked
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Ore ore = Ore.find(s.seedTypes().get(random.nextInt(s.seedTypes().size())), world.getEnvironment());
            if (ore == null) {
                return;
            }
            try {
                placer.veins(world, cx, cz, 7, ore, 1, random);
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Could not save a new decoy: " + e.getMessage());
            }
        }, 200L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stats.remove(event.getPlayer().getUniqueId());
    }
}
