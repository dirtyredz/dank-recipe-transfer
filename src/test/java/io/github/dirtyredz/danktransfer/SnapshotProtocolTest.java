package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.client.DankSnapshotCache;
import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import io.github.dirtyredz.danktransfer.network.TransferMessages;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Entry;
import io.github.dirtyredz.danktransfer.network.TransferMessages.PrototypeCodec;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Snapshot;
import io.github.dirtyredz.danktransfer.network.TransferMessages.Subscribe;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferRequest;
import io.github.dirtyredz.danktransfer.network.TransferMessages.TransferResult;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire format bounds and client cache rules, with string prototypes standing in for items. */
class SnapshotProtocolTest {
    private static final PrototypeCodec<String> STRINGS = new PrototypeCodec<>() {
        @Override
        public void write(FriendlyByteBuf buf, String prototype) {
            buf.writeUtf(prototype, 32767);
        }

        @Override
        public String read(FriendlyByteBuf buf) {
            return buf.readUtf(32767);
        }
    };

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static Snapshot<String> roundTrip(Snapshot<String> snapshot) {
        FriendlyByteBuf buf = buf();
        snapshot.encode(buf, STRINGS);
        return Snapshot.decode(buf, STRINGS);
    }

    @SafeVarargs
    private static Snapshot<String> snapshot(int menu, int session, int revision, Entry<String>... entries) {
        return new Snapshot<>(menu, session, revision, false, List.of(entries));
    }

    @Test
    void largeCountsSurvive() {
        Snapshot<String> in = snapshot(3, 42, 7,
                new Entry<>("a", 128), new Entry<>("b", 32_768), new Entry<>("c", 5_000_000_000L));
        Snapshot<String> out = roundTrip(in);
        assertEquals(in, out);
    }

    @Test
    void oversizedSnapshotIsTruncatedNotRejected() {
        List<Entry<String>> entries = new ArrayList<>();
        for (int i = 0; i < TransferMessages.MAX_SNAPSHOT_ENTRIES + 10; i++) {
            entries.add(new Entry<>("item_" + i, i + 1));
        }
        Snapshot<String> out = roundTrip(new Snapshot<>(1, 1, 1, false, entries));
        assertTrue(out.truncated());
        assertEquals(TransferMessages.MAX_SNAPSHOT_ENTRIES, out.entries().size());
    }

