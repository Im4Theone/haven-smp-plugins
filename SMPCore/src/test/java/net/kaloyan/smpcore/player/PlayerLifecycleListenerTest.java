package net.kaloyan.smpcore.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kaloyan.smpcore.api.CoreSettings;
import net.kaloyan.smpcore.log.DebugLogger;
import net.kaloyan.smpcore.testutil.LogCapture;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class PlayerLifecycleListenerTest {

    private static final UUID ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    private final LogCapture log = new LogCapture();
    private final AtomicReference<CoreSettings> settings = new AtomicReference<>(CoreSettings.defaults());
    private final PlayerLifecycleListener listener = new PlayerLifecycleListener(
            log.logger(), settings::get, new DebugLogger(log.logger(), settings::get));
    private final Player player = mock(Player.class);

    PlayerLifecycleListenerTest() {
        when(player.getUniqueId()).thenReturn(ID);
        when(player.getName()).thenReturn("Steve");
    }

    private PlayerJoinEvent join() {
        return new PlayerJoinEvent(player, Component.empty());
    }

    private PlayerQuitEvent quit() {
        return new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
    }

    @Test
    void joinIsLoggedByUuid() {
        listener.onJoin(join());

        assertEquals(1, log.messages(Level.INFO).size());
        assertTrue(log.messages(Level.INFO).get(0).contains(ID.toString()));
    }

    @Test
    void quitIsLoggedByUuid() {
        listener.onQuit(quit());

        assertEquals(1, log.messages(Level.INFO).size());
        assertTrue(log.messages(Level.INFO).get(0).contains(ID.toString()));
    }

    @Test
    void joinLoggingCanBeDisabledIndependentlyOfQuit() {
        settings.set(new CoreSettings(CoreSettings.DEFAULT_PREFIX, false, false, true));

        listener.onJoin(join());
        assertTrue(log.records().isEmpty());

        listener.onQuit(quit());
        assertEquals(1, log.records().size());
    }

    @Test
    void quitLoggingCanBeDisabledIndependentlyOfJoin() {
        settings.set(new CoreSettings(CoreSettings.DEFAULT_PREFIX, false, true, false));

        listener.onQuit(quit());
        assertTrue(log.records().isEmpty());

        listener.onJoin(join());
        assertEquals(1, log.records().size());
    }

    @Test
    void settingsChangesTakeEffectWithoutRecreatingTheListener() {
        listener.onJoin(join());
        settings.set(new CoreSettings(CoreSettings.DEFAULT_PREFIX, false, false, false));
        listener.onJoin(join());

        assertEquals(1, log.records().size());
    }

    @Test
    void debugLinesAppearOnlyWhenDebugIsOn() {
        settings.set(new CoreSettings(CoreSettings.DEFAULT_PREFIX, false, false, false));
        listener.onJoin(join());
        assertTrue(log.records().isEmpty());

        settings.set(new CoreSettings(CoreSettings.DEFAULT_PREFIX, true, false, false));
        listener.onJoin(join());
        assertEquals(1, log.records().size());
        assertTrue(log.allMessages().get(0).startsWith("[DEBUG]"));
    }
}
