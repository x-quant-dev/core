# Aeron Archive Integration, HA, and ReplayMerge

This document covers the detailed design for integrating Aeron Archive into the sequencer platform for durable recording, replay, HA failover, and ReplayMerge.

---

## Multi-Node Topology With Archive

### Full HA Topology

```
                      UDP Multicast Group (events)
                     ┌───────────────────────────────────────┐
                     │  aeron:udp?endpoint=224.0.1.1:40456   │
                     │  streamId=1001                        │
                     └───────────────────────────────────────┘
                              ▲            │
                              │            ├──────────────────┐
                              │            │                  │
┌──────────────────────┐      │   ┌────────▼─────────┐  ┌────▼──────────────┐
│  NODE A (Primary)    │      │   │  NODE B (Standby) │  │  NODE C..N        │
│                      │      │   │                   │  │  (Subscribers)    │
│  Sequencer (ACTIVE)  │──────┘   │  Sequencer        │  │                   │
│  Publication.offer() │          │  (PASSIVE)        │  │  Application      │
│                      │          │  listens to events │  │  BusClients       │
│  Archive Conductor   │          │  updates app       │  │                   │
│  ├─ RecordingSession │          │  sequence numbers  │  │  Archive (opt.)   │
│  │  (LOCAL recording)│          │                   │  │  local recording  │
│  ├─ Catalog          │          │  Archive Conductor │  │  for replay       │
│  └─ Segment files    │          │  ├─ RecordingSession│  │                   │
│     /archive/*.rec   │          │  │  (LOCAL or       │  │                   │
│                      │          │  │   REPLICATION)   │  │                   │
│  Archive Control Ch. │          │  ├─ Catalog        │  │                   │
│  :8010 (unicast)     │          │  └─ Segment files  │  │                   │
└──────────────────────┘          │     /backup/*.rec  │  │                   │
                                  │                   │  │                   │
                                  │  Archive Control  │  │                   │
                                  │  :8020 (unicast)  │  │                   │
                                  └───────────────────┘  └───────────────────┘
```

### Channel Assignments

| Channel | Purpose | Transport |
|---------|---------|-----------|
| `aeron:udp?endpoint=224.0.1.1:40456` streamId=1001 | Event stream (sequencer → all) | UDP multicast |
| `aeron:udp?endpoint=224.0.1.2:40457` streamId=1002 | Command channel (apps → sequencer) | UDP multicast |
| `aeron:udp?endpoint=nodeA:8010` streamId=100 | Primary Archive control (request/response) | UDP unicast |
| `aeron:udp?endpoint=nodeA:8011` streamId=101 | Primary Archive recording events | UDP unicast |
| `aeron:udp?endpoint=nodeB:8020` streamId=100 | Standby Archive control | UDP unicast |
| `aeron:udp?endpoint=nodeB:8021` streamId=101 | Standby Archive recording events | UDP unicast |

---

## How Nodes Discover the Active Recording Archive

### The Problem

Aeron Archive uses a **point-to-point request/reply model**. A node requesting replay must connect to a specific Archive's control channel (e.g., `nodeA:8010`). But in an HA system the active Archive can change after failover. Nodes need to discover which Archive currently holds the authoritative recording.

### Solution: Archive Locator Service

The platform needs an **Archive Locator** — a lightweight discovery layer that nodes query to find the active Archive. There are three viable approaches, from simplest to most robust:

#### Option 1: Configuration-Based (Static)

All nodes are configured with a list of Archive endpoints at startup via command files:

```
# In aeron-archive.cmd
set archive_endpoints nodeA:8010,nodeB:8020
```

Nodes try each endpoint in order. The first Archive that responds to a `listRecordings` query and has the expected `recordingId` with the highest `stopPosition` is used. On failover, nodes cycle to the next endpoint.

**Pros:** Simple, no extra infrastructure.
**Cons:** Requires restart or shell command to update endpoints.

#### Option 2: Event-Stream Announced (Recommended)

