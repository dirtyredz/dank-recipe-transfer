package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.compat.polymorph.PolymorphSelection;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.PatternSlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.Stack;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.DankExtraction;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.InventoryChange;
import io.github.dirtyredz.danktransfer.dank.CarriedDank;
import io.github.dirtyredz.danktransfer.dank.DankSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Server-side transfer: replans against live state, then moves items under a journal so any
 * failure restores every touched slot exactly. Never manufactures items: the result slot is
 * recomputed by the menu's normal logic.
 */
public final class TransferExecutor {
    /** Finds the player's Danks. Replaceable so GameTests can inject faults. */
    @FunctionalInterface
    public interface Discovery extends Function<ServerPlayer, List<CarriedDank>> {
    }

    private static final TransferExecutor DEFAULT = new TransferExecutor(DankSource::discover);

    private final Discovery discovery;

    public TransferExecutor(Discovery discovery) {
        this.discovery = discovery;
    }

    public static TransferExecutor standard() {
        return DEFAULT;
    }

    /**
     * Places {@code batches} complete sets of the {@code holder}'s recipe (or the maximum for
     * {@link TransferPlanner#MAX}) into the player's open vanilla crafting grid.
     * The caller has already checked the menu identity and request quantity.
     */
    public TransferOutcome execute(ServerPlayer player, CraftingMenuAccess access, RecipeHolder<CraftingRecipe> holder,
                                   int batches) {
        CraftingRecipe recipe = holder.value();
        if (!access.menu().getCarried().isEmpty()) {
            return TransferOutcome.CURSOR_OCCUPIED;
        }
        if (!access.menu().stillValid(player)) {
            return TransferOutcome.WRONG_MENU;
        }
        Optional<List<PatternSlot<ItemKey>>> pattern = RecipePattern.of(recipe, access.width(), access.height());
        if (pattern.isEmpty()) {
            return TransferOutcome.INVALID_RECIPE;
        }
        List<CarriedDank> danks = discovery.apply(player);
        List<List<Stack<ItemKey>>> contents = new ArrayList<>(danks.size());
        for (CarriedDank dank : danks) {
            List<Stack<ItemKey>> slots = new ArrayList<>();
            for (ItemStack stack : dank.copyContents()) {
                slots.add(CraftingMenuAccess.stackOf(stack));
            }
            contents.add(slots);
        }
        PlanInput<ItemKey> input = access.planInput(pattern.get(), contents, DankSource::isCarrier, player);
        TransferPlan<ItemKey> plan = TransferPlanner.plan(input, batches);
        if (!plan.ok()) {
            return TransferOutcome.of(plan.status());
        }
        for (int i = 0; i < access.gridSize(); i++) {
            ItemStack target = CraftingMenuAccess.toItemStack(plan.finalGrid().get(i));
            if (!target.isEmpty() && !access.gridSlot(i).mayPlace(target)) {
                return TransferOutcome.INVALID_RECIPE;
            }
        }
        return new Transaction(player, access, danks, plan).run(holder);
    }

    /** One journaled mutation. */
    private static final class Transaction {
        private final ServerPlayer player;
        private final CraftingMenuAccess access;
        private final List<CarriedDank> danks;
        private final TransferPlan<ItemKey> plan;
        private final Inventory inventory;
        private final CraftingContainer grid;
        private final List<ItemStack> gridJournal = new ArrayList<>();
        private final Map<Integer, ItemStack> inventoryJournal = new HashMap<>();
        /** One whole-store checkpoint per touched Dank, in first-touch order. */
        private final Map<CarriedDank, CarriedDank.Checkpoint> dankJournal = new IdentityHashMap<>();
        private final List<CarriedDank.Checkpoint> dankRestoreOrder = new ArrayList<>();
        private final Map<ItemKey, Long> extracted = new HashMap<>();

        Transaction(ServerPlayer player, CraftingMenuAccess access, List<CarriedDank> danks,
                    TransferPlan<ItemKey> plan) {
            this.player = player;
            this.access = access;
            this.danks = danks;
            this.plan = plan;
            this.inventory = player.getInventory();
            this.grid = access.craftContainer();
        }

