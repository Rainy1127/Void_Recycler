package dev.voidrecycler.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side (per-world) configuration: {@code <world>/serverconfig/void_recycler-server.toml}.
 *
 * <p>All getters are guarded: SERVER configs are not loaded before a world is opened (and on a client that has not
 * received the synced values yet), so reading them early would throw {@link IllegalStateException}.
 * The guarded getters fall back to the defaults instead.
 */
public final class VRConfig {
    public static final ModConfigSpec SPEC;

    public static final double DEFAULT_EFFICIENCY = 0.5D;
    public static final boolean DEFAULT_VOID_UNKNOWN = true;
    public static final int DEFAULT_MAX_EXTRACT = 64;
    public static final double DEFAULT_AMPLIFIER_BONUS = 0.10D;
    public static final boolean DEFAULT_EFFECTS = true;

    private static final ModConfigSpec.DoubleValue RECYCLING_EFFICIENCY;
    private static final ModConfigSpec.BooleanValue VOID_UNKNOWN_ITEMS;
    private static final ModConfigSpec.IntValue MAX_EXTRACT_PER_CALL;
    private static final ModConfigSpec.DoubleValue AMPLIFIER_BONUS;
    private static final ModConfigSpec.BooleanValue EFFECTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        RECYCLING_EFFICIENCY = b
                .comment("Global multiplier applied to every recycling value (0.0 - 1.0).",
                        "Keep it below the ratio between 'fabrication price' and 'recycling value' of every item",
                        "to make sure nobody can build an infinite item loop. Run /voidrecycler validate to check your data maps.")
                .translation("void_recycler.configuration.recycling_efficiency")
                .defineInRange("recycling_efficiency", DEFAULT_EFFICIENCY, 0.0D, 1.0D);

        VOID_UNKNOWN_ITEMS = b
                .comment("true  = items without a recycling value are destroyed for 0 points (a real 'void').",
                        "false = such items are rejected by the block (pipes/hoppers keep them, hand insertion is refused).")
                .translation("void_recycler.configuration.void_unknown_items")
                .define("void_unknown_items", DEFAULT_VOID_UNKNOWN);

        MAX_EXTRACT_PER_CALL = b
                .comment("Upper limit of items a pipe/hopper can fabricate in a single extract call.",
                        "Protects against pipes that request Integer.MAX_VALUE items at once.")
                .translation("void_recycler.configuration.max_extract_per_call")
                .defineInRange("max_extract_per_call", DEFAULT_MAX_EXTRACT, 1, 4096);

        AMPLIFIER_BONUS = b
                .comment("Efficiency added by EACH Resonance Amplifier (up to 4 fit into a recycler).",
                        "Final efficiency = recycling_efficiency + amplifiers * this value, capped at 1.0.",
                        "/voidrecycler validate checks your data maps against the maximum.")
                .translation("void_recycler.configuration.amplifier_bonus")
                .defineInRange("amplifier_bonus", DEFAULT_AMPLIFIER_BONUS, 0.0D, 0.5D);

        EFFECTS = b
                .comment("Play a sound and spawn a few particles when items are recycled / fabricated.",
                        "Throttled to a few per second per block; set to false for a completely silent block.")
                .translation("void_recycler.configuration.effects")
                .define("effects", DEFAULT_EFFECTS);

        SPEC = b.build();
    }

    private VRConfig() {}

    /** @return recycling efficiency in the range 0.0 - 1.0 */
    public static double recyclingEfficiency() {
        try {
            return RECYCLING_EFFICIENCY.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_EFFICIENCY;
        }
    }

    /** @return the efficiency in thousandths (0 - 1000); all point maths is done in these "milli" units. */
    public static int efficiencyMilli() {
        return (int) Math.round(Math.max(0.0D, Math.min(1.0D, recyclingEfficiency())) * 1000.0D);
    }

    public static boolean voidUnknownItems() {
        try {
            return VOID_UNKNOWN_ITEMS.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_VOID_UNKNOWN;
        }
    }

    public static int maxExtractPerCall() {
        try {
            return MAX_EXTRACT_PER_CALL.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_MAX_EXTRACT;
        }
    }

    /** @return efficiency added per Resonance Amplifier, in thousandths (0 - 500). */
    public static int amplifierBonusMilli() {
        try {
            return (int) Math.round(Math.max(0.0D, Math.min(0.5D, AMPLIFIER_BONUS.get())) * 1000.0D);
        } catch (IllegalStateException notLoaded) {
            return (int) Math.round(DEFAULT_AMPLIFIER_BONUS * 1000.0D);
        }
    }

    public static boolean effects() {
        try {
            return EFFECTS.get();
        } catch (IllegalStateException notLoaded) {
            return DEFAULT_EFFECTS;
        }
    }
}
