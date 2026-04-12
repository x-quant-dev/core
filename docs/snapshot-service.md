# Snapshot and Checkpoint Service

No snapshot service exists in the current codebase. This document specifies the design for a **platform-wide coordinated snapshot** that enables fast recovery (load snapshot + replay deltas) rather than replaying the full event stream from the beginning.

---

## Problem Statement

Without snapshots, a recovering node must replay the **entire event stream** from position 0. For a platform that has been running for hours or days, this can take minutes to hours. Snapshots reduce recovery to seconds: load the latest checkpoint, replay only the events since, and merge with live.

---

## Architecture

```
                         Snapshot Coordinator
                         (registered as BusClient application)
                              │
                    ┌─────────┼──────────────┐
                    ▼         ▼              ▼
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │ Node A   │ │ Node B   │ │ Node N   │
              │ Sequencer│ │ OrderBook│ │ Pricing  │
              │          │ │          │ │          │
              │ Snapshot- │ │ Snapshot-│ │ Snapshot-│
              │ table     │ │ table   │ │ table   │
              └──────────┘ └──────────┘ └──────────┘
                    │         │              │
                    └─────────┼──────────────┘
                              ▼
                    Command Channel → Sequencer → Event Stream
                              │
                              ▼
                    Archive Recording (persisted to disk)
```

---

## Message Types

Four new SBE message types added to the schema:

```
SnapshotRequest {
    snapshotId: uint64       // unique identifier for this snapshot
    requestTimestamp: int64  // wall clock time of request
}

SnapshotBegin {
    snapshotId: uint64
    checkpointSeqNum: uint64 // global event sequence number at this point
    requestTimestamp: int64
    expectedNodeCount: uint16
}

SnapshotChunk {
    snapshotId: uint64
    nodeId: uint16           // which node this chunk belongs to
    chunkIndex: uint16       // 0-based chunk index for this node
    totalChunks: uint16      // total chunks this node will send
    payloadLength: uint32    // length of payload data
    payload: varData         // serialised state (binary)
}

SnapshotComplete {
    snapshotId: uint64
    checkpointSeqNum: uint64
    nodeCount: uint16        // how many nodes responded
    valid: bool              // true if all expected nodes submitted all chunks
    archiveRecordingId: int64  // Archive recording ID containing this snapshot
    archivePosition: int64     // Archive position of the SnapshotBegin event
}
```

---

## Protocol

### Phase 1: Initiate

The `SnapshotCoordinator` publishes a `SnapshotRequest` command on the command channel. The sequencer sequences it and publishes `SnapshotBegin` on the event stream.

```
Coordinator ──► SnapshotRequest ──► Command Channel ──► Sequencer
                                                          │
Sequencer publishes:                                      │
  SnapshotBegin { snapshotId, checkpointSeqNum=currentSeqNum }
                                                          │
                                                          ▼
                                                    Event Stream
                                                          │
                                    All nodes receive SnapshotBegin
                                    at the same deterministic position
```

The `checkpointSeqNum` is the event sequence number of the `SnapshotBegin` event itself. This is the **consistency point** — all nodes have processed exactly the same events up to this number.

### Phase 2: Node Snapshots

Each node that implements `Snapshottable` receives the `SnapshotBegin` event via the dispatcher:

```java
public interface Snapshottable {
    /**
     * Called when a SnapshotBegin event is received.
     * Implementation must serialise local state and send SnapshotChunk commands.
     */
    void onSnapshotRequest(long snapshotId, long checkpointSeqNum);
}
```

Each node:
1. Has already processed all events up to `checkpointSeqNum` (the `SnapshotBegin` event is the last one before it acts).
2. Serialises its in-memory state to binary.
3. Splits the binary into chunks of `maxPayloadLength()` or less.
4. Sends each chunk as a `SnapshotChunk` command to the sequencer.

```
Node B ──► SnapshotChunk { snapshotId, nodeId=B, chunkIndex=0, totalChunks=3, payload=... }
Node B ──► SnapshotChunk { snapshotId, nodeId=B, chunkIndex=1, totalChunks=3, payload=... }
Node B ──► SnapshotChunk { snapshotId, nodeId=B, chunkIndex=2, totalChunks=3, payload=... }
```