        TransferOutcome run(RecipeHolder<CraftingRecipe> holder) {
            CraftingRecipe recipe = holder.value();
            for (int i = 0; i < access.gridSize(); i++) {
                gridJournal.add(access.gridSlot(i).getItem().copy());
            }
            for (InventoryChange<ItemKey> change : plan.inventoryChanges()) {
                ItemStack live = inventory.getItem(change.index());
                if (!sameAs(live, change.before())) {
                    return TransferOutcome.STALE;
                }
                inventoryJournal.put(change.index(), live.copy());
            }

            // 1. Danks first: the only step that can partially fail.
            for (DankExtraction<ItemKey> extraction : plan.dankExtractions()) {
                CarriedDank dank = danks.get(extraction.sourceId());
                if (!dankJournal.containsKey(dank)) {
                    CarriedDank.Checkpoint checkpoint = dank.checkpoint();
                    dankJournal.put(dank, checkpoint);
                    dankRestoreOrder.add(checkpoint);
                }
                int got = dank.extract(extraction.slot(), extraction.key(), extraction.count());
                extracted.merge(extraction.key(), (long) got, Long::sum);
                if (got != extraction.count()) {
                    rollback();
                    return TransferOutcome.EXTRACTION_FAILED;
                }
            }

            // 2. Loose inventory (extractions and returned grid items).
            for (InventoryChange<ItemKey> change : plan.inventoryChanges()) {
                inventory.setItem(change.index(), CraftingMenuAccess.toItemStack(change.after()));
            }

            // 3. The grid.
            for (int i = 0; i < access.gridSize(); i++) {
                grid.setItem(i, CraftingMenuAccess.toItemStack(plan.finalGrid().get(i)));
            }

            if (!conserved()) {
                rollback();
                return TransferOutcome.EXTRACTION_FAILED;
            }
            if (!recipe.matches(grid.asCraftInput(), player.level())) {
                rollback();
                return TransferOutcome.RECIPE_MISMATCH;
            }

            PolymorphSelection.select(player, holder);
            access.menu().slotsChanged(grid);
            access.menu().broadcastChanges();
            return TransferOutcome.SUCCESS;
        }

        /** Every item before the move (grid, touched inventory, Dank extractions) is still present after it. */
        private boolean conserved() {
            Map<ItemKey, Long> before = new HashMap<>(extracted);
            Map<ItemKey, Long> after = new HashMap<>();
            for (ItemStack stack : gridJournal) {
                count(before, stack);
            }
            for (ItemStack stack : inventoryJournal.values()) {
                count(before, stack);
            }
            for (int i = 0; i < access.gridSize(); i++) {
                count(after, grid.getItem(i));
            }
            for (int index : inventoryJournal.keySet()) {
                count(after, inventory.getItem(index));
            }
            return before.equals(after);
        }

        private static void count(Map<ItemKey, Long> totals, ItemStack stack) {
            if (!stack.isEmpty()) {
                totals.merge(ItemKey.of(stack), (long) stack.getCount(), Long::sum);
            }
        }

        private void rollback() {
            for (int i = dankRestoreOrder.size() - 1; i >= 0; i--) {
                dankRestoreOrder.get(i).restore();
            }
            for (Map.Entry<Integer, ItemStack> entry : inventoryJournal.entrySet()) {
                inventory.setItem(entry.getKey(), entry.getValue().copy());
            }
            for (int i = 0; i < gridJournal.size(); i++) {
                if (!ItemStack.matches(grid.getItem(i), gridJournal.get(i))) {
                    grid.setItem(i, gridJournal.get(i).copy());
                }
            }
        }

        private static boolean sameAs(ItemStack live, Stack<ItemKey> planned) {
            if (planned == null) {
                return live.isEmpty();
            }
            return planned.key().matches(live) && live.getCount() == planned.count();
        }
    }
}
