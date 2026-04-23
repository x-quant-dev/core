package com.core.platform.applications.snapshot;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusClient;
import com.core.platform.schema.SnapshotCompleteEncoder;
import com.core.platform.schema.SnapshotValidity;
import com.core.platform.schema.TestDispatcher;
import com.core.platform.schema.TestProvider;
import com.core.platform.schema.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.Mockito.mock;

public class LateJoinerServiceTest {

    private ManualTime time;
    private TestBusClient<TestDispatcher, TestProvider> busClient;
    private SnapshotIndex snapshotIndex;
    private SnapshotRecovery snapshotRecovery;
    private LateJoinerService service;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        var schema = new TestSchema();
        busClient = new TestBusClient<>(schema, activatorFactory);
        snapshotIndex = new SnapshotIndex(busClient, metricFactory);
        snapshotRecovery = new SnapshotRecovery(logFactory);
        service = new LateJoinerService(logFactory, metricFactory, time, snapshotIndex, snapshotRecovery);
    }

    @Nested
    class InitialStateTests {

        @Test
        void initially_idle() {
            then(service.getCurrentPhase()).isEqualTo(LateJoinerService.Phase.IDLE);
            then(service.getRecoveredCheckpointSeqNum()).isEqualTo(-1);
        }
    }

    @Nested
    class RecoverWithSnapshotTests {

        @Test
        void recover_with_snapshot_returns_checkpoint() {
            dispatchSnapshotComplete(1, 500, SnapshotValidity.VALID, 42, 5000);
            var snapshottable = mock(Snapshottable.class);

            var result = service.recover((short) 1, snapshottable);

            then(result).isEqualTo(500);
            then(service.getCurrentPhase()).isEqualTo(LateJoinerService.Phase.DELTA_REPLAY);
            then(service.getRecoveredCheckpointSeqNum()).isEqualTo(500);
        }
    }

    @Nested
    class RecoverWithoutSnapshotTests {

        @Test
        void recover_without_snapshot_returns_zero() {
            var snapshottable = mock(Snapshottable.class);

            var result = service.recover((short) 1, snapshottable);

            then(result).isEqualTo(0);
            then(service.getCurrentPhase()).isEqualTo(LateJoinerService.Phase.DELTA_REPLAY);
        }
    }

    @Nested
    class GoLiveTests {

        @Test
        void go_live_transitions_from_delta_replay() {
            var snapshottable = mock(Snapshottable.class);
            service.recover((short) 1, snapshottable);

            service.goLive();

            then(service.getCurrentPhase()).isEqualTo(LateJoinerService.Phase.LIVE);
        }

        @Test
        void go_live_from_idle_does_nothing() {
            service.goLive();

            then(service.getCurrentPhase()).isEqualTo(LateJoinerService.Phase.IDLE);
        }
    }

    @Nested
    class DeltaReplayTests {

        @Test
        void on_delta_replayed_increments_counter() {
            service.onDeltaReplayed();
            service.onDeltaReplayed();
            service.onDeltaReplayed();

            then(service.getEventsReplayed()).isEqualTo(3);
        }
    }

    @Nested
    class ResetTests {

        @Test
        void reset_returns_to_idle() {
            var snapshottable = mock(Snapshottable.class);
            service.recover((short) 1, snapshottable);
            service.goLive();

            service.reset();

            then(service.getCurrentPhase()).isEqualTo(LateJoinerService.Phase.IDLE);
            then(service.getEventsReplayed()).isEqualTo(0);
            then(service.getRecoveredCheckpointSeqNum()).isEqualTo(-1);
        }
    }

    @Nested
    class RecoverWhileInProgressTests {

        @Test
        void recover_while_in_progress_returns_negative_one() {
            var snapshottable = mock(Snapshottable.class);
            service.recover((short) 1, snapshottable);

            var result = service.recover((short) 1, snapshottable);

            then(result).isEqualTo(-1);
        }
    }

    @Nested
    class StatusTests {

        @Test
        void status_contains_all_fields() {
            var status = service.toString();

            then(status).contains("currentPhase");
            then(status).contains("recoveredCheckpointSeqNum");
            then(status).contains("eventsReplayed");
            then(status).contains("recoveryCount");
            then(status).contains("lastRecoveryDurationNanos");
        }
    }

    private void dispatchSnapshotComplete(
            long snapshotId, long checkpointSeqNum, SnapshotValidity validity,
            long archiveRecordingId, long archivePosition) {
        var encoder = new SnapshotCompleteEncoder();
        encoder.setApplicationId((short) 1);
        encoder.setApplicationSequenceNumber(1);
        encoder.setSnapshotId(snapshotId);
        encoder.setCheckpointSeqNum(checkpointSeqNum);
        encoder.setNodeCount((short) 1);
        encoder.setValidity(validity);
        encoder.setArchiveRecordingId(archiveRecordingId);
        encoder.setArchivePosition(archivePosition);
        busClient.dispatch(encoder);
    }
}
