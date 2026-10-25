package net.kaloyan.smpeconomy.player;

import static net.kaloyan.smpeconomy.testutil.TestSupport.money;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.logging.Level;
import net.kaloyan.smpeconomy.config.EconomySettings;
import net.kaloyan.smpeconomy.log.DebugLogger;
import net.kaloyan.smpeconomy.service.EconomyServiceImpl;
import net.kaloyan.smpeconomy.storage.EconomyStore;
import net.kaloyan.smpeconomy.testutil.LogCapture;
import net.kaloyan.smpeconomy.testutil.TestSupport;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountInitListenerTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();
    private final Deque<Runnable> storageQueue = new ArrayDeque<>();
    private final EconomySettings settings = TestSupport.smallLimits();
    private EconomyStore store;
    private AccountInitListener listener;

    @BeforeEach
    void setUp() {
        store = TestSupport.openStore(dir, () -> settings, log);
        EconomyServiceImpl service = new EconomyServiceImpl(store, storageQueue::add, () -> settings,
                new DebugLogger(log.logger(), () -> settings));
        listener = new AccountInitListener(service, log.logger());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static PlayerJoinEvent join(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private void drain() {
        while (!storageQueue.isEmpty()) {
            storageQueue.poll().run();
        }
    }

    @Test
    void joiningCreatesTheAccountWithoutDoingDatabaseWorkOnTheCallingThread() {
        UUID id = UUID.randomUUID();

        listener.onJoin(join(id, "Alex"));

        assertFalse(store.hasAccount(id), "the handler must only queue the work, not perform it");
        assertEquals(1, storageQueue.size());
        drain();
        assertEquals(0, money("100.00").compareTo(store.balance(id).orElseThrow()));
        assertEquals("Alex", store.findAccount(id).orElseThrow().lastName());
    }

    @Test
    void rejoiningAndRenamingNeverGrantTheStartingBalanceAgain() {
        UUID id = UUID.randomUUID();
        listener.onJoin(join(id, "Alex"));
        drain();
        store.withdraw(id, money("75.00"));

        listener.onJoin(join(id, "Alex"));
        listener.onJoin(join(id, "AlexRenamed"));
        drain();

        assertEquals(0, money("25.00").compareTo(store.balance(id).orElseThrow()));
        assertEquals("AlexRenamed", store.findAccount(id).orElseThrow().lastName());
    }

    @Test
    void aFailedInitializationIsLoggedByUuidAndDoesNotThrowIntoTheServer() {
        UUID id = UUID.randomUUID();
        store.close();

        listener.onJoin(join(id, "Alex"));
        drain();

        assertTrue(log.messages(Level.SEVERE).stream().anyMatch(m -> m.contains(id.toString())), log.allMessages().toString());
        assertTrue(log.messages(Level.SEVERE).stream().noneMatch(m -> m.contains("Alex")),
                "personal data beyond the UUID must not be logged");
    }

    @Test
    void aFailedInitializationCanBeRetriedOnTheNextJoin() throws Exception {
        UUID id = UUID.randomUUID();
        store.close();
        listener.onJoin(join(id, "Alex"));
        drain();
        store = TestSupport.openStore(dir, () -> settings, log);
        EconomyServiceImpl service = new EconomyServiceImpl(store, storageQueue::add, () -> settings,
                new DebugLogger(log.logger(), () -> settings));

        new AccountInitListener(service, log.logger()).onJoin(join(id, "Alex"));
        drain();

        assertEquals(0, money("100.00").compareTo(store.balance(id).orElseThrow()));
    }
}
