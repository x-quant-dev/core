# Time, Clock Abstraction, and Distributed Time Synchronization

This document covers how time works in the platform today, how it supports backtesting via deterministic replay, and a design for a **TimeLord** component that synchronizes virtual time across distributed nodes.

---

## Current Time Architecture

### The `Time` Interface

All components depend on a single abstraction: `com.core.infrastructure.time.Time`.

```java
public interface Time {
    long nanos();        // nanoseconds since epoch (Jan 1, 1970)
    void updateTime();   // refresh cached time (no-op for some implementations)

    static Time createSystemTime();  // factory: CachedHighPrecisionTime or SystemTime fallback
}
```

### Implementations

| Class | Precision | `updateTime()` | Usage |
|-------|-----------|-----------------|-------|
| `SystemTime` | Millisecond (`System.currentTimeMillis()`) | No-op | Fallback when JVM module access is unavailable |
| `HighPrecisionTime` | Nanosecond (`jdk.internal.misc.VM.getNanoTimeAdjustment`) | No-op | Direct high-precision (not cached) |
| `CachedHighPrecisionTime` | Nanosecond (cached) | Refreshes cached value | **Default live mode.** `EventLoop.run()` calls `updateTime()` once per iteration to refresh the cache |
| `ManualTime` | Nanosecond (settable) | No-op | **Simulation/playback/testing.** Time is controlled externally via `setNanos()` / `advanceTime()` |

### How Time Is Injected

`Main.java` selects the time source at startup:

```java
// In Main.java
var coreFile = System.getProperty("corefile");
var time = coreFile == null ? Time.createSystemTime() : new ManualTime();
```

`Time` is then passed as an implied parameter to the `Shell`, making it available to all components created via command files:
- `EventLoop(Time, Scheduler, Selector)`
- `Scheduler(Time)`
- `BusServer` / `BusClient` constructors
- All applications

### Where Timestamps Are Stamped

Timestamps are written at the **bus server layer** during `commit()`, not by the Sequencer directly:

```java
// In AeronBusServer.commit(int msgLength)
messageBuffer.putLong(schema.getTimestampOffset(), time.nanos());

// In MoldBusServer — same pattern
messageBuffer.putLong(timestampOffset, time.nanos());
```

This means every sequenced event carries the timestamp from the active sequencer node's `Time` instance at the moment of commit.

---

## How Playback / Backtesting Works Today

### Single-Node Deterministic Replay

When a `corefile` system property is set, `Main.java` switches to `ManualTime` and loads the `FilePlayback` component:

```
Main.java (corefile mode):
  1. time = new ManualTime()
  2. EventLoop uses runOnce() — no blocking select, no time.updateTime()
  3. FilePlayback reads messages from corefile
  4. For each message:
     a. Extract timestamp from message header
     b. time.setNanos(timestamp)          ← virtual time advances
     c. Dispatch message through dispatcher
     d. Any handlers that check time.nanos() see the recorded time
     e. Scheduler.fire() triggers tasks based on virtual time
     f. Any events produced by handlers are stamped with virtual time
```

This provides **fully deterministic replay**: all components — scheduler, timeouts, heartbeats, application logic — operate in the recorded time domain. Wall clock is never consulted.

### What Makes It Deterministic

1. **Single `Time` instance** injected everywhere — no component uses `System.currentTimeMillis()` directly.
2. **`ManualTime.setNanos()`** is called before each message dispatch — all code sees consistent time.
3. **`EventLoop.runOnce()`** does not call `time.updateTime()` — prevents accidental wall clock leakage.
4. **`Scheduler.fire()`** uses `time.nanos()` — scheduled tasks fire at virtual time, not real time.

### Limitation: Single Node Only

Current playback is **single-process**. The `FilePlayback` reads from a local corefile and replays within one JVM. There is no mechanism to synchronize virtual time across multiple nodes running in parallel.

---

## The Problem: Distributed Virtual Time

In a multi-node deployment (sequencer + N application nodes), each node has its own `Time` instance. In live mode, they use their local system clock (slightly different across hosts). In backtesting mode, there is no built-in way to coordinate virtual time.

### Why It Matters

