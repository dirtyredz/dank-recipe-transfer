package io.github.dirtyredz.danktransfer.compat.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.handler.CraftingRecipeHandler;
import dev.emi.emi.handler.InventoryRecipeHandler;
import dev.emi.emi.registry.EmiRecipeFiller;
import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.client.ClientTransfer;
import io.github.dirtyredz.danktransfer.client.ClientTransfer.Preflight;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan;
import io.github.dirtyredz.danktransfer.crafting.TransferPlanner;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps EMI's native {@link CraftingRecipeHandler} / {@link InventoryRecipeHandler} on their exact
 * vanilla menus. For a fill-button fill into the grid (destination {@code NONE}) it plans against
 * the Dank snapshot; everything else (quick-craft to cursor or inventory, unsupported recipes,
 * the craftables sidebar) goes to the native handler with the ordinary inventory, so augmented
 * availability never authorises a native transfer that cannot reach the Danks.
 */
public final class DankEmiTransferHandler<T extends AbstractContainerMenu> implements StandardRecipeHandler<T> {
    private static final Map<EmiRecipeHandler<?>, DankEmiTransferHandler<?>> WRAPPERS = new IdentityHashMap<>();

    private final StandardRecipeHandler<T> delegate;

    private DankEmiTransferHandler(StandardRecipeHandler<T> delegate) {
        this.delegate = delegate;
    }

    /** Decorates native handlers in EMI's list, preserving order and every third-party handler. */
    @SuppressWarnings("unchecked")
    public static <T extends AbstractContainerMenu> List<EmiRecipeHandler<T>> decorate(
            @Nullable AbstractContainerScreen<T> screen, List<EmiRecipeHandler<T>> handlers) {
        if (screen == null || handlers.isEmpty()) {
            return handlers;
        }
        Class<?> menuClass = screen.getMenu().getClass();
        List<EmiRecipeHandler<T>> result = null;
        for (int i = 0; i < handlers.size(); i++) {
            EmiRecipeHandler<T> handler = handlers.get(i);
            boolean table = handler.getClass() == CraftingRecipeHandler.class && menuClass == CraftingMenu.class;
            boolean inventory = handler.getClass() == InventoryRecipeHandler.class && menuClass == InventoryMenu.class;
            if (table || inventory) {
                if (result == null) {
                    result = new ArrayList<>(handlers);
                }
                StandardRecipeHandler<T> standard = (StandardRecipeHandler<T>) handler;
                result.set(i, (EmiRecipeHandler<T>) WRAPPERS.computeIfAbsent(handler, h -> new DankEmiTransferHandler<>(standard)));
            }
        }
        return result == null ? handlers : result;
    }

    // ---- pure delegation ----

    @Override
    public List<Slot> getInputSources(T handler) {
        return delegate.getInputSources(handler);
    }

    @Override
    public List<Slot> getCraftingSlots(T handler) {
        return delegate.getCraftingSlots(handler);
    }

    @Override
    public List<Slot> getCraftingSlots(EmiRecipe recipe, T handler) {
        return delegate.getCraftingSlots(recipe, handler);
    }

    @Override
    public @Nullable Slot getOutputSlot(T handler) {
        return delegate.getOutputSlot(handler);
    }

    @Override
    public EmiPlayerInventory getInventory(AbstractContainerScreen<T> screen) {
        return delegate.getInventory(screen);
    }

    @Override
    public boolean supportsRecipe(EmiRecipe recipe) {
        return delegate.supportsRecipe(recipe);
    }

    @Override
    public boolean alwaysDisplaySupport(EmiRecipe recipe) {
        return delegate.alwaysDisplaySupport(recipe);
    }

    // ---- Dank-aware behaviour ----

    /** The preflight for a fill-button fill into the grid (any state), or null when unsupported. */
    @Nullable
    private Preflight preflight(EmiRecipe recipe, EmiCraftContext<T> context) {
        if (context.getType() != EmiCraftContext.Type.FILL_BUTTON
                || context.getDestination() != EmiCraftContext.Destination.NONE
                || !delegate.supportsRecipe(recipe)) {
            return null;
        }
        if (!(recipe.getBackingRecipe() instanceof CraftingRecipe crafting)) {
            return null;
        }
        int amount = context.getAmount();
        int batches = amount == Integer.MAX_VALUE ? TransferPlanner.MAX : Math.max(1, amount);
        Preflight preflight = ClientTransfer.preflight(context.getScreenHandler(), crafting, batches);
        return preflight.state() == ClientTransfer.State.UNSUPPORTED ? null : preflight;
    }

