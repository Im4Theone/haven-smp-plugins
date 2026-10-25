package net.kaloyan.smpcore.config;

import java.util.List;
import net.kaloyan.smpcore.api.CoreSettings;

/**
 * Outcome of a configuration reload attempt.
 *
 * @param success  {@code true} if the new settings were validated and applied
 * @param settings the settings that are active after the attempt: the new ones on success, the
 *                 unchanged previous ones on failure
 * @param problems why the reload was rejected; empty on success
 */
public record ReloadResult(boolean success, CoreSettings settings, List<String> problems) {

    public ReloadResult {
        problems = List.copyOf(problems);
    }

    static ReloadResult applied(CoreSettings settings) {
        return new ReloadResult(true, settings, List.of());
    }

    static ReloadResult rejected(CoreSettings stillActive, List<String> problems) {
        return new ReloadResult(false, stillActive, problems);
    }
}
