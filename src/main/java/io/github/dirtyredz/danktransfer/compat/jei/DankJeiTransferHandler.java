package io.github.dirtyredz.danktransfer.compat.jei;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.client.ClientTransfer;
import io.github.dirtyredz.danktransfer.client.ClientTransfer.Preflight;
import io.github.dirtyredz.danktransfer.crafting.CraftingMenuAccess;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan;
import io.github.dirtyredz.danktransfer.crafting.TransferPlanner;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferContext;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.RecipeTransferResult;
import mezz.jei.library.transfer.BasicRecipeTransferHandler;
import mezz.jei.library.transfer.PlayerRecipeTransferHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Wraps JEI's native vanilla crafting handler. Translates JEI checks and requests to
 * {@link ClientTransfer}; anything it does not handle goes to the native handler unchanged.
 *
 * <ul>
 *   <li>Preflight ({@code doTransfer == false}) never sends anything.</li>
 *   <li>A plan that needs no Dank items uses JEI's native transfer.</li>
 *   <li>A Dank transfer sends exactly one companion request and completes JEI's context exactly
 *       once, when the acknowledgement arrives.</li>
 * </ul>
 */
public final class DankJeiTransferHandler<C extends AbstractContainerMenu, R> implements IRecipeTransferHandler<C, R> {
    private static final Map<IRecipeTransferHandler<?, ?>, DankJeiTransferHandler<?, ?>> WRAPPERS = new WeakHashMap<>();
    @Nullable
    private static IRecipeTransferHandlerHelper helper;

    private final IRecipeTransferHandler<C, R> delegate;
    private final IRecipeTransferHandlerHelper transferHelper;

    private DankJeiTransferHandler(IRecipeTransferHandler<C, R> delegate, IRecipeTransferHandlerHelper transferHelper) {
        this.delegate = delegate;
        this.transferHelper = transferHelper;
    }

    static void setHelper(IRecipeTransferHandlerHelper transferHelper) {
        helper = transferHelper;
        WRAPPERS.clear();
    }

    /** Decorates only JEI's native table and inventory crafting handlers on their exact menus. */
    @SuppressWarnings("unchecked")
    public static <C extends AbstractContainerMenu, R> Optional<IRecipeTransferHandler<C, R>> decorate(
            C container, RecipeType<R> recipeType, Optional<IRecipeTransferHandler<C, R>> found) {
        IRecipeTransferHandlerHelper current = helper;
        if (current == null || found.isEmpty() || !RecipeTypes.CRAFTING.equals(recipeType)) {
            return found;
        }
        IRecipeTransferHandler<C, R> handler = found.get();
        boolean supported = CraftingMenuAccess.isSupported(container);
        boolean table = supported && handler instanceof BasicRecipeTransferHandler<?, ?> && container instanceof CraftingMenu;
        boolean inventory = supported && handler instanceof PlayerRecipeTransferHandler && container instanceof InventoryMenu;
        if (!table && !inventory) {
            return found;
        }
        synchronized (WRAPPERS) {
            DankJeiTransferHandler<?, ?> wrapper = WRAPPERS.computeIfAbsent(handler, h -> new DankJeiTransferHandler<>(handler, current));
            return Optional.of((IRecipeTransferHandler<C, R>) wrapper);
        }
    }

    @Override
    public Class<? extends C> getContainerClass() {
        return delegate.getContainerClass();
    }

    @Override
    public Optional<MenuType<C>> getMenuType() {
        return delegate.getMenuType();
    }

    @Override
    public RecipeType<R> getRecipeType() {
        return delegate.getRecipeType();
    }

    /** Legacy overload, still used by JEMI. There is no context to complete. */
    @SuppressWarnings("removal")
    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(C container, R recipe, IRecipeSlotsView recipeSlots, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        return handle(container, recipe, recipeSlots, maxTransfer, doTransfer, null,
                () -> delegate.transferRecipe(container, recipe, recipeSlots, player, maxTransfer, doTransfer));
    }

    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(IRecipeTransferContext<R, C> context, boolean doTransfer) {
        return handle(context.getContainer(), context.getRecipe(), context.getRecipeSlots(), context.isMaxTransfer(),
                doTransfer, context, () -> delegate.transferRecipe(context, doTransfer));
    }

    @Nullable
    private IRecipeTransferError handle(C container, R recipe, IRecipeSlotsView recipeSlots, boolean maxTransfer,
                                        boolean doTransfer, @Nullable IRecipeTransferContext<R, C> context,
                                        Supplier<IRecipeTransferError> nativeTransfer) {
        int batches = maxTransfer ? TransferPlanner.MAX : 1;
        Preflight preflight = ClientTransfer.preflight(container, recipe, batches);
        if (!preflight.ready()) {
            return nativeTransfer.get();
        }
        TransferPlan<?> plan = preflight.plan();
        switch (plan.status()) {
            case OK -> {
                if (!plan.usesDank()) {
                    return nativeTransfer.get();
                }
                if (ClientTransfer.busy()) {
                    return transferHelper.createUserErrorWithTooltip(message("busy"));
                }
                if (doTransfer) {
                    boolean sent = ClientTransfer.send(container, (CraftingRecipe) recipe, batches, outcome -> {
                        if (context != null) {
                            context.completeRecipeTransfer(outcome.success() ? RecipeTransferResult.SUCCESS : RecipeTransferResult.REJECTED);
                        }
                    });
                    if (!sent) {
                        return transferHelper.createUserErrorWithTooltip(message("busy"));
                    }
                }
                return null;
            }
            case MISSING -> {
                List<IRecipeSlotView> missing = missingSlots((CraftingRecipe) recipe, recipeSlots, preflight.missingIngredientIndices());
                Component text = Component.translatable("jei.tooltip.error.recipe.transfer.missing");
                return missing.isEmpty() ? transferHelper.createUserErrorWithTooltip(text)
                        : transferHelper.createUserErrorForMissingSlots(text, missing);
            }
            case NO_RETURN_SPACE -> {
                return transferHelper.createUserErrorWithTooltip(Component.translatable("jei.tooltip.error.recipe.transfer.inventory.full"));
            }
            default -> {
                return nativeTransfer.get();
            }
        }
    }

    /**
     * Maps recipe ingredient indices to JEI's displayed slots. JEI reports which ingredient each
     * of its gui slots shows, so the match is by ingredient identity rather than a copied layout.
     */
    private List<IRecipeSlotView> missingSlots(CraftingRecipe recipe, IRecipeSlotsView recipeSlots, List<Integer> ingredientIndices) {
        List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        Map<Integer, Ingredient> guiSlots = transferHelper.getGuiSlotIndexToIngredientMap(recipe);
        List<IRecipeSlotView> result = new ArrayList<>();
        for (int ingredientIndex : ingredientIndices) {
            Ingredient ingredient = recipe.getIngredients().get(ingredientIndex);
            for (Map.Entry<Integer, Ingredient> e : guiSlots.entrySet()) {
                int gui = e.getKey();
                if (e.getValue() == ingredient && gui >= 0 && gui < inputs.size() && !result.contains(inputs.get(gui))) {
                    result.add(inputs.get(gui));
                    break;
                }
            }
        }
        return result;
    }

    private static Component message(String key) {
        return Component.translatable(DankRecipeTransfer.MOD_ID + ".jei." + key);
    }
}
