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

public class SnapshotIndexTest {

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

    @Nested
    class EmptyIndexTests {

        @Test
        void initially_empty() {
            then(index.size()).isEqualTo(0);
            then(index.getLatest()).isNull();
        }
    }

    @Nested
    class AddEntryTests {

        @Test
        void valid_snapshotComplete_adds_entry() {
            dispatchSnapshotComplete(1, 100, SnapshotValidity.VALID, 42, 5000);

            then(index.size()).isEqualTo(1);
        }

        @Test
        void invalid_snapshotComplete_is_ignored() {
            dispatchSnapshotComplete(1, 100, SnapshotValidity.INVALID, 42, 5000);

            then(index.size()).isEqualTo(0);
        }

        @Test
        void timed_out_snapshotComplete_is_ignored() {
            dispatchSnapshotComplete(1, 100, SnapshotValidity.TIMED_OUT, 42, 5000);

            then(index.size()).isEqualTo(0);
        }

        @Test
        void entry_fields_are_correct() {
            dispatchSnapshotComplete(1, 100, SnapshotValidity.VALID, 42, 5000);

            var entry = index.getLatest();
            then(entry.getSnapshotId()).isEqualTo(1);
            then(entry.getCheckpointSeqNum()).isEqualTo(100);
            then(entry.getArchiveRecordingId()).isEqualTo(42);
            then(entry.getArchivePosition()).isEqualTo(5000);
        }
    }

    @Nested
    class LookupTests {

        @Test
        void getLatest_returns_most_recent() {
            dispatchSnapshotComplete(1, 100, SnapshotValidity.VALID, 42, 5000);
            dispatchSnapshotComplete(2, 200, SnapshotValidity.VALID, 43, 6000);

            then(index.getLatest().getSnapshotId()).isEqualTo(2);
        }
    }

    @Nested
    class PruneTests {

        @Test
        void removeOlderThan_prunes_entries() {
            dispatchSnapshotComplete(1, 100, SnapshotValidity.VALID, 42, 5000);
            dispatchSnapshotComplete(2, 200, SnapshotValidity.VALID, 43, 6000);
            dispatchSnapshotComplete(3, 300, SnapshotValidity.VALID, 44, 7000);

            index.removeOlderThan(2);

            then(index.size()).isEqualTo(2);
            then(index.getLatest().getSnapshotId()).isEqualTo(3);
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
