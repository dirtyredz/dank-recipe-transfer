package io.github.dirtyredz.danktransfer.network.payload;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferRequest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S wrapper for {@link TransferRequest}. */
public record TransferRequestPayload(TransferRequest message) implements CustomPacketPayload {
    public static final Type<TransferRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DankRecipeTransfer.MOD_ID, "transfer_request"));
    public static final StreamCodec<FriendlyByteBuf, TransferRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.message().encode(buf), buf -> new TransferRequestPayload(TransferRequest.decode(buf)));

    @Override
    public Type<TransferRequestPayload> type() {
        return TYPE;
    }
}
