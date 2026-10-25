package net.kaloyan.smpshop;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Hands the goods to the player: items directly, crates through SMP-Crates, spawners through SMP-Spawners. */
public interface Deliveries {

    /**
     * @param player who gets them
     * @param item   the item
     * @param amount how many
     * @return how many did NOT fit
     */
    int items(Player player, Material item, int amount);

    /**
     * @param player the player
     * @param crate  the crate id
     * @param amount how many
     * @return whether SMP-Crates saved the crates to the player
     */
    boolean crates(UUID player, String crate, int amount);

    /**
     * @param player   the player
     * @param creature the creature
     * @param amount   how many
     * @return whether SMP-Spawners handed over the spawners
     */
    boolean spawners(UUID player, String creature, int amount);

    /** @return the implementation that talks to the other plugins */
    static Deliveries bukkit() {
        return new Deliveries() {
            @Override
            public int items(Player player, Material item, int amount) {
                int left = 0;
                for (ItemStack rest : player.getInventory().addItem(new ItemStack(item, amount)).values()) {
                    left += rest.getAmount();
                }
                return left;
            }

            @Override
            public boolean crates(UUID player, String crate, int amount) {
                return call("SMP-Crates", "giveCrates", player, crate, amount);
            }

            @Override
            public boolean spawners(UUID player, String creature, int amount) {
                return call("SMP-Spawners", "giveSpawners", player, creature, amount);
            }

            private boolean call(String plugin, String method, UUID player, String what, int amount) {
                Plugin target = Bukkit.getPluginManager().getPlugin(plugin);
                if (target == null || !target.isEnabled()) {
                    return false;
                }
                try {
                    return (boolean) target.getClass().getMethod(method, UUID.class, String.class, int.class)
                            .invoke(target, player, what, amount);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return false;
                }
            }
        };
    }
}
