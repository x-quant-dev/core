# Core Sequencer Platform — User Manual

This manual covers configuration, deployment, monitoring, high availability, and operational procedures for the Core Sequencer Platform.

---

## 1. System Overview

The Core Sequencer Platform is a single-threaded, sequencer-centric trading platform. All state changes flow through a central sequencer that serializes commands into a deterministic, ordered event stream.

**Key characteristics:**

- **Single event loop** — no locks, no concurrent access.
- **Command/event separation** — applications send commands; the sequencer validates, sequences, and publishes events.
- **Shell-driven configuration** — all wiring via command files; no hardcoded values, no DI frameworks.
- **Transport-agnostic** — MoldUDP64 and Aeron transports are interchangeable.
- **Deterministic replay** — every event is sequenced and timestamped.

**Data flow:**

```
Application → Provider → Command Channel → Sequencer → BusServer → Event Channel → Dispatcher → Application
```

---

## 2. Command Shell

The entire runtime is orchestrated through a hierarchical command shell. The shell provides a filesystem-like interface to all registered objects.

### 2.1 Shell Syntax

| Syntax | Description |
|--------|-------------|
| `ls` | List objects in the current directory |
| `cd <path>` | Navigate to a directory |
| `cd ..` | Navigate up one level |
| `create <path> <class> [args...]` | Instantiate an object at the given path |
| `source <file.cmd> [args...]` | Execute a command file |
| `source -s <file.cmd> [args...]` | Execute a command file as a subshell |
| `set <var> <value>` | Set a shell variable |
| `default <var> <value>` | Set a variable only if not already set |
| `$varname` | Variable substitution |
| `$1`, `$2`, ... | Positional parameters from the command file |
| `@/path` | Object reference (resolved from the shell registry) |
| `<object>/command [args...]` | Invoke a command on a registered object |

### 2.2 Shell Interfaces

The shell can be accessed through multiple front-ends. All use the same command registry and support the same semantics.

| Interface | Command File | Default Address | Usage |
|-----------|-------------|-----------------|-------|
| **Telnet** | `telnet.cmd` | `inet:0.0.0.0:7001` | `nc 0.0.0.0 7001` or `telnet 0.0.0.0 7001` |
| **HTTP** | `http.cmd` | `inet:0.0.0.0:8001` | `curl http://localhost:8001/bus/status` |
| **WebSocket** | `ws.cmd` | `inet:0.0.0.0:8001` | WebSocket client on the given address |
| **CLI** | `cli-shell.cmd` | stdin/stdout | Interactive terminal (reads `System.in`) |

**Enabling a shell interface** — add a `source` line to your command file:

```
source -s telnet.cmd inet:0.0.0.0:7001
source -s http.cmd inet:0.0.0.0:8001
source -s ws.cmd inet:0.0.0.0:8002
source -s cli-shell.cmd
```

### 2.3 Common Admin Commands

```
/ % ls                              # list all registered objects
/ % cd seq01a                       # navigate to the sequencer
/seq01a % status                    # view sequencer status
/seq01a % start                     # activate the sequencer (go-active)
/seq01a % stop                      # deactivate the sequencer (go-passive)
/ % bus/status                      # view bus client status
/ % busServer/status                # view bus server status
/ % busServer/createSession AA      # create a new MoldUDP64 session
/ % vm/activation/state             # view the activation graph state
/ % vm/log/debugForAll true         # enable debug logging for all loggers
/ % vm/log/debug "Mold" true        # enable debug logging for a specific logger
```

### 2.4 Object Annotations

Objects expose shell interfaces through annotations:

| Annotation | Purpose |
|------------|---------|
| `@Directory(path = ".")` | Register a nested object subtree |
| `@Command(path = "status", readOnly = true)` | Expose a read-only command |
| `@Command` | Expose a writable command |
| `@Property(write = true)` | Expose a field as a getter/setter |

---

## 3. Transport Configuration

### 3.1 MoldUDP64 Transport

MoldUDP64 is the simplest transport — no external dependencies. Uses UDP multicast for events and commands.

**Network variables** (`network-local.cmd`):

```
set event_channel inet:239.100.100.100:10100:lo0
set command_channel inet:239.100.100.101:10101:lo0
set discovery_channel inet:239.100.100.102:10102:lo0
```

**Key components:**

