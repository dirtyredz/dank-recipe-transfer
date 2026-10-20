package io.github.dirtyredz.danktransfer.dank;

import io.github.dirtyredz.danktransfer.crafting.ItemKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import tfar.dankstorage.inventory.DankInterface;
import tfar.dankstorage.item.CDankItem;
import tfar.dankstorage.utils.CommonUtils;
import tfar.dankstorage.utils.DankStats;
import tfar.dankstorage.world.CDankSavedData;
import tfar.dankstorage.world.DankInventoryForge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The only class that talks to Dank Storage (1.20.1-16).
 *
 * <p>Discovery reads the 36 main/hotbar slots, then the offhand. A carrier counts only when it has
 * a frequency, its storage already exists, and the carrier's tier is not lower than the storage
 * tier (Dank refuses to open such a link). Discovery never creates, upgrades or relinks storage:
 * it reads saved data with {@code DimensionDataStorage#get}, never {@code computeIfAbsent}, and
 * never calls {@code CommonUtils#getBagInventory} (which creates fresh storage).
 */
public final class DankSource {
    /** Saved-data name prefix Dank uses: {@code dankstorage/<frequency>}. */
    private static final String DATA_PREFIX = "dankstorage/";

    private DankSource() {
    }

    /** True for any Dank item. Safe on both sides. */
    public static boolean isCarrier(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof CDankItem;
    }

    /** Distinct, valid Danks carried by {@code player}, in discovery order. Server only. */
    public static List<CarriedDank> discover(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
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
            int frequency = CommonUtils.getFrequency(carrier);
            if (frequency <= CommonUtils.INVALID || seen.contains(frequency)) {
                continue;
            }
            CDankSavedData data = overworld.getDataStorage()
                    .get(tag -> CDankSavedData.loadStatic(tag, overworld), DATA_PREFIX + frequency);
            if (data == null) {
                continue;
            }
            DankInterface storage = data.createInventory(frequency);
            if (!(storage instanceof DankInventoryForge handler) || storage.getDankStats() == DankStats.zero) {
                continue;
            }
            DankStats carrierTier = CommonUtils.getDefaultStats(carrier);
            if (carrierTier.ordinal() < storage.getDankStats().ordinal()) {
                continue;
            }
            seen.add(frequency);
            result.add(new Store(frequency, handler, data));
        }
        return result;
    }

    /** A Dank store backed by Dank's native Forge item handler. */
    private record Store(int frequency, DankInventoryForge handler, CDankSavedData data) implements CarriedDank {
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
            // Forge caps each extractItem call at the item's normal stack size; Danks hold more.
            while (extracted < count) {
                ItemStack live = handler.getStackInSlot(slot);
                if (!key.matches(live)) {
                    break;
                }
                int ask = Math.min(count - extracted, live.getMaxStackSize());
                ItemStack simulated = handler.extractItem(slot, ask, true);
                if (simulated.isEmpty() || !key.matches(simulated)) {
                    break;
                }
                ItemStack actual = handler.extractItem(slot, simulated.getCount(), false);
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

        @Override
        public void restore(int slot, ItemStack original) {
            // setStackInSlot fires onContentsChanged, which saves to the Dank's saved data.
            handler.setStackInSlot(slot, original.copy());
        }

        @Override
        public boolean isDirty() {
            return data.isDirty();
        }
    }
}
