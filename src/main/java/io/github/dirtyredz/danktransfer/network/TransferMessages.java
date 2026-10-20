package io.github.dirtyredz.danktransfer.network;

import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wire payloads. Every decoder is bounded: entry counts, quantities and the snapshot size are
 * capped, and malformed input raises {@link DecoderException} (the network layer drops the packet).
 *
 * <p>Requests never carry item grants or source ids: the server rediscovers sources and replans.
 */
public final class TransferMessages {
    /** Snapshot entries beyond this are never sent; the snapshot is then marked truncated. */
    public static final int MAX_SNAPSHOT_ENTRIES = 2048;
    /** Byte budget for one snapshot (Forge's clientbound custom payload limit is 1 MiB). */
    public static final int MAX_SNAPSHOT_BYTES = 900_000;
    /** Largest finite batch count a client may request; {@link #MAX_BATCHES} asks for the maximum. */
    public static final int MAX_FINITE_BATCHES = 64;
    public static final int MAX_BATCHES = Integer.MAX_VALUE;

    private TransferMessages() {
    }

    /** Reads and writes one item prototype. Production uses {@link #ITEMS}; tests use a fake. */
    public interface PrototypeCodec<T> {
        void write(FriendlyByteBuf buf, T prototype);

        T read(FriendlyByteBuf buf);
    }

    /** Count-1 item prototypes with NBT, via vanilla's bounded item codec. */
    public static final PrototypeCodec<ItemStack> ITEMS = new PrototypeCodec<>() {
        @Override
        public void write(FriendlyByteBuf buf, ItemStack prototype) {
            buf.writeItem(prototype.copyWithCount(1));
        }

        @Override
        public ItemStack read(FriendlyByteBuf buf) {
            ItemStack stack = buf.readItem();
            if (stack.isEmpty()) {
                throw new DecoderException("empty snapshot prototype");
            }
            return stack;
        }
    };

    /** C2S: start ({@code active}) or stop watching Dank contents for an open crafting menu. */
    public record Subscribe(int menuId, boolean active) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(menuId);
            buf.writeBoolean(active);
        }

        public static Subscribe decode(FriendlyByteBuf buf) {
            return new Subscribe(buf.readVarInt(), buf.readBoolean());
        }
    }

    /** One aggregated snapshot line: an item identity and how many the player's Danks hold. */
    public record Entry<T>(T prototype, long count) {
        public Entry {
            Objects.requireNonNull(prototype, "prototype");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    /**
     * S2C: the aggregated contents of every valid Dank the player carries.
     *
     * @param menuId    the menu this snapshot belongs to
     * @param session   server-chosen token for this subscription (menu ids are reused)
     * @param revision  increases on every content change within a session
     * @param truncated true when entries were dropped to respect the size bounds
     */
    public record Snapshot<T>(int menuId, int session, int revision, boolean truncated, List<Entry<T>> entries) {
        public Snapshot {
            entries = List.copyOf(entries);
        }

        public void encode(FriendlyByteBuf buf, PrototypeCodec<T> codec) {
            buf.writeVarInt(menuId);
            buf.writeInt(session);
            buf.writeVarInt(revision);
            boolean cut = truncated;
            int written = 0;
            for (Entry<T> entry : entries) {
                if (written >= MAX_SNAPSHOT_ENTRIES) {
                    cut = true;
                    break;
                }
                int mark = buf.writerIndex();
                buf.writeBoolean(true);
                codec.write(buf, entry.prototype());
                buf.writeVarLong(entry.count());
                if (buf.writerIndex() > MAX_SNAPSHOT_BYTES) {
                    buf.writerIndex(mark);
                    cut = true;
                    break;
                }
                written++;
            }
            buf.writeBoolean(false);
            buf.writeBoolean(cut);
        }

        public static <T> Snapshot<T> decode(FriendlyByteBuf buf, PrototypeCodec<T> codec) {
            if (buf.readableBytes() > MAX_SNAPSHOT_BYTES + 64) {
                throw new DecoderException("snapshot too large: " + buf.readableBytes());
            }
            int menuId = buf.readVarInt();
            int session = buf.readInt();
            int revision = buf.readVarInt();
            List<Entry<T>> entries = new ArrayList<>();
            while (buf.readBoolean()) {
                if (entries.size() >= MAX_SNAPSHOT_ENTRIES) {
                    throw new DecoderException("too many snapshot entries");
                }
                T prototype = codec.read(buf);
                long count = buf.readVarLong();
                if (count <= 0) {
                    throw new DecoderException("non-positive snapshot count: " + count);
                }
                entries.add(new Entry<>(prototype, count));
            }
            boolean truncated = buf.readBoolean();
            if (buf.isReadable()) {
                throw new DecoderException("trailing snapshot bytes: " + buf.readableBytes());
            }
            return new Snapshot<>(menuId, session, revision, truncated, entries);
        }
    }

    /**
     * C2S: place {@code batches} sets of {@code recipeId} into the open grid.
     *
     * @param requestId client correlation id, unique per connection
     * @param revision  the snapshot revision the client planned against
     * @param batches   1..{@link #MAX_FINITE_BATCHES}, or {@link #MAX_BATCHES}
     */
    public record TransferRequest(int requestId, int menuId, int session, int revision,
                                  ResourceLocation recipeId, int batches) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(requestId);
            buf.writeVarInt(menuId);
            buf.writeInt(session);
            buf.writeVarInt(revision);
            buf.writeResourceLocation(recipeId);
            buf.writeInt(batches);
        }

        public static TransferRequest decode(FriendlyByteBuf buf) {
            return new TransferRequest(buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readVarInt(),
                    buf.readResourceLocation(), buf.readInt());
        }

        public boolean legalQuantity() {
            return batches == MAX_BATCHES || (batches >= 1 && batches <= MAX_FINITE_BATCHES);
        }
    }

    /** S2C: the acknowledgement for one request. */
    public record TransferResult(int requestId, TransferOutcome outcome) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(requestId);
            buf.writeVarInt(outcome.ordinal());
        }

        public static TransferResult decode(FriendlyByteBuf buf) {
            return new TransferResult(buf.readVarInt(), TransferOutcome.byId(buf.readVarInt()));
        }
    }
}
