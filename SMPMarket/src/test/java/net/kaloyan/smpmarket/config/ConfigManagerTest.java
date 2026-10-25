package net.kaloyan.smpmarket.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.kaloyan.smpmarket.testutil.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests: configuration defaults, validation and all-or-nothing reload. */
class ConfigManagerTest {

    @TempDir
    Path dir;

    private final LogCapture log = new LogCapture();

    private ConfigManager manager(String yaml) throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, yaml);
        return new ConfigManager(file, Runnable::run, log.logger());
    }

    @Test
    void bundledConfigEqualsTheDocumentedDefaults() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            Files.write(dir.resolve("config.yml"), in.readAllBytes());
        }
        ConfigManager m = new ConfigManager(dir.resolve("config.yml"), Runnable::run, log.logger());
        assertEquals(MarketSettings.defaults(), m.loadInitial());
        MarketSettings d = MarketSettings.defaults();
        assertEquals(0, d.feePercentHundredths(), "fee defaults to zero");
        assertEquals(Duration.ofHours(72), d.duration());
        assertFalse(d.allowSelfPurchase());
    }

    @Test
    void emptyFileUsesDefaults() throws Exception {
        assertEquals(MarketSettings.defaults(), manager("# nothing\n").loadInitial());
    }

    @Test
    void validValuesAreApplied() throws Exception {
        MarketSettings s = manager("""
                listing:
                  max-per-player: 3
                  duration-hours: 24
                  min-price: "1.00"
                  max-price: "500.50"
                  min-amount: 2
                  max-amount: 16
                fee:
                  percent: "2.5"
                purchase:
                  allow-self-purchase: true
                restrictions:
                  blocked-materials: ["*_shulker_box", "bedrock"]
                logging:
                  debug: true
                """).loadInitial();
        assertEquals(3, s.maxPerPlayer());
        assertEquals(Duration.ofHours(24), s.duration());
        assertEquals(100, s.minPriceMinor());
        assertEquals(50050, s.maxPriceMinor());
        assertEquals(250, s.feePercentHundredths());
        assertTrue(s.allowSelfPurchase());
        assertTrue(s.debug());
        assertTrue(s.isBlocked("minecraft:blue_shulker_box"));
        assertTrue(s.isBlocked("BEDROCK"));
        assertFalse(s.isBlocked("minecraft:stone"));
    }

    @Test
    void everyInvalidValueIsReportedAtOnce() throws Exception {
        ConfigManager m = manager("""
                listing:
                  max-per-player: 0
                  duration-hours: "soon"
                  min-price: "-1"
                  max-price: "1.234"
                  min-amount: 10
                  max-amount: 5
                fee:
                  percent: "51"
                purchase:
                  allow-self-purchase: maybe
                restrictions:
                  blocked-materials: ["NOT_A_REAL_MATERIAL", "bad name"]
                """);
        InvalidConfigException e = assertThrows(InvalidConfigException.class, m::loadInitial);
        String all = String.join("\n", e.problems());
        for (String key : new String[] {"max-per-player", "duration-hours", "min-price", "max-price", "min-amount",
                "fee.percent", "allow-self-purchase", "NOT_A_REAL_MATERIAL", "bad name"}) {
            assertTrue(all.contains(key), "missing a diagnostic for " + key + " in:\n" + all);
        }
    }

    @Test
    void priceOrderingIsChecked() throws Exception {
        ConfigManager m = manager("listing:\n  min-price: \"10\"\n  max-price: \"5\"\n");
        assertTrue(assertThrows(InvalidConfigException.class, m::loadInitial).getMessage().contains("must not be greater"));
    }

    @Test
    void malformedYamlAndMissingFileAreDiagnosedNotCrashed() throws Exception {
        assertThrows(InvalidConfigException.class, manager("listing: [unclosed\n  - x: : :\n")::loadInitial);
        ConfigManager missing = new ConfigManager(dir.resolve("nope.yml"), Runnable::run, log.logger());
        assertTrue(assertThrows(InvalidConfigException.class, missing::loadInitial).getMessage().contains("not found"));
    }

    @Test
    void failedReloadKeepsThePreviousSettingsAndAGoodReloadReplacesThem() throws Exception {
        ConfigManager m = manager("listing:\n  max-per-player: 4\n");
        MarketSettings before = m.loadInitial();

        Files.writeString(dir.resolve("config.yml"), "listing:\n  max-per-player: -9\nfee:\n  percent: \"x\"\n");
        ReloadResult bad = m.reload().get(5, TimeUnit.SECONDS);
        assertFalse(bad.success());
        assertFalse(bad.problems().isEmpty());
        assertEquals(before, m.current(), "the active settings did not change");
        assertEquals(before, bad.settings());

        Files.writeString(dir.resolve("config.yml"), "listing:\n  max-per-player: 7\n");
        ReloadResult good = m.reload().get(5, TimeUnit.SECONDS);
        assertTrue(good.success());
        assertEquals(7, m.current().maxPerPlayer());

        Files.writeString(dir.resolve("config.yml"), "{{{{ not yaml");
        assertFalse(m.reload().get(5, TimeUnit.SECONDS).success());
        assertEquals(7, m.current().maxPerPlayer());
    }

    @Test
    void currentBeforeLoadIsAnError() throws Exception {
        ConfigManager m = manager("");
        assertThrows(IllegalStateException.class, m::current);
    }
}
