package net.kaloyan.smpeconomy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DependencyValidatorTest {

    @Test
    void passesWhenEveryRequiredClassIsPresent() {
        assertDoesNotThrow(() -> DependencyValidator.requireAvailable(name -> true));
    }

    @Test
    void failsListingEveryMissingClass() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DependencyValidator.requireAvailable(name -> false));

        for (String required : DependencyValidator.REQUIRED_CLASSES) {
            assertTrue(e.getMessage().contains(required), e.getMessage());
        }
    }

    @Test
    void sqliteDriverIsRequiredAndPresentOnTheTestClassPath() {
        assertTrue(DependencyValidator.REQUIRED_CLASSES.contains("org.sqlite.JDBC"));
        assertTrue(DependencyValidator.REQUIRED_CLASSES.stream().allMatch(DependencyValidator::isClassPresent),
                "the build must compile against exactly the classes the plugin checks for at startup");
        assertFalse(DependencyValidator.isClassPresent("does.not.Exist"));
    }
}
