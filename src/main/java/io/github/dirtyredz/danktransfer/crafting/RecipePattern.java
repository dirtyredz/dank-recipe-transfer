package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.crafting.PlanInput.PatternSlot;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.IShapedRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Places a registered shaped or shapeless crafting recipe into a grid of a given size.
 *
 * <p>Shaped recipes go to the top-left corner, the same placement the vanilla recipe book uses;
 * shapeless ingredients fill the grid in order. The server validates the finished arrangement
 * with {@code Recipe#matches}, so this only has to produce one valid placement.
 */
public final class RecipePattern {
    private RecipePattern() {
    }

    /** The pattern, or empty when the recipe is special or does not fit the grid. */
    public static Optional<List<PatternSlot<ItemKey>>> of(CraftingRecipe recipe, int gridWidth, int gridHeight) {
        if (recipe.isSpecial()) {
            return Optional.empty();
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        List<PatternSlot<ItemKey>> pattern = new ArrayList<>();
        if (recipe instanceof IShapedRecipe<?> shaped) {
            int width = shaped.getRecipeWidth();
            int height = shaped.getRecipeHeight();
            if (width <= 0 || height <= 0 || width > gridWidth || height > gridHeight
                    || ingredients.size() < width * height) {
                return Optional.empty();
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int ingredientIndex = y * width + x;
                    Ingredient ingredient = ingredients.get(ingredientIndex);
                    if (!ingredient.isEmpty()) {
                        pattern.add(slot(y * gridWidth + x, ingredientIndex, ingredient));
                    }
                }
            }
        } else {
            int next = 0;
            for (int i = 0; i < ingredients.size(); i++) {
                Ingredient ingredient = ingredients.get(i);
                if (ingredient.isEmpty()) {
                    continue;
                }
                if (next >= gridWidth * gridHeight) {
                    return Optional.empty();
                }
                pattern.add(slot(next++, i, ingredient));
            }
        }
        return pattern.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(pattern));
    }

    private static PatternSlot<ItemKey> slot(int gridIndex, int ingredientIndex, Ingredient ingredient) {
        return new PatternSlot<>(gridIndex, ingredientIndex, key -> ingredient.test(key.probe()));
    }
}
