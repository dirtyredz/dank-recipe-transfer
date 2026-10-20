package io.github.dirtyredz.danktransfer.network;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.client.ClientTransfer;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferRequest;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferResult;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Protocol registration, direction checks and thread dispatch. No viewer dependencies.
 *
 * <p>The channel accepts a peer without it ({@code acceptMissingOr}), so a vanilla client or a
 * server without this mod still connects; the client adapters then fall back to native transfer.
 */
public final class TransferChannel {
    public static final String PROTOCOL = "1";

    private static SimpleChannel channel;
    private static ServerRequestHandler server;

    private TransferChannel() {
    }

    public static void register(ServerRequestHandler handler) {
        server = handler;
        channel = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(DankRecipeTransfer.MOD_ID, "transfer"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
                .simpleChannel();

        channel.messageBuilder(Subscribe.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Subscribe::encode)
                .decoder(Subscribe::decode)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        server.onSubscribe(sender, msg);
                    }
                })
                .add();

        channel.messageBuilder(TransferRequest.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TransferRequest::encode)
                .decoder(TransferRequest::decode)
                .consumerMainThread((msg, ctx) -> {
                    ServerPlayer sender = ctx.get().getSender();
                    if (sender != null) {
                        sendTo(sender, new TransferResult(msg.requestId(), server.onTransfer(sender, msg)));
                    }
                })
                .add();

        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Snapshot<ItemStack>> snapshotClass = (Class) Snapshot.class;
        channel.messageBuilder(snapshotClass, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder((msg, buf) -> msg.encode(buf, TransferMessages.ITEMS))
                .decoder(buf -> Snapshot.decode(buf, TransferMessages.ITEMS))
                .consumerMainThread((msg, ctx) ->
                        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientTransfer.onSnapshot(msg)))
                .add();

        channel.messageBuilder(TransferResult.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TransferResult::encode)
                .decoder(TransferResult::decode)
                .consumerMainThread((msg, ctx) ->
                        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientTransfer.onResult(msg)))
                .add();
    }

    /** Whether the other end of {@code connection} runs this protocol. */
    public static boolean isRemotePresent(Connection connection) {
        return channel != null && connection != null && channel.isRemotePresent(connection);
    }

    public static void sendTo(ServerPlayer player, Object message) {
        if (channel != null && player.connection != null && !(player instanceof FakePlayer)) {
            channel.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    public static void sendToServer(Object message) {
        if (channel != null) {
            channel.sendToServer(message);
        }
    }
}
