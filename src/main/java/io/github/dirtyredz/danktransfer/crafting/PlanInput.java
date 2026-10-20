package io.github.dirtyredz.danktransfer.crafting;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Minecraft- and viewer-neutral description of one transfer problem.
 *
 * <p>{@code K} is an item identity (item plus NBT). The server and the client both build one of
 * these from live state, so the same {@link TransferPlanner} decides both the button state and
 * the real transfer.
 *
 * @param gridWidth    crafting grid width (2 or 3 for vanilla)
 * @param gridHeight   crafting grid height
 * @param grid         current grid contents, row-major, {@code null} for an empty slot
 * @param pattern      where each recipe ingredient must go
 * @param inventory    loose player inventory slots, in extraction priority order
 * @param danks        distinct Dank stores, in extraction priority order
 * @param maxStackSize normal stack size of an item identity
 * @param slotLimit    per-slot limit of the grid and inventory containers (64 for vanilla)
 */
public record PlanInput<K>(
        int gridWidth,
        int gridHeight,
        List<Stack<K>> grid,
        List<PatternSlot<K>> pattern,
        List<InventorySlot<K>> inventory,
        List<DankStore<K>> danks,
        ToIntFunction<K> maxStackSize,
        int slotLimit
) {
    public PlanInput {
        Objects.requireNonNull(maxStackSize, "maxStackSize");
        grid = nullableCopy(grid);
        pattern = List.copyOf(pattern);
        inventory = List.copyOf(inventory);
        danks = List.copyOf(danks);
    }

    /** An immutable stack. {@code count} may exceed the item's stack size only inside a Dank. */
    public record Stack<K>(K key, int count) {
        public Stack {
            Objects.requireNonNull(key, "key");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive: " + count);
            }
        }

        public Stack<K> withCount(int newCount) {
            return new Stack<>(key, newCount);
        }
    }

    /**
     * One recipe ingredient placed in the grid.
     *
     * @param gridIndex       row-major grid slot that must hold it
     * @param ingredientIndex index in the recipe's ingredient list (viewers map this to their slots)
     * @param accepts         the recipe ingredient predicate
     */
    public record PatternSlot<K>(int gridIndex, int ingredientIndex, Predicate<K> accepts) {
        public PatternSlot {
            Objects.requireNonNull(accepts, "accepts");
        }
    }

    /**
     * A loose inventory slot.
     *
     * @param index    the slot's index in the player's inventory
     * @param stack    its contents, {@code null} when empty
     * @param eligible false for slots that must never be touched (for example the Dank carriers)
     */
    public record InventorySlot<K>(int index, @Nullable Stack<K> stack, boolean eligible) {
    }

    /**
     * One distinct Dank store.
     *
     * @param sourceId stable id of the store within this plan (its position in discovery order)
     * @param slots    copied slot contents, {@code null} for an empty slot
     */
    public record DankStore<K>(int sourceId, List<Stack<K>> slots) {
        public DankStore {
            slots = nullableCopy(slots);
        }
    }

    private static <T> List<T> nullableCopy(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }
}
