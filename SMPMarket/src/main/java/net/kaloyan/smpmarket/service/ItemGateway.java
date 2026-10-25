package net.kaloyan.smpmarket.service;

import java.util.Optional;
import java.util.UUID;
import net.kaloyan.smpmarket.model.ItemSnapshot;

/**
 * The only door from the market logic to Bukkit players and inventories. <strong>Every method must be called on
 * the server main thread</strong>; {@link MarketService} guarantees that. Keeping this a small interface lets the
 * transaction logic be tested without a server and keeps all inventory access in one reviewed place
 * ({@code BukkitItemGateway}).
 */
public interface ItemGateway {

    /** Result of {@link #give}. */
    enum GiveResult {
        /** The complete stack is now in the player's inventory. */
        DELIVERED,
        /** The inventory cannot hold the complete stack; nothing was added. */
        NO_ROOM,
        /** The player is not online; nothing was added. */
        OFFLINE,
        /** The stored item cannot be restored on this server; nothing was added. */
        INVALID_ITEM
    }

    /**
     * Reads the item in the player's main hand without changing anything.
     *
     * @param player the player's UUID
     * @return the exact stack, or empty if the hand is empty or the player is offline
     */
    Optional<ItemSnapshot> readMainHand(UUID player);

    /**
     * Removes exactly the stack described by {@code payload} from the player's inventory (the main hand first). Either
     * the whole stack is removed and {@code true} is returned, or nothing changes and {@code false} is returned.
     *
     * @param player  the player's UUID
     * @param payload serialized stack as captured by {@link #readMainHand}
     * @return whether the stack was found and removed
     */
    boolean removeExact(UUID player, byte[] payload);

    /**
     * Puts the complete stack into the player's inventory, all or nothing.
     *
     * @param player  the player's UUID
     * @param payload serialized stack
     * @return what happened
     */
    GiveResult give(UUID player, byte[] payload);

    /**
     * Tells whether the player is online.
     *
     * @param player the player's UUID
     * @return {@code true} if online
     */
    boolean isOnline(UUID player);

    /**
     * Sends a chat message if the player is online; does nothing otherwise.
     *
     * @param player  the player's UUID
     * @param message plain text
     */
    void notifyPlayer(UUID player, String message);
}
