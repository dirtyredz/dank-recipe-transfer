package io.github.dirtyredz.danktransfer.crafting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.neoforged.fml.ModList;

/** Optional Tweaks boundary. No Tweaks or Curios class is linked or loaded here. */
final class EquipmentMenuLayout {
    private static final String BASE = "io.github.dirtyredz.evileyetweaks.inventory.equipment.EquipmentMenu";
    private static final String CURIOS = "io.github.dirtyredz.evileyetweaks.integration.curios.CuriosEquipmentMenu";
    private static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("evileye_tweaks", "equipment");

    private EquipmentMenuLayout() {}

    static boolean supports(AbstractContainerMenu menu) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded("evileye_tweaks")
                && named(menu.getClass().getName(), mods.isLoaded("curios"))
                && TYPE.equals(BuiltInRegistries.MENU.getKey(menu.getType())) && validSlots(menu);
    }

    static boolean named(String name, boolean curiosLoaded) {
        return BASE.equals(name) || (curiosLoaded && CURIOS.equals(name));
    }

    /** Validate the fixed prefix; the cosmetic/Curios tail is deliberately never exposed as supply. */
    static boolean validSlots(AbstractContainerMenu menu) {
        if (menu.slots.size() < 50 || !(menu.getSlot(0) instanceof ResultSlot)
                || !(menu.getSlot(0).container instanceof ResultContainer)
                || menu.getSlot(0).getContainerSlot() != 0
                || !(menu.getSlot(1).container instanceof CraftingContainer grid)
                || grid.getWidth() != 2 || grid.getHeight() != 2 || grid.getContainerSize() != 4) return false;
        for (int i = 0; i < 4; i++) {
            var slot = menu.getSlot(1 + i);
            if (slot.container != grid || slot.getContainerSlot() != i) return false;
        }
        if (!(menu.getSlot(9).container instanceof Inventory inventory)) return false;
        for (int i = 0; i < 36; i++) {
            var slot = menu.getSlot(9 + i);
            if (slot.container != inventory || slot.getContainerSlot() != (i < 27 ? i + 9 : i - 27)) return false;
        }
        return true;
    }
}
