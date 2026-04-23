package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.messages.Field;
import com.core.infrastructure.messages.MessagePublisher;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * A generic encoder adapter for SBE-encoded messages.
 *
 * <p>Writes the platform's standard 22-byte header and provides field-by-name access for
 * platform components that use the generic {@code Encoder} interface.
 */
public class SbeEncoder implements Encoder {

    private static final int APP_ID_OFFSET = 0;
    private static final int APP_SEQ_NUM_OFFSET = 2;
    private static final int TIMESTAMP_OFFSET = 6;
    private static final int OPTIONAL_FIELDS_OFFSET = 14;
    private static final int LEADER_EPOCH_OFFSET = 16;
    private static final int SCHEMA_VERSION_OFFSET = 20;
    private static final int MESSAGE_TYPE_OFFSET = 21;


    private final String messageName;
    private final byte messageType;
    private final byte schemaVersion;
    private final int fixedLength;
    private final SbeFieldLayout[] fields;
    private final MessagePublisher messagePublisher;

    private MutableDirectBuffer buffer;
    private int offset;
    private int length;

    /**
     * Creates an {@code SbeEncoder} for the specified message type.
     *
     * @param messagePublisher the message publisher, or null if standalone
     * @param messageName the message name
     * @param messageType the message type byte
     * @param schemaVersion the schema version
     * @param fixedLength the total fixed-field message length (header + body fields)
     * @param fields the field layout for this message type
     */
    public SbeEncoder(
            MessagePublisher messagePublisher,
            String messageName,
            byte messageType,
            byte schemaVersion,
            int fixedLength,
            SbeFieldLayout[] fields) {
        this.messagePublisher = messagePublisher;
        this.messageName = messageName;
        this.messageType = messageType;
        this.schemaVersion = schemaVersion;
        this.fixedLength = fixedLength;
        this.fields = fields;
        wrap(new UnsafeBuffer(ByteBuffer.allocate(1450)));
    }

    @Override
    public SbeEncoder wrap(MutableDirectBuffer buffer) {
        return wrap(buffer, 0, buffer.capacity());
    }

    @Override
    public SbeEncoder wrap(MutableDirectBuffer buffer, int offset, int length) {
        this.buffer = buffer;
        this.offset = offset;
        this.length = fixedLength;
        buffer.putShort(offset + OPTIONAL_FIELDS_OFFSET, (short) fixedLength);
        buffer.putByte(offset + SCHEMA_VERSION_OFFSET, schemaVersion);
        buffer.putByte(offset + MESSAGE_TYPE_OFFSET, messageType);
        return this;
    }

    @Override
    public SbeEncoder copy(Decoder decoder, MutableDirectBuffer dst) {
        return copy(decoder, dst, 0, dst.capacity());
    }

    @Override
    public SbeEncoder copy(Decoder decoder, MutableDirectBuffer dst, int offset, int length) {
        this.buffer = dst;
        this.offset = offset;
        this.length = decoder.length();
        dst.putBytes(offset, decoder.buffer(), decoder.offset(), this.length);
        return this;
    }

    @Override
    public MessagePublisher commit() {
        if (messagePublisher != null) {
            messagePublisher.commit(length);
        }
        return messagePublisher;
    }

    @Override
    public SbeEncoder setApplicationId(short value) {
        buffer.putShort(offset + APP_ID_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder setApplicationSequenceNumber(int value) {
        buffer.putInt(offset + APP_SEQ_NUM_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder setTimestamp(long value) {
        buffer.putLong(offset + TIMESTAMP_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder setOptionalFieldsIndex(short value) {
        buffer.putShort(offset + OPTIONAL_FIELDS_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder setLeaderEpoch(int value) {
        buffer.putInt(offset + LEADER_EPOCH_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder setSchemaVersion(byte value) {
        buffer.putByte(offset + SCHEMA_VERSION_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder setMessageType(byte value) {
        buffer.putByte(offset + MESSAGE_TYPE_OFFSET, value);
        return this;
    }

    @Override
    public SbeEncoder set(String name, Object value) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                writeField(f, value);
                return this;
            }
        }
        throw new IllegalArgumentException("invalid field name: " + name);
    }

    @Override
    public SbeEncoder set(String name, byte value) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                buffer.putByte(offset + f.fieldOffset(), value);
                return this;
            }
        }
        throw new IllegalArgumentException("invalid field name: " + name);
    }

    @Override
    public SbeEncoder set(String name, short value) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                buffer.putShort(offset + f.fieldOffset(), value);
                return this;
            }
        }
        throw new IllegalArgumentException("invalid field name: " + name);
    }

    @Override
    public SbeEncoder set(String name, int value) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                buffer.putInt(offset + f.fieldOffset(), value);
                return this;
            }
        }
        throw new IllegalArgumentException("invalid field name: " + name);
    }

    @Override
    public SbeEncoder set(String name, long value) {
        for (var f : fields) {
            if (f.name().equals(name)) {
                buffer.putLong(offset + f.fieldOffset(), value);
                return this;
            }
        }
        throw new IllegalArgumentException("invalid field name: " + name);
    }

    @Override
    public Decoder toDecoder() {
        var decoder = new SbeDecoder(messageName, messageType, fields);
        var wrapper = new UnsafeBuffer();
        wrapper.wrap(buffer, offset, length);
        decoder.wrap(wrapper);
        return decoder;
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
    public int version() {
        return schemaVersion;
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

    private void writeField(SbeFieldLayout f, Object value) {
        switch (f.fieldType()) {
            case BYTE -> buffer.putByte(offset + f.fieldOffset(), ((Number) value).byteValue());
            case SHORT -> buffer.putShort(offset + f.fieldOffset(), ((Number) value).shortValue());
            case INT -> buffer.putInt(offset + f.fieldOffset(), ((Number) value).intValue());
            case LONG -> buffer.putLong(offset + f.fieldOffset(), ((Number) value).longValue());
            default -> throw new IllegalArgumentException("unsupported field type: " + f.fieldType());
        }
    }

    @Override
    public String toString() {
        return toDecoder().toString();
    }
}
