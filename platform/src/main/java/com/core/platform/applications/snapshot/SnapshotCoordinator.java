package com.core.platform.applications.snapshot;

import com.core.infrastructure.collections.CoreMap;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Provider;
import com.core.infrastructure.time.Scheduler;
import com.core.infrastructure.time.Time;
import com.core.platform.activation.Activatable;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.BusClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Initiates coordinated snapshots, tracks per-node chunk completion, and publishes {@code SnapshotComplete} when all
 * nodes have responded or when the timeout fires.
 *
 * <p>Supports scheduled, on-demand, and pre-failover snapshots.
 *
 * <h2>Activation</h2>
 *
 * <p>The application has the following activation dependencies:
 * <ul>
 *     <li>the message publisher is ready to publish
 * </ul>
 *
 * <p>On activation, the application will:
 * <ul>
 *     <li>schedule periodic snapshots at the configured interval
 *     <li>set itself as ready
 * </ul>
 *
 * <p>On deactivation, the application will:
 * <ul>
 *     <li>cancel scheduled and timeout tasks
 *     <li>set itself as not ready
 * </ul>
 */
public class SnapshotCoordinator implements Activatable, Encodable {

    private static final byte VALIDITY_VALID = 1;
    private static final byte VALIDITY_TIMED_OUT = 3;

    private final Log log;
    private final Time time;
    private final Scheduler scheduler;
    private final Provider provider;
    private final List<Snapshottable> registeredNodes;
    private final Map<Short, ChunkTracker> nodeTrackers;
    @Directory(path = ".")
    private final Activator activator;

    @Property(write = true)
    private long intervalNanos = 300_000_000_000L;
    @Property(write = true)
    private long timeoutNanos = 30_000_000_000L;
    private long currentSnapshotId;
    private long checkpointSeqNum;
    private long snapshotStartTime;
    private boolean snapshotInProgress;
    private long completedSnapshots;
    private long lastSnapshotDurationNanos;
    private long scheduledTaskId;
    private long timeoutTaskId;

    /**
     * Creates a {@code SnapshotCoordinator} with the specified parameters.
     *
     * @param time the source of time
     * @param scheduler the scheduler for periodic and timeout tasks
     * @param activatorFactory a factory to create activators
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param busClient the bus client
     * @param applicationName the name of this application
     */
    public SnapshotCoordinator(
            Time time,
            Scheduler scheduler,
            ActivatorFactory activatorFactory,
            LogFactory logFactory,
            MetricFactory metricFactory,
            BusClient<?, ?> busClient,
            String applicationName) {
        Objects.requireNonNull(time, "time is null");
        Objects.requireNonNull(scheduler, "scheduler is null");
        Objects.requireNonNull(activatorFactory, "activatorFactory is null");
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        Objects.requireNonNull(busClient, "busClient is null");
        Objects.requireNonNull(applicationName, "applicationName is null");

        this.time = time;
        this.scheduler = scheduler;
        log = logFactory.create(getClass());
        registeredNodes = new ArrayList<>();
        nodeTrackers = new CoreMap<>();

        provider = busClient.getProvider(applicationName, this);

        busClient.getDispatcher().addListener("snapshotBegin", this::onSnapshotBegin);
        busClient.getDispatcher().addListener("snapshotChunk", this::onSnapshotChunk);

        activator = activatorFactory.createActivator(applicationName, this, provider);

        metricFactory.registerSwitchMetric(
                "Snapshot_InProgress",
                () -> snapshotInProgress);
        metricFactory.registerGaugeMetric(
                "Snapshot_CurrentId",
                () -> currentSnapshotId);
        metricFactory.registerGaugeMetric(
                "Snapshot_CompletedCount",
                () -> completedSnapshots);
        metricFactory.registerGaugeMetric(
                "Snapshot_LastDurationNanos",
                () -> lastSnapshotDurationNanos);
    }

    /**
     * Registers a node to participate in coordinated snapshots.
     *
     * @param node the snapshottable node
     */
    public void register(Snapshottable node) {
        registeredNodes.add(Objects.requireNonNull(node, "node is null"));
    }

    @Override
    public void activate() {
        scheduledTaskId = scheduler.scheduleEvery(
                intervalNanos, this::requestSnapshot, "SnapshotCoordinator:scheduled", 0);
        activator.ready();
    }

    @Override
    public void deactivate() {
        scheduledTaskId = scheduler.cancel(scheduledTaskId);
        timeoutTaskId = scheduler.cancel(timeoutTaskId);
        activator.notReady();
    }

    /**
     * Triggers an on-demand snapshot.
     */
    @Command
    public void snapshot() {
        if (!activator.isActive()) {
            log.warn().append("cannot snapshot: not active").commit();
            return;
        }
        requestSnapshot();
    }

