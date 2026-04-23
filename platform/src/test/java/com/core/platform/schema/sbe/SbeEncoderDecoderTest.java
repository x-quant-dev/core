package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Decoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;

public class SbeEncoderDecoderTest {

    private SbeSchema schema;
    private SbeEncoder encoder;

    @BeforeEach
    void before_each() {
        schema = new SbeSchema();
        encoder = schema.createEncoder("addOrder");
    }

    @Test
    void wrap_sets_schemaVersion_and_messageType() {
        var buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
        encoder.wrap(buffer);

        then(buffer.getByte(20)).isEqualTo((byte) 2);
        then(buffer.getByte(21)).isEqualTo((byte) 4);
    }

    @Test
    void set_and_get_applicationId_roundTrip() {
        encoder.setApplicationId((short) 5);

        Decoder decoder = encoder.toDecoder();

        then(decoder.getApplicationId()).isEqualTo((short) 5);
    }

    @Test
    void set_and_get_applicationSequenceNumber_roundTrip() {
        encoder.setApplicationSequenceNumber(12345);

        Decoder decoder = encoder.toDecoder();

        then(decoder.getApplicationSequenceNumber()).isEqualTo(12345);
    }

    @Test
    void set_and_get_timestamp_roundTrip() {
        encoder.setTimestamp(9876543210L);

        Decoder decoder = encoder.toDecoder();

        then(decoder.getTimestamp()).isEqualTo(9876543210L);
    }

    @Test
    void set_byte_field_by_name() {
        encoder.set("side", (byte) 1);

        Decoder decoder = encoder.toDecoder();

        then(decoder.get("side")).isEqualTo((byte) 1);
    }

    @Test
    void set_int_field_by_name() {
        encoder.set("orderId", 42);

        Decoder decoder = encoder.toDecoder();

        then(decoder.get("orderId")).isEqualTo(42);
    }

    @Test
    void set_long_field_by_name() {
        encoder.set("qty", 1000L);

        Decoder decoder = encoder.toDecoder();

        then(decoder.get("qty")).isEqualTo(1000L);
    }

    @Test
    void set_invalid_field_name_throws() {
        thenThrownBy(() -> encoder.set("nonexistent", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toDecoder_preserves_all_fields() {
        encoder.setApplicationId((short) 1);
        encoder.setApplicationSequenceNumber(100);
        encoder.setTimestamp(5000L);
        encoder.set("orderId", 42);
        encoder.set("side", (byte) 2);
        encoder.set("qty", 500L);
        encoder.set("instrumentId", 7);
        encoder.set("price", 9999L);

        Decoder decoder = encoder.toDecoder();

        then(decoder.getApplicationId()).isEqualTo((short) 1);
        then(decoder.getApplicationSequenceNumber()).isEqualTo(100);
        then(decoder.getTimestamp()).isEqualTo(5000L);
        then(decoder.get("orderId")).isEqualTo(42);
        then(decoder.get("side")).isEqualTo((byte) 2);
        then(decoder.get("qty")).isEqualTo(500L);
        then(decoder.get("instrumentId")).isEqualTo(7);
        then(decoder.get("price")).isEqualTo(9999L);
    }

    @Test
    void messageName_correct() {
        then(encoder.messageName()).isEqualTo("addOrder");
    }

    @Test
    void messageType_correct() {
        then(encoder.messageType()).isEqualTo((byte) 4);
    }

    @Test
    void fields_returns_all_fields() {
        var fields = encoder.fields();

        // 7 header fields + 5 body fields for addOrder
        then(fields).hasSize(12);
    }

    @Test
    void decoder_reads_v1_header_message_type_and_version() {
        var buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
        buffer.putByte(16, (byte) 1);
        buffer.putByte(17, (byte) 4);

        var schema = new SbeSchema();
        var decoder = schema.createDecoder("addOrder");
        decoder.wrap(buffer, 0, buffer.capacity());

        then(decoder.getSchemaVersion()).isEqualTo((byte) 1);
        then(decoder.getMessageType()).isEqualTo((byte) 4);
        then(decoder.getLeaderEpoch()).isEqualTo(0);
    }

    @Test
    void copy_from_decoder_preserves_data() {
        encoder.setApplicationId((short) 3);
        encoder.set("orderId", 77);
        encoder.set("qty", 200L);
        Decoder decoder = encoder.toDecoder();

        SbeEncoder copy = schema.createEncoder("addOrder");
        var dst = new UnsafeBuffer(ByteBuffer.allocate(128));
        copy.copy(decoder, dst);

        Decoder copyDecoder = copy.toDecoder();
        then(copyDecoder.getApplicationId()).isEqualTo((short) 3);
        then(copyDecoder.get("orderId")).isEqualTo(77);
        then(copyDecoder.get("qty")).isEqualTo(200L);
    }
}
