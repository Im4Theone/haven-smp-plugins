package net.kaloyan.smpscoreboard;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Asks SMP-Catch (if it is installed) which players are vanished, so staff in vanish are not counted or marked as online. */
final class Visible {

    private Visible() {
    }

    private static Plugin catcher() {
        Plugin p = Bukkit.getPluginManager().getPlugin("SMP-Catch");
        return p != null && p.isEnabled() ? p : null;
    }

    /** @return how many players are online and not vanished */
    static int online() {
        Plugin p = catcher();
        if (p != null) {
            try {
                return (int) p.getClass().getMethod("visibleOnlineCount").invoke(p);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through to the plain count
            }
        }
        return Bukkit.getOnlinePlayers().size();
    }

    /** @return whether the player is vanished */
    static boolean vanished(UUID id) {
        Plugin p = catcher();
        if (p != null) {
            try {
                return (boolean) p.getClass().getMethod("isVanished", UUID.class).invoke(p, id);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
