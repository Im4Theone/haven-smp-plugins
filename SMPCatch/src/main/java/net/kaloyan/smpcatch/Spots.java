package net.kaloyan.smpcatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Where decoys may go. A decoy must be invisible to an honest player, so it only goes into solid rock that has rock on every side:
 * no cave, tunnel, water or lava can ever show it. This class holds only the geometry, so it can be tested without a server.
 */
public final class Spots {

    /** What the world looks like around a spot. */
    public interface Grid {
        /** @return whether the block is loaded and made of a rock a decoy may replace (stone, deepslate, netherrack, ...) */
        boolean host(int x, int y, int z);

        /** @return whether the block is loaded and a full solid block (not air, not liquid, not a plant) */
        boolean solid(int x, int y, int z);
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private Spots() {
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * @return whether every neighbour of the block is solid or part of the vein being built
     */
    private static boolean enclosed(Grid grid, Set<Long> vein, int x, int y, int z) {
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int ny = y + d[1];
            int nz = z + d[2];
            if (!vein.contains(key(nx, ny, nz)) && !grid.solid(nx, ny, nz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Grows a vein from a starting block.
     *
     * @param grid the world
     * @param rnd  randomness
     * @param sx   start x
     * @param sy   start y
     * @param sz   start z
     * @param size how many blocks at most
     * @return the blocks of the vein (x, y, z each), empty if the start is not a hidden spot
     */
    public static List<int[]> vein(Grid grid, Random rnd, int sx, int sy, int sz, int size) {
        List<int[]> out = new ArrayList<>();
        Set<Long> set = new HashSet<>();
        if (!grid.host(sx, sy, sz) || !enclosed(grid, set, sx, sy, sz)) {
            return out;
        }
        out.add(new int[] {sx, sy, sz});
        set.add(key(sx, sy, sz));
        for (int attempt = 0; attempt < size * 12 && out.size() < size; attempt++) {
            int[] from = out.get(rnd.nextInt(out.size()));
            int[] d = DIRS[rnd.nextInt(DIRS.length)];
            int x = from[0] + d[0];
            int y = from[1] + d[1];
            int z = from[2] + d[2];
            if (set.contains(key(x, y, z)) || !grid.host(x, y, z) || !enclosed(grid, set, x, y, z)) {
                continue;
            }
            out.add(new int[] {x, y, z});
            set.add(key(x, y, z));
        }
        return out;
    }

    /**
     * Whether a hidden chamber fits: a room of 3 x 2 x 3 blocks (floor at {@code cy}) that is solid rock now, inside a shell of
     * rock one block thick on every side.
     *
     * @param grid the world
     * @param cx   centre x of the room
     * @param cy   y of the floor of the room
     * @param cz   centre z of the room
     * @return whether the chamber can be made here
     */
    public static boolean chamberFits(Grid grid, int cx, int cy, int cz) {
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int y = cy - 1; y <= cy + 2; y++) {
                for (int z = cz - 2; z <= cz + 2; z++) {
                    boolean inside = Math.abs(x - cx) <= 1 && y >= cy && y <= cy + 1 && Math.abs(z - cz) <= 1;
                    if (inside ? !grid.host(x, y, z) : !grid.solid(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * @param cx centre x
     * @param cy floor y
     * @param cz centre z
     * @return the 18 blocks of the room
     */
    public static List<int[]> chamber(int cx, int cy, int cz) {
        List<int[]> out = new ArrayList<>();
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int y = cy; y <= cy + 1; y++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    out.add(new int[] {x, y, z});
                }
            }
        }
        return out;
    }
}
