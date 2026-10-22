package dev.voidrecycler.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only preferences ({@code config/void_recycler-client.toml}), editable from the in-game mod list. */
public final class VRClientConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue SHOW_ECONOMY_TOOLTIPS;
    private static final ModConfigSpec.BooleanValue GUI_ANIMATIONS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        SHOW_ECONOMY_TOOLTIPS = b
                .comment("Append 'Recycles for X points' / 'Fabricates for X points' lines to EVERY item tooltip.",
                        "Off by default: inside the Void Recycler GUI the same info is shown when you hover an item.")
                .translation("void_recycler.configuration.show_economy_tooltips")
                .define("show_economy_tooltips", false);

        GUI_ANIMATIONS = b
                .comment("Animated balance counter, '+N' popups and the pulsing output slot in the Void Recycler GUI.")
                .translation("void_recycler.configuration.gui_animations")
                .define("gui_animations", true);

        SPEC = b.build();
    }

    private VRClientConfig() {}

    public static boolean showEconomyTooltips() {
        try {
            return SHOW_ECONOMY_TOOLTIPS.get();
        } catch (IllegalStateException notLoaded) {
            return false;
        }
    }

    public static boolean guiAnimations() {
        try {
            return GUI_ANIMATIONS.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }
}
