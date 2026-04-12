# Sequencer Implementation Details

The sequencer is the only component allowed to publish events when it is active. It is responsible for validating command ordering per application, dispatching commands to handlers, and publishing events onto the bus.

Primary implementation: `com.core.platform.applications.sequencer.Sequencer`.

## Responsibilities

- Accept commands from the command channel while active.
- Reject or drop commands that violate per-application ordering.
- Dispatch validated commands through the schema dispatcher.
- Publish all resulting events to the event channel.
- When inactive, listen to events to update application sequence numbers and keep state consistent.
- Periodically emit heartbeats and publish its own application definition message.

## Message Ordering Model

Each application publishes commands with its own `applicationId` and a monotonic `applicationSequenceNumber`.

- The sequencer maintains per-application sequence numbers via `BusServer` (`AbstractBusServer` stores them in an array).
- When a command arrives:
  - If it is an application definition message with sequence number 1, it is always accepted.
  - Otherwise, the sequencer increments the expected app sequence number and compares.
  - If the message matches the expected number, it is dispatched and sent onward.
  - If it does not, the sequencer rolls back the expected number and drops the command.

Code path:

- `Sequencer.onCommand` validates header length, extracts app id/seq num, checks against `BusServer` state, and dispatches or logs the error.
- `Sequencer.onEvent` runs when the sequencer is inactive and updates the per-application sequence numbers based on received events.

This prevents out-of-order commands from mutating system state and keeps application sequence numbers deterministic.

## Heartbeats and App Definition

Sequencer publishes:

- **Application definition**: emitted on startup or when app id is zero. This message binds the sequencer's application id in the stream.
- **Heartbeat**: periodic messages (default 100ms) sent via the scheduler.

Implementation details:

- Encoders are created using the schema and pre-wrapped buffers.
- The heartbeat task is scheduled using the `Scheduler` via `scheduleEvery`.
- The actual send path reuses `onCommand` to validate and dispatch, maintaining ordering invariants.

## Activation

Sequencer implements `Activatable` and is managed by an `Activator` (created in constructor).

- When activated (`activate`):
  - Schedules heartbeat.
  - Marks its activator ready.
  - Sends an initial heartbeat (which also triggers app definition if missing).
- When deactivated (`deactivate`):
  - Cancels heartbeat.
  - Marks itself not ready.

Sequencer depends on the bus server's activator to be active before it can become active.

## Interaction With BusServer

The `BusServer` is the abstraction for sending events and receiving commands. The sequencer registers:

- An event listener to `busServer.addEventListener(...)` for passive mode.
- A command listener via `busServer.setCommandListener(...)` for active mode.

It uses `busServer.send()` to flush any staged events. In MoldUDP64, events are buffered and packetized; `send()` transmits current packet(s).

## Failure Behavior

The sequencer logs and drops invalid commands. It does not attempt reordering or retransmission for commands. The expectation is that command publishers maintain their own app seq num and use the event stream to confirm command acceptance.

## Thread Safety

The sequencer uses `ThreadIdentityGuard` to enforce single-threaded access. Both `onCommand` and `onEvent` call `threadGuard.check()` at the top of the method, which throws `IllegalStateException` if invoked from a thread other than the event loop. This is enabled by setting `-Dcore.threadChecks=true`.

## Fail-Stop on Dispatch Errors

Both the active path (`onCommand` → `dispatchAndRecord`) and the passive path (`onEvent`) wrap dispatcher calls in try/catch. On any dispatch exception:

1. `dispatchErrorCount` is incremented.
2. A fatal error is logged.
3. `activator.stop()` is called to deactivate the sequencer.

This fail-stop behavior prevents state divergence between primary and backup nodes.

## Consensus Lease Integration

When a `Consensus` is configured (via `set /seq01a/consensusModule @/consensus`):

- **Activation**: the sequencer calls `consensusModule.tryAcquire(applicationName)`. If denied, the sequencer refuses to activate.
- **Leader epoch**: on successful acquisition, `leaderEpoch` is synchronized from `consensusModule.getEpoch()`.
- **Command processing**: every command checks the lease. If the lease is invalid or expired, the command is dropped and the sequencer self-deactivates.
- **Heartbeat**: the heartbeat task renews the lease via `consensusModule.tryRenew()`. If renewal fails, the sequencer self-deactivates.
- **Deactivation**: the lease is released via `consensusModule.tryRelease()`.

## SBE Schema Version Compatibility

When the dispatcher is an `SbeDispatcher`, the sequencer tracks rejected schema versions. During passive event processing, it logs a warning the first time a rejected version is detected (via `warnOnRejectedSchemaVersion()`). The rejected count is exposed as the `Sequencer_SbeRejectedVersionCount` metric.

## Stale Epoch Detection

In passive mode, the sequencer tracks `lastSeenEpoch` from event headers. If an event arrives with a leader epoch older than the last seen epoch, it is logged and dropped. This prevents stale-leader events from corrupting backup state.

## Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `Sequencer_LeaderEpoch` | Gauge | Current leader epoch (fencing token) |
| `Sequencer_CommandCount` | Gauge | Total commands processed |
| `Sequencer_CommandDropCount` | Gauge | Commands dropped due to ordering or validation |
| `Sequencer_CommandProcessedCount` | Gauge | Commands successfully dispatched |
| `Sequencer_DispatchErrorCount` | Gauge | Dispatch exceptions (triggers fail-stop) |
| `Sequencer_LastCommandLatencyNanos` | Gauge | Latency of the last processed command |
| `Sequencer_MaxCommandLatencyNanos` | Gauge | Maximum command latency (resettable via `resetLatency`) |
| `Sequencer_LastCorrelationId` | Gauge | Correlation ID from the last command header |
| `Sequencer_SbeRejectedVersionCount` | Gauge | SBE schema version rejections (SBE dispatcher only) |

