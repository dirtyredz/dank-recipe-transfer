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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tfar.dankstorage.world.DankInventoryForge;

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

/** Real Dank Storage 1.20.1-16 handlers driven through the server executor. */
@GameTestHolder(DankRecipeTransfer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DankTransferGameTests {
    private DankTransferGameTests() {
    }

    private static TransferOutcome run(ServerPlayer player, CraftingRecipe recipe, int batches) {
        return run(TransferExecutor.standard(), player, recipe, batches);
    }

    private static TransferOutcome run(TransferExecutor executor, ServerPlayer player, CraftingRecipe recipe, int batches) {
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
        dank.setGhostItem(2, Items.OAK_PLANKS);
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
        check(level.getDataStorage().get(tag -> null, "dankstorage/987654") == null, "discovery created no storage");
        openTable(helper, player);
        String before = state(player, storage);
        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.MISSING, "nothing usable");
        check(before.equals(state(player, storage)), "nothing moved");
        check(player.getInventory().getItem(0).getTag() == null || GameTestFixtures.frequency(player.getInventory().getItem(0)) < 0,
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
        CompoundTag settings = tfar.dankstorage.utils.CommonUtils.getOrCreateSettings(carrier);
        settings.putInt(tfar.dankstorage.utils.CommonUtils.MODE, 2);
        settings.putInt(tfar.dankstorage.utils.CommonUtils.CON, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 5));
        player.getInventory().setItem(0, carrier);
        CompoundTag before = carrier.getTag().copy();
        int lockBefore = dank.get(2);
        openTable(helper, player);

        check(run(player, recipe(level, "minecraft:stick"), 1) == TransferOutcome.SUCCESS, "transfer");
        check(before.equals(player.getInventory().getItem(0).getTag()), "carrier NBT unchanged");
        check(dank.get(2) == lockBefore, "frequency lock unchanged");
        check(dank.getDankStats() == GameTestFixtures.dankItem(1).stats, "tier unchanged");
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

                    public void restore(int slot, ItemStack original) {
                        real.restore(slot, original);
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
        dank.setGhostItem(0, Items.COAL);
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
        CompoundTag saved = GameTestFixtures.savedData(level, carrier).save(new CompoundTag());
        tfar.dankstorage.inventory.DankInterface reloaded = tfar.dankstorage.world.CDankSavedData
                .loadStatic(saved, level).createInventory(GameTestFixtures.frequency(carrier));
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
}
