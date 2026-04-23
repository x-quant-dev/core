package com.core.clob.applications;

import com.core.clob.schema.ClobDispatcher;
import com.core.clob.schema.ClobProvider;
import com.core.clob.schema.ClobSchema;
import com.core.clob.schema.DeleteEntryEncoder;
import com.core.clob.schema.PutEntryEncoder;
import com.core.clob.schema.RejectEntryEncoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * Unit tests for {@link KvStoreClient}.
 *
 * <p>Simulates an event stream by dispatching {@code PutEntry}, {@code DeleteEntry}, and
 * {@code RejectEntry} decoders through a {@link TestBusClient} dispatcher, and asserts the
 * materialized state matches.
 */
public class KvStoreClientTest {

    private TestBusClient<ClobDispatcher, ClobProvider> busClient;
    private KvStoreClient client;

    @BeforeEach
    void before_each() {
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);

        busClient = new TestBusClient<>(new ClobSchema(), activatorFactory);
        client = new KvStoreClient(busClient);
    }

    @Test
    void put_stores_value_for_lookup() {
        put("hello", "world");

        then(client.get(BufferUtils.fromAsciiString("hello"))).isEqualTo("world");
        then(client.size()).isEqualTo(1);
    }

    @Test
    void put_overwrites_previous_value() {
        put("hello", "world");
        put("hello", "there");

        then(client.get(BufferUtils.fromAsciiString("hello"))).isEqualTo("there");
        then(client.size()).isEqualTo(1);
    }

    @Test
    void get_unknown_key_returns_not_found() {
        then(client.get(BufferUtils.fromAsciiString("missing"))).isEqualTo("NOT_FOUND");
    }

    @Test
    void delete_removes_key() {
        put("hello", "world");
        delete("hello");

        then(client.get(BufferUtils.fromAsciiString("hello"))).isEqualTo("NOT_FOUND");
        then(client.size()).isEqualTo(0);
    }

    @Test
    void multiple_keys_are_independent() {
        put("a", "1");
        put("b", "2");
        put("c", "3");
        delete("b");

        then(client.get(BufferUtils.fromAsciiString("a"))).isEqualTo("1");
        then(client.get(BufferUtils.fromAsciiString("b"))).isEqualTo("NOT_FOUND");
        then(client.get(BufferUtils.fromAsciiString("c"))).isEqualTo("3");
        then(client.size()).isEqualTo(2);
    }

    @Test
    void reject_increments_counter_without_touching_store() {
        put("hello", "world");
        reject("hello", "some reason");

        then(client.size()).isEqualTo(1);
        then(client.getRejectCount()).isEqualTo(1);
        then(client.get(BufferUtils.fromAsciiString("hello"))).isEqualTo("world");
    }

    @Test
    void replay_of_events_produces_identical_state() {
        put("a", "1");
        put("b", "2");
        delete("a");
        put("c", "3");

        // rebuild a fresh client and replay the same sequence
        var activatorFactory = new ActivatorFactory(new TestLogFactory(), new MetricFactory(new TestLogFactory()));
        var replayClient = new TestBusClient<ClobDispatcher, ClobProvider>(new ClobSchema(), activatorFactory);
        var replay = new KvStoreClient(replayClient);
        dispatchPut(replayClient, "a", "1");
        dispatchPut(replayClient, "b", "2");
        dispatchDelete(replayClient, "a");
        dispatchPut(replayClient, "c", "3");

        then(replay.size()).isEqualTo(client.size());
        then(replay.get(BufferUtils.fromAsciiString("a"))).isEqualTo(client.get(BufferUtils.fromAsciiString("a")));
        then(replay.get(BufferUtils.fromAsciiString("b"))).isEqualTo(client.get(BufferUtils.fromAsciiString("b")));
        then(replay.get(BufferUtils.fromAsciiString("c"))).isEqualTo(client.get(BufferUtils.fromAsciiString("c")));
    }

    private void put(String key, String value) {
        dispatchPut(busClient, key, value);
    }

    private void delete(String key) {
        dispatchDelete(busClient, key);
    }

    private void reject(String key, String reason) {
        busClient.dispatch(new RejectEntryEncoder()
                .setApplicationId((short) 2)
                .setApplicationSequenceNumber(1)
                .setKey(key)
                .setReason(reason));
    }

    private static void dispatchPut(
            TestBusClient<ClobDispatcher, ClobProvider> bus, String key, String value) {
        bus.dispatch(new PutEntryEncoder()
                .setApplicationId((short) 2)
                .setApplicationSequenceNumber(1)
                .setKey(key)
                .setValue(value));
    }

    private static void dispatchDelete(
            TestBusClient<ClobDispatcher, ClobProvider> bus, String key) {
        bus.dispatch(new DeleteEntryEncoder()
                .setApplicationId((short) 2)
                .setApplicationSequenceNumber(1)
                .setKey(key));
    }
}
