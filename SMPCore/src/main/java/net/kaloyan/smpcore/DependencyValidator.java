package net.kaloyan.smpcore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Verifies at startup that the server provides the Paper APIs SMP-Core relies on, so that running on
 * a plain Spigot or too-old server fails with a clear message instead of a {@code NoClassDefFoundError}
 * later.
 */
final class DependencyValidator {

    /** Paper-only classes SMP-Core uses directly. */
    static final List<String> REQUIRED_CLASSES = List.of(
            "io.papermc.paper.command.brigadier.Commands",
            "io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents");

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
            throw new IllegalStateException("This server does not provide the Paper API SMP-Core requires "
                    + "(missing: " + String.join(", ", missing) + "). Run SMP-Core on Paper 1.21.11 or newer in the 1.21 line.");
        }
    }

    /**
     * Checks class availability against SMP-Core's own class loader, without initializing the class.
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
