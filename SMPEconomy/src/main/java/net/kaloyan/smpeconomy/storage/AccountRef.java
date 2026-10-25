package net.kaloyan.smpeconomy.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * An existing account. The UUID is the identity; the name is only the last name seen for display and
 * lookup and may be {@code null}.
 *
 * @param uuid     the player's permanent identity
 * @param lastName last known player name, or {@code null}
 */
public record AccountRef(UUID uuid, String lastName) {

    public AccountRef {
        Objects.requireNonNull(uuid, "uuid");
    }

    /**
     * Returns something printable for messages.
     *
     * @return the last known name, or the UUID if no name is known
     */
    public String displayName() {
        return lastName != null ? lastName : uuid.toString();
    }
}