| Scenario | Issue Without Synchronized Time |
|----------|-------------------------------|
| **Distributed backtesting** | Each node advances time independently — scheduler-based logic (timeouts, periodic tasks) fires at different wall-clock moments, producing non-deterministic results |
| **Time-based trading logic** | Order expiry, quote timeouts, TWAP schedules on application nodes use local `time.nanos()` — divergence from sequencer time causes incorrect behavior |
| **Metrics and logging** | Timestamps in logs and metrics from different nodes are inconsistent, making debugging impossible |
| **Replay across nodes** | A recovering node replaying from Archive needs to run scheduler tasks at the correct virtual time, not wall clock |

### The Insight

The sequencer's event stream already carries authoritative timestamps. Every event's timestamp field was written by the active bus server's `time.nanos()`. If all follower nodes set their `Time` from the event stream's timestamps, they are **perfectly synchronized to the sequencer's clock** — just like `FilePlayback` synchronizes to the corefile's timestamps.

---

## Design: TimeLord Component

### Concept

The **TimeLord** is not a separate node. It is a `Time` implementation that derives its value from the sequencer's event stream. Every time a sequenced event is received on a follower node, the `Time` is updated to match the event's timestamp.

### SequencerDrivenTime

A new `Time` implementation:

```java
package com.core.infrastructure.time;

/**
 * A Time implementation that tracks the sequencer's event timestamps.
 * 
 * In live mode: uses system clock between events, snaps to event timestamp on each event.
 * In replay mode: purely driven by event timestamps (like ManualTime).
 */
public class SequencerDrivenTime implements Time, Encodable {

    private final Time wallClock;       // underlying system clock (null in pure replay mode)
    private volatile long eventNanos;   // last event timestamp from sequencer
    private volatile long offsetNanos;  // wallClock - eventTime at last sync point
    private boolean replayMode;         // if true, nanos() returns eventNanos only

    /**
     * Live mode: blends wall clock with sequencer corrections.
     */
    public SequencerDrivenTime(Time wallClock) {
        this.wallClock = wallClock;
        this.replayMode = false;
    }

    /**
     * Replay mode: purely driven by event timestamps.
     */
    public SequencerDrivenTime() {
        this.wallClock = null;
        this.replayMode = true;
    }

    /**
     * Called by BusClient event listener on each received event.
     * Updates the authoritative time from the sequencer's timestamp.
     */
    public void onEventTimestamp(long timestampNanos) {
        this.eventNanos = timestampNanos;
        if (!replayMode && wallClock != null) {
            wallClock.updateTime();
            this.offsetNanos = wallClock.nanos() - timestampNanos;
        }
    }

    @Override
    public long nanos() {
        if (replayMode) {
            return eventNanos;
        }
        // Live mode: return wall clock adjusted by known offset
        // Between events, time advances with the wall clock
        // On each event, offset is recalculated to correct drift
        return wallClock.nanos() - offsetNanos;
    }

    @Override
    public void updateTime() {
        if (wallClock != null) {
            wallClock.updateTime();
        }
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.number("eventNanos", eventNanos);
        encoder.number("offsetNanos", offsetNanos);
        encoder.string("mode", replayMode ? "REPLAY" : "LIVE");
    }
}
```

### How It Works

#### Live Mode (Distributed Deployment)

```
Sequencer Node (Primary)                    Follower Node (Application)
┌────────────────────────┐                  ┌────────────────────────┐
│ Time: CachedHighPrec.  │                  │ Time: SequencerDriven  │
│ BusServer.commit()     │                  │                        │
│   stamps event with    │──── multicast ──▶│ onEventTimestamp(ts)   │
│   time.nanos()         │                  │   eventNanos = ts      │
│                        │                  │   offset = wall - ts   │
│                        │                  │                        │
│                        │                  │ nanos() returns:       │
│                        │                  │   wallClock - offset   │
│                        │                  │   (corrected to seq.)  │
└────────────────────────┘                  └────────────────────────┘
```

Between events, `nanos()` returns the local wall clock adjusted by the offset calculated at the last event. This means:
- **During event processing:** time matches the sequencer's timestamp exactly.
- **Between events:** time advances with the local wall clock but is corrected on the next event.
- **Drift is bounded:** offset is recalculated on every event (typically every ≤100ms due to heartbeats).

