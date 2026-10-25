package net.kaloyan.smptravel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pending teleport requests. A player has at most one request waiting for them; a new one replaces it. Requests expire.
 * Pure logic, no server needed.
 */
public final class TpaRequests {

    /**
     * One request.
     *
     * @param from      who asked
     * @param here      {@code true} if the sender wants the target to come to them ({@code /tpahere})
     * @param expiresAt epoch milliseconds when it stops being valid
     */
    public record Request(UUID from, boolean here, long expiresAt) {
    }

    private final Map<UUID, Request> byTarget = new HashMap<>();

    /**
     * Sends a request, replacing any request the target already had.
     *
     * @param from    who asks
     * @param target  who is asked
     * @param here    whether the target should teleport to the sender
     * @param now     current time in milliseconds
     * @param validMs how long the request lasts
     * @return the request
     */
    public Request send(UUID from, UUID target, boolean here, long now, long validMs) {
        Request request = new Request(from, here, now + validMs);
        byTarget.put(target, request);
        return request;
    }

    /**
     * @param target who answers
     * @param now    current time in milliseconds
     * @return the waiting request, if it has not expired
     */
    public Optional<Request> peek(UUID target, long now) {
        Request r = byTarget.get(target);
        if (r != null && r.expiresAt() <= now) {
            byTarget.remove(target);
            return Optional.empty();
        }
        return Optional.ofNullable(r);
    }

    /**
     * Takes the waiting request (accept or deny).
     *
     * @param target who answers
     * @param now    current time in milliseconds
     * @return the request, if there was a valid one; it is removed
     */
    public Optional<Request> take(UUID target, long now) {
        Optional<Request> r = peek(target, now);
        r.ifPresent(x -> byTarget.remove(target));
        return r;
    }

    /**
     * Cancels the request a player sent.
     *
     * @param from the sender
     * @return the target it was addressed to, if there was one
     */
    public Optional<UUID> cancelFrom(UUID from) {
        for (Map.Entry<UUID, Request> e : byTarget.entrySet()) {
            if (e.getValue().from().equals(from)) {
                byTarget.remove(e.getKey());
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** Forgets everything about a player who left: requests to them and from them. */
    public void forget(UUID player) {
        byTarget.remove(player);
        byTarget.values().removeIf(r -> r.from().equals(player));
    }
}
