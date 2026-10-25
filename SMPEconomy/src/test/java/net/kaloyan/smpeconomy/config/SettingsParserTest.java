package net.kaloyan.smpeconomy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsParserTest {

    private static EconomySettings parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError("test YAML must be well-formed", e);
        }
        return SettingsParser.parse(config);
    }

    private static List<String> problems(String yaml) {
        return assertThrows(InvalidConfigException.class, () -> parse(yaml)).problems();
    }

    @Test
    void emptyConfigYieldsDefaults() throws Exception {
        EconomySettings settings = parse("");

        assertEquals(EconomySettings.defaults(), settings);
        assertEquals("$", settings.symbol());
        assertEquals(1_000_000L, settings.startingMinor());
        assertFalse(settings.debug());
    }

    @Test
    void bundledConfigFileMatchesCodeDefaults() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertTrue(in != null, "config.yml must be bundled");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));

            assertEquals(EconomySettings.defaults(), SettingsParser.parse(yaml));
        }
    }

    @Test
    void validOverridesAreApplied() throws Exception {
        EconomySettings settings = parse("""
                currency:
                  symbol: "€"
                balances:
                  starting: "250.50"
                  maximum: "5000"
                transactions:
                  maximum-amount: "1000.01"
                logging:
                  debug: true
                """);

        assertEquals("€", settings.symbol());
        assertEquals(25_050L, settings.startingMinor());
        assertEquals(500_000L, settings.maxBalanceMinor());
        assertEquals(100_001L, settings.maxTransactionMinor());
        assertTrue(settings.debug());
    }

    @Test
    void integersAndShortUnquotedDecimalsAreReadExactly() throws Exception {
        EconomySettings settings = parse("""
                balances:
                  starting: 100.1
                  maximum: 5000
                transactions:
                  maximum-amount: 0.07
                """);

        assertEquals(10_010L, settings.startingMinor());
        assertEquals(500_000L, settings.maxBalanceMinor());
        assertEquals(7L, settings.maxTransactionMinor());
    }

    @Test
    void startingBalanceOfZeroIsAllowed() throws Exception {
        assertEquals(0L, parse("balances:\n  starting: \"0\"\n").startingMinor());
    }

    @Test
    void explicitNullFallsBackToDefault() throws Exception {
        assertEquals(EconomySettings.defaults(), parse("currency:\n  symbol:\nbalances:\n  starting:\n"));
    }

    @Test
    void negativeAndZeroLimitsAreRejected() {
        assertTrue(problems("balances:\n  starting: \"-1\"\n").get(0).contains("balances.starting"));
        assertTrue(problems("balances:\n  maximum: \"0\"\n").get(0).contains("balances.maximum"));
        assertTrue(problems("transactions:\n  maximum-amount: \"0.00\"\n").get(0).contains("transactions.maximum-amount"));
        assertTrue(problems("transactions:\n  maximum-amount: \"-5\"\n").get(0).contains("transactions.maximum-amount"));
    }

    @Test
    void tooManyDecimalsOrMalformedOrOversizedAmountsAreRejectedNotRounded() {
        assertTrue(problems("balances:\n  starting: \"1.005\"\n").get(0).contains("decimal"));
        assertTrue(problems("balances:\n  starting: \"ten\"\n").get(0).contains("balances.starting"));
        assertTrue(problems("balances:\n  maximum: \"1e9\"\n").get(0).contains("balances.maximum"));
        assertTrue(problems("balances:\n  maximum: \"1000000000000000.01\"\n").get(0).contains("too large"));
        assertTrue(problems("transactions:\n  maximum-amount: \"99999999999999999999\"\n").get(0).contains("too large"));
    }

    @Test
    void largeUnquotedDecimalsAreRefusedBecauseYamlWouldHaveRoundedThem() {
        assertTrue(problems("balances:\n  maximum: 1000000000000000.5\n").get(0).contains("quotes"));
    }

    @Test
    void wrongTypesAreRejected() {
        assertTrue(problems("balances:\n  starting: true\n").get(0).contains("balances.starting"));
        assertTrue(problems("balances:\n  starting: [1, 2]\n").get(0).contains("balances.starting"));
        assertTrue(problems("currency:\n  symbol: 5\n").get(0).contains("currency.symbol"));
        assertTrue(problems("logging:\n  debug: maybe\n").get(0).contains("logging.debug"));
    }

    @Test
    void badSymbolsAreRejected() {
        assertTrue(problems("currency:\n  symbol: \"\"\n").get(0).contains("blank"));
        assertTrue(problems("currency:\n  symbol: \"   \"\n").get(0).contains("blank"));
        assertTrue(problems("currency:\n  symbol: \"123456789\"\n").get(0).contains("maximum is 8"));
        assertTrue(problems("currency:\n  symbol: \"a\\nb\"\n").get(0).contains("control"));
    }

    @Test
    void startingBalanceAboveMaximumBalanceIsRejected() {
        List<String> problems = problems("balances:\n  starting: \"500\"\n  maximum: \"100\"\n");

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("must not be greater"), problems.get(0));
    }

    @Test
    void allProblemsAreReportedTogether() {
        List<String> problems = problems("""
                currency:
                  symbol: ""
                balances:
                  starting: "x"
                transactions:
                  maximum-amount: "0"
                logging:
                  debug: 7
                """);

        assertEquals(4, problems.size(), problems.toString());
    }
}
