package com.core.platform.schema.sbe;

import com.core.platform.schema.sbe.SbeFieldLayout.SbeFieldType;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.BDDAssertions.then;

public class SbeFieldLayoutTest {

    private UnsafeBuffer buffer;

    @BeforeEach
    void before_each() {
        buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
    }

    @Test
    void readValue_byte() {
        buffer.putByte(0, (byte) 42);
        var layout = new SbeFieldLayout("testByte", 0, SbeFieldType.BYTE, false);

        then(layout.readValue(buffer, 0)).isEqualTo((byte) 42);
    }

    @Test
    void readValue_short() {
        buffer.putShort(0, (short) 1000);
        var layout = new SbeFieldLayout("testShort", 0, SbeFieldType.SHORT, false);

        then(layout.readValue(buffer, 0)).isEqualTo((short) 1000);
    }

    @Test
    void readValue_int() {
        buffer.putInt(0, 123456);
        var layout = new SbeFieldLayout("testInt", 0, SbeFieldType.INT, false);

        then(layout.readValue(buffer, 0)).isEqualTo(123456);
    }

    @Test
    void readValue_long() {
        buffer.putLong(0, 999999999L);
        var layout = new SbeFieldLayout("testLong", 0, SbeFieldType.LONG, false);

        then(layout.readValue(buffer, 0)).isEqualTo(999999999L);
    }

    @Test
    void readLong_byte_widens() {
        buffer.putByte(0, (byte) 7);
        var layout = new SbeFieldLayout("testByte", 0, SbeFieldType.BYTE, false);

        then(layout.readLong(buffer, 0)).isEqualTo(7L);
    }

    @Test
    void readLong_short_widens() {
        buffer.putShort(0, (short) 300);
        var layout = new SbeFieldLayout("testShort", 0, SbeFieldType.SHORT, false);

        then(layout.readLong(buffer, 0)).isEqualTo(300L);
    }

    @Test
    void toField_returns_correct_type_for_int() {
        var layout = new SbeFieldLayout("testInt", 0, SbeFieldType.INT, false);

        then(layout.toField().getType()).isEqualTo(int.class);
    }

    @Test
    void toField_header_flag_preserved() {
        var layout = new SbeFieldLayout("headerField", 0, SbeFieldType.SHORT, true);

        then(layout.toField().isHeader()).isTrue();
    }
}
