package net.kaloyan.smpshop;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Asks SMP-Social (if it is installed) whether a player is away; away players earn no shards and no playing time. */
final class Afk {

    private static Method method;

    private Afk() {
    }

    /** @return whether the player is away */
    static boolean is(UUID id) {
        Plugin social = Bukkit.getPluginManager().getPlugin("SMP-Social");
        if (social == null || !social.isEnabled()) {
            return false;
        }
        try {
            if (method == null) {
                method = social.getClass().getMethod("isAfk", UUID.class);
            }
            return (boolean) method.invoke(social, id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
