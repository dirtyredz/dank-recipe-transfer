package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.crafting.PlanInput;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.DankStore;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.InventorySlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.PatternSlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.Stack;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.DankExtraction;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.InventoryChange;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.Status;
import io.github.dirtyredz.danktransfer.crafting.TransferPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Planner rules with plain string item keys ("oak_planks", "stick#nbt", ...). */
class TransferPlannerTest {

    // ---- fixture helpers ----

    private static final Map<String, Integer> STACK_SIZES = Map.of("pearl", 16, "sword", 1);

    private static int maxStack(String key) {
        return STACK_SIZES.getOrDefault(key, 64);
    }

    private static Stack<String> s(String key, int count) {
        return new Stack<>(key, count);
    }

    private static Predicate<String> is(String... keys) {
        Set<String> set = Set.of(keys);
        return set::contains;
    }

    /** Builder for one planning problem. */
    private static final class Problem {
        final int w;
        final int h;
        final List<Stack<String>> grid;
        final List<PatternSlot<String>> pattern = new ArrayList<>();
        final List<InventorySlot<String>> inventory = new ArrayList<>();
        final List<DankStore<String>> danks = new ArrayList<>();

        Problem(int w, int h) {
            this.w = w;
            this.h = h;
            this.grid = new ArrayList<>(java.util.Collections.nCopies(w * h, null));
        }

        Problem needs(int gridIndex, Predicate<String> accepts) {
            pattern.add(new PatternSlot<>(gridIndex, pattern.size(), accepts));
            return this;
        }

        Problem grid(int index, String key, int count) {
            grid.set(index, s(key, count));
            return this;
        }

        Problem inv(String key, int count) {
            inventory.add(new InventorySlot<>(inventory.size(), key == null ? null : s(key, count), true));
            return this;
        }

        Problem emptyInv(int slots) {
            for (int i = 0; i < slots; i++) {
                inv(null, 0);
            }
            return this;
        }

        Problem carrier() {
            inventory.add(new InventorySlot<>(inventory.size(), s("dank_carrier", 1), false));
            return this;
        }

        @SafeVarargs
        final Problem dank(Stack<String>... slots) {
            danks.add(new DankStore<>(danks.size(), Arrays.asList(slots)));
            return this;
        }

        PlanInput<String> input() {
            return new PlanInput<>(w, h, grid, pattern, inventory, danks, TransferPlannerTest::maxStack, 64);
        }

        TransferPlan<String> plan(int batches) {
            PlanInput<String> in = input();
            TransferPlan<String> plan = TransferPlanner.plan(in, batches);
            if (plan.ok()) {
                assertConserved(in, plan);
            }
            return plan;
        }
    }

    /** Every item before (grid + inventory + extracted) equals every item after (grid + inventory). */
    private static void assertConserved(PlanInput<String> in, TransferPlan<String> plan) {
        Map<String, Long> before = new HashMap<>();
        Map<String, Long> after = new HashMap<>();
        in.grid().forEach(st -> add(before, st));
        in.inventory().forEach(slot -> add(before, slot.stack()));
        for (DankExtraction<String> e : plan.dankExtractions()) {
            before.merge(e.key(), (long) e.count(), Long::sum);
        }
        plan.finalGrid().forEach(st -> add(after, st));
        Map<Integer, Stack<String>> inventoryAfter = new HashMap<>();
        in.inventory().forEach(slot -> inventoryAfter.put(slot.index(), slot.stack()));
        for (InventoryChange<String> c : plan.inventoryChanges()) {
            inventoryAfter.put(c.index(), c.after());
        }
        inventoryAfter.values().forEach(st -> add(after, st));
        assertEquals(before, after, "items must be conserved");
        // No stack above its limit after the move.
        plan.finalGrid().forEach(st -> assertTrue(st == null || st.count() <= Math.min(64, maxStack(st.key()))));
        inventoryAfter.values().forEach(st -> assertTrue(st == null || st.count() <= Math.min(64, maxStack(st.key()))));
    }

    private static void add(Map<String, Long> totals, Stack<String> st) {
        if (st != null) {
            totals.merge(st.key(), (long) st.count(), Long::sum);
        }
    }

    private static long fromDank(TransferPlan<String> plan, String key) {
        return plan.dankExtractions().stream().filter(e -> e.key().equals(key)).mapToLong(DankExtraction::count).sum();
    }

    /** 3x3 "stick" recipe: two planks stacked vertically at grid 0 and 3. */
    private static Problem sticks() {
        return new Problem(3, 3).needs(0, is("oak_planks")).needs(3, is("oak_planks"));
    }

    // ---- tests ----

    @Test
    void dankOnlyInputs() {
        TransferPlan<String> plan = sticks().emptyInv(4).dank(s("oak_planks", 500)).plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(1, plan.batches());
        assertEquals(2, fromDank(plan, "oak_planks"));
        assertTrue(plan.usesDank());
        assertEquals(s("oak_planks", 1), plan.finalGrid().get(0));
        assertEquals(s("oak_planks", 1), plan.finalGrid().get(3));
    }

