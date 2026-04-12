package com.core.platform.applications.snapshot;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.platform.bus.BusClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Maintains an in-memory index of valid snapshots, built from the event stream.
 *
 * <p>Listens for {@code SnapshotComplete} events and tracks valid entries.
 * Provides lookup for the latest valid snapshot for recovery.
 */
public class SnapshotIndex implements Encodable {

    private final List<SnapshotEntry> entries;

    /**
     * Creates a {@code SnapshotIndex} that listens for {@code SnapshotComplete} events on the bus.
     *
     * @param busClient the bus client to register the listener with
     * @param metricFactory a factory to create metrics
     */
    public SnapshotIndex(BusClient<?, ?> busClient, MetricFactory metricFactory) {
        Objects.requireNonNull(busClient, "busClient is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");

        entries = new ArrayList<>();
        busClient.getDispatcher().addListener("snapshotComplete", this::onSnapshotComplete);

        metricFactory.registerGaugeMetric(
                "Snapshot_ValidCount",
                this::size);
        metricFactory.registerGaugeMetric(
                "Snapshot_LatestCheckpointSeqNum",
                () -> {
                    var latest = getLatest();
                    return latest != null ? latest.getCheckpointSeqNum() : -1;
                });
    }

    private void onSnapshotComplete(Decoder decoder) {
        var validity = decoder.get("validity").toString();
        if ("VALID".equals(validity)) {
            var snapshotId = decoder.integerValue("snapshotId");
            var checkpointSeqNum = decoder.integerValue("checkpointSeqNum");
            var archiveRecordingId = decoder.integerValue("archiveRecordingId");
            var archivePosition = decoder.integerValue("archivePosition");
            entries.add(new SnapshotEntry(snapshotId, checkpointSeqNum, archiveRecordingId, archivePosition));
        }
    }

    /**
     * Returns the latest valid snapshot entry, or null if no valid snapshots exist.
     *
     * @return the latest snapshot entry, or null
     */
    public SnapshotEntry getLatest() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    /**
     * Returns the number of valid snapshot entries.
     *
     * @return the number of entries
     */
    public int size() {
        return entries.size();
    }

    /**
     * Removes all entries with a snapshot ID older than the specified ID.
     *
     * @param snapshotId the snapshot ID threshold
     */
    public void removeOlderThan(long snapshotId) {
        entries.removeIf(entry -> entry.snapshotId < snapshotId);
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("validSnapshots").number(entries.size());
        if (!entries.isEmpty()) {
            var latest = entries.get(entries.size() - 1);
            encoder.string("latestSnapshotId").number(latest.getSnapshotId())
                    .string("latestCheckpointSeqNum").number(latest.getCheckpointSeqNum())
                    .string("latestArchiveRecordingId").number(latest.getArchiveRecordingId())
                    .string("latestArchivePosition").number(latest.getArchivePosition());
        }
        encoder.closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    /**
     * A record of a valid snapshot with its associated checkpoint and archive location.
     */
    public static class SnapshotEntry {

        private final long snapshotId;
        private final long checkpointSeqNum;
        private final long archiveRecordingId;
        private final long archivePosition;

        SnapshotEntry(long snapshotId, long checkpointSeqNum, long archiveRecordingId, long archivePosition) {
            this.snapshotId = snapshotId;
            this.checkpointSeqNum = checkpointSeqNum;
            this.archiveRecordingId = archiveRecordingId;
            this.archivePosition = archivePosition;
        }

        /**
         * Returns the snapshot ID.
         *
         * @return the snapshot ID
         */
        public long getSnapshotId() {
            return snapshotId;
        }

        /**
         * Returns the checkpoint sequence number.
         *
         * @return the checkpoint sequence number
         */
        public long getCheckpointSeqNum() {
            return checkpointSeqNum;
        }

        /**
         * Returns the archive recording ID.
         *
         * @return the archive recording ID
         */
        public long getArchiveRecordingId() {
            return archiveRecordingId;
        }

        /**
         * Returns the archive position.
         *
         * @return the archive position
         */
        public long getArchivePosition() {
            return archivePosition;
        }
    }
}
