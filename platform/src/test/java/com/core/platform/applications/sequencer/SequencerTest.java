package com.core.platform.applications.sequencer;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.EventLoop;
import com.core.infrastructure.collections.CoreMap;
import com.core.infrastructure.io.Selector;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusServer;
import com.core.platform.bus.TestMessagePublisher;
import com.core.platform.schema.ApplicationDefinitionDecoder;
import com.core.platform.schema.ApplicationDefinitionEncoder;
import com.core.platform.schema.HeartbeatDecoder;
import com.core.platform.schema.TestDispatcher;
import com.core.platform.schema.TestProvider;
import com.core.platform.schema.TestSchema;
import org.agrona.MutableDirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.mockito.Mockito.mock;

public class SequencerTest {

    private TestBusServer<TestDispatcher, TestProvider> busServer;
    private ManualTime time;
    private EventLoop eventLoop;
    private Sequencer sequencer;
    private TestSchema schema;
    private TestMessagePublisher eventPublisher;
    private Activator activator;
    private Map<String, Short> nameIdMap;

    @BeforeEach
    void before_each() {
        final var applicationName = "SEQ01";

        nameIdMap = new CoreMap<>();
        time = new ManualTime(LocalTime.of(9, 30));
        var scheduler = new Scheduler(time);
        eventLoop = new EventLoop(time, scheduler, mock(Selector.class));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        schema = new TestSchema();
        busServer = new TestBusServer<>(time, schema, activatorFactory);
        eventPublisher = busServer.getEventPublisher();
        sequencer = new Sequencer(
                time,
                scheduler,
                activatorFactory,
                logFactory,
                metricFactory,
                busServer,
                applicationName);
        activator = activatorFactory.getActivator(sequencer);
        busServer.getDispatcher().addHeartbeatListener(x -> {
            var buffer = busServer.acquire();
            buffer.putBytes(0, x.buffer(), x.offset(), x.length());
            busServer.commit(x.length());
        });
        busServer.getDispatcher().addApplicationDefinitionListener(x -> {
            var id = nameIdMap.get(x.nameAsString());
            if (id == null) {
                id = (short) (nameIdMap.size() + 1);
                nameIdMap.put(x.nameAsString(), id);
                busServer.setApplicationSequenceNumber(id, 1);
            }

            var buffer = busServer.acquire();
            buffer.putBytes(0, x.buffer(), x.offset(), x.length());
            buffer.putShort(schema.getApplicationIdOffset(), id);
            busServer.commit(x.length());
        });
    }

    @Test
    void commands_update_contributor_sequence_number() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01"); // 1
        publishAppDefinitionCommand("APP02"); // 2

        publishCommand(2, 2, -1, "1");
        publishCommand(3, 2, -1, "12");
        publishCommand(2, 3, -1, "123");
        publishCommand(2, 4, -1, "1234");
        publishCommand(3, 3, -1, "12345");

