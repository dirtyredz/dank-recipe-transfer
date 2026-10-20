package io.github.dirtyredz.danktransfer.compat;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies a viewer mixin only on the physical client and only when its viewer is installed, so the
 * mod loads with JEI, EMI, both or neither, and dedicated servers never touch viewer classes.
 */
public final class MixinGate implements IMixinConfigPlugin {
    private static final String PACKAGE = "io.github.dirtyredz.danktransfer.compat.mixin.";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(PACKAGE)) {
            return false;
        }
        String rest = mixinClassName.substring(PACKAGE.length());
        if (rest.startsWith("jei.")) {
            return onClient() && isLoaded("jei");
        }
        if (rest.startsWith("emi.")) {
            return onClient() && isLoaded("emi");
        }
        return false;
    }

    private static boolean onClient() {
        return FMLLoader.getDist() == Dist.CLIENT;
    }

    private static boolean isLoaded(String modId) {
        LoadingModList mods = FMLLoader.getLoadingModList();
        return mods != null && mods.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
