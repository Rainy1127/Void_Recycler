package dev.voidrecycler.integration.jei;

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

/** "Void Fabrication": points (text) -> item (right). */
final class FabricationCategory implements IRecipeCategory<VoidRecyclerJeiPlugin.FabricateEntry> {
    private static final int WIDTH = 176;
    private static final int HEIGHT = 34;

    private final IDrawable icon;

    FabricationCategory(IGuiHelper gui) {
        this.icon = gui.createDrawableItemLike(VRRegistries.VOID_RECYCLER_ITEM.get());
    }

    @Override
    public IRecipeType<VoidRecyclerJeiPlugin.FabricateEntry> getRecipeType() {
        return VoidRecyclerJeiPlugin.FABRICATION;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.void_recycler.fabrication");
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
    public void setRecipe(IRecipeLayoutBuilder builder, VoidRecyclerJeiPlugin.FabricateEntry recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.CRAFTING_STATION, 9, 9).add(VRRegistries.VOID_RECYCLER_ITEM.get());
        builder.addSlot(RecipeIngredientRole.OUTPUT, 151, 9).add(recipe.item());
    }

    @Override
    public void draw(VoidRecyclerJeiPlugin.FabricateEntry recipe, IRecipeSlotsView slots, GuiGraphicsExtractor g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        JeiText.fit(g, font, Component.translatable("jei.void_recycler.costs", Economy.formatPoints(recipe.cost())), 32, 7, 0xFFFF9F1C, 112);
        JeiText.fit(g, font, Component.translatable("jei.void_recycler.set_target"), 32, 19, 0xFF7A7A88, 112);
    }
}
