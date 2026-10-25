package net.kaloyan.smpcatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Tells staff about a suspicious thing, keeps the player's points and runs the actions chosen for point levels. */
public final class Alerts {

    /** The permission staff need to receive alerts. */
    public static final String PERMISSION = "smpcatch.alerts";

    private final Supplier<Settings> settings;
    private final Suspects suspects;
    private final Path logFile;
    private final Logger log;
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final Set<UUID> muted = new HashSet<>();

    /**
     * @param settings the current settings
     * @param suspects the points
     * @param logFile  where alerts are appended
     * @param log      the plugin logger
     */
    public Alerts(Supplier<Settings> settings, Suspects suspects, Path logFile, Logger log) {
        this.settings = settings;
        this.suspects = suspects;
        this.logFile = logFile;
        this.log = log;
    }

    /**
     * Switches a staff member's alerts on or off.
     *
     * @param id the staff member
     * @return {@code true} if alerts are on now
     */
    public boolean toggle(UUID id) {
        if (muted.remove(id)) {
            return true;
        }
        muted.add(id);
        return false;
    }

    /**
     * Records something a player did and tells staff.
     *
     * @param culprit the player
     * @param kind    a short key; the same kind for the same player is not alerted twice within the cooldown (the points still count)
     * @param what    what happened, for staff to read
     * @param points  points to add
     * @param where   where it happened
     */
    public void raise(Player culprit, String kind, String what, double points, Location where) {
        Settings s = settings.get();
        long now = System.currentTimeMillis();
        List<Double> levels = s.thresholds();
        List<Integer> crossed = suspects.add(culprit.getUniqueId(), culprit.getName(), now, points, what, levels);
        for (int i : crossed) {
            for (String command : s.commands().get(i)) {
                String line = command.replace("%player%", culprit.getName());
                log.warning("Point level " + levels.get(i) + " reached by " + culprit.getName() + ": running '" + line + "'");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
            }
        }
        String key = culprit.getUniqueId() + ":" + kind;
        Long last = lastAlert.get(key);
        if (last != null && now - last < s.cooldownMillis() && crossed.isEmpty()) {
            return;
        }
        lastAlert.put(key, now);
        double total = suspects.points(culprit.getUniqueId(), now);
        TagResolver values = TagResolver.resolver(Placeholder.unparsed("player", culprit.getName()), Placeholder.unparsed("what", what),
                Placeholder.unparsed("points", String.format("%.1f", total)), Placeholder.unparsed("world", where.getWorld().getName()),
                Placeholder.unparsed("x", Integer.toString(where.getBlockX())), Placeholder.unparsed("y", Integer.toString(where.getBlockY())),
                Placeholder.unparsed("z", Integer.toString(where.getBlockZ())));
        MiniMessage mini = MiniMessage.miniMessage();
        Component line = mini.deserialize(s.message("alert"), values)
                .hoverEvent(HoverEvent.showText(mini.deserialize(s.message("alert-location"), values)))
                .clickEvent(ClickEvent.suggestCommand("/tp " + culprit.getName()));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(PERMISSION) && !muted.contains(staff.getUniqueId())) {
                staff.sendMessage(line);
            }
        }
        String plain = culprit.getName() + ": " + what + " [" + String.format("%.1f", total) + " points] at " + where.getWorld().getName() + " "
                + where.getBlockX() + " " + where.getBlockY() + " " + where.getBlockZ();
        if (s.consoleAlerts()) {
            log.warning(plain);
        }
        if (s.logFile()) {
            try {
                Files.writeString(logFile, Instant.now() + " " + plain + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warning("Could not write alerts.log: " + e.getMessage());
            }
        }
    }
}
