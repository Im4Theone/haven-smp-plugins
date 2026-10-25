package net.kaloyan.smpauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.logging.log4j.core.Filter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthTest {

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- passwords

    @Test
    void aPasswordIsStoredOnlyAsASaltedHashAndChecksOut() {
        String h1 = Passwords.hash("correct horse battery");
        String h2 = Passwords.hash("correct horse battery");
        assertNotEquals(h1, h2, "a new salt every time");
        assertFalse(h1.contains("correct"));
        assertTrue(h1.startsWith("pbkdf2$" + Passwords.ITERATIONS + "$"));
        assertTrue(Passwords.verify("correct horse battery", h1));
        assertTrue(Passwords.verify("correct horse battery", h2));
        assertFalse(Passwords.verify("correct horse batterY", h1));
        assertFalse(Passwords.verify("", h1));
    }

    @Test
    void brokenStoredValuesNeverMatch() {
        assertFalse(Passwords.verify("x", null));
        assertFalse(Passwords.verify("x", "plain"));
        assertFalse(Passwords.verify("x", "pbkdf2$abc$AAAA$AAAA"));
        assertFalse(Passwords.verify("x", "pbkdf2$1$AAAA$AAAA"), "too few iterations");
        assertFalse(Passwords.verify("x", "pbkdf2$310000$%%%$AAAA"));
        assertFalse(Passwords.verify("x", Passwords.dummy()), "the dummy hash matches nothing");
    }

    @Test
    void passwordRules() {
        assertEquals(Passwords.Problem.TOO_SHORT, Passwords.check("short", "Steve", 8, 64));
        assertEquals(Passwords.Problem.TOO_LONG, Passwords.check("a".repeat(65), "Steve", 8, 64));
        assertEquals(Passwords.Problem.SAME_AS_NAME, Passwords.check("stevesteve", "StEvEsTeVe", 8, 64));
        assertEquals(Passwords.Problem.TOO_COMMON, Passwords.check("Password", "Steve", 8, 64));
        assertEquals(Passwords.Problem.TOO_COMMON, Passwords.check("12345678", "Steve", 8, 64));
        assertEquals(Passwords.Problem.BAD_CHARACTERS, Passwords.check("two words here", "Steve", 8, 64));
        assertEquals(Passwords.Problem.NONE, Passwords.check("Tr0ub4dor&3", "Steve", 8, 64));
    }

    // ---------------------------------------------------------------- guessing

    @Test
    void tooManyWrongPasswordsLockThenTheLockEndsAndARightOneClears() {
        Throttle t = new Throttle(3, 600_000, 300_000);
        assertEquals(0, t.locked("ip:1", 0));
        assertFalse(t.fail("ip:1", 0));
        assertFalse(t.fail("ip:1", 1000));
        assertTrue(t.fail("ip:1", 2000));
        assertEquals(300_000 - 1000, t.locked("ip:1", 3000));
        assertEquals(0, t.locked("ip:2", 3000), "other keys are unaffected");
        assertEquals(0, t.locked("ip:1", 2000 + 300_000));
        t.fail("ip:3", 0);
        t.fail("ip:3", 1);
        t.clear("ip:3");
        assertFalse(t.fail("ip:3", 2), "a right password started the count again");
    }

    @Test
    void oldFailuresFallOutOfTheWindow() {
        Throttle t = new Throttle(3, 1000, 5000);
        t.fail("k", 0);
        t.fail("k", 100);
        assertFalse(t.fail("k", 5000), "the first two are too old");
    }

    // ---------------------------------------------------------------- sessions

    @Test
    void aSessionIsForTheSameNameAndAddressAndEnds() {
        Sessions s = new Sessions();
        s.start("Steve", "1.1.1.1", 1000, 600_000);
        assertTrue(s.valid("steve", "1.1.1.1", 2000));
        assertFalse(s.valid("Steve", "2.2.2.2", 2000), "another address");
        assertFalse(s.valid("Alex", "1.1.1.1", 2000), "another name");
        assertFalse(s.valid("Steve", "1.1.1.1", 1000 + 600_001), "too late");
        s.start("Alex", "1.1.1.1", 0, 600_000);
        s.end("Alex");
        assertFalse(s.valid("Alex", "1.1.1.1", 1));
        s.start("Zed", "1.1.1.1", 0, 0);
        assertFalse(s.valid("Zed", "1.1.1.1", 1), "sessions switched off");
    }

    // ---------------------------------------------------------------- accounts

    @Test
    void accountsSurviveARestartAndAFailedSaveChangesNothing() throws IOException {
        Path file = dir.resolve("auth.yml");
        AccountStore s = new AccountStore(file);
        s.register("Steve", "hash1", "1.1.1.1", 5);
        s.markPremium("Notch");
        s.saveIfDirty();
        AccountStore again = new AccountStore(file);
        again.load();
        assertEquals("hash1", again.get("sTEVE").hash());
        assertEquals("Steve", again.get("steve").name());
        assertTrue(again.isPremiumName("NOTCH"));
        assertEquals(1, again.countByIp("1.1.1.1"));
        Files.createDirectory(dir.resolve("auth.yml.tmp"));
        assertThrows(IOException.class, () -> again.register("Alex", "h", "2.2.2.2", 6));
        assertNull(again.get("Alex"));
        assertThrows(IOException.class, () -> again.setHash("Steve", "other"));
        assertEquals("hash1", again.get("Steve").hash());
        assertThrows(IOException.class, () -> again.remove("Steve"));
        assertNotNull(again.get("Steve"));
    }

    @Test
    void aDamagedFileStopsLoadingSoNobodyCanTakeAName() throws IOException {
        Files.writeString(dir.resolve("auth.yml"), "accounts:\n  steve:\n    name: Steve\n");
        assertThrows(IOException.class, () -> new AccountStore(dir.resolve("auth.yml")).load());
        Files.writeString(dir.resolve("auth.yml"), "accounts:\n  steve:\n    name: Other\n    hash: x\n");
        assertThrows(IOException.class, () -> new AccountStore(dir.resolve("auth.yml")).load());
    }

    // ---------------------------------------------------------------- who is verified

    @Test
    void onlyMojangMadeUuidsAreVerified() {
        assertTrue(SMPAuthPlugin.verified(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")), "a real (version 4) UUID");
        assertFalse(SMPAuthPlugin.verified(UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(StandardCharsets.UTF_8))), "a name based UUID of a cracked client");
    }

    // ---------------------------------------------------------------- what players may do before logging in

    @Test
    void onlyLoginAndRegisterWorkBeforeLoggingIn() {
        for (String ok : new String[] {"login", "LOGIN", "l", "register", "reg", "smp-auth:login", "smpauth:register"}) {
            assertTrue(Guard.allowedBeforeLogin(ok), ok);
        }
        for (String no : new String[] {"msg", "tp", "op", "changepassword", "unregister", "auth", "ban", "log", "loginx", "gamemode"}) {
            assertFalse(Guard.allowedBeforeLogin(no), no);
        }
    }

    // ---------------------------------------------------------------- the log

    @Test
    void passwordCommandsNeverReachTheLog() {
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /login hunter22"));
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /Register hunter22 hunter22"));
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /reg hunter22 hunter22"));
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /l hunter22"));
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /changepassword old new"));
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /unregister hunter22"));
        assertTrue(PasswordLogFilter.sensitive("Steve issued server command: /smp-auth:login hunter22"));
        assertTrue(PasswordLogFilter.sensitive("Alex issued server command: /auth setpassword Steve newpass123"));
        assertFalse(PasswordLogFilter.sensitive("Steve issued server command: /home base"));
        assertFalse(PasswordLogFilter.sensitive("Steve issued server command: /logout"));
        assertFalse(PasswordLogFilter.sensitive("Steve issued server command: /auth info Steve"));
        assertFalse(PasswordLogFilter.sensitive("Steve joined the game"));
        assertFalse(PasswordLogFilter.sensitive(null));
        assertEquals(Filter.Result.DENY, PasswordLogFilter.decide("Steve issued server command: /login hunter22"));
        assertEquals(Filter.Result.NEUTRAL, PasswordLogFilter.decide("Steve issued server command: /spawn"));
    }

    // ---------------------------------------------------------------- proving a bought account

    @Test
    void theServerHashIsMinecraftsSignedHex() {
        // The well known examples of the protocol: the server id is the text, secret and key are empty.
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", Mojang.serverHash("Notch", new byte[0], new byte[0]));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", Mojang.serverHash("jeb_", new byte[0], new byte[0]));
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", Mojang.serverHash("simon", new byte[0], new byte[0]));
    }

    @Test
    void aProfileAnswerIsReadAndBrokenOnesAreRefused() {
        Mojang.Profile p = Mojang.parseProfile("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":"
                + "[{\"name\":\"textures\",\"value\":\"abc\",\"signature\":\"sig\"}]}");
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), p.id());
        assertEquals("Notch", p.name());
        assertEquals("textures", p.properties().get(0).name());
        assertEquals("sig", p.properties().get(0).signature());
        assertTrue(SMPAuthPlugin.verified(p.id()));
        assertEquals(0, Mojang.parseProfile("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}").properties().size());
        assertThrows(IllegalArgumentException.class, () -> Mojang.parseProfile("not json"));
        assertThrows(IllegalArgumentException.class, () -> Mojang.parseProfile("{\"id\":\"xyz\",\"name\":\"Notch\"}"));
        assertThrows(IllegalArgumentException.class, () -> Mojang.parseProfile("{\"name\":\"Notch\"}"));
    }

    private static Gate gate(boolean detect, java.util.Set<String> flagged, java.util.Set<String> accounts, java.util.Map<String, Mojang.Lookup> mojang,
                             java.util.concurrent.atomic.AtomicInteger asked) {
        return new Gate(true, detect, n -> flagged.contains(n.toLowerCase()), n -> accounts.contains(n.toLowerCase()), n -> {
            asked.incrementAndGet();
            return java.util.concurrent.CompletableFuture.completedFuture(mojang.getOrDefault(n.toLowerCase(), Mojang.Lookup.MISSING));
        }, new Throttle(3, 60_000, 60_000));
    }

    @Test
    void onlyNamesOfBoughtAccountsHaveToProveThem() throws Exception {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        Gate g = gate(true, java.util.Set.of("flagged"), java.util.Set.of("registered"),
                java.util.Map.of("notch", Mojang.Lookup.EXISTS, "registered", Mojang.Lookup.EXISTS, "broken", Mojang.Lookup.ERROR), asked);
        assertEquals(Gate.Verdict.VERIFY, g.decide("Flagged", "1.1.1.1", 0).get(), "flagged names always");
        assertEquals(Gate.Verdict.PASS, g.decide("Registered", "1.1.1.1", 0).get(), "a password account here logs in with the password");
        assertEquals(0, asked.get(), "those did not need Mojang");
        assertEquals(Gate.Verdict.VERIFY, g.decide("Notch", "1.1.1.1", 0).get(), "an unknown name that a bought account has");
        assertEquals(Gate.Verdict.PASS, g.decide("FreeName", "1.1.1.1", 0).get(), "a free name registers");
        assertEquals(Gate.Verdict.UNKNOWN, g.decide("Broken", "1.1.1.1", 0).get(), "Mojang down: no guessing");
        assertEquals(Gate.Verdict.VERIFY, g.decide("notch", "2.2.2.2", 5).get());
        assertEquals(3, asked.get(), "the answers were remembered");
    }

    @Test
    void lookupsAreLimitedPerAddressAndCanBeSwitchedOff() throws Exception {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        Gate g = gate(true, java.util.Set.of(), java.util.Set.of(), java.util.Map.of(), asked);
        assertEquals(Gate.Verdict.PASS, g.decide("a1", "9.9.9.9", 0).get());
        assertEquals(Gate.Verdict.PASS, g.decide("a2", "9.9.9.9", 1).get());
        assertEquals(Gate.Verdict.PASS, g.decide("a3", "9.9.9.9", 2).get());
        assertEquals(Gate.Verdict.BUSY, g.decide("a4", "9.9.9.9", 3).get(), "the fourth name from one address in a minute");
        assertEquals(Gate.Verdict.PASS, g.decide("a4", "8.8.8.8", 3).get(), "other addresses are fine");
        Gate noDetect = gate(false, java.util.Set.of("x"), java.util.Set.of(), java.util.Map.of("notch", Mojang.Lookup.EXISTS), new java.util.concurrent.atomic.AtomicInteger());
        assertEquals(Gate.Verdict.PASS, noDetect.decide("Notch", "1.1.1.1", 0).get());
        assertEquals(Gate.Verdict.VERIFY, noDetect.decide("X", "1.1.1.1", 0).get(), "flagged names are still checked");
        Gate off = new Gate(false, true, n -> true, n -> false, n -> null, new Throttle(1, 1, 1));
        assertEquals(Gate.Verdict.PASS, off.decide("X", "1.1.1.1", 0).get());
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void theBuiltInConfigIsValidAndBadOnesAreExplained() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = AuthTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Settings s = Settings.parse(yaml);
        assertTrue(s.premiumAuto() && s.protectNames());
        assertEquals(8, s.minLength());
        assertEquals(5, s.maxAttempts());
        assertEquals(10 * 60_000L, s.sessionMillis());
        yaml.set("password.min-length", 1);
        yaml.set("login.timeout-seconds", 1);
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("min-length") && all.contains("timeout-seconds"), all);
    }
}
