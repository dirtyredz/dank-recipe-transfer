package io.github.dirtyredz.danktransfer.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * Production snapshot prototypes: count-1 item stacks with their full data-component patch, via
 * vanilla's registry-aware {@link ItemStack#STREAM_CODEC} (which bounds and validates components).
 *
 * <p>Components reference registries (enchantments, for instance), so the buffer must carry
 * registry context; a plain buffer fails closed instead of silently dropping components.
 */
public final class ItemPrototypeCodec implements TransferMessages.PrototypeCodec<ItemStack> {
    public static final ItemPrototypeCodec INSTANCE = new ItemPrototypeCodec();

    private ItemPrototypeCodec() {
    }

    @Override
    public void write(FriendlyByteBuf buf, ItemStack prototype) {
        if (!(buf instanceof RegistryFriendlyByteBuf registries)) {
            throw new EncoderException("snapshot prototypes need a registry-aware buffer");
        }
        if (prototype.isEmpty()) {
            throw new EncoderException("empty snapshot prototype");
        }
        ItemStack.STREAM_CODEC.encode(registries, prototype.copyWithCount(1));
    }

    @Override
    public ItemStack read(FriendlyByteBuf buf) {
        if (!(buf instanceof RegistryFriendlyByteBuf registries)) {
            throw new DecoderException("snapshot prototypes need a registry-aware buffer");
        }
        ItemStack stack = ItemStack.STREAM_CODEC.decode(registries);
        if (stack.isEmpty() || stack.getCount() != 1) {
            throw new DecoderException("snapshot prototype must be a single item: " + stack);
        }
        return stack;
    }
}
