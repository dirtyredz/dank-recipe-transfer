package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.crafting.CraftingMenuAccess;
import io.github.dirtyredz.danktransfer.crafting.ItemKey;
import io.github.dirtyredz.danktransfer.crafting.TransferExecutor;
import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import io.github.dirtyredz.danktransfer.crafting.TransferPlanner;
import io.github.dirtyredz.danktransfer.dank.CarriedDank;
import io.github.dirtyredz.danktransfer.dank.DankSource;
import io.github.dirtyredz.danktransfer.sync.DankSnapshotService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tfar.dankstorage.init.ModDataComponentTypes;
import tfar.dankstorage.item.DankItem;
import tfar.dankstorage.utils.PickupMode;
import tfar.dankstorage.utils.UseType;
import tfar.dankstorage.world.DankInventoryForge;
import tfar.dankstorage.world.DankSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.dirtyredz.danktransfer.GameTestFixtures.TEMPLATE;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.check;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.count;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.inventoryCount;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.newDank;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.openTable;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.player;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.recipe;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.state;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.storage;

/** Real Dank Storage 1.21.1 (build 20) handlers driven through the server executor. */
@GameTestHolder(DankRecipeTransfer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DankTransferGameTests {
    private DankTransferGameTests() {
    }

    private static TransferOutcome run(ServerPlayer player, RecipeHolder<CraftingRecipe> recipe, int batches) {
        return run(TransferExecutor.standard(), player, recipe, batches);
    }

    private static TransferOutcome run(TransferExecutor executor, ServerPlayer player, RecipeHolder<CraftingRecipe> recipe,
                                       int batches) {
        return executor.execute(player, CraftingMenuAccess.of(player.containerMenu).orElseThrow(), recipe, batches);
    }

    @GameTest(template = TEMPLATE)
    public static void dankOnlyFillsCraftingTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "table");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 64));
        player.getInventory().setItem(0, carrier);
        CraftingMenu menu = openTable(helper, player);

        TransferOutcome outcome = run(player, recipe(level, "minecraft:stick"), 1);
        check(outcome == TransferOutcome.SUCCESS, "outcome " + outcome);
        check(count(dank, Items.OAK_PLANKS) == 62, "dank should drop by exactly 2, has " + count(dank, Items.OAK_PLANKS));
        check(menu.getSlot(1).getItem().is(Items.OAK_PLANKS) && menu.getSlot(4).getItem().is(Items.OAK_PLANKS),
                "planks at grid 0 and 3");
        check(menu.getSlot(0).getItem().is(Items.STICK) && menu.getSlot(0).getItem().getCount() == 4,
                "result slot recomputed normally: " + menu.getSlot(0).getItem());
        check(player.getInventory().getItem(0) == carrier, "carrier untouched");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void dankFillsInventoryGrid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "inventory");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(3, new ItemStack(Items.BIRCH_PLANKS, 10));
        player.getInventory().setItem(20, carrier);

        TransferOutcome outcome = run(player, recipe(level, "minecraft:crafting_table"), 1);
        check(outcome == TransferOutcome.SUCCESS, "outcome " + outcome);
        check(count(dank, Items.BIRCH_PLANKS) == 6, "4 planks taken");
        for (int slot = 1; slot <= 4; slot++) {
            check(player.inventoryMenu.getSlot(slot).getItem().is(Items.BIRCH_PLANKS), "2x2 grid filled at " + slot);
        }
        check(player.inventoryMenu.getSlot(0).getItem().is(Items.CRAFTING_TABLE), "result is a crafting table");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void looseInventoryBeforeDank(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "loose");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 10));
        player.getInventory().setItem(0, carrier);
        player.getInventory().setItem(9, new ItemStack(Items.OAK_PLANKS, 1));
        openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "transfer");
        check(inventoryCount(player, Items.OAK_PLANKS) == 0, "loose plank used first");
        check(count(dank, Items.OAK_PLANKS) == 9, "only one from the Dank");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void ghostLockSurvivesDepletion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "ghost");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setGhostItem(2, new ItemStack(Items.OAK_PLANKS));
        dank.setStackInSlot(2, new ItemStack(Items.OAK_PLANKS, 2));
        player.getInventory().setItem(0, carrier);
        openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "transfer");
        check(dank.getStackInSlot(2).isEmpty(), "slot depleted");
        check(dank.getGhostItem(2).is(Items.OAK_PLANKS), "ghost filter retained after depletion");
        ItemStack refused = dank.insertItem(2, new ItemStack(Items.STONE), true);
        check(refused.getCount() == 1, "ghost filter still refuses other items");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void offhandCarrierWorks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "offhand");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 5));
        player.getInventory().offhand.set(0, carrier);
        openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "offhand Dank used");
        check(count(dank, Items.OAK_PLANKS) == 3, "two taken");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void frequencyAliasesCountOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "alias");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 3));
        player.getInventory().setItem(0, carrier);
        player.getInventory().setItem(1, GameTestFixtures.carrier(1, GameTestFixtures.frequency(carrier)));

        List<CarriedDank> found = DankSource.discover(player);
        check(found.size() == 1, "two carriers on one frequency are one store, found " + found.size());
        Map<ItemKey, Long> totals = DankSnapshotService.aggregate(found);
        check(totals.get(ItemKey.of(new ItemStack(Items.OAK_PLANKS))) == 3L, "counted once: " + totals);

        openTable(helper, player);
        check(run(player, recipe(level, "minecraft:stick"), TransferPlanner.MAX) == TransferOutcome.SUCCESS, "transfer");
        check(count(dank, Items.OAK_PLANKS) == 1, "only one set from 3 planks, not two");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void invalidAndMismatchedCarriersIgnored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "invalid");
        // Unassigned carrier: no frequency at all.
        player.getInventory().setItem(0, new ItemStack(GameTestFixtures.dankItem(1)));
        // Lower-tier carrier linked to higher-tier storage: Dank refuses to open it, so do we.
        ItemStack tierTwo = newDank(level, 2);
        DankInventoryForge storage = storage(level, tierTwo);
        storage.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 20));
        player.getInventory().setItem(1, GameTestFixtures.carrier(1, GameTestFixtures.frequency(tierTwo)));
        // Carrier linked to a frequency that has no storage at all.
        player.getInventory().setItem(2, GameTestFixtures.carrier(1, 987_654));

        check(DankSource.discover(player).isEmpty(), "no valid store");
        check(DankSavedData.get(987_654, level.getServer()) == null, "discovery created no storage");
        openTable(helper, player);
        String before = state(player, storage);
        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.MISSING, "nothing usable");
        check(before.equals(state(player, storage)), "nothing moved");
        check(!player.getInventory().getItem(0).has(ModDataComponentTypes.FREQUENCY),
                "unassigned carrier was not assigned a frequency");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void extractionBeyondOneStackCall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "caps");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 300));
        player.getInventory().setItem(0, carrier);
        CraftingMenu menu = openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), TransferPlanner.MAX) == TransferOutcome.SUCCESS, "transfer");
        check(count(dank, Items.OAK_PLANKS) == 300 - 128, "128 extracted over several capped calls, left " + count(dank, Items.OAK_PLANKS));
        check(menu.getSlot(1).getItem().getCount() == 64 && menu.getSlot(4).getItem().getCount() == 64, "two full stacks");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void carrierSettingsUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "settings");
        ItemStack carrier = newDank(level, 1);
        DankItem.setPickupMode(carrier, PickupMode.filtered_pickup);
        DankItem.setUseType(carrier, UseType.construction);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 5));
        dank.toggleFrequencyLock();
        player.getInventory().setItem(0, carrier);
        ItemStack before = carrier.copy();
        boolean lockBefore = dank.frequencyLocked();
        openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "transfer");
        check(ItemStack.isSameItemSameComponents(before, player.getInventory().getItem(0)),
                "carrier components unchanged: " + player.getInventory().getItem(0).getComponentsPatch());
        check(lockBefore && dank.frequencyLocked(), "frequency lock unchanged");
        check(GameTestFixtures.savedData(level, carrier).getStats() == GameTestFixtures.dankItem(1).stats, "tier unchanged");
        helper.succeed();
    }

    /** Wraps every discovered Dank so the extraction of {@code key} comes up one short. */
    private static TransferExecutor shortOn(net.minecraft.world.item.Item item) {
        return new TransferExecutor(player -> {
            List<CarriedDank> wrapped = new ArrayList<>();
            for (CarriedDank real : DankSource.discover(player)) {
                wrapped.add(new CarriedDank() {
                    public int frequency() {
                        return real.frequency();
                    }

                    public List<ItemStack> copyContents() {
                        return real.copyContents();
                    }

                    public ItemStack copySlot(int slot) {
                        return real.copySlot(slot);
                    }

                    public int extract(int slot, ItemKey key, int count) {
                        if (key.item() == item && count > 1) {
                            return real.extract(slot, key, count - 1);
                        }
                        return real.extract(slot, key, count);
                    }

                    public Checkpoint checkpoint() {
                        return real.checkpoint();
                    }

                    public boolean isDirty() {
                        return real.isDirty();
                    }
                });
            }
            return wrapped;
        });
    }

    @GameTest(template = TEMPLATE)
    public static void shortExtractionRollsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "rollback");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setGhostItem(0, new ItemStack(Items.COAL));
        dank.setStackInSlot(0, new ItemStack(Items.COAL, 8));
        dank.setStackInSlot(1, new ItemStack(Items.STICK, 8));
        player.getInventory().setItem(0, carrier);
        player.getInventory().setItem(9, new ItemStack(Items.STICK, 1));
        openTable(helper, player);
        String before = state(player, dank);

        TransferOutcome outcome = run(shortOn(Items.STICK), player, recipe(level, "minecraft:torch"), 4);
        check(outcome == TransferOutcome.EXTRACTION_FAILED, "injected short extraction must fail, got " + outcome);
        check(before.equals(state(player, dank)), "every count restored:\nbefore " + before + "\nafter  " + state(player, dank));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void rollbackRestoresBlacklistedItems(GameTestHelper helper) {
        // The GameTest datapack blacklists minecraft:dirt from Dank storage. An existing dirt stack
        // (stored before the blacklist) must survive a rollback even though insertItem refuses it.
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "blacklist");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.DIRT, 10));
        dank.setStackInSlot(1, new ItemStack(Items.GRAVEL, 10));
        check(dank.insertItem(2, new ItemStack(Items.DIRT), true).getCount() == 1, "dirt is blacklisted for insertion");
        player.getInventory().setItem(0, carrier);
        openTable(helper, player);
        String before = state(player, dank);

        TransferOutcome outcome = run(shortOn(Items.GRAVEL), player, recipe(level, "minecraft:coarse_dirt"), 2);
        check(outcome == TransferOutcome.EXTRACTION_FAILED, "fault expected, got " + outcome);
        check(count(dank, Items.DIRT) == 10, "blacklisted dirt restored exactly, has " + count(dank, Items.DIRT));
        check(before.equals(state(player, dank)), "all counts restored");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void changesMarkSavedDataDirty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "dirty");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 5));
        player.getInventory().setItem(0, carrier);
        GameTestFixtures.savedData(level, carrier).setDirty(false);
        openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "transfer");
        check(GameTestFixtures.savedData(level, carrier).isDirty(), "Dank saved data marked dirty for persistence");
        // Round-trip the saved form, as a save and reload would.
        CompoundTag saved = GameTestFixtures.savedData(level, carrier).save(new CompoundTag(), level.registryAccess());
        tfar.dankstorage.inventory.DankInventory reloaded = DankSavedData
                .loadStatic(saved, level, GameTestFixtures.frequency(carrier)).getOrCreateInventory();
        check(reloaded.getItemDank(0).is(Items.OAK_PLANKS) && reloaded.getItemDank(0).getCount() == 3,
                "reloaded contents show 3 planks: " + reloaded.getItemDank(0));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void twoPlayersShareOneFrequency(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer alice = player(helper, "alice");
        ServerPlayer bob = player(helper, "bob");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 4));
        alice.getInventory().setItem(0, carrier);
        bob.getInventory().setItem(0, GameTestFixtures.carrier(1, GameTestFixtures.frequency(carrier)));

        openTable(helper, alice);
        check(run(alice, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "alice transfers");
        Map<ItemKey, Long> bobSees = DankSnapshotService.aggregate(DankSource.discover(bob));
        check(bobSees.get(ItemKey.of(new ItemStack(Items.OAK_PLANKS))) == 2L, "bob sees the shared store drop: " + bobSees);

        openTable(helper, bob);
        check(run(bob, recipe(level, "minecraft:stick"), TransferPlanner.MAX) == TransferOutcome.SUCCESS, "bob transfers");
        check(count(dank, Items.OAK_PLANKS) == 0, "both drew from one store");
        check(run(alice, recipe(level, "minecraft:stick"), 1) == TransferOutcome.MISSING, "alice cannot overdraw");
        helper.succeed();
    }

    private static ItemStack named(net.minecraft.world.item.Item item, int count, String name) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name));
        return stack;
    }

    @GameTest(template = TEMPLATE)
    public static void componentsSurviveExtraction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "components");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, named(Items.OAK_PLANKS, 5, "Heirloom"));
        dank.setStackInSlot(1, new ItemStack(Items.OAK_PLANKS, 5));
        player.getInventory().setItem(0, carrier);

        Map<ItemKey, Long> totals = DankSnapshotService.aggregate(DankSource.discover(player));
        check(totals.size() == 2, "named and plain planks are distinct keys: " + totals);
        check(totals.get(ItemKey.of(named(Items.OAK_PLANKS, 1, "Heirloom"))) == 5L, "named total: " + totals);

        CraftingMenu menu = openTable(helper, player);
        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "transfer");
        ItemStack placed = menu.getSlot(1).getItem();
        check(placed.is(Items.OAK_PLANKS) && placed.getCount() == 1, "one plank per slot: " + placed);
        check(ItemStack.isSameItemSameComponents(placed, menu.getSlot(4).getItem()), "one identity per set");
        int named = 0;
        int plain = 0;
        for (int i = 0; i < dank.getSlots(); i++) {
            ItemStack stack = dank.getStackInSlot(i);
            if (stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
                named += stack.getCount();
            } else if (stack.is(Items.OAK_PLANKS)) {
                plain += stack.getCount();
            }
        }
        check(named + plain == 8, "exactly two planks left the Dank: named " + named + ", plain " + plain);
        check(placed.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) ? named == 3 : plain == 3,
                "the placed identity is the one that was extracted: " + GameTestFixtures.describe(placed));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void rollbackRestoresComponentStacks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "componentrollback");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, named(Items.COAL, 8, "Charcoal-ish"));
        dank.setStackInSlot(1, named(Items.STICK, 8, "Twig"));
        player.getInventory().setItem(0, carrier);
        openTable(helper, player);
        String before = state(player, dank);

        TransferOutcome outcome = run(shortOn(Items.STICK), player, recipe(level, "minecraft:torch"), 4);
        check(outcome == TransferOutcome.EXTRACTION_FAILED, "fault expected, got " + outcome);
        check(before.equals(state(player, dank)), "named stacks restored with their components:\nbefore " + before
                + "\nafter  " + state(player, dank));
        helper.succeed();
    }

    /**
     * Dank 20's auto-sort re-sorts a store after every change, so extracted slots move mid-transfer. The
     * transfer must still take the planned amounts, and a rollback must restore the exact pre-transfer store.
     */
    @GameTest(template = TEMPLATE)
    public static void autoSortedStoreTransfersAndRollsBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, "autosort");
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(4, new ItemStack(Items.STICK, 3));
        dank.setStackInSlot(2, new ItemStack(Items.COAL, 9));
        dank.setStackInSlot(0, new ItemStack(Items.STICK, 5));
        dank.setGhostItem(6, new ItemStack(Items.COAL));
        if (!dank.autoSort()) {
            dank.toggleAutoSort();
        }
        player.getInventory().setItem(0, carrier);
        openTable(helper, player);
        String before = state(player, dank);

        TransferOutcome failed = run(shortOn(Items.STICK), player, recipe(level, "minecraft:torch"), 8);
        check(failed == TransferOutcome.EXTRACTION_FAILED, "fault expected, got " + failed);
        check(before.equals(state(player, dank)), "auto-sorted store restored exactly:\nbefore " + before
                + "\nafter  " + state(player, dank));

        check(run(player, recipe(level, "minecraft:torch"), 8) == TransferOutcome.SUCCESS, "real transfer");
        check(count(dank, Items.STICK) == 0 && count(dank, Items.COAL) == 1, "8 sticks and 8 coal taken: sticks "
                + count(dank, Items.STICK) + ", coal " + count(dank, Items.COAL));
        helper.succeed();
    }
}