| Component | Class | Purpose |
|-----------|-------|---------|
| Bus Client | `MoldBusClient` | Receives events, sends commands |
| Bus Server | `MoldBusServer` | Receives commands, publishes events |
| Message Store | `BufferChannelMessageStore` | In-memory event store (demo/test) |
| Message Store | `FileChannelMessageStore` | Disk-backed event store (production) |

**Example wiring** (from `clob.cmd`):

```
source network-local.cmd
create /bus/schema com.core.clob.schema.ClobSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel
```

### 3.2 Aeron Transport

Aeron provides higher performance with support for IPC, unicast UDP, and multicast UDP. Supports Archive recording for durability and replay.

**Network variables** (`aeron-network.cmd`):

```
set event_channel aeron:udp?endpoint=239.200.0.1:40123
set command_channel aeron:udp?endpoint=239.200.0.2:40124
set event_stream 1001
set command_stream 1002
set session_name CORE01
```

**Key components:**

| Component | Class | Purpose |
|-----------|-------|---------|
| Bus Client | `AeronBusClient` | Receives events, sends commands over Aeron |
| Bus Server | `AeronBusServer` | Receives commands, publishes events over Aeron |
| Driver Config | `AeronDriverConfiguration` | Configures the embedded media driver |
| Embedded Driver | `EmbeddedAeronDriver` | Runs the Aeron media driver in-process |

#### Channel URI Format

| URI | Transport |
|-----|-----------|
| `aeron:udp?endpoint=host:port` | Unicast UDP |
| `aeron:udp?endpoint=239.x.x.x:port` | Multicast UDP |
| `aeron:ipc` | Inter-process communication (shared memory) |

#### IPC vs UDP Decision Matrix

| Criterion | IPC | UDP |
|-----------|-----|-----|
| Same host | ✅ Preferred | ✅ Works |
| Cross host | ❌ | ✅ Required |
| Latency | Lowest | Low |
| Archive recording | ✅ | ✅ |

**Guideline:** Use IPC when all processes are on the same host. Use UDP when processes span multiple hosts or when multicast delivery is required.

#### Stream IDs

| Stream | Default ID | Purpose |
|--------|-----------|---------|
| Event | 1001 | Sequenced events broadcast to all applications |
| Command | 1002 | Commands from applications to the sequencer |
| Archive control | 100 | Archive recording control channel |

Stream IDs must be unique per logical channel. When running multiple cores on the same host, assign distinct stream IDs or use separate channels.

### 3.3 Embedded vs External Aeron Driver

**Embedded driver** (`aeron-embedded.cmd`) — runs the media driver in the same JVM:

```
source -s aeron-embedded.cmd /tmp/aeron
```

This creates an `AeronDriverConfiguration` and an `EmbeddedAeronDriver`. Simplest for development and single-process deployments.

**External driver** (`aeron-external.cmd`) — uses a separately launched media driver process:

```
source -s aeron-external.cmd /tmp/aeron
```

Only sets the `aeron_dir` variable. The driver must be started separately and share the same directory. Preferred for production where the driver lifecycle is managed independently.

### 3.4 SBE Schema with Aeron

The SBE schema is a drop-in replacement for the typed `ClobSchema`. To use it, replace the schema creation line:

```
create /bus/schema com.core.platform.schema.sbe.SbeSchema
```

See `clob-aeron-sbe.cmd` for a complete example.

---

## 4. Deployment Topologies

### 4.1 Standalone (Single Process)

Everything runs in one JVM — sequencer, bus, applications.

**Command files:** `clob.cmd` (MoldUDP64), `clob-aeron-embedded.cmd` (Aeron embedded), `clob-aeron.cmd` (Aeron external)

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob.cmd
```

### 4.2 Primary + Follower

A primary node runs the sequencer and publishes events. Follower nodes receive the event stream and track sequencer time.

**Primary:** `clob-aeron-ha.cmd` (includes Archive recording)

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-ha.cmd /tmp/aeron /tmp/archive
```

**Follower:** `clob-aeron-follower.cmd` (sequencer-driven time, Archive locator, snapshot tracking)

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-follower.cmd /tmp/aeron
```

Follower nodes use `time-synced.cmd` to track sequencer time from event timestamps and include an `ArchiveLocator` to discover Archive endpoints from event-stream announcements.

### 4.3 Primary + Backup (HA)

A backup sequencer remains passive (does not accept commands or publish events) but keeps its application sequence numbers synchronized by listening to the event stream. Upon promotion, it can immediately begin accepting commands.

**Primary:** `clob-primary.cmd`

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-primary.cmd
```

