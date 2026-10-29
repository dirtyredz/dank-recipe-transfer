package io.github.dirtyredz.danktransfer.crafting;

import io.github.dirtyredz.danktransfer.crafting.PlanInput.DankStore;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.InventorySlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.PatternSlot;
import io.github.dirtyredz.danktransfer.crafting.PlanInput.Stack;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Vanilla crafting menu layouts: the crafting table ({@link CraftingMenu}) and the player
 * inventory grid ({@link InventoryMenu}). A subclass is accepted only if it keeps the vanilla
 * slot layout (46 slots, crafting container behind the grid).
 *
 * <p>Menu slot layouts (vanilla 1.21.1):
 * <ul>
 *   <li>CraftingMenu: 0 result, 1-9 grid, 10-36 main inventory, 37-45 hotbar.</li>
 *   <li>InventoryMenu: 0 result, 1-4 grid, 5-8 armor, 9-35 main inventory, 36-44 hotbar, 45 offhand.</li>
 * </ul>
 */
public final class CraftingMenuAccess {
    /** Vanilla container stack limit for grid and inventory slots. */
    public static final int SLOT_LIMIT = 64;

    private final AbstractContainerMenu menu;
    private final int width;
    private final int height;
    private final int firstInventorySlot;

    private CraftingMenuAccess(AbstractContainerMenu menu, int width, int height, int firstInventorySlot) {
        this.menu = menu;
        this.width = width;
        this.height = height;
        this.firstInventorySlot = firstInventorySlot;
    }

    /** Slot count of both vanilla menus: CraftingMenu 1+9+36, InventoryMenu 1+4+4+36+1. */
    private static final int VANILLA_SLOT_COUNT = 46;

    public static Optional<CraftingMenuAccess> of(AbstractContainerMenu menu) {
        if (menu == null || menu.slots.size() != VANILLA_SLOT_COUNT) {
            return Optional.empty();
        }
        // Subclasses are accepted when they keep the vanilla layout. EnderIO's painted crafting
        // table, FTB Essentials' /craft and Ars Nouveau's craft effect all open the crafting table
        // screen (MenuType.CRAFTING) with a server-side CraftingMenu subclass that only overrides
        // stillValid; the client builds a plain CraftingMenu for the same screen.
        if (menu instanceof CraftingMenu && menu.getSlot(1).container instanceof CraftingContainer) {
            return Optional.of(new CraftingMenuAccess(menu, 3, 3, 10));
        }
        if (menu instanceof InventoryMenu && menu.getSlot(1).container instanceof CraftingContainer) {
            return Optional.of(new CraftingMenuAccess(menu, 2, 2, 9));
        }
        return Optional.empty();
    }

    public static boolean isSupported(AbstractContainerMenu menu) {
        return of(menu).isPresent();
    }

    public AbstractContainerMenu menu() {
        return menu;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int gridSize() {
        return width * height;
    }

    public Slot gridSlot(int index) {
        return menu.getSlot(1 + index);
    }

    /** The crafting container behind the grid slots. */
    public CraftingContainer craftContainer() {
        Container container = gridSlot(0).container;
        if (container instanceof CraftingContainer crafting) {
            return crafting;
        }
        throw new IllegalStateException("grid slot is not backed by a crafting container: " + container);
    }

    /**
     * The 36 loose inventory slots, in extraction priority order: main inventory first, then the
     * hotbar (so hotbar tools are touched last). The offhand is not a loose source.
     */
    public List<Slot> inventorySlots() {
        List<Slot> slots = new ArrayList<>(36);
        for (int i = 0; i < 36; i++) {
            slots.add(menu.getSlot(firstInventorySlot + i));
        }
        return slots;
    }

    /**
     * Builds the planner input from this menu's live contents.
     *
     * @param pattern   recipe placement for this grid
     * @param danks     copied contents of each distinct Dank store, in priority order
     * @param isCarrier stacks that must never be used as ingredients (the Dank carriers)
     * @param player    the menu's player, for slot pickup permission
     */
    public PlanInput<ItemKey> planInput(List<PatternSlot<ItemKey>> pattern, List<List<Stack<ItemKey>>> danks,
                                        Predicate<ItemStack> isCarrier, Player player) {
        List<Stack<ItemKey>> grid = new ArrayList<>(gridSize());
        for (int i = 0; i < gridSize(); i++) {
            grid.add(stackOf(gridSlot(i).getItem()));
        }
        List<InventorySlot<ItemKey>> inventory = new ArrayList<>(36);
        for (Slot slot : inventorySlots()) {
            if (!(slot.container instanceof Inventory)) {
                throw new IllegalStateException("inventory slot is not backed by the player inventory");
            }
            ItemStack stack = slot.getItem();
            boolean eligible = stack.isEmpty() || (!isCarrier.test(stack) && slot.mayPickup(player));
            inventory.add(new InventorySlot<>(slot.getContainerSlot(), stackOf(stack), eligible));
        }
        List<DankStore<ItemKey>> stores = new ArrayList<>(danks.size());
        for (int i = 0; i < danks.size(); i++) {
            stores.add(new DankStore<>(i, danks.get(i)));
        }
        return new PlanInput<>(width, height, grid, pattern, inventory, stores, ItemKey::maxStackSize, SLOT_LIMIT);
    }

    /** Converts a live stack to a planner stack ({@code null} when empty). */
    public static Stack<ItemKey> stackOf(ItemStack stack) {
        return stack.isEmpty() ? null : new Stack<>(ItemKey.of(stack), stack.getCount());
    }

    /** Converts a planner stack back to a fresh live stack. */
    public static ItemStack toItemStack(Stack<ItemKey> stack) {
        return stack == null ? ItemStack.EMPTY : stack.key().toStack(stack.count());
    }
}
