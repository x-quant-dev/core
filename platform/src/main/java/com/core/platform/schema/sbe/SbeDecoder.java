package com.core.platform.schema.sbe;

import com.core.infrastructure.buffer.UnsafeBuffer;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Field;
import org.agrona.DirectBuffer;

/**
 * A generic decoder adapter for SBE-encoded messages.
 *
 * <p>Reads the platform's standard 22-byte header and provides field-by-name access for
 * platform components that use the generic {@code Decoder} interface.
 *
 * <p>This decoder reads fixed-position fields from the buffer at known offsets,
 * providing the same interface as the Velocity-generated decoders without requiring
 * per-message-type generated classes on the platform side.
 */
public class SbeDecoder implements Decoder, Encodable {

    private static final int APP_ID_OFFSET = 0;
    private static final int APP_SEQ_NUM_OFFSET = 2;
    private static final int TIMESTAMP_OFFSET = 6;
    private static final int OPTIONAL_FIELDS_OFFSET = 14;

    private static final int V1_HEADER_LENGTH = 18;
    private static final int V2_HEADER_LENGTH = 22;
    private static final int V1_SCHEMA_VERSION_OFFSET = 16;
    private static final int V2_SCHEMA_VERSION_OFFSET = 20;
    private static final int V1_MESSAGE_TYPE_OFFSET = 17;
    private static final int V2_MESSAGE_TYPE_OFFSET = 21;
    private static final int V2_LEADER_EPOCH_OFFSET = 16;


    private final String messageName;
    private final byte messageType;
    private final SbeFieldLayout[] fields;
    private DirectBuffer buffer;
    private int offset;
    private int length;
    private int schemaVersionOffset = V2_SCHEMA_VERSION_OFFSET;
    private int messageTypeOffset = V2_MESSAGE_TYPE_OFFSET;
    private int leaderEpochOffset = V2_LEADER_EPOCH_OFFSET;

    /**
     * Creates an {@code SbeDecoder} for the specified message type.
     *
     * @param messageName the message name
     * @param messageType the message type byte
     * @param fields the field layout for this message type
     */
    public SbeDecoder(String messageName, byte messageType, SbeFieldLayout[] fields) {
        this.messageName = messageName;
        this.messageType = messageType;
        this.fields = fields;
    }

    @Override
    public SbeDecoder wrap(DirectBuffer buffer) {
        return wrap(buffer, 0, buffer.capacity());
    }

    @Override
    public SbeDecoder wrap(DirectBuffer buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = length;
        configureHeaderOffsets(buffer, offset, length);
        return this;
    }

    @Override
    public short getApplicationId() {
        return buffer.getShort(offset + APP_ID_OFFSET);
    }

    @Override
    public int getApplicationSequenceNumber() {
        return buffer.getInt(offset + APP_SEQ_NUM_OFFSET);
    }

    @Override
    public long getTimestamp() {
        return buffer.getLong(offset + TIMESTAMP_OFFSET);
    }

    @Override
    public short getOptionalFieldsIndex() {
        return buffer.getShort(offset + OPTIONAL_FIELDS_OFFSET);
    }

    @Override
    public int getLeaderEpoch() {
        if (leaderEpochOffset < 0) {
            return 0;
        }
        return buffer.getInt(offset + leaderEpochOffset);
    }

    @Override
    public byte getSchemaVersion() {
        return buffer.getByte(offset + schemaVersionOffset);
    }

    @Override
    public byte getMessageType() {
        return buffer.getByte(offset + messageTypeOffset);
    }

    @Override
    public DirectBuffer buffer() {
        return buffer;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public int version() {
        return getSchemaVersion();
    }

    @Override
    public Field[] fields() {
        var result = new Field[fields.length];
        for (var i = 0; i < fields.length; i++) {
            result[i] = fields[i].toField();
        }
        return result;
    }

    @Override
    public Field field(String name) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                return f.toField();
            }
        }
        throw new IllegalArgumentException("unknown field name: " + name);
    }

    @Override
    public String messageName() {
        return messageName;
    }

    @Override
    public byte messageType() {
        return messageType;
    }

    @Override
    public String entityName() {
        return null;
    }

    @Override
    public String baseEntityName() {
        return null;
    }

    @Override
    public Object get(String name) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                return f.readValue(buffer, offset);
            }
        }
        throw new IllegalArgumentException("unknown field name: " + name);
    }

    @Override
    public long integerValue(String name) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                return f.readLong(buffer, offset);
            }
        }
        throw new IllegalArgumentException("unknown field name: " + name);
    }

    @Override
    public double realValue(String name) {
        throw new IllegalArgumentException("no real fields");
    }

    @Override
    public boolean isPresent(String name) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("messageName").string(messageName)
                .string("messageType").number(messageType)
                .closeMap();
    }

    private void configureHeaderOffsets(DirectBuffer buffer, int offset, int length) {
        boolean v2Candidate = length >= V2_HEADER_LENGTH
                && buffer.getByte(offset + V2_MESSAGE_TYPE_OFFSET) == messageType;
        boolean v1Candidate = length >= V1_HEADER_LENGTH
                && buffer.getByte(offset + V1_MESSAGE_TYPE_OFFSET) == messageType;

        if (v2Candidate && !v1Candidate) {
            useV2Header();
            return;
        }
        if (v1Candidate && !v2Candidate) {
            useV1Header();
            return;
        }
        if (v1Candidate && v2Candidate) {
            useV1Header();
            return;
        }
        if (length >= V2_HEADER_LENGTH) {
            useV2Header();
        } else {
            useV1Header();
        }
    }

    private void useV1Header() {
        schemaVersionOffset = V1_SCHEMA_VERSION_OFFSET;
        messageTypeOffset = V1_MESSAGE_TYPE_OFFSET;
        leaderEpochOffset = -1;
    }

    private void useV2Header() {
        schemaVersionOffset = V2_SCHEMA_VERSION_OFFSET;
        messageTypeOffset = V2_MESSAGE_TYPE_OFFSET;
        leaderEpochOffset = V2_LEADER_EPOCH_OFFSET;
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
