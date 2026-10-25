package net.kaloyan.smpworth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class WorthTest {

    private static PriceSheet bundled() throws IOException {
        try (Reader in = new InputStreamReader(WorthTest.class.getResourceAsStream("/prices.json"), StandardCharsets.UTF_8)) {
            return PriceSheet.parse(in);
        }
    }

    // ---------------------------------------------------------------- the price sheet

    @Test
    void theBundledSheetHasEveryItemOfTheOriginal() throws Exception {
        PriceSheet sheet = bundled();
        assertEquals(1404, sheet.size());
        assertEquals(900.0, sheet.worth("diamond_pickaxe", Map.of()).orElseThrow());
        assertEquals(15.0, sheet.worth("cobblestone", Map.of()).orElseThrow());
        assertEquals(0.0, sheet.worth("spawner", Map.of()).orElseThrow(), "listed with a zero value");
        assertTrue(sheet.worth("not_an_item", Map.of()).isEmpty());
        assertTrue(sheet.knows("spawner"));
        assertFalse(sheet.knows("not_an_item"));
    }

    @Test
    void enchantmentsAddTheirBonusPerLevelToTheBaseValue() throws Exception {
        PriceSheet sheet = bundled();
        // diamond pickaxe 90 + efficiency 5*5 + unbreaking 3*7 + fortune 3*14 + mending 1*35 = 213
        assertEquals(2130.0, sheet.worth("diamond_pickaxe", Map.of("efficiency", 5, "unbreaking", 3, "fortune", 3, "mending", 1)).orElseThrow());
        // an enchanted book is priced as the book plus what is stored in it: 12 + sharpness 5*6 = 42
        assertEquals(420.0, sheet.worth("enchanted_book", Map.of("sharpness", 5)).orElseThrow());
        // netherite sword 300 + sharpness 5 (30) + fire aspect 2 (14) + looting 3 (36) = 380
        assertEquals(3800.0, sheet.worth("netherite_sword", Map.of("sharpness", 5, "fire_aspect", 2, "looting", 3)).orElseThrow());
    }

    @Test
    void cursesAreCheaperAndMinecraftsNamesForThemAreUnderstood() throws Exception {
        PriceSheet sheet = bundled();
        double plain = sheet.worth("iron_helmet", Map.of()).orElseThrow();
        assertEquals(plain - 80, sheet.worth("iron_helmet", Map.of("binding_curse", 1)).orElseThrow(), 1e-9);
        assertEquals(plain - 120, sheet.worth("iron_helmet", Map.of("vanishing_curse", 1)).orElseThrow(), 1e-9);
        assertEquals(plain - 80, sheet.worth("iron_helmet", Map.of("curse_of_binding", 1)).orElseThrow(), 1e-9);
    }

    @Test
    void unknownEnchantmentsAddNothingAndTheResultHasAtMostTwoDecimals() throws Exception {
        PriceSheet sheet = bundled();
        assertEquals(900.0, sheet.worth("diamond_pickaxe", Map.of("made_up", 9)).orElseThrow());
        assertEquals(900.0, sheet.worth("diamond_pickaxe", Map.of("efficiency", 0)).orElseThrow());
        double v = sheet.worth("copper_slab", Map.of("smite", 3)).orElseThrow();     // 25 + 3 * 45
        assertEquals(160.0, v);
        assertEquals(v, Math.round(v * 100) / 100.0);
    }

    @Test
    void maxLevelsComeFromTheSheet() throws Exception {
        PriceSheet sheet = bundled();
        assertEquals(5, sheet.maxLevel("efficiency"));
        assertEquals(1, sheet.maxLevel("mending"));
        assertEquals(0, sheet.maxLevel("nonsense"));
        assertEquals(1, sheet.maxLevel("binding_curse"), "Minecraft's name works too");
    }

    @Test
    void aBrokenSheetIsAnErrorAndNeverPricesAnythingAtZero() {
        assertThrows(IOException.class, () -> PriceSheet.parse(new StringReader("{ not json")));
        assertThrows(IOException.class, () -> PriceSheet.parse(new StringReader("{\"things\": {}}")));
        assertThrows(IOException.class, () -> PriceSheet.parse(new StringReader("{\"items\": {}}")), "an empty sheet");
        assertThrows(IOException.class, () -> PriceSheet.parse(new StringReader("{\"items\": {\"stone\": {\"category\": \"x\"}}}")), "no base_value");
        assertThrows(IOException.class, () -> PriceSheet.parse(new StringReader("{\"items\": {\"stone\": {\"base_value\": \"lots\"}}}")));
        assertThrows(IOException.class, () -> PriceSheet.parse(new StringReader("[]")));
    }

    // ---------------------------------------------------------------- the lore line

    @Test
    void pricesReadNaturally() {
        assertEquals("1.5", LoreLine.price(1.5));
        assertEquals("90", LoreLine.price(90.0));
        assertEquals("1,250", LoreLine.price(1250));
        assertEquals("0.6", LoreLine.price(0.6));
        assertEquals("0", LoreLine.price(0));
        assertEquals("1,234,567.89", LoreLine.price(1234567.89));
        assertEquals("-4.8", LoreLine.price(-4.8));
        assertEquals("100", LoreLine.price(100.0));
    }

    @Test
    void theWorthLineIsAddedOnceAndReplacedWhenThePriceChanges() {
        Component name = Component.text("Fast pickaxe");
        Component first = Component.text("Worth: 90");
        List<Component> lore = LoreLine.update(List.of(name), null, first);
        assertEquals(List.of(name, first), lore);
        assertTrue(LoreLine.upToDate(lore, "Worth: 90", first));

        Component second = Component.text("Worth: 213");
        assertFalse(LoreLine.upToDate(lore, "Worth: 90", second));
        assertEquals(List.of(name, second), LoreLine.update(lore, "Worth: 90", second));
    }

    @Test
    void otherLoreIsNeverTouchedAndTheLineCanBeRemoved() {
        Component a = Component.text("Mines a 5x5 tunnel");
        Component b = Component.text("Sneak to mine one block");
        Component worth = Component.text("Worth: 300");
        List<Component> lore = LoreLine.update(List.of(a, b), null, worth);
        assertEquals(List.of(a, b, worth), lore);
        assertEquals(List.of(a, b), LoreLine.update(lore, "Worth: 300", null), "no line wanted: only ours goes");
        assertEquals(List.of(a, b, worth), LoreLine.update(lore, "Worth: 300", worth), "idempotent");
        assertEquals(List.of(worth), LoreLine.update(null, null, worth));
        assertEquals(List.of(), LoreLine.update(null, null, null));
    }

    @Test
    void aWorthLookalikeThatIsNotOursStaysUnlessItMatchesWhatWeRemember() {
        Component fake = Component.text("Worth: 5 (admin note)");
        List<Component> lore = LoreLine.update(List.of(fake), "Worth: 5", Component.text("Worth: 6"));
        assertEquals(List.of(fake, Component.text("Worth: 6")), lore, "a different line is not deleted");
    }

    @Test
    void ifSomeoneAddedLinesAfterOursOnlyOursIsReplaced() {
        Component ours = Component.text("Worth: 10");
        Component later = Component.text("Soulbound");
        List<Component> lore = LoreLine.update(List.of(ours, later), "Worth: 10", Component.text("Worth: 11"));
        assertEquals(List.of(later, Component.text("Worth: 11")), lore);
        assertFalse(LoreLine.upToDate(List.of(ours, later), "Worth: 10", ours), "the line must be the last one");
        assertTrue(LoreLine.upToDate(null, null, null));
        assertTrue(LoreLine.upToDate(List.of(), "", null));
        assertFalse(LoreLine.upToDate(List.of(ours), "Worth: 10", null), "we have a line but want none");
    }

    // ---------------------------------------------------------------- settings

    private static YamlConfiguration shipped() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = WorthTest.class.getResourceAsStream("/config.yml")) {
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void theShippedConfigIsValid() throws Exception {
        WorthSettings s = WorthSettings.parse(shipped());
        assertTrue(s.format().contains("<price>"));
        assertEquals(10, s.scanTicks());
        assertFalse(s.hideZero());
        assertTrue(s.skipCreative());
        assertNull(s.messages().get("nothing"));
        assertTrue(s.messages().containsKey("hand"));
    }

    @Test
    void badValuesAreAllReported() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("lore.format", "Worth: nothing here");
        yaml.set("scan-ticks", 1);
        WorthSettings.Invalid e = assertThrows(WorthSettings.Invalid.class, () -> WorthSettings.parse(yaml));
        assertEquals(2, e.problems().size(), e.problems().toString());
    }

    // ---------------------------------------------------------------- fallback rules

    private static PriceSheet withRules() throws Exception {
        return bundled().withRules(WorthSettings.parse(shipped()).fallbacks());
    }

    @Test
    void everyItemThatTheSheetLeavesOutGetsAPriceFromTheRules() throws Exception {
        PriceSheet plain = bundled();
        PriceSheet sheet = withRules();
        List<String> missing = new java.io.BufferedReader(new InputStreamReader(
                WorthTest.class.getResourceAsStream("/missing-in-sheet.txt"), StandardCharsets.UTF_8)).lines().map(String::strip)
                .filter(l -> !l.isEmpty()).toList();
        assertEquals(277, missing.size());
        for (String id : missing) {
            assertFalse(plain.knows(id), id + " should be missing from the plain sheet");
            assertTrue(sheet.knows(id), id + " has no price even with the fallback rules");
        }
    }

    @Test
    void theRulesGiveSensiblePrices() throws Exception {
        PriceSheet s = withRules();
        assertEquals(12500.0, s.worth("totem_of_undying", Map.of()).orElseThrow());
        assertEquals(14.0, s.worth("deepslate_tile_slab", Map.of()).orElseThrow());
        assertEquals(28.0, s.worth("polished_andesite_stairs", Map.of()).orElseThrow());
        assertEquals(24.0, s.worth("mud_brick_wall", Map.of()).orElseThrow());
        assertEquals(80.0, s.worth("angler_pottery_sherd", Map.of()).orElseThrow(), "same as the explorer sherd");
        assertEquals(s.worth("exposed_lightning_rod", Map.of()).orElseThrow(), s.worth("waxed_exposed_lightning_rod", Map.of()).orElseThrow());
        assertEquals(40.0, s.worth("waxed_weathered_copper_lantern", Map.of()).orElseThrow(), "waxed and weathered copper cost like the plain item");
        assertEquals(s.worth("iron_sword", Map.of()).orElseThrow(), s.worth("iron_spear", Map.of()).orElseThrow());
        assertEquals(120.0, s.worth("copper_spear", Map.of()).orElseThrow(), "through the copper sword rule");
        assertEquals(s.worth("iron_horse_armor", Map.of()).orElseThrow(), s.worth("iron_nautilus_armor", Map.of()).orElseThrow());
        assertEquals(0.0, s.worth("pig_spawn_egg", Map.of()).orElseThrow(), "like the sheet's other spawn eggs");
        assertEquals(25.0, s.worth("blue_egg", Map.of()).orElseThrow(), "same as an egg");
        assertEquals(630.0, s.worth("raw_iron_block", Map.of()).orElseThrow());
        assertEquals(420.0, s.worth("enchanted_book", Map.of("sharpness", 5)).orElseThrow(), "listed items are never changed by the rules");
        assertEquals(15.0, s.worth("stone", Map.of()).orElseThrow());
        assertTrue(s.worth("something_new_from_next_year", Map.of()).isEmpty(), "unknown stays unknown");
    }

    @Test
    void ruleChainsStopInsteadOfLoopingForever() throws Exception {
        PriceSheet loop = bundled().withRules(List.of(
                new PriceSheet.Rule(java.util.regex.Pattern.compile("a_(.*)"), null, "b_$1"),
                new PriceSheet.Rule(java.util.regex.Pattern.compile("b_(.*)"), null, "a_$1")));
        assertTrue(loop.worth("a_x", Map.of()).isEmpty());
    }

    @Test
    void badRulesAreRejected() throws Exception {
        YamlConfiguration yaml = shipped();
        yaml.set("fallbacks", List.of(Map.of("match", "(", "value", 1), Map.of("match", "x", "value", -3),
                Map.of("match", "y"), Map.of("match", "z", "value", 1, "same-as", "stone"), Map.of("value", 2)));
        WorthSettings.Invalid e = assertThrows(WorthSettings.Invalid.class, () -> WorthSettings.parse(yaml));
        assertEquals(5, e.problems().size(), e.problems().toString());
    }
}