    private void requestSnapshot() {
        if (snapshotInProgress) {
            return;
        }
        currentSnapshotId++;

        var enc = provider.getEncoder("snapshotRequest");
        enc.set("snapshotId", currentSnapshotId);
        enc.set("requestTimestamp", time.nanos());
        enc.set("expectedNodeCount", (short) registeredNodes.size());
        enc.commit().send();

        snapshotInProgress = true;
        snapshotStartTime = time.nanos();

        timeoutTaskId = scheduler.scheduleAt(
                timeoutTaskId, time.nanos() + timeoutNanos,
                this::checkTimeout, "SnapshotCoordinator:timeout", 0);

        log.info().append("snapshot requested: snapshotId=").append(currentSnapshotId)
                .append(", expectedNodes=").append(registeredNodes.size())
                .commit();
    }

    private void onSnapshotBegin(Decoder decoder) {
        var snapshotId = decoder.integerValue("snapshotId");
        if (snapshotId != currentSnapshotId) {
            return;
        }
        checkpointSeqNum = decoder.integerValue("checkpointSeqNum");

        for (var i = 0; i < registeredNodes.size(); i++) {
            var node = registeredNodes.get(i);
            node.onSnapshotRequest(snapshotId, checkpointSeqNum, provider);
        }

        log.info().append("snapshot begin: snapshotId=").append(snapshotId)
                .append(", checkpointSeqNum=").append(checkpointSeqNum)
                .commit();
    }

    private void onSnapshotChunk(Decoder decoder) {
        var snapshotId = decoder.integerValue("snapshotId");
        if (snapshotId != currentSnapshotId) {
            return;
        }

        var nodeId = (short) decoder.integerValue("nodeId");
        var chunkIndex = (int) decoder.integerValue("chunkIndex");
        var totalChunks = (int) decoder.integerValue("totalChunks");

        var tracker = nodeTrackers.get(nodeId);
        if (tracker == null || tracker.totalChunks != totalChunks) {
            tracker = new ChunkTracker(totalChunks);
            nodeTrackers.put(nodeId, tracker);
        }
        tracker.markReceived(chunkIndex);

        checkCompletion();
    }

    private void checkCompletion() {
        if (!snapshotInProgress) {
            return;
        }
        if (nodeTrackers.size() < registeredNodes.size()) {
            return;
        }
        for (var tracker : nodeTrackers.values()) {
            if (!tracker.isComplete()) {
                return;
            }
        }

        log.info().append("snapshot complete: snapshotId=").append(currentSnapshotId)
                .append(", nodes=").append(nodeTrackers.size())
                .commit();
        publishComplete(VALIDITY_VALID);
    }

    private void checkTimeout() {
        if (snapshotInProgress && time.nanos() - snapshotStartTime > timeoutNanos) {
            log.warn().append("snapshot timed out: snapshotId=").append(currentSnapshotId)
                    .append(", receivedNodes=").append(nodeTrackers.size())
                    .append(", expectedNodes=").append(registeredNodes.size())
                    .commit();
            publishComplete(VALIDITY_TIMED_OUT);
        }
    }

    private void publishComplete(byte validity) {
        completedSnapshots++;
        lastSnapshotDurationNanos = time.nanos() - snapshotStartTime;

        var enc = provider.getEncoder("snapshotComplete");
        enc.set("snapshotId", currentSnapshotId);
        enc.set("checkpointSeqNum", checkpointSeqNum);
        enc.set("nodeCount", (short) nodeTrackers.size());
        enc.set("validity", validity);
        enc.set("archiveRecordingId", 0L);
        enc.set("archivePosition", 0L);
        enc.commit().send();

        snapshotInProgress = false;
        nodeTrackers.clear();
        timeoutTaskId = scheduler.cancel(timeoutTaskId);
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("snapshotInProgress").bool(snapshotInProgress)
                .string("currentSnapshotId").number(currentSnapshotId)
                .string("registeredNodes").number(registeredNodes.size())
                .string("intervalNanos").number(intervalNanos)
                .string("timeoutNanos").number(timeoutNanos)
                .string("completedSnapshots").number(completedSnapshots)
                .string("lastSnapshotDurationNanos").number(lastSnapshotDurationNanos)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    private static class ChunkTracker {

        private final boolean[] received;
        final int totalChunks;

        ChunkTracker(int totalChunks) {
            this.totalChunks = totalChunks;
            received = new boolean[totalChunks];
        }

        void markReceived(int chunkIndex) {
            if (chunkIndex >= 0 && chunkIndex < received.length) {
                received[chunkIndex] = true;
            }
        }

        boolean isComplete() {
            for (var r : received) {
                if (!r) {
                    return false;
                }
            }
            return true;
        }
    }
}
