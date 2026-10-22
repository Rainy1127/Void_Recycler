package dev.voidrecycler.economy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Data map entry of {@code void_recycler:recycling_values}: how many Resonance Points ONE item is worth
 * before the global {@code recycling_efficiency} is applied.
 *
 * <p>JSON: either {@code {"points": 100}} or the short form {@code 100}.
 */
public record RecyclingValue(long points) {
    private static final Codec<RecyclingValue> OBJECT_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("points").forGetter(RecyclingValue::points)
    ).apply(i, RecyclingValue::new));

    public static final Codec<RecyclingValue> CODEC = Codec.withAlternative(
            OBJECT_CODEC,
            Codec.LONG.xmap(RecyclingValue::new, RecyclingValue::points));
}
