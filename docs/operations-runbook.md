# Core Sequencer Platform — Operations Runbook

## 1. Pre-Flight Checks

Validate node health using shell commands (connect via `nc 0.0.0.0 <port>`):

| Endpoint | Purpose |
|----------|---------|
| `/bus/status` | Bus client state and session |
| `/busServer/status` | Bus server state |
| `/seq01a/status` | Sequencer status including leaderEpoch, commandCount, latency |
| `/vm/activation/state` | Full activation graph |
| `/health/status` | Health endpoint (live/ready/leader) |
| `/health/schema` | SBE schema compatibility status + rejected version count |
| `/cluster/nodes` | Cluster-wide node registry |
| `/promotionGuard/syncStatus` | Backup sync state: events received, gap detection, epoch tracking |
| `/autoFailover/status` | Auto-failover state: enabled, consecutive passes, checks performed |
| `/consensus/status` | Consensus lease status: epoch, holder, remaining time |
| `/tcpConsensus/status` | TCP consensus server: connections, requests received |
| `/lateJoiner/status` | Late-joiner recovery phase and progress |
| `/snapshotRecovery/status` | Snapshot recovery state: last recovered snapshot, count |

## 2. Rolling Update — Aeron Transport

Step-by-step zero-downtime rolling update.

### Step 1: Pre-Update Validation

```
# On primary node (port 7001):
nc 0.0.0.0 7001
/ % seq01a/status
/ % busServer/status  
/ % health/status
/ % health/schema
/ % snapshotCoordinator/status
```

### Step 2: Trigger Pre-Failover Snapshot

```
/ % snapshotCoordinator/snapshot
```

Wait for `SnapshotComplete { valid=true }` in logs.

### Step 3: Verify Standby Sync

```
# On standby node (port 7002):
nc 0.0.0.0 7002
/ % promotionGuard/syncStatus
/ % bus/status
```

Confirm: `eventSilenceMs < 200`, `eventsReceived > 0`, `promotionSafe = true` (will show `false` while primary is still active — this is expected, check after step 5).

### Step 4: Demote Primary

```
# On primary (port 7001):
/ % seq01a/stop
```

### Step 5: Validate Promotion Safety

```
# On standby (port 7002), after primary stopped:
/ % promotionGuard/syncStatus
```

Wait until `eventSilenceMs > eventSilenceThresholdMs` (default 3000ms). `promotionSafe` should now be `true`.

### Step 6: Promote Standby

```
/ % promotionGuard/promote
# If "PROMOTION CHECKS PASSED", then:
/ % seq01b/start
```

### Step 7: Verify New Primary

```
/ % seq01b/status
/ % health/leader
```

Confirm `leaderEpoch` incremented, `commandCount` advancing.

### Step 8: Deploy New Code to Old Primary

