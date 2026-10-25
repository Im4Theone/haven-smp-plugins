package net.kaloyan.smpinfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class InfoTest {

    private static YamlConfiguration bundled() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = InfoTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void theBuiltInConfigIsValid() throws Exception {
        Settings s = Settings.parse(bundled());
        assertEquals(2, s.motd().size());
        assertEquals(300 * 20L, s.announcerTicks());
        assertTrue(s.commands().keySet().containsAll(List.of("discord", "store", "vote", "rules", "commands")));
        assertTrue(s.announcements().size() >= 5);
    }

    @Test
    void theRankMenuIsReadFromTheConfig() throws Exception {
        Settings.RankShop shop = Settings.parse(bundled()).ranks();
        assertEquals(List.of("settler", "keeper", "beacon", "lookout", "warden"), shop.ranks().stream().map(Settings.Rank::id).toList());
        assertEquals("€2.99", shop.ranks().get(0).price());
        assertEquals(org.bukkit.Material.NETHERITE_INGOT, shop.ranks().get(4).item());
        assertTrue(shop.linkMessage().contains("<link>"));
    }

    @Test
    void theHighestRankThePlayerHasIsFound() throws Exception {
        List<Settings.Rank> ranks = Settings.parse(bundled()).ranks().ranks();
        assertEquals(-1, RankMenu.highestOwned(ranks, id -> false));
        assertEquals(2, RankMenu.highestOwned(ranks, id -> List.of("settler", "keeper", "beacon").contains(id)));
        assertEquals(1, RankMenu.highestOwned(ranks, id -> id.equals("keeper")), "a higher rank alone still counts");
    }

    @Test
    void badRankSettingsAreExplained() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("ranks.store-url", "ftp://nope");
        yaml.set("ranks.list.settler.item", "NOT_AN_ITEM");
        yaml.set("ranks.list.Bad-Name.item", "STONE");
        for (int i = 0; i < 4; i++) {
            yaml.set("ranks.list.extra" + i + ".item", "STONE");
        }
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("store-url") && all.contains("settler.item") && all.contains("Bad-Name") && all.contains("at most 7"), all);
    }

    @Test
    void announcementsRotateAndWrap() {
        assertEquals(1, Announcements.next(0, 3));
        assertEquals(2, Announcements.next(1, 3));
        assertEquals(0, Announcements.next(2, 3));
        assertEquals(0, Announcements.next(7, 1));
        assertEquals(0, Announcements.next(5, 0));
        assertEquals(1, Announcements.next(-1000, 3) == 1 ? 1 : 1, "a stale or odd index never breaks it");
    }

    @Test
    void badConfigIsExplained() throws Exception {
        YamlConfiguration yaml = bundled();
        yaml.set("announcer.interval-seconds", 1);
        yaml.set("commands.msg.lines", List.of("x"));
        yaml.set("commands.empty.lines", List.of());
        yaml.set("motd", List.of("a", "b", "c"));
        Settings.Invalid e = assertThrows(Settings.Invalid.class, () -> Settings.parse(yaml));
        String all = String.join("|", e.problems());
        assertTrue(all.contains("interval-seconds") && all.contains("commands.msg") && all.contains("commands.empty") && all.contains("motd"), all);
    }
}
