package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.crafting.PlanInput.Stack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The immutable result of {@link TransferPlanner#plan}: either a complete, count-conserving
 * allocation or the reason none exists.
 *
 * @param status           outcome
 * @param batches          complete recipe sets placed (0 unless {@link Status#OK})
 * @param missing          indices into {@link PlanInput#pattern()} that could not be satisfied
 * @param finalGrid        grid contents after the transfer, row-major ({@code null} = empty)
 * @param inventoryChanges every inventory slot whose contents change (extraction and returns)
 * @param dankExtractions  items to take from Dank stores, per store slot
 */
public record TransferPlan<K>(
        Status status,
        int batches,
        List<Integer> missing,
        List<Stack<K>> finalGrid,
        List<InventoryChange<K>> inventoryChanges,
        List<DankExtraction<K>> dankExtractions
) {
    public enum Status {
        /** A complete allocation exists. */
        OK,
        /** At least one ingredient is unavailable from grid, inventory and Danks. */
        MISSING,
        /** Displaced grid items would not fit in the loose inventory. */
        NO_RETURN_SPACE,
        /** The grid already holds full stacks of the recipe; nothing more fits. */
        GRID_FULL,
        /** The recipe does not fit this grid, or the input is malformed. */
        INVALID
    }

    public TransferPlan {
        missing = List.copyOf(missing);
        finalGrid = Collections.unmodifiableList(new ArrayList<>(finalGrid));
        inventoryChanges = List.copyOf(inventoryChanges);
        dankExtractions = List.copyOf(dankExtractions);
    }

    /** An inventory slot before and after the transfer. */
    public record InventoryChange<K>(int index, @Nullable Stack<K> before, @Nullable Stack<K> after) {
    }

    /** Take {@code count} of {@code key} from slot {@code slot} of Dank store {@code sourceId}. */
    public record DankExtraction<K>(int sourceId, int slot, K key, int count) {
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** True when the plan needs at least one item from a Dank. */
    public boolean usesDank() {
        return !dankExtractions.isEmpty();
    }

    static <K> TransferPlan<K> failure(Status status, List<Integer> missing) {
        return new TransferPlan<>(status, 0, missing, List.of(), List.of(), List.of());
    }
}
