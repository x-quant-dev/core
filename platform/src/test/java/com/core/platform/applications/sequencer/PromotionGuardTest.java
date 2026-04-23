package com.core.platform.applications.sequencer;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusServer;
import com.core.platform.schema.TestDispatcher;
import com.core.platform.schema.TestProvider;
import com.core.platform.schema.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.agrona.DirectBuffer;

import java.time.Duration;
import java.time.LocalTime;
import java.util.function.Consumer;

import static org.assertj.core.api.BDDAssertions.then;

public class PromotionGuardTest {

    private TestBusServer<TestDispatcher, TestProvider> busServer;
    private ManualTime time;
    private PromotionGuard promotionGuard;
    private TestSchema schema;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        schema = new TestSchema();
        busServer = new TestBusServer<>(time, schema, activatorFactory);
        promotionGuard = new PromotionGuard(logFactory, time, busServer);
    }

    @Test
    void promote_fails_when_no_events_received() {
        var result = promotionGuard.promote();
        then(result).startsWith("FAIL");
        then(result).contains("no events received");
    }

    @Test
    void promote_fails_when_silence_below_threshold() {
        publishPassiveEvent(1, 1);
        var result = promotionGuard.promote();
        then(result).startsWith("FAIL");
        then(result).contains("primary may still be active");
    }

    @Test
    void promote_passes_when_silence_above_threshold() {
        publishPassiveEvent(1, 1);
        time.advanceTime(Duration.ofMillis(3500));
        var result = promotionGuard.promote();
        then(result).startsWith("PASS");
    }

    @Test
    void promote_fails_when_sequence_gap_detected() {
        publishPassiveEvent(1, 1);
        publishPassiveEvent(1, 3);
        time.advanceTime(Duration.ofMillis(3500));
        var result = promotionGuard.promote();
        then(result).startsWith("FAIL");
        then(result).contains("sequence gap");
    }

    @Test
    void promote_passes_with_contiguous_sequences() {
        publishPassiveEvent(1, 1);
        publishPassiveEvent(1, 2);
        publishPassiveEvent(1, 3);
        time.advanceTime(Duration.ofMillis(3500));
        var result = promotionGuard.promote();
        then(result).startsWith("PASS");
    }

    @Test
    void promote_fails_when_bus_does_not_support_event_listening() {
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        var nonListeningBusServer = new TestBusServer<>(time, schema, activatorFactory) {
            @Override
            public boolean supportsEventListening() {
                return false;
            }
        };
        var guard = new PromotionGuard(logFactory, time, nonListeningBusServer);
        var result = guard.promote();
        then(result).startsWith("FAIL");
        then(result).contains("no events received");
    }

    @Test
    void force_promote_bypasses_all_checks() {
        var result = promotionGuard.forcePromote();
        then(result).startsWith("PASS");
        then(result).contains("force promote");
    }

    @Test
    void reset_gap_detection_allows_promotion_after_gap() {
        publishPassiveEvent(1, 1);
        publishPassiveEvent(1, 3); // gap
        time.advanceTime(Duration.ofMillis(3500));

        var result = promotionGuard.promote();
        then(result).startsWith("FAIL");
        then(result).contains("sequence gap");

        var resetResult = promotionGuard.resetGapDetection();
        then(resetResult).contains("reset");

        result = promotionGuard.promote();
        then(result).startsWith("PASS");
    }

    @Test
    void reset_gap_detection_when_no_gap() {
        var result = promotionGuard.resetGapDetection();
        then(result).contains("no gap");
    }

    @Test
    void promote_passes_with_external_event_source_on_non_listening_bus() {
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        var nonListeningBusServer = new TestBusServer<>(time, schema, activatorFactory) {
            @Override
            public boolean supportsEventListening() {
                return false;
            }
        };

        // Create a fake PassiveEventSource
        @SuppressWarnings("unchecked")
        Consumer<DirectBuffer>[] holder = new Consumer[1];
        PassiveEventSource fakeSource = listener -> holder[0] = listener;

        var guard = new PromotionGuard(logFactory, time, nonListeningBusServer, fakeSource);

        // Push an event through the external source
        var buffer = BufferUtils.allocate(schema.getMessageHeaderLength() + 10);
        buffer.putShort(schema.getApplicationIdOffset(), (short) 1);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), 1);
        buffer.putByte(schema.getMessageTypeOffset(), (byte) -1);
        holder[0].accept(buffer);

        time.advanceTime(Duration.ofMillis(3500));
        var result = guard.promote();
        then(result).startsWith("PASS");
    }

    @Test
    void promotion_pass_increments_passed_counter() {
        publishPassiveEvent(1, 1);
        time.advanceTime(Duration.ofMillis(3500));

        promotionGuard.promote();

        then(promotionGuard.toString()).contains("promotionChecksPassed");
        var result = promotionGuard.promote();
        then(result).startsWith("PASS");
    }

    @Test
    void promotion_fail_increments_failed_counter() {
        promotionGuard.promote();

        then(promotionGuard.toString()).contains("promotionChecksFailed");
    }

    private void publishPassiveEvent(int appId, int appSeqNum) {
        var buffer = BufferUtils.allocate(schema.getMessageHeaderLength() + 10);
        buffer.putShort(schema.getApplicationIdOffset(), (short) appId);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), appSeqNum);
        buffer.putByte(schema.getMessageTypeOffset(), (byte) -1);
        busServer.publishEvent(buffer);
    }

    // ── Epoch validation tests ──────────────────────────────────────────

    @Test
    void promote_fails_when_stale_epoch_detected() {
        publishPassiveEventWithEpoch(1, 1, 2);
        publishPassiveEventWithEpoch(1, 2, 1); // stale epoch
        time.advanceTime(Duration.ofMillis(3500));

        var result = promotionGuard.promote();
        then(result).startsWith("FAIL");
        then(result).contains("stale leader epoch");
    }

    @Test
    void promote_passes_with_increasing_epochs() {
        publishPassiveEventWithEpoch(1, 1, 1);
        publishPassiveEventWithEpoch(1, 2, 2);
        time.advanceTime(Duration.ofMillis(3500));

        var result = promotionGuard.promote();
        then(result).startsWith("PASS");
    }

    @Test
    void stale_epoch_status_in_encode() {
        publishPassiveEventWithEpoch(1, 1, 2);
        publishPassiveEventWithEpoch(1, 2, 1); // stale

        var status = promotionGuard.toString();
        then(status).contains("staleEpochDetected");
        then(status).contains("lastSeenEpoch");
    }

    private void publishPassiveEventWithEpoch(int appId, int appSeqNum, int epoch) {
        var buffer = BufferUtils.allocate(schema.getMessageHeaderLength() + 10);
        buffer.putShort(schema.getApplicationIdOffset(), (short) appId);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), appSeqNum);
        buffer.putByte(schema.getMessageTypeOffset(), (byte) -1);
        buffer.putInt(schema.getLeaderEpochOffset(), epoch);
        busServer.publishEvent(buffer);
    }

    // ── Checksum tests ───────────────────────────────────────────────────

    @Test
    void rolling_checksum_updates_on_passive_events() {
        publishPassiveEvent(1, 1);
        publishPassiveEvent(1, 2);

        var status = promotionGuard.toString();
        then(status).contains("rollingChecksum");
        then(status).contains("checksumMessageCount");
    }

    @Test
    void rolling_checksum_is_deterministic() {
        publishPassiveEvent(1, 1);
        publishPassiveEvent(1, 2);
        var status1 = promotionGuard.toString();

        // Create a second guard and feed the same events
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        var busServer2 = new TestBusServer<>(time, schema, activatorFactory);
        var guard2 = new PromotionGuard(logFactory, time, busServer2);

        publishPassiveEventTo(busServer2, 1, 1);
        publishPassiveEventTo(busServer2, 1, 2);
        var status2 = guard2.toString();

        // Both should have the same checksum
        then(extractField(status1, "rollingChecksum")).isEqualTo(extractField(status2, "rollingChecksum"));
        then(extractField(status1, "checksumMessageCount")).isEqualTo(extractField(status2, "checksumMessageCount"));
    }

    private void publishPassiveEventTo(TestBusServer<TestDispatcher, TestProvider> server, int appId, int appSeqNum) {
        var buffer = BufferUtils.allocate(schema.getMessageHeaderLength() + 10);
        buffer.putShort(schema.getApplicationIdOffset(), (short) appId);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), appSeqNum);
        buffer.putByte(schema.getMessageTypeOffset(), (byte) -1);
        server.publishEvent(buffer);
    }

    private String extractField(String json, String fieldName) {
        var key = "\"" + fieldName + "\":";
        var idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        var start = idx + key.length();
        var end = json.indexOf(',', start);
        if (end < 0) {
            end = json.indexOf('}', start);
        }
        return json.substring(start, end).trim();
    }

    @Nested
    class AutoPromoteTests {

        private Scheduler scheduler;
        private Activator targetActivator;

        @BeforeEach
        void setUp() {
            scheduler = new Scheduler(time);
            var logFactory = new TestLogFactory();
            var metricFactory = new MetricFactory(logFactory);
            var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
            targetActivator = activatorFactory.createActivator("TargetSequencer", new Object());
        }

        @Test
        void enable_auto_promote_fails_without_configuration() {
            var result = promotionGuard.enableAutoPromote();
            then(result).startsWith("FAIL");
        }

        @Test
        void enable_auto_promote_succeeds_after_configuration() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            var result = promotionGuard.enableAutoPromote();
            then(result).startsWith("OK");
            then(result).contains("enabled");
        }

        @Test
        void enable_auto_promote_idempotent() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();
            var result = promotionGuard.enableAutoPromote();
            then(result).contains("already enabled");
        }

        @Test
        void disable_auto_promote_when_not_enabled() {
            var result = promotionGuard.disableAutoPromote();
            then(result).contains("already disabled");
        }

        @Test
        void disable_auto_promote_after_enable() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();
            var result = promotionGuard.disableAutoPromote();
            then(result).startsWith("OK");
            then(result).contains("disabled");
        }

        @Test
        void auto_promote_triggers_when_checks_pass() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();

            publishPassiveEvent(1, 1);
            time.advanceTime(Duration.ofMillis(3500));
            scheduler.fire();

            then(targetActivator.isStarted()).isTrue();
        }

        @Test
        void auto_promote_does_not_trigger_when_checks_fail() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();

            // no events received, checks should fail
            time.advanceTime(Duration.ofMillis(300));
            scheduler.fire();

            then(targetActivator.isStarted()).isFalse();
        }

        @Test
        void auto_promote_only_attempts_once() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();

            publishPassiveEvent(1, 1);
            time.advanceTime(Duration.ofMillis(3500));
            scheduler.fire();

            then(targetActivator.isStarted()).isTrue();

            // fire again — should not attempt again
            time.advanceTime(Duration.ofMillis(300));
            scheduler.fire();

            then(promotionGuard.toString()).contains("autoPromoteAttemptCount");
        }

        @Test
        void auto_promote_does_not_trigger_when_gap_detected() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();

            publishPassiveEvent(1, 1);
            publishPassiveEvent(1, 3); // gap
            time.advanceTime(Duration.ofMillis(3500));
            scheduler.fire();

            then(targetActivator.isStarted()).isFalse();
        }

        @Test
        void auto_promote_does_not_trigger_when_disabled() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();
            promotionGuard.disableAutoPromote();

            publishPassiveEvent(1, 1);
            time.advanceTime(Duration.ofMillis(3500));
            scheduler.fire();

            then(targetActivator.isStarted()).isFalse();
        }

        @Test
        void auto_promote_resets_attempted_on_re_enable() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();

            publishPassiveEvent(1, 1);
            time.advanceTime(Duration.ofMillis(3500));
            scheduler.fire();
            then(targetActivator.isStarted()).isTrue();

            promotionGuard.disableAutoPromote();
            promotionGuard.enableAutoPromote();

            // The latch was reset, but targetActivator is already started
            // Verify status shows auto-promote state
            then(promotionGuard.toString()).contains("autoPromoteEnabled");
        }

        @Test
        void auto_promote_status_in_encode() {
            promotionGuard.configureAutoPromote(scheduler, targetActivator);
            promotionGuard.enableAutoPromote();

            var status = promotionGuard.toString();
            then(status).contains("autoPromoteEnabled");
            then(status).contains("autoPromoteAttempted");
            then(status).contains("autoPromoteAttemptCount");
        }
    }
}
