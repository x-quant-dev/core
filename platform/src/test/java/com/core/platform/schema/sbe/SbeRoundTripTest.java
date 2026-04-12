package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Decoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.BDDAssertions.then;

public class SbeRoundTripTest {

    private SbeSchema schema;
    private SbeDispatcher dispatcher;

    @BeforeEach
    void before_each() {
        schema = new SbeSchema();
        dispatcher = schema.createDispatcher();
    }

    @Test
    void addOrder_encode_dispatch_decode_all_fields() {
        var captured = new AtomicReference<Decoder>();
        dispatcher.addListener("addOrder", (Decoder d) -> captured.set(d));

        SbeEncoder encoder = schema.createEncoder("addOrder");
        encoder.setApplicationId((short) 1);
        encoder.setApplicationSequenceNumber(100);
        encoder.setTimestamp(5000L);
        encoder.set("orderId", 42);
        encoder.set("side", (byte) 2);
        encoder.set("qty", 1000L);
        encoder.set("instrumentId", 7);
        encoder.set("price", 9999L);

        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        var decoder = captured.get();
        then(decoder).isNotNull();
        then(decoder.getApplicationId()).isEqualTo((short) 1);
        then(decoder.getApplicationSequenceNumber()).isEqualTo(100);
        then(decoder.getTimestamp()).isEqualTo(5000L);
        then(decoder.get("orderId")).isEqualTo(42);
        then(decoder.get("side")).isEqualTo((byte) 2);
        then(decoder.get("qty")).isEqualTo(1000L);
        then(decoder.get("instrumentId")).isEqualTo(7);
        then(decoder.get("price")).isEqualTo(9999L);
    }

    @Test
    void heartbeat_roundTrip() {
        var captured = new AtomicReference<Decoder>();
        dispatcher.addListener("heartbeat", (Decoder d) -> captured.set(d));

        SbeEncoder encoder = schema.createEncoder("heartbeat");
        encoder.setApplicationId((short) 3);
        encoder.setTimestamp(7777L);

        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        var decoder = captured.get();
        then(decoder).isNotNull();
        then(decoder.getApplicationId()).isEqualTo((short) 3);
        then(decoder.getTimestamp()).isEqualTo(7777L);
        then(decoder.messageName()).isEqualTo("heartbeat");
    }

    @Test
    void snapshotComplete_roundTrip() {
        var captured = new AtomicReference<Decoder>();
        dispatcher.addListener("snapshotComplete", (Decoder d) -> captured.set(d));

        SbeEncoder encoder = schema.createEncoder("snapshotComplete");
        encoder.setApplicationId((short) 2);
        encoder.setTimestamp(10000L);
        encoder.set("snapshotId", 111L);
        encoder.set("checkpointSeqNum", 222L);
        encoder.set("nodeCount", (short) 3);
        encoder.set("validity", (byte) 1);
        encoder.set("archiveRecordingId", 333L);
        encoder.set("archivePosition", 444L);

        dispatcher.dispatch(encoder.buffer(), encoder.offset(), encoder.length());

        var decoder = captured.get();
        then(decoder).isNotNull();
        then(decoder.get("snapshotId")).isEqualTo(111L);
        then(decoder.get("checkpointSeqNum")).isEqualTo(222L);
        then(decoder.get("nodeCount")).isEqualTo((short) 3);
        then(decoder.get("validity")).isEqualTo((byte) 1);
        then(decoder.get("archiveRecordingId")).isEqualTo(333L);
        then(decoder.get("archivePosition")).isEqualTo(444L);
    }
}