    @Test
    void looseInventoryBeforeDank() {
        TransferPlan<String> plan = sticks().inv("oak_planks", 1).dank(s("oak_planks", 10)).plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(1, fromDank(plan, "oak_planks"), "one from inventory, the rest from the Dank");
        assertEquals(List.of(new InventoryChange<>(0, s("oak_planks", 1), null)), plan.inventoryChanges());
    }

    @Test
    void inventoryAloneDoesNotUseDank() {
        TransferPlan<String> plan = sticks().inv("oak_planks", 5).dank(s("oak_planks", 10)).plan(1);
        assertTrue(plan.ok());
        assertFalse(plan.usesDank());
    }

    @Test
    void gridContentsReusedFirst() {
        // Grid holds planks in the wrong place plus junk; planks are reused, junk goes home.
        TransferPlan<String> plan = sticks().grid(8, "oak_planks", 2).grid(4, "dirt", 3)
                .emptyInv(2).dank(s("oak_planks", 100)).plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(0, fromDank(plan, "oak_planks"), "the grid's planks cover the recipe");
        assertEquals(null, plan.finalGrid().get(8));
        assertEquals(null, plan.finalGrid().get(4));
        assertEquals(List.of(new InventoryChange<>(0, null, s("dirt", 3))), plan.inventoryChanges());
    }

    @Test
    void multiplePartialStacks() {
        TransferPlan<String> plan = sticks().inv("oak_planks", 1).dank(s("oak_planks", 1), null, s("oak_planks", 3)).plan(2);
        assertEquals(Status.OK, plan.status());
        assertEquals(2, plan.batches());
        assertEquals(List.of(new DankExtraction<>(0, 0, "oak_planks", 1), new DankExtraction<>(0, 2, "oak_planks", 2)),
                plan.dankExtractions());
    }

    @Test
    void overlappingAlternativesNeverDoubleSpend() {
        // Slot A takes oak or birch, slot B takes only oak. One oak, one birch: A must take birch.
        Problem p = new Problem(2, 2).needs(0, is("oak_planks", "birch_planks")).needs(1, is("oak_planks"))
                .dank(s("oak_planks", 1), s("birch_planks", 1));
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(s("birch_planks", 1), plan.finalGrid().get(0));
        assertEquals(s("oak_planks", 1), plan.finalGrid().get(1));
    }

