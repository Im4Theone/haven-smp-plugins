package net.kaloyan.smpcrates;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Hands rewards to online players: items into the inventory, money through Vault (your SMP-Economy), shards through
 * DonutShop, commands through the console.
 */
public final class BukkitDelivery implements CrateService.Delivery {

    private final Plugin plugin;
    private final Items items;

    /**
     * @param plugin the owning plugin
     * @param items  builds the reward items
     */
    public BukkitDelivery(Plugin plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }

    private Object shardManager() {
        Plugin shop = Bukkit.getPluginManager().getPlugin("SMP-Shop");
        if (shop == null) {
            shop = Bukkit.getPluginManager().getPlugin("DonutShop");
        }
        if (shop == null || !shop.isEnabled()) {
            return null;
        }
        try {
            return shop.getClass().getMethod("getShardManager").invoke(shop);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean canDeliver(UUID id, Reward reward) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return false;
        }
        return switch (reward.kind()) {
            case ITEM -> {
                Inventory test = Bukkit.createInventory(null, 36);
                test.setContents(player.getInventory().getStorageContents());
                yield test.addItem(items.stacksOf(reward).toArray(ItemStack[]::new)).isEmpty();
            }
            case MONEY -> economy() != null;
            case SHARDS -> shardManager() != null;
            case COMMAND -> true;
        };
    }

    @Override
    public boolean deliver(UUID id, Reward reward) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            return false;
        }
        switch (reward.kind()) {
            case ITEM -> {
                List<ItemStack> stacks = items.stacksOf(reward);
                for (ItemStack left : player.getInventory().addItem(stacks.toArray(ItemStack[]::new)).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left); // never lose an item
                }
                return true;
            }
            case MONEY -> {
                Economy economy = economy();
                return economy != null && economy.depositPlayer(player, reward.amount()).transactionSuccess();
            }
            case SHARDS -> {
                Object manager = shardManager();
                if (manager == null) {
                    return false;
                }
                try {
                    Method add = manager.getClass().getMethod("add", UUID.class, long.class);
                    add.invoke(manager, id, reward.amount());
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not give shards through DonutShop", e);
                    return false;
                }
            }
            case COMMAND -> {
                return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.command().replace("%player%", player.getName()));
            }
            default -> {
                return false;
            }
        }
    }
}
