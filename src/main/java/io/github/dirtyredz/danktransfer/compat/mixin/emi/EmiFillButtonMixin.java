package io.github.dirtyredz.danktransfer.compat.mixin.emi;

import dev.emi.emi.api.widget.RecipeFillButtonWidget;
import io.github.dirtyredz.danktransfer.client.ClientTransfer;
import io.github.dirtyredz.danktransfer.compat.emi.DankEmiTransferHandler;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * EMI computes a fill button's {@code canFill} and tooltip once, when the recipe page opens. A
 * Dank snapshot that arrives later would leave the button disabled until the page is reopened, so
 * the cached state is refreshed before render, tooltip and click: at most once per client tick,
 * and only for recipes this mod's handler serves. The refresh never transfers anything.
 */
@Mixin(value = RecipeFillButtonWidget.class, remap = false)
public abstract class EmiFillButtonMixin {
    @Shadow
    private boolean canFill;

    @Shadow
    private List<ClientTooltipComponent> tooltip;

    @Unique
    private long dankRecipeTransfer$lastTick = Long.MIN_VALUE;

    @Unique
    private int dankRecipeTransfer$lastSnapshot = Integer.MIN_VALUE;

    @Inject(method = {"getTextureOffset", "getTooltip", "mouseClicked"}, at = @At("HEAD"))
    private void dankRecipeTransfer$refresh(CallbackInfoReturnable<?> cir) {
        long tick = ClientTransfer.ticks();
        int snapshot = ClientTransfer.snapshotChanges();
        if (tick == dankRecipeTransfer$lastTick && snapshot == dankRecipeTransfer$lastSnapshot) {
            return;
        }
        dankRecipeTransfer$lastTick = tick;
        dankRecipeTransfer$lastSnapshot = snapshot;
        DankEmiTransferHandler.ButtonState state =
                DankEmiTransferHandler.refreshButton(((RecipeButtonWidgetAccessor) (Object) this).dankRecipeTransfer$recipe());
        if (state != null) {
            canFill = state.canFill();
            tooltip = state.tooltip();
        }
    }
}