#### Replay Mode (Backtesting Across Nodes)

```
Archive Replay                              Follower Node
┌────────────────────────┐                  ┌────────────────────────┐
│ ReplayMerge sends      │                  │ Time: SequencerDriven  │
│ recorded events        │──── replay ─────▶│   (replayMode=true)    │
│ with original          │                  │                        │
│ timestamps             │                  │ onEventTimestamp(ts)   │
│                        │                  │   eventNanos = ts      │
│                        │                  │                        │
│                        │                  │ nanos() returns:       │
│                        │                  │   eventNanos           │
│                        │                  │   (pure virtual time)  │
└────────────────────────┘                  └────────────────────────┘
```

In replay mode, `nanos()` returns purely the last event timestamp — identical to how `ManualTime` + `FilePlayback` works, but driven by the event stream instead of a local file.

### Wiring Into BusClient

The `onEventTimestamp()` call is wired as a `beforeDispatch` listener on the `Dispatcher`:

```java
// In AeronBusClient or a wrapper
if (time instanceof SequencerDrivenTime sdt) {
    dispatcher.addListenerBeforeDispatch(buffer -> {
        long timestamp = buffer.getLong(schema.getTimestampOffset());
        sdt.onEventTimestamp(timestamp);
    });
}
```

This runs before any application handler, so by the time `onFillOrder()` or `onAddOrder()` executes, `time.nanos()` already reflects the sequencer's timestamp.

### TimeLord as a Dedicated Event (Optional)

For higher-precision time synchronization, the sequencer can publish a dedicated `TimeLord` event:

```xml
<message id="14" name="TimeSync">
    <field name="SequencerNanos" type="long"/>
    <field name="WallClockNanos" type="long"/>
    <field name="Epoch" type="int"/>
</message>
```

This would be published periodically (e.g., every 10ms) and carry both the sequencer's `Time.nanos()` and its wall clock reading. Follower nodes can compute precise clock offset and drift rate.

However, for most use cases the **event timestamp in the header is sufficient** — every event already carries `time.nanos()` from the sequencer, and heartbeats ensure at least one event per 100ms.

---

## Modes of Operation

| Mode | Sequencer `Time` | Follower `Time` | Use Case |
|------|-------------------|-----------------|----------|
| **Live** | `CachedHighPrecisionTime` | `CachedHighPrecisionTime` | Current behavior. Each node uses its own clock. |
| **Live Synchronized** | `CachedHighPrecisionTime` | `SequencerDrivenTime(wallClock)` | Followers track sequencer time. Drift corrected on each event. |
| **Replay (Single Node)** | N/A | `ManualTime` + `FilePlayback` | Current playback. Single process, reads from corefile. |
| **Replay (Distributed)** | `ManualTime` (or Archive replay) | `SequencerDrivenTime(replayMode)` | Multi-node backtesting. All nodes track event timestamps from Archive replay. |
| **Replay (Accelerated)** | `ManualTime` | `SequencerDrivenTime(replayMode)` | Fast-forward replay. Events replayed as fast as possible, virtual time jumps with each event. |

---

## Backtesting Architecture

### Single-Node Backtesting (Existing)

```
corefile ──► FilePlayback ──► ManualTime.setNanos(ts) ──► Dispatcher ──► Application
                                                              │
                                                    Scheduler fires at virtual time
```

### Multi-Node Backtesting (New)

```
Archive Recording ──► ReplayMerge ──► Event Stream (multicast or MDC)
                                            │
                      ┌─────────────────────┼─────────────────────┐
                      ▼                     ▼                     ▼
               ┌──────────┐          ┌──────────┐          ┌──────────┐
               │ Node A   │          │ Node B   │          │ Node N   │
               │ Sequencer│          │ OrderBook│          │ Pricing  │
               │ (replay) │          │          │          │          │
               │          │          │          │          │          │
               │ ManualTime│         │ Seq.Driv.│         │ Seq.Driv.│
               │ .setNanos │         │ Time     │         │ Time     │
               │ (from     │         │ .onEvent │         │ .onEvent │
               │  archive) │         │ Timestamp│         │ Timestamp│
               └──────────┘          └──────────┘          └──────────┘
                                           │                     │
                                   Scheduler fires         Scheduler fires
                                   at virtual time         at virtual time
```

