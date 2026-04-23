package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ReplayMerge;
import io.aeron.logbuffer.FragmentHandler;

import java.util.Objects;

/** Client for replaying archived Aeron streams during recovery. */
public class ArchiveReplayClient implements Encodable {

    private final Log log;
    private final AeronDirectoryProvider directoryProvider;
    private final ArchiveLocator archiveLocator;
    private final String eventChannel;
    private final int eventStreamId;
    @Directory(path = ".")
    private final Activator activator;

    private AeronArchive archive;
    private ReplayMerge replayMerge;
    private boolean merged;
    private boolean recovering;
    private long fragmentsPolled;

    /**
     * Creates an {@code ArchiveReplayClient} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param directoryProvider the Aeron directory provider
     * @param archiveLocator the archive locator
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     */
    public ArchiveReplayClient(
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            AeronDirectoryProvider directoryProvider,
            ArchiveLocator archiveLocator,
            String eventChannel,
            int eventStreamId) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        Objects.requireNonNull(activatorFactory, "activatorFactory is null");
        this.directoryProvider = Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        this.archiveLocator = Objects.requireNonNull(archiveLocator, "archiveLocator is null");
        this.eventChannel = Objects.requireNonNull(eventChannel, "eventChannel is null");
        this.eventStreamId = eventStreamId;

        log = logFactory.create(getClass());
        activator = activatorFactory.createActivator("ArchiveReplayClient", this);

        metricFactory.registerSwitchMetric(
                "Archive_Recovery_InProgress",
                this::isRecovering,
                "eventChannel", eventChannel);
        metricFactory.registerSwitchMetric(
                "Archive_Recovery_Merged",
                this::isMerged,
                "eventChannel", eventChannel);
        metricFactory.registerGaugeMetric(
                "Archive_Recovery_FragmentsPolled",
                () -> fragmentsPolled,
                "eventChannel", eventChannel);
    }

    /**
     * Starts recovery replay from the specified position.
     *
     * @param fromPosition the position to replay from
     */
    public void recover(long fromPosition) {
        if (recovering) {
            log.warn().append("recovery already in progress").commit();
            return;
        }

        var controlChannel = archiveLocator.getControlChannel();
        if (controlChannel == null) {
            log.warn().append("no archive discovered, cannot recover").commit();
            return;
        }

        var archiveContext = new AeronArchive.Context()
                .controlRequestChannel(controlChannel)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                .aeronDirectoryName(directoryProvider.getAeronDirectory());
        archive = AeronArchive.connect(archiveContext);

        var aeron = archive.context().aeron();
        var subscription = aeron.addSubscription(
                "aeron:udp?control-mode=manual", eventStreamId);

        var replayChannel = "aeron:udp?endpoint=0.0.0.0:0";
        var replayDestination = "aeron:udp?endpoint=0.0.0.0:0";
        var liveDestination = eventChannel;

        replayMerge = new ReplayMerge(
                subscription,
                archive,
                replayChannel,
                replayDestination,
                liveDestination,
                archiveLocator.getRecordingId(),
                fromPosition);

        recovering = true;
        merged = false;

        log.info().append("recovery started: fromPosition=").append(fromPosition)
                .append(", recordingId=").append(archiveLocator.getRecordingId())
                .append(", controlChannel=").append(controlChannel)
                .commit();
    }

    /**
     * Polls the replay merge for fragments.
     *
     * @param handler the fragment handler
     * @param fragmentLimit the maximum fragments to poll
     * @return the number of fragments polled
     */
    public int poll(FragmentHandler handler, int fragmentLimit) {
        if (!recovering || merged) {
            return 0;
        }

        var work = replayMerge.poll(handler, fragmentLimit);
        fragmentsPolled += work;

        if (replayMerge.isMerged()) {
            merged = true;
            recovering = false;
            log.info().append("replay merge complete").commit();
            closeReplayResources();
            activator.ready();
        }

        return work;
    }

    public boolean isMerged() {
        return merged;
    }

    public boolean isRecovering() {
        return recovering;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("recovering").bool(recovering)
                .string("merged").bool(merged)
                .string("eventChannel").string(eventChannel)
                .string("eventStreamId").number(eventStreamId)
                .string("fragmentsPolled").number(fragmentsPolled)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    private void closeReplayResources() {
        if (replayMerge != null) {
            try {
                replayMerge.close();
            } catch (Exception e) {
                log.warn().append("error closing ReplayMerge: ").append(e).commit();
            }
            replayMerge = null;
        }
        if (archive != null) {
            try {
                archive.close();
            } catch (Exception e) {
                log.warn().append("error closing AeronArchive client: ").append(e).commit();
            }
            archive = null;
        }
    }
}
