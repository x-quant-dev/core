package com.core.infrastructure.log;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.io.WritableBufferChannel;
import com.core.infrastructure.MemoryUnit;
import com.core.infrastructure.time.TimestampDecimals;
import com.core.infrastructure.time.TimestampFormatter;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A log sink that writes structured JSON to a {@code WritableBufferChannel}.
 *
 * <p>Each log entry is written as a single-line JSON object:
 * <pre>{"timestamp":"2024-01-15T10:30:45.123","level":"INFO","logger":"/vm/Sequencer","message":"hello world"}</pre>
 *
 * <p>This format is suitable for log aggregation systems such as ELK, Splunk, or CloudWatch.
 */
public class JsonLogSink implements LogSink {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int INITIAL_MSG_SIZE = (int) MemoryUnit.KILOBYTES.toBytes(1024);

    private final WritableBufferChannel channel;
    private final MutableDirectBuffer buffer;
    private final MutableDirectBuffer wrapper;
    private final TimestampFormatter timestampFormatter;
    private int headerLength;

    /**
     * Creates a {@code JsonLogSink} with the specified buffer channel, millisecond precision, and system default
     * time zone.
     *
     * @param channel the channel to write log statements to
     */
    public JsonLogSink(WritableBufferChannel channel) {
        this(channel, TimestampDecimals.MILLISECONDS, ZoneId.systemDefault());
    }

    /**
     * Creates a {@code JsonLogSink} with the specified parameters.
     *
     * @param channel the channel to write log statements to
     * @param timestampDecimals the timestamp precision
     * @param zoneId the zone to write timestamps at
     */
    public JsonLogSink(
            WritableBufferChannel channel, TimestampDecimals timestampDecimals, ZoneId zoneId) {
        this.channel = Objects.requireNonNull(channel);
        timestampFormatter = new TimestampFormatter(zoneId, timestampDecimals);
        buffer = BufferUtils.allocateExpandable(INITIAL_MSG_SIZE);
        wrapper = BufferUtils.mutableEmptyBuffer();
    }

    @Override
    public MutableDirectBuffer start(LogLevel logLevel, DirectBuffer logId, long timestamp) {
        var length = 0;

        // {"timestamp":"
        length += buffer.putStringWithoutLengthAscii(length, "{\"timestamp\":\"");

        // write timestamp
        length += timestampFormatter.writeDateTime(buffer, length, timestamp);

        // ","level":"
        length += buffer.putStringWithoutLengthAscii(length, "\",\"level\":\"");

        // write level
        buffer.putBytes(length, logLevel.getNameAsBuffer(), 0, logLevel.getNameAsBuffer().capacity());
        length += logLevel.getNameAsBuffer().capacity();

        // ","logger":"
        length += buffer.putStringWithoutLengthAscii(length, "\",\"logger\":\"");

        // write logger id
        buffer.putBytes(length, logId, 0, logId.capacity());
        length += logId.capacity();

        // ","message":"
        length += buffer.putStringWithoutLengthAscii(length, "\",\"message\":\"");

        wrapper.wrap(buffer, length, buffer.capacity() - length);
        headerLength = length;
        return wrapper;
    }

    @SuppressWarnings("PMD.EmptyCatchBlock")
    @Override
    public void commit(int length) {
        try {
            // length includes the trailing \n appended by Log.Statement.commit(),
            // strip it and close the JSON object with "}\n instead
            var msgLength = length;
            if (msgLength > 0 && buffer.getByte(headerLength + msgLength - 1) == '\n') {
                msgLength--;
            }

            // escape JSON special characters in the message body
            // Ensure buffer capacity for worst-case escape expansion (6 bytes per char)
            var maxNeededCapacity = headerLength + msgLength + (msgLength * 5) + 3;
            if (maxNeededCapacity > buffer.capacity()) {
                buffer.putByte(maxNeededCapacity, (byte) 0);
            }

            var escapedOffset = headerLength + msgLength;
            var escapeStart = escapedOffset;
            for (var i = 0; i < msgLength; i++) {
                var b = buffer.getByte(headerLength + i);
                if (b == '"' || b == '\\') {
                    buffer.putByte(escapedOffset++, (byte) '\\');
                    buffer.putByte(escapedOffset++, b);
                } else if (b == '\n') {
                    buffer.putByte(escapedOffset++, (byte) '\\');
                    buffer.putByte(escapedOffset++, (byte) 'n');
                } else if (b == '\r') {
                    buffer.putByte(escapedOffset++, (byte) '\\');
                    buffer.putByte(escapedOffset++, (byte) 'r');
                } else if (b == '\t') {
                    buffer.putByte(escapedOffset++, (byte) '\\');
                    buffer.putByte(escapedOffset++, (byte) 't');
                } else if (b >= 0 && b < 0x20) {
                    buffer.putByte(escapedOffset++, (byte) '\\');
                    buffer.putByte(escapedOffset++, (byte) 'u');
                    buffer.putByte(escapedOffset++, (byte) '0');
                    buffer.putByte(escapedOffset++, (byte) '0');
                    buffer.putByte(escapedOffset++, (byte) HEX_DIGITS[(b >> 4) & 0x0F]);
                    buffer.putByte(escapedOffset++, (byte) HEX_DIGITS[b & 0x0F]);
                } else {
                    buffer.putByte(escapedOffset++, b);
                }
            }
            var escapedLength = escapedOffset - escapeStart;

            // move escaped message into position (overwrite original)
            buffer.putBytes(headerLength, buffer, escapeStart, escapedLength);

            var totalLength = headerLength + escapedLength;
            totalLength += buffer.putStringWithoutLengthAscii(totalLength, "\"}\n");

            channel.write(buffer, 0, totalLength);
        } catch (IOException e) {
            // cannot do anything when the log fails to write
        }
    }

    @SuppressWarnings("PMD.EmptyCatchBlock")
    @Override
    public void dump(DirectBuffer buffer, int index, int length) {
        try {
            var position = index;
            var capacity = index + length;
            while (position < capacity) {
                position += channel.write(buffer, position, capacity - position);
            }
        } catch (IOException e) {
            // cannot do anything when the log fails to write
        }
    }
}
