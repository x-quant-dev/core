# HA Architecture Reference

This is the comprehensive reference for high availability in the F9 sequencer platform. It covers failover modes, transport behaviour during failover, Aeron channel types, port discovery, leader election scope, archive replication, failure modes, and design trade-offs.

For component-specific details, see:
- [consensus-module.md](consensus-module.md) — Consensus fencing and automatic leader election
- [aeron-archive-ha.md](aeron-archive-ha.md) — Archive recording, replication, and ReplayMerge
- [snapshot-service.md](snapshot-service.md) — Snapshot protocol and recovery lifecycle
- [deterministic-snapshots.md](deterministic-snapshots.md) — Rules for deterministic snapshot implementations
- [bus-architecture.md](bus-architecture.md) — Channel architecture, heartbeating, and MoldUDP64 failover

---

## HA Modes: Hot/Hot, Hot/Warm, Hot/Cold

### Hot/Hot (Active/Active) — NOT SUPPORTED

Two sequencers both actively processing commands simultaneously.

**Why F9 does not support this:** The fundamental invariant is that exactly ONE sequencer assigns sequence numbers. Two active sequencers would produce duplicate or conflicting sequence numbers, corrupting the event log and making deterministic replay impossible. The consensus lease prevents this — at most one node holds the lease.

Hot/Hot is architecturally incompatible with single-threaded deterministic sequencing. Systems that support Active/Active (e.g., multi-partition exchanges) use partitioning — each partition has its own sequencer. F9 is a single-partition design.

### Hot/Warm (Active/Passive) — PRIMARY MODE

Primary is active, backup passively receives the event stream and maintains state. On failover, backup can promote immediately because its state is current.

```
┌──────────────────────┐                    ┌──────────────────────┐
│  NODE A (Primary)    │   UDP Multicast    │  NODE B (Backup)     │
│                      │   Event Stream     │                      │
│  Sequencer (ACTIVE)  │──────────────────►│  Sequencer (PASSIVE) │
│  - accepts commands  │                    │  - listens to events │
│  - publishes events  │                    │  - updates app       │
│  - renews consensus  │                    │    sequence numbers  │
│    lease             │                    │  - maintains state   │
│                      │                    │  - does NOT publish  │
│  Archive (LOCAL)     │   Replication     │  Archive (REPLICA)   │
│  - authoritative     │──────────────────►│  - replicated copy   │
│  - gap-free          │                    │  - self-healing      │
└──────────────────────┘                    └──────────────────────┘
         │                                            │
         │         ┌──────────────────────┐           │
         └────────►│  Consensus Node      │◄──────────┘
                   │  (independent domain)│
                   │  ConsensusModule      │
                   │  TcpConsensusServer   │
                   │  Candidates:         │
                   │   seq01a (priority 1)│
                   │   seq01b (priority 2)│
                   └──────────────────────┘
```

**What makes it "warm":**
- Backup's `Sequencer.onEvent()` processes every event, updating per-application sequence numbers
- Backup's `PromotionGuard` monitors the event stream for gaps, stale epochs, and event silence
- Backup's Archive has a (nearly) complete copy of the event stream via replication
- Promotion takes **milliseconds** — acquire consensus lease, start heartbeat, begin accepting commands

**This is the designed and recommended mode.**

### Hot/Cold (Cold Start) — SUPPORTED VIA SNAPSHOT + REPLAY

Primary is active, backup is either not running or has no current state. On failover, backup must rebuild state before going active.

**Recovery path:**
1. `LateJoinerService` orchestrates: IDLE → SNAPSHOT_LOOKUP → SNAPSHOT_RESTORE → DELTA_REPLAY → LIVE
2. Load latest valid snapshot from Archive (if available)
3. Replay delta events from `checkpointSeqNum` to current position
4. `ReplayMerge` to transition from Archive replay to live multicast
5. Ready — begin accepting commands

**Recovery time:** Seconds with recent snapshot, minutes/hours without.

See [snapshot-service.md](snapshot-service.md) for the full recovery protocol.

---

## Component-Level HA Support

