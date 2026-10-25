package net.kaloyan.smpauth;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** Until a player has logged in they can do nothing: not move, build, talk, open anything, use commands (except login and register), or be hurt. */
public final class Guard implements Listener {

    /** The commands a player who is not logged in may use. */
    private static final List<String> ALLOWED = List.of("login", "l", "register", "reg");

    private final Predicate<Player> locked;
    private final java.util.function.Consumer<Player> remind;

    /**
     * @param locked tells whether a player is not logged in
     * @param remind tells such a player (at most now and then) what to do
     */
    public Guard(Predicate<Player> locked, java.util.function.Consumer<Player> remind) {
        this.locked = locked;
        this.remind = remind;
    }

    /**
     * @param typed the first word after the slash
     * @return whether somebody who is not logged in may use this command (login, register and their aliases, also with a plugin prefix)
     */
    public static boolean allowedBeforeLogin(String typed) {
        String word = typed.toLowerCase(Locale.ROOT);
        int colon = word.indexOf(':');
        if (colon >= 0) {
            word = word.substring(colon + 1);
        }
        return ALLOWED.contains(word);
    }

    private boolean stop(Player player, org.bukkit.event.Cancellable event) {
        if (locked.test(player)) {
            event.setCancelled(true);
            remind.accept(player);
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!locked.test(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        var from = event.getFrom();
        var to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            var back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (locked.test(event.getPlayer())) {
            event.setCancelled(true);       // a password typed into the chat must never be shown to anybody
            remind.accept(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!locked.test(event.getPlayer())) {
            return;
        }
        String first = event.getMessage().substring(1).split("\\s", 2)[0];
        if (!allowedBeforeLogin(first)) {
            event.setCancelled(true);
            remind.accept(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        stop(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && locked.test(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player p) {
            stop(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            stop(p, event);
        }
    }

    /** Nobody can hurt a player who has not logged in (and they cannot hurt anybody). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && locked.test(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            stop(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && locked.test(p)) {
            event.setCancelled(true);
        }
    }
}
