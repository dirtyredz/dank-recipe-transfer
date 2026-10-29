package io.github.dirtyredz.danktransfer.dank;

import io.github.dirtyredz.danktransfer.crafting.ItemKey;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One distinct, validated Dank store reachable through a carrier in the player's inventory.
 * Server only. Implementations never hand out live stack references.
 */
public interface CarriedDank {
    /** The Dank frequency (its storage id). */
    int frequency();

    /** Copied contents, one entry per storage slot ({@link ItemStack#EMPTY} when empty). */
    List<ItemStack> copyContents();

    /** A copy of one slot. */
    ItemStack copySlot(int slot);

    /**
     * Extracts exactly {@code count} items of {@code key} through the store's normal extraction
     * path, in as many capped calls as needed, starting at {@code slot}. Dank 20 can re-sort a
     * store after every change (its auto-sort setting), so once {@code slot} no longer holds the key
     * the remaining items come from any other slot holding it.
     *
     * @return how many were actually extracted (less than {@code count} means failure)
     */
    int extract(int slot, ItemKey key, int count);

    /**
     * Captures the whole store (contents and ghost locks) before its first change, so a rollback
     * restores it exactly even after an auto-sort rearranged slots.
     */
    Checkpoint checkpoint();

    /** Whether the store's saved data is marked dirty (pending a save to disk). */
    boolean isDirty();

    /** A captured store state. */
    interface Checkpoint {
        /**
         * Puts the exact captured contents back. Rollback only: this bypasses insertion rules on
         * purpose, because ordinary reinsertion can refuse an item that was already stored.
         */
        void restore();
    }
}
