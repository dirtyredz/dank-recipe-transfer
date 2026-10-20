package io.github.dirtyredz.danktransfer.compat.mixin.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.widget.RecipeButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the recipe a recipe button belongs to. */
@Mixin(value = RecipeButtonWidget.class, remap = false)
public interface RecipeButtonWidgetAccessor {
    @Accessor("recipe")
    EmiRecipe dankRecipeTransfer$recipe();
}
