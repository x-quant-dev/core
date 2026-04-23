package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Field;
import org.agrona.DirectBuffer;

/**
 * Describes the layout of a single field within an SBE message.
 *
 * <p>Provides the field name, byte offset from the start of the message, primitive type,
 * and methods to read the value from a buffer.
 */
public record SbeFieldLayout(
        String name,
        int fieldOffset,
        SbeFieldType fieldType,
        boolean header) {

    /**
     * Reads the field value as a boxed object from the buffer.
     *
     * @param buffer the buffer
     * @param msgOffset the message offset in the buffer
     * @return the field value
     */
    public Object readValue(DirectBuffer buffer, int msgOffset) {
        return switch (fieldType) {
            case BYTE -> buffer.getByte(msgOffset + fieldOffset);
            case SHORT -> buffer.getShort(msgOffset + fieldOffset);
            case INT -> buffer.getInt(msgOffset + fieldOffset);
            case LONG -> buffer.getLong(msgOffset + fieldOffset);
        };
    }

    /**
     * Reads the field value as a long from the buffer.
     *
     * @param buffer the buffer
     * @param msgOffset the message offset in the buffer
     * @return the field value as a long
     */
    public long readLong(DirectBuffer buffer, int msgOffset) {
        return switch (fieldType) {
            case BYTE -> buffer.getByte(msgOffset + fieldOffset);
            case SHORT -> buffer.getShort(msgOffset + fieldOffset);
            case INT -> buffer.getInt(msgOffset + fieldOffset);
            case LONG -> buffer.getLong(msgOffset + fieldOffset);
        };
    }

    /**
     * Converts to the platform's {@code Field} descriptor.
     *
     * @return the field descriptor
     */
    public Field toField() {
        var type = switch (fieldType) {
            case BYTE -> byte.class;
            case SHORT -> short.class;
            case INT -> int.class;
            case LONG -> long.class;
        };
        return new Field(name, type, true, header, null, 0, false, false, null);
    }

    /**
     * Primitive field types supported by the SBE adapter.
     */
    public enum SbeFieldType {
        BYTE, SHORT, INT, LONG
    }
}
