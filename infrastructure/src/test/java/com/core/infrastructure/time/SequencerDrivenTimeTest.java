package com.core.infrastructure.time;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;

public class SequencerDrivenTimeTest {

    private ManualTime wallClock;
    private SequencerDrivenTime liveTime;
    private SequencerDrivenTime replayTime;

    @BeforeEach
    void setUp() {
        wallClock = new ManualTime();
        liveTime = new SequencerDrivenTime(wallClock);
        replayTime = new SequencerDrivenTime();
    }

    @Test
    void replayMode_nanos_returns_zero_before_any_event() {
        then(replayTime.nanos()).isEqualTo(0);
    }

    @Test
    void replayMode_nanos_returns_last_event_timestamp() {
        replayTime.onEventTimestamp(5000);

        then(replayTime.nanos()).isEqualTo(5000);
    }

    @Test
    void replayMode_nanos_tracks_multiple_events() {
        replayTime.onEventTimestamp(1000);
        then(replayTime.nanos()).isEqualTo(1000);

        replayTime.onEventTimestamp(2000);
        then(replayTime.nanos()).isEqualTo(2000);

        replayTime.onEventTimestamp(9999);
        then(replayTime.nanos()).isEqualTo(9999);
    }

    @Test
    void replayMode_updateTime_is_noop() {
        replayTime.updateTime();

        then(replayTime.nanos()).isEqualTo(0);
    }

    @Test
    void liveMode_nanos_returns_wall_clock_adjusted_by_offset() {
        wallClock.setNanos(2000);
        liveTime.onEventTimestamp(1000);
        // offset = wallClock(2000) - event(1000) = 1000

        wallClock.setNanos(3000);
        // nanos = wallClock(3000) - offset(1000) = 2000
        then(liveTime.nanos()).isEqualTo(2000);
    }

    @Test
    void liveMode_offset_recalculated_on_each_event() {
        wallClock.setNanos(2000);
        liveTime.onEventTimestamp(1000);
        // offset = 2000 - 1000 = 1000

        wallClock.setNanos(5000);
        liveTime.onEventTimestamp(4500);
        // offset = 5000 - 4500 = 500

        wallClock.setNanos(6000);
        // nanos = 6000 - 500 = 5500
        then(liveTime.nanos()).isEqualTo(5500);
    }

    @Test
    void liveMode_updateTime_delegates_to_wallClock() {
        wallClock.setNanos(100);
        liveTime.updateTime();

        then(wallClock.nanos()).isEqualTo(100);
    }

    @Test
    void eventsReceived_increments_on_each_event() {
        then(replayTime.getEventsReceived()).isEqualTo(0);

        replayTime.onEventTimestamp(100);
        then(replayTime.getEventsReceived()).isEqualTo(1);

        replayTime.onEventTimestamp(200);
        then(replayTime.getEventsReceived()).isEqualTo(2);

        wallClock.setNanos(1000);
        liveTime.onEventTimestamp(500);
        then(liveTime.getEventsReceived()).isEqualTo(1);

        wallClock.setNanos(2000);
        liveTime.onEventTimestamp(1500);
        then(liveTime.getEventsReceived()).isEqualTo(2);
    }
}
