package dev.voidrecycler.integration.jei;

import dev.voidrecycler.config.VRConfig;
import dev.voidrecycler.economy.Economy;
import dev.voidrecycler.registry.VRRegistries;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.types.IRecipeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** "Void Recycling": item (left) -> points (text). */
final class RecyclingCategory implements IRecipeCategory<VoidRecyclerJeiPlugin.RecycleEntry> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 34;

    private final IDrawable icon;

    RecyclingCategory(IGuiHelper gui) {
        this.icon = gui.createDrawableItemLike(VRRegistries.VOID_RECYCLER_ITEM.get());
    }

    @Override
    public IRecipeType<VoidRecyclerJeiPlugin.RecycleEntry> getRecipeType() {
        return VoidRecyclerJeiPlugin.RECYCLING;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.void_recycler.recycling");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, VoidRecyclerJeiPlugin.RecycleEntry recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, 9, 9).add(recipe.item());
        builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, 151, 9).add(VRRegistries.VOID_RECYCLER_ITEM.get());
    }

    @Override
    public void draw(VoidRecyclerJeiPlugin.RecycleEntry recipe, IRecipeSlotsView slots, GuiGraphicsExtractor g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        long milli = Economy.satMul(recipe.rawPoints(), VRConfig.efficiencyMilli());
        JeiText.fit(g, font, Component.translatable("jei.void_recycler.recycles", Economy.formatMilli(milli)), 32, 7, 0xFF00C8C8, 112);
        JeiText.fit(g, font, Component.translatable("jei.void_recycler.at_efficiency", Economy.formatMilli(VRConfig.efficiencyMilli() * 100L)), 32, 19, 0xFF7A7A88, 112);
    }
}
