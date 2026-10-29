package io.github.dirtyredz.danktransfer;

import com.mojang.authlib.GameProfile;
import io.github.dirtyredz.danktransfer.compat.MixinGate;
import io.github.dirtyredz.danktransfer.network.TransferChannel;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.github.dirtyredz.danktransfer.network.payload.SnapshotPayload;
import io.github.dirtyredz.danktransfer.network.payload.SubscribePayload;
import io.github.dirtyredz.danktransfer.network.payload.TransferRequestPayload;
import io.github.dirtyredz.danktransfer.network.payload.TransferResultPayload;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import tfar.dankstorage.world.DankInventoryForge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.dirtyredz.danktransfer.GameTestFixtures.TEMPLATE;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.check;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.newDank;
import static io.github.dirtyredz.danktransfer.GameTestFixtures.storage;

/**
 * The subscription path end to end on the server: a real (non-fake) player with an embedded
 * connection, so the snapshot really goes through {@code PacketDistributor} and NeoForge's channel
 * check, then is encoded and decoded with the registered payload codec and registry context, the
 * way the client decodes it. The FakePlayer tests skip sending entirely.
 */
@GameTestHolder(DankRecipeTransfer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SnapshotWireGameTests {
    private SnapshotWireGameTests() {
    }

    /** A real server player whose packets land in an embedded channel. */
    private record WiredPlayer(ServerPlayer player, EmbeddedChannel wire) {
        /** Every snapshot written to the wire so far, round-tripped through the registered codec. */
        List<Snapshot<ItemStack>> snapshots() {
            List<Snapshot<ItemStack>> out = new ArrayList<>();
            Object packet;
            while ((packet = wire.readOutbound()) != null) {
                if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof SnapshotPayload payload) {
                    RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                            player.server.registryAccess(), ConnectionType.NEOFORGE);
                    SnapshotPayload.STREAM_CODEC.encode(buf, payload);
                    out.add(SnapshotPayload.STREAM_CODEC.decode(buf).snapshot());
                    check(!buf.isReadable(), "codec consumed the whole snapshot body");
                }
            }
            return out;
        }
    }

    /**
     * @param negotiated whether the client side "has the mod": its play channels then include this
     *                   protocol's payloads, as NeoForge records them after negotiation
     */
    private static WiredPlayer wiredPlayer(GameTestHelper helper, String name, boolean negotiated) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.nameUUIDFromBytes(("drt-wire-" + name).getBytes()), "drt_wire_" + name);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel wire = new EmbeddedChannel(connection);
        ChannelAttributes.setConnectionType(connection, ConnectionType.NEOFORGE);
        if (negotiated) {
            Map<ResourceLocation, NetworkChannel> play = new HashMap<>();
            for (ResourceLocation id : List.of(SubscribePayload.TYPE.id(), TransferRequestPayload.TYPE.id(),
                    SnapshotPayload.TYPE.id(), TransferResultPayload.TYPE.id())) {
                play.put(id, new NetworkChannel(id, TransferChannel.PROTOCOL));
            }
            ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(Map.of(ConnectionProtocol.PLAY, play)));
        }
        new ServerGamePacketListenerImpl(level.getServer(), connection, player, CommonListenerCookie.createInitial(profile, false));
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        player.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        return new WiredPlayer(player, wire);
    }

    private static CraftingMenu open(ServerPlayer player, CraftingMenu menu) {
        player.containerMenu = menu;
        return menu;
    }

    private static CraftingMenu table(GameTestHelper helper, ServerPlayer player, int id) {
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CRAFTING_TABLE);
        return open(player, new CraftingMenu(id, player.getInventory(),
                ContainerLevelAccess.create(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 2)))));
    }

    @GameTest(template = TEMPLATE)
    public static void vanillaTableSubscriptionSendsSnapshot(GameTestHelper helper) {
        WiredPlayer wired = wiredPlayer(helper, "vanilla", true);
        ItemStack carrier = newDank(helper.getLevel(), 1);
        DankInventoryForge dank = storage(helper.getLevel(), carrier);
        dank.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 200));
        wired.player().getInventory().setItem(0, carrier);
        CraftingMenu menu = table(helper, wired.player(), 7);

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

    /** Component-bearing prototypes (registry-backed enchantments, names) survive the real codec. */
    @GameTest(template = TEMPLATE)
    public static void componentPrototypesSurviveTheCodec(GameTestHelper helper) {
        WiredPlayer wired = wiredPlayer(helper, "components", true);
        ItemStack carrier = newDank(helper.getLevel(), 1);
        DankInventoryForge dank = storage(helper.getLevel(), carrier);
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(helper.getLevel().registryAccess().holderOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Edge"));
        dank.setStackInSlot(0, sword.copy());
        dank.setStackInSlot(1, new ItemStack(Items.IRON_SWORD));
        wired.player().getInventory().setItem(0, carrier);
        CraftingMenu menu = table(helper, wired.player(), 9);

        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(menu.containerId, true));
        List<Snapshot<ItemStack>> sent = wired.snapshots();
        check(sent.size() == 1 && sent.get(0).entries().size() == 2, "two distinct sword identities: "
                + (sent.isEmpty() ? "none" : sent.get(0).entries()));
        boolean found = sent.get(0).entries().stream()
                .anyMatch(entry -> ItemStack.isSameItemSameComponents(entry.prototype(), sword) && entry.count() == 1);
        check(found, "the enchanted, named sword decodes with every component");
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
        WiredPlayer wired = wiredPlayer(helper, "subclass", true);
        ItemStack carrier = newDank(helper.getLevel(), 1);
        DankInventoryForge dank = storage(helper.getLevel(), carrier);
        dank.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 6));
        wired.player().getInventory().setItem(0, carrier);
        CraftingMenu menu = open(wired.player(),
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
        WiredPlayer wired = wiredPlayer(helper, "unsupported", true);
        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(41, true));
        List<Snapshot<ItemStack>> sent = wired.snapshots();
        check(sent.size() == 1, "an explicit reply is sent, got " + sent.size());
        check(sent.get(0).menuId() == 41 && !sent.get(0).supported(), "reply marks the menu unsupported");
        helper.succeed();
    }

    /**
     * A client without this mod never negotiated the payloads. NeoForge refuses to send an
     * unnegotiated payload (it throws), so the server must stay silent instead: the vanilla client
     * then keeps native behaviour.
     */
    @GameTest(template = TEMPLATE)
    public static void absentPeerGetsNothingAndNothingThrows(GameTestHelper helper) {
        WiredPlayer wired = wiredPlayer(helper, "absent", false);
        check(!TransferChannel.isRemotePresent(wired.player().connection), "an unnegotiated peer is not present");
        ItemStack carrier = newDank(helper.getLevel(), 1);
        storage(helper.getLevel(), carrier).setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 3));
        wired.player().getInventory().setItem(0, carrier);
        CraftingMenu menu = table(helper, wired.player(), 11);

        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(menu.containerId, true));
        DankRecipeTransfer.server().onSubscribe(wired.player(), new Subscribe(99, true));
        check(wired.snapshots().isEmpty(), "nothing is sent to a peer without the mod");
        DankRecipeTransfer.server().snapshots().forget(wired.player());
        helper.succeed();
    }

    /**
     * The GameTest server is a dedicated server: it booted with this mod's payloads registered, so the
     * registration never loaded client classes, and the mixin gate keeps every viewer mixin off.
     */
    @GameTest(template = TEMPLATE)
    public static void dedicatedServerKeepsClientCodeUnloaded(GameTestHelper helper) {
        check(FMLEnvironment.dist == Dist.DEDICATED_SERVER, "GameTests run on a dedicated server, got " + FMLEnvironment.dist);
        MixinGate gate = new MixinGate();
        for (String mixin : List.of("jei.JeiTransferLookupMixin", "emi.EmiHandlerLookupMixin", "emi.EmiFillButtonMixin",
                "emi.RecipeButtonWidgetAccessor")) {
            check(!gate.shouldApplyMixin("target", "io.github.dirtyredz.danktransfer.compat.mixin." + mixin),
                    mixin + " must not apply on a dedicated server");
        }
        helper.succeed();
    }
}
