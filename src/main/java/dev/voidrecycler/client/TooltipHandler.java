package dev.voidrecycler.client;

import dev.voidrecycler.config.VRClientConfig;
import dev.voidrecycler.config.VRConfig;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.registry.VRRegistries;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Adds the economy to item tooltips so players (and pack makers) can see values without opening a wiki:
 * what an item recycles for, what it costs to fabricate, and the points stored in a Void Recycler item.
 */
public final class TooltipHandler {
    private static final int CYAN = 0x00FFFF;
    private static final int ORANGE = 0xFF9F1C;

    private TooltipHandler() {}

    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        Item item = stack.getItem();
        List<Component> tooltip = event.getToolTip();

        // Economy lines are opt-in (client config): in modpacks with hundreds of priced items they would clutter
        // every tooltip. The GUI shows the same numbers when hovering an item inside the Void Recycler.
        boolean economy = VRClientConfig.showEconomyTooltips();

        if (economy && Economy.isRecyclingKnown(item)) {
            long effectiveMilli = Economy.satMul(Economy.rawRecyclingValue(item), VRConfig.efficiencyMilli());
            tooltip.add(Component.translatable("tooltip.void_recycler.recycles", Economy.formatMilli(effectiveMilli))
                    .withStyle(style -> style.withColor(CYAN)));
        }

        long price = economy ? Economy.fabricationPrice(item) : 0L;
        if (economy && price > 0L) {
            tooltip.add(Component.translatable("tooltip.void_recycler.fabricates", Economy.formatPoints(price))
                    .withStyle(style -> style.withColor(ORANGE)));
        }

        // our own items explain themselves (these are few and fixed, so they are always shown)
        if (stack.is(VRRegistries.AMPLIFIER_ITEM.get())) {
            tooltip.add(Component.translatable("tooltip.void_recycler.amplifier",
                    Economy.formatMilli(VRConfig.amplifierBonusMilli() * 100L), VRRegistries.MAX_UPGRADES)
                    .withStyle(style -> style.withColor(CYAN)));
        } else if (stack.is(VRRegistries.THROUGHPUT_ITEM.get())) {
            tooltip.add(Component.translatable("tooltip.void_recycler.throughput", VRRegistries.MAX_UPGRADES)
                    .withStyle(style -> style.withColor(CYAN)));
        } else if (stack.is(VRRegistries.CAPSULE_ITEM.get())) {
            tooltip.add(Component.translatable("tooltip.void_recycler.capsule")
                    .withStyle(style -> style.withColor(0x7A7A88)));
        }

        Integer amps = stack.get(VRRegistries.STORED_AMPLIFIERS.get());
        if (amps != null && amps > 0) {
            tooltip.add(Component.translatable("tooltip.void_recycler.stored_amplifiers", amps)
                    .withStyle(style -> style.withColor(CYAN)));
        }
        Integer thr = stack.get(VRRegistries.STORED_THROUGHPUT.get());
        if (thr != null && thr > 0) {
            tooltip.add(Component.translatable("tooltip.void_recycler.stored_throughput", thr)
                    .withStyle(style -> style.withColor(CYAN)));
        }

        Long stored = stack.get(VRRegistries.STORED_POINTS.get());
        if (stored != null && stored > 0L) {
            tooltip.add(Component.translatable("tooltip.void_recycler.stored", Economy.formatPoints(stored))
                    .withStyle(style -> style.withColor(CYAN)));
        }
    }
}
