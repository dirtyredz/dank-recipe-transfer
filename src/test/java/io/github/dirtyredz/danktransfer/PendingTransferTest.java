package io.github.dirtyredz.danktransfer;

import io.github.dirtyredz.danktransfer.client.PendingTransfer;
import io.github.dirtyredz.danktransfer.crafting.TransferOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The correlation rules every viewer adapter relies on: one transfer in flight, completion exactly
 * once (JEI's {@code completeRecipeTransfer} contract), late and duplicate acknowledgements ignored.
 */
class PendingTransferTest {

    @Test
    void completesExactlyOnceOnAcknowledgement() {
        PendingTransfer pending = new PendingTransfer();
        List<TransferOutcome> calls = new ArrayList<>();
        int id = pending.begin(0, calls::add).getAsInt();
        assertTrue(calls.isEmpty(), "nothing completes before the acknowledgement");
        assertTrue(pending.complete(id, TransferOutcome.SUCCESS));
        assertFalse(pending.complete(id, TransferOutcome.SUCCESS), "duplicate acknowledgement");
        assertEquals(List.of(TransferOutcome.SUCCESS), calls);
    }

    @Test
    void onlyOneTransferInFlight() {
        PendingTransfer pending = new PendingTransfer();
        assertTrue(pending.begin(0, o -> { }).isPresent());
        assertTrue(pending.busy());
        assertEquals(OptionalInt.empty(), pending.begin(0, o -> { }));
    }

    @Test
    void unknownAcknowledgementIgnored() {
        PendingTransfer pending = new PendingTransfer();
        List<TransferOutcome> calls = new ArrayList<>();
        int id = pending.begin(0, calls::add).getAsInt();
        assertFalse(pending.complete(id + 1, TransferOutcome.SUCCESS));
        assertTrue(calls.isEmpty());
        assertTrue(pending.busy());
    }

    @Test
    void timeoutCompletesOnceAndLateAckIsIgnored() {
        PendingTransfer pending = new PendingTransfer();
        List<TransferOutcome> calls = new ArrayList<>();
        int id = pending.begin(10, calls::add).getAsInt();
        pending.expire(50, 100);
        assertTrue(calls.isEmpty());
        pending.expire(111, 100);
        assertEquals(List.of(TransferOutcome.TIMEOUT), calls);
        assertFalse(pending.complete(id, TransferOutcome.SUCCESS));
        assertEquals(1, calls.size());
    }

    @Test
    void cancelFailsPendingOnce() {
        PendingTransfer pending = new PendingTransfer();
        List<TransferOutcome> calls = new ArrayList<>();
        pending.begin(0, calls::add);
        pending.cancel(TransferOutcome.WRONG_MENU);
        pending.cancel(TransferOutcome.WRONG_MENU);
        assertEquals(List.of(TransferOutcome.WRONG_MENU), calls);
        assertFalse(pending.busy());
    }

    @Test
    void requestIdsAreUnique() {
        PendingTransfer pending = new PendingTransfer();
        int first = pending.begin(0, o -> { }).getAsInt();
        pending.complete(first, TransferOutcome.SUCCESS);
        int second = pending.begin(0, o -> { }).getAsInt();
        assertTrue(second != first);
    }
}
