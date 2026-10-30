package io.github.dirtyredz.danktransfer.compat.mixin.jei;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.dirtyredz.danktransfer.compat.jei.DankJeiTransferHandler;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.library.recipes.RecipeTransferManager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

/**
 * Decorates JEI's native vanilla crafting handlers at lookup time (JEI 19.70.0.464; descriptor verified against that jar). Every other
 * handler is returned unchanged.
 */
@Mixin(value = RecipeTransferManager.class, remap = false)
public abstract class JeiTransferLookupMixin {
    @WrapMethod(
            method = "getRecipeTransferHandler(Lnet/minecraft/world/inventory/AbstractContainerMenu;" +
                    "Lmezz/jei/api/recipe/category/IRecipeCategory;)Ljava/util/Optional;",
            remap = false
    )
    private <C extends AbstractContainerMenu, R> Optional<IRecipeTransferHandler<C, R>> dankRecipeTransfer$decorate(
            C container, IRecipeCategory<R> category,
            Operation<Optional<IRecipeTransferHandler<C, R>>> original) {
        Optional<IRecipeTransferHandler<C, R>> found = original.call(container, category);
        return DankJeiTransferHandler.decorate(container, category.getRecipeType(), found);
    }
}
