package com.core.kv.applications.sequencer;

import com.core.kv.schema.DeleteEntryDecoder;
import com.core.kv.schema.KvDispatcher;
import com.core.kv.schema.KvProvider;
import com.core.kv.schema.PutEntryDecoder;
import com.core.kv.schema.RejectEntryEncoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.platform.bus.BusServer;
import org.agrona.DirectBuffer;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;

import java.util.Objects;

/**
 * Command handlers for the key/value store messages.
 *
 * <p>Validates {@code PutEntry} and {@code DeleteEntry} commands.
 * Valid commands are copied as events.
 * Invalid commands produce a {@code RejectEntry} event.
 */
public class KvCommandHandlers implements Encodable {

    private final BusServer<KvDispatcher, KvProvider> busServer;
    private final RejectEntryEncoder rejectEntryEncoder;
    private final UnifiedSet<DirectBuffer> keys;

    /**
     * Creates a {@code KvCommandHandlers} and subscribes to
     * {@code PutEntry} and {@code DeleteEntry} commands.
     *
     * @param busServer the sequencer bus
     */
    public KvCommandHandlers(BusServer<KvDispatcher, KvProvider> busServer) {
        this.busServer = Objects.requireNonNull(busServer, "busServer is null");

        rejectEntryEncoder = new RejectEntryEncoder();
        keys = new UnifiedSet<>();

        var dispatcher = busServer.getDispatcher();
        dispatcher.addPutEntryListener(this::onPutEntry);
        dispatcher.addDeleteEntryListener(this::onDeleteEntry);
    }

    private void onPutEntry(PutEntryDecoder decoder) {
        var key = decoder.getKey();
        if (key == null || key.capacity() == 0) {
            sendReject(decoder.getApplicationId(),
                    decoder.getApplicationSequenceNumber(), key, "empty key");
            return;
        }

        BusServer.copy(busServer, decoder);

        if (!keys.contains(key)) {
            keys.add(BufferUtils.copy(key));
        }
    }

    private void onDeleteEntry(DeleteEntryDecoder decoder) {
        var key = decoder.getKey();
        if (key == null || key.capacity() == 0) {
            sendReject(decoder.getApplicationId(),
                    decoder.getApplicationSequenceNumber(), key, "empty key");
            return;
        }

        if (!keys.contains(key)) {
            sendReject(decoder.getApplicationId(),
                    decoder.getApplicationSequenceNumber(), key, "key not found");
            return;
        }

        BusServer.copy(busServer, decoder);
        keys.remove(key);
    }

    private void sendReject(
            short applicationId, int applicationSequenceNumber,
            DirectBuffer key, String reason) {
        rejectEntryEncoder.wrap(busServer.acquire())
                .setApplicationId(applicationId)
                .setApplicationSequenceNumber(applicationSequenceNumber);
        if (key != null && key.capacity() > 0) {
            rejectEntryEncoder.setKey(key);
        }
        rejectEntryEncoder.setReason(reason);
        BusServer.commit(busServer, rejectEntryEncoder);
    }

    int getKeyCount() {
        return keys.size();
    }

    boolean containsKey(DirectBuffer key) {
        return keys.contains(key);
    }

    /**
     * Prints the number of keys tracked by the sequencer.
     *
     * @param encoder the object encoder
     */
    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("keys").number(keys.size())
                .closeMap();
    }
}
