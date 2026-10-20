package io.github.dirtyredz.danktransfer;

import com.mojang.authlib.GameProfile;
import io.github.dirtyredz.danktransfer.network.TransferMessages;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tfar.dankstorage.world.DankInventoryForge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.dirtyredz.danktransfer.GameTestFixtures.TEMPLATE;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.check;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.newDank;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.storage;

/**
 * The subscription path end to end on the server: a real (non-fake) player with an embedded
 * connection, so the snapshot is actually encoded and written, then decoded the way the client
 * decodes it. The FakePlayer tests skip sending entirely.
 */
@GameTestHolder(DankRecipeTransfer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SnapshotWireGameTests {
    private SnapshotWireGameTests() {
    }

    /** A real server player whose packets land in an embedded channel. */
    private record WiredPlayer(ServerPlayer player, EmbeddedChannel wire) {
        /** Every snapshot written to the wire so far, decoded like the client does. */
        List<Snapshot<ItemStack>> snapshots() {
            List<Snapshot<ItemStack>> out = new ArrayList<>();
            Object packet;
            while ((packet = wire.readOutbound()) != null) {
                if (packet instanceof ClientboundCustomPayloadPacket payload
                        && payload.getIdentifier().getNamespace().equals(DankRecipeTransfer.MOD_ID)) {
                    FriendlyByteBuf buf = payload.getData();
                    int discriminator = buf.readUnsignedByte();
                    if (discriminator == 2) {
                        out.add(Snapshot.decode(buf, TransferMessages.ITEMS));
                    }
                }
            }
            return out;
        }
    }

    private static WiredPlayer wiredPlayer(GameTestHelper helper, String name) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.nameUUIDFromBytes(("drt-wire-" + name).getBytes()), "drt_wire_" + name));
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel wire = new EmbeddedChannel(connection);
        new ServerGamePacketListenerImpl(level.getServer(), connection, player);
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        player.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        return new WiredPlayer(player, wire);
    }

    private static CraftingMenu open(GameTestHelper helper, ServerPlayer player, CraftingMenu menu) {
        player.containerMenu = menu;
        return menu;
    }

    @GameTest(template = TEMPLATE)
    public static void vanillaTableSubscriptionSendsSnapshot(GameTestHelper helper) {
        WiredPlayer wired = wiredPlayer(helper, "vanilla");
        ItemStack carrier = newDank(helper.getLevel(), 1);
        DankInventoryForge dank = storage(helper.getLevel(), carrier);
        dank.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 200));
        wired.player().getInventory().setItem(0, carrier);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CRAFTING_TABLE);
        CraftingMenu menu = open(helper, wired.player(), new CraftingMenu(7, wired.player().getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)))));

        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(menu.containerId, true));
        List<Snapshot<ItemStack>> sent = wired.snapshots();
        check(sent.size() == 1, "one snapshot on the wire, got " + sent.size());
        Snapshot<ItemStack> snapshot = sent.get(0);
        check(snapshot.menuId() == menu.containerId, "snapshot carries the menu id");
        check(snapshot.entries().size() == 1 && snapshot.entries().get(0).count() == 200
                && snapshot.entries().get(0).prototype().is(Items.COBBLESTONE), "cobblestone x200: " + snapshot.entries());
        DankRecipeTransfer.server().snapshots().forget(wired.player());
        helper.succeed();
    }

    /**
     * Several mods open the crafting table screen ({@code MenuType.CRAFTING}) backed by a server-side
     * {@link CraftingMenu} subclass that only overrides {@code stillValid}: EnderIO's painted
     * crafting table, FTB Essentials' {@code /craft}, Ars Nouveau's craft effect. The client builds
     * a plain {@code CraftingMenu} for that screen, so it subscribes; the server must answer.
     */
    @GameTest(template = TEMPLATE)
    public static void craftingMenuSubclassStillAnswered(GameTestHelper helper) {
        WiredPlayer wired = wiredPlayer(helper, "subclass");
        ItemStack carrier = newDank(helper.getLevel(), 1);
        DankInventoryForge dank = storage(helper.getLevel(), carrier);
        dank.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 6));
        wired.player().getInventory().setItem(0, carrier);
        CraftingMenu menu = open(helper, wired.player(),
                new CraftingMenu(8, wired.player().getInventory(), ContainerLevelAccess.NULL) {
                    @Override
                    public boolean stillValid(net.minecraft.world.entity.player.Player player) {
                        return true;
                    }
                });

        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(menu.containerId, true));
        List<Snapshot<ItemStack>> sent = wired.snapshots();
        check(sent.size() == 1, "a subclass crafting menu must still get a snapshot, got " + sent.size());
        check(sent.get(0).entries().size() == 1, "contents sent: " + sent.get(0).entries());
        DankRecipeTransfer.server().snapshots().forget(wired.player());
        helper.succeed();
    }

    /** A menu the server cannot serve gets an explicit answer, so the client never waits forever. */
    @GameTest(template = TEMPLATE)
    public static void unsupportedMenuIsAnsweredNotIgnored(GameTestHelper helper) {
        WiredPlayer wired = wiredPlayer(helper, "unsupported");
        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(41, true));
        List<Snapshot<ItemStack>> sent = wired.snapshots();
        check(sent.size() == 1, "an explicit reply is sent, got " + sent.size());
        check(sent.get(0).menuId() == 41 && !sent.get(0).supported(), "reply marks the menu unsupported");
        helper.succeed();
    }
}
