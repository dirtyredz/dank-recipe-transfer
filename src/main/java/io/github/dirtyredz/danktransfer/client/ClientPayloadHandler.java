package io.github.dirtyredz.danktransfer.client;

import io.github.dirtyredz.danktransfer.network.payload.SnapshotPayload;
import io.github.dirtyredz.danktransfer.network.payload.TransferResultPayload;

/**
 * Client receipt of clientbound payloads (main thread). Kept apart from {@code TransferChannel} so
 * the dedicated server never loads client code: it is reached only from clientbound handlers.
 */
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void onSnapshot(SnapshotPayload payload) {
        ClientTransfer.onSnapshot(payload.snapshot());
    }

    public static void onResult(TransferResultPayload payload) {
        ClientTransfer.onResult(payload.message());
    }
}