Stop old primary process, deploy new JAR, restart as PASSIVE standby:

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-backup.cmd
```

### Step 9: Verify New Standby

```
# On new standby:
/ % bus/status
/ % promotionGuard/syncStatus
```

Confirm events are being received.

### Step 10: Post-Update Validation

Verify on all nodes: event flow continuous, no gaps, all applications healthy.

## 3. Rolling Update — MoldUDP64 Transport

Same procedure using `clob-primary.cmd` / `clob-backup.cmd`. MoldUDP64 uses `BufferChannelMessageStore` (in-memory) for demo or `FileChannelMessageStore` for production. Gap recovery uses the discovery channel and `MoldRewinder`.

Key differences:
- No Archive recording/replication status to check
- Gap recovery via `MoldRewinder` instead of `ReplayMerge`
- Session name must match between primary and backup

## 4. Planned Failover

```
1. Announce maintenance window
2. Trigger snapshot:             /snapshotCoordinator/snapshot
3. Verify standby sync:          /promotionGuard/syncStatus (on standby)
4. Demote primary:               /seq01a/stop (on primary)
5. Wait for event silence:       3+ seconds
6. Promote standby:              /seq01b/start (on standby)
7. Verify event continuity:      /seq01b/status — commandCount advancing
8. Monitor for 5 minutes:        /health/status on all nodes
```

## 5. Emergency Failover (Primary Crash)

```
1. Detect: event silence on all subscribers, heartbeat timeout exceeded
2. On standby: /promotionGuard/syncStatus — check eventsReceived > 0
3. On standby: /promotionGuard/forcePromote (bypasses silence check since primary crashed)
4. On standby: seq01b/start
5. Monitor subscriber recovery (NAK for small gaps, ReplayMerge for large)
6. Investigate failed primary
7. Optionally restart failed primary as new standby
```

## 6. Node Recovery After Crash

### With Snapshot (Fast — seconds)

```
1. Restart node with appropriate command file
2. Node automatically: loads latest snapshot → replays deltas → ReplayMerge with live
3. Monitor: /bus/status — watch sequence numbers advancing
4. Ready when: /vm/activation/state shows all activators ACTIVE
```

### Without Snapshot (Slow — minutes to hours)

```
1. Restart node
2. Node replays entire event stream from Archive position 0
3. Monitor progress via logs
4. Ready when activation completes
```

## 7. Snapshot Operations

| Operation | Command |
|-----------|---------|
| Trigger manual snapshot | `/snapshotCoordinator/snapshot` |
| Check snapshot validity | `/snapshotIndex/status` |
| Check coordinator status | `/snapshotCoordinator/status` |
| Change interval (nanos) | `set /snapshotCoordinator/intervalNanos 60000000000` (1 min) |
| Change timeout (nanos) | `set /snapshotCoordinator/timeoutNanos 10000000000` (10 sec) |

## 8. Archive Operations

| Operation | Command |
|-----------|---------|
| Check recording status | `/archiveManager/status` |
| Check replication lag | `/archiveReplicator/status` |
| Check Archive location | `/archiveLocator/status` |
| Debug recording contents | `/archiveDebug/replay <channel> <streamId> <recordingId> <startPos> <length>` |
| Filter by message type | `/archiveDebug/replayByType <channel> <streamId> <recordingId> <startPos> <length> <msgType>` |
| Export to corefile | `/archiveExport/export <controlChannel> <controlStreamId> <recordingId> <startPos> <outputPath>` |

## 9. Consensus Module (Split-Brain Fencing)

The consensus module provides lease-based fencing to prevent split-brain scenarios where two sequencers are active simultaneously.

> **⚠️ Important:** The default ConsensusModule is an in-process lease service. It prevents accidental dual-activation within a single JVM but does NOT provide distributed consensus across nodes. For true cross-node split-brain prevention, deploy the consensus module as a separate centralized process on an independent failure domain.

### Setup

Source `consensus.cmd` and `consensus-wire.cmd` in your startup command file, after creating the sequencer:

```
source consensus.cmd
source -s consensus-wire.cmd seq01a
```

When the consensus module is wired to the sequencer:
- The sequencer **will not activate** unless it acquires the consensus lease
- The sequencer **self-deactivates** if the consensus lease expires (not renewed during heartbeat)
- The sequencer **drops commands** if the lease is lost between heartbeats (GC pause safety)
- The sequencer **releases** the lease on deactivation
- The sequencer **syncs leaderEpoch** from the consensus epoch on activation
- The sequencer **propagates leaderEpoch** in the heartbeat header's optional fields (fencing token)
- PromotionGuard checks consensus lease **and** event sequence continuity before allowing promotion

### Operations

| Operation | Command |
|-----------|---------|
| Acquire lease | `/consensus/acquire <nodeId>` |
| Renew lease | `/consensus/renew <nodeId>` |
| Release lease | `/consensus/release <nodeId>` |
| Check status | `/consensus/status` |

### Promotion with Consensus Module (Automated)

When wired via `consensus-wire.cmd`, the sequencer handles lease lifecycle automatically:

```
1. /promotionGuard/promote        — checks consensus lease is available
2. /seq01b/start                  — sequencer auto-acquires lease on activation
3. Heartbeats auto-renew the lease
4. /seq01b/stop                   — sequencer auto-releases lease on deactivation
```

For manual lease management (without wiring):

```
1. /consensus/acquire seq01b
2. /promotionGuard/promote
3. /seq01b/start
4. Periodically: /consensus/renew seq01b
5. /consensus/release seq01b
```

### Emergency Failover with Consensus Module

```
1. Check if lease expired: /consensus/status
2. If expired, acquire: /consensus/acquire seq01b
3. Force promote: /promotionGuard/forcePromote
4. Start: /seq01b/start
```

## 10. Configuration Validation

```
# Validate required config variables are set:
/ % config/validate

