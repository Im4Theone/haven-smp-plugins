package net.kaloyan.smpprotect;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Shows a region's greeting when a player enters it and its farewell when they leave. */
public final class MessageListener implements Listener {

    private final RegionManager manager;
    private volatile String display = "actionbar";

    /** @param manager the regions */
    public MessageListener(RegionManager manager) {
        this.manager = manager;
    }

    /** @param display {@code actionbar}, {@code title} or {@code chat} */
    public void display(String display) {
        this.display = display;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld().equals(to.getWorld()))) {
            return;
        }
        crossing(event.getPlayer(), from, to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            crossing(event.getPlayer(), event.getFrom(), event.getTo());
        }
    }

    private void crossing(Player player, Location from, Location to) {
        RegionIndex index = manager.index();
        boolean fromWorld = index.hasRegions(from.getWorld().getName());
        boolean toWorld = index.hasRegions(to.getWorld().getName());
        if (!fromWorld && !toWorld) {
            return;
        }
        Set<String> before = names(index.regionsAt(from.getWorld().getName(), from.getBlockX(), from.getBlockY(), from.getBlockZ()));
        List<Region> after = index.regionsAt(to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        Set<String> afterNames = names(after);
        if (before.equals(afterNames)) {
            return;
        }
        for (String left : before) {
            if (!afterNames.contains(left)) {
                manager.get(left).ifPresent(r -> show(player, r, r.farewell()));
            }
        }
        for (Region entered : after) {
            if (!before.contains(entered.name())) {
                show(player, entered, entered.greeting());
            }
        }
    }

    private static Set<String> names(List<Region> regions) {
        Set<String> out = new HashSet<>();
        regions.forEach(r -> out.add(r.name()));
        return out;
    }

    private void show(Player player, Region region, String text) {
        if (text.isEmpty()) {
            return;
        }
        Component message = MiniMessage.miniMessage().deserialize(text, Placeholder.unparsed("region", region.name()),
                Placeholder.unparsed("player", player.getName()));
        switch (display) {
            case "title" -> player.showTitle(Title.title(message, Component.empty()));
            case "chat" -> player.sendMessage(message);
            default -> player.sendActionBar(message);
        }
    }
}
