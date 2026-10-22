package dev.voidrecycler.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Data map entry of {@code void_recycler:fabrication_prices}: the Resonance Point cost of fabricating ONE item.
 *
 * <p>JSON: either {@code {"cost": 5000}} or the short form {@code 5000}.
 */
public record FabricationPrice(long cost) {
    private static final Codec<FabricationPrice> OBJECT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("cost").forGetter(FabricationPrice::cost)
    ).apply(i, FabricationPrice::new));

    public static final Codec<FabricationPrice> CODEC = Codec.withAlternative(
            OBJECT_CODEC,
            Codec.LONG.xmap(FabricationPrice::new, FabricationPrice::cost));
}
