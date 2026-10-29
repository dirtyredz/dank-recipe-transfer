package io.github.dirtyredz.danktransfer.compat.mixin.jei;

import io.github.dirtyredz.danktransfer.compat.jei.DankJeiTransferHandler;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.library.recipes.RecipeTransferManager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Decorates JEI's native vanilla crafting handlers at lookup time (JEI 19.70.0.464; descriptor verified against that jar). Every other
 * handler is returned unchanged.
 */
@Mixin(value = RecipeTransferManager.class, remap = false)
public abstract class JeiTransferLookupMixin {
    @Inject(method = "getRecipeTransferHandler", at = @At("RETURN"), cancellable = true)
    private <C extends AbstractContainerMenu, R> void dankRecipeTransfer$decorate(
            C container, IRecipeCategory<R> category, CallbackInfoReturnable<Optional<IRecipeTransferHandler<C, R>>> cir) {
        Optional<IRecipeTransferHandler<C, R>> found = cir.getReturnValue();
        Optional<IRecipeTransferHandler<C, R>> decorated = DankJeiTransferHandler.decorate(container, category.getRecipeType(), found);
        if (decorated != found) {
            cir.setReturnValue(decorated);
        }
    }
}
