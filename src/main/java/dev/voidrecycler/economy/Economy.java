package dev.voidrecycler.economy;

import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.config.VRConfig;
import dev.voidrecycler.registry.VRRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * Stateless, O(1) helpers around the two data maps. Every lookup is a single hash access on the item's registry holder.
 * All point arithmetic is saturating so absurd data packs can never overflow a {@code long} into negative numbers.
 */
public final class Economy {
    /** Items in this tag are rejected by the block, whatever the data maps or {@code void_unknown_items} say. */
    public static final TagKey<Item> NEVER_RECYCLE = TagKey.create(Registries.ITEM, VoidRecycler.id("never_recycle"));
    /** Items in this tag can never be fabricated (no price, hidden from the catalog and JEI). */
    public static final TagKey<Item> NEVER_FABRICATE = TagKey.create(Registries.ITEM, VoidRecycler.id("never_fabricate"));

    private Economy() {}

    /** @return true if a tag forbids recycling this item. O(1): a hash lookup on the holder's tag set. */
    public static boolean isRecyclingBlocked(Item item) {
        return item.builtInRegistryHolder().is(NEVER_RECYCLE);
    }

    /** @return true if a tag forbids fabricating this item. */
    public static boolean isFabricationBlocked(Item item) {
        return item.builtInRegistryHolder().is(NEVER_FABRICATE);
    }

    // ----- data map lookups ------------------------------------------------------------------------------------

    /** @return true if the item has an entry in the recycling map (even a 0 entry). */
    public static boolean isRecyclingKnown(Item item) {
        return item.builtInRegistryHolder().getData(VRDataMaps.RECYCLING_VALUES) != null && !isRecyclingBlocked(item);
    }

    /** @return the un-scaled recycling value of ONE item, never negative; 0 for unknown items. */
    public static long rawRecyclingValue(Item item) {
        RecyclingValue value = item.builtInRegistryHolder().getData(VRDataMaps.RECYCLING_VALUES);
        return value == null || isRecyclingBlocked(item) ? 0L : Math.max(0L, value.points());
    }

    /** @return the cost of fabricating ONE item; 0 means "not fabricable". */
    public static long fabricationPrice(Item item) {
        FabricationPrice price = item.builtInRegistryHolder().getData(VRDataMaps.FABRICATION_PRICES);
        return price == null || isFabricationBlocked(item) ? 0L : Math.max(0L, price.cost());
    }

    public static boolean isFabricable(Item item) {
        return fabricationPrice(item) > 0L;
    }

    /**
     * May this item be pushed into the block (hand or pipe)? Items with a recycling entry always can; items without
     * one only when the {@code void_unknown_items} option is on (they are then destroyed for 0 points).
     */
    public static boolean canVoid(Item item) {
        if (isRecyclingBlocked(item)) {
            return false;
        }
        return isRecyclingKnown(item) || (VRConfig.voidUnknownItems() && !isProtected(item));
    }

    /**
     * Our own valuable items (the block with its balance, the capsule, the upgrades) are never destroyed by accident
     * through the "void unknown items" rule - hoppers and a misplaced click would otherwise delete stored points.
     * A data pack can still opt in by giving them an explicit recycling value.
     */
    public static boolean isProtected(Item item) {
        return item == VRRegistries.CAPSULE_ITEM.get()
                || item == VRRegistries.AMPLIFIER_ITEM.get()
                || item == VRRegistries.THROUGHPUT_ITEM.get()
                || item == VRRegistries.VOID_RECYCLER_ITEM.get();
    }

    // ----- saturating maths + formatting (see PointMath, which has no Minecraft dependency) ---------------------

    public static long satAdd(long a, long b) {
        return PointMath.satAdd(a, b);
    }

    public static long satMul(long a, long b) {
        return PointMath.satMul(a, b);
    }

    /** 1500000 -> "1,500,000" (always US grouping so the GUI looks identical in every locale). */
    public static String formatPoints(long points) {
        return PointMath.formatPoints(points);
    }

    /** Formats a value given in thousandths: 1500 -> "1.5", 2000 -> "2", 123456 -> "123.456". */
    public static String formatMilli(long milli) {
        return PointMath.formatMilli(milli);
    }
}
