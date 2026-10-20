package io.github.dirtyredz.danktransfer.compat.jei;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers nothing; it only captures JEI's transfer helper so the decorated handlers can build
 * JEI-native errors. Handler selection is decorated by {@code JeiTransferLookupMixin}, because JEI
 * allows one handler per menu and recipe type and the native ones are already registered.
 */
@JeiPlugin
public final class DankJeiPlugin implements IModPlugin {
    @Override
    @SuppressWarnings("removal") // the (namespace, path) constructor keeps Forge 47.0-47.2 support
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(DankRecipeTransfer.MOD_ID, "jei");
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        DankJeiTransferHandler.setHelper(registration.getTransferHelper());
    }
}
