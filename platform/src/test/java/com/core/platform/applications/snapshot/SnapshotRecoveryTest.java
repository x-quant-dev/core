package com.core.platform.applications.snapshot;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
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

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class SnapshotRecoveryTest {

    private SnapshotRecovery recovery;

    @BeforeEach
    void before_each() {
        var logFactory = new TestLogFactory();
        recovery = new SnapshotRecovery(logFactory);
    }

    @Nested
    class RecoverTests {

        @Test
        void returns_negative_one_when_no_snapshot_available() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recover(-1, 0, (short) 1, snapshottable);

            then(result).isEqualTo(-1);
            verifyNoInteractions(snapshottable);
        }

        @Test
        void returns_checkpoint_seq_num_when_snapshot_exists() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recover(5, 1000, (short) 1, snapshottable);

            then(result).isEqualTo(1000);
        }

        @Test
        void returns_correct_checkpoint_for_different_values() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recover(10, 5000, (short) 2, snapshottable);

            then(result).isEqualTo(5000);
        }

        @Test
        void zero_snapshot_id_is_valid() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recover(0, 100, (short) 1, snapshottable);

            then(result).isEqualTo(100);
        }
    }

    @Nested
    class RecoverFromIndexTests {

        private TestBusClient<TestDispatcher, TestProvider> busClient;
        private SnapshotIndex index;

        @BeforeEach
        void before_each() {
            var logFactory = new TestLogFactory();
            var metricFactory = new MetricFactory(logFactory);
            var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
            var schema = new TestSchema();
            busClient = new TestBusClient<>(schema, activatorFactory);
            index = new SnapshotIndex(busClient, metricFactory);
        }

        @Test
        void returns_checkpoint_when_snapshot_exists() {
            var snapshottable = mock(Snapshottable.class);
            dispatchSnapshotComplete(5, 1000, SnapshotValidity.VALID, 42, 5000);

            var result = recovery.recoverFromIndex(index, (short) 1, snapshottable);

            then(result).isEqualTo(1000);
        }

        @Test
        void returns_negative_one_when_index_empty() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recoverFromIndex(index, (short) 1, snapshottable);

            then(result).isEqualTo(-1);
            verifyNoInteractions(snapshottable);
        }

        @Test
        void returns_latest_checkpoint_when_multiple_snapshots() {
            var snapshottable = mock(Snapshottable.class);
            dispatchSnapshotComplete(1, 100, SnapshotValidity.VALID, 42, 5000);
            dispatchSnapshotComplete(2, 200, SnapshotValidity.VALID, 43, 6000);
            dispatchSnapshotComplete(3, 300, SnapshotValidity.VALID, 44, 7000);

            var result = recovery.recoverFromIndex(index, (short) 1, snapshottable);

            then(result).isEqualTo(300);
        }

        @Test
        void updates_recovery_state() {
            var snapshottable = mock(Snapshottable.class);
            dispatchSnapshotComplete(5, 1000, SnapshotValidity.VALID, 42, 5000);

            recovery.recoverFromIndex(index, (short) 1, snapshottable);

            var status = recovery.toString();
            then(status).contains("lastRecoveredSnapshotId");
            then(status).contains("5");
            then(status).contains("lastRecoveredCheckpointSeqNum");
            then(status).contains("1000");
            then(status).contains("recoveryCount");
            then(status).contains("1");
        }

        @Test
        void ignores_invalid_snapshots() {
            var snapshottable = mock(Snapshottable.class);
            dispatchSnapshotComplete(1, 100, SnapshotValidity.INVALID, 42, 5000);

            var result = recovery.recoverFromIndex(index, (short) 1, snapshottable);

            then(result).isEqualTo(-1);
            verifyNoInteractions(snapshottable);
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

    @Nested
    class RecoverFromArchiveTests {

        @Test
        void archive_recovery_returns_negative_one_not_implemented() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recoverFromArchive(null, 42, (short) 1, snapshottable);

            then(result).isEqualTo(-1);
            verifyNoInteractions(snapshottable);
        }

        @Test
        void with_null_archive_returns_negative_one() {
            var snapshottable = mock(Snapshottable.class);

            var result = recovery.recoverFromArchive(null, 0, (short) 2, snapshottable);

            then(result).isEqualTo(-1);
            verifyNoInteractions(snapshottable);
        }
    }

    @Nested
    class StatusTests {

        @Test
        void encode_shows_ready_status() {
            var status = recovery.toString();

            then(status).contains("status");
            then(status).contains("ready");
        }

        @Test
        void encode_shows_default_recovery_state() {
            var status = recovery.toString();

            then(status).contains("lastRecoveredSnapshotId");
            then(status).contains("-1");
            then(status).contains("recoveryCount");
        }

        @Test
        void encode_shows_updated_state_after_recovery() {
            var snapshottable = mock(Snapshottable.class);
            recovery.recover(7, 2000, (short) 1, snapshottable);

            var status = recovery.toString();

            then(status).contains("7");
            then(status).contains("2000");
        }

        @Test
        void recovery_count_increments() {
            var snapshottable = mock(Snapshottable.class);
            recovery.recover(1, 100, (short) 1, snapshottable);
            recovery.recover(2, 200, (short) 1, snapshottable);

            var status = recovery.toString();

            then(status).contains("2");
        }
    }
}
