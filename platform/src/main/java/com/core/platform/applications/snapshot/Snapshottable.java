package com.core.platform.applications.snapshot;

import com.core.infrastructure.messages.Provider;
import org.agrona.DirectBuffer;

/**
 * Interface for nodes that participate in coordinated snapshots.
 *
 * <p>Implementations serialise their in-memory state into one or more {@code SnapshotChunk} commands when requested,
 * and restore state from chunks during recovery.
 */
public interface Snapshottable {

    /**
     * Returns the node ID that identifies this node's snapshot chunks.
     *
     * @return the node ID
     */
    short nodeId();

    /**
     * Called when a {@code SnapshotBegin} event is received.
     * The implementation should serialise its local state and send {@code SnapshotChunk} commands via the provider.
     *
     * @param snapshotId unique snapshot identifier
     * @param checkpointSeqNum the event sequence number at the checkpoint
     * @param provider the provider to use for sending {@code SnapshotChunk} commands
     */
    void onSnapshotRequest(long snapshotId, long checkpointSeqNum, Provider provider);

    /**
     * Called during recovery to restore state from a snapshot chunk.
     *
     * @param snapshotId the snapshot being restored
     * @param chunkIndex 0-based index of this chunk
     * @param totalChunks total number of chunks for this node
     * @param payload the binary state data
     */
    void onSnapshotRestore(long snapshotId, int chunkIndex, int totalChunks, DirectBuffer payload);
}
