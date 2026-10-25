package net.kaloyan.smpcore.api;

/**
 * Entry point other plugins use to talk to SMP-Core.
 *
 * <h2>Depending on SMP-Core</h2>
 * <ol>
 *   <li>Compile against the SMP-Core jar ({@code compileOnly}); do not shade it.</li>
 *   <li>Declare {@code depend: [SMP-Core]} in your {@code plugin.yml} so SMP-Core is enabled before
 *       your plugin and disabled after it. This does not prevent your plugin from being enabled if
 *       SMP-Core disables itself during startup, so check first.</li>
 *   <li>In {@code onEnable}, check that SMP-Core is enabled <em>before</em> using any of its classes (they
 *       are unloadable once it has disabled itself), then obtain the API from Bukkit's services manager:
 * <pre>{@code
 * if (!getServer().getPluginManager().isPluginEnabled("SMP-Core")) {
 *     getLogger().severe("SMP-Core is not enabled; disabling.");
 *     getServer().getPluginManager().disablePlugin(this);
 *     return;
 * }
 * SMPAPI api = getServer().getServicesManager().load(SMPAPI.class);
 * if (api == null) {
 *     getLogger().severe("SMP-Core API is not available; disabling.");
 *     getServer().getPluginManager().disablePlugin(this);
 *     return;
 * }
 * }</pre></li>
 * </ol>
 *
 * <p>The instance is published only after SMP-Core has fully started, and is withdrawn when it shuts
 * down or fails to start. After shutdown every method throws {@link IllegalStateException}; do not
 * keep using a reference past your own plugin's lifetime.
 *
 * <p>Only the {@code net.kaloyan.smpcore.api} package is a supported API. Everything else in the jar
 * is internal and may change without notice.
 */
public interface SMPAPI {

    /**
     * Returns the running SMP-Core version, for example {@code 1.0.0}.
     *
     * @return the plugin version
     * @throws IllegalStateException if SMP-Core has shut down
     */
    String getVersion();

    /**
     * Returns the currently active, validated configuration. The returned snapshot is immutable;
     * call this method again to observe changes made by {@code /smpcore reload}.
     *
     * @return the current settings snapshot
     * @throws IllegalStateException if SMP-Core has shut down
     */
    CoreSettings getSettings();

    /**
     * Returns the shared service registry.
     *
     * @return the service registry
     * @throws IllegalStateException if SMP-Core has shut down
     */
    ServiceRegistry getServices();
}