# Dump all configuration:
/ % config/dump

# Get config hash (for drift detection between nodes):
/ % config/hash
```

Compare config hashes across nodes — they should match for nodes in the same role.

### Structured JSON Logging

For log aggregation (ELK/Splunk), use `json-log.cmd` instead of `sysout-log.cmd`:

```
source json-log.cmd
```

Output format:
```json
{"timestamp":"2024-01-15T10:30:45.123","level":"INFO","logger":"/vm01/Sequencer","message":"sequencer activated: epoch=1"}
```

## 11. Correlation ID Tracing

The `optionalFieldsIndex` header field (offset 14, 2 bytes) can be used as a correlation token to trace commands through to events. The sequencer preserves this field from inbound commands to outbound events.

| Operation | Command |
|-----------|---------|
| Check last correlation ID | `/seq01a/lastCorrelationId` |

Applications can set the correlation field on outbound commands and then match it on received events for end-to-end tracing.

## 12. Troubleshooting

| Symptom | Likely Cause | Resolution |
|---------|-------------|------------|
| "command received with incorrect appSeqNum" | Duplicate or out-of-order command | Check command publisher retransmission; verify app sequence numbers |
| Activation graph not ready | Missing dependency | `/vm/activation/state` — find the activator that is not active |
| Archive replication stalled | Network issue or primary down | Check primary Archive status; verify control channel connectivity |
| High commandLatencyNanos | GC pause, thread contention, network | Check GC logs; ensure `--add-opens` flag is set; verify no blocking I/O |
| Event gaps on subscriber | Packet loss | MoldUDP64: discovery channel rewind. Aeron: NAK recovery or ReplayMerge |
| promotionSafe = false | Primary still active or standby not synced | Wait for event silence or check eventsReceived |
| sequenceGapDetected = true | Packet loss or out-of-order events while passive | Check `/promotionGuard/syncStatus` for gap details (appId, expected/actual seq). Investigate event transport. |
| "bus server does not support event listening" | Aeron transport cannot receive events on server | Backup sync requires a separate AeronBusClient subscription; cannot validate via PromotionGuard |
| Split-brain suspected | Two sequencers active | Check `/consensus/status` — only one node should hold the lease. Demote the one without the lease. Compare leaderEpoch on both nodes. |
| "dropping command: consensus lease lost" | GC pause or stall exceeded lease timeout | Sequencer self-deactivated after detecting expired lease during command processing. Investigate pause cause. |
| Consensus lease denied on activation | Another node holds the lease | Check `/consensus/status` for current holder. Wait for expiry or release the lease from the other node. |
| "auto-failover check failed" in logs | PromotionGuard checks not passing | Check `/promotionGuard/syncStatus` for the specific failure reason. |
| LateJoiner stuck in SNAPSHOT_RESTORE | Snapshot data corrupted or missing | Check `/snapshotIndex/status` for valid snapshots. Reset with `/lateJoiner/reset` and retry. |
| Rolling checksum mismatch across nodes | Event stream divergence | Compare `/promotionGuard/syncStatus` checksum values across nodes. Investigate network or transport issues. |

## 13. Fencing Token (Leader Epoch)

The sequencer propagates `leaderEpoch` as a 2-byte fencing token in the heartbeat header's optional fields offset. When the consensus module is wired, `leaderEpoch` is synchronized from the consensus epoch on activation. This allows downstream consumers to detect stale-leader output by tracking the highest epoch seen in heartbeats.

| Operation | Command |
|-----------|---------|
| Check current epoch | `/seq01a/leaderEpoch` |
| Check consensus epoch | `/consensus/status` |

## 14. Thread Safety (Dev Mode)

Set `-Dcore.threadChecks=true` to enable dev-only thread identity validation. When enabled, `@Command` methods on Sequencer, ConsensusModule, and PromotionGuard will throw `IllegalStateException` if invoked from a thread other than the event loop. Disabled by default (zero overhead in production).
