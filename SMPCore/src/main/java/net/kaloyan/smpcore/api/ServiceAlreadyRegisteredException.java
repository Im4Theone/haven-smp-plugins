package net.kaloyan.smpcore.api;

/**
 * Thrown when registering a service for a type that already has one.
 * Use {@link ServiceRegistry#replace} to replace a service deliberately.
 */
public final class ServiceAlreadyRegisteredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a duplicate registration.
     *
     * @param type  the service type that was registered twice
     * @param owner name of the plugin that currently owns the registration
     */
    public ServiceAlreadyRegisteredException(Class<?> type, String owner) {
        super("A service for " + type.getName() + " is already registered by plugin '" + owner + "'.");
    }
}
