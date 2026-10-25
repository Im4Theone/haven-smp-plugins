package net.kaloyan.smpcore.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import net.kaloyan.smpcore.api.CoreSettings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsParserTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private static InvalidConfigException rejected(String text) throws InvalidConfigurationException {
        YamlConfiguration config = yaml(text);
        return assertThrows(InvalidConfigException.class, () -> SettingsParser.parse(config));
    }

    @Test
    void emptyConfigYieldsDefaults() throws Exception {
        assertEquals(CoreSettings.defaults(), SettingsParser.parse(yaml("")));
    }

    @Test
    void bundledConfigFileMatchesCodeDefaults() throws IOException, InvalidConfigException {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "bundled config.yml must be on the classpath");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(reader);
                assertEquals(CoreSettings.defaults(), SettingsParser.parse(bundled));
            }
        }
    }

    @Test
    void missingKeysKeepTheirDefaults() throws Exception {
        CoreSettings parsed = SettingsParser.parse(yaml("logging:\n  debug: true\n"));

        assertTrue(parsed.debug());
        assertEquals(CoreSettings.DEFAULT_PREFIX, parsed.prefix());
        assertTrue(parsed.logPlayerJoin());
        assertTrue(parsed.logPlayerQuit());
    }

    @Test
    void explicitNullValueFallsBackToDefault() throws Exception {
        assertEquals(CoreSettings.defaults(), SettingsParser.parse(yaml("prefix:\nlogging:\n  debug:\n")));
    }

    @Test
    void validOverridesAreApplied() throws Exception {
        CoreSettings parsed = SettingsParser.parse(yaml("""
                prefix: "<green>[Test]</green>"
                logging:
                  debug: true
                  player-join: false
                  player-quit: false
                """));

        assertEquals(new CoreSettings("<green>[Test]</green>", true, false, false), parsed);
    }

    @Test
    void blankPrefixIsRejected() throws Exception {
        InvalidConfigException e = rejected("prefix: \"   \"\n");
        assertEquals(1, e.problems().size());
        assertTrue(e.problems().get(0).contains("prefix"));
    }

    @Test
    void nonTextPrefixIsRejected() throws Exception {
        InvalidConfigException e = rejected("prefix:\n  - a\n  - b\n");
        assertTrue(e.problems().get(0).contains("must be text"));
    }

    @Test
    void overlongPrefixIsRejected() throws Exception {
        InvalidConfigException e = rejected("prefix: \"" + "x".repeat(SettingsParser.MAX_PREFIX_LENGTH + 1) + "\"\n");
        assertTrue(e.problems().get(0).contains("maximum"));
    }

    @Test
    void prefixAtTheLengthLimitIsAccepted() throws Exception {
        String prefix = "x".repeat(SettingsParser.MAX_PREFIX_LENGTH);
        assertEquals(prefix, SettingsParser.parse(yaml("prefix: \"" + prefix + "\"\n")).prefix());
    }

    @Test
    void malformedMiniMessageIsRejected() throws Exception {
        InvalidConfigException e = rejected("prefix: \"<red>[SMP]\"\n"); // tag never closed
        assertTrue(e.problems().get(0).contains("MiniMessage"), e.problems().get(0));
    }

    @Test
    void nonBooleanValuesAreRejected() throws Exception {
        InvalidConfigException e = rejected("logging:\n  debug: maybe\n");
        assertTrue(e.problems().get(0).contains("logging.debug"));
        assertTrue(e.problems().get(0).contains("true or false"));
    }

    @Test
    void allProblemsAreReportedTogether() throws Exception {
        InvalidConfigException e = rejected("""
                prefix: ""
                logging:
                  debug: 3
                  player-join: nope
                  player-quit: false
                """);

        assertEquals(3, e.problems().size(), e.problems().toString());
    }
}
