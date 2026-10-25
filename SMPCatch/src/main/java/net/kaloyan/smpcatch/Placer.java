package net.kaloyan.smpcatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Builds and removes decoys in the world. Main thread only; it never touches a chunk that is not loaded. */
public final class Placer {

    private final TrapStore store;
    private final Supplier<Settings> settings;
    private final NamespacedKey baitKey;

    /**
     * @param store    the decoys
     * @param settings the current settings
     * @param baitKey  the invisible mark on stash items
     */
    public Placer(TrapStore store, Supplier<Settings> settings, NamespacedKey baitKey) {
        this.store = store;
        this.settings = settings;
        this.baitKey = baitKey;
    }

    private Spots.Grid grid(World world) {
        Settings s = settings.get();
        return new Spots.Grid() {
            @Override
            public boolean host(int x, int y, int z) {
                return inside(world, x, y, z) && s.hosts().contains(world.getBlockAt(x, y, z).getType());
            }

            @Override
            public boolean solid(int x, int y, int z) {
                if (!inside(world, x, y, z)) {
                    return false;
                }
                Material m = world.getBlockAt(x, y, z).getType();
                return m.isSolid() && !m.isAir() && m.isOccluding();
            }
        };
    }

    private static boolean inside(World world, int x, int y, int z) {
        return y > world.getMinHeight() && y < world.getMaxHeight() - 1 && world.isChunkLoaded(x >> 4, z >> 4);
    }

    /**
     * Places fake veins in the rock around a point.
     *
     * @param world  the world
     * @param cx     centre x
     * @param cz     centre z
     * @param radius how far from the centre
     * @param ore    which ore
     * @param count  how many veins
     * @param rnd    randomness
     * @return the decoys that were placed (fewer than asked if no hidden spot was found)
     * @throws IOException if the list could not be saved
     */
    public List<TrapStore.Trap> veins(World world, int cx, int cz, int radius, Ore ore, int count, Random rnd) throws IOException {
        Spots.Grid grid = grid(world);
        List<TrapStore.Trap> placed = new ArrayList<>();
        int lo = Math.max(ore.minY(), world.getMinHeight() + 2);
        int hi = Math.min(ore.maxY(), world.getMaxHeight() - 3);
        for (int attempt = 0; attempt < count * 4000 && placed.size() < count && hi >= lo; attempt++) {
            int x = cx + rnd.nextInt(radius * 2 + 1) - radius;
            int z = cz + rnd.nextInt(radius * 2 + 1) - radius;
            int y = lo + rnd.nextInt(hi - lo + 1);
            int size = ore.minSize() + rnd.nextInt(ore.maxSize() - ore.minSize() + 1);
            List<int[]> blocks = Spots.vein(grid, rnd, x, y, z, size);
            if (blocks.isEmpty()) {
                continue;
            }
            List<TrapStore.Part> parts = new ArrayList<>();
            List<Material> fake = new ArrayList<>();
            for (int[] b : blocks) {
                Material host = world.getBlockAt(b[0], b[1], b[2]).getType();
                parts.add(new TrapStore.Part(b[0], b[1], b[2], host.name(), "ore"));
                fake.add(host == Material.DEEPSLATE ? ore.deep() : ore.normal());
            }
            TrapStore.Trap trap = new TrapStore.Trap(store.nextId(), "VEIN", ore.name(), world.getName(), System.currentTimeMillis(), parts);
            store.add(trap);        // saved first: if the server stops now, the list has a decoy that is simply not built
            for (int i = 0; i < blocks.size(); i++) {
                int[] b = blocks.get(i);
                world.getBlockAt(b[0], b[1], b[2]).setType(fake.get(i), false);
            }
            placed.add(trap);
        }
        return placed;
    }

