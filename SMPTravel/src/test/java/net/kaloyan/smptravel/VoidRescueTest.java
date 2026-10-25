package net.kaloyan.smptravel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class VoidRescueTest {

    private final VoidRescue.Config on = new VoidRescue.Config(true, Set.of("hub"), 20, true, "back");

    @Test
    void onlyPlayersBelowTheHeightInTheHubWorldAreRescued() {
        assertTrue(VoidRescue.falling(on, "hub", 19.9, false));
        assertTrue(VoidRescue.falling(on, "hub", -70, false));
        assertFalse(VoidRescue.falling(on, "hub", 20, false), "exactly at the height is fine");
        assertFalse(VoidRescue.falling(on, "hub", 100, false));
        assertFalse(VoidRescue.falling(on, "world", -50, false), "other worlds are left alone");
    }

    @Test
    void creativeAndSpectatorPlayersAreLeftAloneUnlessConfigured() {
        assertFalse(VoidRescue.falling(on, "hub", 0, true));
        assertTrue(VoidRescue.falling(new VoidRescue.Config(true, Set.of("hub"), 20, false, "back"), "hub", 0, true));
    }

    @Test
    void switchedOffDoesNothing() {
        assertFalse(VoidRescue.falling(new VoidRescue.Config(false, Set.of("hub"), 20, true, "back"), "hub", -60, false));
    }
}