**Backup:** `clob-backup.cmd` (on a different telnet port — 7002)

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-backup.cmd
```

**Promotion (go-active):**

```
# Connect to backup shell (port 7002)
seq01b/start
```

**Demotion (go-passive):**

```
# Connect to primary shell (port 7001)
seq01a/stop
```

### 4.4 Multi-Tier (MoldUDP64)

A tiered topology for isolating the sequencer core from downstream consumers.

| Tier | Command File | Port | Role |
|------|-------------|------|------|
| Tier 0 | `clob-tier0.cmd` | 7001 | Sequencer only (no applications) |
| Tier 1 | `clob-tier1.cmd` | 7002 | `MoldRepeater` — retransmits events to Tier 2 |
| Tier 2 | `clob-tier2.cmd` | 7003 | Applications (ref data, injector, printer) |

Tier 0 publishes on `discovery_channel_tier0`. Tier 1 subscribes to Tier 0 and retransmits on `discovery_channel_tier1`. Tier 2 subscribes to Tier 1's discovery channel.

### 4.5 Dual Sequencer

The `clob-2seq.cmd` command file creates two sequencers (`seq01a`, `seq02a`) in a single process. Only one is active at a time. This is useful for testing failover within a single JVM.

---

## 5. High Availability

### 5.1 How Passive Mode Works

When the sequencer is inactive:

- It listens to events via `busServer.addEventListener(...)`.
- It updates application sequence numbers from the event stream via `Sequencer.onEvent()`.
- It does **not** accept commands or publish events.

This keeps the backup's internal state synchronized and prevents duplicate publishing.

### 5.2 Archive Recording

The `aeron-archive.cmd` command file configures an embedded Aeron Archive for the primary node:

```
source -s aeron-archive.cmd /tmp/archive
```

This creates an `ArchiveManager` that records the event stream locally. The recording can be used for:

- Subscriber catch-up via `ReplayMerge`
- Standby replication
- Post-mortem analysis and corefile export

### 5.3 Archive Replication

The `aeron-archive-standby.cmd` command file configures replication from the primary Archive to a local standby Archive:

```
source -s aeron-archive-standby.cmd /tmp/standby-archive
```

This creates an `ArchiveReplicator` that continuously copies recordings from the primary. The prerequisite is that `primary_control_channel` and `primary_control_stream` variables are set (from the primary's `aeron-archive.cmd`).

### 5.4 Archive Announcement

The `ArchiveAnnouncer` application publishes `ArchiveAnnouncement` messages on the event stream, allowing follower nodes to discover the Archive endpoint dynamically:

```
create archive01a com.core.clob.applications.ArchiveAnnouncer @/bus @/archiveManager ARCHIVE01 1
```

Follower nodes use `ArchiveLocator` to listen for these announcements:

```
create /archiveLocator com.core.platform.bus.aeron.ArchiveLocator @/bus
```

### 5.5 Failover Procedure

**Planned failover:**

1. Demote the primary: `seq01a/stop` on the primary shell.
2. Promote the backup: `seq01b/start` on the backup shell.
3. The promoted backup begins publishing heartbeats and accepting commands immediately.

**Unplanned failover (primary crash):**

1. Subscribers detect the primary's unavailable image.
2. The standby's `ArchiveReplicator` detects the primary loss.
3. Promote the standby: `seq01b/start`.
4. The standby's replicated recording becomes the authoritative replay source.
5. Subscribers reconnect via NAK recovery (small gap) or `ReplayMerge` (large gap).

### 5.6 Recovery Paths

| Scenario | Recovery Method | Time |
|----------|----------------|------|
| Transient packet loss | Aeron NAK retransmit (driver-level) | < 1ms |
| Small gap (data in term buffer) | NAK recovery, automatic | < 1ms |
| Large gap (data overwritten) | ReplayMerge from Archive | seconds |
| Node restart (snapshot exists) | Load snapshot + replay deltas + ReplayMerge | seconds |
| Node restart (no snapshot) | Full Archive replay + ReplayMerge | minutes/hours |
| Late joiner | Full Archive replay (or from snapshot) + ReplayMerge | seconds–minutes |
| Failover | Standby promotion + subscriber NAK/ReplayMerge | < 1s for standby |

### 5.7 Flow Control Rules

1. **Use `MaxMulticastFlowControl`** on event publications (default). A slow consumer cannot stall the sequencer.
2. **Use `TaggedMulticastFlowControl`** if standby nodes must be prioritized. Tag the standby's subscription so the sender's flow control tracks only the standby window.
3. **Never use `MinMulticastFlowControl`** on a trading event stream. A single slow consumer would halt order matching for all participants.
4. **Rely on NAK for transient loss** — sub-millisecond recovery with no application involvement.
5. **Rely on Archive replay for sustained loss** — `ReplayMerge` catches up from Archive and rejoins the live stream.

### 5.8 PromotionGuard

The `PromotionGuard` validates backup synchronization before allowing promotion. It wraps the promotion workflow with safety checks.

**Safety checks performed:**

1. Bus server is not already active.
2. At least one event has been received while passive.
3. No per-application sequence gaps detected.
4. No stale leader epochs detected.
5. Event silence exceeds threshold (default 3000ms) — the primary has stopped.
6. If a consensus module is configured, no other node holds an active lease.

**Usage:**

```
# Check sync status:
/ % promotionGuard/syncStatus

