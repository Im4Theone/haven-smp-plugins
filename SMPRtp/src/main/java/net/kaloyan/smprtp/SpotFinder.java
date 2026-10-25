package net.kaloyan.smprtp;

import java.util.OptionalInt;
import org.bukkit.Material;

/**
 * Decides where a player may be dropped: solid, harmless ground, two free blocks above it, and no lava, fire, cactus
 * or other hazard right next to the spot. It only reads blocks through {@link View}, so it can be tested without a
 * server.
 */
public final class SpotFinder {

    /** The blocks of a world. */
    @FunctionalInterface
    public interface View {
        /** @return the material at these coordinates (air outside the world's height) */
        Material type(int x, int y, int z);
    }

    /** What the materials mean; the server version uses the real block properties. */
    public interface Classifier {
        /** @return {@code true} if a player can safely stand on this block */
        boolean standable(Material material);

        /** @return {@code true} if a player can stand inside this block (air and harmless plants) */
        boolean free(Material material);

        /** @return {@code true} if this block must not be next to the spot (lava, fire, cactus...) */
        boolean hazard(Material material);
    }

    private final Classifier classifier;

    /** @param classifier what the materials mean */
    public SpotFinder(Classifier classifier) {
        this.classifier = classifier;
    }

    /**
     * Looks for a safe spot in one column, scanning down.
     *
     * @param view  the world's blocks
     * @param x     block X
     * @param z     block Z
     * @param fromY the highest ground block to consider
     * @param toY   the lowest ground block to consider
     * @return the Y a player's feet should be at, or empty if the column has no safe spot
     */
    public OptionalInt find(View view, int x, int z, int fromY, int toY) {
        for (int y = fromY; y >= toY; y--) {
            Material ground = view.type(x, y, z);
            if (!classifier.standable(ground)) {
                continue;
            }
            if (classifier.free(view.type(x, y + 1, z)) && classifier.free(view.type(x, y + 2, z))
                    && !hazardNear(view, x, y, z)) {
                return OptionalInt.of(y + 1);
            }
        }
        return OptionalInt.empty();
    }

    private boolean hazardNear(View view, int x, int groundY, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = groundY - 1; y <= groundY + 3; y++) {
                    if (classifier.hazard(view.type(x + dx, y, z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
