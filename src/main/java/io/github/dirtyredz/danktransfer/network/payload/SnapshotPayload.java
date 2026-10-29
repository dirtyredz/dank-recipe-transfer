package io.github.dirtyredz.danktransfer.network.payload;

import io.github.dirtyredz.danktransfer.DankRecipeTransfer;
import io.github.dirtyredz.danktransfer.network.ItemPrototypeCodec;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** S2C wrapper for an item {@link Snapshot}; registry-aware because prototypes carry components. */
public record SnapshotPayload(Snapshot<ItemStack> snapshot) implements CustomPacketPayload {
    public static final Type<SnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DankRecipeTransfer.MOD_ID, "snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> payload.snapshot().encode(buf, ItemPrototypeCodec.INSTANCE),
            buf -> new SnapshotPayload(Snapshot.decode(buf, ItemPrototypeCodec.INSTANCE)));

    @Override
    public Type<SnapshotPayload> type() {
        return TYPE;
    }
}
