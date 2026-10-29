package io.github.dirtyredz.danktransfer.network.payload;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C wrapper for {@link TransferResult}. */
public record TransferResultPayload(TransferResult message) implements CustomPacketPayload {
    public static final Type<TransferResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DankRecipeTransfer.MOD_ID, "transfer_result"));
    public static final StreamCodec<FriendlyByteBuf, TransferResultPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.message().encode(buf), buf -> new TransferResultPayload(TransferResult.decode(buf)));

    @Override
    public Type<TransferResultPayload> type() {
        return TYPE;
    }
}