The sequencer sequences these chunks as events on the event stream. This makes snapshot data **part of the deterministic event stream** — any node replaying the stream will see the snapshot.

### Phase 3: Validate

The `SnapshotCoordinator` listens to the event stream for `SnapshotChunk` events matching the current `snapshotId`. It tracks:

- Which nodes have responded (by `nodeId`).
- Whether each node has submitted all its chunks (`chunkIndex` 0..`totalChunks-1`).
- A timeout (configurable, default 30 seconds) for the entire snapshot operation.

When all expected nodes have submitted all chunks:

```
Coordinator ──► SnapshotComplete { snapshotId, checkpointSeqNum, valid=true }
```

If any node fails to respond within the timeout:

```
Coordinator ──► SnapshotComplete { snapshotId, valid=false, missingNodes=... }
```

An invalid snapshot is ignored for recovery purposes. The platform continues operating normally.

### Phase 4: Persist

The snapshot data is already persisted — it is part of the event stream, which is recorded by Aeron Archive. The `SnapshotComplete` event records:
- `archiveRecordingId`: which Archive recording contains this snapshot.
- `archivePosition`: the Archive position of the `SnapshotBegin` event.

This allows recovery to seek directly to the snapshot in the Archive without scanning the entire recording.

---

## Where Snapshots Are Persisted

### Primary Storage: Aeron Archive (Event Stream)

Snapshots are **embedded in the event stream**. Because the sequencer sequences `SnapshotChunk` messages as events, they are automatically:
- Recorded by the primary Archive's `RecordingSession`.
- Replicated to standby Archives via the replication protocol.
- Available for replay via `AeronArchive.startReplay()`.

There are **no separate snapshot files**. The Archive's segment files contain everything.

### Optional Secondary Storage: Snapshot Index File

For fast lookup, the `SnapshotCoordinator` can maintain a small index file:

```
/archive/snapshots.idx

Format (one line per valid snapshot):
snapshotId,checkpointSeqNum,archiveRecordingId,archivePosition,nodeCount,timestamp
1001,500000,42,1048576,5,2026-02-16T10:00:00Z
1002,1000000,42,2097152,5,2026-02-16T10:05:00Z
1003,1500000,42,3145728,5,2026-02-16T10:10:00Z
```

This allows recovery to jump directly to the latest snapshot without scanning the Archive. The index can be rebuilt from the event stream at any time.

### Snapshot Pruning

Old snapshots can be pruned:
- **Logical pruning:** Mark old `SnapshotComplete` entries as superseded. Recovery always uses the latest valid snapshot.
- **Archive truncation:** The Archive recording can be truncated before the last valid snapshot's position. All events before the checkpoint are no longer needed for recovery.

```
Archive recording:
  ├─ events 1..500000 ─┤─── snapshot 1001 ──┤─ events 500001..1000000 ─┤─── snapshot 1002 ──┤─ ...
                        │                    │                          │                     │
                   can truncate          this snapshot         can truncate              latest
                   (if 1002+ valid)      is embedded           (if later valid)         snapshot
```

---

## Checkpoint Frequency

### Factors Determining Frequency

| Factor | More Frequent | Less Frequent |
|--------|--------------|---------------|
| **Recovery time** | ✅ Faster (fewer deltas to replay) | ⚠️ Slower (more deltas) |
| **Event stream overhead** | ⚠️ More SnapshotChunk events on stream | ✅ Less overhead |
| **Archive size** | ⚠️ Larger (snapshot data in recording) | ✅ Smaller |
| **Snapshot duration** | ⚠️ More frequent serialisation pauses | ✅ Fewer pauses |

### Recommended Frequency

| Environment | Frequency | Rationale |
|-------------|-----------|-----------|
| **Production** | Every 5 minutes | Balance between recovery speed and stream overhead. At ~100K events/sec, 5 minutes = ~30M events to replay. With snapshot, replay takes seconds. |
| **High-throughput production** | Every 1 minute | If event rates exceed 500K/sec, 5 minutes of delta replay could take tens of seconds. 1-minute checkpoints keep recovery under 10 seconds. |
| **Development/test** | Every 30 seconds | Fast iteration on snapshot/recovery testing. |
| **End of day** | On demand | Trigger via shell command before controlled shutdown. Ensures clean recovery on next startup. |