    @Override
    public boolean canCraft(EmiRecipe recipe, EmiCraftContext<T> context) {
        Preflight preflight = preflight(recipe, context);
        if (preflight != null && preflight.ready() && preflight.plan().ok()) {
            return true;
        }
        return delegate.canCraft(recipe, context);
    }

    @Override
    public boolean craft(EmiRecipe recipe, EmiCraftContext<T> context) {
        Preflight preflight = preflight(recipe, context);
        if (preflight == null || !preflight.needsDank()) {
            return delegate.craft(recipe, context);
        }
        CraftingRecipe crafting = (CraftingRecipe) recipe.getBackingRecipe();
        int amount = context.getAmount();
        int batches = amount == Integer.MAX_VALUE ? TransferPlanner.MAX : Math.max(1, amount);
        boolean sent = ClientTransfer.send(context.getScreenHandler(), crafting, batches, outcome -> {
        });
        if (sent) {
            Minecraft.getInstance().setScreen(context.getScreen());
        }
        return sent;
    }

    @Override
    public List<ClientTooltipComponent> getTooltip(EmiRecipe recipe, EmiCraftContext<T> context) {
        Preflight preflight = preflight(recipe, context);
        if (preflight != null && preflight.ready()) {
            TransferPlan.Status status = preflight.plan().status();
            if (status == TransferPlan.Status.OK) {
                return List.of();
            }
            if (status == TransferPlan.Status.NO_RETURN_SPACE) {
                return List.of(line("emi.no_return_space"));
            }
        }
        List<ClientTooltipComponent> tooltip = delegate.getTooltip(recipe, context);
        if (preflight != null && !preflight.ready() && !tooltip.isEmpty()) {
            List<ClientTooltipComponent> withSync = new ArrayList<>(tooltip);
            withSync.add(line("syncing"));
            return withSync;
        }
        return tooltip;
    }

    @Override
    public void render(EmiRecipe recipe, EmiCraftContext<T> context, List<Widget> widgets, GuiGraphics draw) {
        Preflight current = preflight(recipe, context);
        if (current == null || !current.ready()) {
            delegate.render(recipe, context, widgets, draw);
            return;
        }
        // Rendering only: highlight what is missing from inventory plus Danks.
        T menu = context.getScreenHandler();
        List<EmiStack> stacks = new ArrayList<>();
        for (Slot slot : delegate.getInputSources(menu)) {
            stacks.add(EmiStack.of(slot.getItem()));
        }
        for (Entry<ItemStack> entry : ClientTransfer.snapshotEntries(menu.containerId)) {
            stacks.add(EmiStack.of(entry.prototype(), entry.count()));
        }
        StandardRecipeHandler.renderMissing(recipe, new EmiPlayerInventory(stacks), widgets, draw);
    }

    private static ClientTooltipComponent line(String key) {
        return ClientTooltipComponent.create(Component.translatable(DankRecipeTransfer.MOD_ID + "." + key).getVisualOrderText());
    }

    // ---- fill button refresh ----

    /** Fresh fill-button state, as RecipeFillButtonWidget computes it at construction. */
    public record ButtonState(boolean canFill, List<ClientTooltipComponent> tooltip) {
    }

    /**
     * Recomputes a fill button's state when this mod's handler serves the recipe; null otherwise
     * (the native cached state stays untouched). Performs no transfer.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    public static ButtonState refreshButton(EmiRecipe recipe) {
        AbstractContainerScreen screen = EmiApi.getHandledScreen();
        if (screen == null) {
            return null;
        }
        EmiRecipeHandler handler = EmiRecipeFiller.getFirstValidHandler(recipe, screen);
        if (!(handler instanceof DankEmiTransferHandler<?>)) {
            return null;
        }
        try {
            EmiPlayerInventory inventory = handler.getInventory(screen);
            boolean applicable = handler.supportsRecipe(recipe);
            EmiCraftContext context = new EmiCraftContext<>(screen, inventory, EmiCraftContext.Type.FILL_BUTTON);
            boolean canFill = applicable && handler.canCraft(recipe, context);
            List<ClientTooltipComponent> tooltip = applicable ? handler.getTooltip(recipe, context)
                    : List.of(ClientTooltipComponent.create(Component.translatable("emi.inapplicable").getVisualOrderText()));
            return new ButtonState(canFill, tooltip);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
