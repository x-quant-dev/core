package com.core.clob.applications;

import com.core.clob.schema.DeleteEntryDecoder;
import com.core.clob.schema.PutEntryDecoder;
import com.core.clob.schema.RejectEntryDecoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.platform.bus.BusClient;
import org.agrona.DirectBuffer;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

import java.util.Objects;

/**
 * Client-side key/value store that materializes state from events.
 *
 * <p>Listens for {@code PutEntry}, {@code DeleteEntry}, and {@code RejectEntry} events
 * and maintains an in-memory key-to-value map.
 */
public class KvStoreClient implements Encodable {

    private final UnifiedMap<DirectBuffer, DirectBuffer> store;
    private int rejectCount;

    /**
     * Creates a {@code KvStoreClient} and subscribes to key/value events.
     *
     * @param busClient the bus client
     */
    public KvStoreClient(BusClient<?, ?> busClient) {
        Objects.requireNonNull(busClient, "busClient is null");

        store = new UnifiedMap<>();

        var dispatcher = busClient.getDispatcher();
        dispatcher.addListener("putEntry", this::onPutEntry);
        dispatcher.addListener("deleteEntry", this::onDeleteEntry);
        dispatcher.addListener("rejectEntry", this::onRejectEntry);
    }

    private void onPutEntry(PutEntryDecoder decoder) {
        var key = decoder.getKey();
        var value = decoder.getValue();
        store.put(BufferUtils.copy(key), BufferUtils.copy(value));
    }

    private void onDeleteEntry(DeleteEntryDecoder decoder) {
        var key = decoder.getKey();
        store.remove(key);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void onRejectEntry(RejectEntryDecoder decoder) {
        rejectCount++;
    }

    int size() {
        return store.size();
    }

    int getRejectCount() {
        return rejectCount;
    }

    /**
     * Returns the value associated with the specified key, or "NOT_FOUND".
     *
     * @param key the key to look up
     * @return the value as a string
     */
    @Command(readOnly = true)
    public String get(DirectBuffer key) {
        var value = store.get(key);
        return value == null ? "NOT_FOUND" : BufferUtils.toAsciiString(value);
    }

    /**
     * Encodes the store status as a JSON-like object.
     *
     * @param encoder the object encoder
     */
    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("entries").number(store.size())
                .string("rejects").number(rejectCount)
                .closeMap();
    }
}
