# Logging, Metrics, Instrumentation, and Status

The platform provides low-latency logging, metrics collection, and a status/inspection API via the shell. These facilities are designed to be allocation-free where possible and to integrate with the event loop without blocking.

## Logging

Key classes:

- `LogFactory`: creates `Log` instances and manages the sink.
- `Log`: allocation-friendly logger with append-style API.
- `ChannelLogSink`: writes log entries to a `WritableBufferChannel`.
- `RollingLogFile`: sets up file-backed logging with optional roll-over.

### Startup Behavior

`LogFactory` starts with an in-memory `DirectBufferChannel` as its sink. This allows logging before the real sink is configured. When `logSink(...)` is invoked, the buffer is flushed to the new sink.

### Log Structure

`ChannelLogSink` formats:

- timestamp
- log level
- log identifier (hierarchical name)
- message text

The `Log` class writes directly into pre-allocated buffers and commits to the sink. Logging failures are intentionally swallowed to avoid cascading failures in low-latency flows.

### Command Usage

The command files provide quick ways to set logging:

- `sysout-log.cmd` uses `SysoutChannel` to log to stdout.
- `file-log.cmd` uses `RollingLogFile` and a `ChannelLogSink` to log to a file.

Log verbosity can be changed at runtime via:

- `/vm/log/debugForAll true`
- `/vm/log/debug "Mold" true`

## Metrics

Key classes:

- `MetricFactory`: registry for gauge/switch/state metrics.
- `UdpMetricPublisher`: publishes metrics periodically on a UDP multicast channel.
- `LogMetricPublisher`: writes metrics to a log file.

### Metric Registration

Systems register metrics as they initialize. Examples:

- `Activator` registers `Activator_Active`, `Activator_Ready`, `Activator_Started`.
- `MoldEventReceiver` registers sequence number gauges.
- `Sequencer` registers `Sequencer_LeaderEpoch`, `Sequencer_CommandCount`, `Sequencer_CommandDropCount`, `Sequencer_CommandProcessedCount`, `Sequencer_DispatchErrorCount`, `Sequencer_LastCommandLatencyNanos`, `Sequencer_MaxCommandLatencyNanos`, `Sequencer_LastCorrelationId`, `Sequencer_SbeRejectedVersionCount`.
- `PromotionGuard` registers `PromotionGuard_EventsReceived`, `PromotionGuard_GapDetected`, `PromotionGuard_PromotionChecksPassed`, `PromotionGuard_PromotionChecksFailed`, `PromotionGuard_AutoPromoteAttemptCount`, `PromotionGuard_AutoPromoteEnabled`, `PromotionGuard_StaleEpochDetected`, `PromotionGuard_RollingChecksum`, `PromotionGuard_ChecksumMessageCount`.
- `AutoFailover` registers `AutoFailover_Enabled`, `AutoFailover_ConsecutivePasses`, `AutoFailover_ChecksPerformed`, `AutoFailover_AutoPromotions`.
- `ConsensusModule` registers `Consensus_Epoch`, `Consensus_LeaseHeld`, `Consensus_LeaseExpired`, `Consensus_LeaseRemainingMs`, `Consensus_AcquireDeniedCount`, `Consensus_RenewFailureCount`.
- `TcpConsensusServer` registers `TcpConsensus_RequestsReceived`, `TcpConsensus_ClientsAccepted`.
- `TcpConsensusClient` registers `TcpConsensusClient_Epoch`, `TcpConsensusClient_RequestsSent`, `TcpConsensusClient_RequestsFailed`, `TcpConsensusClient_Connected`.
- `LateJoinerService` registers `LateJoiner_Phase`, `LateJoiner_EventsReplayed`, `LateJoiner_RecoveryCount`, `LateJoiner_RecoveredCheckpointSeqNum`.
- `SnapshotIndex` registers `Snapshot_ValidCount`, `Snapshot_LatestCheckpointSeqNum`.

Labels can be applied globally or per metric to provide context (e.g., `vm`, `bus`, `address`).

### Publishing

- `UdpMetricPublisher` encodes metrics to a binary format and sends them to a multicast address.
- `LogMetricPublisher` formats metrics as text log entries.

Both publishers run on the scheduler at a configurable interval (default 10s).

## Status and Instrumentation

Most runtime objects implement `Encodable` or expose `status` commands. Examples:

- `/bus/status`
- `/busServer/status`
- `/busServer/eventReceiver/status`
- `/vm/activation/state`
- `/seq01a/status` (sequencer status including leader epoch, command counts, latency)
- `/promotionGuard/syncStatus` (backup sync state, gap detection, consensus lease)
- `/autoFailover/status` (auto-failover state, consecutive passes)
- `/consensus/status` (consensus lease epoch, holder, expiry)
- `/snapshotCoordinator/status` (snapshot coordinator state)
- `/snapshotIndex/status` (valid snapshot count, latest checkpoint)
- `/snapshotRecovery/status` (recovery state, last recovered snapshot)
- `/lateJoiner/status` (recovery phase, events replayed)

These return structured maps that can be rendered in the shell output. This forms the primary instrumentation and monitoring interface alongside logs and metrics.

## Operational Patterns

Typical operator flow:

1. Use Telnet or WebSocket to query `status` endpoints.
2. Enable debug logging for targeted components.
3. Monitor metrics for lag, gaps, and event sequence drift.

