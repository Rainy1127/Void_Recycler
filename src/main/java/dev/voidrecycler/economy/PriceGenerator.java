package dev.voidrecycler.economy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure maths behind {@code /voidrecycler generate} (no Minecraft types, unit tested).
 *
 * <p>Idea: raw materials get their recycling value from the modpack author (the "seeds"). Every crafted item is worth the
 * sum of its ingredients divided by the recipe's output count. For a slot that accepts several items (tags) the
 * cheapest priced alternative counts, and when an item has several recipes the cheapest recipe wins. Items that already
 * have an explicit value are never overwritten, items whose recipe contains an unpriced slot are skipped.
 *
 * <p>The relaxation can only lower a value it has already found and every value comes from a finite sum of seeds, so it
 * terminates; {@code maxPasses} is just a safety net against absurd inputs.
 */
public final class PriceGenerator {
    private PriceGenerator() {}

    /**
     * One crafting recipe.
     *
     * @param output      item id of the result
     * @param outputCount how many items one craft produces
     * @param slots       one entry per ingredient slot (empty grid cells are NOT slots); each entry lists the accepted item ids
     */
    public record Recipe(String output, int outputCount, List<List<String>> slots) {}

    /**
     * @param known     items that already have a value (never overwritten)
     * @param recipes   all crafting recipes
     * @param maxPasses upper bound for the relaxation passes
     * @return the newly derived values, sorted by item id (never contains a key of {@code known})
     */
    public static Map<String, Long> derive(Map<String, Long> known, List<Recipe> recipes, int maxPasses) {
        Map<String, Long> value = new HashMap<>(known);
        Map<String, Long> derived = new TreeMap<>();
        for (int pass = 0; pass < Math.max(1, maxPasses); pass++) {
            boolean changed = false;
            for (Recipe recipe : recipes) {
                if (recipe.outputCount() <= 0 || recipe.slots().isEmpty() || known.containsKey(recipe.output())) {
                    continue;
                }
                long sum = 0L;
                boolean complete = true;
                for (List<String> alternatives : recipe.slots()) {
                    long best = Long.MAX_VALUE;
                    for (String id : alternatives) {
                        Long v = value.get(id);
                        if (v != null && v > 0L && v < best) {
                            best = v;
                        }
                    }
                    if (best == Long.MAX_VALUE) {
                        complete = false;
                        break;
                    }
                    sum = PointMath.satAdd(sum, best);
                }
                if (!complete) {
                    continue;
                }
                long perItem = Math.max(1L, sum / recipe.outputCount());
                Long old = derived.get(recipe.output());
                if (old == null || perItem < old) {
                    derived.put(recipe.output(), perItem);
                    value.put(recipe.output(), perItem);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return derived;
    }

    /** {@code value * markup}, rounded up, at least 1. Saturating. */
    public static long markup(long value, double markup) {
        double scaled = Math.ceil(value * markup);
        if (!(scaled < (double) Long.MAX_VALUE)) {
            return Long.MAX_VALUE;
        }
        return Math.max(1L, (long) scaled);
    }

    /**
     * Renders a data map file: {@code {"values": {"ns:item": {"<field>": n}, ...}}}, keys sorted.
     * The ids come from the item registry, so no escaping beyond the quotes is needed.
     */
    public static String toDataMapJson(Map<String, Long> values, String field) {
        StringBuilder sb = new StringBuilder("{\n  \"values\": {");
        boolean first = true;
        for (Map.Entry<String, Long> entry : new TreeMap<>(values).entrySet()) {
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("    \"").append(entry.getKey()).append("\": { \"").append(field).append("\": ")
                    .append(entry.getValue()).append(" }");
        }
        sb.append(first ? "}\n}\n" : "\n  }\n}\n");
        return sb.toString();
    }
}
