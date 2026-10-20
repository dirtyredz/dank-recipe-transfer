package io.github.dirtyredz.danktransfer.crafting;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Item identity used by the planner: the item plus its exact NBT. Two stacks with the same key
 * stack together and are interchangeable for a recipe slot.
 */
public final class ItemKey {
    private final Item item;
    @Nullable
    private final CompoundTag tag;
    private final int hash;
    /** Count-1 stack for predicate tests. Never handed out; {@link #toStack} returns copies. */
    private final ItemStack probe;

    private ItemKey(Item item, @Nullable CompoundTag tag) {
        this.item = item;
        this.tag = tag;
        this.hash = 31 * System.identityHashCode(item) + Objects.hashCode(tag);
        this.probe = new ItemStack(item, 1);
        if (tag != null) {
            this.probe.setTag(tag.copy());
        }
    }

    /** Key of a non-empty stack. The stack is not retained. */
    public static ItemKey of(ItemStack stack) {
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("empty stack has no key");
        }
        CompoundTag tag = stack.getTag();
        return new ItemKey(stack.getItem(), tag == null ? null : tag.copy());
    }

    public boolean matches(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == item && Objects.equals(stack.getTag(), tag);
    }

    /** A fresh stack of this item with {@code count} items. */
    public ItemStack toStack(int count) {
        ItemStack stack = probe.copy();
        stack.setCount(count);
        return stack;
    }

    /** Read-only view for ingredient tests. Callers must not mutate it. */
    ItemStack probe() {
        return probe;
    }

    public int maxStackSize() {
        return probe.getMaxStackSize();
    }

    public Item item() {
        return item;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof ItemKey other && item == other.item && Objects.equals(tag, other.tag);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return item + (tag == null ? "" : tag.toString());
    }
}
