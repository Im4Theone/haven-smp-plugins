package net.kaloyan.smpscoreboard;

/**
 * The title shown to a player who joins.
 *
 * @param enabled     whether the title is shown
 * @param title       MiniMessage title with {@code <name>}
 * @param subtitle    MiniMessage subtitle with {@code <name>}; may be empty
 * @param fadeInMs    fade in time in milliseconds
 * @param stayMs      how long it stays fully visible, in milliseconds
 * @param fadeOutMs   fade out time in milliseconds
 */
public record WelcomeSettings(boolean enabled, String title, String subtitle, int fadeInMs, int stayMs, int fadeOutMs) {
}
