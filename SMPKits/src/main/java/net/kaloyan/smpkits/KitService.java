package net.kaloyan.smpkits;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Hands out kits. Nothing is claimed unless everything fits; the claim is saved before the items are given. */
public final class KitService {

    /** How a claim ended. */
    public enum Outcome {
        /** The kit was given. */
        OK,
        /** The player may not use this kit. */
        NO_PERMISSION,
        /** The player's rank gets no kits. */
        EXCLUDED,
        /** The kit is on cooldown. */
        COOLDOWN,
        /** The kit was claimed before and can be claimed once only. */
        USED,
        /** The items do not fit. */
        NO_SPACE,
        /** Saving failed; nothing was given. */
        SAVE_FAILED
    }

    /**
     * @param outcome   how it ended
     * @param remaining for {@code COOLDOWN}, the milliseconds left
     * @param needed    for {@code NO_SPACE}, how many more stacks need room
     */
    public record Result(Outcome outcome, long remaining, int needed) {
        static Result of(Outcome outcome) {
            return new Result(outcome, 0, 0);
        }
    }

    private final Supplier<Settings> settings;
    private final Claims claims;
    private final Logger log;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * @param settings the current settings
     * @param claims   the claims
     * @param log      the plugin logger
     */
    public KitService(Supplier<Settings> settings, Claims claims, Logger log) {
        this.settings = settings;
        this.claims = claims;
        this.log = log;
    }

    /** @return the claims */
    public Claims claims() {
        return claims;
    }

    /** @return whether the player's main rank (LuckPerms primary group) is one that gets no kits */
    public boolean excluded(Player player) {
        RegisteredServiceProvider<Permission> provider = Bukkit.getServicesManager().getRegistration(Permission.class);
        if (provider == null) {
            return false;
        }
        String group = provider.getProvider().getPrimaryGroup(player);
        return group != null && settings.get().excludedGroups().contains(group.toLowerCase(Locale.ROOT));
    }

    /**
     * @param player who
     * @param kit    the kit
     * @param now    the time
     * @return what would happen on a claim, without claiming (space is not checked)
     */
    public Result availability(Player player, Kit kit, long now) {
        if (excluded(player)) {
            return Result.of(Outcome.EXCLUDED);
        }
        if (!player.hasPermission(kit.permission())) {
            return Result.of(Outcome.NO_PERMISSION);
        }
        Claims.Status status = claims.status(player.getUniqueId(), kit, now);
        return switch (status.state()) {
            case USED -> Result.of(Outcome.USED);
            case COOLDOWN -> new Result(Outcome.COOLDOWN, status.remaining(), 0);
            default -> Result.of(Outcome.OK);
        };
    }

    /**
     * Makes the items of a kit.
     *
     * @param kit the kit
     * @return new stacks
     */
    public List<ItemStack> items(Kit kit) {
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        List<ItemStack> out = new ArrayList<>();
        for (Kit.Spec spec : kit.items()) {
            ItemStack stack = new ItemStack(spec.material(), spec.amount());
            ItemMeta meta = stack.getItemMeta();
            if (spec.name() != null) {
                meta.displayName(mini.deserialize(spec.name()).decoration(TextDecoration.ITALIC, false));
            }
            if (!spec.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                spec.lore().forEach(line -> lore.add(mini.deserialize(line).decoration(TextDecoration.ITALIC, false)));
                meta.lore(lore);
            }
            spec.enchants().forEach((name, level) -> {
                Enchantment enchantment = registry.get(NamespacedKey.minecraft(name));
                if (enchantment == null) {
                    log.warning("Kit " + kit.id() + ": unknown enchantment '" + name + "'");
                } else {
                    meta.addEnchant(enchantment, level, true);
                }
            });
            stack.setItemMeta(meta);
            out.add(stack);
        }
        return out;
    }

    /** @return how many of the stacks do not fit into the player's inventory */
    private int missingRoom(Player player, List<ItemStack> stacks) {
        Inventory scratch = Bukkit.createInventory(null, 36);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < 36 && i < storage.length; i++) {
            scratch.setItem(i, storage[i] == null ? null : storage[i].clone());
        }
        int left = 0;
        for (ItemStack stack : stacks) {
            left += scratch.addItem(stack.clone()).size();
        }
        return left;
    }

    /** Gives the items and runs the commands, without any checks. */
    public void give(Player player, Kit kit) {
        for (ItemStack stack : items(kit)) {
            for (ItemStack rest : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        for (String command : kit.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
    }

    /**
     * Claims a kit for a player.
     *
     * @param player who
     * @param kit    the kit
     * @param now    the time
     * @return how it ended
     */
    public Result claim(Player player, Kit kit, long now) {
        Result check = availability(player, kit, now);
        if (check.outcome() != Outcome.OK) {
            return check;
        }
        List<ItemStack> stacks = items(kit);
        int missing = missingRoom(player, stacks);
        if (missing > 0) {
            return new Result(Outcome.NO_SPACE, 0, missing);
        }
        try {
            claims.claim(player.getUniqueId(), kit.id(), now);
        } catch (IOException e) {
            log.severe("Could not save claims.yml: " + e.getMessage());
            return Result.of(Outcome.SAVE_FAILED);
        }
        give(player, kit);
        return Result.of(Outcome.OK);
    }
}
