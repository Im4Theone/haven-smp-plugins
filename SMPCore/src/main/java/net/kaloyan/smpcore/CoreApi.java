package net.kaloyan.smpcore;

import net.kaloyan.smpcore.api.CoreSettings;
import net.kaloyan.smpcore.api.SMPAPI;
import net.kaloyan.smpcore.api.ServiceRegistry;
import net.kaloyan.smpcore.config.ConfigManager;
import net.kaloyan.smpcore.service.SimpleServiceRegistry;

/**
 * The {@link SMPAPI} handed to other plugins. It is created already wired to fully initialized
 * services and goes inert on {@link #invalidate()}, so a stale reference kept by another plugin fails
 * loudly instead of reaching shut-down services.
 */
final class CoreApi implements SMPAPI {

    private final String version;
    private final ConfigManager config;
    private final SimpleServiceRegistry services;
    private volatile boolean active = true;

    CoreApi(String version, ConfigManager config, SimpleServiceRegistry services) {
        this.version = version;
        this.config = config;
        this.services = services;
    }

    @Override
    public String getVersion() {
        requireActive();
        return version;
    }

    @Override
    public CoreSettings getSettings() {
        requireActive();
        return config.current();
    }

    @Override
    public ServiceRegistry getServices() {
        requireActive();
        return services;
    }

    /** Makes every further call on this API throw {@link IllegalStateException}. */
    void invalidate() {
        active = false;
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("SMP-Core has shut down; its API is no longer available");
        }
    }
}
