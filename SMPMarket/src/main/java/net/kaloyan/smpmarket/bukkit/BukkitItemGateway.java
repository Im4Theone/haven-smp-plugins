package net.kaloyan.smpmarket.bukkit;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kaloyan.smpmarket.model.ItemSnapshot;
import net.kaloyan.smpmarket.service.ItemGateway;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Bukkit implementation of {@link ItemGateway}. Every method must be called on the server main thread
 * ({@code MarketService} guarantees it). This class is the only place that touches player inventories.
 *
 * <p>Items are stored with {@link ItemStack#serializeAsBytes()}, which captures the complete stack including all
 * item data components (custom name, lore, enchantments, persistent data, nested container contents), and restored
 * with {@link ItemStack#deserializeBytes(byte[])}, which also upgrades data written by an older game version.
 *
 * <p>This adapter needs a running server and is not covered by the automated tests; see the README smoke tests.
 */
public final class BukkitItemGateway implements ItemGateway {

    private static final int MAX_DESCRIPTION = 120;
    private static final int INVENTORY_SLOTS = 41; // 36 storage + 4 armour + off hand

    private final Server server;
    private final Logger logger;

    /**
     * @param server the server
     * @param logger console logger
     */
    public BukkitItemGateway(Server server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Override
    public Optional<ItemSnapshot> readMainHand(UUID playerId) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            return Optional.empty();
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ItemSnapshot(hand.serializeAsBytes(), hand.getType().getKey().toString(),
                hand.getAmount(), describe(hand)));
    }

    @Override
    public boolean removeExact(UUID playerId, byte[] payload) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            return false;
        }
        ItemStack expected;
        try {
            expected = ItemStack.deserializeBytes(payload);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not restore the item that is being listed", e);
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        if (expected.equals(inventory.getItemInMainHand())) {
            inventory.setItemInMainHand(null);
            return true;
        }
        // The hand changed since the command ran; accept an identical stack elsewhere, never a similar one.
        for (int slot = 0; slot < INVENTORY_SLOTS; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate != null && expected.equals(candidate)) {
                inventory.setItem(slot, null);
                return true;
            }
        }
        return false;
    }

    @Override
    public GiveResult give(UUID playerId, byte[] payload) {
        Player player = server.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return GiveResult.OFFLINE;
        }
        ItemStack stack;
        try {
            stack = ItemStack.deserializeBytes(payload);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Could not restore a stored item", e);
            return GiveResult.INVALID_ITEM;
        }
        PlayerInventory inventory = player.getInventory();
        if (roomFor(inventory.getStorageContents(), stack) < stack.getAmount()) {
            return GiveResult.NO_ROOM;
        }
        Map<Integer, ItemStack> leftover = inventory.addItem(stack.clone());
        if (!leftover.isEmpty()) {
            // The room check and addItem disagree. Undo the part that was added so the outcome is all-or-nothing.
            int left = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int added = stack.getAmount() - left;
            if (added > 0) {
                inventory.removeItem(stack.asQuantity(added));
            }
            logger.warning("Inventory room check disagreed with addItem for " + playerId + "; delivery rolled back.");
            return GiveResult.NO_ROOM;
        }
        return GiveResult.DELIVERED;
    }

    /** How many items of {@code stack} the storage slots can still take. */
    private static int roomFor(ItemStack[] storage, ItemStack stack) {
        int room = 0;
        for (ItemStack slot : storage) {
            if (slot == null || slot.isEmpty()) {
                room += stack.getMaxStackSize();
            } else if (slot.isSimilar(stack)) {
                room += Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            }
        }
        return room;
    }

    @Override
    public boolean isOnline(UUID playerId) {
        Player player = server.getPlayer(playerId);
        return player != null && player.isOnline();
    }

    @Override
    public void notifyPlayer(UUID playerId, String message) {
        Player player = server.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text("[Market] ", NamedTextColor.DARK_AQUA)
                    .append(Component.text(message, NamedTextColor.GRAY)));
        }
    }

    /** Short plain-text summary: custom name and enchantments. Never parsed as formatting. */
    static String describe(ItemStack stack) {
        StringBuilder text = new StringBuilder();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            text.append('"').append(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).append('"');
        }
        Map<Enchantment, Integer> enchants = meta instanceof EnchantmentStorageMeta storage
                ? storage.getStoredEnchants() : stack.getEnchantments();
        for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(e.getKey().getKey().getKey()).append(' ').append(e.getValue());
        }
        String clean = text.toString().chars().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return clean.length() > MAX_DESCRIPTION ? clean.substring(0, MAX_DESCRIPTION - 1) + "…" : clean;
    }
}