### Triggering

Snapshots can be triggered in three ways:

1. **Scheduled:** The `SnapshotCoordinator` schedules periodic snapshots via the `Scheduler`:
   ```
   scheduler.scheduleEvery(5 * 60 * 1000, this::requestSnapshot)  // every 5 minutes
   ```

2. **On demand:** Operator issues shell command:
   ```
   /snapshotCoordinator/snapshot
   ```

3. **Event-driven:** Triggered by specific events (e.g., end-of-day, configuration change, before planned failover):
   ```
   /snapshotCoordinator/snapshotBeforeFailover
   ```

---

## Recovery Process

### Step-by-Step Recovery

```
Node restarts or new node joins
           │
           ▼
    ┌──────────────┐
    │ 1. Find       │  Query Archive (via ArchiveAnnouncement or config)
    │    latest     │  for latest SnapshotComplete where valid=true
    │    snapshot   │  
    └──────┬───────┘
           │
           ▼
    ┌──────────────┐
    │ 2. Load       │  Start Archive replay from snapshot's archivePosition
    │    snapshot   │  Read SnapshotBegin + all SnapshotChunk events for this snapshotId
    │    chunks     │  Filter chunks matching this node's nodeId
    └──────┬───────┘  Deserialise binary payload into local state
           │
           ▼
    ┌──────────────┐
    │ 3. Replay     │  Continue Archive replay from checkpointSeqNum + 1
    │    deltas     │  Apply all events from checkpoint to current position
    │               │  State is now current as of Archive's max recorded position
    └──────┬───────┘
           │
           ▼
    ┌──────────────┐
    │ 4. ReplayMerge│  Transition from Archive replay to live multicast
    │    with live  │  Uses Aeron ReplayMerge:
    │               │    - Multi-destination subscription (control-mode=manual)
    │               │    - Replay source + live source merged seamlessly
    │               │    - No messages lost during transition
    └──────┬───────┘
           │
           ▼
    ┌──────────────┐
    │ 5. Ready      │  Activator becomes ready
    │               │  Node begins accepting commands
    └──────────────┘
```

### Recovery Timeline Comparison

```
Full replay (no snapshot):
  ├────────────── replay all events (position 0 → current) ──────────────┤ READY
  t=0                                                                  t=minutes/hours

Snapshot recovery (5-minute checkpoint interval):
  ├─ find snapshot ─┤─ load chunks ─┤─ replay ≤5min of deltas ─┤─ merge ─┤ READY
  t=0             ~100ms          ~1s                         ~5s      ~5.5s

Snapshot recovery (1-minute checkpoint interval):
  ├─ find ─┤─ load ─┤─ replay ≤1min ─┤─ merge ─┤ READY
  t=0    ~100ms   ~1s              ~1s        ~2.5s
```

### Recovery Without Snapshot (Fallback)

If no valid snapshot exists (first run, all snapshots invalid), the node falls back to full event stream replay:

1. Query Archive for the recording's `startPosition`.
2. Start ReplayMerge from `startPosition`.
3. Replay entire history.
4. Merge with live.

This is functionally identical to MoldUDP64 rewind-based recovery, but uses Archive replay instead.

---

## Implementation Fit

### New Classes

| Class | Module | Role |
|-------|--------|------|
| `SnapshotCoordinator` | platform | BusClient application that initiates and validates snapshots |
| `Snapshottable` | platform | Interface implemented by nodes that participate in snapshots |
| `SnapshotRecovery` | platform | Utility that reads snapshot chunks from Archive and deserialises |
| `SnapshotIndex` | platform | Optional index file for fast snapshot lookup |

### Schema Changes

Add to the SBE schema:
- `SnapshotRequest` (message type: command)
- `SnapshotBegin` (message type: event)
- `SnapshotChunk` (message type: both — command from node, event when sequenced)
- `SnapshotComplete` (message type: both — command from coordinator, event when sequenced)

### Command File Integration

```
# snapshot.cmd — loaded by nodes that participate in snapshots
create /snapshotCoordinator com.core.platform.applications.snapshot.SnapshotCoordinator /bus
set /snapshotCoordinator/interval 300000  # 5 minutes in ms
set /snapshotCoordinator/timeout 30000    # 30 second timeout for node responses
/snapshotCoordinator/start
```

