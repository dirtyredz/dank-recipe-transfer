package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.crafting.PlanInput.DankStore;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.InventorySlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.PatternSlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.Stack;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.DankExtraction;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.InventoryChange;
import io.github.dirtyredz.danktransfer.crafting.TransferPlan.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Side-effect-free ingredient allocation, shared by client preflight and server execution.
 *
 * <p>Rules:
 * <ul>
 *   <li>Sources in priority order: the current grid contents, eligible loose inventory, then Danks.</li>
 *   <li>If the grid already holds exactly this recipe, a request adds complete sets on top of it
 *       ("add" mode). Otherwise the grid is rebuilt from scratch and every grid item that is not
 *       reused goes back to the loose inventory ("replace" mode).</li>
 *   <li>Only complete sets are ever placed. {@link #MAX} asks for the largest count that fits the
 *       available ingredients, the item stack sizes and the slot limit.</li>
 *   <li>Displaced grid items must fit the loose inventory (using slots freed by extraction), or the
 *       plan fails before anything moves. Nothing is ever returned into a Dank.</li>
 * </ul>
 */
public final class TransferPlanner {
    /** Request the greatest number of complete sets. */
    public static final int MAX = Integer.MAX_VALUE;

    /** Bound on the ingredient-alternative search per feasibility check. */
    static final int SEARCH_BUDGET = 20_000;

    private static final int TIER_GRID = 0;
    private static final int TIER_INVENTORY = 1;
    private static final int TIER_DANK = 2;

    private TransferPlanner() {
    }

    public static <K> TransferPlan<K> plan(PlanInput<K> in, int requestedBatches) {
        Objects.requireNonNull(in, "in");
        if (requestedBatches <= 0 || !isWellFormed(in)) {
            return TransferPlan.failure(Status.INVALID, List.of());
        }
        return new Run<>(in, requestedBatches).execute();
    }

    private static <K> boolean isWellFormed(PlanInput<K> in) {
        int size = in.gridWidth() * in.gridHeight();
        if (in.gridWidth() <= 0 || in.gridHeight() <= 0 || in.grid().size() != size
                || in.pattern().isEmpty() || in.slotLimit() <= 0) {
            return false;
        }
        Set<Integer> used = new HashSet<>();
        for (PatternSlot<K> slot : in.pattern()) {
            if (slot.gridIndex() < 0 || slot.gridIndex() >= size || !used.add(slot.gridIndex())) {
                return false;
            }
        }
        return true;
    }

    /** True when every pattern slot holds an accepted item and every other grid slot is empty. */
    static <K> boolean gridMatches(PlanInput<K> in) {
        boolean[] inPattern = new boolean[in.grid().size()];
        for (PatternSlot<K> slot : in.pattern()) {
            Stack<K> stack = in.grid().get(slot.gridIndex());
            if (stack == null || !slot.accepts().test(stack.key())) {
                return false;
            }
            inPattern[slot.gridIndex()] = true;
        }
        for (int i = 0; i < inPattern.length; i++) {
            if (!inPattern[i] && in.grid().get(i) != null) {
                return false;
            }
        }
        return true;
    }

    /** Per-key totals by source tier. */
    private static final class Availability {
        long grid;
        long inventory;
        long dank;
        int bestTier = Integer.MAX_VALUE;

        void add(int tier, long count) {
            switch (tier) {
                case TIER_GRID -> grid += count;
                case TIER_INVENTORY -> inventory += count;
                default -> dank += count;
            }
            bestTier = Math.min(bestTier, tier);
        }

        long total(boolean includeGrid) {
            return (includeGrid ? grid : 0) + inventory + dank;
        }
    }

    /** One planning pass. Holds derived state; never mutates the input. */
    private static final class Run<K> {
        private final PlanInput<K> in;
        private final int requested;
        private final boolean addMode;
        private final Map<K, Availability> available = new LinkedHashMap<>();
        private final int n;

        Run(PlanInput<K> in, int requested) {
            this.in = in;
            this.requested = requested;
            this.addMode = gridMatches(in);
            this.n = in.pattern().size();
            if (!addMode) {
                for (Stack<K> stack : in.grid()) {
                    if (stack != null) {
                        availability(stack.key()).add(TIER_GRID, stack.count());
                    }
                }
            }
            for (InventorySlot<K> slot : in.inventory()) {
                if (slot.eligible() && slot.stack() != null) {
                    availability(slot.stack().key()).add(TIER_INVENTORY, slot.stack().count());
                }
            }
            for (DankStore<K> store : in.danks()) {
                for (Stack<K> stack : store.slots()) {
                    if (stack != null) {
                        availability(stack.key()).add(TIER_DANK, stack.count());
                    }
                }
            }
        }

        private Availability availability(K key) {
            return available.computeIfAbsent(key, k -> new Availability());
        }

        private int capacity(K key) {
            return Math.min(in.maxStackSize().applyAsInt(key), in.slotLimit());
        }

        TransferPlan<K> execute() {
            List<K> keys;
            int batches;
            if (addMode) {
                keys = new ArrayList<>(n);
                int headroom = Integer.MAX_VALUE;
                for (PatternSlot<K> slot : in.pattern()) {
                    Stack<K> present = in.grid().get(slot.gridIndex());
                    keys.add(present.key());
                    headroom = Math.min(headroom, capacity(present.key()) - present.count());
                }
                if (headroom <= 0) {
                    return TransferPlan.failure(Status.GRID_FULL, List.of());
                }
                batches = largestFeasible(Math.min(requested, headroom), k -> addModeFeasible(keys, k));
                if (batches == 0) {
                    return TransferPlan.failure(Status.MISSING, addModeMissing(keys));
                }
            } else {
                List<List<K>> candidates = candidates();
                int upper = Math.min(requested, in.slotLimit());
                batches = largestFeasible(upper, k -> assign(candidates, k) != null);
                if (batches == 0) {
                    return TransferPlan.failure(Status.MISSING, replaceModeMissing(candidates));
                }
                keys = assign(candidates, batches);
            }
            return build(keys, batches);
        }

        /** Largest k in [1, upper] with feasible(k); feasibility is monotone in k. */
        private int largestFeasible(int upper, IntPredicate feasible) {
            if (upper <= 0) {
                return 0;
            }
            if (feasible.test(upper)) {
                return upper;
            }
            int lo = 0;
            int hi = upper - 1;
            while (lo < hi) {
                int mid = lo + (hi - lo + 1) / 2;
                if (feasible.test(mid)) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo >= 1 && feasible.test(lo) ? lo : 0;
        }

        private boolean addModeFeasible(List<K> keys, int k) {
            Map<K, Long> need = new LinkedHashMap<>();
            for (K key : keys) {
                need.merge(key, (long) k, Long::sum);
            }
            for (Map.Entry<K, Long> e : need.entrySet()) {
                Availability a = available.get(e.getKey());
                if (a == null || a.total(false) < e.getValue()) {
                    return false;
                }
            }
            return true;
        }

        private List<Integer> addModeMissing(List<K> keys) {
            Map<K, Long> remaining = new LinkedHashMap<>();
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                K key = keys.get(i);
                Availability a = available.get(key);
                long left = remaining.computeIfAbsent(key, kk -> a == null ? 0L : a.total(false));
                if (left < 1) {
                    missing.add(i);
                } else {
                    remaining.put(key, left - 1);
                }
            }
            return missing;
        }

        /** Candidate keys per pattern slot, best first. */
        private List<List<K>> candidates() {
            List<List<K>> result = new ArrayList<>(n);
            for (PatternSlot<K> slot : in.pattern()) {
                Stack<K> inPlace = in.grid().get(slot.gridIndex());
                List<K> list = new ArrayList<>();
                for (Map.Entry<K, Availability> e : available.entrySet()) {
                    if (e.getValue().total(true) > 0 && slot.accepts().test(e.getKey())) {
                        list.add(e.getKey());
                    }
                }
                list.sort(Comparator
                        .comparingInt((K key) -> inPlace != null && inPlace.key().equals(key) ? 0 : 1)
                        .thenComparingInt(key -> available.get(key).bestTier)
                        .thenComparingLong(key -> -available.get(key).total(true)));
                result.add(list);
            }
            return result;
        }

        /** Backtracking key assignment for k sets; null when infeasible or over budget. */
        private List<K> assign(List<List<K>> candidates, int k) {
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).size()));
            Map<K, Long> remaining = new LinkedHashMap<>();
            for (Map.Entry<K, Availability> e : available.entrySet()) {
                remaining.put(e.getKey(), e.getValue().total(true));
            }
            List<K> chosen = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                chosen.add(null);
            }
            int[] budget = {SEARCH_BUDGET};
            return search(candidates, order, 0, k, remaining, chosen, budget) ? chosen : null;
        }

        private boolean search(List<List<K>> candidates, Integer[] order, int depth, int k,
                               Map<K, Long> remaining, List<K> chosen, int[] budget) {
            if (depth == n) {
                return true;
            }
            int slot = order[depth];
            for (K key : candidates.get(slot)) {
                if (--budget[0] < 0) {
                    return false;
                }
                if (capacity(key) < k) {
                    continue;
                }
                long left = remaining.get(key);
                if (left < k) {
                    continue;
                }
                remaining.put(key, left - k);
                chosen.set(slot, key);
                if (search(candidates, order, depth + 1, k, remaining, chosen, budget)) {
                    return true;
                }
                remaining.put(key, left);
                chosen.set(slot, null);
            }
            return false;
        }

        private List<Integer> replaceModeMissing(List<List<K>> candidates) {
            Map<K, Long> remaining = new LinkedHashMap<>();
            for (Map.Entry<K, Availability> e : available.entrySet()) {
                remaining.put(e.getKey(), e.getValue().total(true));
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, Comparator.comparingInt(i -> candidates.get(i).size()));
            List<Integer> missing = new ArrayList<>();
            for (int slot : order) {
                K pick = null;
                for (K key : candidates.get(slot)) {
                    if (remaining.get(key) >= 1) {
                        pick = key;
                        break;
                    }
                }
                if (pick == null) {
                    missing.add(slot);
                } else {
                    remaining.merge(pick, -1L, Long::sum);
                }
            }
            missing.sort(Integer::compare);
            return missing;
        }

        /** Turns a feasible key assignment into concrete moves. */
        private TransferPlan<K> build(List<K> keys, int k) {
            int size = in.grid().size();
            List<Stack<K>> finalGrid = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                finalGrid.add(addMode ? in.grid().get(i) : null);
            }
            Map<K, Long> need = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int gridIndex = in.pattern().get(i).gridIndex();
                K key = keys.get(i);
                int base = addMode ? in.grid().get(gridIndex).count() : 0;
                finalGrid.set(gridIndex, new Stack<>(key, base + k));
                need.merge(key, (long) k, Long::sum);
            }

            // 1. Grid contents first (replace mode): whatever is not reused must go back.
            Map<K, Long> leftovers = new LinkedHashMap<>();
            if (!addMode) {
                for (Stack<K> stack : in.grid()) {
                    if (stack == null) {
                        continue;
                    }
                    long want = need.getOrDefault(stack.key(), 0L);
                    long use = Math.min(want, stack.count());
                    if (use > 0) {
                        need.put(stack.key(), want - use);
                    }
                    if (stack.count() - use > 0) {
                        leftovers.merge(stack.key(), stack.count() - use, Long::sum);
                    }
                }
            }

            // 2. Loose inventory, in the given priority order.
            List<InventorySlot<K>> inventory = in.inventory();
            List<Stack<K>> after = new ArrayList<>(inventory.size());
            for (InventorySlot<K> slot : inventory) {
                Stack<K> stack = slot.stack();
                if (slot.eligible() && stack != null) {
                    long want = need.getOrDefault(stack.key(), 0L);
                    int use = (int) Math.min(want, stack.count());
                    if (use > 0) {
                        need.put(stack.key(), want - use);
                        stack = stack.count() == use ? null : stack.withCount(stack.count() - use);
                    }
                }
                after.add(stack);
            }

            // 3. Danks, store by store, slot by slot.
            List<DankExtraction<K>> extractions = new ArrayList<>();
            for (DankStore<K> store : in.danks()) {
                for (int s = 0; s < store.slots().size(); s++) {
                    Stack<K> stack = store.slots().get(s);
                    if (stack == null) {
                        continue;
                    }
                    long want = need.getOrDefault(stack.key(), 0L);
                    int use = (int) Math.min(want, stack.count());
                    if (use > 0) {
                        need.put(stack.key(), want - use);
                        extractions.add(new DankExtraction<>(store.sourceId(), s, stack.key(), use));
                    }
                }
            }
            for (long remainingNeed : need.values()) {
                if (remainingNeed != 0) {
                    // Availability said yes but the sources disagree: never place a partial set.
                    return TransferPlan.failure(Status.INVALID, List.of());
                }
            }

            // 4. Return displaced grid items into the inventory as it will be after extraction.
            for (Map.Entry<K, Long> e : leftovers.entrySet()) {
                if (!returnToInventory(inventory, after, e.getKey(), e.getValue())) {
                    return TransferPlan.failure(Status.NO_RETURN_SPACE, List.of());
                }
            }

            List<InventoryChange<K>> changes = new ArrayList<>();
            for (int i = 0; i < inventory.size(); i++) {
                Stack<K> before = inventory.get(i).stack();
                if (!Objects.equals(before, after.get(i))) {
                    changes.add(new InventoryChange<>(inventory.get(i).index(), before, after.get(i)));
                }
            }
            return new TransferPlan<>(Status.OK, k, List.of(), finalGrid, changes, extractions);
        }

        private boolean returnToInventory(List<InventorySlot<K>> slots, List<Stack<K>> after, K key, long amount) {
            int cap = capacity(key);
            // Merge into existing partial stacks first, then empty slots.
            for (int pass = 0; pass < 2 && amount > 0; pass++) {
                for (int i = 0; i < slots.size() && amount > 0; i++) {
                    if (!slots.get(i).eligible()) {
                        continue;
                    }
                    Stack<K> current = after.get(i);
                    boolean merge = pass == 0 && current != null && current.key().equals(key) && current.count() < cap;
                    boolean fill = pass == 1 && current == null;
                    if (merge || fill) {
                        int have = current == null ? 0 : current.count();
                        int put = (int) Math.min(amount, cap - have);
                        after.set(i, new Stack<>(key, have + put));
                        amount -= put;
                    }
                }
            }
            return amount == 0;
        }
    }
}
