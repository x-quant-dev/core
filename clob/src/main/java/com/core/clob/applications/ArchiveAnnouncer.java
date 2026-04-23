package com.core.clob.applications;

import com.core.clob.schema.ArchiveRole;
import com.core.clob.schema.ClobDispatcher;
import com.core.clob.schema.ClobProvider;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.platform.activation.Activatable;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.BusClient;
import com.core.platform.bus.aeron.ArchiveManager;

import java.util.Objects;

/**
 * Publishes {@code ArchiveAnnouncement} commands on the event stream at startup.
 *
 * <p>This application runs on the sequencer/primary node and announces the active archive location
 * to all nodes. It depends on both the {@code BusClient} provider and the {@code ArchiveManager}
 * activators, ensuring that the announcement is only sent after the archive recording has started.
 *
 * <h2>Activation</h2>
 *
 * <p>The application has the following activation dependencies:
 * <ul>
 *     <li>the message publisher is ready to publish
 *     <li>the {@code ArchiveManager} is active (recording has started)
 * </ul>
 *
 * <p>On activation, the application will:
 * <ul>
 *     <li>encode and send an {@code ArchiveAnnouncement} with the archive ID, recording ID, role,
 *         and control channel
 *     <li>set itself as ready
 * </ul>
 *
 * <p>On deactivation, the application will:
 * <ul>
 *     <li>set itself as not ready
 * </ul>
 */
public class ArchiveAnnouncer implements Activatable, Encodable {

    private final Log log;
    private final ClobProvider provider;
    private final ArchiveManager archiveManager;
    private final short archiveId;
    @Directory(path = ".")
    private final Activator activator;

    @Property(write = true)
    private ArchiveRole role;

    /**
     * Creates an {@code ArchiveAnnouncer} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param activatorFactory a factory to create activators
     * @param busClient the bus client
     * @param archiveManager the archive manager providing recording ID and control channel
     * @param applicationName the name of this application
     * @param archiveId the unique ID for this archive node
     */
    public ArchiveAnnouncer(
            LogFactory logFactory,
            ActivatorFactory activatorFactory,
            BusClient<ClobDispatcher, ClobProvider> busClient,
            ArchiveManager archiveManager,
            String applicationName,
            short archiveId) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(activatorFactory, "activatorFactory is null");
        Objects.requireNonNull(busClient, "busClient is null");
        this.archiveManager = Objects.requireNonNull(archiveManager, "archiveManager is null");
        Objects.requireNonNull(applicationName, "applicationName is null");
        this.archiveId = archiveId;

        log = logFactory.create(getClass());
        provider = busClient.getProvider(applicationName, this);
        role = ArchiveRole.PRIMARY;

        activator = activatorFactory.createActivator(applicationName, this, provider, archiveManager);
    }

    @Override
    public void activate() {
        sendAnnouncement();
        activator.ready();
    }

    @Override
    public void deactivate() {
        activator.notReady();
    }

    /**
     * Re-sends the archive announcement.
     */
    @Command
    public void announce() {
        if (!activator.isActive()) {
            log.warn().append("cannot announce: not active").commit();
            return;
        }
        sendAnnouncement();
    }

    private void sendAnnouncement() {
        var encoder = provider.getArchiveAnnouncementEncoder();
        encoder.setArchiveId(archiveId)
                .setRecordingId(archiveManager.getRecordingId())
                .setRole(role)
                .setControlChannel(archiveManager.getControlChannel());
        encoder.commit().send();

        log.info().append("sent ArchiveAnnouncement: archiveId=").append(archiveId)
                .append(", recordingId=").append(archiveManager.getRecordingId())
                .append(", role=").append(role)
                .append(", controlChannel=").append(archiveManager.getControlChannel())
                .commit();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("archiveId").number(archiveId)
                .string("role").string(role.name())
                .string("recordingId").number(archiveManager.getRecordingId())
                .string("controlChannel").string(archiveManager.getControlChannel())
                .string("active").bool(activator.isActive())
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
