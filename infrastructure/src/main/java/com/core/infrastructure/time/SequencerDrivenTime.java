package com.core.infrastructure.time;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.EncoderUtils;
import com.core.infrastructure.encoding.ObjectEncoder;

/**
 * A {@code Time} implementation that tracks the sequencer's event timestamps.
 *
 * <p>In live mode, the time source blends the local wall clock with corrections from sequencer event timestamps.
 * Between events, {@code nanos()} advances with the wall clock. On each event, the offset between the wall clock
 * and the sequencer's timestamp is recalculated, correcting drift.
 *
 * <p>In replay mode, {@code nanos()} returns purely the last event timestamp, providing fully deterministic
 * replay identical to {@link ManualTime} but driven by the event stream.
 */
public class SequencerDrivenTime implements Time, Encodable {

    private final Time wallClock;
    private long eventNanos;
    private long offsetNanos;
    private final boolean replayMode;
    private long eventsReceived;

    /**
     * Creates a {@code SequencerDrivenTime} in live mode.
     *
     * <p>Between events, {@code nanos()} returns the wall clock adjusted by the offset calculated at the last
     * event. On each event, the offset is recalculated to correct drift.
     *
     * @param wallClock the underlying wall clock
     */
    public SequencerDrivenTime(Time wallClock) {
        this.wallClock = java.util.Objects.requireNonNull(wallClock, "wallClock is null");
        this.replayMode = false;
    }

    /**
     * Creates a {@code SequencerDrivenTime} in replay mode.
     *
     * <p>In replay mode, {@code nanos()} returns only the last event timestamp.
     * The wall clock is never consulted.
     */
    public SequencerDrivenTime() {
        this.wallClock = null;
        this.replayMode = true;
    }

    /**
     * Called by the bus client on each received event to update the authoritative time.
     *
     * @param timestampNanos the event timestamp in nanoseconds since epoch
     */
    public void onEventTimestamp(long timestampNanos) {
        this.eventNanos = timestampNanos;
        eventsReceived++;
        if (!replayMode) {
            wallClock.updateTime();
            this.offsetNanos = wallClock.nanos() - timestampNanos;
        }
    }

    @Command(readOnly = true)
    @Override
    public long nanos() {
        if (replayMode) {
            return eventNanos;
        }
        return wallClock.nanos() - offsetNanos;
    }

    @Override
    public void updateTime() {
        if (wallClock != null) {
            wallClock.updateTime();
        }
    }

    /**
     * Returns the number of events received.
     *
     * @return the number of events received
     */
    public long getEventsReceived() {
        return eventsReceived;
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("eventNanos").number(eventNanos, EncoderUtils.NANOSECOND_ENCODER)
                .string("offsetNanos").number(offsetNanos)
                .string("eventsReceived").number(eventsReceived)
                .string("mode").string(replayMode ? "REPLAY" : "LIVE")
                .closeMap();
    }
}
