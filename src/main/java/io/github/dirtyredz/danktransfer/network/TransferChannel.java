package io.github.dirtyredz.danktransfer.network;

import io.github.dirtyredz.danktransfer.client.ClientPayloadHandler;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferResult;
import io.github.dirtyredz.danktransfer.network.payload.SnapshotPayload;
import io.github.dirtyredz.danktransfer.network.payload.SubscribePayload;
import io.github.dirtyredz.danktransfer.network.payload.TransferRequestPayload;
import io.github.dirtyredz.danktransfer.network.payload.TransferResultPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

/**
 * Payload registration, direction checks and thread dispatch. No viewer dependencies.
 *
 * <p>All four payloads are registered {@code optional()}, so a vanilla client or a server without this
 * mod still connects; nothing is sent to a peer that did not negotiate the payload, and the client
 * adapters then fall back to native transfer. Protocol {@value #PROTOCOL} is a new wire format: a
 * Forge 1.20.1 peer never interoperates with it.
 *
 * <p>Client callbacks are reached only through lambdas, so a dedicated server never loads
 * {@link ClientPayloadHandler} (clientbound handlers never run there).
 */
public final class TransferChannel {
    public static final String PROTOCOL = "2";

    private static ServerRequestHandler server;

    private TransferChannel() {
    }

    public static void register(IEventBus modBus, ServerRequestHandler handler) {
        server = handler;
        modBus.addListener(RegisterPayloadHandlersEvent.class, TransferChannel::onRegister);
    }

    private static void onRegister(RegisterPayloadHandlersEvent event) {
        // Game-state work runs on the main thread; every handler below touches menus or storage.
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional().executesOn(HandlerThread.MAIN);
        registrar.playToServer(SubscribePayload.TYPE, SubscribePayload.STREAM_CODEC, (payload, context) -> {
            ServerPlayer sender = serverSender(context);
            if (sender != null) {
                server.onSubscribe(sender, payload.message());
            }
        });
        registrar.playToServer(TransferRequestPayload.TYPE, TransferRequestPayload.STREAM_CODEC, (payload, context) -> {
            ServerPlayer sender = serverSender(context);
            if (sender != null) {
                TransferResult result = new TransferResult(payload.message().requestId(),
                        server.onTransfer(sender, payload.message()));
                send(sender, new TransferResultPayload(result));
            }
        });
        registrar.playToClient(SnapshotPayload.TYPE, SnapshotPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandler.onSnapshot(payload));
        registrar.playToClient(TransferResultPayload.TYPE, TransferResultPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandler.onResult(payload));
    }

    @Nullable
    private static ServerPlayer serverSender(IPayloadContext context) {
        return context.player() instanceof ServerPlayer player ? player : null;
    }

    /** Whether the other end of {@code listener} negotiated this mod's payloads. */
    public static boolean isRemotePresent(@Nullable ICommonPacketListener listener) {
        return listener != null && listener.hasChannel(SubscribePayload.TYPE) && listener.hasChannel(SnapshotPayload.TYPE);
    }

    /** Sends a snapshot to {@code player}, when that client runs this protocol. */
    public static void sendSnapshot(ServerPlayer player, Snapshot<ItemStack> snapshot) {
        send(player, new SnapshotPayload(snapshot));
    }

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (!(player instanceof FakePlayer) && player.connection != null && player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** Sends a serverbound payload, when the server negotiated it. */
    public static void sendToServer(@Nullable ICommonPacketListener connection, CustomPacketPayload payload) {
        if (connection != null && connection.hasChannel(payload.type())) {
            PacketDistributor.sendToServer(payload);
        }
    }
}
