package dev.voidrecycler.integration.jei;

import dev.voidrecycler.VoidRecycler;
import dev.voidrecycler.client.VoidRecyclerScreen;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.economy.FabricationPrice;
import dev.voidrecycler.economy.RecyclingValue;
import dev.voidrecycler.economy.VRDataMaps;
import dev.voidrecycler.registry.VRRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Optional JEI integration (compiled only when {@code jei_enabled=true} in gradle.properties).
 *
 * <ul>
 *   <li>Two recipe categories - "Void Recycling" (what an item recycles for) and "Void Fabrication" (what an item
 *       costs) - built from the synced data maps, so they follow the modpack's data pack.</li>
 *   <li>The Void Recycler is registered as the catalyst of both.</li>
 *   <li>Drag an item from the JEI list onto the Target Slot to select it.</li>
 *   <li>The catalog side panel is reported as an extra GUI area, so the JEI overlay moves out of its way.</li>
 * </ul>
 */
@JeiPlugin
public class VoidRecyclerJeiPlugin implements IModPlugin {
    static final IRecipeType<RecycleEntry> RECYCLING = IRecipeType.create(VoidRecycler.id("recycling"), RecycleEntry.class);
    static final IRecipeType<FabricateEntry> FABRICATION = IRecipeType.create(VoidRecycler.id("fabrication"), FabricateEntry.class);

    /** One JEI "recipe": an item and the raw (before efficiency) points it recycles for. */
    record RecycleEntry(Item item, long rawPoints) {}

    /** One JEI "recipe": an item and the points it costs to fabricate. */
    record FabricateEntry(Item item, long cost) {}

    @Override
    public Identifier getPluginUid() {
        return VoidRecycler.id("jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var gui = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(new RecyclingCategory(gui), new FabricationCategory(gui));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<RecycleEntry> recycling = new ArrayList<>();
        for (Map.Entry<ResourceKey<Item>, RecyclingValue> entry : BuiltInRegistries.ITEM.getDataMap(VRDataMaps.RECYCLING_VALUES).entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(entry.getKey().identifier());
            if (item != null && item != Items.AIR && Economy.rawRecyclingValue(item) > 0L) {
                recycling.add(new RecycleEntry(item, Economy.rawRecyclingValue(item)));
            }
        }
        List<FabricateEntry> fabrication = new ArrayList<>();
        for (Map.Entry<ResourceKey<Item>, FabricationPrice> entry : BuiltInRegistries.ITEM.getDataMap(VRDataMaps.FABRICATION_PRICES).entrySet()) {
            Item item = BuiltInRegistries.ITEM.getValue(entry.getKey().identifier());
            if (item != null && item != Items.AIR && Economy.fabricationPrice(item) > 0L) {
                fabrication.add(new FabricateEntry(item, Economy.fabricationPrice(item)));
            }
        }
        registration.addRecipes(RECYCLING, recycling);
        registration.addRecipes(FABRICATION, fabrication);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addCraftingStation(RECYCLING, VRRegistries.VOID_RECYCLER_ITEM.get());
        registration.addCraftingStation(FABRICATION, VRRegistries.VOID_RECYCLER_ITEM.get());
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(VoidRecyclerScreen.class, new IGuiContainerHandler<VoidRecyclerScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(VoidRecyclerScreen screen) {
                return screen.getExtraAreas();
            }
        });

        registration.addGhostIngredientHandler(VoidRecyclerScreen.class, new IGhostIngredientHandler<VoidRecyclerScreen>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(VoidRecyclerScreen screen, ITypedIngredient<I> ingredient, boolean doStart) {
                Optional<ItemStack> stack = ingredient.getItemStack();
                if (stack.isEmpty() || !Economy.isFabricable(stack.get().getItem())) {
                    return List.of();
                }
                final Item item = stack.get().getItem();
                return List.of(new Target<I>() {
                    @Override
                    public Rect2i getArea() {
                        return screen.getTargetArea();
                    }

                    @Override
                    public void accept(I dropped) {
                        screen.selectTargetFromGhost(item);
                    }
                });
            }

            @Override
            public void onComplete() {}
        });
    }
}
