package io.github.dirtyredz.danktransfer.crafting;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentMenuLayoutTest {
    @BeforeAll
    static void bootstrap() {
        // NeoForge feature-flag init needs a (possibly empty) mod list outside a launched game.
        net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsOnlyNamedTypesAndGatesCurios() {
        assertTrue(EquipmentMenuLayout.named("io.github.dirtyredz.evileyetweaks.inventory.equipment.EquipmentMenu", false));
        String curios = "io.github.dirtyredz.evileyetweaks.integration.curios.CuriosEquipmentMenu";
        assertTrue(EquipmentMenuLayout.named(curios, true));
        assertFalse(EquipmentMenuLayout.named(curios, false));
        assertFalse(EquipmentMenuLayout.named("other.EquipmentMenu", true));
        assertFalse(EquipmentMenuLayout.named(curios + "$Subclass", true));
    }

    @Test
    void acceptsFixedPrefixWithCosmeticAndCuriosTailButNotAnonymousLookalike() {
        for (int tail : new int[]{0, 1, 30, 512}) {
            var menu = new Fixture(tail);
            assertTrue(EquipmentMenuLayout.validSlots(menu));
            assertFalse(CraftingMenuAccess.isSupported(menu), "topology alone does not authorize an unknown menu");
        }
    }

    @Test
    void rejectsWrongGridOwnerIndexDimensionsResultAndInventoryMapping() {
        var menu = new Fixture(0);
        menu.slots.set(2, new Slot(new SimpleContainer(4), 1, 0, 0));
        assertFalse(EquipmentMenuLayout.validSlots(menu));
        menu = new Fixture(0);
        menu.slots.set(2, new Slot(menu.grid, 0, 0, 0));
        assertFalse(EquipmentMenuLayout.validSlots(menu));
        menu = new Fixture(0);
        menu.slots.set(1, new Slot(new TransientCraftingContainer(menu, 3, 3), 0, 0, 0));
        assertFalse(EquipmentMenuLayout.validSlots(menu));
        menu = new Fixture(0);
        menu.slots.set(0, new Slot(new ResultContainer(), 0, 0, 0));
        assertFalse(EquipmentMenuLayout.validSlots(menu));
        for (int index : new int[]{9, 35, 36, 44}) {
            menu = new Fixture(0);
            menu.slots.set(index, new Slot(menu.inventory, Inventory.SLOT_OFFHAND, 0, 0));
            assertFalse(EquipmentMenuLayout.validSlots(menu));
            menu = new Fixture(0);
            menu.slots.set(index, new Slot(new Inventory(null), index < 36 ? index : index - 36, 0, 0));
            assertFalse(EquipmentMenuLayout.validSlots(menu));
        }
        menu = new Fixture(0);
        menu.slots.removeLast();
        assertFalse(EquipmentMenuLayout.validSlots(menu));
    }

    private static final class Fixture extends AbstractContainerMenu {
        final Inventory inventory = new Inventory(null);
        final TransientCraftingContainer grid = new TransientCraftingContainer(this, 2, 2);

        Fixture(int tail) {
            super(null, 1);
            addSlot(new ResultSlot(null, grid, new ResultContainer(), 0, 0, 0));
            for (int i = 0; i < 4; i++) addSlot(new Slot(grid, i, 0, 0));
            for (int i = 0; i < 4; i++) addSlot(new Slot(inventory, 39 - i, 0, 0));
            for (int i = 0; i < 36; i++) addSlot(new Slot(inventory, i < 27 ? i + 9 : i - 27, 0, 0));
            addSlot(new Slot(inventory, Inventory.SLOT_OFFHAND, 0, 0));
            var cosmetic = new SimpleContainer(4 + tail);
            for (int i = 0; i < 4 + tail; i++) addSlot(new Slot(cosmetic, i, 0, 0));
        }

        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
