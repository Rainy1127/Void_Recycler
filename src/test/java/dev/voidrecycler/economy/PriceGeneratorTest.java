package dev.voidrecycler.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voidrecycler.economy.PriceGenerator.Recipe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceGeneratorTest {
    private static List<String> any(String... ids) {
        return List.of(ids);
    }

    @Test
    void sumsIngredientsAndDividesByOutputCount() {
        Map<String, Long> known = Map.of("m:ingot", 40L);
        // 9 ingots -> 1 block, 1 block -> 9 ingots (ingot is a seed, never overwritten)
        List<Recipe> recipes = List.of(
                new Recipe("m:block", 1, java.util.Collections.nCopies(9, any("m:ingot"))),
                new Recipe("m:ingot", 9, List.of(any("m:block"))));
        Map<String, Long> derived = PriceGenerator.derive(known, recipes, 16);
        assertEquals(360L, derived.get("m:block"));
        assertFalse(derived.containsKey("m:ingot"));
    }

    @Test
    void cheapestAlternativeInASlotAndCheapestRecipeWin() {
        Map<String, Long> known = Map.of("m:cheap", 2L, "m:rich", 50L);
        List<Recipe> recipes = List.of(
                new Recipe("m:thing", 1, List.of(any("m:rich", "m:cheap"), any("m:cheap"))),
                new Recipe("m:thing", 1, List.of(any("m:rich"), any("m:rich"))));
        assertEquals(4L, PriceGenerator.derive(known, recipes, 16).get("m:thing"));
    }

    @Test
    void chainsResolveRegardlessOfRecipeOrder() {
        Map<String, Long> known = Map.of("m:log", 4L);
        List<Recipe> recipes = List.of(
                new Recipe("m:chest", 1, java.util.Collections.nCopies(8, any("m:planks"))),
                new Recipe("m:planks", 4, List.of(any("m:log"))));
        Map<String, Long> derived = PriceGenerator.derive(known, recipes, 16);
        assertEquals(1L, derived.get("m:planks"));
        assertEquals(8L, derived.get("m:chest"));
    }

    @Test
    void unpricedSlotsSkipTheRecipeAndNeverCreateValueFromNothing() {
        Map<String, Long> known = Map.of("m:a", 5L);
        List<Recipe> recipes = List.of(
                new Recipe("m:x", 1, List.of(any("m:a"), any("m:unknown"))),
                new Recipe("m:y", 1, List.of(any())),          // unresolvable slot
                new Recipe("m:loop1", 1, List.of(any("m:loop2"))),
                new Recipe("m:loop2", 1, List.of(any("m:loop1"))));
        Map<String, Long> derived = PriceGenerator.derive(known, recipes, 16);
        assertTrue(derived.isEmpty());
    }

    @Test
    void roundTripThroughMarkupStaysLoopFree() {
        assertEquals(80L, PriceGenerator.markup(40L, 2.0D));
        assertEquals(3L, PriceGenerator.markup(1L, 2.5D));
        assertEquals(1L, PriceGenerator.markup(0L, 2.0D));
        assertEquals(Long.MAX_VALUE, PriceGenerator.markup(Long.MAX_VALUE, 10.0D));
    }

    @Test
    void rendersSortedDataMapJson() {
        String json = PriceGenerator.toDataMapJson(Map.of("m:b", 2L, "m:a", 1L), "points");
        assertEquals("{\n  \"values\": {\n    \"m:a\": { \"points\": 1 },\n    \"m:b\": { \"points\": 2 }\n  }\n}\n", json);
        assertEquals("{\n  \"values\": {}\n}\n", PriceGenerator.toDataMapJson(Map.of(), "cost"));
    }
}