The active Archive announces itself on the **event stream** using a new message type:

```
ArchiveAnnouncement {
    archiveId,
    controlChannel,      // "aeron:udp?endpoint=nodeA:8010"
    controlStreamId,     // 100
    recordingId,
    role                 // PRIMARY | STANDBY
}
```

- The sequencer node publishes this as a sequenced event at startup and after failover.
- All nodes receive it deterministically on the event stream.
- Nodes cache the latest `ArchiveAnnouncement` per role.
- On replay request, nodes connect to the `PRIMARY` Archive; if it fails, they fall back to `STANDBY`.

**Pros:** Discovery is deterministic, part of the event stream, no extra protocol.
**Cons:** Small delay between failover and announcement propagation.

#### Option 3: Multicast Heartbeat (Polling-Based)

Each Archive publishes periodic heartbeats on a dedicated multicast channel:

```
Archive Heartbeat Channel: aeron:udp?endpoint=224.0.1.3:40458 streamId=2001

ArchiveHeartbeat {
    archiveId,
    controlChannel,
    recordingId,
    maxRecordedPosition,
    role,
    timestamp
}
```

Nodes subscribe to this channel and maintain a registry of known Archives. They select the PRIMARY with the highest `maxRecordedPosition`.

**Pros:** Fast discovery, works independently of event stream.
**Cons:** Extra multicast channel, non-deterministic (not part of sequenced stream).

### Recommended Approach

**Option 2 (event-stream announced)** is the best fit for this architecture because:
- It preserves the principle that all state-affecting information flows through the sequencer.
- Recovery nodes can replay the event stream to discover the Archive configuration — no separate discovery needed.
- It works with both multicast and MDC.
- The `ArchiveAnnouncement` is part of the snapshot (nodes replaying from a snapshot automatically learn the Archive location).

---

## How All Archivers Record the Sequenced Stream

### Recording Strategy Per Node Role

| Node Role | Recording Method | Source | Completeness Guarantee |
|-----------|-----------------|--------|----------------------|
| **Primary (Sequencer)** | `SourceLocation.LOCAL` | Records directly from the local `ExclusivePublication` | **Complete.** Co-located with publisher — records before multicast send. No network loss possible. |
| **Standby (Passive Sequencer)** | `SourceLocation.REMOTE` or **Replication** | Subscribes to multicast event stream and records, OR replicates from primary Archive | **Eventually complete.** See gap handling below. |
| **Subscriber (Optional)** | `SourceLocation.REMOTE` | Subscribes to multicast event stream and records locally | **Best-effort.** May have gaps if joining late or experiencing loss. |

### Primary Archive: The Authoritative Copy

The primary Archive runs **co-located** with the sequencer's `ExclusivePublication`. It uses `SourceLocation.LOCAL`:

```java
archive.startRecording(
    eventChannel,        // "aeron:udp?endpoint=224.0.1.1:40456"
    eventStreamId,       // 1001
    SourceLocation.LOCAL // reads from local publication log buffer, not network
);
```

`SourceLocation.LOCAL` means the Archive subscribes to the publisher's log buffer **directly in the same Media Driver**, not via the network. This provides the **strongest guarantee**: every frame written to the publication is recorded before it is sent over the network. There is zero network loss risk for the primary recording.

### Standby Archive: Replication vs Direct Recording

The standby has two options:

#### Option A: Direct Multicast Recording (`SourceLocation.REMOTE`)

The standby Archive subscribes to the same multicast event stream as all other nodes:

```java
standbyArchive.startRecording(
    eventChannel,         // same multicast group
    eventStreamId,        // 1001
    SourceLocation.REMOTE // subscribe via network
);
```

**Risk:** Subject to the same multicast loss as any subscriber. If a packet is lost and the term buffer is overwritten before NAK recovery completes, the recording has a gap.

#### Option B: Replication From Primary Archive (Recommended)

The standby replicates the primary's recording via the Archive replication protocol:

```java
standbyArchive.replicate(
    srcRecordingId,
    Aeron.NULL_VALUE,                            // create new local recording
    AeronArchive.NULL_POSITION,                  // continuous — no stop
    "aeron:udp?endpoint=primaryNode:8010",       // primary control channel
    new ReplicationParams()
        .liveDestination(eventChannel)           // merge with live multicast when caught up
);
```

Replication flow:
1. Standby connects to primary's Archive control channel.
2. Requests replay from position 0 (or last replicated position).
3. Primary Archive replays from its segment files — which are **complete** (LOCAL recording).
4. Standby writes replayed frames to its own segment files.
5. When caught up, optionally adds the live multicast as a second source (ReplayMerge within replication).
6. Continuous: the standby tracks the primary's recording position and stays in sync.

**Guarantee:** The standby's recording is as complete as the primary's, because it replays from the primary's authoritative LOCAL recording — not from the potentially lossy network.

### Data Completeness Guarantee

```
Primary Archive (LOCAL)     ████████████████████████████████████▶ COMPLETE (authoritative)
                                       │
                              replication (replay)
                                       │
Standby Archive (REPL)      ████████████████████████████████▓▓▓▶ EVENTUALLY COMPLETE
                                                           ^^^
                                                     replication lag
                                                     (typically < 1ms)

Subscriber Archive (REMOTE) ████████░░░████████████████████████▶ BEST-EFFORT (may have gaps)
                                    ^^
                                    network loss
```

---

## Archive Gap Detection and Late Joiners

### What Happens When an Archive Detects a Gap

#### Primary Archive (LOCAL): Gaps Are Impossible

Because `SourceLocation.LOCAL` reads from the publisher's log buffer in the same Media Driver process, there is no network path. Gaps cannot occur. If the publisher crashes, the recording simply stops at the last committed frame.

#### Standby Archive (Replication): Gaps Self-Heal

If the replication stream between primary and standby experiences a transient outage:

1. The replication `Image` becomes unavailable on the standby.
2. The `ReplicationSession` detects the loss.
3. On reconnection, it queries the primary's `maxRecordedPosition`.
4. It requests replay from its own `stopPosition` (where it left off).
5. The primary replays the gap from its segment files.
6. The standby fills the gap and continues continuous replication.

**No manual intervention required.** The replication protocol is self-healing as long as the primary Archive is accessible.

#### Standby Archive (REMOTE Recording): Gaps Require Repair

If the standby records directly from multicast and loses packets beyond NAK recovery:

1. The recording has a gap — missing frames between two positions.
2. The standby detects this when its `Image` shows a discontinuity.
3. **Recovery:** Stop the REMOTE recording and switch to replication from the primary.
4. The replication fills the gap from the primary's complete recording.
5. Alternatively, accept the gap and mark the recording as non-authoritative.

**This is why replication is preferred over direct REMOTE recording for standby Archives.**

### Late Joiner Scenarios

#### Scenario 1: New Node Joins a Running Platform

A new node starts after the platform has been running for hours or days. It needs the full event history.

```
Timeline
────────────────────────────────────────────────────────────────

Platform started                      New node joins
     ▼                                      ▼
     ├──────── events 1..N ─────────────────┤
                                            │
     Recovery:                              │
     1. Query Archive for recordingId       │
        (from ArchiveAnnouncement on        │
         event stream, or from config)      │
                                            │
     2. Start ReplayMerge:                  │
        - Replay from position 0            │
        - Catch up through Archive replay   │
        - Merge with live multicast         │
        - Seamless transition               │
                                            │
     3. If snapshot exists:                 │
        - Load snapshot (skip bulk replay)  │
        - Replay from checkpoint only       │
        - Merge with live                   │
                                            │
     4. Activator becomes ready             │
        - Begin accepting commands          │
```

#### Scenario 2: Node Restarts After Crash

A node crashes and restarts. It has partial local state that is stale.