    /**
     * Builds a hidden chamber with a stash chest in the rock around a point.
     *
     * @param world     the world
     * @param cx        centre x
     * @param cz        centre z
     * @param radius    how far from the centre
     * @param container the block that holds the loot: a chest, barrel or shulker box
     * @param rnd       randomness
     * @return the decoy, or {@code null} if no hidden spot was found
     * @throws IOException if the list could not be saved
     */
    public TrapStore.Trap stash(World world, int cx, int cz, int radius, Material container, Random rnd) throws IOException {
        Spots.Grid grid = grid(world);
        boolean nether = world.getEnvironment() == World.Environment.NETHER;
        int lo = Math.max(world.getMinHeight() + 4, nether ? 12 : -56);
        int hi = Math.min(world.getMaxHeight() - 6, nether ? 100 : 40);
        for (int attempt = 0; attempt < 6000; attempt++) {
            int x = cx + rnd.nextInt(radius * 2 + 1) - radius;
            int z = cz + rnd.nextInt(radius * 2 + 1) - radius;
            int y = lo + rnd.nextInt(hi - lo + 1);
            if (!Spots.chamberFits(grid, x, y, z)) {
                continue;
            }
            List<TrapStore.Part> parts = new ArrayList<>();
            for (int[] b : Spots.chamber(x, y, z)) {
                boolean chest = b[0] == x && b[1] == y && b[2] == z;
                parts.add(new TrapStore.Part(b[0], b[1], b[2], world.getBlockAt(b[0], b[1], b[2]).getType().name(), chest ? "container" : "cavity"));
            }
            TrapStore.Trap trap = new TrapStore.Trap(store.nextId(), "STASH", container.name().toLowerCase(Locale.ROOT), world.getName(),
                    System.currentTimeMillis(), parts);
            store.add(trap);
            for (int[] b : Spots.chamber(x, y, z)) {
                world.getBlockAt(b[0], b[1], b[2]).setType(Material.AIR, false);
            }
            Block block = world.getBlockAt(x, y, z);
            block.setType(container, false);
            if (block.getBlockData() instanceof Directional d && d.getFaces().contains(BlockFace.NORTH)) {
                BlockFace[] faces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
                d.setFacing(faces[rnd.nextInt(faces.length)]);
                block.setBlockData(d, false);
            }
            if (block.getState() instanceof Container c) {
                fill(c.getInventory(), rnd);
            }
            return trap;
        }
        return null;
    }

    private void fill(Inventory inventory, Random rnd) {
        for (Settings.Loot loot : settings.get().loot()) {
            if (rnd.nextDouble() * 100 >= loot.chance()) {
                continue;
            }
            ItemStack stack = new ItemStack(loot.item(), loot.min() + rnd.nextInt(loot.max() - loot.min() + 1));
            ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(baitKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
            for (int tries = 0; tries < 30; tries++) {
                int slot = rnd.nextInt(inventory.getSize());
                if (inventory.getItem(slot) == null) {
                    inventory.setItem(slot, stack);
                    break;
                }
            }
        }
    }

    /**
     * Takes a decoy out of the world and the list: stash chests are emptied, and blocks that still look like the decoy are turned
     * back into rock. Blocks someone has already mined or changed are left alone.
     *
     * @param trap the decoy
     * @return {@code true} if the list was saved
     */
    public boolean remove(TrapStore.Trap trap) {
        World world = org.bukkit.Bukkit.getWorld(trap.world());
        if (world != null) {
            for (TrapStore.Part p : trap.parts()) {
                Block block = world.getBlockAt(p.x(), p.y(), p.z());
                Material now = block.getType();
                Material original = Material.getMaterial(p.original());
                if (original == null) {
                    continue;
                }
                switch (p.role()) {
                    case "ore" -> {
                        if (now.name().endsWith("_ORE") || now == Material.ANCIENT_DEBRIS) {
                            block.setType(original, false);
                        }
                    }
                    case "container" -> {
                        if (block.getState() instanceof Container c) {
                            c.getInventory().clear();
                            block.setType(original, false);
                        }
                    }
                    default -> {
                        if (now.isAir()) {
                            block.setType(original, false);
                        }
                    }
                }
            }
        }
        try {
            store.remove(trap.id());
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
