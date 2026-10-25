package net.kaloyan.smpcore.api;

import java.util.Optional;
import org.bukkit.plugin.Plugin;

/**
 * Shared registry that lets plugins publish a service object under an interface type and look up
 * services published by other plugins.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>Every registration is owned by the plugin that made it.</li>
 *   <li>A registration lives until its owner calls {@link #unregister}, the owner is disabled
 *       (SMP-Core then removes all its registrations automatically, when the server fires
 *       {@code PluginDisableEvent}, which is before the owner's {@code onDisable} runs), or SMP-Core
 *       shuts down.</li>
 *   <li>The registry never calls {@code close()} or any other method on registered services. The
 *       owner remains responsible for releasing the resources behind its own services.</li>
 *   <li>Once SMP-Core has shut down, every method of this registry throws
 *       {@link IllegalStateException}, including on references obtained earlier.</li>
 * </ul>
 *
 * <p>All methods are thread-safe. Services should be registered in {@code onEnable} and looked up
 * after SMP-Core is enabled, which is guaranteed for plugins that declare {@code depend: [SMP-Core]}.
 */
public interface ServiceRegistry {

    /**
     * Registers a service.
     *
     * @param owner   the plugin that owns the registration
     * @param type    the type other plugins will look the service up by; normally an interface
     * @param service the implementation
     * @param <T>     the service type
     * @throws ServiceAlreadyRegisteredException if a service is already registered for {@code type}
     * @throws IllegalStateException             if SMP-Core has shut down
     */
    <T> void register(Plugin owner, Class<T> type, T service);

    /**
     * Replaces the service registered for {@code type}. Only the plugin that registered the
     * current service may replace it.
     *
     * @param owner   the plugin that owns the existing registration
     * @param type    the service type
     * @param service the new implementation
     * @param <T>     the service type
     * @return the service that was replaced
     * @throws ServiceNotFoundException  if nothing is registered for {@code type}
     * @throws IllegalArgumentException  if {@code owner} does not own the current registration
     * @throws IllegalStateException     if SMP-Core has shut down
     */
    <T> T replace(Plugin owner, Class<T> type, T service);

    /**
     * Removes the service registered for {@code type}. Only the owner may remove it.
     *
     * @param owner the plugin that owns the registration
     * @param type  the service type
     * @return {@code true} if a service was removed, {@code false} if none was registered
     * @throws IllegalArgumentException if {@code owner} does not own the current registration
     * @throws IllegalStateException    if SMP-Core has shut down
     */
    boolean unregister(Plugin owner, Class<?> type);

    /**
     * Returns the service registered for {@code type}.
     *
     * @param type the service type
     * @param <T>  the service type
     * @return the registered service, never {@code null}
     * @throws ServiceNotFoundException if nothing is registered for {@code type}
     * @throws IllegalStateException    if SMP-Core has shut down
     */
    <T> T get(Class<T> type);

    /**
     * Returns the service registered for {@code type}, if any.
     *
     * @param type the service type
     * @param <T>  the service type
     * @return the service, or empty if none is registered
     * @throws IllegalStateException if SMP-Core has shut down
     */
    <T> Optional<T> find(Class<T> type);

    /**
     * Checks whether a service is registered for {@code type}.
     *
     * @param type the service type
     * @return {@code true} if a service is registered
     * @throws IllegalStateException if SMP-Core has shut down
     */
    boolean isRegistered(Class<?> type);
}