# Validate promotion safety:
/ % promotionGuard/promote

# Emergency bypass (use with caution):
/ % promotionGuard/forcePromote

# Reset gap detection after investigation:
/ % promotionGuard/resetGapDetection
```

### 5.9 Consensus Module (Split-Brain Fencing)

The consensus module provides lease-based fencing with monotonically increasing epochs.

**In-process consensus module** — prevents accidental dual-activation within a single JVM:

```
source consensus.cmd
source -s consensus-wire.cmd seq01a
```

**Distributed consensus (TCP)** — cross-JVM fencing via `TcpConsensusServer` and `TcpConsensusClient`:

```
# On consensus server node:
create /consensus com.core.platform.applications.sequencer.ConsensusModule
create /tcpConsensus com.core.platform.applications.sequencer.TcpConsensusServer @/consensus
/tcpConsensus/bind inet:0.0.0.0:9000

# On each sequencer node:
create /consensus com.core.platform.applications.sequencer.TcpConsensusClient
/consensus/connect 0.0.0.0:9000
set /seq01a/consensusModule @/consensus
set /promotionGuard/consensusModule @/consensus
```

**When wired to the sequencer:**

- The sequencer **will not activate** unless it acquires the consensus lease.
- The sequencer **self-deactivates** if the lease expires or is lost.
- The sequencer **drops commands** if the lease is invalid.
- The sequencer **releases** the lease on deactivation.
- Leader epoch is synchronized from the consensus epoch on activation.

**Shell commands:**

```
/ % consensus/status                    # view lease status
/ % consensus/acquire seq01a            # manually acquire lease
/ % consensus/renew seq01a              # manually renew lease
/ % consensus/release seq01a            # manually release lease
```

### 5.10 AutoFailover

The `AutoFailover` service monitors event stream health and automatically triggers sequencer promotion.

**How it works:**

1. Runs a periodic health check (default 1000ms).
2. Calls `PromotionGuard.promote()` to validate safety.
3. Requires 3 consecutive passes (configurable) before triggering promotion.
4. Resets the counter on any failed check.

**Configuration:**

```
create /autoFailover com.core.platform.applications.sequencer.AutoFailover @/promotionGuard @/seq01b

# Configure before enabling:
set /autoFailover/checkIntervalMs 1000
set /autoFailover/requiredConsecutivePasses 3

# Enable:
/autoFailover/enable

# Disable:
/autoFailover/disable

