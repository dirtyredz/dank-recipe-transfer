package io.github.dirtyredz.danktransfer.network.payload;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S wrapper for {@link Subscribe}. */
public record SubscribePayload(Subscribe message) implements CustomPacketPayload {
    public static final Type<SubscribePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DankRecipeTransfer.MOD_ID, "subscribe"));
    public static final StreamCodec<FriendlyByteBuf, SubscribePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.message().encode(buf), buf -> new SubscribePayload(Subscribe.decode(buf)));

    @Override
    public Type<SubscribePayload> type() {
        return TYPE;
    }
}
