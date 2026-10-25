package net.kaloyan.smpeconomy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Verifies at startup that the server provides the classes SMP-Economy relies on, so that running on a
 * server without them fails with a clear message instead of a {@code NoClassDefFoundError} later.
 */
final class DependencyValidator {

    /** Classes SMP-Economy uses directly that it does not ship itself. */
    static final List<String> REQUIRED_CLASSES = List.of(
            "io.papermc.paper.command.brigadier.Commands",
            "io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents",
            "org.sqlite.JDBC");

    private DependencyValidator() {
    }

    /**
     * Checks that every required class can be loaded.
     *
     * @param classPresent tells whether a class with the given binary name is available
     * @throws IllegalStateException listing every missing class
     */
    static void requireAvailable(Predicate<String> classPresent) {
        List<String> missing = new ArrayList<>();
        for (String className : REQUIRED_CLASSES) {
            if (!classPresent.test(className)) {
                missing.add(className);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("This server does not provide classes SMP-Economy requires (missing: "
                    + String.join(", ", missing) + "). Run it on Paper 1.21.11 or newer in the 1.21 line, "
                    + "which includes the SQLite JDBC driver.");
        }
    }

    /**
     * Checks class availability against SMP-Economy's own class loader, without initializing the class.
     *
     * @param className binary class name
     * @return {@code true} if the class can be loaded
     */
    static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, DependencyValidator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false; // expected on servers that lack the class; reported by requireAvailable
        }
    }
}
