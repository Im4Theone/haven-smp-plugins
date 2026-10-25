package net.kaloyan.smpdrill;

import java.util.ArrayList;
import java.util.List;

/**
 * Which blocks one swing of the drill breaks: a square wall (5x5 by default) around the block that was hit, perpendicular to the
 * face that was hit, and optionally several layers deep. Pure geometry, no server needed.
 */
public final class TunnelShape {

    /** The axis a mined face points along. */
    public enum Axis { X, Y, Z }

    /**
     * One block position.
     *
     * @param x X
     * @param y Y
     * @param z Z
     */
    public record Pos(int x, int y, int z) {
    }

    private TunnelShape() {
    }

    /**
     * Computes the blocks to break.
     *
     * @param normal the axis the mined face points along (a block hit from the north or south has {@link Axis#Z})
     * @param sign   +1 if the face points to the positive direction of that axis (east, south, up), -1 otherwise; the tunnel goes
     *               the other way, into the wall
     * @param hit    the block that was hit
     * @param feetY  the Y of the block the player's feet are in
     * @param radius blocks on each side of the centre (2 gives 5 wide)
     * @param depth  how many layers deep, at least 1
     * @return the hit block first, then the rest of its layer, then deeper layers
     */
    public static List<Pos> cells(Axis normal, int sign, Pos hit, int feetY, int radius, int depth) {
        if (sign != 1 && sign != -1) {
            throw new IllegalArgumentException("sign must be +1 or -1");
        }
        if (radius < 1 || depth < 1) {
            throw new IllegalArgumentException("radius and depth must be at least 1");
        }
        List<Pos> out = new ArrayList<>();
        // Horizontal tunnels: when the block is at normal reach, the tunnel floor is the floor the player stands on, so the
        // drill never digs the ground away under them; for blocks far above or below, the tunnel is centred on the block.
        int height = 2 * radius + 1;
        int low = (hit.y() >= feetY && hit.y() <= feetY + height - 1) ? feetY : hit.y() - radius;
        for (int k = 0; k < depth; k++) {
            int shift = -sign * k;
            for (int a = -radius; a <= radius; a++) {
                for (int b = 0; b < height; b++) {
                    Pos p = switch (normal) {
                        case Y -> new Pos(hit.x() + a, hit.y() + shift, hit.z() + (b - radius));
                        case X -> new Pos(hit.x() + shift, low + b, hit.z() + a);
                        case Z -> new Pos(hit.x() + a, low + b, hit.z() + shift);
                    };
                    out.add(p);
                }
            }
        }
        out.remove(hit);
        out.add(0, hit);
        return out;
    }
}
