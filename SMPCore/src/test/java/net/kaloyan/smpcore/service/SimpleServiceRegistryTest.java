package net.kaloyan.smpcore.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.kaloyan.smpcore.api.ServiceAlreadyRegisteredException;
import net.kaloyan.smpcore.api.ServiceNotFoundException;
import net.kaloyan.smpcore.testutil.TestSupport;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimpleServiceRegistryTest {

    interface Economy {
    }

    interface Claims {
    }

    private final Plugin alpha = TestSupport.plugin("Alpha");
    private final Plugin beta = TestSupport.plugin("Beta");
    private SimpleServiceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleServiceRegistry();
    }

    @Test
    void registeredServiceCanBeRetrieved() {
        Economy economy = new Economy() { };

        registry.register(alpha, Economy.class, economy);

        assertSame(economy, registry.get(Economy.class));
        assertEquals(Optional.of(economy), registry.find(Economy.class));
        assertTrue(registry.isRegistered(Economy.class));
    }

    @Test
    void unregisteredTypeIsReportedAbsent() {
        assertFalse(registry.isRegistered(Economy.class));
        assertEquals(Optional.empty(), registry.find(Economy.class));
    }

    @Test
    void missingServiceFailsWithClearMessage() {
        ServiceNotFoundException e = assertThrows(ServiceNotFoundException.class, () -> registry.get(Economy.class));

        assertTrue(e.getMessage().contains(Economy.class.getName()), e.getMessage());
    }

    @Test
    void duplicateRegistrationIsRejectedAndKeepsTheOriginal() {
        Economy first = new Economy() { };
        registry.register(alpha, Economy.class, first);

        ServiceAlreadyRegisteredException e = assertThrows(ServiceAlreadyRegisteredException.class,
                () -> registry.register(beta, Economy.class, new Economy() { }));

        assertTrue(e.getMessage().contains("Alpha"), "message should name the current owner: " + e.getMessage());
        assertSame(first, registry.get(Economy.class));
    }

    @Test
    void sameOwnerCannotRegisterTwiceEither() {
        registry.register(alpha, Economy.class, new Economy() { });

        assertThrows(ServiceAlreadyRegisteredException.class,
                () -> registry.register(alpha, Economy.class, new Economy() { }));
    }

    @Test
    void ownerCanReplaceExplicitlyAndGetsThePreviousService() {
        Economy first = new Economy() { };
        Economy second = new Economy() { };
        registry.register(alpha, Economy.class, first);

        Economy previous = registry.replace(alpha, Economy.class, second);

        assertSame(first, previous);
        assertSame(second, registry.get(Economy.class));
        assertNotSame(first, registry.get(Economy.class));
    }

    @Test
    void replaceRequiresAnExistingRegistration() {
        assertThrows(ServiceNotFoundException.class, () -> registry.replace(alpha, Economy.class, new Economy() { }));
        assertFalse(registry.isRegistered(Economy.class));
    }

    @Test
    void otherPluginsCannotReplaceOrUnregister() {
        Economy original = new Economy() { };
        registry.register(alpha, Economy.class, original);

        assertThrows(IllegalArgumentException.class, () -> registry.replace(beta, Economy.class, new Economy() { }));
        assertThrows(IllegalArgumentException.class, () -> registry.unregister(beta, Economy.class));
        assertSame(original, registry.get(Economy.class));
    }

    @Test
    void ownerCanUnregister() {
        registry.register(alpha, Economy.class, new Economy() { });

        assertTrue(registry.unregister(alpha, Economy.class));

        assertFalse(registry.isRegistered(Economy.class));
        registry.register(beta, Economy.class, new Economy() { }); // type is free again
    }

    @Test
    void unregisteringAnAbsentTypeReturnsFalse() {
        assertFalse(registry.unregister(alpha, Economy.class));
    }

    @Test
    void unregisterAllRemovesOnlyThatOwnersServices() {
        registry.register(alpha, Economy.class, new Economy() { });
        registry.register(beta, Claims.class, new Claims() { });

        assertEquals(1, registry.unregisterAll(alpha));

        assertFalse(registry.isRegistered(Economy.class));
        assertTrue(registry.isRegistered(Claims.class));
        assertEquals(0, registry.unregisterAll(alpha));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> registry.register(null, Economy.class, new Economy() { }));
        assertThrows(NullPointerException.class, () -> registry.register(alpha, null, new Economy() { }));
        assertThrows(NullPointerException.class, () -> registry.register(alpha, Economy.class, null));
        assertThrows(NullPointerException.class, () -> registry.get(null));
    }

    @Test
    void shutdownDropsServicesAndRejectsEveryOperation() {
        registry.register(alpha, Economy.class, new Economy() { });

        registry.shutdown();

        assertThrows(IllegalStateException.class, () -> registry.get(Economy.class));
        assertThrows(IllegalStateException.class, () -> registry.find(Economy.class));
        assertThrows(IllegalStateException.class, () -> registry.isRegistered(Economy.class));
        assertThrows(IllegalStateException.class, () -> registry.register(alpha, Claims.class, new Claims() { }));
        assertThrows(IllegalStateException.class, () -> registry.replace(alpha, Economy.class, new Economy() { }));
        assertThrows(IllegalStateException.class, () -> registry.unregister(alpha, Economy.class));
    }

    @Test
    void shutdownIsIdempotentAndLateOwnerCleanupIsHarmless() {
        registry.register(alpha, Economy.class, new Economy() { });
        registry.shutdown();

        registry.shutdown();

        assertEquals(0, registry.unregisterAll(alpha));
    }
}
