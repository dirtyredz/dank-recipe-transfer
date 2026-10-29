package io.github.dirtyredz.danktransfer.dank;

import io.github.dirtyredz.danktransfer.crafting.ItemKey;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import tfar.dankstorage.item.DankItem;
import tfar.dankstorage.utils.CommonUtils;
import tfar.dankstorage.utils.DankStats;
import tfar.dankstorage.world.DankInventoryForge;
import tfar.dankstorage.world.DankSavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only class that talks to Dank Storage (1.21.1, build 20).
 *
 * <p>Discovery reads the 36 main/hotbar slots, then the offhand. A carrier counts only when it has
 * a frequency (the {@code dankstorage:frequency} component), its storage already exists, and the
 * carrier's tier is not lower than the storage tier (Dank refuses to open such a link). Discovery
 * never creates, upgrades or relinks storage: it reads saved data with {@link DankSavedData#get}
 * (backed by {@code DimensionDataStorage#get}), never {@code getOrCreate}, and never writes the
 * carrier's components.
 */
public final class DankSource {
    private DankSource() {
    }

    /** True for any Dank item. Safe on both sides. */
    public static boolean isCarrier(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof DankItem;
    }

    /** Distinct, valid Danks carried by {@code player}, in discovery order. Server only. */
    public static List<CarriedDank> discover(ServerPlayer player) {
        MinecraftServer server = player.server;
        Inventory inventory = player.getInventory();
        List<ItemStack> carriers = new ArrayList<>(37);
        for (int i = 0; i < inventory.items.size(); i++) {
            carriers.add(inventory.items.get(i));
        }
        carriers.addAll(inventory.offhand);

        Set<Integer> seen = new HashSet<>();
        List<CarriedDank> result = new ArrayList<>();
        for (ItemStack carrier : carriers) {
            if (!isCarrier(carrier)) {
                continue;
            }
            int frequency = DankItem.getFrequency(carrier);
            // DankSavedData.get throws for negative ids; INVALID (-1) means "never linked".
            if (frequency <= CommonUtils.INVALID || seen.contains(frequency)) {
                continue;
            }
            DankSavedData data = DankSavedData.get(frequency, server);
            if (data == null || data.getStats() == DankStats.zero) {
                continue;
            }
            DankStats carrierTier = ((DankItem) carrier.getItem()).stats;
            if (carrierTier.ordinal() < data.getStats().ordinal()) {
                continue;
            }
            // Dank's in-memory view of the existing saved contents; it creates no storage.
            if (!(data.getOrCreateInventory() instanceof DankInventoryForge handler)) {
                continue;
            }
            seen.add(frequency);
            result.add(new Store(frequency, handler, data));
        }
        return result;
    }

    /** A Dank store backed by Dank's native NeoForge item handler. */
    private record Store(int frequency, DankInventoryForge handler, DankSavedData data) implements CarriedDank {
        @Override
        public List<ItemStack> copyContents() {
            List<ItemStack> copy = new ArrayList<>(handler.getSlots());
            for (int i = 0; i < handler.getSlots(); i++) {
                copy.add(handler.getStackInSlot(i).copy());
            }
            return copy;
        }

        @Override
        public ItemStack copySlot(int slot) {
            return slot < 0 || slot >= handler.getSlots() ? ItemStack.EMPTY : handler.getStackInSlot(slot).copy();
        }

        @Override
        public int extract(int slot, ItemKey key, int count) {
            if (slot < 0 || slot >= handler.getSlots()) {
                return 0;
            }
            int extracted = 0;
            int current = slot;
            // Each extractItem call is capped at the item's normal stack size; Danks hold more.
            while (extracted < count) {
                if (!key.matches(handler.getStackInSlot(current))) {
                    current = find(key);
                    if (current < 0) {
                        break;
                    }
                }
                ItemStack live = handler.getStackInSlot(current);
                int ask = Math.min(count - extracted, live.getMaxStackSize());
                ItemStack simulated = handler.extractItem(current, ask, true);
                if (simulated.isEmpty() || !key.matches(simulated)) {
                    break;
                }
                ItemStack actual = handler.extractItem(current, simulated.getCount(), false);
                if (!key.matches(actual)) {
                    break;
                }
                extracted += actual.getCount();
                if (actual.getCount() != simulated.getCount()) {
                    break;
                }
            }
            return extracted;
        }

        private int find(ItemKey key) {
            for (int i = 0; i < handler.getSlots(); i++) {
                if (key.matches(handler.getStackInSlot(i))) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public Checkpoint checkpoint() {
            List<ItemStack> items = new ArrayList<>(handler.getSlots());
            List<ItemStack> ghosts = new ArrayList<>(handler.getSlots());
            for (int i = 0; i < handler.getSlots(); i++) {
                items.add(handler.getItemDank(i).copy());
                ghosts.add(handler.getGhostItem(i).copy());
            }
            return () -> {
                // Whole lists, not slot by slot: each per-slot write triggers Dank's auto-sort, which
                // would move not-yet-restored stacks underneath the remaining writes.
                handler.setItemsDank(copyOf(items));
                handler.setGhostItems(copyOf(ghosts));
                data.setDirty();
            };
        }

        private static NonNullList<ItemStack> copyOf(List<ItemStack> stacks) {
            NonNullList<ItemStack> copy = NonNullList.withSize(stacks.size(), ItemStack.EMPTY);
            for (int i = 0; i < stacks.size(); i++) {
                copy.set(i, stacks.get(i).copy());
            }
            return copy;
        }

        @Override
        public boolean isDirty() {
            return data.isDirty();
        }
    }
}
