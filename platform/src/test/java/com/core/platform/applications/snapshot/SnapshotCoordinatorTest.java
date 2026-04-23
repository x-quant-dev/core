package com.core.platform.applications.snapshot;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusClient;
import com.core.platform.bus.TestMessagePublisher;
import com.core.platform.schema.SnapshotBeginEncoder;
import com.core.platform.schema.SnapshotChunkEncoder;
import com.core.platform.schema.SnapshotCompleteDecoder;
import com.core.platform.schema.SnapshotRequestDecoder;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SnapshotCoordinatorTest {

    private ManualTime time;
    private Scheduler scheduler;
    private TestBusClient<TestDispatcher, TestProvider> busClient;
    private TestMessagePublisher publisher;
    private SnapshotCoordinator coordinator;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        scheduler = new Scheduler(time);
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        var schema = new TestSchema();
        busClient = new TestBusClient<>(schema, activatorFactory);
        coordinator = new SnapshotCoordinator(
                time, scheduler, activatorFactory, logFactory, metricFactory, busClient, "SNAP01");
        publisher = busClient.getMessagePublisher("SNAP01");

        var activator = activatorFactory.getActivator(coordinator);
        activator.start();
        publisher.removeAll();
    }

    @Nested
    class SnapshotRequestTests {

        @Test
        void snapshot_sends_snapshotRequest_command() {
            coordinator.snapshot();

            then(publisher.size()).isEqualTo(1);
            SnapshotRequestDecoder decoder = publisher.remove();
            then(decoder.getSnapshotId()).isEqualTo(1);
            then(decoder.getExpectedNodeCount()).isEqualTo((short) 0);
        }

        @Test
        void snapshot_when_not_active_does_nothing() {
            var logFactory = new TestLogFactory();
            var metricFactory = new MetricFactory(logFactory);
            var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
            var schema = new TestSchema();
            var inactiveClient = new TestBusClient<>(schema, activatorFactory);
            var inactiveCoordinator = new SnapshotCoordinator(
                    time, scheduler, activatorFactory, logFactory, metricFactory, inactiveClient, "SNAP02");
            var inactivePublisher = inactiveClient.getMessagePublisher("SNAP02");

            inactiveCoordinator.snapshot();

            then(inactivePublisher.size()).isEqualTo(0);
        }

        @Test
        void snapshot_while_in_progress_does_nothing() {
            coordinator.snapshot();
            publisher.removeAll();

            coordinator.snapshot();

            then(publisher.size()).isEqualTo(0);
        }
    }

    @Nested
    class SnapshotBeginTests {

        @Test
        void snapshotBegin_triggers_registered_nodes() {
            var node = mock(Snapshottable.class);
            when(node.nodeId()).thenReturn((short) 1);
            coordinator.register(node);

            coordinator.snapshot();
            publisher.removeAll();

            dispatchSnapshotBegin(1, 100);

            verify(node).onSnapshotRequest(1, 100, busClient.getProvider("SNAP01", null));
        }

        @Test
        void snapshotBegin_for_wrong_id_is_ignored() {
            var node = mock(Snapshottable.class);
            when(node.nodeId()).thenReturn((short) 1);
            coordinator.register(node);

            coordinator.snapshot();
            publisher.removeAll();

            dispatchSnapshotBegin(999, 100);

            verifyNoInteractions(node);
        }
    }

    @Nested
    class CompletionTests {

        @Test
        void all_chunks_received_publishes_complete_valid() {
            var node = mock(Snapshottable.class);
            when(node.nodeId()).thenReturn((short) 1);
            coordinator.register(node);

            coordinator.snapshot();
            publisher.removeAll();

            dispatchSnapshotBegin(1, 100);
            dispatchSnapshotChunk(1, (short) 1, (short) 0, (short) 1);

            then(publisher.size()).isEqualTo(1);
            SnapshotCompleteDecoder decoder = publisher.remove();
            then(decoder.getSnapshotId()).isEqualTo(1);
            then(decoder.getCheckpointSeqNum()).isEqualTo(100);
            then(decoder.getNodeCount()).isEqualTo((short) 1);
            then(decoder.getValidity()).isEqualTo(SnapshotValidity.VALID);
        }

        @Test
        void timeout_publishes_complete_timed_out() {
            var node = mock(Snapshottable.class);
            when(node.nodeId()).thenReturn((short) 1);
            coordinator.register(node);

            coordinator.snapshot();
            publisher.removeAll();

            dispatchSnapshotBegin(1, 100);

            time.advanceTime(Duration.ofSeconds(31));
            scheduler.fire();

            then(publisher.size()).isEqualTo(1);
            SnapshotCompleteDecoder decoder = publisher.remove();
            then(decoder.getSnapshotId()).isEqualTo(1);
            then(decoder.getValidity()).isEqualTo(SnapshotValidity.TIMED_OUT);
        }
    }

    private void dispatchSnapshotBegin(long snapshotId, long checkpointSeqNum) {
        var encoder = new SnapshotBeginEncoder();
        encoder.setApplicationId((short) 1);
        encoder.setApplicationSequenceNumber(1);
        encoder.setSnapshotId(snapshotId);
        encoder.setCheckpointSeqNum(checkpointSeqNum);
        encoder.setRequestTimestamp(0);
        encoder.setExpectedNodeCount((short) 0);
        busClient.dispatch(encoder);
    }

    private void dispatchSnapshotChunk(long snapshotId, short nodeId, short chunkIndex, short totalChunks) {
        var encoder = new SnapshotChunkEncoder();
        encoder.setApplicationId((short) 1);
        encoder.setApplicationSequenceNumber(1);
        encoder.setSnapshotId(snapshotId);
        encoder.setNodeId(nodeId);
        encoder.setChunkIndex(chunkIndex);
        encoder.setTotalChunks(totalChunks);
        busClient.dispatch(encoder);
    }
}