| Component | Hot/Warm | Hot/Cold | Auto-Elected? | Notes |
|-----------|----------|----------|---------------|-------|
| **Sequencer** | ✅ Passive event listening | ✅ Snapshot + replay | ✅ Via ConsensusModule | Primary mode; single authority |
| **Archive (Journal)** | ✅ Continuous replication | ✅ Replicate from scratch | ❌ Follows sequencer | Archive leadership is a consequence of sequencer leadership |
| **Snapshot Coordinator** | N/A (stateless) | N/A | ❌ | Runs as BusClient; works with any active sequencer |
| **Applications** (OrderBook etc) | ✅ Passive event listening | ✅ Snapshot restore + replay | ❌ | Same mechanisms as sequencer |
| **Consensus Module** | ✅ Always running | ✅ Stateless restart | N/A | Independent failure domain; must be available for any promotion |

### Leader Election Scope

The automatic leader election (see [consensus-module.md](consensus-module.md)) applies to the **Sequencer only**. Other components follow:

```
Leader Election:   ConsensusModule elects → Sequencer
                                              │
Follows automatically:                        ├──► Archive (promoted node starts LOCAL recording)
                                              ├──► ArchiveAnnouncer (publishes new PRIMARY announcement)
                                              └──► SnapshotCoordinator (works with whichever sequencer is active)
```

There is **no independent Archive election**. The Archive's role is determined by which node is the active sequencer:
- Active sequencer's Archive records `SourceLocation.LOCAL` → authoritative, gap-free
- Passive node's Archive records via replication → eventually consistent copy

---

## Transport Behaviour During Failover

### MoldUDP64

```
Before failover:
  Node A (Primary) ──► multicast 239.100.100.100:10100 ──► All subscribers
                                                            (including Node B backup)

After failover:
  Node B (Promoted) ──► multicast 239.100.100.100:10100 ──► All subscribers
```

**Key facts:**
- Both primary and backup subscribe to the **same multicast group** for events
- After promotion, the new primary publishes to the **same multicast address**
- **No subscriber reconfiguration needed** — multicast delivers from any publisher on the group
- Gap detection: `MoldEventReceiver` tracks `nextSeqNum`. A gap between old primary's last event and new primary's first event is detected and recovered via rewinder
- **Journal is local-only:** `FileChannelMessageStore` on each node is populated from what that node received over multicast. The backup's journal may have gaps if multicast packets were lost.

**Limitations:**
- No built-in replication of the journal across nodes
- Backup's journal is best-effort (subject to multicast loss)
- After failover, the promoted node's journal starts fresh from its first published event
- Recovery relies on the backup having processed all events via `onEvent()` (state is in memory, not replayed from journal)

### Aeron UDP

```
Before failover:
  Node A (Primary)
    ExclusivePublication ──► aeron:udp?endpoint=224.0.1.1:40456 streamId=1001 ──► All subscribers

After failover:
  Node B (Promoted)
    NEW ExclusivePublication ──► aeron:udp?endpoint=224.0.1.1:40456 streamId=1001 ──► All subscribers
```

**Key facts:**
- Event stream uses **UDP Multicast** (`aeron:udp?endpoint=224.0.1.1:40456`)
- Command stream uses **UDP Multicast** (`aeron:udp?endpoint=224.0.1.2:40457`)
- Both are standard Aeron multicast channels — not IPC, not unicast, not MDC for live streams
- After promotion, the new primary creates a new `ExclusivePublication` on the same channel + streamId
- Subscribers automatically receive from the new publisher — Aeron handles publisher changes transparently
- **IMPORTANT:** `AeronBusServer.addEventListener()` is a **NO-OP**. The Aeron bus server publishes but cannot subscribe to its own event stream. For backup sequencer passive listening, a **separate `AeronBusClient` subscription** is required (`supportsEventListening()` returns `false`)