### Serialisation Format

Each node's snapshot payload is opaque binary. The recommended format is:

1. **Version header** (4 bytes): schema version for forward compatibility.
2. **State data**: SBE-encoded messages representing the node's state. Each state entry is a normal SBE message (e.g., `EquityDefinition`, `OrderState`), allowing the same decoders to be used for snapshot loading and event replay.

This means snapshot loading reuses the existing `Dispatcher` — snapshot chunks are dispatched through the same message handlers as live events.

---

## Design Constraints

1. **Snapshot data flows through the sequencer.** This preserves total ordering and determinism — the snapshot is part of the event stream. All nodes see the same snapshot at the same position.

2. **Chunks, not monoliths.** Large node state is split into multiple `SnapshotChunk` messages, each within `maxPayloadLength()`. The coordinator reassembles.

3. **Snapshots are idempotent.** A snapshot records state at a deterministic point. Replaying events from that point produces the same state. The sequencer's per-app sequence number validation guarantees this.

4. **Invalid snapshots are harmless.** If a snapshot is marked invalid (missing nodes, timeout), it is simply ignored. Recovery falls back to the previous valid snapshot or full replay.

5. **Snapshot overhead is bounded.** Snapshot chunks are normal events — they consume event stream bandwidth and Archive space. At 5-minute intervals with ~10 MB of state per node and 5 nodes, snapshot overhead is ~50 MB every 5 minutes, or ~170 KB/s — negligible compared to typical event stream throughput.

6. **No separate snapshot storage system.** Everything lives in the Archive recording. This eliminates consistency problems between snapshot files and event stream recordings.

---

## Implementation Status

### Implemented Classes

| Class | Module | Status |
|-------|--------|--------|
| `SnapshotCoordinator` | platform | ✅ Implemented — orchestrates snapshot lifecycle |
| `Snapshottable` | platform | ✅ Implemented — interface for snapshot participants |
| `SnapshotRecovery` | platform | ✅ Implemented — `recover()` and `recoverFromIndex()` |
| `SnapshotIndex` | platform | ✅ Implemented — in-memory index of valid snapshots |
| `LateJoinerService` | platform | ✅ Implemented — full recovery lifecycle orchestration |

### SnapshotRecovery

Supports two recovery paths:

1. **`recoverFromIndex(SnapshotIndex, nodeId, Snapshottable)`** — the preferred path when the `SnapshotIndex` has been populated from the event stream. Queries the index for the latest valid snapshot and returns the `checkpointSeqNum` for delta replay.

2. **`recover(snapshotId, checkpointSeqNum, nodeId, Snapshottable)`** — direct recovery when the caller already knows the snapshot coordinates.

3. **`recoverFromArchive(AeronArchive, recordingId, nodeId, Snapshottable)`** — cold-start recovery by scanning the Archive recording (Phase 3 — not yet fully implemented).

### LateJoinerService

Orchestrates the complete recovery lifecycle for late-joining nodes:

```
IDLE → SNAPSHOT_LOOKUP → SNAPSHOT_RESTORE → DELTA_REPLAY → LIVE
```

| Phase | Description |
|-------|-------------|
| `IDLE` | Not recovering |
| `SNAPSHOT_LOOKUP` | Finding the latest valid snapshot from the `SnapshotIndex` |
| `SNAPSHOT_RESTORE` | Restoring state via `SnapshotRecovery` |
| `DELTA_REPLAY` | Replaying events from `checkpointSeqNum` to current position |
| `LIVE` | Fully caught up, processing live events |

**Shell commands:**

| Command | Description |
|---------|-------------|
| `/lateJoiner/recover <nodeId> <snapshottable>` | Initiate recovery |
| `/lateJoiner/goLive` | Mark recovery complete |
| `/lateJoiner/reset` | Reset to IDLE |
| `/lateJoiner/status` | View current recovery state |

**Metrics:**

| Metric | Description |
|--------|-------------|
| `LateJoiner_Phase` | Current recovery phase (ordinal) |
| `LateJoiner_EventsReplayed` | Delta events replayed during recovery |
| `LateJoiner_RecoveryCount` | Total recoveries completed |
| `LateJoiner_RecoveredCheckpointSeqNum` | Checkpoint sequence number from latest recovery |
