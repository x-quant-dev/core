package com.core.platform.applications.snapshot;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.Time;

import java.util.Objects;

/**
 * Orchestrates the late-joiner recovery flow: snapshot restore &rarr; delta replay &rarr; live.
 *
 * <p>Recovery phases:
 * <ol>
 *     <li><b>IDLE</b> &mdash; not recovering
 *     <li><b>SNAPSHOT_LOOKUP</b> &mdash; finding the latest valid snapshot from the SnapshotIndex
 *     <li><b>SNAPSHOT_RESTORE</b> &mdash; restoring state from the snapshot via SnapshotRecovery
 *     <li><b>DELTA_REPLAY</b> &mdash; replaying events from checkpointSeqNum to current position
 *     <li><b>LIVE</b> &mdash; fully caught up, processing live events
 * </ol>
 *
 * <p>This service is designed for use by backup sequencers or late-joining application nodes
 * that need to reconstruct state before going active.
 */
public class LateJoinerService implements Encodable {

    /**
     * The recovery phase.
     */
    public enum Phase {
        IDLE,
        SNAPSHOT_LOOKUP,
        SNAPSHOT_RESTORE,
        DELTA_REPLAY,
        LIVE
    }

    private final Log log;
    private final Time time;
    private final SnapshotIndex snapshotIndex;
    private final SnapshotRecovery snapshotRecovery;

    @Property
    private Phase currentPhase;
    @Property
    private long recoveredCheckpointSeqNum;
    @Property
    private long eventsReplayed;
    @Property
    private long recoveryCount;
    @Property
    private long lastRecoveryDurationNanos;
    private long recoveryStartNanos;

    /**
     * Creates a {@code LateJoinerService}.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param time the source of time
     * @param snapshotIndex the snapshot index for looking up the latest valid snapshot
     * @param snapshotRecovery the snapshot recovery service for restoring state
     */
    public LateJoinerService(
            LogFactory logFactory,
            MetricFactory metricFactory,
            Time time,
            SnapshotIndex snapshotIndex,
            SnapshotRecovery snapshotRecovery) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.time = Objects.requireNonNull(time, "time is null");
        this.snapshotIndex = Objects.requireNonNull(snapshotIndex, "snapshotIndex is null");
        this.snapshotRecovery = Objects.requireNonNull(snapshotRecovery, "snapshotRecovery is null");

        log = logFactory.create(getClass());
        currentPhase = Phase.IDLE;
        recoveredCheckpointSeqNum = -1;

        metricFactory.registerGaugeMetric("LateJoiner_Phase", () -> (long) currentPhase.ordinal());
        metricFactory.registerGaugeMetric("LateJoiner_EventsReplayed", () -> eventsReplayed);
        metricFactory.registerGaugeMetric("LateJoiner_RecoveryCount", () -> recoveryCount);
        metricFactory.registerGaugeMetric("LateJoiner_RecoveredCheckpointSeqNum", () -> recoveredCheckpointSeqNum);
    }

    /**
     * Initiates recovery for the specified node.
     * Returns the checkpoint sequence number to replay from, or -1 if no snapshot found.
     *
     * @param nodeId the node ID to recover
     * @param snapshottable the snapshottable node to restore
     * @return the checkpoint sequence number to replay from, or -1 if recovery is already in progress
     */
    @Command
    public long recover(short nodeId, Snapshottable snapshottable) {
        if (currentPhase != Phase.IDLE && currentPhase != Phase.LIVE) {
            log.warn().append("recovery already in progress: phase=").append(currentPhase.name()).commit();
            return -1;
        }

        recoveryStartNanos = time.nanos();
        eventsReplayed = 0;
        currentPhase = Phase.SNAPSHOT_LOOKUP;
        log.info().append("late-joiner recovery starting: nodeId=").append(nodeId).commit();

        var latest = snapshotIndex.getLatest();
        if (latest == null) {
            log.info().append("no snapshot available, starting from beginning").commit();
            currentPhase = Phase.DELTA_REPLAY;
            recoveredCheckpointSeqNum = 0;
            return 0;
        }

        currentPhase = Phase.SNAPSHOT_RESTORE;
        recoveredCheckpointSeqNum = snapshotRecovery.recover(
                latest.getSnapshotId(), latest.getCheckpointSeqNum(),
                nodeId, snapshottable);

        if (recoveredCheckpointSeqNum < 0) {
            log.warn().append("snapshot restore failed, starting from beginning").commit();
            currentPhase = Phase.DELTA_REPLAY;
            recoveredCheckpointSeqNum = 0;
            return 0;
        }

        currentPhase = Phase.DELTA_REPLAY;
        log.info().append("snapshot restored: checkpointSeqNum=").append(recoveredCheckpointSeqNum).commit();
        return recoveredCheckpointSeqNum;
    }

    /**
     * Called for each replayed delta event. Tracks progress.
     */
    public void onDeltaReplayed() {
        eventsReplayed++;
    }

    /**
     * Marks recovery as complete &mdash; node is now processing live events.
     */
    @Command
    public void goLive() {
        if (currentPhase == Phase.DELTA_REPLAY) {
            currentPhase = Phase.LIVE;
            recoveryCount++;
            lastRecoveryDurationNanos = time.nanos() - recoveryStartNanos;
            log.info().append("late-joiner is live: eventsReplayed=").append(eventsReplayed)
                    .append(", durationNanos=").append(lastRecoveryDurationNanos).commit();
        }
    }

    /**
     * Resets the service to IDLE state.
     */
    @Command
    public void reset() {
        currentPhase = Phase.IDLE;
        eventsReplayed = 0;
        recoveredCheckpointSeqNum = -1;
    }

    /**
     * Returns the current recovery phase.
     *
     * @return the current phase
     */
    public Phase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Returns the checkpoint sequence number recovered from the latest snapshot.
     *
     * @return the recovered checkpoint sequence number, or -1 if not recovered
     */
    public long getRecoveredCheckpointSeqNum() {
        return recoveredCheckpointSeqNum;
    }

    /**
     * Returns the number of delta events replayed during recovery.
     *
     * @return the number of events replayed
     */
    public long getEventsReplayed() {
        return eventsReplayed;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("currentPhase").string(currentPhase.name())
                .string("recoveredCheckpointSeqNum").number(recoveredCheckpointSeqNum)
                .string("eventsReplayed").number(eventsReplayed)
                .string("recoveryCount").number(recoveryCount)
                .string("lastRecoveryDurationNanos").number(lastRecoveryDurationNanos)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