**Archive integration:**
- Primary Archive records `SourceLocation.LOCAL` — reads from the publisher's log buffer in the same Media Driver. Gap-free.
- Standby Archive replicates from primary via `AeronArchive.replicate()`. Self-healing.
- After promotion, the new primary's Archive starts a new LOCAL recording
- `ArchiveAnnouncer` publishes `ArchiveAnnouncement { role=PRIMARY }` on the event stream

---

## Aeron Channel Types Used

| Channel | Type | URI | Purpose |
|---------|------|-----|---------|
| Event stream | **UDP Multicast** | `aeron:udp?endpoint=224.0.1.1:40456` | Sequencer → all nodes |
| Command stream | **UDP Multicast** | `aeron:udp?endpoint=224.0.1.2:40457` | Applications → sequencer |
| Archive control (request) | **UDP Unicast** | `aeron:udp?endpoint=nodeA:8010` | Control requests to Archive |
| Archive control (response) | **UDP Unicast** | `aeron:udp?endpoint=localhost:0` | Ephemeral port for responses |
| Archive replication | **UDP Unicast** | `aeron:udp?endpoint=localhost:0` | Segment file replication between Archives |
| ReplayMerge subscription | **MDC (Multi-Destination Cast)** | `aeron:udp?control-mode=manual` | Combines replay + live sources during recovery |
| ArchiveToCorefile replay | **IPC** | `aeron:ipc` | Local-only replay for corefile export |

### Why UDP Multicast for Event/Command Streams

- **One-to-many delivery** without per-subscriber state on the publisher
- **Publisher transparency** — subscribers don't need to know which node is publishing
- **Failover transparency** — new publisher on same multicast group is received automatically
- **No connection setup** — no TCP handshake latency

### Why Not MDC for Live Streams

Multi-Destination Cast (MDC) uses `control-mode=manual` or `control-mode=dynamic` to add/remove unicast destinations. It would require the publisher to know each subscriber's address. Standard multicast is simpler for the live event stream.

MDC IS used for `ReplayMerge` — the recovery subscription starts with a replay source and dynamically adds the live multicast source, then drops the replay source once caught up.

### Why IPC for Corefile Export

`ArchiveToCorefile` replays an Archive recording locally. IPC avoids network overhead for a same-JVM operation.

---

## Port and Endpoint Discovery

### Static Configuration (Command Files)

All primary channel addresses are configured statically in command files:

```bash
# MoldUDP64 (clob.cmd)
set event_channel inet:239.100.100.100:10100
set command_channel inet:239.100.100.101:10101
set discovery_channel inet:239.100.100.102:10102

# Aeron (clob-aeron.cmd)
set event_channel aeron:udp?endpoint=224.0.1.1:40456
set command_channel aeron:udp?endpoint=224.0.1.2:40457
set event_stream_id 1001
set command_stream_id 1002
```

All nodes in the cluster use the **same** event and command channel URIs. There is no per-node variation for these channels.

### Ephemeral Ports (Aeron)

Several Aeron channels use `endpoint=localhost:0` or `endpoint=0.0.0.0:0` — the Media Driver assigns an ephemeral port:

- Archive control response channel
- Archive replication channel
- ReplayMerge replay channel

These are point-to-point channels where the port is communicated via the Aeron protocol, not configured by the operator.

### Archive Discovery (Event-Stream Announced)

Archive endpoints are discovered via `ArchiveAnnouncement` events on the sequenced event stream:

```
ArchiveAnnouncer (on sequencer node)
    │
    ├── publishes ArchiveAnnouncement { archiveId, recordingId, role=PRIMARY, controlChannel="aeron:udp?endpoint=nodeA:8010" }
    │
    ▼ (sequenced on event stream)
    
ArchiveLocator (on every node)
    │
    ├── receives ArchiveAnnouncement
    ├── caches PRIMARY and STANDBY endpoints
    └── provides getControlChannel() and getRecordingId() to ArchiveReplayClient
```

This is **deterministic** — a node replaying the event stream (from Archive or corefile) will automatically learn the Archive location from the announcement events in the stream. No external discovery service.

### Consensus Module Discovery

The consensus server address is configured statically:

```bash
# On each sequencer node
create /consensusClient TcpConsensusClient
/consensusClient/connect consensusNode:9000
```

