package net.kaloyan.smpprotect;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageListenerTest {

    @TempDir
    Path dir;

    private World hub;
    private Player player;
    private MessageListener listener;

    @BeforeEach
    void setUp() throws IOException {
        hub = mock(World.class);
        when(hub.getName()).thenReturn("hub");
        RegionManager manager = new RegionManager(new RegionStore(dir.resolve("regions.yml")));
        manager.put(Region.of("plaza", "hub", 0, 0, 0, 10, 10, 10).withGreeting("<gold>Welcome to <region>")
                .withFarewell("<gray>Bye <player>"));
        listener = new MessageListener(manager);
        player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
    }

    private void move(int fx, int tx) {
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(new Location(hub, fx, 5, 5));
        when(event.getTo()).thenReturn(new Location(hub, tx, 5, 5));
        listener.onMove(event);
    }

    @Test
    void enteringShowsTheGreetingLeavingShowsTheFarewellAndMovingInsideShowsNothing() {
        move(-1, 0);
        verify(player, times(1)).sendActionBar(any(Component.class));
        move(0, 1);
        move(5, 6);
        verify(player, times(1)).sendActionBar(any(Component.class));
        move(10, 11);
        verify(player, times(2)).sendActionBar(any(Component.class));
        move(20, 21);
        verify(player, times(2)).sendActionBar(any(Component.class));
    }

    @Test
    void subBlockMovementIsIgnored() {
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(new Location(hub, 0.2, 5, 5.1));
        when(event.getTo()).thenReturn(new Location(hub, 0.7, 5, 5.9));
        listener.onMove(event);
        verify(player, never()).sendActionBar(any(Component.class));
    }

    @Test
    void chatDisplayModeSendsAChatMessageInstead() {
        listener.display("chat");
        move(-1, 0);
        verify(player).sendMessage(any(Component.class));
        verify(player, never()).sendActionBar(any(Component.class));
    }
}
