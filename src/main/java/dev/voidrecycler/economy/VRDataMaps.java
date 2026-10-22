package dev.voidrecycler.economy;

import dev.voidrecycler.VoidRecycler;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/**
 * The two data-driven price lists. Both are synced to clients (for tooltips and the GUI) but are not mandatory,
 * so a client without the mod data can still connect.
 *
 * <ul>
 *   <li>{@code data/<ns>/data_maps/item/recycling_values.json}  - namespace {@code void_recycler}</li>
 *   <li>{@code data/<ns>/data_maps/item/fabrication_prices.json} - namespace {@code void_recycler}</li>
 * </ul>
 * Both support item ids and {@code #tags} as keys, {@code "remove": [...]} and {@code "replace": true}.
 */
public final class VRDataMaps {
    public static final DataMapType<Item, RecyclingValue> RECYCLING_VALUES = DataMapType.builder(
            VoidRecycler.id("recycling_values"), Registries.ITEM, RecyclingValue.CODEC)
            .synced(RecyclingValue.CODEC, false)
            .build();

    public static final DataMapType<Item, FabricationPrice> FABRICATION_PRICES = DataMapType.builder(
            VoidRecycler.id("fabrication_prices"), Registries.ITEM, FabricationPrice.CODEC)
            .synced(FabricationPrice.CODEC, false)
            .build();

    private VRDataMaps() {}

    public static void onRegister(RegisterDataMapTypesEvent event) {
        event.register(RECYCLING_VALUES);
        event.register(FABRICATION_PRICES);
    }
}
