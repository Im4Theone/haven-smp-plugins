package net.kaloyan.smppunish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class RenderTest {

    private static Settings settings() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = RenderTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return Settings.parse(yaml);
    }

    @Test
    void theBanScreenShowsEverythingAndAcceptsNoMarkupFromTheReason() throws Exception {
        Settings s = settings();
        long now = 1_000_000_000L;
        Punishment p = new Punishment(7, Punishment.Type.BAN, UUID.randomUUID(), "Steve", null, "<red>Alex", "<click:run_command:/op me>x-ray</click>", now,
                now + 2 * 86_400_000L);
        var screen = Render.screen(s.banScreen(), Render.values(p, now, s));
        String text = PlainTextComponentSerializer.plainText().serialize(screen);
        assertTrue(text.contains("You are banned from Haven SMP"), text);
        assertTrue(text.contains("Reason: <click:run_command:/op me>x-ray</click>"), "the reason is shown as plain text: " + text);
        assertTrue(text.contains("Banned by: <red>Alex"), text);
        assertTrue(text.contains("Ban ID: #7"), text);
        assertTrue(text.contains("2d"), text);
        assertTrue(text.contains("UTC"), text);
        assertFalse(screen.children().stream().anyMatch(c -> c.clickEvent() != null), "no click events from typed text");
    }

    @Test
    void aPermanentBanSaysNever() throws Exception {
        Settings s = settings();
        Punishment p = new Punishment(1, Punishment.Type.BAN, UUID.randomUUID(), "Steve", null, "Console", "", 5, 0);
        String text = PlainTextComponentSerializer.plainText().serialize(Render.screen(s.banScreen(), Render.values(p, 10, s)));
        assertTrue(text.contains("Expires: Never"), text);
        assertTrue(text.contains("No reason given"), text);
    }
}
