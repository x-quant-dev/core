package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Dispatcher;
import org.agrona.DirectBuffer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A dispatcher for SBE-encoded messages.
 *
 * <p>Dispatches messages based on the message type byte at offset 21 in the platform's standard header.
 * Supports before/after dispatch listeners and per-message-type listeners registered by name.
 */
@SuppressWarnings("unchecked")
public class SbeDispatcher implements Dispatcher {

    private static final int TIMESTAMP_OFFSET = 6;
    private static final int V1_MESSAGE_TYPE_OFFSET = 17;
    private static final int V2_MESSAGE_TYPE_OFFSET = 21;

    private final Map<Byte, SbeDecoder> decoders;
    private final Map<Byte, Consumer<Decoder>[]> listeners;
    private final Map<String, Byte> nameToType;
    private final byte minCompatibleVersion;
    private Consumer<Decoder>[] before = new Consumer[0];
    private Consumer<Decoder>[] after = new Consumer[0];
    private long timestamp;
    private long rejectedVersionCount;
    private boolean reportedRejectedVersion;

    /**
     * Creates an empty {@code SbeDispatcher}.
     *
     * @param decoders map of message type to decoder
     * @param nameToType map of message name to type
     * @param minCompatibleVersion the minimum compatible schema version
     */
    public SbeDispatcher(Map<Byte, SbeDecoder> decoders, Map<String, Byte> nameToType, byte minCompatibleVersion) {
        this.decoders = decoders;
        this.nameToType = nameToType;
        this.minCompatibleVersion = minCompatibleVersion;
        this.listeners = new HashMap<>();
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public void dispatch(DirectBuffer buffer, int offset, int length) {
        timestamp = buffer.getLong(offset + TIMESTAMP_OFFSET);
        var messageType = resolveMessageType(buffer, offset, length);

        var decoder = decoders.get(messageType);
        if (decoder == null) {
            return;
        }
        decoder.wrap(buffer, offset, length);
        if (decoder.getSchemaVersion() < minCompatibleVersion) {
            rejectedVersionCount++;
            return;
        }

        for (var listener : before) {
            listener.accept(decoder);
        }

        var msgListeners = listeners.get(messageType);
        if (msgListeners != null) {
            for (var listener : msgListeners) {
                listener.accept(decoder);
            }
        }

        for (var listener : after) {
            listener.accept(decoder);
        }
    }

    @Override
    public Decoder getDecoder(DirectBuffer buffer, int offset, int length) {
        var messageType = resolveMessageType(buffer, offset, length);
        var decoder = decoders.get(messageType);
        if (decoder == null) {
            return null;
        }
        decoder.wrap(buffer, offset, length);
        if (decoder.getSchemaVersion() < minCompatibleVersion) {
            rejectedVersionCount++;
            return null;
        }
        return decoder;
    }

    public long getRejectedVersionCount() {
        return rejectedVersionCount;
    }

    public boolean isReportedRejectedVersion() {
        return reportedRejectedVersion;
    }

    public void markRejectedVersionReported() {
        reportedRejectedVersion = true;
    }

    public void resetRejectedVersionReported() {
        reportedRejectedVersion = false;
    }

    private byte resolveMessageType(DirectBuffer buffer, int offset, int length) {
        if (length < V1_MESSAGE_TYPE_OFFSET + 1) {
            return 0;
        }
        byte v2Type = 0;
        byte v1Type = buffer.getByte(offset + V1_MESSAGE_TYPE_OFFSET);
        if (length >= V2_MESSAGE_TYPE_OFFSET + 1) {
            v2Type = buffer.getByte(offset + V2_MESSAGE_TYPE_OFFSET);
        }
        if (decoders.containsKey(v2Type)) {
            return v2Type;
        }
        if (decoders.containsKey(v1Type)) {
            return v1Type;
        }
        return v2Type != 0 ? v2Type : v1Type;
    }

    @Override
    public SbeDispatcher addListenerAfterDispatch(Consumer<Decoder> listener) {
        after = Arrays.copyOf(after, after.length + 1);
        after[after.length - 1] = listener;
        return this;
    }

    @Override
    public SbeDispatcher addListenerBeforeDispatch(Consumer<Decoder> listener) {
        before = Arrays.copyOf(before, before.length + 1);
        before[before.length - 1] = listener;
        return this;
    }

    @Override
    public <T extends Decoder> SbeDispatcher addListener(String messageName, Consumer<T> listener) {
        var type = nameToType.get(messageName);
        if (type == null) {
            throw new IllegalArgumentException("invalid message name: " + messageName);
        }
        var existing = listeners.getOrDefault(type, new Consumer[0]);
        var updated = Arrays.copyOf(existing, existing.length + 1);
        updated[updated.length - 1] = (Consumer<Decoder>) listener;
        listeners.put(type, updated);
        return this;
    }
}
