package com.core.platform.applications.snapshot;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import io.aeron.archive.client.AeronArchive;

import java.util.Objects;

/**
 * Reads snapshot chunks from an Archive replay and restores node state.
 *
 * <p>Used during node recovery to load the latest valid snapshot before replaying deltas.
 */
public class SnapshotRecovery implements Encodable {

    private final Log log;

    @Property
    private long lastRecoveredSnapshotId = -1;
    @Property
    private long lastRecoveredCheckpointSeqNum = -1;
    @Property
    private long recoveryCount;

    /**
     * Creates a {@code SnapshotRecovery}.
     *
     * @param logFactory the log factory
     */
    public SnapshotRecovery(LogFactory logFactory) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        log = logFactory.create(getClass());
    }

    /**
     * Finds the latest valid snapshot and restores state for the specified node.
     *
     * <p>This simplified version is intended for use when a snapshot index has been populated during normal event
     * stream processing.  It looks up the latest valid snapshot entry and returns the checkpoint sequence number
     * so the caller can replay deltas from that point.
     *
     * @param latestSnapshotId the latest valid snapshot ID, or -1 if none
     * @param latestCheckpointSeqNum the checkpoint sequence number of the latest valid snapshot
     * @param nodeId this node's ID (to filter snapshot chunks)
     * @param snapshottable the node to restore
     * @return the checkpointSeqNum to replay from, or -1 if no snapshot found
     */
    public long recover(long latestSnapshotId, long latestCheckpointSeqNum, short nodeId,
                        Snapshottable snapshottable) {
        if (latestSnapshotId < 0) {
            log.info().append("no valid snapshot found for recovery").commit();
            return -1;
        }

        log.info().append("found snapshot for recovery: snapshotId=").append(latestSnapshotId)
                .append(", checkpointSeqNum=").append(latestCheckpointSeqNum)
                .commit();

        lastRecoveredSnapshotId = latestSnapshotId;
        lastRecoveredCheckpointSeqNum = latestCheckpointSeqNum;
        recoveryCount++;

        return latestCheckpointSeqNum;
    }

    /**
     * Recovers using a populated {@link SnapshotIndex}.
     *
     * <p>Finds the latest valid snapshot entry in the index and returns the checkpoint sequence number
     * so the caller can replay deltas from that point.  This is the preferred recovery path when
     * the {@code SnapshotIndex} has been populated from the event stream.
     *
     * @param snapshotIndex the snapshot index to query
     * @param nodeId this node's ID (to filter snapshot chunks)
     * @param snapshottable the node to restore
     * @return the checkpointSeqNum to replay from, or -1 if no snapshot found
     */
    public long recoverFromIndex(SnapshotIndex snapshotIndex, short nodeId, Snapshottable snapshottable) {
        Objects.requireNonNull(snapshotIndex, "snapshotIndex is null");

        var latest = snapshotIndex.getLatest();
        if (latest == null) {
            log.info().append("no valid snapshot in index for recovery").commit();
            return -1;
        }

        log.info().append("recovering from snapshot index: snapshotId=").append(latest.getSnapshotId())
                .append(", checkpointSeqNum=").append(latest.getCheckpointSeqNum())
                .append(", nodeId=").append(nodeId)
                .commit();

        lastRecoveredSnapshotId = latest.getSnapshotId();
        lastRecoveredCheckpointSeqNum = latest.getCheckpointSeqNum();
        recoveryCount++;

        return latest.getCheckpointSeqNum();
    }

    /**
     * Recovers from an Archive recording by finding and replaying the latest valid snapshot.
     * This method is for use during cold start when the snapshot index is empty.
     *
     * @param archive connected AeronArchive client, or null if archive is unavailable
     * @param recordingId the recording to search
     * @param nodeId this node's ID
     * @param snapshottable the node to restore
     * @return the checkpointSeqNum, or -1 if no snapshot found
     */
    public long recoverFromArchive(AeronArchive archive, long recordingId, short nodeId,
                                   Snapshottable snapshottable) {
        if (archive == null) {
            log.info().append("archive is null, cannot recover from archive").commit();
            return -1;
        }

        // TODO: Phase 3 Step 7 full implementation
        // 1. Replay archive backwards to find SnapshotComplete { validity=VALID }
        // 2. Replay from snapshot position, collect chunks for this nodeId
        // 3. Call snapshottable.onSnapshotRestore() for each chunk
        log.info().append("archive-based recovery not yet implemented, returning -1").commit();
        return -1;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("status").string("ready")
                .string("lastRecoveredSnapshotId").number(lastRecoveredSnapshotId)
                .string("lastRecoveredCheckpointSeqNum").number(lastRecoveredCheckpointSeqNum)
                .string("recoveryCount").number(recoveryCount)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
