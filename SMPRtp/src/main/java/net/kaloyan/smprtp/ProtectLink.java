package net.kaloyan.smprtp;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Asks SMP-Protect (if installed) whether a spot is inside a region that denies building. It uses reflection so that
 * SMP-RTP needs no jar of SMP-Protect and works without it.
 */
final class ProtectLink {

    private Method method;
    private Plugin target;

    /**
     * @param world the world's name
     * @param x     block X
     * @param y     block Y
     * @param z     block Z
     * @return {@code true} if SMP-Protect says players may not build there; {@code false} if they may, or if SMP-Protect is
     *         not installed or cannot be asked
     */
    boolean isProtected(String world, int x, int y, int z) {
        Plugin protect = Bukkit.getPluginManager().getPlugin("SMP-Protect");
        if (protect == null || !protect.isEnabled()) {
            return false;
        }
        try {
            if (method == null || target != protect) {
                method = protect.getClass().getMethod("isProtectedSpot", String.class, int.class, int.class, int.class);
                target = protect;
            }
            return (boolean) method.invoke(protect, world, x, y, z);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
