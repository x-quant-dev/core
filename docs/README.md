# Core Sequencer Platform — Documentation

A low-latency, deterministic trading platform built around a single ordered event stream.
Every command flows through a central sequencer, producing a replayable event log that
guarantees identical state reconstruction — the foundation for zero-downtime failover,
time-travel debugging, and sub-microsecond determinism.

```
  ┌─────────────┐     ┌────────────────┐     ┌─────────────┐
  │  App A      │────►│                │────►│  App A      │
  │  (commands) │     │   Sequencer    │     │  (events)   │
  ├─────────────┤     │                │     ├─────────────┤
  │  App B      │────►│  assigns seq#  │────►│  App B      │
  │  (commands) │     │  publishes     │     │  (events)   │
  ├─────────────┤     │  ordered log   │     ├─────────────┤
  │  App C      │────►│                │────►│  App C      │
  │  (commands) │     └────────────────┘     │  (events)   │
  └─────────────┘                            └─────────────┘
    Command Channel                           Event Channel
```

**Key capabilities:** single-threaded event loop · zero-allocation hot path ·
deterministic replay · snapshot recovery · automatic HA failover ·
lease-based consensus (leader election) · binary message encoding ·
shell-driven configuration

---

## 🚀 Quick Start (5 minutes)

Build and run a complete trading system — sequencer, order book, and test injector:

```bash
./gradlew uberjar
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main -s clob.cmd
nc 0.0.0.0 7001          # then type: inject01a/submit LEHM01 Buy 100 AAPL 150
```

→ Full setup details: **[Getting Started](getting-started.md)**

---

## 📝 Build Your First Application (15 minutes)

Follow the **[Tutorial: Distributed Key/Value Store](tutorial-kv-store.md)** to build
a complete distributed application with HA failover — in under 100 lines of code.

You'll learn: schema definition · command handling · event-driven state · shell integration · HA failover

---

## 📖 Reading Guide

Documentation is organized from simple to advanced. Start at the top and
work your way down — or jump to what you need.

### 1. Get Running

| Document | What You'll Learn |
|----------|------------------|
| **[Getting Started](getting-started.md)** | Build, run, connect to the shell, submit your first order |
| **[Overview](overview.md)** | Architecture at a glance — modules, data flow, key components |
| **[Tutorial: KV Store](tutorial-kv-store.md)** | Build a distributed app with HA failover in ~100 lines |

### 2. Build Applications

| Document | What You'll Learn |
|----------|------------------|
| **[Developer Manual](developer-manual.md)** | Write applications, define message schemas, test with replay |
| **[Command Files](command-files.md)** | Shell syntax, `.cmd` file format, all platform commands explained |

### 3. Understand the Core

| Document | What You'll Learn |
|----------|------------------|
| [Sequencer](sequencer.md) | Command validation, event ordering, activation lifecycle |
| [Bus Architecture](bus-architecture.md) | Command/event channels, heartbeating, gap detection |
| [Activation & Lifecycle](activation-and-lifecycle.md) | Activator graph, go-active/go-passive state machine |
| [Selector & I/O](selector-io-model.md) | NIO selector abstraction, event loop integration |
| [Time & Clock](time-and-clock.md) | Time interface, ManualTime, distributed time, backtesting |
| [Logging & Metrics](logging-metrics-instrumentation.md) | Allocation-free logging, metrics, shell status endpoints |

### 4. Operate in Production

| Document | What You'll Learn |
|----------|------------------|
| **[User Manual](user-manual.md)** | Configuration, deployment, monitoring, HA setup |
| **[Operations Runbook](operations-runbook.md)** | Rolling updates, failover procedures, troubleshooting |

### 5. Advanced: HA, Snapshots & Recovery

These documents cover advanced topics for production deployments. Skip on
first read unless you're setting up high availability.

| Document | What You'll Learn |
|----------|------------------|
| [HA Architecture](ha-architecture.md) | Primary/backup topology, failover modes, failure scenarios |
| [Consensus Module](consensus-module.md) | Lease-based fencing, epoch tokens, automatic leader election |
| [Snapshot Service](snapshot-service.md) | Coordinated snapshots, persistence, recovery workflow |
| [Deterministic Snapshots](deterministic-snapshots.md) | Rules for `Snapshottable` implementations |
| [Event Journal & Replay](event-journal-replay.md) | Journal persistence, replay workflow, production debugging |

### 6. Advanced: Transport Deep Dives

| Document | What You'll Learn |
|----------|------------------|
| [MoldUDP64 Transport](message-bus-moldudp64.md) | MoldUDP64 protocol, sessions, sequence numbers, replay |
| [Aeron Archive HA](aeron-archive-ha.md) | Archive recording, ReplayMerge, standby replication |

### 7. Design History

Internal planning documents preserved for design context and rationale.
These may not reflect the current codebase.

→ **[Platform Architecture Uplift](design-history/platform-architecture-uplift.md)**

---

## Quick Reference

| I want to... | Go to |
|--------------|-------|
| Build and run the demo | [Getting Started](getting-started.md) |
| Build a sample application | [Tutorial: KV Store](tutorial-kv-store.md) |
| Write a new application | [Developer Manual](developer-manual.md) |
| Add a new message type | [Developer Manual](developer-manual.md) § Schema Definition |
| Configure HA failover | [User Manual](user-manual.md) § High Availability |
| Troubleshoot a production issue | [Operations Runbook](operations-runbook.md) |
| Understand the sequencer | [Sequencer](sequencer.md) |
| Set up consensus/leader election | [Consensus Module](consensus-module.md) |
| Configure snapshots | [User Manual](user-manual.md) § Snapshots |
| Debug with replay | [Event Journal & Replay](event-journal-replay.md) |
| Understand command files | [Command Files](command-files.md) |
