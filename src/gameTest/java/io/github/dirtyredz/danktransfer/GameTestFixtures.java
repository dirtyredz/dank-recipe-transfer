package io.github.dirtyredz.danktransfer;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import tfar.dankstorage.DankStorage;
import tfar.dankstorage.inventory.DankInterface;
import tfar.dankstorage.item.CDankItem;
import tfar.dankstorage.utils.CommonUtils;
import tfar.dankstorage.utils.DankStats;
import tfar.dankstorage.world.CDankSavedData;
import tfar.dankstorage.world.DankInventoryForge;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Shared setup for the GameTests: fake players, a real crafting table and real Dank storage. */
final class GameTestFixtures {
    static final String TEMPLATE = "empty";
    private static int nextMenuId = 50;

    private GameTestFixtures() {
    }

    static void check(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }

    /** A clean fake server player standing at the test origin with only its inventory menu open. */
    static ServerPlayer player(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        UUID id = UUID.nameUUIDFromBytes(("dank_recipe_transfer:" + name).getBytes(StandardCharsets.UTF_8));
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(id, "drt_" + name));
        player.getInventory().clearContent();
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        for (int i = 0; i < 4; i++) {
            player.inventoryMenu.getCraftSlots().setItem(i, ItemStack.EMPTY);
        }
        player.containerMenu = player.inventoryMenu;
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        player.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        return player;
    }

    /** Places a crafting table and opens a real {@link CraftingMenu} for the player. */
    static CraftingMenu openTable(GameTestHelper helper, ServerPlayer player) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.CRAFTING_TABLE);
        BlockPos table = helper.absolutePos(relative);
        CraftingMenu menu = new CraftingMenu(nextMenuId++, player.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), table));
        player.containerMenu = menu;
        return menu;
    }

    static CDankItem dankItem(int tier) {
        Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("dankstorage", "dank_" + tier));
        check(item instanceof CDankItem, "dank_" + tier + " is not registered");
        return (CDankItem) item;
    }

    /** Creates fresh storage exactly as Dank does on first open and returns a carrier linked to it. */
    static ItemStack newDank(ServerLevel level, int tier) {
        CDankItem item = dankItem(tier);
        int frequency = DankStorage.maxId.getMaxId();
        DankStorage.maxId.increment();
        CDankSavedData data = DankStorage.getData(frequency, level.getServer());
        data.setStats(item.stats, frequency);
        data.createFreshInventory(item.stats, frequency);
        return carrier(tier, frequency);
    }

    /** A carrier of {@code tier} linked to {@code frequency} (no storage is created). */
    static ItemStack carrier(int tier, int frequency) {
        ItemStack stack = new ItemStack(dankItem(tier));
        CommonUtils.getOrCreateSettings(stack).putInt(CommonUtils.FREQ, frequency);
        return stack;
    }

    static int frequency(ItemStack carrier) {
        return CommonUtils.getFrequency(carrier);
    }

    static DankInventoryForge storage(ServerLevel level, ItemStack carrier) {
        DankInterface inventory = DankStorage.getData(frequency(carrier), level.getServer()).createInventory(frequency(carrier));
        check(inventory instanceof DankInventoryForge, "Dank storage is not the Forge handler");
        check(inventory.getDankStats() != DankStats.zero, "Dank storage was not initialised");
        return (DankInventoryForge) inventory;
    }

    static CDankSavedData savedData(ServerLevel level, ItemStack carrier) {
        return DankStorage.getData(frequency(carrier), level.getServer());
    }

    static CraftingRecipe recipe(ServerLevel level, String id) {
        return level.getRecipeManager().byKey(ResourceLocation.parse(id))
                .filter(r -> r instanceof CraftingRecipe)
                .map(r -> (CraftingRecipe) r)
                .orElseThrow(() -> new GameTestAssertException("missing recipe " + id));
    }

    static int count(DankInventoryForge storage, Item item) {
        int total = 0;
        for (int i = 0; i < storage.getSlots(); i++) {
            ItemStack stack = storage.getStackInSlot(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    static int inventoryCount(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Every item the player could have touched, rendered for before/after equality. */
    static String state(ServerPlayer player, DankInventoryForge... storages) {
        StringBuilder out = new StringBuilder();
        out.append("carried=").append(describe(player.containerMenu.getCarried())).append('\n');
        for (int i = 0; i < player.containerMenu.slots.size(); i++) {
            out.append("slot").append(i).append('=').append(describe(player.containerMenu.getSlot(i).getItem())).append(';');
        }
        out.append("\noffhand=").append(describe(player.getInventory().offhand.get(0))).append('\n');
        for (DankInventoryForge storage : storages) {
            out.append("dank").append(storage.frequency()).append(':');
            for (int i = 0; i < storage.getSlots(); i++) {
                out.append(describe(storage.getStackInSlot(i))).append('|').append(describe(storage.getGhostItem(i))).append(';');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty() ? "-" : stack.getCount() + "x" + ForgeRegistries.ITEMS.getKey(stack.getItem())
                + (stack.hasTag() ? stack.getTag().toString() : "");
    }
}
