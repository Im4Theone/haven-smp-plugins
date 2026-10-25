package net.kaloyan.smppunish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PunishTest {

    @TempDir
    Path dir;

    private final UUID steve = UUID.randomUUID();
    private final Punisher.Target target = new Punisher.Target(steve, "Steve", "10.0.0.5");
    private long now = 1_000_000_000L;
    private PunishStore store;
    private Punisher punisher;
    private Settings settings;

    @BeforeEach
    void setUp() throws Exception {
        settings = Settings.parse(bundled());
        store = new PunishStore(dir.resolve("punishments.yml"));
        punisher = new Punisher(store, () -> settings, () -> now, Logger.getLogger("test"));
    }

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = PunishTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    // ---------------------------------------------------------------- times

    @Test
    void durationsAreReadAndWritten() {
        assertEquals(30 * 60_000L, Durations.parse("30m"));
        assertEquals(36 * 3_600_000L, Durations.parse("1d12h"));
        assertEquals(7 * 86_400_000L, Durations.parse("1w"));
        assertEquals(30 * 86_400_000L, Durations.parse("1mo"));
        assertEquals(Durations.PERMANENT, Durations.parse("PERM"));
        assertEquals(Durations.INVALID, Durations.parse("abc"));
        assertEquals(Durations.INVALID, Durations.parse("5x"));
        assertEquals(Durations.INVALID, Durations.parse("d5"));
        assertEquals(Durations.INVALID, Durations.parse("0d"));
        assertEquals(Durations.INVALID, Durations.parse("999999y"));
        assertEquals("2d 3h", Durations.format(2 * 86_400_000L + 3 * 3_600_000L));
        assertEquals("45m", Durations.format(45 * 60_000L));
        assertEquals("permanent", Durations.format(Durations.PERMANENT));
    }

    // ---------------------------------------------------------------- bans

    @Test
    void aBanKeepsThePlayerOutUntilItExpires() {
        Punisher.Result r = punisher.ban(target, "Alex", "x-ray", 3_600_000L, false);
        assertEquals(Punisher.Status.OK, r.status());
        assertNotNull(store.banFor(steve, null, now));
        assertNotNull(store.banFor(steve, null, now + 3_599_000L));
        assertNull(store.banFor(steve, null, now + 3_600_001L), "expired by itself");
    }

    @Test
    void aSecondBanIsRefusedAndTheFirstStays() {
        punisher.ban(target, "Alex", "a", Durations.PERMANENT, false);
        Punisher.Result again = punisher.ban(target, "Bob", "b", 1000, false);
        assertEquals(Punisher.Status.ALREADY, again.status());
        assertEquals("Alex", again.entry().staff());
        assertEquals(1, store.of(steve).size());
    }

    @Test
    void anAddressBanAlsoKeepsOutOtherAccountsOnThatAddress() {
        punisher.ban(target, "Alex", "evasion", Durations.PERMANENT, true);
        UUID alt = UUID.randomUUID();
        assertNotNull(store.banFor(alt, "10.0.0.5", now));
        assertNull(store.banFor(alt, "10.0.0.6", now));
        assertNull(store.banFor(alt, null, now));
        punisher.ban(new Punisher.Target(UUID.randomUUID(), "Other", "10.0.0.9"), "Alex", "plain", Durations.PERMANENT, false);
        assertNull(store.banFor(UUID.randomUUID(), "10.0.0.9", now), "a plain ban does not cover the address");
    }

    @Test
    void unbanningLiftsAndKeepsTheRecord() {
        punisher.ban(target, "Alex", "a", Durations.PERMANENT, false);
        List<Punishment> lifted = punisher.lift(Punishment.Type.BAN, steve, "Bob", "appeal accepted");
        assertEquals(1, lifted.size());
        assertNull(store.banFor(steve, null, now));
        Punishment p = store.of(steve).get(0);
        assertFalse(p.active());
        assertEquals("Bob", p.removedBy());
        assertEquals("appeal accepted", p.removeReason());
        assertTrue(punisher.lift(Punishment.Type.BAN, steve, "Bob", "").isEmpty());
        assertEquals(Punisher.Status.OK, punisher.ban(target, "Alex", "again", 1000, false).status(), "can be banned again");
    }

    @Test
    void ifSavingFailsNothingHappens() throws Exception {
        Files.createDirectory(dir.resolve("punishments.yml.tmp"));
        assertEquals(Punisher.Status.SAVE_FAILED, punisher.ban(target, "Alex", "a", 1000, false).status());
        assertNull(store.banFor(steve, null, now));
        assertTrue(store.of(steve).isEmpty());
        assertEquals(1, store.nextId());
    }

    // ---------------------------------------------------------------- mutes, kicks, warnings

    @Test
    void mutesWorkAndExpire() {
        assertEquals(Punisher.Status.OK, punisher.mute(target, "Alex", "spam", 60_000L).status());
        assertNotNull(store.muteFor(steve, now));
        assertEquals(Punisher.Status.ALREADY, punisher.mute(target, "Alex", "spam", 60_000L).status());
        assertNull(store.muteFor(steve, now + 61_000L));
        assertEquals(1, punisher.lift(Punishment.Type.MUTE, steve, "Alex", "").size());
        assertNull(store.muteFor(steve, now));
    }

    @Test
    void kicksAreOnlyRecorded() {
        Punisher.Result r = punisher.kick(target, "Alex", "afk");
        assertEquals(Punishment.Type.KICK, r.entry().type());
        assertNull(store.banFor(steve, null, now));
        assertEquals(Punisher.Status.NOT_FOUND, punisher.liftById(r.entry().id(), "Alex", "").status());
    }

    @Test
    void warningsAddUpAndTriggerTheConfiguredStepsOnce() {
        assertNull(punisher.warn(target, "Alex", "1").next());
        Punisher.Result second = punisher.warn(target, "Alex", "2");
        assertEquals(2, second.count());
        assertEquals(Punishment.Type.MUTE, second.next().type());
        assertEquals(3_600_000L, second.next().durationMillis());
        Punisher.Result third = punisher.warn(target, "Alex", "3");
        assertEquals(Punishment.Type.BAN, third.next().type());
        assertNull(punisher.warn(target, "Alex", "4").next(), "no step at 4");
        assertEquals(7 * 86_400_000L, punisher.warn(target, "Alex", "5").next().durationMillis());
    }

    @Test
    void oldWarningsStopCounting() {
        punisher.warn(target, "Alex", "old");
        now += 31 * 86_400_000L;
        assertEquals(1, punisher.warn(target, "Alex", "new").count());
    }

    @Test
    void aWarningCanBeTakenBack() {
        long id = punisher.warn(target, "Alex", "mistake").entry().id();
        assertEquals(Punisher.Status.OK, punisher.liftById(id, "Bob", "wrong player").status());
        assertEquals(1, punisher.warn(target, "Alex", "real").count());
    }

    // ---------------------------------------------------------------- the file

    @Test
    void everythingSurvivesARestart() throws IOException {
        punisher.ban(target, "Alex", "reason with: colon and | bar", 86_400_000L, true);
        punisher.mute(new Punisher.Target(UUID.randomUUID(), "Mia", null), "Bob", "spam", Durations.PERMANENT);
        punisher.lift(Punishment.Type.BAN, steve, "Bob", "appeal");
        store.seen(steve, "Steve", "10.0.0.5", now);
        store.saveIfDirty();
        PunishStore again = new PunishStore(dir.resolve("punishments.yml"));
        again.load();
        Punishment p = again.of(steve).get(0);
        assertEquals("reason with: colon and | bar", p.reason());
        assertEquals("10.0.0.5", p.ip());
        assertFalse(p.active());
        assertEquals("appeal", p.removeReason());
        assertEquals(3, again.nextId());
        assertEquals(steve, again.byName("steve").id());
        assertNotNull(again.byId(steve));
    }

    @Test
    void aDamagedFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("punishments.yml"), "punishments:\n  '1':\n    type: NOPE\n");
        assertThrows(IOException.class, () -> new PunishStore(dir.resolve("punishments.yml")).load());
    }

    @Test
    void playersOnTheSameAddressAreListed() {
        store.seen(steve, "Steve", "1.1.1.1", now);
        store.seen(UUID.randomUUID(), "Alt", "1.1.1.1", now);
        store.seen(UUID.randomUUID(), "Other", "2.2.2.2", now);
        assertEquals(List.of("Alt"), store.sameAddress(steve).stream().map(PunishStore.Known::name).toList());
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void theBuiltInConfigIsValid() {
        assertEquals(3, settings.escalation().size());
        assertEquals(8, settings.presets().size());
        assertEquals(Durations.PERMANENT, settings.presets().get("cheating").durationMillis());
        assertEquals(14 * 86_400_000L, settings.presets().get("xray").durationMillis());
        assertTrue(settings.mutedCommands().contains("msg"));
    }

    @Test
    void badConfigIsExplained() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("presets.7d.reason", "looks like a time");
        yaml.set("presets.7d.duration", "1d");
        yaml.set("warnings.escalation", List.of(java.util.Map.of("warns", 0, "action", "WARN")));
        yaml.set("screens.ban", List.of());
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("presets.7d") && all.contains("escalation") && all.contains("screens.ban"), all);
    }
}
