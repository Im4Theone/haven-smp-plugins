package net.kaloyan.smptravel;

/**
 * Where a player who died comes back. Pure decision logic.
 *
 * <p>Modes: {@code hub} sends everybody to the hub, even players with a bed; {@code bed} keeps a bed or respawn anchor and sends
 * everybody else to the hub; {@code vanilla} leaves respawning alone.
 */
public final class Respawn {

    /** The place chosen. */
    public enum Place { VANILLA, SAVED_HUB, CONFIGURED }

    private Respawn() {
    }

    /**
     * @param mode        {@code hub}, {@code bed} or {@code vanilla}
     * @param hasBed      whether the player would respawn at a bed or a respawn anchor
     * @param hasSavedHub whether an admin saved the hub with {@code /setspawn hub}
     * @return the place to use: the saved hub spot if there is one, else the location from the config
     */
    public static Place choose(String mode, boolean hasBed, boolean hasSavedHub) {
        if (mode.equals("vanilla") || (mode.equals("bed") && hasBed)) {
            return Place.VANILLA;
        }
        return hasSavedHub ? Place.SAVED_HUB : Place.CONFIGURED;
    }
}
