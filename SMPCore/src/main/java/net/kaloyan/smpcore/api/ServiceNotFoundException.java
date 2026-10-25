package net.kaloyan.smpcore.api;

/**
 * Thrown when a service is requested (or replaced) but nothing is registered for its type.
 */
public final class ServiceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a missing service type.
     *
     * @param type the service type that was requested
     */
    public ServiceNotFoundException(Class<?> type) {
        super("No service registered for " + type.getName()
                + ". Check that the providing plugin is installed, enabled and listed in your plugin's depend/softdepend.");
    }
}