# Check status:
/autoFailover/status
```

---

## 6. Snapshots

### 6.1 Configuration

The `snapshot.cmd` command file configures the `SnapshotCoordinator`:

```
create /snapshotCoordinator com.core.platform.applications.snapshot.SnapshotCoordinator @/bus SNAPSHOT01
set /snapshotCoordinator/intervalNanos 300000000000
set /snapshotCoordinator/timeoutNanos 30000000000
```

| Property | Default | Description |
|----------|---------|-------------|
| `intervalNanos` | 300000000000 (5 min) | Interval between automatic snapshots |
| `timeoutNanos` | 30000000000 (30 sec) | Timeout for a snapshot round to complete |

Applications that participate in snapshots register with the coordinator by implementing the `Snapshottable` interface.

### 6.2 Snapshot Index

The `SnapshotIndex` tracks valid snapshots from event-stream messages:

```
create /snapshotIndex com.core.platform.applications.snapshot.SnapshotIndex @/bus
```

Query the snapshot index via the shell:

```
/ % snapshotIndex/status
```

### 6.3 Manual Trigger

To trigger an immediate snapshot via the shell:

```
/ % snapshotCoordinator/snapshot
```

### 6.4 Recovery from Snapshot

On restart, a node loads the latest snapshot and replays only the delta of events since the snapshot was taken. This reduces recovery time from hours (full replay) to seconds.

### 6.5 Late-Joiner Recovery

The `LateJoinerService` orchestrates the full recovery lifecycle for nodes joining a running cluster:

```
IDLE → SNAPSHOT_LOOKUP → SNAPSHOT_RESTORE → DELTA_REPLAY → LIVE
```

| Phase | Description |
|-------|-------------|
| `IDLE` | Not recovering |
| `SNAPSHOT_LOOKUP` | Finding the latest valid snapshot |
| `SNAPSHOT_RESTORE` | Restoring state from snapshot |
| `DELTA_REPLAY` | Replaying events from checkpoint to current |
| `LIVE` | Fully caught up, processing live events |

**Shell commands:**

```
/ % lateJoiner/status                 # view recovery progress
/ % lateJoiner/goLive                 # mark recovery complete
/ % lateJoiner/reset                  # reset to IDLE
```

---

## 7. Time Modes

The platform supports three time modes, selected by which `Time` implementation is registered at `/vm/time`.

### 7.1 System Time (Default)

Uses `CachedHighPrecisionTime` for nanosecond-resolution wall-clock time. This is the default when no time command file is sourced.

### 7.2 Sequencer-Driven Live (`time-synced.cmd`)

```
source -s time-synced.cmd
```

Creates a `SequencerDrivenTime` backed by the system clock. Follower nodes track sequencer time from event timestamps while using the local clock for scheduling. Use this for follower/downstream nodes that need time synchronized with the sequencer.

### 7.3 Replay Mode (`time-replay.cmd`)

```
source -s time-replay.cmd
```

Creates a `SequencerDrivenTime` with no system clock backing. Time advances **only** when events are received — purely driven by event timestamps. This enables deterministic distributed replay where every node processes events at exactly the recorded timestamps.

**Use cases:**

- Post-mortem analysis and debugging
- Regression testing against recorded event streams
- Deterministic replay of historical sessions

**Example replay node** (`clob-aeron-replay.cmd`):

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-replay.cmd /tmp/aeron
```

---

## 8. Logging

### 8.1 Stdout Logging (`sysout-log.cmd`)

Writes all log output to `System.out`:

```
source -s sysout-log.cmd
```

Creates a `SysoutChannel` and a `ChannelLogSink` writing to it.

### 8.2 File Logging (`file-log.cmd`)

Writes logs to a rolling log file:

```
source -s file-log.cmd vm.log
```

| Variable | Default | Description |
|----------|---------|-------------|
| `append_log_file` | `true` | Append to existing file (set to `false` to overwrite) |
| `$1` (positional) | — | Log file name (required) |

Creates a `RollingLogFile` and a `ChannelLogSink` backed by the file.

### 8.3 Startup Behavior

`LogFactory` starts with an in-memory `DirectBufferChannel` as its sink. This allows logging before the real sink is configured. When `logSink(...)` is invoked (by the log command file), the buffer is flushed to the new sink.

### 8.4 Runtime Log Control

Change log verbosity at runtime via the shell:

```
/ % vm/log/debugForAll true          # enable debug logging for all loggers
/ % vm/log/debugForAll false         # disable debug logging
/ % vm/log/debug "Mold" true         # enable debug for loggers matching "Mold"
/ % vm/log/debug "Sequencer" true    # enable debug for sequencer loggers
```

### 8.5 Log Format

Each log entry contains:

- Timestamp
- Log level
- Logger identifier (hierarchical name)
- Message text

