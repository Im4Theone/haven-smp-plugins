package net.kaloyan.smpcore.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kaloyan.smpcore.api.ServiceAlreadyRegisteredException;
import net.kaloyan.smpcore.api.ServiceNotFoundException;
import net.kaloyan.smpcore.api.ServiceRegistry;
import org.bukkit.plugin.Plugin;

/**
 * Default {@link ServiceRegistry}. Internal: plugins should only use the interface.
 *
 * <p>Registrations are keyed by service type and remember the owning plugin's name. All methods are
 * synchronized; registration is rare and lookups are a single map access.
 */
public final class SimpleServiceRegistry implements ServiceRegistry {

    private record Entry(Object service, String owner) {
    }

    private final Map<Class<?>, Entry> entries = new HashMap<>();
    private boolean shutDown;

    @Override
    public synchronized <T> void register(Plugin owner, Class<T> type, T service) {
        requireRunning();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        Entry existing = entries.get(type);
        if (existing != null) {
            throw new ServiceAlreadyRegisteredException(type, existing.owner());
        }
        entries.put(type, new Entry(service, owner.getName()));
    }

    @Override
    public synchronized <T> T replace(Plugin owner, Class<T> type, T service) {
        requireRunning();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        Entry existing = requireOwned(owner, type);
        entries.put(type, new Entry(service, owner.getName()));
        return type.cast(existing.service());
    }

    @Override
    public synchronized boolean unregister(Plugin owner, Class<?> type) {
        requireRunning();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        if (!entries.containsKey(type)) {
            return false;
        }
        requireOwned(owner, type);
        entries.remove(type);
        return true;
    }

    @Override
    public synchronized <T> T get(Class<T> type) {
        requireRunning();
        Objects.requireNonNull(type, "type");
        Entry entry = entries.get(type);
        if (entry == null) {
            throw new ServiceNotFoundException(type);
        }
        return type.cast(entry.service());
    }

    @Override
    public synchronized <T> Optional<T> find(Class<T> type) {
        requireRunning();
        Objects.requireNonNull(type, "type");
        return Optional.ofNullable(entries.get(type)).map(entry -> type.cast(entry.service()));
    }

    @Override
    public synchronized boolean isRegistered(Class<?> type) {
        requireRunning();
        Objects.requireNonNull(type, "type");
        return entries.containsKey(type);
    }

    /**
     * Removes every registration owned by {@code owner}. Called when that plugin is disabled so its
     * services (and its class loader) are not kept alive. Does nothing once the registry has shut down.
     *
     * @param owner the plugin that is going away
     * @return how many registrations were removed
     */
    public synchronized int unregisterAll(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (shutDown) {
            return 0;
        }
        int before = entries.size();
        entries.values().removeIf(entry -> entry.owner().equals(owner.getName()));
        return before - entries.size();
    }

    /**
     * Drops all registrations and rejects every further call. Services are not closed; their owners
     * are responsible for that. Idempotent.
     */
    public synchronized void shutdown() {
        entries.clear();
        shutDown = true;
    }

    private void requireRunning() {
        if (shutDown) {
            throw new IllegalStateException("SMP-Core has shut down; its service registry is no longer available");
        }
    }

    private Entry requireOwned(Plugin owner, Class<?> type) {
        Entry existing = entries.get(type);
        if (existing == null) {
            throw new ServiceNotFoundException(type);
        }
        if (!existing.owner().equals(owner.getName())) {
            throw new IllegalArgumentException("Service " + type.getName() + " is owned by plugin '"
                    + existing.owner() + "', not '" + owner.getName() + "'");
        }
        return existing;
    }
}
