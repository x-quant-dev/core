# Consensus Module (Split-Brain Fencing)

## What Is a Consensus Module?

The **Consensus Module** is a lease-based fencing mechanism that prevents **split-brain** — the catastrophic scenario where two sequencer nodes both believe they are the active primary and simultaneously assign sequence numbers, corrupting the event log.

Before a sequencer can go active (start sequencing commands into events), it must **acquire a lease** from the consensus module. The consensus module guarantees that **at most one lease is active at any time**. Each lease acquisition increments a monotonically increasing **epoch** (fencing token) that can be embedded in events to detect stale leadership.

### The Core Contract

```
1. At most one node holds the lease at any time.
2. Each lease grant increments the epoch.
3. Leases expire if not renewed within the timeout.
4. A node MUST hold the lease before sequencing events.
5. The epoch acts as a fencing token — stale epochs are rejected.
```

---

## Why Is It Needed?

In a primary/backup sequencer topology, the backup passively consumes the event stream and maintains state. When the primary fails, the backup must be promoted. But several dangerous scenarios can arise:

| Scenario | Risk | How the Consensus Module Helps |
|----------|------|----------------------|
| **Network partition** | Primary is alive but unreachable; backup thinks it's dead | Consensus module is on an independent failure domain — both nodes can reach it. Only the lease holder may sequence. |
| **Slow primary** | Primary pauses (GC, I/O stall); backup sees event silence and promotes | Primary's lease expires during the pause. When it resumes, its `tryRenew` fails, forcing it to step down. |
| **Operator error** | Operator accidentally starts two primaries | Second node's `tryAcquire` is denied because the first already holds the lease. |
| **Race condition** | Two nodes try to promote simultaneously | The consensus module serialises `tryAcquire` calls — only one succeeds, the other is denied. |

Without a consensus module, the `PromotionGuard`'s event-silence-based detection is the only safeguard, and it cannot distinguish "primary is dead" from "primary is temporarily slow/partitioned."

---

## How It Works

### Lease Lifecycle

```
        ┌──────────────────────────────────────────────────┐
        │                  ConsensusModule                  │
        │                                                  │
        │  State:                                          │
        │    leaseHolder: String (nullable)                │
        │    epoch: int (monotonically increasing)         │
        │    leaseRenewedNanos: long (last renewal time)   │
        │    leaseTimeoutMs: long (default 5000ms)         │
        │                                                  │
        │  Invariant: epoch only increases, never resets   │
        └──────────────────────────────────────────────────┘

   Node A                  Consensus Module                Node B
     │                          │                           │
     │── tryAcquire("A") ──────►│                           │
     │                          │ leaseHolder=null → "A"    │
     │                          │ epoch: 0 → 1              │
     │◄── OK (epoch=1) ────────│                           │
     │                          │                           │
     │── tryRenew("A") ────────►│                           │
     │                          │ leaseRenewedNanos=now     │
     │◄── OK ──────────────────│                           │
     │                          │                           │
     │                          │           tryAcquire("B") │
     │                          │◄──────────────────────────│
     │                          │ leaseHolder="A", not expired
     │                          │ → DENIED                  │
     │                          │──────────────── DENIED ──►│
     │                          │                           │
     │  (Node A crashes)        │                           │
     │  ✗                       │                           │
     │                          │  (5s timeout expires)     │
     │                          │  leaseHolder="A" EXPIRED  │
     │                          │                           │
     │                          │           tryAcquire("B") │
     │                          │◄──────────────────────────│
     │                          │ expired → grant lease     │
     │                          │ leaseHolder="A" → "B"     │
     │                          │ epoch: 1 → 2              │
     │                          │──────────────── OK (2) ──►│
```

### Epoch as Fencing Token

The epoch prevents a revived old primary from corrupting the stream. When the primary recovers after a failover:

1. Its `tryRenew` fails (it no longer holds the lease).
2. Even if it somehow sends a late event, the epoch in the event header is stale.
3. The `PromotionGuard` on all nodes detects `staleEpochDetected` and flags it.

This is the same concept as **fencing tokens** in distributed systems literature (Lamport, Chubby, ZooKeeper).

---

## Deployment Topology