    @Test
    void byteBudgetTruncates() {
        String big = "x".repeat(1000);
        List<Entry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            entries.add(new Entry<>(big, 1));
        }
        FriendlyByteBuf buf = buf();
        new Snapshot<>(1, 1, 1, false, entries).encode(buf, STRINGS);
        assertTrue(buf.writerIndex() <= TransferMessages.MAX_SNAPSHOT_BYTES + 2);
        Snapshot<String> out = Snapshot.decode(buf, STRINGS);
        assertTrue(out.truncated());
        assertTrue(out.entries().size() < 1500);
    }

    @Test
    void malformedPayloadsAreRejected() {
        // Non-positive count.
        FriendlyByteBuf negative = buf();
        negative.writeVarInt(1);
        negative.writeInt(1);
        negative.writeVarInt(1);
        negative.writeBoolean(true);
        negative.writeUtf("a");
        negative.writeVarLong(0);
        assertThrows(DecoderException.class, () -> Snapshot.decode(negative, STRINGS));

        // Too many entries.
        FriendlyByteBuf flood = buf();
        flood.writeVarInt(1);
        flood.writeInt(1);
        flood.writeVarInt(1);
        for (int i = 0; i <= TransferMessages.MAX_SNAPSHOT_ENTRIES; i++) {
            flood.writeBoolean(true);
            flood.writeUtf("a");
            flood.writeVarLong(1);
        }
        flood.writeBoolean(false);
        flood.writeBoolean(false);
        assertThrows(DecoderException.class, () -> Snapshot.decode(flood, STRINGS));

        // Trailing garbage.
        FriendlyByteBuf trailing = buf();
        snapshot(1, 1, 1).encode(trailing, STRINGS);
        trailing.writeByte(9);
        assertThrows(DecoderException.class, () -> Snapshot.decode(trailing, STRINGS));

        // Truncated buffer.
        FriendlyByteBuf cut = buf();
        snapshot(1, 1, 1, new Entry<>("a", 5)).encode(cut, STRINGS);
        cut.writerIndex(cut.writerIndex() - 2);
        assertThrows(RuntimeException.class, () -> Snapshot.decode(cut, STRINGS));
    }

    @Test
    void requestAndResultRoundTrip() {
        TransferRequest request = new TransferRequest(9, 4, -77, 3, ResourceLocation.fromNamespaceAndPath("minecraft", "stick"), 64);
        FriendlyByteBuf buf = buf();
        request.encode(buf);
        assertEquals(request, TransferRequest.decode(buf));

        TransferResult result = new TransferResult(9, TransferOutcome.STALE);
        FriendlyByteBuf rbuf = buf();
        result.encode(rbuf);
        assertEquals(result, TransferResult.decode(rbuf));

        Subscribe subscribe = new Subscribe(4, true);
        FriendlyByteBuf sbuf = buf();
        subscribe.encode(sbuf);
        assertEquals(subscribe, Subscribe.decode(sbuf));
    }

    @Test
    void unknownOutcomeDecodesSafely() {
        FriendlyByteBuf buf = buf();
        buf.writeVarInt(1);
        buf.writeVarInt(999);
        assertEquals(TransferOutcome.INVALID_RECIPE, TransferResult.decode(buf).outcome());
    }

    @Test
    void quantityBounds() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("minecraft", "stick");
        assertTrue(new TransferRequest(1, 1, 1, 1, id, 1).legalQuantity());
        assertTrue(new TransferRequest(1, 1, 1, 1, id, 64).legalQuantity());
        assertTrue(new TransferRequest(1, 1, 1, 1, id, TransferMessages.MAX_BATCHES).legalQuantity());
        assertFalse(new TransferRequest(1, 1, 1, 1, id, 0).legalQuantity());
        assertFalse(new TransferRequest(1, 1, 1, 1, id, -5).legalQuantity());
        assertFalse(new TransferRequest(1, 1, 1, 1, id, 65).legalQuantity());
    }

    // ---- client cache ----

    @Test
    void cacheIgnoresOtherMenus() {
        DankSnapshotCache<String> cache = new DankSnapshotCache<>();
        cache.watch(5);
        assertFalse(cache.accept(snapshot(6, 1, 0)));
        assertNull(cache.current(5));
        assertTrue(cache.accept(snapshot(5, 1, 0)));
        assertNull(cache.current(6), "a snapshot is only usable for its own menu");
    }

    @Test
    void cacheDropsStaleAndOutOfOrderRevisions() {
        DankSnapshotCache<String> cache = new DankSnapshotCache<>();
        cache.watch(5);
        Snapshot<String> r2 = snapshot(5, 1, 2, new Entry<>("a", 2));
        assertTrue(cache.accept(r2));
        assertFalse(cache.accept(snapshot(5, 1, 1)), "older revision");
        assertFalse(cache.accept(snapshot(5, 1, 2)), "duplicate revision");
        assertSame(r2, cache.current(5));
        assertTrue(cache.accept(snapshot(5, 1, 3)));
    }

    @Test
    void newSessionOnReusedMenuIdReplaces() {
        DankSnapshotCache<String> cache = new DankSnapshotCache<>();
        cache.watch(5);
        cache.accept(snapshot(5, 1, 9));
        Snapshot<String> fresh = snapshot(5, 2, 0);
        assertTrue(cache.accept(fresh), "a new session restarts revisions");
        assertSame(fresh, cache.current(5));
    }

    @Test
    void invalidationClearsAndCountsChanges() {
        DankSnapshotCache<String> cache = new DankSnapshotCache<>();
        cache.watch(5);
        int start = cache.changeCount();
        cache.accept(snapshot(5, 1, 0));
        assertEquals(start + 1, cache.changeCount());
        cache.invalidate();
        assertNull(cache.current(5));
        assertEquals(start + 2, cache.changeCount());
        assertFalse(cache.accept(snapshot(5, 1, 1)), "nothing is watched after invalidation");
        cache.watch(5);
        assertTrue(cache.accept(snapshot(5, 1, 1)));
        cache.watch(7);
        assertNull(cache.current(5), "watching another menu drops the old snapshot");
    }

    @Test
    void snapshotsAreImmutable() {
        List<Entry<String>> entries = new ArrayList<>(List.of(new Entry<>("a", 1)));
        Snapshot<String> snapshot = new Snapshot<>(1, 1, 1, false, entries);
        entries.add(new Entry<>("b", 1));
        assertEquals(1, snapshot.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().add(new Entry<>("c", 1)));
    }
}
