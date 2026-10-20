package io.github.dirtyredz.danktransfer.client;

import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Request correlation for the client: at most one transfer in flight, every request completes
 * exactly once (acknowledgement, timeout or invalidation), and late or duplicate acknowledgements
 * are ignored.
 */
public final class PendingTransfer {
    private record Pending(int id, long startedTick, Consumer<TransferOutcome> callback) {
    }

    private int nextId = 1;
    @Nullable
    private Pending current;

    public boolean busy() {
        return current != null;
    }

    /** Reserves a request id, or empty when a transfer is already in flight. */
    public OptionalInt begin(long tick, Consumer<TransferOutcome> callback) {
        if (current != null) {
            return OptionalInt.empty();
        }
        int id = nextId++;
        if (nextId <= 0) {
            nextId = 1;
        }
        current = new Pending(id, tick, callback);
        return OptionalInt.of(id);
    }

    /**
     * Completes the in-flight request when {@code id} matches it.
     *
     * @return false for unknown, late or duplicate acknowledgements
     */
    public boolean complete(int id, TransferOutcome outcome) {
        Pending pending = current;
        if (pending == null || pending.id != id) {
            return false;
        }
        current = null;
        pending.callback.accept(outcome);
        return true;
    }

    /** Fails the in-flight request if it has waited longer than {@code timeoutTicks}. */
    public void expire(long tick, int timeoutTicks) {
        Pending pending = current;
        if (pending != null && tick - pending.startedTick > timeoutTicks) {
            complete(pending.id, TransferOutcome.TIMEOUT);
        }
    }

    /** Fails the in-flight request immediately (menu closed, disconnect). */
    public void cancel(TransferOutcome outcome) {
        Pending pending = current;
        if (pending != null) {
            complete(pending.id, outcome);
        }
    }
}
