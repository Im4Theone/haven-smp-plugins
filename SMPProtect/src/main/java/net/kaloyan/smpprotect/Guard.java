package net.kaloyan.smpprotect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kaloyan.smpprotect.RegionIndex.Verdict;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Answers "may this be done here?" for listeners, tells players why not, and honours the bypass permission. */
public final class Guard {

    /** Permission that ignores every region. */
    public static final String BYPASS = "smpprotect.bypass";

    private final RegionManager manager;
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private volatile String denyMessage = "<red>You can't do that here.";
    private volatile long cooldownMillis = 1500;

    /** @param manager the regions */
    public Guard(RegionManager manager) {
        this.manager = manager;
    }

    /**
     * @param message    MiniMessage text with {@code <region>} and {@code <flag>}
     * @param cooldownMs the least time between two messages to the same player
     */
    public void configure(String message, long cooldownMs) {
        this.denyMessage = message;
        this.cooldownMillis = cooldownMs;
    }

    /**
     * Checks a player's action.
     *
     * @param player who does it
     * @param at     where it happens
     * @param flag   what kind of action
     * @return {@code true} if it must be cancelled (the player has been told)
     */
    public boolean denies(Player player, Location at, Flag flag) {
        if (at.getWorld() == null || player.hasPermission(BYPASS)) {
            return false;
        }
        RegionIndex index = manager.index();
        String world = at.getWorld().getName();
        if (!index.hasRegions(world)) {
            return false;
        }
        Verdict verdict = index.query(world, at.getBlockX(), at.getBlockY(), at.getBlockZ(), flag, player.getUniqueId());
        if (verdict.allowed()) {
            return false;
        }
        tell(player, verdict.decidedBy(), flag);
        return true;
    }

    /**
     * Checks something nobody does: fire, explosions, mobs, liquids.
     *
     * @param at   where it happens
     * @param flag what kind of event
     * @return {@code true} if it must be cancelled
     */
    public boolean deniesEnvironment(Location at, Flag flag) {
        return at.getWorld() != null && deniesEnvironment(at.getWorld().getName(), at.getBlockX(), at.getBlockY(),
                at.getBlockZ(), flag);
    }

    /** @see #deniesEnvironment(Location, Flag) */
    public boolean deniesEnvironment(String world, int x, int y, int z, Flag flag) {
        RegionIndex index = manager.index();
        return index.hasRegions(world) && !index.query(world, x, y, z, flag, null).allowed();
    }

    private void tell(Player player, Region region, Flag flag) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            return;
        }
        lastMessage.put(player.getUniqueId(), now);
        player.sendActionBar(MiniMessage.miniMessage().deserialize(denyMessage,
                Placeholder.unparsed("region", region.name()), Placeholder.unparsed("flag", flag.id())));
    }

    /** Forgets a player's message timer (on quit). */
    public void forget(UUID player) {
        lastMessage.remove(player);
    }
}
