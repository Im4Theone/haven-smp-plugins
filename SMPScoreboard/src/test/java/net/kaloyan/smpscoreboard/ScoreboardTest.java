package net.kaloyan.smpscoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Unit tests: formatting, rendering and configuration. No server needed. */
class ScoreboardTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static BoardSettings bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ScoreboardTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return BoardSettings.parse(yaml);
    }

    @Test
    void moneyIsCompactAndNeverRoundsUpIntoTheNextUnit() {
        assertEquals("$0", Formats.money("$", BigDecimal.ZERO));
        assertEquals("$950.5", Formats.money("$", new BigDecimal("950.50")));
        assertEquals("$10k", Formats.money("$", new BigDecimal("10000")));
        assertEquals("$999.9k", Formats.money("$", new BigDecimal("999999.99")));
        assertEquals("$1.2m", Formats.money("$", new BigDecimal("1250000")));
        assertEquals("-$1.5k", Formats.money("$", new BigDecimal("-1500")));
        assertEquals("$1q", Formats.money("$", new BigDecimal("1000000000000000")));
        assertEquals("$1000q", Formats.money("$", new BigDecimal("1000000000000000000")),
                "the largest unit is q, so the table is never overrun");
    }

    @Test
    void playtimeShowsMinutesUntilTheFirstHourThenDaysAndHours() {
        assertEquals("0m", Formats.playtime(0));
        assertEquals("12m", Formats.playtime(12 * 60 * 20L));
        assertEquals("0d 1h", Formats.playtime(60 * 60 * 20L));
        assertEquals("3d 4h", Formats.playtime((3 * 24 + 4) * 60 * 60 * 20L));
        assertEquals("0m", Formats.playtime(-5));
    }

    @Test
    void defaultConfigIsValidAndHasNoFooterAndNoPurple() throws Exception {
        BoardSettings settings = bundled();
        assertEquals(11, settings.lines().size());
        assertEquals(20, settings.updateTicks());
        String all = String.join("\n", settings.lines()) + settings.title();
        assertFalse(all.toLowerCase().contains("allay"), "no website footer");
        assertFalse(all.contains(".pro") || all.contains(".net") || all.contains(".com"));
        for (String purple : List.of("#A", "#C084", "#8B5CF6", "<dark_purple>", "<light_purple>", "<purple>")) {
            assertFalse(all.contains(purple), "unexpected purple: " + purple);
        }
    }

    @Test
    void tabListAndWelcomeTitleAreReadFromTheDefaults() throws Exception {
        BoardSettings settings = bundled();
        assertTrue(settings.tab().enabled());
        assertTrue(settings.tab().sortByRank());
        assertFalse(settings.tab().header().isEmpty());
        assertEquals("<prefix><name>", settings.tab().nameFormat());
        assertTrue(settings.welcome().enabled());
        assertEquals(1750, settings.welcome().stayMs());
        assertTrue(settings.welcome().title().contains("<name>"));
    }

    @Test
    void anOlderConfigWithoutTheNewSectionsGetsTheShippedDefaults() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        try (var in = ScoreboardTest.class.getResourceAsStream("/config.yml")) {
            shipped.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        YamlConfiguration old = new YamlConfiguration();
        old.loadFromString(shipped.saveToString());
        old.set("tab", null);
        old.set("welcome", null);
        old.set("scoreboard.title", "<red>MY TITLE");
        old.setDefaults(shipped);
        BoardSettings settings = BoardSettings.parse(old);
        assertEquals("<red>MY TITLE", settings.title(), "the user's own edit is kept");
        assertFalse(settings.tab().header().isEmpty(), "missing tab section comes from the defaults");
        assertTrue(settings.welcome().enabled());
    }

    @Test
    void negativeWelcomeTimesAreRejected() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = ScoreboardTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        yaml.set("welcome.stay-ms", -1);
        BoardSettings.Invalid e = assertThrows(BoardSettings.Invalid.class, () -> BoardSettings.parse(yaml));
        assertEquals(1, e.problems().size(), e.problems().toString());
    }

    @Test
    void rendersEveryPlaceholder() throws Exception {
        BoardSettings settings = bundled();
        BoardRenderer.Stats stats = new BoardRenderer.Stats("Steve", "$10k", 7, 3, 1, 2,
                Component.text("[Admin]", NamedTextColor.RED), "0d 4h", 5, 32, 3, "12:34");
        List<Component> lines = new BoardRenderer().lines(settings, stats);
        assertEquals(settings.lines().size(), lines.size());
        String text = String.join("\n", lines.stream().map(ScoreboardTest::plain).toList());
        for (String expected : List.of("Steve", "Money: $10k", "Shards: 7", "Kills: 3", "Deaths: 1", "Streak: 2",
                "Rank: [Admin]", "Playtime: 0d 4h", "Online: 5", "32ms", "Keyall: 12:34")) {
            assertTrue(text.contains(expected), expected + " missing in:\n" + text);
        }
        assertEquals("", plain(lines.get(7)), "a blank template line is a blank row");
    }

    @Test
    void countdownShowsMinutesAndSecondsThenHours() {
        assertEquals("00:09", Formats.countdown(9));
        assertEquals("59:59", Formats.countdown(3599));
        assertEquals("60:00", Formats.countdown(3600));
        assertEquals("00:00", Formats.countdown(0));
        assertEquals("-", Formats.countdown(-1));
    }

    @Test
    void playerNamesCannotInjectFormatting() throws Exception {
        BoardSettings settings = bundled();
        BoardRenderer.Stats stats = new BoardRenderer.Stats("<red><bold>Evil</bold>", "$1", 0, 0, 0, 0,
                Component.empty(), "0m", 1, 1, 0, "-");
        Component first = new BoardRenderer().lines(settings, stats).get(0);
        assertEquals("<red><bold>Evil</bold>", plain(first), "the name is shown literally");
    }

    @Test
    void joinAndLeaveMessagesRenderLikeTheReference() throws Exception {
        BoardSettings settings = bundled();
        var mini = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
        var name = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", "_ishitmypants_");
        assertEquals("+ _ishitmypants_ ʜᴀꜱ ᴊᴏɪɴᴇᴅ", plain(mini.deserialize(settings.joinMessage(), name)));
        assertEquals("- _ishitmypants_ ʜᴀꜱ ʟᴇꜰᴛ", plain(mini.deserialize(settings.quitMessage(), name)));
        var evil = net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("name", "<red>x");
        assertTrue(plain(mini.deserialize(settings.joinMessage(), evil)).contains("<red>x"), "names cannot inject tags");
    }

    @Test
    void prefixesSupportHexAndLegacyColoursAndBold() {
        var admin = Prefixes.parse("&#F87171&lAdmin");
        assertEquals("Admin", plain(admin));
        var styled = admin.children().isEmpty() ? admin : (net.kyori.adventure.text.TextComponent) admin.children().get(0);
        assertEquals(net.kyori.adventure.text.format.TextColor.fromHexString("#F87171"), styled.color());
        assertTrue(styled.hasDecoration(net.kyori.adventure.text.format.TextDecoration.BOLD));
        assertEquals(NamedTextColor.GRAY, Prefixes.parse("&7Member").color());
        assertEquals("Member", plain(Prefixes.parse("&#9CA3AFMember")));
    }

    @Test
    void invalidConfigurationListsEveryProblem() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("scoreboard.title", "");
        yaml.set("scoreboard.update-ticks", 1);
        yaml.set("scoreboard.lines", List.of());
        BoardSettings.Invalid e = assertThrows(BoardSettings.Invalid.class, () -> BoardSettings.parse(yaml));
        assertEquals(3, e.problems().size(), e.problems().toString());

        YamlConfiguration tooMany = new YamlConfiguration();
        tooMany.set("scoreboard.title", "T");
        tooMany.set("scoreboard.lines", java.util.Collections.nCopies(16, "x"));
        assertTrue(assertThrows(BoardSettings.Invalid.class, () -> BoardSettings.parse(tooMany)).getMessage().contains("16"));
    }
}
