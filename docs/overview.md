# Architecture Overview

This repository implements a low-latency sequencer-centric trading platform with a hierarchical command shell, a single ordered event stream, and no flow control (clients detect gaps and recover via replay). The core is divided into the following modules:

- `infrastructure`: allocation-free utilities, I/O, message schema/encoders, time, logging, and metrics.
- `platform`: sequencer architecture, bus implementations, activation lifecycle, and shell-based runtime orchestration.
- `clob`: sample domain implementation built on top of the platform.
- `buildSrc`: code generation for binary encoders/decoders and FIX.

## Runtime Topology

At runtime, a VM is driven by `com.core.platform.Main`, which instantiates the event loop, selector, scheduler, log/metrics factories, the activation system, and the command shell. Everything else is loaded via command files using the shell. This makes configuration and topology dynamic while keeping startup deterministic.

Key runtime components:

- **Event Loop**: combines scheduler + selector to deliver time-driven and I/O-driven events.
- **Bus**: a moldudp64-based transport for ordered events and command ingress.
- **Sequencer**: the only active publisher of events; validates command ordering.
- **Applications**: subscribe to events, publish commands, all through the bus.
- **Activation Graph**: dependencies ensure components only run when prerequisites are ready.
- **PromotionGuard**: validates backup synchronization before allowing sequencer promotion.
- **ConsensusModule**: lease-based fencing to prevent split-brain (in-process or distributed via TCP).
- **AutoFailover**: automatic promotion based on event stream health monitoring.
- **SnapshotCoordinator**: coordinates periodic snapshots across all nodes for fast recovery.
- **LateJoinerService**: orchestrates snapshot restore → delta replay → live for joining nodes.

## Data Flow Summary

1. Applications create `Provider` instances on the bus to send commands.
2. Commands are multicast over the command channel to the sequencer.
3. The sequencer validates per-application sequence numbers, dispatches messages, and publishes events on the event channel.
4. Event listeners on clients dispatch events in order, tracking gaps and requesting replays.
5. Message stores persist the event stream and support replay for catch-up.

## Sequencing and Ordering

Ordering is global: every event is published in a single ordered stream (session-based sequence numbers). There is no flow control. Instead:

- **Producers** always send at their pace.
- **Consumers** detect gaps via sequence numbers, request replays, and rejoin.
- **Rewinders** serve replay data from the message store.

This design prioritizes deterministic ordering and low-latency publish over backpressure. It relies on fast replay and short-lived gaps for recovery.

## Where To Look

- Sequencer logic: `platform/src/main/java/com/core/platform/applications/sequencer/Sequencer.java`.
- Bus + MoldUDP64: `platform/src/main/java/com/core/platform/bus/mold/`.
- Activation graph: `platform/src/main/java/com/core/platform/activation/`.
- Shell + command execution: `platform/src/main/java/com/core/platform/shell/`.
- Selector/I/O: `infrastructure/src/main/java/com/core/infrastructure/io/`.
- Logging: `infrastructure/src/main/java/com/core/infrastructure/log/`.
- Metrics: `infrastructure/src/main/java/com/core/infrastructure/metrics/`.
- PromotionGuard / AutoFailover / Consensus: `platform/src/main/java/com/core/platform/applications/sequencer/`.
- Snapshots / Recovery: `platform/src/main/java/com/core/platform/applications/snapshot/`.