### In-Process (Single JVM)

The `ConsensusModule` runs in the same JVM as the sequencer. This prevents accidental dual-activation within the same process but **cannot fence across separate JVM instances**.

```
┌─────────────────────────────────────┐
│            JVM                      │
│  ┌────────────┐  ┌──────────────┐  │
│  │  Sequencer  │──│ ConsensusModule│  │
│  └────────────┘  └──────────────┘  │
└─────────────────────────────────────┘
```

### Cross-JVM (TCP Consensus)

For true split-brain prevention, the `TcpConsensusServer` exposes the `ConsensusModule` over TCP, and each sequencer node uses a `TcpConsensusClient`:

```
┌────────────────────┐     ┌────────────────────┐     ┌────────────────────┐
│   Consensus Node   │     │  Sequencer Node A   │     │  Sequencer Node B   │
│  (independent      │     │                     │     │                     │
│   failure domain)  │     │  TcpConsensusClient ─┼────►│  TcpConsensusClient ─┤
│                    │◄────┤                     │     │                     │
│  ConsensusModule    │     │  Sequencer          │     │  Sequencer          │
│  TcpConsensusServer │     │  PromotionGuard     │     │  PromotionGuard     │
└────────────────────┘     └────────────────────┘     └────────────────────┘
```

The consensus node should be on an **independent failure domain** — separate host, separate power, separate network switch. This ensures that a network partition isolates the sequencer from the consensus module (making it fail-safe) rather than isolating two sequencers from each other (split-brain).

### Protocol

The TCP protocol uses fixed-size binary messages with no allocation on the hot path:

| Direction | Format | Size |
|-----------|--------|------|
| Request | 1 byte opcode + 32 bytes nodeId (null-padded ASCII) | 33 bytes |
| Response | 1 byte result + 4 bytes epoch + 32 bytes leaseHolder (null-padded ASCII) | 37 bytes |

| Opcode | Value | Request Size | Description |
|--------|-------|-------------|-------------|
| `ACQUIRE` | 1 | 33 bytes | Request lease acquisition |
| `RENEW` | 2 | 33 bytes | Renew existing lease |
| `RELEASE` | 3 | 33 bytes | Release lease voluntarily |
| `STATUS` | 4 | 33 bytes | Query current state |
| `REGISTER` | 5 | 37 bytes (33 + 4-byte priority) | Register as election candidate |
| `DEREGISTER` | 6 | 33 bytes | Deregister as election candidate |

| Result | Value | Description |
|--------|-------|-------------|
| `OK` | 0 | Operation succeeded |
| `DENIED` | 1 | Another node holds an active lease |
| `EXPIRED` | 2 | Lease expired before renewal |

### Fail-Safe Behavior

The `TcpConsensusClient` is **fail-safe**: if it cannot reach the consensus server, `tryAcquire` and `tryRenew` return `false`, and `isLeaseExpired` returns `true`. This means a sequencer **cannot go active** if the consensus module is unreachable. The design philosophy is:

> **It is better to have no primary than two primaries.**

---

## Alternative Names in Competing Architectures

The "consensus" concept appears across many distributed systems under different names:

| System / Literature | Name | Mechanism |
|---------------------|------|-----------|
| **Google Chubby** | Lock Service / Sequencer | Distributed lock with sequencer numbers (fencing tokens) |
| **Apache ZooKeeper** | Leader Election / Ephemeral Nodes | Ephemeral znodes for lease-like leadership; session timeouts for expiry |
| **etcd** | Lease / Election | TTL-based leases with `Grant`/`KeepAlive`/`Revoke`; `concurrency.Election` for leader election |
| **Consul** | Session / Leader Election | Session-based locks with TTL; health-check-based invalidation |
| **Raft (general)** | Term / Leader Lease | Monotonic term number is the fencing token; leader holds lease until term expires |
| **Paxos (general)** | Ballot Number / Proposer ID | Ballot numbers serve the same role as epochs |
| **LMAX Disruptor** | Cluster Witness / Heartbeat Service | Third-party witness observing heartbeats to arbitrate failover |
| **Aeron Cluster** | Consensus Module / Election | Raft-based consensus with log position and leadership term |
| **VMware vSAN** | Witness Node / Witness Appliance | Tie-breaker node in 2-node clusters to prevent split-brain |
| **SQL Server** | File Share Witness / Cloud Witness | Quorum witness for Always On Availability Groups |
| **Windows Failover Clustering** | Witness Disk / Cloud Witness | Quorum vote to break ties in even-node clusters |
| **Martin Kleppmann** | Fencing Token | Monotonically increasing token attached to every write; storage rejects stale tokens |

