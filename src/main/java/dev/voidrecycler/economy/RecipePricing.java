package dev.voidrecycler.economy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Reads the server's crafting recipes into the plain {@link PriceGenerator.Recipe} model. Uses only the recipe
 * <i>display</i> (the same data JEI shows), so tags, composite slots and modded recipe types resolve the same way
 * they do on screen. Special recipes (dyeing, fireworks, ...), recipes with component-bearing results and crafting
 * remainders (the bucket in a cake) are ignored.
 */
public final class RecipePricing {
    private RecipePricing() {}

    public static List<PriceGenerator.Recipe> scanCrafting(ServerLevel level) {
        ContextMap context = SlotDisplayContext.fromLevel(level);
        List<PriceGenerator.Recipe> out = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getServer().getRecipeManager().recipeMap().byType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (recipe.isSpecial()) {
                continue;
            }
            List<RecipeDisplay> displays = recipe.display();
            if (displays.isEmpty()) {
                continue;
            }
            SlotDisplay result;
            List<SlotDisplay> ingredients;
            if (displays.getFirst() instanceof ShapedCraftingRecipeDisplay shaped) {
                result = shaped.result();
                ingredients = shaped.ingredients();
            } else if (displays.getFirst() instanceof ShapelessCraftingRecipeDisplay shapeless) {
                result = shapeless.result();
                ingredients = shapeless.ingredients();
            } else {
                continue;
            }

            ItemStack resultStack = result.resolveForFirstStack(context);
            if (resultStack.isEmpty() || resultStack.getItem() == Items.AIR || !resultStack.getComponentsPatch().isEmpty()) {
                continue;
            }

            List<List<String>> slots = new ArrayList<>();
            for (SlotDisplay slot : ingredients) {
                if (slot == SlotDisplay.Empty.INSTANCE) {
                    continue; // an empty grid cell is not an ingredient
                }
                Set<String> alternatives = new LinkedHashSet<>();
                for (ItemStack stack : slot.resolveForStacks(context)) {
                    Item item = stack.getItem();
                    if (item != Items.AIR) {
                        alternatives.add(BuiltInRegistries.ITEM.getKey(item).toString());
                    }
                }
                slots.add(List.copyOf(alternatives)); // empty list = unresolvable slot, the recipe will be skipped
            }
            out.add(new PriceGenerator.Recipe(
                    BuiltInRegistries.ITEM.getKey(resultStack.getItem()).toString(), resultStack.getCount(), slots));
        }
        return out;
    }
}
