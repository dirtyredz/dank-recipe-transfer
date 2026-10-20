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
     * Extracts exactly {@code count} items of {@code key} from {@code slot} through the store's
     * normal extraction path, in as many capped calls as needed.
     *
     * @return how many were actually extracted (less than {@code count} means failure)
     */
    int extract(int slot, ItemKey key, int count);

    /**
     * Puts the exact original stack back into {@code slot}. Rollback only: this bypasses insertion
     * rules on purpose, because ordinary reinsertion can refuse an item that was already stored.
     */
    void restore(int slot, ItemStack original);

    /** Whether the store's saved data is marked dirty (pending a save to disk). */
    boolean isDirty();
}