The consensus module must be on a known, stable address (independent failure domain).

---

## Archive Replication

### How It Works

```
Primary Node                              Standby Node
┌────────────────────────┐               ┌────────────────────────┐
│ Sequencer              │               │ Sequencer (passive)    │
│   ExclusivePublication │               │                        │
│         │              │               │                        │
│         ▼              │               │                        │
│ Archive (LOCAL)        │  replication   │ Archive (REPLICA)      │
│ ├─ RecordingSession    │──────────────►│ ├─ ReplicationSession  │
│ │  reads from log buf  │               │ │  replays from primary│
│ ├─ Segment files       │               │ ├─ Segment files       │
│ │  /archive/*.rec      │               │ │  /backup/*.rec       │
│ ├─ Catalog             │               │ ├─ Catalog             │
│ └─ Control :8010       │               │ └─ Control :8020       │
└────────────────────────┘               └────────────────────────┘
```

**Primary (`SourceLocation.LOCAL`):** Records directly from the local `ExclusivePublication` log buffer. No network path. **Gap-free** — every frame written to the publication is recorded before it is sent over the network.

**Standby (Replication):** Connects to primary's Archive control channel, replays from position 0 (or last replicated position), writes to local segment files. Self-healing — if the replication stream is interrupted, it reconnects and replays from its `stopPosition`.

### Is Replication Automatic?

**Partially.** In the current implementation:
- Each node's `ArchiveManager` is configured via command files with its own `archiveDir` and `controlChannel`
- The primary's Archive starts LOCAL recording automatically when the `ArchiveManager` activates
- Standby replication must be **configured** — the standby node must know the primary's control channel to call `AeronArchive.replicate()`
- The `ArchiveLocator` provides dynamic discovery of the primary's control channel via event-stream announcements
- **Full automation** would require the standby to automatically initiate replication when it receives an `ArchiveAnnouncement` with `role=PRIMARY`

### Replication Lag

Replication introduces a small lag — typically < 1ms under normal conditions. On promotion, the standby's recording may be missing the last few events that the primary published but hadn't yet been replicated.

**Impact:** These events were published on the multicast event stream. If the backup was receiving the live stream (Hot/Warm mode), it has already processed these events via `onEvent()` — its in-memory state is current even if the Archive copy lags. The gap only matters for:
1. Other nodes requesting Archive replay that spans the gap
2. Post-failover corefile export from the standby's Archive

---

## Failover Sequence

### Step-by-Step: Primary Failure → Backup Promotion

```
Timeline
────────────────────────────────────────────────────────────────

1. Primary stops publishing
   - Crash, network partition, or GC pause
   - Consensus lease is NOT renewed

2. Consensus lease expires (default 5000ms)
   - ConsensusModule.electIfExpired() triggers
   - Primary (the expired holder) is SKIPPED
   - Backup (next-priority candidate) is elected
   - Epoch increments: e.g., 1 → 2
   - ElectionListener fires on backup node (if in-process)

3. Backup detects promotion opportunity
   - PromotionGuard checks:
     ✅ Events received while passive
     ✅ No sequence gaps
     ✅ No stale epochs
     ✅ Event silence > threshold (primary stopped)
     ✅ Consensus lease available
   - AutoFailover requires N consecutive passes (default 3)

4. Backup activates
   - Sequencer.activate():
     a. Acquires consensus lease (epoch=2)
     b. Starts heartbeat scheduler
     c. Creates ExclusivePublication on same event channel
     d. Publishes first heartbeat + AppDefinition
   - Time from primary failure to first event: ~5s (lease timeout) + ~3s (silence threshold + consecutive passes)

5. Subscribers recover
   - MoldUDP64: detect gap via sequence numbers → rewinder recovery
   - Aeron: new Image connects automatically on same multicast channel
     Small gap → NAK retransmit. Large gap → ReplayMerge from Archive

6. Archive transitions
   - Backup's Archive starts LOCAL recording (new events)
   - ArchiveAnnouncer publishes ArchiveAnnouncement { role=PRIMARY }
   - All nodes update cached Archive location via ArchiveLocator

7. If old primary recovers
   - Its tryRenew("seq01a") fails (lease held by seq01b, epoch=2)
   - Self-deactivates via activator.stop()
   - Can rejoin as standby by registering as a candidate
```

