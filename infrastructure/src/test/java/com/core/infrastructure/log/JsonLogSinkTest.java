package com.core.infrastructure.log;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.io.WritableBufferChannel;
import com.core.infrastructure.time.TimestampDecimals;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;

import static org.assertj.core.api.BDDAssertions.then;

public class JsonLogSinkTest {

    private TestWritableChannel channel;
    private JsonLogSink sink;
    private DirectBuffer logId;

    @BeforeEach
    void before_each() {
        channel = new TestWritableChannel();
        sink = new JsonLogSink(channel, TimestampDecimals.MILLISECONDS, ZoneOffset.UTC);
        logId = BufferUtils.fromAsciiString("/test/Logger");
    }

    @Test
    void escapes_quotes_and_backslashes() {
        var wrapper = sink.start(LogLevel.INFO, logId, 0);
        var msg = "value=\"test\\path\"";
        wrapper.putStringWithoutLengthAscii(0, msg + "\n");
        sink.commit(msg.length() + 1);

        var output = channel.getOutput();
        then(output).contains("value=\\\"test\\\\path\\\"");
        then(output).endsWith("\"}\n");
    }

    @Test
    void escapes_newlines_tabs_carriage_returns() {
        var wrapper = sink.start(LogLevel.WARN, logId, 0);
        var msg = "line1\nline2\ttab\rreturn";
        wrapper.putStringWithoutLengthAscii(0, msg + "\n");
        sink.commit(msg.length() + 1);

        var output = channel.getOutput();
        then(output).contains("line1\\nline2\\ttab\\rreturn");
    }

    @Test
    void escapes_control_chars_as_unicode() {
        var wrapper = sink.start(LogLevel.ERROR, logId, 0);
        wrapper.putByte(0, (byte) 0x00);
        wrapper.putByte(1, (byte) 0x07);
        wrapper.putByte(2, (byte) 0x0C);
        wrapper.putByte(3, (byte) 'A');
        wrapper.putByte(4, (byte) '\n');
        sink.commit(5);

        var output = channel.getOutput();
        then(output).contains("\\u0000");
        then(output).contains("\\u0007");
        then(output).contains("\\u000c");
        then(output).contains("A");
        then(output).endsWith("\"}\n");
    }

    @Test
    void handles_all_control_chars_0x00_to_0x1F() {
        var wrapper = sink.start(LogLevel.INFO, logId, 0);
        for (int i = 0; i < 32; i++) {
            wrapper.putByte(i, (byte) i);
        }
        wrapper.putByte(32, (byte) '\n');
        sink.commit(33);

        var output = channel.getOutput();
        then(output).contains("\\t");
        then(output).contains("\\n");
        then(output).contains("\\r");
        then(output).contains("\\u0000");
        then(output).contains("\\u0001");
        then(output).contains("\\u001f");
        then(output).doesNotContain("\\u0009");
        then(output).doesNotContain("\\u000a");
        then(output).doesNotContain("\\u000d");
        then(output).endsWith("\"}\n");
    }

    @Test
    void produces_valid_json_structure() {
        var wrapper = sink.start(LogLevel.INFO, logId, 0);
        var msg = "hello world";
        wrapper.putStringWithoutLengthAscii(0, msg + "\n");
        sink.commit(msg.length() + 1);

        var output = channel.getOutput();
        then(output).startsWith("{\"timestamp\":\"");
        then(output).contains("\"level\":\"INFO\"");
        then(output).contains("\"logger\":\"/test/Logger\"");
        then(output).contains("\"message\":\"hello world\"");
        then(output).endsWith("\"}\n");
    }

    @Test
    void handles_empty_message() {
        var wrapper = sink.start(LogLevel.INFO, logId, 0);
        wrapper.putByte(0, (byte) '\n');
        sink.commit(1);

        var output = channel.getOutput();
        then(output).contains("\"message\":\"\"");
        then(output).endsWith("\"}\n");
    }

    private static class TestWritableChannel implements WritableBufferChannel {
        private final MutableDirectBuffer captured = BufferUtils.allocateExpandable(4096);
        private int position = 0;

        @Override
        public int write(DirectBuffer buffer, int index, int length) {
            captured.putBytes(position, buffer, index, length);
            position += length;
            return length;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            return 0;
        }

        @Override
        public long write(ByteBuffer[] srcs) {
            return 0;
        }

        @Override
        public int write(ByteBuffer src) {
            return 0;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }

        @Override
        public void setWriteListener(Runnable listener) {
        }

        String getOutput() {
            var bytes = new byte[position];
            captured.getBytes(0, bytes);
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }
}