    @Test
    void overlappingAlternativesExhausted() {
        Problem p = new Problem(2, 2).needs(0, is("oak_planks", "birch_planks")).needs(1, is("oak_planks"))
                .dank(s("oak_planks", 1));
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.MISSING, plan.status());
        assertEquals(1, plan.missing().size());
    }

    @Test
    void nbtVariantsAreDistinct() {
        Problem p = new Problem(2, 2).needs(0, is("potion#healing")).dank(s("potion#swiftness", 5));
        assertEquals(Status.MISSING, p.plan(1).status());
        Problem q = new Problem(2, 2).needs(0, is("potion#healing")).dank(s("potion#swiftness", 5), s("potion#healing", 1));
        assertEquals(s("potion#healing", 1), q.plan(1).finalGrid().get(0));
    }

    @Test
    void repeatedIngredients() {
        // 2x2 crafting table recipe: four planks.
        Problem p = new Problem(2, 2);
        for (int i = 0; i < 4; i++) {
            p.needs(i, is("oak_planks"));
        }
        p.dank(s("oak_planks", 7));
        TransferPlan<String> plan = p.plan(TransferPlanner.MAX);
        assertEquals(Status.OK, plan.status());
        assertEquals(1, plan.batches(), "7 planks make one complete set of 4");
        assertEquals(4, fromDank(plan, "oak_planks"));
    }

    @Test
    void twoByTwoDimensions() {
        Problem tooBig = new Problem(2, 2).needs(5, is("x"));
        assertEquals(Status.INVALID, tooBig.plan(1).status());
    }

    @Test
    void repeatedClicksAddOneSetEach() {
        Problem p = sticks().grid(0, "oak_planks", 3).grid(3, "oak_planks", 3).dank(s("oak_planks", 10));
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(s("oak_planks", 4), plan.finalGrid().get(0));
        assertEquals(s("oak_planks", 4), plan.finalGrid().get(3));
        assertEquals(2, fromDank(plan, "oak_planks"));
    }

    @Test
    void finiteQuantityPreserved() {
        TransferPlan<String> plan = sticks().dank(s("oak_planks", 100)).plan(5);
        assertEquals(5, plan.batches());
        assertEquals(10, fromDank(plan, "oak_planks"));
    }

    @Test
    void finiteQuantityCappedByAvailability() {
        TransferPlan<String> plan = sticks().dank(s("oak_planks", 7)).plan(5);
        assertEquals(3, plan.batches());
    }

    @Test
    void maxLimitedByScarceIngredient() {
        Problem p = new Problem(3, 3).needs(0, is("iron")).needs(1, is("stick")).dank(s("iron", 500), s("stick", 9));
        assertEquals(9, p.plan(TransferPlanner.MAX).batches());
    }

    @Test
    void maxLimitedBySlotCapacity() {
        assertEquals(64, sticks().dank(s("oak_planks", 10_000)).plan(TransferPlanner.MAX).batches());
        Problem pearls = new Problem(2, 2).needs(0, is("pearl")).dank(s("pearl", 1000));
        assertEquals(16, pearls.plan(TransferPlanner.MAX).batches(), "ender pearls stack to 16");
    }

    @Test
    void maxOnTopOfMatchingGrid() {
        Problem p = sticks().grid(0, "oak_planks", 60).grid(3, "oak_planks", 62).dank(s("oak_planks", 100));
        TransferPlan<String> plan = p.plan(TransferPlanner.MAX);
        assertEquals(2, plan.batches());
        assertEquals(s("oak_planks", 62), plan.finalGrid().get(0));
        assertEquals(s("oak_planks", 64), plan.finalGrid().get(3));
    }

    @Test
    void fullGridReported() {
        Problem p = sticks().grid(0, "oak_planks", 64).grid(3, "oak_planks", 64).dank(s("oak_planks", 100));
        assertEquals(Status.GRID_FULL, p.plan(1).status());
    }

    @Test
    void fullInventoryRejectsBeforeMoving() {
        // Grid holds unrelated dirt; inventory has no space; Dank supplies the planks.
        Problem p = sticks().grid(4, "dirt", 5).inv("stone", 64).inv("stone", 64).dank(s("oak_planks", 10));
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.NO_RETURN_SPACE, plan.status());
        assertTrue(plan.inventoryChanges().isEmpty());
        assertTrue(plan.dankExtractions().isEmpty());
    }

    @Test
    void returnsUseSlotsFreedByExtraction() {
        // The only inventory slot holds the two planks the recipe consumes; it frees up for the dirt.
        Problem p = sticks().grid(4, "dirt", 5).inv("oak_planks", 2);
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(List.of(new InventoryChange<>(0, s("oak_planks", 2), s("dirt", 5))), plan.inventoryChanges());
    }

    @Test
    void returnsMergeIntoPartialStacks() {
        Problem p = sticks().grid(4, "dirt", 5).inv("dirt", 60).emptyInv(1).dank(s("oak_planks", 2));
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.OK, plan.status());
        assertEquals(List.of(new InventoryChange<>(0, s("dirt", 60), s("dirt", 64)),
                new InventoryChange<>(1, null, s("dirt", 1))), plan.inventoryChanges());
    }

    @Test
    void carriersAreNeverIngredients() {
        Problem p = new Problem(2, 2).needs(0, is("dank_carrier")).carrier();
        assertEquals(Status.MISSING, p.plan(1).status());
    }

    @Test
    void neverPlacesIncompleteSets() {
        Problem p = new Problem(3, 3).needs(0, is("iron")).needs(1, is("stick")).dank(s("iron", 5));
        TransferPlan<String> plan = p.plan(1);
        assertEquals(Status.MISSING, plan.status());
        assertEquals(List.of(1), plan.missing());
        assertTrue(plan.finalGrid().isEmpty());
        assertTrue(plan.dankExtractions().isEmpty());
    }

    @Test
    void inputsAreNotMutated() {
        Problem p = sticks().grid(4, "dirt", 5).inv("oak_planks", 2).dank(s("oak_planks", 5));
        PlanInput<String> in = p.input();
        String before = in.toString();
        TransferPlanner.plan(in, TransferPlanner.MAX);
        assertEquals(before, in.toString());
    }

    @Test
    void largeDankCounts() {
        Problem p = sticks().dank(s("oak_planks", Integer.MAX_VALUE), s("oak_planks", Integer.MAX_VALUE));
        TransferPlan<String> plan = p.plan(TransferPlanner.MAX);
        assertEquals(64, plan.batches());
        assertEquals(128, fromDank(plan, "oak_planks"));
    }

    @Test
    void searchBudgetBoundsPathologicalAlternatives() {
        // Nine slots, twelve alternatives each, one item of each: infeasible and must return quickly.
        String[] woods = new String[12];
        for (int i = 0; i < woods.length; i++) {
            woods[i] = "planks_" + i;
        }
        Problem p = new Problem(3, 3);
        for (int i = 0; i < 9; i++) {
            p.needs(i, is(woods));
        }
        List<Stack<String>> slots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            slots.add(s(woods[i], 1));
        }
        @SuppressWarnings("unchecked")
        Stack<String>[] arr = slots.toArray(new Stack[0]);
        p.dank(arr);
        long start = System.nanoTime();
        assertEquals(Status.MISSING, p.plan(1).status());
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "planner must stay bounded");
    }

    @Test
    void malformedInputIsInvalid() {
        assertEquals(Status.INVALID, sticks().plan(0).status());
        assertEquals(Status.INVALID, new Problem(3, 3).plan(1).status(), "empty pattern");
        Problem duplicate = new Problem(3, 3).needs(0, is("a")).needs(0, is("a"));
        assertEquals(Status.INVALID, duplicate.plan(1).status());
    }
}
