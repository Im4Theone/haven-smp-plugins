package net.kaloyan.smpkits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KitsTest {

    @TempDir
    Path dir;

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = KitsTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static Kit kit(long cooldown) {
        return new Kit("settler", "Settler", "Settler", Material.IRON_PICKAXE, 11, cooldown, List.of(), List.of("say hi"));
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void theBuiltInConfigHasOneKitPerRankIncludingWandererAndNothingForAdminAndFounder() throws Exception {
        Settings s = Settings.parse(bundled());
        assertEquals(List.of("wanderer", "settler", "keeper", "beacon", "lookout", "warden"), List.copyOf(s.kits().keySet()));
        assertEquals(java.util.Set.of("guardian"), s.excludedGroups(), "the Founder plays too");
        assertFalse(s.kits().containsKey("guardian") || s.kits().containsKey("founder"));
        for (Kit k : s.kits().values()) {
            boolean netherite = k.id().equals("lookout") || k.id().equals("warden");
            assertEquals(netherite ? 3 * 86_400_000L : 86_400_000L, k.cooldownMillis(), k.id());
        }
        assertEquals("smpkits.kit.beacon", s.kits().get("beacon").permission());
        assertEquals(Map.of("efficiency", 5, "unbreaking", 3, "fortune", 3, "mending", 1), s.kits().get("warden").items().get(5).enchants());
    }

    @Test
    void cooldownsAreReadAndBadOnesAreRefused() {
        assertEquals(Kit.ONCE, Settings.cooldown("once"));
        assertEquals(90 * 60_000L, Settings.cooldown("1h30m"));
        assertEquals(7 * 86_400_000L, Settings.cooldown("1w"));
        assertEquals(0, Settings.cooldown("soon"));
        assertEquals(0, Settings.cooldown("5x"));
        assertEquals(0, Settings.cooldown(""));
    }

    @Test
    void badConfigListsEveryProblem() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("kits.keeper.slot", 11);
        yaml.set("kits.beacon.cooldown", "never");
        yaml.set("kits.lookout.icon", "NOT_AN_ITEM");
        yaml.set("kits.warden.items", List.of(Map.of("item", "DIAMOND", "amount", 100)));
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("kits.keeper.slot") && all.contains("kits.beacon.cooldown") && all.contains("kits.lookout.icon")
                && all.contains("kits.warden.items[1].amount"), all);
    }

    // ---------------------------------------------------------------- cooldowns

    @Test
    void aKitIsReadyThenOnCooldownThenReadyAgain() throws IOException {
        Claims claims = new Claims(dir.resolve("claims.yml"));
        UUID id = UUID.randomUUID();
        Kit k = kit(86_400_000L);
        assertEquals(Claims.State.READY, claims.status(id, k, 1000).state());
        claims.claim(id, "settler", 1000);
        Claims.Status wait = claims.status(id, k, 1000 + 3_600_000L);
        assertEquals(Claims.State.COOLDOWN, wait.state());
        assertEquals(86_400_000L - 3_600_000L, wait.remaining());
        assertEquals(Claims.State.READY, claims.status(id, k, 1000 + 86_400_000L).state());
        assertEquals(Claims.State.READY, claims.status(UUID.randomUUID(), k, 1000 + 1).state(), "other players are unaffected");
    }

    @Test
    void aOneTimeKitCanBeClaimedOnlyOnceUntilAnAdminResetsIt() throws IOException {
        Claims claims = new Claims(dir.resolve("claims.yml"));
        UUID id = UUID.randomUUID();
        Kit once = kit(Kit.ONCE);
        claims.claim(id, "settler", 5);
        assertEquals(Claims.State.USED, claims.status(id, once, 10_000_000_000L).state());
        assertTrue(claims.reset(id, "settler"));
        assertEquals(Claims.State.READY, claims.status(id, once, 10).state());
        assertFalse(claims.reset(id, "settler"));
    }

    @Test
    void claimsSurviveARestart() throws IOException {
        Path file = dir.resolve("claims.yml");
        Claims claims = new Claims(file);
        UUID id = UUID.randomUUID();
        claims.claim(id, "settler", 1000);
        claims.claim(id, "warden", 2000);
        Claims again = new Claims(file);
        again.load();
        assertEquals(Claims.State.COOLDOWN, again.status(id, kit(86_400_000L), 5000).state());
    }

    @Test
    void ifSavingFailsTheClaimDoesNotCount() throws IOException {
        Claims claims = new Claims(dir.resolve("claims.yml"));
        UUID id = UUID.randomUUID();
        Files.createDirectory(dir.resolve("claims.yml.tmp"));
        assertThrows(IOException.class, () -> claims.claim(id, "settler", 1000));
        assertEquals(Claims.State.READY, claims.status(id, kit(86_400_000L), 1001).state());
    }

    @Test
    void aDamagedClaimsFileStopsLoading() throws IOException {
        Files.writeString(dir.resolve("claims.yml"), "claims:\n  not-a-uuid:\n    settler: 5\n");
        assertThrows(IOException.class, () -> new Claims(dir.resolve("claims.yml")).load());
    }
}
