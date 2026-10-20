package io.github.dirtyredz.danktransfer.compat.mixin.emi;

import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.registry.EmiRecipeFiller;
import io.github.dirtyredz.danktransfer.compat.emi.DankEmiTransferHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Decorates EMI's native vanilla crafting handlers where EMI collects them (EMI 1.1.24). EMI's
 * native handlers come before JEMI, so the JEI integration alone cannot cover EMI.
 */
@Mixin(value = EmiRecipeFiller.class, remap = false)
public abstract class EmiHandlerLookupMixin {
    @Inject(method = "getAllHandlers", at = @At("RETURN"), cancellable = true)
    private static <T extends AbstractContainerMenu> void dankRecipeTransfer$decorate(
            AbstractContainerScreen<T> screen, CallbackInfoReturnable<List<EmiRecipeHandler<T>>> cir) {
        List<EmiRecipeHandler<T>> found = cir.getReturnValue();
        List<EmiRecipeHandler<T>> decorated = DankEmiTransferHandler.decorate(screen, found);
        if (decorated != found) {
            cir.setReturnValue(decorated);
        }
    }
}