### Failover Timing

| Phase | Duration | Bottleneck |
|-------|----------|------------|
| Lease expiry detection | 5000ms (configurable) | `leaseTimeoutMs` |
| Event silence confirmation | 3000ms (configurable) | `eventSilenceThresholdMs` |
| Consecutive pass requirement | 3000ms (3 × 1000ms) | `requiredConsecutivePasses × checkIntervalMs` |
| Activation | < 1ms | Consensus acquire + heartbeat start |
| **Total** | **~8-11 seconds** | Dominated by safety timeouts |

This is deliberately conservative. Reducing `leaseTimeoutMs` and `eventSilenceThresholdMs` lowers failover time but increases the risk of false failovers (e.g., during GC pauses).

---

## Failure Modes and Recovery

### 1. Primary Sequencer Crash

| Aspect | Behaviour |
|--------|-----------|
| Detection | Consensus lease expires; event silence threshold exceeded |
| Recovery | Backup promoted via leader election + AutoFailover |
| Data loss | None — backup's in-memory state is current from passive listening |
| Archive | Standby's replicated copy is nearly complete; may miss last ~1ms of events |
| Time to recover | ~8-11 seconds (safety timeouts) |

### 2. Network Partition (Primary Alive but Unreachable)

| Aspect | Behaviour |
|--------|-----------|
| Detection | Primary can't renew consensus lease → self-deactivates. Backup sees event silence. |
| Recovery | Backup promoted. Primary steps down when it regains connectivity. |
| Split-brain risk | **None** — consensus module ensures at most one lease holder. If primary can't reach consensus module, it drops commands and stops. |
| Edge case | If both nodes are partitioned FROM the consensus module but CAN see each other: both self-deactivate. **Outage, not corruption.** |

### 3. Consensus Module Crash

| Aspect | Behaviour |
|--------|-----------|
| Detection | `TcpConsensusClient.tryRenew()` returns false (connection fails). Active sequencer self-deactivates. |
| Impact | **Complete outage** — no node can acquire or renew a lease. |
| Recovery | Restart consensus module. Candidates re-register. Leader election resumes. |
| Design rationale | **"Better no primary than two primaries."** This is the fail-safe trade-off. |

### 4. Archive Corruption

| Aspect | Behaviour |
|--------|-----------|
| Detection | Replay fails or returns inconsistent data |
| Recovery | Fall back to another Archive (if standby has a complete copy), or full replay from an uncorrupted source |
| Snapshot impact | Snapshots are IN the Archive (part of the event stream). If the Archive is corrupted, snapshots may be lost. |
| Mitigation | Run Archives on multiple nodes with replication. |

### 5. Snapshot Corruption

| Aspect | Behaviour |
|--------|-----------|
| Detection | `SnapshotComplete { valid=false }` or checksum mismatch after restore + replay |
| Recovery | Skip to the previous valid snapshot, or fall back to full event stream replay |
| Impact | Slower recovery (more events to replay), no data loss |

### 6. Standby Archive Replication Lag at Promotion

| Aspect | Behaviour |
|--------|-----------|
| Scenario | Standby promoted while replication is 100 events behind |
| Impact | The standby's Archive recording is missing 100 events. Subscribers requesting replay of those events from the new primary's Archive won't find them. |
| Mitigation | The promoted standby starts a NEW recording. The 100 missing events are in the old primary's Archive (if accessible). Subscribers can be directed to the old primary's Archive for that range. |
| Typical lag | < 1ms (< 100 events at 100K events/sec) |

### 7. Both Nodes Crash Simultaneously

