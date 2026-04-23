package com.core.kv.applications.sequencer;

import com.core.kv.schema.DeleteEntryDecoder;
import com.core.kv.schema.DeleteEntryEncoder;
import com.core.kv.schema.KvDispatcher;
import com.core.kv.schema.KvProvider;
import com.core.kv.schema.KvSchema;
import com.core.kv.schema.PutEntryDecoder;
import com.core.kv.schema.PutEntryEncoder;
import com.core.kv.schema.RejectEntryDecoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusServer;
import com.core.platform.bus.TestMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * Unit tests for {@link KvCommandHandlers}.
 *
 * <p>Tests dispatch {@code PutEntry}/{@code DeleteEntry} decoders directly through the
 * bus-server dispatcher, bypassing the {@code Sequencer}'s appId bookkeeping.
 * The handler validates messages, copies valid ones as events, and emits
 * {@code RejectEntry} events for invalid ones.
 */
public class KvCommandHandlersTest {

    private static final short APP_ID = 2;

    private TestBusServer<KvDispatcher, KvProvider> busServer;
    private TestMessagePublisher eventPublisher;
    private KvCommandHandlers handler;
    private int appSeqNum;

    @BeforeEach
    void before_each() {
        var time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);

        busServer = new TestBusServer<>(time, new KvSchema(), activatorFactory);
        eventPublisher = busServer.getEventPublisher();
        handler = new KvCommandHandlers(busServer);
        appSeqNum = 0;
    }

    @Nested
    class PutEntryTests {

        @Test
        void putEntry_emits_putEntry_event() {
            put("hello", "world");

            PutEntryDecoder decoder = eventPublisher.remove();
            then(decoder.getApplicationId()).isEqualTo(APP_ID);
            then(BufferUtils.toAsciiString(decoder.getKey())).isEqualTo("hello");
            then(BufferUtils.toAsciiString(decoder.getValue())).isEqualTo("world");
        }

        @Test
        void putEntry_tracks_key_in_sequencer_state() {
            put("hello", "world");

            then(handler.getKeyCount()).isEqualTo(1);
            then(handler.containsKey(BufferUtils.fromAsciiString("hello"))).isTrue();
        }

        @Test
        void putEntry_with_empty_key_is_rejected() {
            put(null, "value");

            RejectEntryDecoder decoder = eventPublisher.remove();
            then(decoder.reasonAsString()).isEqualTo("empty key");
            then(handler.getKeyCount()).isEqualTo(0);
        }

        @Test
        void repeated_put_does_not_duplicate_key_tracking() {
            put("hello", "world");
            put("hello", "there");

            then(handler.getKeyCount()).isEqualTo(1);
        }

        @Test
        void multiple_puts_track_each_key() {
            put("a", "1");
            put("b", "2");
            put("c", "3");

            then(handler.getKeyCount()).isEqualTo(3);
        }
    }

    @Nested
    class DeleteEntryTests {

        @BeforeEach
        void before_each() {
            put("hello", "world");
            put("foo", "bar");
            eventPublisher.removeAll();
        }

        @Test
        void deleteEntry_emits_deleteEntry_event_and_untracks_key() {
            delete("hello");

            DeleteEntryDecoder decoder = eventPublisher.remove();
            then(BufferUtils.toAsciiString(decoder.getKey())).isEqualTo("hello");
            then(handler.getKeyCount()).isEqualTo(1);
            then(handler.containsKey(BufferUtils.fromAsciiString("hello"))).isFalse();
        }

        @Test
        void deleteEntry_with_empty_key_is_rejected() {
            delete(null);

            RejectEntryDecoder decoder = eventPublisher.remove();
            then(decoder.reasonAsString()).isEqualTo("empty key");
        }

        @Test
        void deleteEntry_on_unknown_key_is_rejected() {
            delete("does-not-exist");

            RejectEntryDecoder decoder = eventPublisher.remove();
            then(decoder.reasonAsString()).isEqualTo("key not found");
            then(BufferUtils.toAsciiString(decoder.getKey())).isEqualTo("does-not-exist");
        }

        @Test
        void deleteEntry_twice_produces_reject_on_second() {
            delete("hello");
            eventPublisher.removeAll();

            delete("hello");

            RejectEntryDecoder decoder = eventPublisher.remove();
            then(decoder.reasonAsString()).isEqualTo("key not found");
        }

        @Test
        void put_after_delete_retracks_key() {
            delete("hello");
            eventPublisher.removeAll();

            put("hello", "again");

            then(handler.containsKey(BufferUtils.fromAsciiString("hello"))).isTrue();
            then(handler.getKeyCount()).isEqualTo(2);
        }
    }

    private void put(String key, String value) {
        var encoder = new PutEntryEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum);
        if (key != null) {
            encoder.setKey(key);
        }
        if (value != null) {
            encoder.setValue(value);
        }
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }

    private void delete(String key) {
        var encoder = new DeleteEntryEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum);
        if (key != null) {
            encoder.setKey(key);
        }
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }
}
