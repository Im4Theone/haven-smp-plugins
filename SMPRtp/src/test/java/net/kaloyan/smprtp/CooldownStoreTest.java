package net.kaloyan.smprtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CooldownStoreTest {

    @TempDir
    Path dir;

    @Test
    void cooldownsSurviveARestartAndExpiredOnesAreDropped() throws IOException {
        CooldownStore store = new CooldownStore(dir.resolve("cooldowns.yml"));
        UUID active = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        Map<UUID, Long> ends = new HashMap<>();
        ends.put(active, 10_000L);
        ends.put(expired, 500L);
        store.save(ends, 1_000L);
        Map<UUID, Long> loaded = store.load(2_000L);
        assertEquals(Map.of(active, 10_000L), loaded);
        assertTrue(store.load(20_000L).isEmpty(), "everything has ended by then");
        assertFalse(Files.exists(dir.resolve("cooldowns.yml.tmp")));
    }

    @Test
    void aMissingFileIsEmptyAndABrokenFileIsAnError() throws IOException {
        assertTrue(new CooldownStore(dir.resolve("none.yml")).load(0).isEmpty());
        Path broken = dir.resolve("broken.yml");
        Files.writeString(broken, "cooldowns: [oops\n");
        assertThrows(IOException.class, () -> new CooldownStore(broken).load(0));
        Files.writeString(broken, "cooldowns:\n  not-a-uuid: 99999999999\n");
        assertThrows(IOException.class, () -> new CooldownStore(broken).load(0));
    }

    @Test
    void shippedDefaultsAvoidProtectedRegions() throws Exception {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        try (var in = CooldownStoreTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(RtpSettings.parse(yaml).avoidProtected());
    }
}
