# Platform Architecture Uplift — Design History

> **Design History** — This document consolidates the design decisions, implementation plans, and phased rollout context from the Aeron + SBE platform uplift. It may not reflect the current state of the codebase. For current documentation, see the [Documentation Index](../README.md).

---

## Table of Contents

1. [Overview & Goals](#1-overview--goals)
2. [Architecture & Guiding Principles](#2-architecture--guiding-principles)
3. [Phase Map & Dependency Graph](#3-phase-map--dependency-graph)
4. [Phase 1: Aeron Transport](#4-phase-1-aeron-transport)
5. [Phase 2A: Aeron Archive Integration](#5-phase-2a-aeron-archive-integration)
6. [Phase 2B: SBE Schema Integration](#6-phase-2b-sbe-schema-integration)
7. [Phase 3: Checkpoint and Snapshot Service](#7-phase-3-checkpoint-and-snapshot-service)
8. [Phase 4: SequencerDrivenTime & Distributed Replay](#8-phase-4-sequencerdriventime--distributed-replay)
9. [Phase 5: Production Hardening](#9-phase-5-production-hardening)
10. [Aeron API Reference](#10-aeron-api-reference)
11. [Test Plan](#11-test-plan)
12. [Operational Guide](#12-operational-guide)
13. [Evaluation Framework](#13-evaluation-framework)
14. [Completion Summary](#14-completion-summary)

---

## 1. Overview & Goals

This document records the design and implementation of the Aeron + SBE extension to the sequencer platform — a Java-based deterministic sequencer architecture for FX trading. The uplift adds:

- **Aeron transport** as an alternative to MoldUDP64, providing kernel-bypass, low-latency messaging
- **Aeron Archive** for durable event journal recording, replay, and standby replication
- **SBE encoding** as an alternative serialisation layer to the Velocity-generated codecs
- **Snapshot/checkpoint service** for fast node recovery without full journal replay
- **SequencerDrivenTime** for deterministic distributed backtesting and replay
- **Production hardening** — monitoring, alerting, debugging tooling, and operational runbooks

### Version Baseline

| Library | Version |
|---------|---------|
| Agrona  | 2.4.0   |
| Aeron   | 1.50.1  |
| SBE     | 1.37.0  |
| Java    | 17      |

---

## 2. Architecture & Guiding Principles

### Repository Structure

- **`infrastructure/`** — Allocation-free utilities: async I/O (`EventLoop`), buffers, encoding, logging, metrics, time, compression, concurrent primitives
- **`platform/`** — Sequencer architecture: activation/failover (`Activator`, `ActivatorFactory`), bus layer (`BusServer`, `BusClient`, Aeron/Mold/InProc transports), sequencer core (`Sequencer`, `ConsensusModule`, `PromotionGuard`), snapshots, schema/codecs, shell, FIX protocol, cluster coordination
- **`clob/`** — Demo CLOB trading core built on the platform
- **`buildSrc/`** — Code generators for binary encoders/decoders and FIX messages

### Key Source Paths

- `platform/src/main/java/com/core/platform/applications/sequencer/` — Sequencer, ConsensusModule, PromotionGuard, PassiveEventSource, Consensus
- `platform/src/main/java/com/core/platform/bus/` — BusServer, BusClient, AbstractBusServer, Aeron/Mold/InProc implementations
- `platform/src/main/java/com/core/platform/activation/` — Activator, ActivatorFactory, Activatable
- `platform/src/main/java/com/core/platform/applications/snapshot/` — SnapshotCoordinator, SnapshotRecovery, SnapshotIndex

### Guiding Principles

- **Preserve the sequencing model**: the event stream is authoritative. All state changes flow through the sequencer as sequenced events.
- **Keep command shell and activation semantics intact**: `@Directory`, `@Command`, `@Property`, `Activatable` patterns remain unchanged.
- **Make transport pluggable**: implement `BusServer`/`BusClient` variants; switch at command file level.
- **Minimal footprint in core modules**: isolate Aeron-specific code. `Sequencer` depends only on `BusServer`; applications depend only on `BusClient` and schema.
- **Allocation-free hot paths**: avoid `new` in hot paths, use buffer-based I/O, no autoboxing.

---

## 3. Phase Map & Dependency Graph

```
Phase 1 ─────────────────────────────────────────────────────────────── ✅ COMPLETE
  Aeron Transport (BusServer/BusClient, Selector.addPoller(), embedded/external driver)
       │
       ├──────────────────────┐
       │                      │
Phase 2A ✅ COMPLETE      Phase 2B ✅ COMPLETE
  Aeron Archive            SBE Schema
  Integration              Integration
  (recording, replay,      (SbeSchema, code gen,
   ReplayMerge, HA)         domain model)
       │                      │
       ├──────────────────────┘
       │
Phase 3 ✅ COMPLETE
  Checkpoint &
  Snapshot Service
       │
Phase 4 ✅ COMPLETE
  SequencerDrivenTime
  & Distributed Replay
       │
Phase 5 ✅ COMPLETE
  Production Hardening
  & Operational Tooling
```

### Dependency Graph

```
Phase 1 (Aeron Transport)
    │
    ├─► Phase 2A (Archive Integration)
    │       │
    │       ├─► Phase 3 (Snapshot Service)
    │       │       └─► Phase 5 (Production Hardening)
    │       └─► Phase 4 (SequencerDrivenTime)
    │               └─► Phase 5 (Production Hardening)
    │
    └─► Phase 2B (SBE Schema) — parallel with 2A
            └─► Phase 5 (Production Hardening)
```

**Critical path:** 1 → 2A → 3 → 5
**Parallel track:** 1 → 2B (can run alongside 2A)
**Phase 4 can start after 2A**, does not need to wait for Phase 3.

### Sprint Schedule

#### Sprint 1 (Weeks 1–3): Phase 2A + 2B in parallel

| Track A (Archive) | Track B (SBE) |
|------------------|--------------|
| Add aeron-archive dependency | Define SbeSchema interface |
| ArchiveAnnouncement schema | SBE codec generation in build |
| ArchiveManager (recording) | Map SBE header to platform header |
| ArchiveLocator (discovery) | Wire SbeSchema into bus |
| ArchiveReplayClient (ReplayMerge) | Sequencer validation with SBE |
| ArchiveReplicator (standby) | Shell adapter for SBE commands |
| Failover integration | |
| Command files | |

#### Sprint 2 (Weeks 4–6): Phase 3

| Week | Steps |
|------|-------|
| Week 4 | Steps 1–3: Schema, Snapshottable, SnapshotCoordinator |
| Week 5 | Steps 4–7: Sequencer handler, node snapshot impl, SnapshotIndex, SnapshotRecovery |
| Week 6 | Steps 8–10: Pruning, scheduled triggers, command files |

#### Sprint 3 (Weeks 7–8): Phase 4 + Phase 5

| Week | Focus |
|------|-------|
| Week 7 | Phase 4: SequencerDrivenTime, multi-node replay, ArchiveToCorefile |
| Week 8 | Phase 5: Monitoring, alerting, stress testing, runbooks, documentation |

---

## 4. Phase 1: Aeron Transport

### Scope

- Aeron transport for the event and command channels
- Selector integration for Aeron subscription polling
- Embedded and external Media Driver support
- Command file wiring for Aeron directory configuration

### New Components

| Class | Package | Role |
|-------|---------|------|
| `AeronBusServer` | `com.core.platform.bus.aeron` | Implements `BusServer`; owns `Publication` for events, `Subscription` for commands |
| `AeronBusClient` | `com.core.platform.bus.aeron` | Implements `BusClient`; owns `Subscription` for events, creates per-app `AeronCommandPublisher` |
| `AeronCommandPublisher` | `com.core.platform.bus.aeron` | Implements `MessagePublisher`; per-application command publisher via `tryClaim` |
| `Selector.addPoller(Runnable)` | `com.core.infrastructure.io` | Extension point on the standard `Selector`; `NioSelector` calls all registered pollers after each NIO select |

### Selector Design

Aeron subscriptions are registered as pollers on the standard `Selector` — the same interface used for `DatagramChannel`, `SocketChannel`, `Pipe`, and `ServerSocketChannel`. No separate `AeronSelector` wrapper class exists.

The `Selector` interface exposes `addPoller(Runnable)`:

```java
// In AeronBusClient / AeronBusServer constructor
selector.addPoller(() -> eventSubscription.poll(fragmentHandler, FRAGMENT_LIMIT));
```

`NioSelector` maintains a list of registered pollers and calls each one after every NIO select:

```
selector.selectNow()
  ├─ NIO readiness callbacks  (onRead, onWrite, onAccept, onConnect)
  └─ addPoller() callbacks    (Aeron subscriptions, or any custom source)
```

This means a developer can freely mix and match transport sources through one `Selector` instance:
- Pipes, plain UDP/TCP sockets — created via `Selector` factory methods
- MoldUDP64 channels — use the datagram channel factory method
- Aeron subscriptions — registered via `addPoller()`

**Previously considered and rejected:** A separate `AeronSelector` wrapper class that delegated NIO channel creation to an inner `NioSelector`. This was rejected because it created an unnecessary parallel abstraction — developers had to choose between `Selector` and `AeronSelector` rather than composing freely.

### AeronBusServer

- Owns Aeron `Publication` for events
- Owns Aeron `Subscription` for commands
- Uses `tryClaim` for event publishing (zero-copy)
- Validates `maxPayloadLength()` vs message length
- Converts Aeron fragments to `DirectBuffer` and dispatches to listeners

### AeronBusClient

- Owns Aeron `Subscription` for events
- Creates per-application `AeronCommandPublisher`
- Uses dispatcher for inbound events

### AeronCommandPublisher

- Per-application publisher for commands
- Uses `tryClaim` and sets app id/seq in message header
- Subscribes to event stream to confirm commands (similar to `MoldCommandPublisher`)

### Publication Strategy (Zero-Copy)

Initial Aeron integration uses `Publication.tryClaim` for zero-copy publishing:
- Message sizes must be ≤ `Publication.maxPayloadLength()` (no Aeron-side fragmentation in Phase 1)
- Buffer claims must be committed within the unblock timeout (default 15s)
- Use thread-local `BufferClaim` with `ConcurrentPublication` when multithreaded
- Chunking for larger payloads deferred to Phase 2+

### Dependencies

```groovy
// platform/build.gradle
implementation 'io.aeron:aeron-client:1.50.1'
implementation 'io.aeron:aeron-annotations:1.50.1'

// infrastructure/build.gradle
implementation 'org.agrona:agrona:2.4.0'
```

Agrona must be upgraded from 1.10.0 → 2.4.0 across all modules before adding Aeron dependencies.

### Key Files

- Aeron transport code: `platform/src/main/java/com/core/platform/bus/aeron/`
- External driver command file: `clob/src/main/resources/clob-aeron.cmd`
- Embedded driver command file: `clob/src/main/resources/clob-aeron-embedded.cmd`
- Aeron defaults: `platform/src/main/resources/aeron-network.cmd`
- Embedded driver setup: `platform/src/main/resources/aeron-embedded.cmd`
- Build changes: `platform/build.gradle`, `infrastructure/build.gradle`

### Validation

#### External Media Driver

```bash
./gradlew uberjar
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron.cmd /tmp/aeron
```

#### Embedded Media Driver

```bash
./gradlew uberjar
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-embedded.cmd /tmp/aeron
```

### Known Constraints

- Phase 1 enforces `Publication.maxPayloadLength()`.
- Chunking is deferred until Phase 2+.
- External driver directory is passed via command file arguments (not system properties).

---

## 5. Phase 2A: Aeron Archive Integration

**Prerequisite:** Phase 1 complete.

**Goal:** Add durable recording, replay, ReplayMerge, standby replication, and Archive discovery. After Phase 2A, a node can crash and recover by replaying from the Archive and merging with the live stream.

### Dependencies

```groovy
// platform/build.gradle
implementation 'io.aeron:aeron-archive:1.50.1'
```

### New Components

| Class | Package | Responsibility |
|-------|---------|---------------|
| `ArchiveManager` | `com.core.platform.bus.aeron` | Manages `AeronArchive` connection, starts/stops recording, lifecycle via `Activatable` |
| `ArchiveReplayClient` | `com.core.platform.bus.aeron` | Wraps `ReplayMerge` for node recovery — replays from Archive, merges with live |
| `ArchiveReplicator` | `com.core.platform.bus.aeron` | Runs on standby — continuous replication from primary Archive via `AeronArchive.replicate()` |
| `ArchiveLocator` | `com.core.platform.bus.aeron` | Caches `ArchiveAnnouncement` events to resolve PRIMARY/STANDBY Archive endpoints |
| `ArchiveAnnouncer` | `com.core.platform.applications` | Publishes `ArchiveAnnouncement` on event stream at startup and after failover |

### Schema Changes

```xml
<!-- Add to clob/src/main/resources/clob-schema.xml -->
<enum name="ArchiveRole">
    <value name="Primary" value="1"/>
    <value name="Standby" value="2"/>
</enum>

<message id="9" name="ArchiveAnnouncement">
    <field name="ArchiveId" type="short"/>
    <field name="RecordingId" type="long"/>
    <field name="Role" type="ArchiveRole"/>
    <optional name="ControlChannel" type="DirectBuffer"/>
</message>
```

Generates `ArchiveAnnouncementEncoder`, `ArchiveAnnouncementDecoder`, `ArchiveRole` via the Velocity code gen pipeline.

### Recording Architecture

#### Primary Node (Co-located with Sequencer)

```
AeronBusServer
  └── ArchiveManager
        ├── AeronArchive.connect(archiveContext)
        ├── archive.startRecording(eventChannel, eventStreamId, SourceLocation.LOCAL)
        ├── Activatable: starts recording on activate, stops on deactivate
        └── Publishes ArchiveAnnouncement { role=PRIMARY } via ArchiveAnnouncer
```

`SourceLocation.LOCAL` records from the publication's log buffer in the same Media Driver — gap-free, no network dependency.

#### Standby Node (Passive Sequencer)

```
AeronBusClient (or passive AeronBusServer)
  └── ArchiveReplicator
        ├── AeronArchive.connect(localArchiveContext)
        ├── archive.replicate(srcRecordingId, ..., primaryControlChannel, replicationParams)
        ├── replicationParams.liveDestination(eventChannel)  // merge with live when caught up
        ├── Activatable: starts replication on activate
        └── Publishes ArchiveAnnouncement { role=STANDBY }
```

Replication replays from the primary's LOCAL recording — self-healing on transient outages.

### Archive Discovery (ArchiveLocator)

`ArchiveLocator` listens to the event stream for `ArchiveAnnouncement` events. Discovery information is part of the sequenced event stream — deterministic and replay-safe.

```java
public class ArchiveLocator {
    private String primaryControlChannel;
    private long primaryRecordingId;
    private String standbyControlChannel;
    private long standbyRecordingId;

    public void onArchiveAnnouncement(ArchiveAnnouncementDecoder decoder) {
        if (decoder.role() == ArchiveRole.Primary) {
            primaryControlChannel = decoder.controlChannel();
            primaryRecordingId = decoder.recordingId();
        } else {
            standbyControlChannel = decoder.controlChannel();
            standbyRecordingId = decoder.recordingId();
        }
    }

    public String getControlChannel() {
        return primaryControlChannel != null ? primaryControlChannel : standbyControlChannel;
    }

    public long getRecordingId() {
        return primaryControlChannel != null ? primaryRecordingId : standbyRecordingId;
    }
}
```

Falls back to standby if primary is unavailable.

### ReplayMerge Integration (ArchiveReplayClient)

`ArchiveReplayClient` is registered as a poller on the standard `Selector`. During recovery it drives the `ReplayMerge` state machine; once merged, the poller becomes a no-op and the node activates.

```java
public class ArchiveReplayClient {
    private final Selector selector;
    private final ArchiveLocator locator;
    private ReplayMerge replayMerge;
    private boolean merged;

    public void recover(long fromPosition) {
        AeronArchive archive = AeronArchive.connect(
            new AeronArchive.Context().controlRequestChannel(locator.getControlChannel()));

        Subscription subscription = aeron.addSubscription(
            "aeron:udp?control-mode=manual", eventStreamId);

        replayMerge = new ReplayMerge(
            subscription, archive,
            replayChannel, replayDestination,
            liveEventChannel,
            locator.getRecordingId(), fromPosition);

        // Register with the selector alongside NIO channels and other Aeron subscriptions
        selector.addPoller(() -> poll(fragmentHandler, FRAGMENT_LIMIT));
    }

    public int poll(FragmentHandler handler, int fragmentLimit) {
        if (merged) return 0;
        int work = replayMerge.poll(handler, fragmentLimit);
        if (replayMerge.isMerged()) {
            merged = true;
            replayMerge.close();
            activator.ready();
        }
        return work;
    }
}
```

### Embedded vs Standalone Archive

| Mode | When To Use | Configuration |
|------|-------------|---------------|
| **Embedded** | Development, single-process testing | `ArchiveManager` launches `Archive.launch(archiveContext)` internally |
| **Standalone** | Production, multi-node | External `ArchivingMediaDriver` process; `ArchiveManager` connects via control channel |

Phase 2A targets embedded first, with standalone as a configuration switch.

### Command Files

```bash
# aeron-archive.cmd — Primary node
create /archiveManager com.core.platform.bus.aeron.ArchiveManager /busServer $archive_dir
create /archiveAnnouncer com.core.platform.applications.ArchiveAnnouncer /busServer /archiveManager
set /archiveManager/controlChannel aeron:udp?endpoint=0.0.0.0:8010
set /archiveManager/controlStreamId 100

# aeron-archive-standby.cmd — Standby node
create /archiveReplicator com.core.platform.bus.aeron.ArchiveReplicator /bus $archive_dir
set /archiveReplicator/primaryControlChannel aeron:udp?endpoint=nodeA:8010
set /archiveReplicator/primaryControlStreamId 100

# clob-aeron-ha.cmd — Full HA setup
source aeron-network.cmd
source aeron-archive.cmd
create /busServer com.core.platform.bus.aeron.AeronBusServer ...
create /bus com.core.platform.bus.aeron.AeronBusClient ...
create /archiveLocator com.core.platform.bus.aeron.ArchiveLocator /bus
```

### Implementation Steps

1. **Add dependency and validate build** — Add `aeron-archive:1.50.1`, verify no version conflicts, write minimal embedded Archive test
2. **Schema — ArchiveAnnouncement** — Add `ArchiveRole` enum and `ArchiveAnnouncement` (id=9) to `clob-schema.xml`, verify code gen
3. **ArchiveManager** — Recording lifecycle; wire into `AeronBusServer` as optional dependency
4. **ArchiveAnnouncer + ArchiveLocator** — Event-stream discovery
5. **ArchiveReplayClient** — ReplayMerge state machine; register with `selector.addPoller()` during recovery
6. **ArchiveReplicator** — Continuous standby replication, self-heals on transient outage
7. **Failover integration** — End-to-end test: stop primary, promote standby, verify Archive continuity
8. **Command files** — `aeron-archive.cmd`, `aeron-archive-standby.cmd`, `clob-aeron-ha.cmd`

### Risks

| Risk | Mitigation |
|------|------------|
| Archive version incompatibility | Archive is part of same Aeron release — versions are aligned |
| Embedded Archive performance on single-threaded event loop | Archive Conductor has its own thread; only control requests go through event loop |
| ReplayMerge requires UDP (not IPC) | All Aeron transport already uses UDP multicast/MDC |
| Term buffer size limits replay window | Configure term buffer ≥ 16 MB; Archive handles anything beyond |
| Replication lag during high throughput | Monitor via `archive.getMaxRecordedPosition()` delta; alert if lag exceeds threshold |

---

## 6. Phase 2B: SBE Schema Integration

**Prerequisite:** Phase 1 complete. **Can run in parallel with Phase 2A.**

**Goal:** Add SBE encoding as an alternative to Velocity-generated encoders, while preserving the `Schema` interface contract.

### Scope

1. Define `SbeSchema` implementing `Schema<DispatcherT, ProviderT>`
2. Map SBE message header to platform header fields: `applicationId`, `applicationSequenceNumber`, `timestamp`, `messageType`
3. Generate SBE codecs from `.xml` schema definition via build integration
4. Wire `SbeSchema` into `AeronBusServer`/`AeronBusClient` as alternative to Velocity schema
5. Verify Sequencer works unchanged with SBE encoding (same header layout)
6. Adapter layer for `@Command` shell integration with SBE encoders

### New Components

| Class | Module | Role |
|-------|--------|------|
| `SbeSchema` | platform | Implements `Schema`; wraps SBE-generated encoders/decoders |
| `SbeDispatcher` | platform | Dispatches incoming messages to registered listeners |
| `SbeProvider` | platform | Provides SBE encoders for outbound messages |
| `SbeEncoder` | platform | Wraps SBE-generated encoder; exposes platform field API |
| `SbeDecoder` | platform | Wraps SBE-generated decoder; exposes platform field API |
| `SbeFieldLayout` | platform | Maps field names to SBE buffer offsets and types |

### Header Layout Compatibility

The SBE message header must match the platform's required fields at fixed offsets:

| Field | Offset | Type |
|-------|--------|------|
| `applicationId` | 0 | short |
| `applicationSequenceNumber` | 2 | int |
| `timestamp` | 6 | long |
| `messageType` | 17 | byte |
| Header length | 18 | — |

### Key Deliverables

- SBE-encoded messages flow through the bus end-to-end
- Existing Sequencer and application code unchanged
- Schema pluggable via command file (MoldUDP64 schema vs SBE schema)

### Validation

```
□ SBE codec generation integrated into build
□ AeronBusServer/Client works with SbeSchema
□ All 8 existing message types encode/decode correctly via SBE
□ Sequencer validates app seq nums with SBE header
□ Round-trip test: command → sequencer → event → client
```

---

## 7. Phase 3: Checkpoint and Snapshot Service

**Prerequisite:** Phase 2A complete (Archive recording, replay, ReplayMerge, `ArchiveLocator`).

**Goal:** Add a platform-wide coordinated snapshot service that captures consistent state from all nodes, persists it in the Archive event stream, and enables fast recovery (snapshot + delta replay + ReplayMerge) instead of full event stream replay from position 0.

### Overview

Snapshot data flows through the sequencer as normal events — no separate storage system. Recovery reads snapshots from the Archive recording. The service introduces four new message types, a coordinator application, and a `Snapshottable` interface.

### Schema Changes

```xml
<!-- Add to clob/src/main/resources/clob-schema.xml -->
<enum name="SnapshotValidity">
    <value name="Valid" value="1"/>
    <value name="Invalid" value="2"/>
    <value name="TimedOut" value="3"/>
</enum>

<message id="10" name="SnapshotRequest">
    <field name="SnapshotId" type="long"/>
    <field name="RequestTimestamp" type="long"/>
    <field name="ExpectedNodeCount" type="short"/>
</message>

<message id="11" name="SnapshotBegin">
    <field name="SnapshotId" type="long"/>
    <field name="CheckpointSeqNum" type="long"/>
    <field name="RequestTimestamp" type="long"/>
    <field name="ExpectedNodeCount" type="short"/>
</message>

<message id="12" name="SnapshotChunk">
    <field name="SnapshotId" type="long"/>
    <field name="NodeId" type="short"/>
    <field name="ChunkIndex" type="short"/>
    <field name="TotalChunks" type="short"/>
    <optional name="Payload" type="DirectBuffer"/>
</message>

<message id="13" name="SnapshotComplete">
    <field name="SnapshotId" type="long"/>
    <field name="CheckpointSeqNum" type="long"/>
    <field name="NodeCount" type="short"/>
    <field name="Validity" type="SnapshotValidity"/>
    <field name="ArchiveRecordingId" type="long"/>
    <field name="ArchivePosition" type="long"/>
</message>
```

### New Components

| Class | Package | Module | Role |
|-------|---------|--------|------|
| `SnapshotCoordinator` | `com.core.platform.applications.snapshot` | platform | Initiates snapshots, validates completeness, publishes `SnapshotComplete` |
| `Snapshottable` | `com.core.platform.applications.snapshot` | platform | Interface — nodes implement to participate in snapshots |
| `SnapshotRecovery` | `com.core.platform.applications.snapshot` | platform | Reads snapshot chunks from Archive replay, deserialises node state |
| `SnapshotIndex` | `com.core.platform.applications.snapshot` | platform | In-memory index of valid snapshots (built from event stream) |

### Snapshottable Interface

```java
package com.core.platform.applications.snapshot;

public interface Snapshottable {
    /** @return the nodeId that identifies this node's snapshot chunks */
    short nodeId();

    /**
     * Called when SnapshotBegin event is received.
     * Implementation serialises local state and sends SnapshotChunk commands.
     */
    void onSnapshotRequest(long snapshotId, long checkpointSeqNum, Provider provider);

    /**
     * Called during recovery to restore state from a snapshot chunk.
     */
    void onSnapshotRestore(long snapshotId, int chunkIndex, int totalChunks, DirectBuffer payload);
}
```

### SnapshotCoordinator

```java
@Directory
public class SnapshotCoordinator implements Activatable {
    private final BusClient busClient;
    private final Provider provider;
    private final ArchiveLocator archiveLocator;
    private final Scheduler scheduler;
    private final List<Snapshottable> registeredNodes;

    @Property private long intervalMs = 300_000;  // 5 minutes
    @Property private long timeoutMs = 30_000;    // 30 seconds

    @Command
    public void snapshot() { requestSnapshot(); }

    private void requestSnapshot() {
        if (snapshotInProgress) return;
        currentSnapshotId = nextSnapshotId();
        SnapshotRequestEncoder enc = provider.getEncoder("SnapshotRequest");
        enc.snapshotId(currentSnapshotId);
        enc.requestTimestamp(time.epochMillis());
        enc.expectedNodeCount((short) registeredNodes.size());
        provider.send();
        snapshotInProgress = true;
        snapshotStartTime = time.epochMillis();
    }

    public void onSnapshotChunk(SnapshotChunkDecoder decoder) {
        if (decoder.snapshotId() != currentSnapshotId) return;
        ChunkTracker tracker = nodeTrackers.computeIfAbsent(
            decoder.nodeId(), id -> new ChunkTracker(decoder.totalChunks()));
        tracker.markReceived(decoder.chunkIndex());
        checkCompletion();
    }

    private void checkCompletion() {
        if (nodeTrackers.size() == registeredNodes.size()
                && nodeTrackers.values().stream().allMatch(ChunkTracker::isComplete)) {
            publishComplete(SnapshotValidity.Valid);
        }
    }

    private void publishComplete(SnapshotValidity validity) {
        SnapshotCompleteEncoder enc = provider.getEncoder("SnapshotComplete");
        enc.snapshotId(currentSnapshotId);
        enc.checkpointSeqNum(/* from SnapshotBegin */);
        enc.nodeCount((short) nodeTrackers.size());
        enc.validity(validity);
        enc.archiveRecordingId(archiveLocator.getRecordingId());
        enc.archivePosition(/* position of SnapshotBegin in Archive */);
        provider.send();
        snapshotInProgress = false;
        nodeTrackers.clear();
    }
}
```

### Snapshot Serialisation Format

Each node's payload uses the existing schema encoders:

```
Chunk payload layout:
  ┌─────────────────────────────┐
  │ Version (4 bytes)           │  schema version for forward compat
  ├─────────────────────────────┤
  │ SBE Message 1               │  e.g. EquityDefinition (full state)
  │ [msgType][length][fields]   │
  ├─────────────────────────────┤
  │ SBE Message 2               │  e.g. OrderState
  │ [msgType][length][fields]   │
  ├─────────────────────────────┤
  │ ...                         │
  └─────────────────────────────┘
```

`onSnapshotRestore()` can dispatch chunks through the same `Dispatcher` used for live events — no separate deserialisation path.

### SnapshotIndex

```java
public class SnapshotIndex {
    private final List<SnapshotEntry> entries = new ArrayList<>();

    public void onSnapshotComplete(SnapshotCompleteDecoder decoder) {
        if (decoder.validity() == SnapshotValidity.Valid) {
            entries.add(new SnapshotEntry(
                decoder.snapshotId(), decoder.checkpointSeqNum(),
                decoder.archiveRecordingId(), decoder.archivePosition()));
        }
    }

    public SnapshotEntry getLatest() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    record SnapshotEntry(long snapshotId, long checkpointSeqNum,
                         long archiveRecordingId, long archivePosition) {}
}
```

### SnapshotRecovery

```java
public class SnapshotRecovery {
    /**
     * Find the latest valid snapshot and restore state.
     * @return checkpointSeqNum to replay from, or -1 if no snapshot found
     */
    public long recover(AeronArchive archive, long recordingId,
                        short nodeId, Snapshottable snapshottable) {
        // 1. Use SnapshotIndex to find latest valid snapshot (or scan Archive if empty)
        // 2. Replay Archive from archivePosition
        // 3. Filter SnapshotChunk events matching nodeId
        // 4. Call snapshottable.onSnapshotRestore() for each chunk in order
        // 5. Return checkpointSeqNum — caller replays deltas from here
    }
}
```

### Recovery Integration Flow

```
1. Connect to Archive (via ArchiveLocator or config)
2. SnapshotRecovery.recover() → returns checkpointSeqNum
   - If snapshot found: node state is restored
   - If not found: checkpointSeqNum = 0 (full replay)
3. ArchiveReplayClient.recover(checkpointSeqNum + 1)
   - Replays deltas from checkpoint to current
   - ReplayMerge with live stream
4. Activator ready
```

### Checkpoint Triggering

| Trigger | Mechanism | Default |
|---------|-----------|---------|
| **Scheduled** | `scheduler.scheduleEvery(intervalMs, this::requestSnapshot)` | Every 5 minutes |
| **On demand** | `/snapshotCoordinator/snapshot` shell command | Manual |
| **Pre-failover** | `/snapshotCoordinator/snapshotBeforeFailover` — waits for `SnapshotComplete` before proceeding | Manual |
| **End of day** | Triggered by EOD event or cron-like scheduler | Configurable |

### Snapshot Persistence and Pruning

Snapshots are embedded in the Archive recording. No separate storage files.

```java
@Command
public void prune() {
    SnapshotEntry latest = snapshotIndex.getLatest();
    if (latest != null) {
        archive.truncateRecording(recordingId, latest.archivePosition());
        snapshotIndex.removeOlderThan(latest.snapshotId());
    }
}
```

Keep at least 2 valid snapshots (`retainCount` property, default 2) to allow fallback if the latest is corrupted.

### Command Files

```bash
# snapshot.cmd — Coordinator node
create /snapshotCoordinator com.core.platform.applications.snapshot.SnapshotCoordinator /bus /archiveLocator
set /snapshotCoordinator/intervalMs 300000
set /snapshotCoordinator/timeoutMs 30000
/snapshotCoordinator/start

# snapshot-node.cmd — Participant node template
# Each application registers itself as Snapshottable:
#   snapshotCoordinator.register(this);
```

### Implementation Steps

1. **Schema** — Add `SnapshotValidity`, `SnapshotRequest` (id=10), `SnapshotBegin` (id=11), `SnapshotChunk` (id=12), `SnapshotComplete` (id=13)
2. **Snapshottable interface** — Create in platform module, no Archive/Aeron dependencies
3. **SnapshotCoordinator** — Initiation, chunk tracking, timeout, `SnapshotComplete` publication
4. **Sequencer handler** — `SnapshotRequest` → `SnapshotBegin` with `checkpointSeqNum = busServer.getNextSeqNum()`
5. **Node snapshot impl** — Implement `Snapshottable`; serialise state, chunk, send
6. **SnapshotIndex** — In-memory tracking; `@Command(path="status")` for inspection
7. **SnapshotRecovery** — Restore from Archive; integrate with `AeronBusClient` recovery flow
8. **Pruning** — Archive truncation, `retainCount` property
9. **Scheduled triggers** — Wire `scheduleEvery`, `snapshotBeforeFailover`, EOD trigger
10. **Command files** — `snapshot.cmd`, `snapshot-node.cmd`, update `clob-aeron-ha.cmd`

### Risks

| Risk | Mitigation |
|------|------------|
| Snapshot chunks consume event stream bandwidth | ~170 KB/s at 5-min intervals with 50 MB state — acceptable. Monitor via metrics. |
| Large node state exceeds chunk count | At ~32 KB/chunk, 50 MB = ~1600 chunks — acceptable. |
| Snapshot timeout too short for slow nodes | Configurable `timeoutMs`. Default 30s. |
| Concurrent snapshots | `SnapshotCoordinator` rejects new request while `snapshotInProgress=true`. |
| Schema version mismatch during recovery | Version header in payload. Decoder checks version before deserialising. |
| Pruning deletes data needed by slow recovering node | Retain ≥ 2 snapshots. Recovery falls back to earlier snapshot. |

### Success Criteria

1. Snapshot request → begin → chunks → complete lifecycle works end-to-end.
2. `SnapshotComplete { valid=true }` appears on event stream and is recorded by Archive.
3. Node recovery from snapshot + delta replay + ReplayMerge completes in < 10 seconds (5-min checkpoint interval).
4. Invalid/timed-out snapshots are harmless — recovery falls back to previous valid snapshot.
5. Pruning reduces Archive disk usage without breaking recovery.

---

## 8. Phase 4: SequencerDrivenTime & Distributed Replay

**Prerequisites:** Phase 2A complete.

### Scope

1. Implement `SequencerDrivenTime` in infrastructure module
2. Wire `beforeDispatch` listener in `AeronBusClient` and `MoldBusClient` to call `onEventTimestamp()`
3. Add mode selection via command files (system property or command file)
4. `ArchiveToCorefile` utility — export Archive recording to legacy corefile format
5. Multi-node backtesting via Archive replay + `SequencerDrivenTime`
6. Command files: `time-synced.cmd`, `time-replay.cmd`

### Key Deliverables

- Follower nodes track sequencer time via event timestamps
- Distributed backtesting: replay Archive to multiple nodes, all synchronized
- Accelerated replay: events replayed faster than real time
- Legacy compatibility: export Archive to corefile for `FilePlayback`

### Validation

```
□ SequencerDrivenTime.nanos() matches event timestamp during replay
□ Scheduler fires at virtual time, not wall clock
□ Multi-node replay: all nodes produce identical results
□ Accelerated replay: 1 hour of events replayed in < 5 minutes
□ ArchiveToCorefile produces valid corefile readable by FilePlayback
```

---

## 9. Phase 5: Production Hardening

**Prerequisites:** Phases 2A, 3, 4 complete.

### Scope

1. **Monitoring dashboards:** Archive recording position, replication lag, snapshot validity, recovery time metrics
2. **Alerting:** Archive recording stopped, replication lag exceeds threshold, snapshot timeout, node recovery failed
3. **Operational runbooks:** failover procedure, snapshot-before-failover, Archive pruning, node recovery
4. **Archive-based debugging tool** (`ArchiveDebugTool`): CLI for replaying specific time ranges, filtering by message type, exporting to corefile
5. **Stress testing:** sustained 100K+ events/s recording, concurrent replay requests, ReplayMerge under load
6. **End-to-end HA test:** full topology (primary + standby + N subscribers), planned failover, unplanned crash, node recovery
7. **Documentation:** update all reverse-engineering docs with final implementation details

### Validation

```
□ All metrics visible in monitoring system
□ Alerts fire correctly for Archive and snapshot failures
□ Runbook tested: failover + recovery completes without data loss
□ Stress test: 100K events/s sustained for 1 hour, no gaps
□ Debug tool replays specific time range and produces correct output
```

---

## 10. Aeron API Reference

### Core Objects and Concepts

- **Media Driver**: runtime that manages log buffers and network I/O. Can be embedded or standalone.
- **Aeron**: client entry point used to create publications and subscriptions.
- **Publication**: send-side object that appends data into a log buffer for the driver to send.
- **Subscription**: receive-side object that polls for data; aggregates one or more `Image`s.
- **Image**: a session-specific stream within a subscription, identified by session id and `sourceIdentity()`.

### Startup and Connection

```java
// Connect to external driver
Aeron aeron = Aeron.connect(new Aeron.Context());

// Point to specific driver directory
Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName("/tmp/aeron"));

// Launch embedded driver
MediaDriver driver = MediaDriver.launchEmbedded();
Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
```

### Event Handlers

Configured via `Aeron.Context` — all invoked from the `ClientConductor` thread:

```java
new Aeron.Context()
    .errorHandler(Throwable::printStackTrace)
    .availableImageHandler(image -> { /* new session */ })
    .unavailableImageHandler(image -> { /* session lost */ });
```

### Publications

```java
// Concurrent (thread-safe)
Publication pub = aeron.addPublication(channel, streamId);

// Exclusive (single-threaded, lower overhead)
ExclusivePublication pub = aeron.addExclusivePublication(channel, streamId);

// Offer with copy
long result = pub.offer(buffer, offset, length);

// Zero-copy claim
BufferClaim claim = new BufferClaim();
long result = pub.tryClaim(length, claim);
if (result > 0) {
    MutableDirectBuffer buf = claim.buffer();
    // write into buf at claim.offset()
    claim.commit();
}
```

### Offer Return Codes

| Code | Value | Meaning |
|------|-------|---------|
| `NOT_CONNECTED` | -1 | No active subscribers |
| `BACK_PRESSURED` | -2 | Term buffer full |
| `ADMIN_ACTION` | -3 | Re-try; transient administrative action |
| `CLOSED` | -4 | Publication is closed |
| `MAX_POSITION_EXCEEDED` | -5 | Stream position limit reached |

A positive return value is the new stream position.

### Subscriptions and Polling

```java
Subscription sub = aeron.addSubscription(channel, streamId);

// Poll for fragments
int fragmentsRead = sub.poll(
    (buffer, offset, length, header) -> { /* handle */ },
    fragmentLimit);

// Controlled poll — return ABORT/BREAK/COMMIT/CONTINUE
int work = sub.controlledPoll(controlledHandler, fragmentLimit);

// Reassemble fragmented messages
FragmentHandler handler = new FragmentAssembler(actualHandler);
```

### Buffer Notes

- Aeron uses Agrona `DirectBuffer` and `MutableDirectBuffer`.
- Buffers provided in `FragmentHandler` are read-only and only valid within a duty cycle — copy if needed.

### Integration Implications for the Sequencer Platform

- Aeron is pull-based for receive: subscription polling determines delivery timing.
- Publication offer is non-blocking and supports backpressure (negative return codes).
- Fragmentation occurs automatically on send; reassembly requires `FragmentAssembler`.
- Event loop integration should drive `subscription.poll()` in the same duty cycle as existing selector polling.
- Aeron recommends tight polling loops; integration should avoid excessive blocking.
- `IdleStrategy` manages CPU usage based on fragments read.

---

## 11. Test Plan

### Pre-Existing Issue: Test JVM Configuration

All existing tests failed with `NoClassDefFoundError` from `UnsafeApi` because the `test` task in `core.java-conventions.gradle` was missing the `--add-opens` JVM argument required by `BufferUtils`. **Fixed first** (Step 0).

```groovy
// buildSrc/src/main/groovy/core.java-conventions.gradle
test {
    useJUnitPlatform()
    jvmArgs '--add-opens', 'java.base/jdk.internal.misc=ALL-UNNAMED'
}
```

### Test Architecture

- **JUnit 5** (Jupiter) + **AssertJ** (`BDDAssertions.then()`) + **Mockito**
- **TestBusServer / TestBusClient** test fixtures for in-memory bus simulation
- **ManualTime** for deterministic time control
- **TestLogFactory** for test logging
- No external dependencies in unit tests (no Aeron driver, no Archive)

### What CAN Be Unit Tested

| Class | Module | Testability |
|-------|--------|-------------|
| `SequencerDrivenTime` | infrastructure | Pure logic, no external deps |
| `SbeSchema` | platform | Pure logic, no external deps |
| `SbeEncoder` / `SbeDecoder` | platform | Buffer read/write only |
| `SbeDispatcher` | platform | Buffer dispatch logic |
| `SbeProvider` | platform | Wraps TestMessagePublisher |
| `SbeFieldLayout` | platform | Pure record with read methods |
| `SnapshotCoordinator` | platform | Uses generic Decoder/Provider (TestBusClient) |
| `SnapshotIndex` | platform | Uses generic Decoder (TestBusClient) |

### What CANNOT Be Unit Tested (Requires Running Aeron)

| Class | Reason |
|-------|--------|
| `ArchiveManager` | Needs embedded Archive + AeronArchive client |
| `ArchiveReplayClient` | Needs Archive recording to replay from |
| `ArchiveReplicator` | Needs two Archive instances |
| `ArchiveToCorefile` | Needs Archive recording to export |
| `ArchiveDebugTool` | Needs Archive recording to replay |
| `ArchiveAnnouncer` / `ArchiveLocator` | Needs BusClient event stream |
| `SnapshotRecovery` | Needs ArchiveReplayClient + Archive |

These require integration tests with a running embedded Aeron driver/Archive, or manual testing via command files.

### Unit Test Suite (84 tests)

| Step | Test Class | Module | Tests |
|------|-----------|--------|-------|
| 0 | (JVM config fix) | buildSrc | — |
| 1 | `SequencerDrivenTimeTest` | infrastructure | 10 |
| 2 | `SbeFieldLayoutTest` | platform | 8 |
| 3 | `SbeEncoderDecoderTest` | platform | 15 |
| 4 | `SbeDispatcherTest` | platform | 9 |
| 5 | `SbeSchemaTest` | platform | 16 |
| 6 | `SbeProviderTest` | platform | 4 |
| 7 | `SnapshotCoordinatorTest` | platform | 10 |
| 8 | `SnapshotIndexTest` | platform | 7 |
| 9 | `SbeRoundTripTest` | platform | 5 |
| | **Total** | | **84** |

#### Step 1: SequencerDrivenTimeTest

| Test | Description |
|------|-------------|
| `replayMode_nanos_returns_zero_before_any_event` | Default nanos is 0 |
| `replayMode_nanos_returns_last_event_timestamp` | After `onEventTimestamp(T)`, `nanos()` == T |
| `replayMode_nanos_tracks_multiple_events` | Multiple calls update correctly |
| `replayMode_updateTime_is_noop` | `updateTime()` doesn't throw with null wallClock |
| `liveMode_nanos_returns_wall_clock_adjusted_by_offset` | After event, nanos ≈ wallClock - offset |
| `liveMode_offset_recalculated_on_each_event` | Drift is corrected |
| `liveMode_updateTime_delegates_to_wallClock` | wallClock.updateTime() called |
| `eventsReceived_increments_on_each_event` | Counter tracks events |
| `encode_outputs_mode_and_counters` | Status encoding works |

#### Step 3: SbeEncoderDecoderTest

| Test | Description |
|------|-------------|
| `wrap_sets_header_fields` | schemaVersion, messageType, optionalFieldsIndex written |
| `set_and_get_applicationId` | Round-trip short field |
| `set_and_get_applicationSequenceNumber` | Round-trip int field |
| `set_and_get_timestamp` | Round-trip long field |
| `set_byte/short/int/long_field_by_name` | Typed field set/get round-trips |
| `set_invalid_field_throws` | Unknown field name → IllegalArgumentException |
| `toDecoder_returns_decoder_with_same_data` | Encoder → Decoder preserves all fields |
| `commit_delegates_to_publisher` | `commit()` calls messagePublisher.commit(length) |

#### Step 5: SbeSchemaTest (key tests)

| Test | Description |
|------|-------------|
| `header_offsets_match_platform_layout` | appId=0, appSeqNum=2, timestamp=6, msgType=17 |
| `headerLength_is_18` | Matches all other schemas |
| `getMessageNames_returns_all_13` | All message types present |
| `roundTrip_addOrder_through_schema` | Encode → dispatch → decode all fields |
| `roundTrip_all_messages` | Every message type encodes/decodes header correctly |

#### Step 7: SnapshotCoordinatorTest

| Test | Description |
|------|-------------|
| `snapshot_sends_snapshotRequest` | Manual trigger publishes request |
| `snapshot_when_not_active_does_nothing` | No request before activation |
| `snapshot_while_in_progress_does_nothing` | Duplicate request ignored |
| `snapshotBegin_triggers_registered_nodes` | Mock Snapshottable receives onSnapshotRequest |
| `all_chunks_received_publishes_complete_valid` | All nodes respond → VALID |
| `timeout_publishes_complete_timed_out` | Partial response + time advance → TIMED_OUT |

#### Step 8: SnapshotIndexTest

| Test | Description |
|------|-------------|
| `initially_empty` | `size() == 0`, `getLatest() == null` |
| `valid_snapshotComplete_adds_entry` | VALID event → size() == 1 |
| `invalid_snapshotComplete_ignored` | INVALID/TIMED_OUT → size() == 0 |
| `getLatest_returns_most_recent` | Multiple entries → last one |
| `removeOlderThan_prunes_entries` | Removes entries below threshold |

### Phase 2A Integration Tests

#### Single Process (Embedded Archive)

| Test | Scenario | Expected Result |
|------|----------|-----------------|
| **Record and Replay** | Start sequencer + Archive. Publish 1000 events. Replay from position 0. | All 1000 events replayed in order. |
| **ReplayMerge** | Publish 500 events. Start client with `ArchiveReplayClient`. Continue publishing during replay. | Client replays 500, merges with live, no gaps or duplicates. |
| **Late Joiner** | Platform running with 10,000 events. New node joins. | Replays from Archive, merges with live. Time < 5 seconds. |

#### Multi-Process (External Archive)

| Test | Scenario | Expected Result |
|------|----------|-----------------|
| **Failover** | Primary + standby running. Stop primary. Promote standby. | Standby publishes `ArchiveAnnouncement { role=PRIMARY }`. No event loss. |
| **Replication Gap Self-Heal** | Introduce 2s network partition. Restore. | Replication resumes from `stopPosition`. No gaps in standby. |

### Phase 3 Integration Tests

| Test | Scenario | Expected Result |
|------|----------|-----------------|
| **End-to-End Snapshot** | Trigger snapshot with 2 nodes. | `SnapshotComplete { valid=true }`. Correct nodeCount and checkpointSeqNum. |
| **Recovery From Snapshot** | Snapshot at event 5000. Continue to 10000. Kill node. Restart. | Loads snapshot, replays deltas 5001–10000, merges live. Total < 5 seconds. |
| **Invalid Snapshot Fallback** | Valid at 1000. Timeout at 2000. Kill node at 2500. Restart. | Skips invalid. Recovers from valid at 1000. Replays 1500 deltas. |
| **Pruning** | Take 3 snapshots. Prune. | Archive truncated before oldest retained. Recovery still works. |

### Manual Validation Checklist

```
□ ./gradlew :platform:compileJava :clob:compileJava — build green
□ Segment files appear in archive directory after recording starts
□ ArchiveAnnouncement visible on event stream via Printer
□ /archiveManager/status shows recording active, recordingId, position
□ /archiveLocator/status shows PRIMARY and STANDBY endpoints
□ Node recovery from Archive completes and activator becomes ready
□ Failover: standby promotion results in new ArchiveAnnouncement { role=PRIMARY }
□ /snapshotCoordinator/snapshot triggers snapshot via shell
□ SnapshotBegin, SnapshotChunk, SnapshotComplete visible on event stream
□ /snapshotIndex/status shows list of valid snapshots
□ Scheduled snapshots fire at configured interval
□ /snapshotCoordinator/prune removes old Archive segments
□ After pruning, recovery still works from retained snapshot
□ ./gradlew :infrastructure:test :platform:test :clob:test — all tests pass
```

---

## 12. Operational Guide

### Running the Platform

#### Build

```bash
./gradlew uberjar
```

#### External Media Driver

```bash
# Start external Media Driver separately, then:
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron.cmd /tmp/aeron
```

#### Embedded Media Driver

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-embedded.cmd /tmp/aeron
```

### Shell Access

```bash
nc 0.0.0.0 7001    # telnet shell (see telnet.cmd)
```

### Consensus Module

#### In-Process

```bash
source consensus.cmd
source -s consensus-wire.cmd seq01a
```

#### Distributed (Cross-JVM)

```bash
# Start standalone consensus server
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s consensus-server.cmd inet:0.0.0.0:9000
```

```bash
# Configure nodes to use remote consensus
create /consensus com.core.platform.applications.sequencer.TcpConsensusClient
/consensus/connect 0.0.0.0:9000
set /seq01a/consensusModule @/consensus
set /promotionGuard/consensusModule @/consensus
```

### Auto-Failover

```bash
create /autoFailover com.core.platform.applications.sequencer.AutoFailover @/promotionGuard @/seq01b
/autoFailover/enable
```

`AutoFailover` runs periodic health checks via `PromotionGuard` and triggers promotion after 3 consecutive passes (configurable).

### Snapshots

```bash
source snapshot.cmd                   # configure coordinator
/snapshotCoordinator/snapshot         # trigger manual snapshot
/snapshotCoordinator/prune            # prune old Archive segments
/snapshotIndex/status                 # list valid snapshots
```

### Late-Joiner Recovery

The `LateJoinerService` orchestrates recovery for nodes joining a running cluster:

1. Looks up the latest valid snapshot from `SnapshotIndex`
2. Restores state via `SnapshotRecovery`
3. Replays delta events from the checkpoint to current position
4. Transitions to live event processing

---

## 13. Evaluation Framework

The platform is evaluated across 8 dimensions targeting a score of 80/100.

| # | Dimension | Max Score | What to Evaluate |
|---|-----------|-----------|-----------------|
| 1 | **24/7 HA & Redundancy** | 15 | Active/passive failover, `ActivatorFactory` chain, automatic promotion, graceful deactivation, warm standby readiness |
| 2 | **Deterministic State Replication** | 15 | All state through sequencer as sequenced events, replay produces identical state, command validation (appId, seqNum), `BusServer` commit ordering |
| 3 | **Fencing & Split-Brain Prevention** | 15 | Leader epoch tracking, `ConsensusModule` lease-based fencing, `PromotionGuard` backup validation, epoch enforcement on passive event consumers |
| 4 | **Journal Replication & Durability** | 10 | Event journal persistence (Mold `MessageStore`, Aeron Archive), replay, message ordering guarantees, backpressure handling |
| 5 | **Late-Joiner & Recovery Support** | 10 | `SnapshotCoordinator`/`SnapshotRecovery` checkpoint-based recovery, `SnapshotIndex`, late-joiner replay from snapshot + journal tail |
| 6 | **Backtesting & Playback** | 10 | `PlaybackBusServer`/`PlaybackBusClient` for deterministic replay, time simulation support |
| 7 | **Operational Safety & Observability** | 15 | Metrics registration, command/event counters, latency tracking, health endpoints, shell introspection, fail-stop policy |
| 8 | **Code Quality & Testability** | 10 | Test coverage, test isolation (in-process bus), assertion quality, interface abstractions |

**Target score: 80/100**

### Evaluation Loop Process

1. **Evaluate** — Score platform across all 8 dimensions
2. **Consult oracle** — Get 5–8 prioritized improvements by score-impact-to-effort ratio
3. **Implement** — Execute the highest-priority improvement with unit tests; run `./gradlew test`
4. **Re-evaluate** — Loop until score ≥ 80

### Constraints

- Do NOT modify test fixture infrastructure (`TestBusServer`, `TestBusClient`, `TestSchema`) unless absolutely required
- Do NOT introduce new external dependencies — only Agrona, Eclipse Collections, and JDK classes
- Do NOT break the public API of `BusServer`, `BusClient`, `Sequencer`, or `Activator`
- Allocation-free patterns: avoid `new` in hot paths, buffer-based I/O, no autoboxing
- Maximum 10 iterations

---

## 14. Completion Summary

| Phase | Status | Key Deliverables |
|-------|--------|-----------------|
| Phase 1 | ✅ COMPLETE | AeronBusServer, AeronBusClient, Selector.addPoller(), embedded/external driver |
| Phase 2A | ✅ COMPLETE | ArchiveManager, ArchiveLocator, ArchiveAnnouncer, ArchiveReplayClient, ArchiveReplicator |
| Phase 2B | ✅ COMPLETE | SbeSchema, SbeDispatcher, SbeProvider, SbeEncoder, SbeDecoder, SBE XML schema |
| Phase 3 | ✅ COMPLETE | SnapshotCoordinator, SnapshotIndex, SnapshotRecovery, Snapshottable |
| Phase 4 | ✅ COMPLETE | SequencerDrivenTime, time-synced.cmd, time-replay.cmd |
| Phase 5 | ✅ COMPLETE | Metrics on all components, ArchiveToCorefile, ArchiveDebugTool, operational command files |

**All phases complete.** The full Aeron + SBE extension is implemented.

---

*Consolidated from source design history documents on 2026-04-12.*