The `Log` class writes directly into pre-allocated buffers — allocation-free on the hot path. Logging failures are intentionally swallowed to avoid cascading failures.

---

## 9. Metrics and Monitoring

### 9.1 Metrics Configuration (`metrics.cmd`)

```
source -s metrics.cmd vm01
```

This creates a `LogMetricPublisher` that writes metrics to a file and starts periodic publishing.

The platform also supports `UdpMetricPublisher` for publishing metrics via UDP multicast.

### 9.2 Metric Types

| Type | Description |
|------|-------------|
| Gauge | Numeric value that can increase or decrease |
| Switch | Boolean on/off state |
| State | Enumerated state value |

### 9.3 Key Metrics

Systems register metrics during initialization:

| Component | Metrics |
|-----------|---------|
| `Activator` | `Activator_Active`, `Activator_Ready`, `Activator_Started` |
| `MoldEventReceiver` | Sequence number gauges |
| `ArchiveReplicator` | Replication lag, recording position |
| `SnapshotIndex` | Snapshot count, latest snapshot position |
| `Sequencer` | `Sequencer_LeaderEpoch`, `Sequencer_CommandCount`, `Sequencer_CommandDropCount`, `Sequencer_CommandProcessedCount`, `Sequencer_DispatchErrorCount`, `Sequencer_LastCommandLatencyNanos`, `Sequencer_MaxCommandLatencyNanos` |
| `PromotionGuard` | `PromotionGuard_EventsReceived`, `PromotionGuard_GapDetected`, `PromotionGuard_PromotionChecksPassed`, `PromotionGuard_PromotionChecksFailed`, `PromotionGuard_RollingChecksum` |
| `AutoFailover` | `AutoFailover_Enabled`, `AutoFailover_ConsecutivePasses`, `AutoFailover_ChecksPerformed`, `AutoFailover_AutoPromotions` |
| `ConsensusModule` | `Consensus_Epoch`, `Consensus_LeaseHeld`, `Consensus_LeaseExpired`, `Consensus_LeaseRemainingMs` |
| `TcpConsensusClient` | `TcpConsensusClient_Epoch`, `TcpConsensusClient_RequestsSent`, `TcpConsensusClient_RequestsFailed`, `TcpConsensusClient_Connected` |
| `LateJoinerService` | `LateJoiner_Phase`, `LateJoiner_EventsReplayed`, `LateJoiner_RecoveryCount` |

### 9.4 Status Commands

Most runtime objects expose `status` commands that return structured maps:

```
/ % bus/status
/ % busServer/status
/ % busServer/eventReceiver/status
/ % seq01a/status
/ % archiveManager/status
/ % archiveLocator/status
/ % archiveReplicator/status
/ % snapshotCoordinator/status
/ % snapshotIndex/status
/ % vm/activation/state
/ % promotionGuard/syncStatus
/ % autoFailover/status
/ % consensus/status
/ % lateJoiner/status
/ % snapshotRecovery/status
```

### 9.5 Operational Monitoring Pattern

1. Use Telnet or WebSocket to query `status` endpoints.
2. Enable debug logging for targeted components.
3. Monitor metrics for lag, gaps, and event sequence drift.

---

## 10. Debugging Tools

### 10.1 Archive Debug Tool

Replays and decodes Aeron Archive recordings for inspection. Created via `archive-debug.cmd`:

```
source -s archive-debug.cmd
```

**Shell commands:**

```
/ % archiveDebug/replay <channel> <streamId> <recordingId> <startPos> <length>
/ % archiveDebug/replayByType <channel> <streamId> <recordingId> <startPos> <length> <messageType>
```

- `replay` — decodes and prints all messages in the specified recording range.
- `replayByType` — filters to a specific message type (e.g., `addOrder`, `fillOrder`).

### 10.2 Archive to Corefile Export

Exports Aeron Archive recordings to a binary corefile format (2-byte length prefix per message). Created via `archive-debug.cmd`:

```
/ % archiveExport/export <controlChannel> <controlStreamId> <recordingId> <startPos> <outputPath>
```

The exported corefile can be played back using the platform's `PlaybackBusClient` for offline analysis.

### 10.3 Corefile Playback

Use the `bus.playback` package to replay corefile recordings through the standard dispatcher. This enables:

- Offline analysis without a live Aeron driver
- Deterministic replay with `time-replay.cmd`
- Regression testing against historical data

### 10.4 Replay Node

A complete replay node configuration is provided in `clob-aeron-replay.cmd`:

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-replay.cmd /tmp/aeron
```

This configures a replay-mode time source and a printer to observe replayed events.

---

## 11. Command File Reference

### Platform Command Files (`platform/src/main/resources/`)

| File | Parameters | Description |
|------|-----------|-------------|
| `network-local.cmd` | — | Sets MoldUDP64 multicast addresses for local development |
| `aeron-network.cmd` | `<aeron_dir>` | Sets Aeron channel defaults (multicast addresses, stream IDs, session name) |
| `aeron-embedded.cmd` | `<aeron_dir>` | Creates an embedded Aeron Media Driver |
| `aeron-external.cmd` | `<aeron_dir>` | Uses an external Aeron Media Driver directory |
| `aeron-archive.cmd` | `<archive_dir>` | Configures an embedded Aeron Archive for the primary node |
| `aeron-archive-standby.cmd` | `<archive_dir>` | Configures an Aeron Archive replicator for the standby node |
| `archive-debug.cmd` | — | Creates Archive debug and export tools |
| `sysout-log.cmd` | — | Writes logs to stdout |
| `file-log.cmd` | `<log_file>` | Writes logs to a rolling log file |
| `telnet.cmd` | `<address>` | Opens a Telnet shell (e.g., `inet:0.0.0.0:7001`) |
| `http.cmd` | `<address>` | Opens an HTTP shell (e.g., `inet:0.0.0.0:8001`) |
| `ws.cmd` | `<address>` | Opens a WebSocket shell |
| `cli-shell.cmd` | — | Opens an interactive CLI shell on stdin/stdout |
| `metrics.cmd` | `<log_file>` | Creates a metric publisher writing to a log file |
| `snapshot.cmd` | — | Configures the snapshot coordinator (5-min interval, 30-sec timeout) |
| `consensus.cmd` | — | Creates the ConsensusModule for lease-based fencing |
| `consensus-wire.cmd` | `<sequencer>` | Wires a sequencer to the consensus module |
| `json-log.cmd` | — | Structured JSON logging for log aggregation (ELK/Splunk) |
| `time-synced.cmd` | — | Sequencer-driven time in live mode (for follower nodes) |
| `time-replay.cmd` | — | Sequencer-driven time in replay mode (deterministic) |

### CLOB Command Files (`clob/src/main/resources/`)

| File | Parameters | Description |
|------|-----------|-------------|
| `clob.cmd` | — | Standalone CLOB with MoldUDP64 transport |
| `clob-aeron.cmd` | `<aeron_dir>` | CLOB with Aeron transport (external driver) |
| `clob-aeron-embedded.cmd` | `<aeron_dir>` | CLOB with Aeron transport (embedded driver) |
| `clob-aeron-sbe.cmd` | `<aeron_dir>` | CLOB with Aeron transport and SBE schema |
| `clob-aeron-ha.cmd` | `<aeron_dir> <archive_dir>` | CLOB with Aeron, embedded Archive, HA support, and snapshots |
| `clob-aeron-follower.cmd` | `<aeron_dir>` | Follower node with sequencer-driven time and Archive locator |
| `clob-aeron-replay.cmd` | `<aeron_dir>` | Replay node with deterministic time from event timestamps |
| `clob-primary.cmd` | — | Primary sequencer with MoldUDP64 (HA pair) |
| `clob-backup.cmd` | — | Backup sequencer with MoldUDP64 (HA pair, port 7002) |
| `clob-2seq.cmd` | — | Dual sequencer in a single process (failover testing) |
| `clob-tier0.cmd` | — | Tier 0: sequencer only (no applications) |
| `clob-tier1.cmd` | — | Tier 1: MoldRepeater (retransmits events to downstream) |
| `clob-tier2.cmd` | — | Tier 2: applications (ref data, injector, printer) |

---

## 12. JVM Configuration

All run commands require the following JVM flag for high-precision time access:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

The shell path must be set to include the directories containing command files:

```
-DSHELL_PATH=platform/src/main/resources:clob/src/main/resources
```

The entry point is always:

```
java [JVM flags] -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main -s <command_file> [args...]
```