The sequencer node replays from Archive using `ManualTime` (like `FilePlayback`). Follower nodes receive events over the network and use `SequencerDrivenTime` to synchronize. All nodes agree on "now" = the latest event's timestamp.

### Accelerated Replay

Events can be replayed faster than real time by removing any delays between events:

```java
// In a backtesting driver
while (archiveReplay.hasNext()) {
    archiveReplay.poll(fragmentHandler, 256);  // no idle, no sleep
    // ManualTime jumps instantly to each event's timestamp
    // Scheduler fires immediately when virtual time crosses task boundaries
    // No wall-clock waiting between events
}
```

A full day of trading (millions of events) can be replayed in minutes.

---

## Command File Integration

```
# time-synced.cmd — Use sequencer-driven time on follower nodes
create /vm/time com.core.infrastructure.time.SequencerDrivenTime
# In live mode, pass the wall clock as fallback:
# create /vm/time com.core.infrastructure.time.SequencerDrivenTime /vm/systemClock

# time-replay.cmd — Use sequencer-driven time in replay mode
create /vm/time com.core.infrastructure.time.SequencerDrivenTime
set /vm/time/replayMode true
```

---

## Relationship to Other Components

### Sequencer Heartbeat

The sequencer's 100ms heartbeat ensures `SequencerDrivenTime` is updated at least 10 times per second, bounding maximum clock drift between events to ~100ms in live mode.

### Scheduler

`Scheduler.fire()` calls `time.nanos()`. With `SequencerDrivenTime`:
- **Live synchronized:** scheduled tasks fire based on corrected time. Jitter is bounded by heartbeat interval.
- **Replay mode:** tasks fire deterministically at virtual time. A task scheduled for `T+5min` fires when the replayed event stream crosses that point, regardless of wall clock.

### Snapshot Service

Snapshots reference `checkpointSeqNum`, not a timestamp. However, the `SnapshotBegin` event carries a timestamp. During snapshot recovery, `SequencerDrivenTime` is set to the snapshot's timestamp, ensuring scheduler state is consistent with the restored snapshot.

### Archive Replay and ReplayMerge

During `ReplayMerge`, events arrive with their original timestamps. `SequencerDrivenTime.onEventTimestamp()` is called for each, so the recovering node's virtual time tracks the recording. Once merged with live, the time source seamlessly transitions to live event timestamps.

---

## Implementation Fit

### New Classes

| Class | Module | Role |
|-------|--------|------|
| `SequencerDrivenTime` | infrastructure | `Time` implementation that tracks sequencer event timestamps |

### Changes to Existing Classes

| Class | Change |
|-------|--------|
| `AeronBusClient` | Add `beforeDispatch` listener to call `SequencerDrivenTime.onEventTimestamp()` |
| `MoldBusClient` | Same `beforeDispatch` listener wiring |
| `Main.java` | Add mode selection for `SequencerDrivenTime` (via system property or command file) |

### No Schema Changes Required

Event timestamps are already in the message header. No new message types are needed for basic sequencer-driven time. The optional `TimeSync` message (id=14) is a future enhancement for sub-heartbeat precision.

---

## Guarantees

1. **Deterministic replay.** In replay mode, `SequencerDrivenTime` produces the same `nanos()` values as the original recording. All scheduler tasks, timeouts, and time-dependent logic reproduce exactly.

2. **Bounded drift in live mode.** Between events, drift is bounded by the heartbeat interval (100ms). On each event, the offset is recalculated, snapping the follower's clock to the sequencer's.

3. **No wall clock leakage in replay.** `SequencerDrivenTime(replayMode=true)` never consults the system clock. `nanos()` returns only event-derived values.

4. **Compatible with existing code.** Any component that uses the injected `Time` interface works unchanged. No code modifications needed in applications — only the `Time` instance injected at startup changes.

5. **Compatible with `Scheduler`.** `Scheduler.fire()` and `scheduleEvery()` / `scheduleIn()` / `scheduleAt()` all use `time.nanos()`. Switching to `SequencerDrivenTime` makes all scheduled tasks event-time-aligned.
