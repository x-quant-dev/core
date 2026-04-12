package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Decoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;

public class SbeDispatcherTest {

    private SbeSchema schema;
    private SbeDispatcher dispatcher;

    @BeforeEach
    void before_each() {
        schema = new SbeSchema();
        dispatcher = schema.createDispatcher();
    }

    @Test
    void dispatch_calls_registered_listener() {
        var received = new AtomicBoolean(false);
        dispatcher.addListener("addOrder", (Decoder d) -> received.set(true));

        var encoder = encodeAddOrder();
        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        then(received.get()).isTrue();
    }

    @Test
    void dispatch_unknown_type_is_ignored() {
        var buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
        buffer.putByte(17, (byte) 99);

        dispatcher.dispatch(buffer, 0, 64);
    }

    @Test
    void dispatch_before_listener_fires_before_message_listener() {
        var order = new java.util.ArrayList<String>();
        dispatcher.addListenerBeforeDispatch(d -> order.add("before"));
        dispatcher.addListener("addOrder", (Decoder d) -> order.add("message"));

        var encoder = encodeAddOrder();
        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        then(order).containsExactly("before", "message");
    }

    @Test
    void dispatch_after_listener_fires_after_message_listener() {
        var order = new java.util.ArrayList<String>();
        dispatcher.addListener("addOrder", (Decoder d) -> order.add("message"));
        dispatcher.addListenerAfterDispatch(d -> order.add("after"));

        var encoder = encodeAddOrder();
        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        then(order).containsExactly("message", "after");
    }

    @Test
    void dispatch_sets_timestamp() {
        var encoder = encodeAddOrder();
        encoder.setTimestamp(123456789L);

        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        then(dispatcher.getTimestamp()).isEqualTo(123456789L);
    }

    @Test
    void addListener_invalid_name_throws() {
        thenThrownBy(() -> dispatcher.addListener("fake", (Decoder d) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getDecoder_returns_decoder_for_known_type() {
        var encoder = encodeAddOrder();

        var decoder = dispatcher.getDecoder(encoder.buffer(), encoder.offset(), encoder.length());

        then(decoder).isNotNull();
    }

    @Test
    void getDecoder_returns_null_for_unknown_type() {
        var buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
        buffer.putByte(17, (byte) 99);

        var decoder = dispatcher.getDecoder(buffer, 0, 64);

        then(decoder).isNull();
    }

    @Test
    void dispatch_prefers_v1_type_when_present() {
        var buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
        buffer.putByte(17, (byte) 4);

        var received = new AtomicBoolean(false);
        dispatcher.addListener("addOrder", (Decoder d) -> received.set(true));

        dispatcher.dispatch(buffer, 0, 64);

        then(received.get()).isFalse();
        then(dispatcher.getRejectedVersionCount()).isEqualTo(1);
        then(dispatcher.isReportedRejectedVersion()).isFalse();
    }

    @Test
    void dispatch_accepts_v1_type_when_min_compat_is_one() {
        schema = new SbeSchema((byte) 1);
        dispatcher = schema.createDispatcher();

        var buffer = new UnsafeBuffer(ByteBuffer.allocate(64));
        buffer.putByte(16, (byte) 1);
        buffer.putByte(17, (byte) 4);

        var received = new AtomicBoolean(false);
        dispatcher.addListener("addOrder", (Decoder d) -> received.set(true));

        dispatcher.dispatch(buffer, 0, 64);

        then(received.get()).isTrue();
    }

    private SbeEncoder encodeAddOrder() {
        SbeEncoder encoder = schema.createEncoder("addOrder");
        encoder.set("orderId", 1);
        encoder.set("side", (byte) 1);
        encoder.set("qty", 100L);
        encoder.set("instrumentId", 10);
        encoder.set("price", 5000L);
        return encoder;
    }
}
