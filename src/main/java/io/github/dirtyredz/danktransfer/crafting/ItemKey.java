package io.github.dirtyredz.danktransfer.crafting;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Item identity used by the planner: the item plus its complete data-component state, excluding
 * the count. Two stacks with the same key stack together and are interchangeable for a recipe slot.
 *
 * <p>Equality, {@link #matches} and the hash all use vanilla's own stacking identity
 * ({@link ItemStack#isSameItemSameComponents} / {@link ItemStack#hashItemAndComponents}), so
 * removed default components, custom data, names, enchantments and any modded component all
 * count, and a component-defined maximum stack size is honoured.
 */
public final class ItemKey {
    /** Count-1 copy of the source stack. Never handed out; {@link #toStack} returns copies. */
    private final ItemStack probe;
    private final int hash;

    private ItemKey(ItemStack probe) {
        this.probe = probe;
        this.hash = ItemStack.hashItemAndComponents(probe);
    }

    /** Key of a non-empty stack. The stack is copied, never retained. */
    public static ItemKey of(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("empty stack has no key");
        }
        return new ItemKey(stack.copyWithCount(1));
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameComponents(probe, stack);
    }

    /** A fresh stack of this item, with these components, holding {@code count} items. */
    public ItemStack toStack(int count) {
        return probe.copyWithCount(count);
    }

    /** Read-only view for ingredient tests. Callers must not mutate it. */
    ItemStack probe() {
        return probe;
    }

    /** The component-aware stack limit (vanilla's max stack size component included). */
    public int maxStackSize() {
        return probe.getMaxStackSize();
    }

    public Item item() {
        return probe.getItem();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ItemKey other && hash == other.hash
                && ItemStack.isSameItemSameComponents(probe, other.probe);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return probe.getItem() + probe.getComponentsPatch().toString();
    }
}