```
Recovery:
1. Discard local state (unsafe — may be partially applied).
2. Load latest valid snapshot from Archive (if available).
3. Replay deltas from checkpoint sequence number.
4. ReplayMerge with live stream.
5. Ready.
```

#### Scenario 3: Standby Archive Starts Late

A standby Archive node starts after the primary has been recording for some time.

```
Recovery:
1. Connect to primary Archive control channel.
2. Initiate replication with srcRecordingId, startPosition=0.
3. Primary replays full recording history from segment files.
4. Standby writes to local segment files.
5. When caught up, merge with live multicast.
6. Continuous replication from that point forward.
```

The replication protocol handles this natively — there is no special "late joiner" mode. It always replays from the requested position, which can be 0 (beginning) or any valid recorded position.

#### Scenario 4: Archive Node Returns After Network Partition

An Archive node was partitioned from the network and reconnects.

```
Recovery:
1. Replication Image reconnects automatically (Aeron handles this).
2. ReplicationSession queries primary's maxRecordedPosition.
3. Requests replay from its own stopPosition (where it was when partitioned).
4. Primary replays the missing range.
5. Standby fills gap and resumes continuous replication.
6. No data loss — primary's LOCAL recording is complete.
```

---

## ReplayMerge: Detailed Integration

### When ReplayMerge Is Used

| Scenario | Trigger | Archive Source |
|----------|---------|---------------|
| Late joiner | Node starts after platform | Primary or standby Archive |
| Crash recovery | Node restarts | Primary or standby Archive |
| Failover catch-up | Subscriber missed events during primary→standby transition | New primary (promoted standby) Archive |
| Snapshot recovery | Node loads snapshot, replays deltas | Any Archive with complete recording |
| Image loss | Slow consumer's Image becomes unavailable | Any Archive |

### ReplayMerge State Machine

```
GET_RECORDING_POSITION
  │  Query Archive: what is the max recorded position?
  ▼
REPLAY
  │  Start Archive replay from desired position
  │  Archive creates ReplaySession, reads segment files
  │  Replay data arrives on multi-destination subscription
  ▼
CATCHUP
  │  Consume replay fragments through fragmentHandler
  │  Monitor: is replay position within merge window of live?
  │  Merge window = min(termLength/4, 32 MB)
  ▼
ATTEMPT_LIVE_JOIN
  │  Add live multicast as second source on subscription
  │  Both replay and live deliver to same fragmentHandler
  │  Deduplication by position (Aeron guarantees this)
  ▼
MERGED
  │  Stop replay, remove replay source
  │  Subscription now carries live stream only
  ▼
DONE — node is caught up, activator becomes ready
```

### Critical Constraint: UDP Only

ReplayMerge **requires UDP** — it cannot work with IPC. The multi-destination subscription must use `control-mode=manual` to allow dynamic source addition/removal:

```java
Subscription subscription = aeron.addSubscription(
    "aeron:udp?control-mode=manual", streamId);
```

### Integration With Platform Event Loop

ReplayMerge polling is registered as a poller on the standard `Selector`:

```
EventLoop iteration:
  1. scheduler.fire()
  2. selector.selectNow()
       ├─ NIO channel callbacks
       └─ addPoller() callbacks  ← includes ReplayMerge.poll() during recovery
```

During recovery, `ReplayMerge.poll()` is registered via `selector.addPoller()`. Once merged, the node switches to normal subscription polling.

---

## Failover Sequence With Archive

### Primary Goes Offline

