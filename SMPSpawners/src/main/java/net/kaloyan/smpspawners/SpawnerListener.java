package net.kaloyan.smpspawners;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Placing, stacking, breaking and protecting stacked spawners. */
public final class SpawnerListener implements Listener {

    private final Registry registry;
    private final Supplier<Settings> settings;
    private final SpawnerItems items;
    private final Actions actions;
    private final Menus menus;
    private final Texts texts;
    private final java.util.function.Predicate<ItemStack> silkTouch;
    /** Whether a held material is a block that can be placed (replaceable in tests: it needs the server's registries). */
    java.util.function.Predicate<Material> placeable = m -> m.isBlock() && !m.isAir();

    /**
     * @param registry the spawners
     * @param settings the current settings
     * @param items    recognises and makes spawner items
     * @param actions  hands out loot and experience
     * @param menus    the menus
     * @param texts    the messages
     */
    public SpawnerListener(Registry registry, Supplier<Settings> settings, SpawnerItems items, Actions actions, Menus menus, Texts texts) {
        this(registry, settings, items, actions, menus, texts, SpawnerListener::hasSilkTouch);
    }

    /** As above, with the silk touch check replaced (tests: the real one needs the server's enchantment registry). */
    SpawnerListener(Registry registry, Supplier<Settings> settings, SpawnerItems items, Actions actions, Menus menus, Texts texts,
                    java.util.function.Predicate<ItemStack> silkTouch) {
        this.silkTouch = silkTouch;
        this.registry = registry;
        this.settings = settings;
        this.items = items;
        this.actions = actions;
        this.menus = menus;
        this.texts = texts;
    }

    static Spawner.Pos pos(Block block) {
        return new Spawner.Pos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static void warn(Player player, String text) {
        player.sendActionBar(Component.text(text, NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- placing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String kind = items.kindOf(event.getItemInHand());
        if (kind == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!settings.get().kinds().containsKey(kind)) {
            event.setCancelled(true);
            texts.send(player, "kind-disabled", "kind", Texts.pretty(kind));
            return;
        }
        Block block = event.getBlockPlaced();
        try {
            registry.add(new Spawner(pos(block), kind, 1));
        } catch (IOException e) {
            event.setCancelled(true);
            texts.send(player, "save-failed");
            return;
        }
        if (block.getState() instanceof CreatureSpawner live) {
            live.setSpawnedType(EntityType.valueOf(kind));
            live.update(true, false);
        }
    }

    // ---------------------------------------------------------------- right click: stack or open the menu

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || block.getType() != Material.SPAWNER) {
            return;
        }
        Spawner spawner = registry.at(pos(block));
        if (spawner == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        String held = items.kindOf(hand);
        if (held == null && player.isSneaking() && placeable.test(hand.getType())) {
            return;     // sneak-placing a block against the spawner works as usual
        }
        event.setUseInteractedBlock(Event.Result.DENY);     // no spawn eggs changing it, no vanilla interaction
        event.setUseItemInHand(Event.Result.DENY);
        if (held == null) {
            menus.openMain(player, spawner);
            return;
        }
        stack(player, spawner, hand, held);
    }

    private void stack(Player player, Spawner spawner, ItemStack hand, String held) {
        if (!held.equals(spawner.kind())) {
            warn(player, "That is a different creature's spawner.");
            return;
        }
        int room = settings.get().maxStack() - spawner.stack();
        if (room <= 0) {
            warn(player, "This spawner is full (" + settings.get().maxStack() + " stacked).");
            return;
        }
        int wanted = player.isSneaking() ? hand.getAmount() : 1;
        int n = Math.min(wanted, room);
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!creative) {
            hand.setAmount(hand.getAmount() - n);       // the items go first: a crash can lose them but never duplicate them
        }
        int before = spawner.stack();
        spawner.stack(before + n);
        try {
            registry.commit(() -> spawner.stack(before));
        } catch (IOException e) {
            if (!creative) {
                hand.setAmount(hand.getAmount() + n);
            }
            texts.send(player, "save-failed");
            return;
        }
        player.sendActionBar(Component.text("Stacked " + n + " (" + spawner.stack() + " total)", NamedTextColor.GREEN));
    }

    // ---------------------------------------------------------------- breaking

    private static boolean hasSilkTouch(ItemStack tool) {
        Enchantment silk = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft("silk_touch"));
        return silk != null && tool != null && tool.containsEnchantment(silk);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        Spawner spawner = registry.at(pos(block));
        if (spawner == null) {
            return;
        }
        event.setCancelled(true);       // we remove the spawner ourselves, one stacked spawner at a time
        Player player = event.getPlayer();
        if (settings.get().requireSilk() && !silkTouch.test(player.getInventory().getItemInMainHand())) {
            warn(player, "You need a silk touch pickaxe to pick up spawners!");
            return;
        }
        int n = player.isSneaking() ? Math.min(settings.get().sneakBatch(), spawner.stack()) : 1;
        n = Math.min(n, spawner.stack());
        takeOut(player, spawner, block, n);
    }

    /** Removes {@code n} spawners from the stack and gives them to the player; the last one also pays out the stored loot and experience. */
    private void takeOut(Player player, Spawner spawner, Block block, int n) {
        boolean last = n >= spawner.stack();
        Map<String, Long> loot = null;
        long xp = 0;
        int before = spawner.stack();
        try {
            if (last) {
                if (!registry.remove(spawner.pos())) {
                    return;
                }
                loot = spawner.takeAllLoot();
                xp = spawner.xp();
                // the removal was saved with the loot still inside; save again without it is unnecessary, the spawner is gone
                block.setType(Material.AIR);
            } else {
                spawner.stack(before - n);
                registry.commit(() -> spawner.stack(before));
            }
        } catch (IOException e) {
            texts.send(player, "save-failed");
            return;
        }
        int max = 64;
        int left = n;
        while (left > 0) {
            int part = Math.min(left, max);
            for (ItemStack rest : player.getInventory().addItem(items.create(spawner.kind(), part)).values()) {
                player.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), rest);
            }
            left -= part;
        }
        if (last) {
            actions.payOut(player, loot, xp);
        }
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (event.getSpawner() != null && registry.at(pos(event.getSpawner().getBlock())) != null) {
            event.setCancelled(true);      // stacked spawners produce stored loot instead of mobs
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (settings.get().protectBlocks()) {
            event.blockList().removeIf(b -> b.getType() == Material.SPAWNER && registry.at(pos(b)) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (settings.get().protectBlocks()) {
            event.blockList().removeIf(b -> b.getType() == Material.SPAWNER && registry.at(pos(b)) != null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (settings.get().protectBlocks() && event.getBlocks().stream().anyMatch(b -> registry.at(pos(b)) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (settings.get().protectBlocks() && event.getBlocks().stream().anyMatch(b -> registry.at(pos(b)) != null)) {
            event.setCancelled(true);
        }
    }
}
