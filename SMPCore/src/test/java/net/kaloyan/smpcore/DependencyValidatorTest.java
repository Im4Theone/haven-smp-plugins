package net.kaloyan.smpcore;

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
    void requiredClassesExistInTheApiWeCompileAgainst() {
        for (String required : DependencyValidator.REQUIRED_CLASSES) {
            assertTrue(DependencyValidator.isClassPresent(required), required);
        }
    }

    @Test
    void absentClassIsReportedAbsent() {
        assertFalse(DependencyValidator.isClassPresent("net.kaloyan.DoesNotExist"));
    }
}
