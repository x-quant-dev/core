# FX Credit Engine — Design & Implementation Spec

> **Module**: `credit`  
> **Architecture**: Design 3 — Atomic Broadcast / Total Order via Sequencer Platform  
> **Status**: Implemented

---

## 1. Overview

The FX Credit Engine enforces **pre-trade credit limits** for FX orders in real time.
It prevents any single account from consuming more USD notional than its assigned master
limit in a single trading session.

This implementation follows **Design 3** from the FX-Credit-Engine-Design specification —
leveraging the project's existing **Sequencer Platform** (MoldUDP64 / MoldBusServer) as
the total-order atomic broadcast layer. All credit decisions are made deterministically
inside a single sequencer process; replicas converge to identical state by replaying the
same ordered event log.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                         Sequencer Node                        │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐     │
│  │               CreditCommandHandlers                  │     │
│  │  onCreditCheckRequest()  onSetCreditLimit()          │     │
│  │  onHardStop()            onLoadSodConsumed()         │     │
│  │                       ▼                              │     │
│  │                  CreditState                         │     │
│  │     applyOrder()  applyHardStop()  loadAccount()     │     │
│  └─────────────────────────────────────────────────────┘     │
│                       ▼  publishes events                     │
│          ┌────────────────────────────────┐                  │
│          │         BusServer              │                  │
│          │  (MoldBusServer / TestBusServer)│                  │
│          └───────────────┬────────────────┘                  │
└──────────────────────────┼────────────────────────────────────┘
                           │ total-ordered event stream
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
   CreditStoreClient  CreditStoreClient  Other Clients
   (OMS)              (Risk Monitor)     (Reporting)
```

### Key components

| Component | Role |
|---|---|
| `CreditState` | Pure in-memory deterministic state machine. Tracks consumed and limit per account. No I/O. |
| `CreditCommandHandlers` | Sequencer-side handler. Subscribes to commands, validates them, mutates `CreditState`, publishes result events. |
| `CreditStoreClient` | Client-side passive replica. Materialises state from the ordered event stream. Read-only. |
| `CreditInjector` | Operator/test utility. Sends typed commands to the sequencer via the bus. |
| Schema (generated) | SBE-style schema auto-generated from `credit-schema.xml`. |

---

## 3. Message Protocol

### Commands (client → sequencer)

| Message | Description |
|---|---|
| `CreditCheckRequest` | Pre-trade credit check for a single order. |
| `SetCreditLimit` | Update master USD limit for an account. |
| `LoadSodConsumed` | Seed SOD consumed state (from trade log). |
| `HardStop` | Freeze account immediately (circuit breaker). |
| `HardStopReleased` | Lift a hard stop. |

### Events (sequencer → all)

| Message | Description |
|---|---|
| `CreditAccepted` | Order passed credit check; consumed updated. |
| `CreditRejected` | Order failed; state unchanged. Contains `DecisionCode`. |
| `SetCreditLimitAck` | Limit update confirmed. |
| `LoadSodConsumedAck` | SOD state seeded. |
| `HardStop` | Hard stop applied (re-published as event). |
| `HardStopReleased` | Hard stop lifted. |

### DecisionCode (byte, ordinal-encoded)

| Code | Meaning |
|---|---|
| `ACCEPT` | Order approved, credit reserved. |
| `REJECT_LIMIT_EXCEEDED` | Consumed + notional > limit. |
| `REJECT_HARD_STOP` | Account is frozen. |
| `REJECT_UNKNOWN_ACCOUNT` | Account not loaded (no limit). |

---

## 4. Determinism Constraints

> [!IMPORTANT]
> `CreditState` and `CreditCommandHandlers` MUST be fully deterministic.
> Violating these constraints causes primary and backup nodes to diverge.

- **No I/O**: no disk, no network, no logging inside hot-path methods.
- **No wall-clock reads**: use sequencer-provided timestamps only.
- **No randomness**: `HashMap`, `ArrayList` OK; do not use `HashSet` where iteration order matters.
- **Copy inbound buffers**: `DirectBuffer` references from decoders are slice views; always `BufferUtils.copy()` before storing as map keys.

---

## 5. SOD Workflow

```
┌──────────┐         ┌──────────────┐       ┌────────────────────┐
│ SOD Script│─────── ▶│ CreditInjector│──────▶│ CreditCommandHandlers│
│(trade log)│  loadSod │               │  cmd  │  loadAccount()       │
│          │         └──────────────┘       │  → publishes Ack     │
└──────────┘                                └────────────────────┘
```

1. At SOD, a script reads the previous day's trade log.
2. For each account it calls `CreditInjector.loadSodConsumed(accountId, carriedConsumed, newLimit)`.
3. The sequencer applies the command, updates `CreditState`, and publishes `LoadSodConsumedAck`.
4. All `CreditStoreClient` replicas reflect the new state.

---

## 6. Hard Stop (Circuit Breaker)

- Triggered manually by ops (`CreditInjector.hardStop(accountId, reason)`) **or** by an automated monitor that detects a limit breach trend.
- The sequencer publishes a `HardStop` event immediately; all replicas mark the account.
- All subsequent `CreditCheckRequest` for the frozen account → `CreditRejected(REJECT_HARD_STOP)`.
- Lifted by `CreditInjector.releaseHardStop(accountId)`.

---

## 7. Project Structure

```
credit/
├── build.gradle.kts
├── docs/
│   └── design.md                           ← this file
└── src/
    ├── main/
    │   ├── java/com/core/credit/
    │   │   ├── domain/
    │   │   │   ├── CreditState.java         ← pure state machine
    │   │   │   └── DecisionCode.java        ← decision enum
    │   │   └── applications/
    │   │       ├── CreditStoreClient.java   ← passive replica (clients)
    │   │       ├── sequencer/
    │   │       │   └── CreditCommandHandlers.java  ← sequencer logic
    │   │       └── utilities/
    │   │           └── CreditInjector.java  ← ops/test command sender
    │   └── resources/
    │       └── credit-schema.xml            ← message schema
    └── test/
        └── java/com/core/credit/
            ├── domain/
            │   └── CreditStateTest.java     ← domain unit tests (20 cases)
            └── applications/
                ├── CreditStoreClientTest.java   ← client replica tests
                └── sequencer/
                    └── CreditCommandHandlersTest.java  ← integration tests
```

---

## 8. Compared to Design 1 & 2

| Aspect | Design 1 (Single-box) | Design 2 (Sharded) | Design 3 (Sequencer) |
|---|---|---|---|
| Consistency | Trivial | Complex (cross-shard) | Strong (total order) |
| Throughput | Moderate | High | High (sequencer bound) |
| HA | Manual failover | Partition-tolerant | Sequencer failover |
| Overshoot risk | None | Yes (cross-shard race) | None |
| FX fit | Simple desks | Large books | **Recommended** |

Design 3 eliminates the cross-client overshoot problem that afflicts Design 2 by ensuring
every order passes through a single sequencer before being committed to the event log.

---

## 9. Extension Points

- **Pair-level sub-limits**: add a `CurrencyPair`-keyed sub-map in `CreditState`.
- **Tenor-bucketed limits**: add a `ProductType` dimension.
- **Metrics**: publish `CreditAccepted`/`CreditRejected` counts to a metrics bus.
- **Persistence**: replay the event log from time 0 to recover full state on cold start.