        then(eventPublisher.size()).isEqualTo(5);
        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(4);
        then(busServer.getApplicationSequenceNumber(3)).isEqualTo(3);
        then(busServer.getApplicationSequenceNumber(4)).isEqualTo(0);
    }

    @Test
    void command_sets_timestamp_of_event() {
        time.advanceTime(Duration.ofMillis(250));
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01");

        publishCommand(2, 2, -1, "1");

        var decoder = eventPublisher.remove();
        then(decoder.get("timestamp")).isEqualTo(time.nanos());
    }

    @Test
    void invalid_contributor_sequence_number_drops_command() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01"); // 1
        publishCommand(2, 2, -1, "1");
        publishCommand(2, 2, -3, "12");

        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(2);
    }

    @Test
    void too_short_buffer_drops_command() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01"); // 1
        var buffer = BufferUtils.allocate(20);
        buffer.putShort(schema.getApplicationIdOffset(), (short) 1);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), 2);

        busServer.publishCommand(buffer);

        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(1);
    }

    @Test
    void events_update_contributor_sequence_number() {
        publishAppDefinitionEvent(5, "APP01");
        publishAppDefinitionEvent(6, "APP02");
        publishEvent(5, 5, -1, "1");
        publishEvent(6, 9, -2, "12");

        then(busServer.getApplicationSequenceNumber(5)).isEqualTo(5);
        then(busServer.getApplicationSequenceNumber(6)).isEqualTo(9);
    }

    @Test
    void take_over_as_primary_and_keep_appSeqNum() {
        publishAppDefinitionEvent(1, "SEQ01");
        publishAppDefinitionEvent(5, "APP01");
        publishAppDefinitionEvent(6, "APP02");
        publishEvent(5, 5, -1, "1");
        publishEvent(6, 9, -2, "12");
        activator.start();
        // pop the heartbeat
        eventPublisher.remove();

        publishCommand(5, 6, -1, "");

        then(busServer.getApplicationSequenceNumber(5)).isEqualTo(6);
    }

    @Test
    void take_over_as_primary_and_prevent_event() {
        publishAppDefinitionEvent(1, "SEQ01");
        publishAppDefinitionEvent(3, "APP01");
        publishAppDefinitionEvent(7, "APP02");
        publishEvent(3, 5, -1, "1");
        publishEvent(7, 9, -2, "12");
        activator.start();
        eventPublisher.remove();

        publishCommand(3, 5, -1, "");

        then(busServer.getApplicationSequenceNumber(3)).isEqualTo(5);
        then(busServer.getApplicationSequenceNumber(7)).isEqualTo(9);
    }

    @Test
    void too_short_buffer_drops_event() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        var buffer = BufferUtils.allocate(20);
        buffer.putShort(schema.getApplicationIdOffset(), (short) 2);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), 1);

        busServer.publishEvent(buffer);

        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(0);
    }

    @Test
    void heartbeat_is_published_immediately() throws IOException {
        activator.start();

        eventLoop.runOnce();

        then(eventPublisher.size()).isEqualTo(2);
        ApplicationDefinitionDecoder decoder1 = eventPublisher.remove();
        then(decoder1.getApplicationId()).isEqualTo((short) 1);
        then(decoder1.getApplicationSequenceNumber()).isEqualTo(1);
        then(decoder1.getTimestamp()).isEqualTo(time.nanos());
        HeartbeatDecoder decoder2 = eventPublisher.remove();
        then(decoder2.getApplicationId()).isEqualTo((short) 1);
        then(decoder2.getApplicationSequenceNumber()).isEqualTo(2);
        then(decoder2.getTimestamp()).isEqualTo(time.nanos());
    }

    @Test
    void heartbeat_published_at_specified_time() throws IOException {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        time.advanceTime(Duration.ofMillis(100));

        eventLoop.runOnce();

        then(eventPublisher.size()).isEqualTo(1);
        HeartbeatDecoder decoder2 = eventPublisher.remove();
        then(decoder2.getApplicationId()).isEqualTo((short) 1);
        then(decoder2.getApplicationSequenceNumber()).isEqualTo(3);
        then(decoder2.getTimestamp()).isEqualTo(time.nanos());
    }

    @Test
    void take_over_as_primary_and_send_heartbeats() throws IOException {
        publishAppDefinitionEvent(1, "SEQ01");
        publishEvent(1, 2, -1, "");
        publishEvent(1, 3, -1, "");
        publishEvent(1, 4, -1, "");
        time.advanceTime(Duration.ofMillis(1500));
        eventPublisher.remove();
        eventPublisher.remove();
        eventPublisher.remove();
        eventPublisher.remove();
        activator.start();
        // sent
        time.advanceTime(Duration.ofMillis(100));
        eventLoop.runOnce();
        time.advanceTime(Duration.ofMillis(200));
        eventLoop.runOnce();
        time.advanceTime(Duration.ofMillis(100));
        eventLoop.runOnce();

        var decoder = eventPublisher.remove();
        then(decoder.getApplicationSequenceNumber()).isEqualTo(5);
        decoder = eventPublisher.remove();
        then(decoder.getApplicationSequenceNumber()).isEqualTo(6);
        decoder = eventPublisher.remove();
        then(decoder.getApplicationSequenceNumber()).isEqualTo(7);
        decoder = eventPublisher.remove();
        then(decoder.getApplicationSequenceNumber()).isEqualTo(8);
    }

    @Test
    void command_with_unknown_appId_is_dropped() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();

        // appId 99 has never been registered via AppDefinition
        publishCommand(99, 2, -1, "test");

        // command should be dropped, no event published
        then(eventPublisher.size()).isEqualTo(0);
    }

    @Test
    void passive_events_do_not_regress_sequence_numbers() {
        // receive events with appId=5
        publishAppDefinitionEvent(5, "APP01");
        publishEvent(5, 5, -1, "1");

        // simulate replay/rewind: older event arrives
        publishEvent(5, 3, -1, "old");

        // sequence number should stay at 5 (not regress to 3)
        then(busServer.getApplicationSequenceNumber(5)).isEqualTo(5);
    }

    @Test
    void command_with_unregistered_in_range_appId_is_dropped() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();

        // appId 50 is in range (≤100 array size) but was never registered via ApplicationDefinition
        publishCommand(50, 1, -1, "test");

        // command should be dropped, no event published
        then(eventPublisher.size()).isEqualTo(0);
    }

    @Test
    void passive_event_with_appId_zero_is_ignored() {
        // sequencer is passive (not activated)
        publishEvent(0, 1, -1, "test");

        // should not crash and state unchanged
        then(busServer.getApplicationSequenceNumber(0)).isEqualTo(-1);
    }

    @Test
    void passive_event_with_negative_appId_is_ignored() {
        // sequencer is passive (not activated)
        publishEvent(-1, 1, -1, "test");

        // should not crash
        then(busServer.getApplicationSequenceNumber(-1)).isEqualTo(-1);
    }

    @Test
    void dispatch_exception_propagates_to_event_loop() {
        busServer.getDispatcher().addApplicationDefinitionListener(x -> {
            if (x.nameAsString().equals("CRASHME")) {
                throw new RuntimeException("simulated dispatch error");
            }
        });
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();

        thenThrownBy(() -> busServer.publishCommand(new ApplicationDefinitionEncoder()
                .setApplicationId((short) 0)
                .setApplicationSequenceNumber(1)
                .setName("CRASHME")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated dispatch error");
    }

    @Test
    void app_definition_with_nonzero_appId_is_dropped() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();

        // app definition with appId=5 instead of 0 should be rejected
        busServer.publishCommand(new ApplicationDefinitionEncoder()
                .setApplicationId((short) 5)
                .setApplicationSequenceNumber(1)
                .setName("ROGUE"));

        then(eventPublisher.size()).isEqualTo(0);
    }

    @Test
    void setApplicationSequenceNumber_with_zero_appId_is_ignored() {
        busServer.setApplicationSequenceNumber(0, 10);
        then(busServer.getApplicationSequenceNumber(0)).isEqualTo(-1);
    }

    @Test
    void setApplicationSequenceNumber_with_negative_appId_is_ignored() {
        busServer.setApplicationSequenceNumber(-1, 10);
        then(busServer.getApplicationSequenceNumber(-1)).isEqualTo(-1);
    }

    @Test
    void setApplicationSequenceNumber_with_large_appId_resizes_correctly() {
        busServer.setApplicationSequenceNumber(500, 9);

        then(busServer.getApplicationSequenceNumber(500)).isEqualTo(9);
    }

    @Test
    void setApplicationSequenceNumber_with_appId_beyond_double_capacity() {
        busServer.setApplicationSequenceNumber(250, 7);

        then(busServer.getApplicationSequenceNumber(250)).isEqualTo(7);
    }

    // ── Phase 4: leader-epoch stamping ──────────────────────────────────

    @Test
    void published_events_contain_leader_epoch() {
        activator.start();
        eventPublisher.remove();
        var heartbeat = eventPublisher.remove();
        then(heartbeat.getLeaderEpoch()).isEqualTo(1);

        publishAppDefinitionCommand("APP01");
        publishCommand(2, 2, -1, "test");

        var event = eventPublisher.remove();
        then(event.getLeaderEpoch()).isEqualTo(1);
    }

    @Test
    void epoch_survives_beyond_32k_without_truncation() {
        var buffer = BufferUtils.allocate(schema.getMessageHeaderLength());
        int epoch = 50000;
        buffer.putInt(schema.getLeaderEpochOffset(), epoch);
        then(buffer.getInt(schema.getLeaderEpochOffset())).isEqualTo(50000);
    }

    @Test
    void heartbeat_contains_full_epoch() {
        activator.start();
        eventPublisher.remove(); // appDef
        HeartbeatDecoder heartbeat = eventPublisher.remove();
        then(heartbeat.getLeaderEpoch()).isEqualTo(1);

        activator.stop();
        activator.start();
        HeartbeatDecoder heartbeat2 = eventPublisher.remove();
        then(heartbeat2.getLeaderEpoch()).isEqualTo(2);
    }

    @Test
    void passive_event_with_stale_epoch_is_dropped() {
        var buffer = buildMessage(2, 1, (byte) -1, "test");
        buffer.putInt(schema.getLeaderEpochOffset(), 2);
        busServer.publishEvent(buffer);

        var stale = buildMessage(2, 2, (byte) -1, "stale");
        stale.putInt(schema.getLeaderEpochOffset(), 1);
        busServer.publishEvent(stale);

        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(1);
    }

    @Test
    void passive_event_with_higher_epoch_is_accepted() {
        var buffer = buildMessage(2, 1, (byte) -1, "test");
        buffer.putInt(schema.getLeaderEpochOffset(), 1);
        busServer.publishEvent(buffer);

        var newer = buildMessage(2, 2, (byte) -1, "newer");
        newer.putInt(schema.getLeaderEpochOffset(), 2);
        busServer.publishEvent(newer);

        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(2);
    }

    @Test
    void stale_epoch_drop_resets_on_new_epoch_sequence() {
        var buffer = buildMessage(2, 1, (byte) -1, "test");
        buffer.putInt(schema.getLeaderEpochOffset(), 2);
        busServer.publishEvent(buffer);

        var stale = buildMessage(2, 2, (byte) -1, "stale");
        stale.putInt(schema.getLeaderEpochOffset(), 1);
        busServer.publishEvent(stale);

        var newer = buildMessage(2, 2, (byte) -1, "newer");
        newer.putInt(schema.getLeaderEpochOffset(), 3);
        busServer.publishEvent(newer);

        then(busServer.getApplicationSequenceNumber(2)).isEqualTo(2);
    }

    // ── Latency tracking tests ────────────────────────────────────────────

    @Test
    void status_contains_latency_fields() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01");

        publishCommand(2, 2, -1, "test");

        var status = sequencer.toString();
        then(status).contains("lastCommandLatencyNanos");
        then(status).contains("maxCommandLatencyNanos");
    }

    @Test
    void latency_is_zero_when_no_commands() {
        var status = sequencer.toString();
        then(status).contains("lastCommandLatencyNanos");
    }

    @Test
    void reset_latency_clears_max() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01");

        publishCommand(2, 2, -1, "test");
        sequencer.resetLatency();

        var status = sequencer.toString();
        then(status).contains("lastCommandLatencyNanos");
        then(status).contains("maxCommandLatencyNanos");
    }

    @Test
    void passive_dispatch_exception_propagates_to_event_loop() {
        busServer.getDispatcher().addApplicationDefinitionListener(x -> {
            if (x.nameAsString().equals("CRASHME")) {
                throw new RuntimeException("simulated passive dispatch error");
            }
        });

        thenThrownBy(() -> busServer.publishEvent(new ApplicationDefinitionEncoder()
                .setApplicationId((short) 5)
                .setApplicationSequenceNumber(1)
                .setName("CRASHME")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated passive dispatch error");
    }

    public void publishCommand(int appId, int appSeqNum, int msgType, String message) {
        busServer.publishCommand(buildMessage(appId, appSeqNum, (byte) msgType, message));
    }

    public void publishEvent(int appId, int appSeqNum, int msgType, String message) {
        busServer.publishEvent(buildMessage(appId, appSeqNum, (byte) msgType, message));
    }

    public void publishAppDefinitionCommand(String appName) {
        busServer.publishCommand(new ApplicationDefinitionEncoder()
                .setApplicationId((short) 0)
                .setApplicationSequenceNumber(1)
                .setName(appName));
        busServer.getEventPublisher().remove();
    }

    public void publishAppDefinitionEvent(int appId, String appName) {
        busServer.publishEvent(new ApplicationDefinitionEncoder()
                .setApplicationId((short) appId)
                .setApplicationSequenceNumber(1)
                .setName(appName));
    }

    private MutableDirectBuffer buildMessage(int appId, int appSeqNum, byte msgType, String message) {
        var event = BufferUtils.allocate(
                schema.getMessageHeaderLength() + message.length());
        event.putShort(schema.getApplicationIdOffset(), (short) appId);
        event.putInt(schema.getApplicationSequenceNumberOffset(), appSeqNum);
        event.putByte(schema.getMessageTypeOffset(), msgType);
        event.putStringWithoutLengthAscii(schema.getMessageHeaderLength(), message);
        return event;
    }
}
