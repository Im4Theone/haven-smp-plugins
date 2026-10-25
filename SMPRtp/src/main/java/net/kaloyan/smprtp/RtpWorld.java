package net.kaloyan.smprtp;

import java.util.Random;

/**
 * Where and how one destination is searched.
 *
 * @param key       the name used in commands: overworld, nether or end
 * @param world     the Bukkit world's name
 * @param enabled   whether players may use it
 * @param centerX   centre of the search ring
 * @param centerZ   centre of the search ring
 * @param minRadius nearest distance from the centre (0 = anywhere)
 * @param size      side length of the square search area around the centre, in blocks
 * @param minY      lowest ground block that may be used
 * @param maxY      highest ground block that may be used ({@code -1} = the highest block of the column, for the overworld)
 * @param cost      price in the server currency (0 = free)
 */
public record RtpWorld(String key, String world, boolean enabled, int centerX, int centerZ, int minRadius, int size,
                       int minY, int maxY, double cost) {

    /**
     * Picks a random point in the square (uniformly), at least {@code minRadius} blocks from the centre.
     *
     * @param random the random source
     * @return {x, z}
     */
    public int[] sample(Random random) {
        int half = size / 2;
        for (int attempt = 0; attempt < 1000; attempt++) {
            int dx = random.nextInt(-half, half + 1);
            int dz = random.nextInt(-half, half + 1);
            if ((long) dx * dx + (long) dz * dz >= (long) minRadius * minRadius) {
                return new int[] {centerX + dx, centerZ + dz};
            }
        }
        return new int[] {centerX + half, centerZ};
    }
}
