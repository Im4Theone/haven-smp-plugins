package net.kaloyan.smppunish;

import java.util.UUID;

/** One entry in a player's record: a ban, mute, kick or warning. */
public final class Punishment {

    /** What kind of entry it is. */
    public enum Type { BAN, MUTE, KICK, WARN }

    private final long id;
    private final Type type;
    private final UUID target;
    private final String targetName;
    private final String ip;
    private final String staff;
    private final String reason;
    private final long created;
    private final long expires;
    private boolean active = true;
    private String removedBy = "";
    private long removedAt;
    private String removeReason = "";

    /**
     * @param id         the number
     * @param type       ban, mute, kick or warning
     * @param target     the player
     * @param targetName the player's name when it happened
     * @param ip         the player's address if the ban also covers it, otherwise {@code null}
     * @param staff      who did it ({@code Console} for the console)
     * @param reason     why
     * @param created    when, epoch milliseconds
     * @param expires    when it ends, epoch milliseconds, or 0 if it never does
     */
    public Punishment(long id, Type type, UUID target, String targetName, String ip, String staff, String reason, long created, long expires) {
        this.id = id;
        this.type = type;
        this.target = target;
        this.targetName = targetName;
        this.ip = ip;
        this.staff = staff;
        this.reason = reason;
        this.created = created;
        this.expires = expires;
    }

    /** @return the number */
    public long id() {
        return id;
    }

    /** @return the kind */
    public Type type() {
        return type;
    }

    /** @return the player */
    public UUID target() {
        return target;
    }

    /** @return the player's name when it happened */
    public String targetName() {
        return targetName;
    }

    /** @return the address the ban also covers, or {@code null} */
    public String ip() {
        return ip;
    }

    /** @return who did it */
    public String staff() {
        return staff;
    }

    /** @return why */
    public String reason() {
        return reason;
    }

    /** @return when it happened */
    public long created() {
        return created;
    }

    /** @return when it ends, 0 if never */
    public long expires() {
        return expires;
    }

    /** @return whether it was lifted early (false) or has not been (true); an expired one may still say true */
    public boolean active() {
        return active;
    }

    /** @return who lifted it, or empty */
    public String removedBy() {
        return removedBy;
    }

    /** @return when it was lifted, or 0 */
    public long removedAt() {
        return removedAt;
    }

    /** @return why it was lifted, or empty */
    public String removeReason() {
        return removeReason;
    }

    /**
     * Lifts it.
     *
     * @param by     who
     * @param at     when
     * @param reason why
     */
    void lift(String by, long at, String reason) {
        active = false;
        removedBy = by;
        removedAt = at;
        removeReason = reason;
    }

    /** Puts a lifted entry back (used to undo a failed save). */
    void restore(boolean wasActive, String by, long at, String reason) {
        active = wasActive;
        removedBy = by;
        removedAt = at;
        removeReason = reason;
    }

    /**
     * @param now the time
     * @return whether it is in force now: not lifted and not expired
     */
    public boolean inForce(long now) {
        return active && (expires == 0 || expires > now);
    }
}