The F9 platform uses the name **"Consensus Module"** because:
1. It is a **third-party observer** (independent failure domain) that arbitrates who may be primary.
2. It does not participate in the event stream or state machine — it only **witnesses** lease claims.
3. The term is established in infrastructure (VMware, Windows Clustering) for exactly this role.

The closest analogs in the trading/exchange world are:
- **LMAX's cluster witness** — nearly identical: a separate process that observes heartbeats and grants permission to go active.
- **CME Globex** and **LSE Millennium** use dedicated arbitration services (often called "arbiter" or "controller") for the same purpose.

---

## Integration with PromotionGuard

The `PromotionGuard` uses the consensus module as one of several safety checks before allowing promotion:

```
PromotionGuard.checkPromotion():
  1. ✅ Bus server is not already active
  2. ✅ Events received while passive (backup is synchronized)
  3. ✅ No sequence gaps detected in the event stream
  4. ✅ No stale leader epochs detected
  5. ✅ Event silence threshold exceeded (primary has stopped)
  6. ✅ Consensus lease is available (no other node holds an active lease)  ◄── HERE
```

The consensus check is the **last line of defence**. Even if all other checks pass (the primary appears dead from the backup's perspective), the consensus module provides independent confirmation that no other node believes it is the primary.

---

## Automatic Leader Election

The `ConsensusModule` extends lease-based fencing with **automatic leader election**. Instead of requiring an operator to manually trigger `tryAcquire`, nodes register as candidates and the consensus module automatically grants the lease to the highest-priority candidate when no active leader exists.

### Candidate Registration

Nodes register as election candidates with a **priority** value. Lower priority value = higher priority (priority `1` beats priority `2`).

```java
consensus.registerCandidate("seq01a", 1);  // preferred primary
consensus.registerCandidate("seq01b", 2);  // backup
```

- Up to **16 candidates** can be registered (pre-allocated array, no allocation on the hot path).
- `deregisterCandidate(nodeId)` removes a candidate from the pool.
- Registering an already-registered candidate updates its priority.

### Election Triggers

| Trigger | Behavior |
|---------|----------|
| **Candidate registers, no leader exists** | Immediate election — the new candidate may be granted the lease |
| **Lease expires** (holder failed to renew) | Next `checkElection()`, `tryAcquire`, or `tryRenew` call triggers election of the next-best candidate |
| **Explicit release** (`tryRelease`) | Election is triggered; all candidates are eligible including the releaser |

### Expired vs Released

The election algorithm treats expired and released leases differently:

- **Expired**: The expired holder is **skipped** during election. They failed to renew, so they are presumed dead. The next-best candidate is elected.
- **Released**: All candidates are eligible, including the node that released. This supports graceful restarts and rolling upgrades.

### ElectionListener

An `ElectionListener` callback fires when a node is elected, providing the new epoch:

```java
consensus.setElectionListener((nodeId, epoch) -> {
    // nodeId was elected leader with this epoch
    log.info("New leader: {} epoch: {}", nodeId, epoch);
});
```

---

## Election Algorithm

```
electIfExpired():
1. If candidateCount == 0 → return (no candidates)
2. If leaseHolder != null AND not expired → return (active leader exists)
3. Find candidate with lowest priority value, SKIPPING the expired holder
4. If no other candidate found AND leaseHolder is null (explicit release) → include all candidates
5. Grant lease to winner: epoch++, set leaseHolder, fire ElectionListener
```

The algorithm is **O(1)** in practice — it scans a fixed-size array of at most 16 candidates with no allocation, no comparator objects, and no sorting.

---

## Election vs Consensus

| Property | F9 Leader Election | Raft | ZooKeeper |
|----------|-------------------|------|-----------|
| **Mechanism** | Single consensus module, priority-based | Quorum-based, log replication | Session-based ephemeral nodes |
| **Selection** | O(1) scan of ≤16 candidates | Election round with vote collection | Sequence-based (lowest ephemeral znode) |
| **Latency** | Single RTT to consensus module | Multiple RTTs for vote quorum | Session creation + watch notification |
| **Log replication** | None — consensus module only grants leases | Full log replication to followers | ZAB protocol replication |
| **Split-brain prevention** | Epoch-based fencing token | Term-based fencing | Session-based ephemeral nodes |
| **Complexity** | Minimal — ~200 lines | Significant — Raft protocol | Significant — ZAB + session management |

The F9 approach trades consensus durability for **minimal latency and zero allocation**. The consensus module is a single point of failure, mitigated by placing it on an independent failure domain and keeping it stateless enough for fast restart.

---

## What Leader Election Covers

| Concern | Covered by Election? | Notes |
|---------|---------------------|-------|
| **Sequencer leadership** | ✅ YES | The primary purpose — determines which node may assign sequence numbers |
| **Archive leadership** | ❌ NO | Archive follows the sequencer — whoever is the active sequencer runs the authoritative archive |
| **Snapshot coordinator** | ❌ NO | Stateless — works with whichever sequencer is active |

---

## Wiring with AutoFailover

Automatic leader election works with `AutoFailover` for fully hands-off failover. The consensus module elects a leader; `AutoFailover` handles the promotion mechanics on the elected node.

```bash
# On consensus node:
create /consensus ConsensusModule
/consensus/registerCandidate seq01a 1
/consensus/registerCandidate seq01b 2

# On each sequencer node:
create /consensusClient TcpConsensusClient
/consensusClient/connect consensusNode:9000
set /seq01a/consensusModule @/consensusClient
set /promotionGuard/consensusModule @/consensusClient

# AutoFailover handles the promotion when consensus module grants the lease
create /autoFailover AutoFailover @/promotionGuard @/seq01a
/autoFailover/enable
```

When `seq01a` crashes and its lease expires, the consensus module automatically elects `seq01b` (next-best priority). The `AutoFailover` on `seq01b`'s node detects the lease grant and triggers promotion through the `PromotionGuard`.

---

## Shell Commands

```bash
# On the consensus server node:
create /consensus com.core.platform.applications.sequencer.ConsensusModule
create /tcpConsensus com.core.platform.applications.sequencer.TcpConsensusServer @/consensus
/tcpConsensus/bind inet:0.0.0.0:9000

# On each sequencer node:
create /consensusClient com.core.platform.applications.sequencer.TcpConsensusClient
/consensusClient/connect 0.0.0.0:9000
set /seq01a/consensusModule @/consensusClient
set /promotionGuard/consensusModule @/consensusClient

# Leader election:
/consensus/registerCandidate seq01a 1    # Register candidate with priority
/consensus/deregisterCandidate seq01a    # Remove candidate
/consensus/candidates                    # List registered candidates and priorities
/consensus/checkElection                 # Manually trigger election check

# Status and diagnostics:
/consensus/status          # Lease holder, epoch, remaining TTL
/consensusClient/status    # Connection state, last epoch, request counts
/promotionGuard/syncStatus  # Full sync status including consensus state
```

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `leaseTimeoutMs` | `5000` | Time before an unrenewed lease expires |
| `timeoutMs` (client) | `2000` | TCP socket timeout for consensus requests |
| `MAX_CANDIDATES` | `16` | Maximum number of election candidates (compile-time constant, pre-allocated array) |

### Tuning Guidelines

- **`leaseTimeoutMs`** should be **longer than** the maximum expected network round-trip time between sequencer and consensus module, but **shorter than** the `eventSilenceThresholdMs` on `PromotionGuard` (default 3000ms). A typical production value is 3000–5000ms.
- If the consensus timeout is shorter than the promotion guard's silence threshold, a transiently slow primary will lose its lease before the backup considers promoting — the correct ordering.
- Setting `leaseTimeoutMs` too low causes false lease expirations during GC pauses or network blips. Setting it too high delays failover.
