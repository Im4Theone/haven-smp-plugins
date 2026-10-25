package net.kaloyan.smptravel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A saved position.
 *
 * @param world the world's name
 * @param x     X
 * @param y     Y
 * @param z     Z
 * @param yaw   horizontal look direction
 * @param pitch vertical look direction
 */
public record Spot(String world, double x, double y, double z, float yaw, float pitch) {

    /**
     * @param location a location in a loaded world
     * @return the same position as a spot
     */
    public static Spot of(Location location) {
        return new Spot(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(),
                location.getPitch());
    }

    /** @return the position, or {@code null} if the world is not loaded */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }
}
