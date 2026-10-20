package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import net.minecraft.network.chat.Component;

/** Every way a transfer request can end. Sent to the client by ordinal. */
public enum TransferOutcome {
    SUCCESS,
    MISSING,
    NO_RETURN_SPACE,
    GRID_FULL,
    INVALID_RECIPE,
    WRONG_MENU,
    STALE,
    CURSOR_OCCUPIED,
    ILLEGAL_QUANTITY,
    DUPLICATE,
    EXTRACTION_FAILED,
    RECIPE_MISMATCH,
    /** Client-side only: no acknowledgement arrived in time. */
    TIMEOUT;

    private static final TransferOutcome[] VALUES = values();

    public boolean success() {
        return this == SUCCESS;
    }

    /** Decodes an ordinal; anything unknown becomes {@link #INVALID_RECIPE}, never an exception. */
    public static TransferOutcome byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : INVALID_RECIPE;
    }

    public static TransferOutcome of(TransferPlan.Status status) {
        return switch (status) {
            case OK -> SUCCESS;
            case MISSING -> MISSING;
            case NO_RETURN_SPACE -> NO_RETURN_SPACE;
            case GRID_FULL -> GRID_FULL;
            case INVALID -> INVALID_RECIPE;
        };
    }

    public Component message() {
        return Component.translatable(DankRecipeTransfer.MOD_ID + ".outcome." + name().toLowerCase(java.util.Locale.ROOT));
    }
}
