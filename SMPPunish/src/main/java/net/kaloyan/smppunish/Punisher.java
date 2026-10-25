package net.kaloyan.smppunish;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** The rules for punishing: who can be punished again, how warnings escalate, and saving every change before it takes effect. */
public final class Punisher {

    /** How an operation ended. */
    public enum Status {
        /** Done. */
        OK,
        /** The player already has one in force. */
        ALREADY,
        /** There is nothing to lift. */
        NOT_FOUND,
        /** Saving failed; nothing happened. */
        SAVE_FAILED
    }

    /**
     * Who is punished.
     *
     * @param id   the player
     * @param name their name
     * @param ip   their last address, or {@code null}
     */
    public record Target(UUID id, String name, String ip) {
    }

    /**
     * @param status how it ended
     * @param entry  the entry made, or the one that was already in force
     * @param count  for a warning: how many warnings count now
     * @param next   for a warning: the automatic step it triggers, or {@code null}
     */
    public record Result(Status status, Punishment entry, int count, Settings.Escalation next) {
        static Result of(Status status) {
            return new Result(status, null, 0, null);
        }
    }

    private final PunishStore store;
    private final Supplier<Settings> settings;
    private final LongSupplier clock;
    private final Logger log;

    /**
     * @param store    the data
     * @param settings the current settings
     * @param clock    epoch milliseconds
     * @param log      where problems are reported
     */
    public Punisher(PunishStore store, Supplier<Settings> settings, LongSupplier clock, Logger log) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        this.log = log;
    }

    /** @return the data */
    public PunishStore store() {
        return store;
    }

    private Result add(Punishment.Type type, Target t, String staff, String reason, long durationMillis, boolean withIp) {
        long now = clock.getAsLong();
        Punishment p = new Punishment(store.nextId(), type, t.id(), t.name(), withIp ? t.ip() : null, staff, reason, now,
                durationMillis == Durations.PERMANENT ? 0 : now + durationMillis);
        try {
            store.add(p);
        } catch (IOException e) {
            log.severe("Could not save punishments.yml: " + e.getMessage());
            return Result.of(Status.SAVE_FAILED);
        }
        return new Result(Status.OK, p, 0, null);
    }

    /**
     * Bans a player.
     *
     * @param t              who
     * @param staff          who bans
     * @param reason         why
     * @param durationMillis how long, or {@link Durations#PERMANENT}
     * @param withIp         whether the ban also covers the player's address
     * @return the result; {@code ALREADY} if they are banned already
     */
    public Result ban(Target t, String staff, String reason, long durationMillis, boolean withIp) {
        Punishment existing = store.banFor(t.id(), null, clock.getAsLong());
        if (existing != null) {
            return new Result(Status.ALREADY, existing, 0, null);
        }
        return add(Punishment.Type.BAN, t, staff, reason, durationMillis, withIp);
    }

    /**
     * Mutes a player.
     *
     * @param t              who
     * @param staff          who mutes
     * @param reason         why
     * @param durationMillis how long, or {@link Durations#PERMANENT}
     * @return the result; {@code ALREADY} if they are muted already
     */
    public Result mute(Target t, String staff, String reason, long durationMillis) {
        Punishment existing = store.muteFor(t.id(), clock.getAsLong());
        if (existing != null) {
            return new Result(Status.ALREADY, existing, 0, null);
        }
        return add(Punishment.Type.MUTE, t, staff, reason, durationMillis, false);
    }

    /**
     * Records a kick (the caller removes the player).
     *
     * @param t      who
     * @param staff  who kicks
     * @param reason why
     * @return the result
     */
    public Result kick(Target t, String staff, String reason) {
        return add(Punishment.Type.KICK, t, staff, reason, Durations.PERMANENT, false);
    }

    /**
     * Warns a player and works out the automatic step.
     *
     * @param t      who
     * @param staff  who warns
     * @param reason why
     * @return the result; {@code count} and {@code next} say what the warning leads to
     */
    public Result warn(Target t, String staff, String reason) {
        Result r = add(Punishment.Type.WARN, t, staff, reason, Durations.PERMANENT, false);
        if (r.status() != Status.OK) {
            return r;
        }
        Settings s = settings.get();
        int count = store.warnings(t.id(), clock.getAsLong(), s.warnExpiryDays());
        Settings.Escalation next = null;
        for (Settings.Escalation e : s.escalation()) {
            if (e.warns() == count) {
                next = e;
            }
        }
        return new Result(Status.OK, r.entry(), count, next);
    }

    /**
     * Lifts every ban (or every mute) in force against a player.
     *
     * @param type   BAN or MUTE
     * @param t      who
     * @param staff  who lifts
     * @param reason why
     * @return the lifted entries; empty if there was nothing to lift. If saving failed part-way the entries lifted so far are returned.
     */
    public List<Punishment> lift(Punishment.Type type, UUID t, String staff, String reason) {
        long now = clock.getAsLong();
        List<Punishment> lifted = new ArrayList<>();
        for (Punishment p : store.inForce(type, now)) {
            if (p.target().equals(t)) {
                try {
                    store.lift(p, staff, now, reason);
                    lifted.add(p);
                } catch (IOException e) {
                    log.severe("Could not save punishments.yml: " + e.getMessage());
                    break;
                }
            }
        }
        return lifted;
    }

    /**
     * Lifts one entry by its number (a ban, mute or a warning).
     *
     * @param id     the number
     * @param staff  who lifts
     * @param reason why
     * @return the result
     */
    public Result liftById(long id, String staff, String reason) {
        Punishment p = store.get(id);
        if (p == null || !p.active() || p.type() == Punishment.Type.KICK) {
            return Result.of(Status.NOT_FOUND);
        }
        try {
            store.lift(p, staff, clock.getAsLong(), reason);
        } catch (IOException e) {
            log.severe("Could not save punishments.yml: " + e.getMessage());
            return Result.of(Status.SAVE_FAILED);
        }
        return new Result(Status.OK, p, 0, null);
    }
}
