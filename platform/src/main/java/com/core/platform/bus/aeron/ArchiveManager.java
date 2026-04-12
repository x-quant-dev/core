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
import io.aeron.archive.codecs.SourceLocation;

import java.util.Objects;

/**
 * Manages an embedded Aeron Archive and recording of the event stream.
 *
 * <p>On activation, an embedded {@code Archive} is launched in the same JVM and an {@code AeronArchive} client is
 * connected to start a LOCAL recording of the event stream.
 * On deactivation, the recording is stopped and the archive is closed.
 */
public class ArchiveManager implements Activatable, Encodable {

    private final Log log;
    private final AeronDirectoryProvider directoryProvider;
    private final String archiveDir;
    private final String eventChannel;
    private final int eventStreamId;
    @Directory(path = ".")
    private final Activator activator;

    @Property(write = true)
    private String controlChannel;
    @Property(write = true)
    private int controlStreamId;

    private Archive archive;
    private AeronArchive aeronArchive;
    private long recordingId;
    private boolean recording;

    /**
     * Creates an {@code ArchiveManager}.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory a factory to create activators
     * @param directoryProvider provides the Aeron directory for the archive context
     * @param archiveDir directory path for archive segment files
     * @param controlChannel archive control channel
     * @param controlStreamId archive control stream ID
     * @param eventChannel the event channel to record
     * @param eventStreamId the event stream ID to record
     */
    public ArchiveManager(
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            AeronDirectoryProvider directoryProvider,
            String archiveDir,
            String controlChannel,
            int controlStreamId,
            String eventChannel,
            int eventStreamId) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        Objects.requireNonNull(activatorFactory, "activatorFactory is null");
        this.directoryProvider = Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        this.archiveDir = Objects.requireNonNull(archiveDir, "archiveDir is null");
        this.controlChannel = Objects.requireNonNull(controlChannel, "controlChannel is null");
        this.controlStreamId = controlStreamId;
        this.eventChannel = Objects.requireNonNull(eventChannel, "eventChannel is null");
        this.eventStreamId = eventStreamId;

        log = logFactory.create(getClass());

        activator = activatorFactory.createActivator("ArchiveManager", this);

        metricFactory.registerSwitchMetric(
                "Archive_Recording_Active",
                this::isRecording,
                "archiveDir", archiveDir);
        metricFactory.registerGaugeMetric(
                "Archive_Recording_Id",
                this::getRecordingId,
                "archiveDir", archiveDir);
        metricFactory.registerGaugeMetric(
                "Archive_Recording_Position",
                this::getRecordingPosition,
                "archiveDir", archiveDir);
    }

    @Override
    public void activate() {
        try {
            log.info().append("launching embedded archive: archiveDir=").append(archiveDir)
                    .append(", controlChannel=").append(controlChannel)
                    .append(", controlStreamId=").append(controlStreamId)
                    .commit();

            var archiveContext = new Archive.Context()
                    .archiveDirectoryName(archiveDir)
                    .controlChannel(controlChannel)
                    .controlStreamId(controlStreamId)
                    .replicationChannel("aeron:udp?endpoint=localhost:0")
                    .aeronDirectoryName(directoryProvider.getAeronDirectory());
            archive = Archive.launch(archiveContext);

            var archiveClientContext = new AeronArchive.Context()
                    .controlRequestChannel(controlChannel)
                    .controlRequestStreamId(controlStreamId)
                    .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                    .aeronDirectoryName(directoryProvider.getAeronDirectory());
            aeronArchive = AeronArchive.connect(archiveClientContext);

            aeronArchive.startRecording(eventChannel, eventStreamId, SourceLocation.LOCAL);
            recordingId = aeronArchive.findLastMatchingRecording(
                    0, eventChannel, eventStreamId, Aeron.NULL_VALUE);
            recording = true;

            log.info().append("recording started: recordingId=").append(recordingId)
                    .append(", eventChannel=").append(eventChannel)
                    .append(", eventStreamId=").append(eventStreamId)
                    .commit();

            activator.ready();
        } catch (Exception e) {
            log.warn().append("failed to activate archive: ").append(e).commit();
            closeResources();
            activator.notReady("failed to activate: " + e.getMessage());
        }
    }

    @Override
    public void deactivate() {
        log.info().append("deactivating archive").commit();
        closeResources();
        activator.notReady();
    }

    /**
     * Returns the current recording ID.
     *
     * @return the current recording ID
     */
    public long getRecordingId() {
        return recordingId;
    }

    /**
     * Returns the archive control channel.
     *
     * @return the control channel
     */
    public String getControlChannel() {
        return controlChannel;
    }

    /**
     * Returns true if recording is active.
     *
     * @return true if recording is active
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Returns the current recording position, or -1 if not recording.
     *
     * @return the recording position
     */
    public long getRecordingPosition() {
        if (recording && aeronArchive != null) {
            try {
                return aeronArchive.getRecordingPosition(recordingId);
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("archiveDir").string(archiveDir)
                .string("controlChannel").string(controlChannel)
                .string("controlStreamId").number(controlStreamId)
                .string("eventChannel").string(eventChannel)
                .string("eventStreamId").number(eventStreamId)
                .string("recording").bool(recording)
                .string("recordingId").number(recordingId)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    private void closeResources() {
        if (recording && aeronArchive != null) {
            try {
                aeronArchive.stopRecording(eventChannel, eventStreamId);
            } catch (Exception e) {
                log.warn().append("error stopping recording: ").append(e).commit();
            }
            recording = false;
        }
        if (aeronArchive != null) {
            try {
                aeronArchive.close();
            } catch (Exception e) {
                log.warn().append("error closing AeronArchive client: ").append(e).commit();
            }
            aeronArchive = null;
        }
        if (archive != null) {
            try {
                archive.close();
            } catch (Exception e) {
                log.warn().append("error closing embedded Archive: ").append(e).commit();
            }
            archive = null;
        }
    }
}
