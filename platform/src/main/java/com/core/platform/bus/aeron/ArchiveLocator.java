package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.messages.Decoder;
import com.core.platform.bus.BusClient;
import org.agrona.DirectBuffer;

import java.util.Objects;

/**
 * Listens to the event stream for {@code ArchiveAnnouncement} events and caches PRIMARY/STANDBY archive endpoints.
 *
 * <p>Any node replaying the event stream automatically learns archive locations, providing deterministic archive
 * discovery without external configuration.
 */
public class ArchiveLocator implements Encodable {

    private String primaryControlChannel;
    private long primaryRecordingId;
    private String standbyControlChannel;
    private long standbyRecordingId;

    /**
     * Creates an {@code ArchiveLocator} that listens for {@code ArchiveAnnouncement} events on the bus.
     *
     * @param busClient the bus client to register the listener with
     */
    public ArchiveLocator(BusClient<?, ?> busClient) {
        Objects.requireNonNull(busClient, "busClient is null");

        busClient.getDispatcher().addListener("archiveAnnouncement", this::onArchiveAnnouncement);
    }

    private void onArchiveAnnouncement(Decoder decoder) {
        var recordingId = decoder.integerValue("recordingId");
        var role = decoder.get("role").toString();

        String controlChannel = null;
        if (decoder.isPresent("controlChannel")) {
            var buf = (DirectBuffer) decoder.get("controlChannel");
            controlChannel = buf.getStringWithoutLengthAscii(0, buf.capacity());
        }

        if ("PRIMARY".equals(role)) {
            primaryControlChannel = controlChannel;
            primaryRecordingId = recordingId;
        } else if ("STANDBY".equals(role)) {
            standbyControlChannel = controlChannel;
            standbyRecordingId = recordingId;
        }
    }

    /**
     * Returns the control channel of the preferred archive.
     * Prefers the primary archive if available, otherwise falls back to the standby.
     *
     * @return the control channel, or null if no archive has been discovered
     */
    public String getControlChannel() {
        if (primaryControlChannel != null) {
            return primaryControlChannel;
        }
        return standbyControlChannel;
    }

    /**
     * Returns the recording ID of the preferred archive.
     * Prefers the primary archive if available, otherwise falls back to the standby.
     *
     * @return the recording ID
     */
    public long getRecordingId() {
        if (primaryControlChannel != null) {
            return primaryRecordingId;
        }
        return standbyRecordingId;
    }

    /**
     * Returns whether a primary archive has been discovered.
     *
     * @return true if a primary archive announcement has been received
     */
    public boolean hasPrimary() {
        return primaryControlChannel != null;
    }

    /**
     * Returns whether a standby archive has been discovered.
     *
     * @return true if a standby archive announcement has been received
     */
    public boolean hasStandby() {
        return standbyControlChannel != null;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("primaryControlChannel").string(primaryControlChannel != null ? primaryControlChannel : "none")
                .string("primaryRecordingId").number(primaryRecordingId)
                .string("standbyControlChannel").string(standbyControlChannel != null ? standbyControlChannel : "none")
                .string("standbyRecordingId").number(standbyRecordingId)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
