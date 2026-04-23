package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.platform.activation.Activatable;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.archive.client.AeronArchive;

import java.util.Objects;

/**
 * Runs on standby nodes to continuously replicate the primary Archive's recording to a local Archive via Aeron
 * Archive's replication protocol.
 *
 * <p>On activation, an embedded local {@code Archive} is launched, a client is connected to both the local and primary
 * archives, and replication is started from the primary recording.
 * On deactivation, replication is stopped and all resources are closed.
 */
public class ArchiveReplicator implements Activatable, Encodable {

    private final Log log;
    private final AeronDirectoryProvider directoryProvider;
    private final String localArchiveDir;
    private final String localControlChannel;
    private final int localControlStreamId;
    private final String eventChannel;
    private final int eventStreamId;
    @Directory(path = ".")
    private final Activator activator;

    @Property(write = true)
    private String primaryControlChannel;
    @Property(write = true)
    private int primaryControlStreamId;

    private Archive localArchive;
    private AeronArchive localAeronArchive;
    private AeronArchive sourceAeronArchive;
    private long localRecordingId;
    private long replicationId;
    private boolean replicating;

    /**
     * Creates an {@code ArchiveReplicator}.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory a factory to create activators
     * @param directoryProvider provides the Aeron directory for the archive context
     * @param localArchiveDir directory for the local standby archive segment files
     * @param localControlChannel local archive control channel
     * @param localControlStreamId local archive control stream ID
     * @param primaryControlChannel primary archive control channel
     * @param primaryControlStreamId primary archive control stream ID
     * @param eventChannel the event channel being recorded
     * @param eventStreamId the event stream ID
     */
    public ArchiveReplicator(
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            AeronDirectoryProvider directoryProvider,
            String localArchiveDir,
            String localControlChannel,
            int localControlStreamId,
            String primaryControlChannel,
            int primaryControlStreamId,
            String eventChannel,
            int eventStreamId) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        Objects.requireNonNull(activatorFactory, "activatorFactory is null");
        this.directoryProvider = Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        this.localArchiveDir = Objects.requireNonNull(localArchiveDir, "localArchiveDir is null");
        this.localControlChannel = Objects.requireNonNull(localControlChannel, "localControlChannel is null");
        this.localControlStreamId = localControlStreamId;
        this.primaryControlChannel = Objects.requireNonNull(primaryControlChannel, "primaryControlChannel is null");
        this.primaryControlStreamId = primaryControlStreamId;
        this.eventChannel = Objects.requireNonNull(eventChannel, "eventChannel is null");
        this.eventStreamId = eventStreamId;

        log = logFactory.create(getClass());

        activator = activatorFactory.createActivator("ArchiveReplicator", this);

        metricFactory.registerSwitchMetric(
                "Archive_Replication_Active",
                this::isReplicating,
                "localArchiveDir", localArchiveDir);
        metricFactory.registerGaugeMetric(
                "Archive_Replication_LocalRecordingId",
                this::getLocalRecordingId,
                "localArchiveDir", localArchiveDir);
    }

    @Override
    public void activate() {
        try {
            log.info().append("launching local archive: localArchiveDir=").append(localArchiveDir)
                    .append(", localControlChannel=").append(localControlChannel)
                    .append(", localControlStreamId=").append(localControlStreamId)
                    .commit();

            var archiveContext = new Archive.Context()
                    .archiveDirectoryName(localArchiveDir)
                    .controlChannel(localControlChannel)
                    .controlStreamId(localControlStreamId)
                    .aeronDirectoryName(directoryProvider.getAeronDirectory());
            localArchive = Archive.launch(archiveContext);

            var localClientContext = new AeronArchive.Context()
                    .controlRequestChannel(localControlChannel)
                    .controlResponseChannel(localControlChannel)
                    .aeronDirectoryName(directoryProvider.getAeronDirectory());
            localAeronArchive = AeronArchive.connect(localClientContext);

            var sourceClientContext = new AeronArchive.Context()
                    .controlRequestChannel(primaryControlChannel)
                    .controlResponseChannel(primaryControlChannel)
                    .aeronDirectoryName(directoryProvider.getAeronDirectory());
            sourceAeronArchive = AeronArchive.connect(sourceClientContext);

            var srcRecordingId = sourceAeronArchive.findLastMatchingRecording(
                    0, eventChannel, eventStreamId, Aeron.NULL_VALUE);
            if (srcRecordingId == Aeron.NULL_VALUE) {
                log.warn().append("no matching recording found on primary: eventChannel=").append(eventChannel)
                        .append(", eventStreamId=").append(eventStreamId)
                        .commit();
                closeResources();
                activator.notReady("no matching recording on primary");
                return;
            }

            log.info().append("starting replication: srcRecordingId=").append(srcRecordingId)
                    .append(", primaryControlChannel=").append(primaryControlChannel)
                    .commit();

            replicationId = localAeronArchive.replicate(
                    srcRecordingId,
                    Aeron.NULL_VALUE,
                    primaryControlStreamId,
                    primaryControlChannel,
                    eventChannel);

            localRecordingId = localAeronArchive.findLastMatchingRecording(
                    0, eventChannel, eventStreamId, Aeron.NULL_VALUE);
            replicating = true;

            log.info().append("replication started: localRecordingId=").append(localRecordingId)
                    .append(", replicationId=").append(replicationId)
                    .commit();

            activator.ready();
        } catch (Exception e) {
            log.warn().append("failed to activate replicator: ").append(e).commit();
            closeResources();
            activator.notReady("failed to activate: " + e.getMessage());
        }
    }

    @Override
    public void deactivate() {
        log.info().append("deactivating replicator").commit();
        closeResources();
        activator.notReady();
    }

    /**
     * Returns the local recording ID.
     *
     * @return the local recording ID
     */
    public long getLocalRecordingId() {
        return localRecordingId;
    }

    /**
     * Returns the local control channel.
     *
     * @return the local control channel
     */
    public String getLocalControlChannel() {
        return localControlChannel;
    }

    /**
     * Returns whether replication is active.
     *
     * @return true if replication is active
     */
    public boolean isReplicating() {
        return replicating;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("localArchiveDir").string(localArchiveDir)
                .string("localControlChannel").string(localControlChannel)
                .string("primaryControlChannel").string(primaryControlChannel)
                .string("replicating").bool(replicating)
                .string("localRecordingId").number(localRecordingId)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    private void closeResources() {
        if (replicating && localAeronArchive != null) {
            try {
                localAeronArchive.stopReplication(replicationId);
            } catch (Exception e) {
                log.warn().append("error stopping replication: ").append(e).commit();
            }
            replicating = false;
        }
        if (sourceAeronArchive != null) {
            try {
                sourceAeronArchive.close();
            } catch (Exception e) {
                log.warn().append("error closing source AeronArchive client: ").append(e).commit();
            }
            sourceAeronArchive = null;
        }
        if (localAeronArchive != null) {
            try {
                localAeronArchive.close();
            } catch (Exception e) {
                log.warn().append("error closing local AeronArchive client: ").append(e).commit();
            }
            localAeronArchive = null;
        }
        if (localArchive != null) {
            try {
                localArchive.close();
            } catch (Exception e) {
                log.warn().append("error closing embedded local Archive: ").append(e).commit();
            }
            localArchive = null;
        }
    }
}
