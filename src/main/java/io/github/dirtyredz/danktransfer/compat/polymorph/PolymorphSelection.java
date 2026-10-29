package io.github.dirtyredz.danktransfer.compat.polymorph;

import com.illusivesoulworks.polymorph.api.PolymorphApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional Polymorph bridge: after a transfer, select the recipe the player asked for so a
 * conflicting recipe with the same inputs does not win the result slot.
 *
 * <p>Verified against Polymorph 1.2.0+1.21.1: {@code PolymorphApi#getPlayerRecipeData} returns the
 * player's data, and {@code PlayerRecipeData#selectRecipe(RecipeHolder)} stores the selection
 * (clearing the cached one) and syncs the player; the crafting result recomputation that follows
 * reads it. The holder keeps the requested recipe's id, so a same-input conflict cannot win.
 */
public final class PolymorphSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolymorphSelection.class);
    private static final boolean LOADED = ModList.get() != null && ModList.get().isLoaded("polymorph");

    private PolymorphSelection() {
    }

    public static void select(ServerPlayer player, RecipeHolder<CraftingRecipe> recipe) {
        if (!LOADED) {
            return;
        }
        try {
            Bridge.select(player, recipe);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Polymorph recipe selection failed; the result slot uses normal recipe lookup", e);
        }
    }

    /** Only loaded when Polymorph is present. */
    private static final class Bridge {
        static void select(ServerPlayer player, RecipeHolder<CraftingRecipe> recipe) {
            PolymorphApi.getInstance().getPlayerRecipeData(player).selectRecipe(recipe);
        }
    }
}