| Aspect | Behaviour |
|--------|-----------|
| Recovery | Cold start from Archive + snapshot (Hot/Cold mode) |
| Steps | 1. Restart one node. 2. `LateJoinerService` loads snapshot from Archive. 3. Replay deltas. 4. Go active. |
| Time | Seconds (with snapshot) to minutes/hours (without) |
| Data loss | Events that were in-flight at crash time (published but not yet archived by either node) |

---

## Design Trade-Offs

### 1. Lease-Based Fencing vs Raft Consensus

| Aspect | F9 (Lease/Consensus) | Raft Consensus (e.g., Aeron Cluster) |
|--------|-------------------|--------------------------------------|
| Latency (happy path) | Near-zero (local lease check) | 10-50µs (log replication round-trip) |
| Leader election | Priority-based, O(1) | Log-based, requires quorum |
| Nodes required | 2 sequencers + 1 consensus node | 3+ nodes (odd number for quorum) |
| Split-brain protection | Consensus module must be reachable | Majority quorum |
| Consensus module failure | **Outage** (fail-safe) | Majority can still operate |
| Implementation complexity | Simple | Complex (log replication, term management) |

**Why F9 chose lease-based:** Lower latency on the critical path. The sequencer doesn't need to replicate its log to a quorum before confirming an event — it publishes directly to multicast. This is faster but means the "replication" is async (via multicast + Archive replication).

### 2. Fail-Safe vs Fail-Available

F9 is **fail-safe**: if the consensus module is unreachable, all sequencers self-deactivate. This prevents split-brain but causes a complete outage during consensus module failure.

A **fail-available** design (like Raft with 5 nodes) can tolerate 2 node failures and continue operating. The trade-off is higher steady-state latency and more nodes.

### 3. Single Event Stream for Everything

All events — orders, fills, heartbeats, snapshots, archive announcements — share one sequenced stream.

**Benefit:** Total ordering. Single sequence number. Simple replay. Deterministic snapshot coordination.

**Cost:** Snapshot chunks consume event stream bandwidth. High-volume pricing data on the same stream would increase latency for order events. See [bus-architecture.md](bus-architecture.md) for the dual-command-channel design that mitigates this.

### 4. Archive Follows Sequencer (No Independent Archive Election)

The Archive's role is determined by which node is the active sequencer. There is no separate "Archive leader election."

**Benefit:** Simpler — one election, not two. The authoritative recording is always co-located with the publisher (LOCAL recording = gap-free).

**Cost:** If a node has a broken disk (Archive can't write), the sequencer on that node is still elected. A more sophisticated design would factor Archive health into the election priority.

### 5. Replication Lag Acceptance

Archive replication introduces ~1ms lag. On promotion, the standby's copy may be slightly behind.

**Benefit:** Lower latency — the sequencer doesn't wait for replication confirmation before publishing events.

**Cost:** On promotion, the last few events may not be in the new primary's Archive. These events ARE in subscribers' memory (they received them via multicast) but may not be recoverable for late joiners.

---

## MoldUDP64 vs Aeron HA Comparison

| Aspect | MoldUDP64 | Aeron UDP |
|--------|-----------|-----------|
| **Event delivery after failover** | Same multicast group, automatic | Same multicast group, automatic |
| **Gap recovery** | Application-level: discovery channel + rewinder | Driver-level: NAK retransmit (fast), Archive ReplayMerge (large gaps) |
| **Journal replication** | ❌ None — local `FileChannelMessageStore` only | ✅ Archive replication protocol (self-healing) |
| **Journal completeness** | Best-effort (subject to multicast loss) | Authoritative on primary (`SourceLocation.LOCAL`), replicated on standby |
| **Snapshot integration** | ❌ No snapshot-based recovery | ✅ Snapshot embedded in Archive, `LateJoinerService` orchestrates recovery |
| **Subscriber recovery** | Rewinder reads from local `MessageStore` (sequential) | `ReplayMerge` — O(1) seek, seamless transition to live |
| **Backup event listening** | Built-in — `busServer.addEventListener()` works | Requires separate `AeronBusClient` subscription |
| **Late joiner support** | Full replay from rewinder (sequential, slow) | `ReplayMerge` from any position, with optional snapshot fast-path |
