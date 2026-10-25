package net.kaloyan.smpspawners;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What a player can do with a spawner's contents: sell it, collect the experience, take items out. Every action first changes the
 * spawner and saves, and only then hands anything to the player; if saving fails the change is undone and nothing is given.
 */
public final class Actions {

    private final Registry registry;
    private final Supplier<Settings> settings;
    private final Texts texts;
    private final Payments payments;
    private final Logger log;

    /**
     * @param registry the spawners
     * @param settings the current settings
     * @param texts    the messages
     * @param payments pays money
     * @param log      for serious problems
     */
    public Actions(Registry registry, Supplier<Settings> settings, Texts texts, Payments payments, Logger log) {
        this.registry = registry;
        this.settings = settings;
        this.texts = texts;
        this.payments = payments;
        this.log = log;
    }

    private static void restore(Spawner spawner, Map<String, Long> items) {
        items.forEach(spawner::addLoot);
    }

    /**
     * Sells every stored item that has a price. Items without a price stay in the spawner.
     *
     * @param player  who is paid
     * @param spawner the spawner
     * @return {@code true} if something was sold
     */
    public boolean sellAll(Player player, Spawner spawner) {
        Settings s = settings.get();
        Map<String, Long> sold = new TreeMap<>();
        long cents = 0;
        for (Map.Entry<String, Long> e : spawner.loot().entrySet()) {
            long price = s.pricesCents().getOrDefault(e.getKey(), 0L);
            if (price > 0 && e.getValue() > 0) {
                sold.put(e.getKey(), e.getValue());
                cents = Math.addExact(cents, Math.multiplyExact(price, e.getValue()));
            }
        }
        if (cents <= 0) {
            texts.send(player, "nothing-to-sell");
            return false;
        }
        if (!payments.available()) {
            texts.send(player, "no-economy");
            return false;
        }
        sold.forEach(spawner::takeLoot);
        try {
            registry.commit(() -> restore(spawner, sold));
        } catch (IOException e) {
            texts.send(player, "save-failed");
            return false;
        }
        Payments.Result result = payments.deposit(player, cents);
        if (result == Payments.Result.PAID) {
            texts.send(player, "sold", "items", sold.values().stream().mapToLong(Long::longValue).sum(), "money", Texts.money(cents));
            return true;
        }
        if (result == Payments.Result.UNCERTAIN) {
            // The money may have been paid: giving the items back could pay twice. The items stay gone and a human checks.
            log.severe("Payment of " + Texts.money(cents) + " to " + player.getName() + " for " + sold + " could not be confirmed; the items"
                    + " were NOT given back. Check the player's balance.");
            texts.send(player, "payment-unconfirmed");
            return true;
        }
        restore(spawner, sold);
        try {
            registry.commit(() -> sold.forEach(spawner::takeLoot));
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not put " + sold + " back into the spawner of " + player.getName() + " after a failed payment", e);
        }
        texts.send(player, "payment-failed");
        return false;
    }

    /**
     * Gives the player the experience stored in the spawner.
     *
     * @param player  who gets it
     * @param spawner the spawner
     */
    public void collectXp(Player player, Spawner spawner) {
        long points = spawner.xp();
        if (points <= 0) {
            texts.send(player, "no-xp");
            return;
        }
        spawner.xp(0);
        try {
            registry.commit(() -> spawner.xp(points));
        } catch (IOException e) {
            texts.send(player, "save-failed");
            return;
        }
        long left = points;
        while (left > 0) {
            int part = (int) Math.min(left, Integer.MAX_VALUE);
            player.giveExp(part);
            left -= part;
        }
        texts.send(player, "xp-collected", "xp", points);
    }

    /** @return how many of this item fit into the player's inventory right now */
    static long room(PlayerInventory inventory, Material material) {
        long room = 0;
        int max = material.getMaxStackSize();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                room += max;
            } else if (stack.getType() == material && !stack.hasItemMeta()) {
                room += Math.max(0, max - stack.getAmount());
            }
        }
        return room;
    }

    /**
     * Moves stored items into the player's inventory.
     *
     * @param player   who gets them
     * @param spawner  the spawner
     * @param material the item's name in capitals
     * @param all      {@code true} for everything that fits, {@code false} for one stack
     */
    public void collectItem(Player player, Spawner spawner, String material, boolean all) {
        Material type = Material.getMaterial(material);
        long have = spawner.loot().getOrDefault(material, 0L);
        if (type == null || have <= 0) {
            return;
        }
        long wanted = all ? have : Math.min(have, type.getMaxStackSize());
        long fits = Math.min(wanted, room(player.getInventory(), type));
        if (fits <= 0) {
            texts.send(player, "inventory-full");
            return;
        }
        long taken = spawner.takeLoot(material, fits);
        try {
            registry.commit(() -> spawner.addLoot(material, taken));
        } catch (IOException e) {
            texts.send(player, "save-failed");
            return;
        }
        giveItems(player, type, taken);
    }

    /** Puts items into the inventory; anything that does not fit lands at the player's feet, so nothing is ever lost. */
    static void giveItems(Player player, Material type, long amount) {
        long left = amount;
        while (left > 0) {
            int n = (int) Math.min(left, type.getMaxStackSize());
            Map<Integer, ItemStack> rest = player.getInventory().addItem(new ItemStack(type, n));
            for (ItemStack r : rest.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), r);
            }
            left -= n;
        }
    }

    /**
     * Hands out the stored items and experience of a spawner that is being removed. Everything is given; what does not fit in the
     * inventory is dropped.
     *
     * @param player  who gets it
     * @param loot    the stored items
     * @param xp      the stored experience
     */
    public void payOut(Player player, Map<String, Long> loot, long xp) {
        Map<String, Long> given = new HashMap<>(loot);
        given.forEach((item, amount) -> {
            Material type = Material.getMaterial(item);
            if (type != null && amount > 0) {
                giveItems(player, type, amount);
            }
        });
        long left = xp;
        while (left > 0) {
            int part = (int) Math.min(left, Integer.MAX_VALUE);
            player.giveExp(part);
            left -= part;
        }
    }
}
