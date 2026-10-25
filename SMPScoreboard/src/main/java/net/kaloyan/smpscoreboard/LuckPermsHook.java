package net.kaloyan.smpscoreboard;

import java.util.Optional;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * LuckPerms access. Only loaded when LuckPerms is installed, so the rest of the plugin works without it. Reads the
 * data LuckPerms already keeps in memory for online players; it never touches storage.
 */
public final class LuckPermsHook {

    private final LuckPerms api;

    private LuckPermsHook(LuckPerms api) {
        this.api = api;
    }

    /**
     * @return the hook, or empty if LuckPerms is not running
     */
    public static Optional<LuckPermsHook> create() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return Optional.empty();
        }
        LuckPerms api = Bukkit.getServicesManager().load(LuckPerms.class);
        return api == null ? Optional.empty() : Optional.of(new LuckPermsHook(api));
    }

    /**
     * @param player an online player
     * @return the player's highest-priority prefix with {@code &} colour codes, or {@code null}
     */
    public String prefix(Player player) {
        return api.getPlayerAdapter(Player.class).getMetaData(player).getPrefix();
    }

    /**
     * @param player an online player
     * @return the player's primary group name, or an empty string if unknown
     */
    public String group(Player player) {
        User user = api.getPlayerAdapter(Player.class).getUser(player);
        return user == null ? "" : user.getPrimaryGroup();
    }

    /**
     * @param player an online player
     * @return the weight of the player's primary group (higher = more senior), or 0 if unknown
     */
    public int weight(Player player) {
        String name = group(player);
        net.luckperms.api.model.group.Group group = name.isEmpty() ? null : api.getGroupManager().getGroup(name);
        return group == null ? 0 : group.getWeight().orElse(0);
    }
}