```
Timeline
────────────────────────────────────────────────────────────────

1. Primary goes offline
   - ExclusivePublication stops
   - Primary Archive RecordingSession stops (recording has definite stopPosition)
   - Subscribers detect unavailable Image (unavailableImageHandler fires)

2. Standby detects primary loss
   - Standby's event subscription Image becomes unavailable
   - Standby's replication session detects primary Archive is unreachable
   - Replication pauses (will resume if primary returns)

3. Standby promotion
   - Shell command: seq01b/start
   - Standby sequencer activates (PASSIVE → ACTIVE)
   - Per-app sequence numbers already current (maintained via onEvent)
   - Creates new ExclusivePublication on same event channel + streamId
   - Begins publishing heartbeats and accepting commands

4. Standby Archive becomes authoritative
   - Standby Archive's replicated recording contains full history up to failover point
   - New events from promoted sequencer are recorded as a new recording (or extended)
   - Standby publishes ArchiveAnnouncement { role=PRIMARY } on event stream
   - All nodes update their cached Archive location

5. Subscriber recovery
   - Subscribers' new Image connects automatically (same multicast channel + streamId)
   - Small gap between old primary's last event and new primary's first:
     - If data in term buffer: Aeron NAK recovery fills it
     - If not: subscriber uses ReplayMerge against standby (now primary) Archive
   - Subscribers seamlessly rejoin live stream

6. New standby (optional)
   - A third node begins replicating from the new primary's Archive
   - Or the recovered original primary rejoins as standby
   - New standby publishes ArchiveAnnouncement { role=STANDBY }
```

### What the Promoted Standby's Archive Contains

```
Standby Archive after promotion:
  Recording A (replicated):  events 1..N  ← full copy from former primary
  Recording B (new LOCAL):   events N+1.. ← new events from promoted sequencer

Subscribers requesting replay get:
  - Events 1..N from Recording A
  - Events N+1.. from Recording B
  - Archive catalog links both by channel+streamId
```

---

## Backpressure and Flow Control

### Flow Control Strategies

| Strategy | Behaviour | Slow Consumer Impact |
|----------|-----------|----------------------|
| `MaxMulticastFlowControl` (default) | `senderLimit = max(all receiver windows)` | **Isolated.** Slow consumer does not block others. |
| `MinMulticastFlowControl` | `senderLimit = min(all receiver windows)` | **Global stall.** Single slow consumer blocks all. |
| `TaggedMulticastFlowControl` | `senderLimit = min(tagged receiver windows)` | **Selective.** Only tagged receivers participate. |

### Why Max Flow Control Is Correct

The platform's no-flow-control design maps to `MaxMulticastFlowControl`:

- Sequencer publishes at full speed.
- Fast subscribers consume in real time.
- Slow subscriber falls behind → NAK recovery or Archive replay.
- **No subscriber can stall the event stream.**

`MinMulticastFlowControl` is a **design error** for a trading sequencer — a single slow monitoring tool would halt order matching.

### Recommended Configuration

- **Event publication:** `MaxMulticastFlowControl` (default).
- **Standby tracking:** `TaggedMulticastFlowControl` if the standby sequencer must be flow-controlled to guarantee it stays within the term buffer. Tag the standby, leave all other subscribers untagged.
- **Archive recording:** `SourceLocation.LOCAL` on primary — not subject to flow control (reads from log buffer directly).

### What Happens to a Slow Consumer

1. **Small gap (data in term buffer):** NAK → retransmit → automatic, sub-millisecond recovery. No application involvement.
2. **Large gap (data overwritten):** `Image` becomes `unavailable`. Application initiates `ReplayMerge` against Archive to catch up and rejoin live.

---

## Design Rules

1. **Primary Archive always uses `SourceLocation.LOCAL`.** This guarantees a complete, gap-free recording.

2. **Standby Archive uses replication, not REMOTE recording.** Replication from the primary's LOCAL recording is self-healing and gap-free.

3. **Archive location is announced on the event stream.** `ArchiveAnnouncement` messages allow all nodes to discover the active Archive deterministically.

4. **Late joiners use ReplayMerge.** Replay from Archive + merge with live multicast. No special late-joiner protocol.

5. **Use `MaxMulticastFlowControl`.** Slow consumers recover via NAK or Archive replay. Never block the sequencer.

6. **Failover creates a new recording.** The promoted standby's new events are a new recording in the catalog. Both old and new recordings are available for replay.
