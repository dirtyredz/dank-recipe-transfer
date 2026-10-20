package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import io.github.dirtyredz.danktransfer.network.ServerRequestHandler;
import io.github.dirtyredz.danktransfer.network.TransferMessages;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferRequest;
import io.github.dirtyredz.danktransfer.sync.DankSnapshotService.Subscription;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tfar.dankstorage.world.DankInventoryForge;

import java.util.concurrent.atomic.AtomicInteger;

import static io.github.dirtyredz.danktransfer.GameTestFixtures.TEMPLATE;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.check;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.count;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.newDank;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.openTable;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.player;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.state;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.storage;

/**
 * The server's request validation, driven through the same entry points the network handler
 * calls. Every rejected request must leave every count unchanged.
 */
@GameTestHolder(DankRecipeTransfer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TransferProtocolGameTests {
    private static final ResourceLocation STICK = ResourceLocation.fromNamespaceAndPath("minecraft", "stick");
    private static final AtomicInteger REQUEST_IDS = new AtomicInteger(1_000);

    private TransferProtocolGameTests() {
    }

    /** A player with a Dank of 64 planks, a crafting table open and an active subscription. */
    private record Setup(ServerPlayer player, CraftingMenu menu, DankInventoryForge dank, Subscription subscription) {
        TransferRequest request(ResourceLocation recipe, int batches) {
            return new TransferRequest(REQUEST_IDS.incrementAndGet(), menu.containerId, subscription.session(),
                    subscription.revision(), recipe, batches);
        }
    }

    private static ServerRequestHandler server() {
        return DankRecipeTransfer.server();
    }

    private static Setup setup(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = player(helper, name);
        ItemStack carrier = newDank(level, 1);
        DankInventoryForge dank = storage(level, carrier);
        dank.setStackInSlot(0, new ItemStack(Items.OAK_PLANKS, 64));
        player.getInventory().setItem(0, carrier);
        CraftingMenu menu = openTable(helper, player);
        server().onSubscribe(player, new Subscribe(menu.containerId, true));
        Subscription subscription = server().snapshots().subscription(player)
                .orElseThrow(() -> new IllegalStateException("subscription not created"));
        return new Setup(player, menu, dank, subscription);
    }

    private static void assertRejected(Setup s, TransferRequest request, TransferOutcome expected) {
        String before = state(s.player(), s.dank());
        TransferOutcome outcome = server().onTransfer(s.player(), request);
        check(outcome == expected, "expected " + expected + " but got " + outcome);
        check(before.equals(state(s.player(), s.dank())), expected + " must leave every count unchanged");
    }

    @GameTest(template = TEMPLATE)
    public static void validRequestSucceeds(GameTestHelper helper) {
        Setup s = setup(helper, "valid");
        check(server().onTransfer(s.player(), s.request(STICK, 1)) == TransferOutcome.SUCCESS, "valid request");
        check(count(s.dank(), Items.OAK_PLANKS) == 62, "two planks moved");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void wrongMenuRejected(GameTestHelper helper) {
        Setup s = setup(helper, "wrongmenu");
        TransferRequest r = s.request(STICK, 1);
        assertRejected(s, new TransferRequest(r.requestId(), r.menuId() + 7, r.session(), r.revision(), STICK, 1),
                TransferOutcome.WRONG_MENU);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void closedMenuRejected(GameTestHelper helper) {
        Setup s = setup(helper, "closed");
        TransferRequest r = s.request(STICK, 1);
        s.player().containerMenu = s.player().inventoryMenu;
        check(server().snapshots().subscription(s.player()).isEmpty(), "subscription expires with its menu");
        assertRejected(s, r, TransferOutcome.WRONG_MENU);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void staleSnapshotRejected(GameTestHelper helper) {
        Setup s = setup(helper, "stale");
        TransferRequest r = s.request(STICK, 1);
        s.dank().setStackInSlot(1, new ItemStack(Items.STONE, 3)); // contents change behind the client's back
        String before = state(s.player(), s.dank());
        check(server().onTransfer(s.player(), r) == TransferOutcome.STALE, "old revision is stale");
        check(before.equals(state(s.player(), s.dank())), "stale request moved nothing");
        check(s.subscription().revision() > r.revision(), "a refreshed snapshot was issued");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void wrongSessionRejected(GameTestHelper helper) {
        Setup s = setup(helper, "session");
        TransferRequest r = s.request(STICK, 1);
        assertRejected(s, new TransferRequest(r.requestId(), r.menuId(), r.session() ^ 0x5a5a, r.revision(), STICK, 1),
                TransferOutcome.STALE);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void removedDankRejected(GameTestHelper helper) {
        Setup s = setup(helper, "removed");
        TransferRequest r = s.request(STICK, 1);
        ItemStack carrier = s.player().getInventory().removeItemNoUpdate(0);
        String before = state(s.player(), s.dank());
        TransferOutcome outcome = server().onTransfer(s.player(), r);
        check(outcome == TransferOutcome.STALE || outcome == TransferOutcome.MISSING, "removed Dank: " + outcome);
        check(before.equals(state(s.player(), s.dank())), "nothing moved");
        check(!carrier.isEmpty(), "test sanity");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void relinkedDankRejected(GameTestHelper helper) {
        Setup s = setup(helper, "relinked");
        TransferRequest r = s.request(STICK, 1);
        ItemStack other = newDank(helper.getLevel(), 1); // empty storage on a new frequency
        s.player().getInventory().setItem(0, GameTestFixtures.carrier(1, GameTestFixtures.frequency(other)));
        String before = state(s.player(), s.dank());
        TransferOutcome outcome = server().onTransfer(s.player(), r);
        check(outcome == TransferOutcome.STALE || outcome == TransferOutcome.MISSING, "relinked Dank: " + outcome);
        check(before.equals(state(s.player(), s.dank())), "nothing moved");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void invalidRecipesRejected(GameTestHelper helper) {
        Setup s = setup(helper, "recipe");
        assertRejected(s, s.request(ResourceLocation.fromNamespaceAndPath("minecraft", "no_such_recipe"), 1), TransferOutcome.INVALID_RECIPE);
        assertRejected(s, s.request(ResourceLocation.fromNamespaceAndPath("minecraft", "iron_ingot_from_smelting_raw_iron"), 1),
                TransferOutcome.INVALID_RECIPE);
        assertRejected(s, s.request(ResourceLocation.fromNamespaceAndPath("minecraft", "armor_dye"), 1), TransferOutcome.INVALID_RECIPE);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void illegalQuantitiesRejected(GameTestHelper helper) {
        Setup s = setup(helper, "quantity");
        assertRejected(s, s.request(STICK, 0), TransferOutcome.ILLEGAL_QUANTITY);
        assertRejected(s, s.request(STICK, -3), TransferOutcome.ILLEGAL_QUANTITY);
        assertRejected(s, s.request(STICK, TransferMessages.MAX_FINITE_BATCHES + 1), TransferOutcome.ILLEGAL_QUANTITY);
        assertRejected(s, s.request(STICK, Integer.MIN_VALUE), TransferOutcome.ILLEGAL_QUANTITY);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void occupiedCursorRejected(GameTestHelper helper) {
        Setup s = setup(helper, "cursor");
        s.menu().setCarried(new ItemStack(Items.APPLE, 3));
        assertRejected(s, s.request(STICK, 1), TransferOutcome.CURSOR_OCCUPIED);
        s.menu().setCarried(ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void duplicateRequestRejected(GameTestHelper helper) {
        Setup s = setup(helper, "duplicate");
        TransferRequest r = s.request(STICK, 1);
        check(server().onTransfer(s.player(), r) == TransferOutcome.SUCCESS, "first use");
        TransferRequest replay = new TransferRequest(r.requestId(), r.menuId(), r.session(), s.subscription().revision(), STICK, 1);
        assertRejected(s, replay, TransferOutcome.DUPLICATE);
        check(count(s.dank(), Items.OAK_PLANKS) == 62, "replay moved nothing");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void simultaneousRequestsDoNotDoubleSpend(GameTestHelper helper) {
        Setup s = setup(helper, "simultaneous");
        // Both requests were planned against the same snapshot revision.
        TransferRequest first = s.request(STICK, 1);
        TransferRequest second = s.request(STICK, 1);
        check(server().onTransfer(s.player(), first) == TransferOutcome.SUCCESS, "first wins");
        String before = state(s.player(), s.dank());
        check(server().onTransfer(s.player(), second) == TransferOutcome.STALE, "second sees a moved revision");
        check(before.equals(state(s.player(), s.dank())), "second moved nothing");
        check(s.menu().getSlot(1).getItem().getCount() == 1, "exactly one set placed");
        helper.succeed();
    }
}
