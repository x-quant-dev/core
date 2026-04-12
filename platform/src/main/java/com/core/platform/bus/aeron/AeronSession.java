package com.core.platform.bus.aeron;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import org.agrona.DirectBuffer;

import java.util.Objects;

/**
 * Tracks the Aeron session name and next sequence number.
 */
final class AeronSession implements Encodable {

    private DirectBuffer sessionName;
    private String sessionNameString;
    private long nextSequenceNumber;

    AeronSession() {
        nextSequenceNumber = 1;
    }

    void setSessionName(DirectBuffer sessionName) {
        this.sessionName = Objects.requireNonNull(sessionName, "sessionName is null");
        sessionNameString = BufferUtils.toAsciiString(sessionName);
    }

    @Command(readOnly = true)
    public DirectBuffer getSessionName() {
        return sessionName;
    }

    public String getSessionNameAsString() {
        return sessionNameString;
    }

    @Command(readOnly = true)
    public long getNextSequenceNumber() {
        return nextSequenceNumber;
    }

    void setNextSequenceNumber(long nextSequenceNumber) {
        this.nextSequenceNumber = nextSequenceNumber;
    }

    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("sessionName").string(sessionName)
                .string("nextSeqNum").number(nextSequenceNumber)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
