# FX Credit Engine — 24x7x365 Design Extension Spec

This specification outlines the architecture and implementation details for extending the FX Credit Engine to operate continuously (**24x7x365**). It replaces the daily Start-of-Day (SOD) restart boundaries with a rolling, in-band snapshot and journaled reconciliation model.

---

## 1. Architectural Overview & Shift

In the daily SOD model, the system restarts every morning, loads a clean position snapshot, and replays that day's events. In a 24x7x365 model, the system runs continuously. Recovery from failure must happen in-band (while backups handle traffic), and the "SOD position" is treated as a periodic reconciliation command rather than a startup trigger.

```
  External Risk/Back-Office           Sequencer Command Channel
┌───────────────────────────┐        ┌─────────────────────────┐
│                           │        │                         │
│  Account Balance Refresh  │───────►│  AccountSnapshotCommand │
│  (Limit & Consumed at     │        │  (accountId, limit,     │
│   daily boundary)         │        │   consumed, asOf, seq)  │
└───────────────────────────┘        └────────────┬────────────┘
                                                  │
                                                  ▼
                                       [ Aeron Archive Journal ]
                                       (continuous, rolling segment)
                                                  │
                                                  ▼
                                       ┌───────────────────────┐
                                       │   Sequencer Handlers  │
                                       │  Determines floor,    │
                                       │  reconciles in-flight │
                                       └───────────────────────┘
```

### Key Differences
| Aspect | Daily SOD Model | 24x7x365 Model |
| :--- | :--- | :--- |
| **Startup Dependency** | Loads from DB at startup | Replays journal from disk snapshot |
| **Replay Source** | Today's database trades | Rolling Aeron Archive journal segment |
| **SOD Event** | Out-of-band startup trigger | In-band `AccountSnapshot` command |
| **Recovery** | Downtime restart | In-band snapshot recovery |
| **Backup State** | Overnight cold standby | Active-passive hot shadowing |

---

## 2. In-Flight Trade Reconciliation

### The Stale Database / Settlement Race Condition
If an external system pushes a daily balance refresh and the Credit Engine simply overwrites its local state, a race condition occurs:
1. **T=23:59:50**: Back-office database takes a snapshot of settled trades. Cumulative consumed exposure is **$7.0M**.
2. **T=23:59:58**: Credit Engine accepts **Order A ($500K)** in real time, increasing in-memory consumed exposure to **$7.5M**.
3. **T=00:00:02**: Credit Engine accepts **Order B ($300K)**, increasing in-memory consumed exposure to **$7.8M**.
4. **T=00:00:05**: The `AccountSnapshotCommand` is received by the Credit Engine containing the database snapshot from T=23:59:50 (`authorityConsumedUsd = 7.0M`, `asOf = 23:59:50`).
5. **If overwritten blindly (Hard Reset)**: The in-memory state resets to **$7.0M**, silently erasing the $800K exposure from Order A and Order B, resulting in a dangerous credit limit overshoot capacity.

### The Reconciled Floor Formula
To resolve this, the Credit Engine treats the back-office snapshot as a **floor** as of its database cut-off time (`asOf`), and adds back any in-flight trades accepted *after* that specific timestamp:

$$\text{Reconciled Consumed} = \text{Authority Consumed (from snapshot)} + \sum \text{Trades Accepted } > \text{asOf}$$

```
Back-Office             Command Stream            Credit Engine
     │                        │                         │
     │                        │              [T=23:59:58 — Accepted Order A $500K]
     │                        │              [T=00:00:02 — Accepted Order B $300K]
     │                        │              [recentTrades: {A:23:59:58, B:00:00:02}]
     │                        │                         │
[T=23:59:50 — Snapshot Cutoff]│                         │
[authorityConsumed = 7.0M]    │                         │
[asOf = 23:59:50]             │                         │
     │                        │                         │
───── AccountSnapshot ───────►│                         │
      limit = 10M             │────────────────────────►│
      consumed = 7.0M         │                         │ [Find trades after asOf=23:59:50]
      asOf = 23:59:50         │                         │   - Order A (23:59:58 > 23:59:50) -> YES
      seqNo = 42              │                         │   - Order B (00:00:02 > 23:59:50) -> YES
                              │                         │ [inFlightAfterAsOf = 500K + 300K = 800K]
                              │                         │ [Reconciled = 7.0M + 800K = 7.8M]
                              │                         │ [Prune trades <= 23:59:50]
                              │                         │ [State: limit=10M, consumed=7.8M]
```

---

## 3. Implementation Details

### A. Extended Domain State (`AccountState` & `AcceptedTrade`)
The engine tracks a short rolling window of recently accepted trades to support reconciliation:

```java
public class AccountState {
    private final String accountId;
    private long limitUsd;
    private long consumedUsd;
    private long lastSnapshotSeqNo;
    private Instant lastSnapshotAsOf;
    
    // Short rolling queue of recent accepted trades
    private final Deque<AcceptedTrade> recentTrades = new ArrayDeque<>();

    public void addTrade(String orderId, long notionalUsd, Instant acceptedAt) {
        recentTrades.addLast(new AcceptedTrade(orderId, notionalUsd, acceptedAt));
    }

    public void pruneStaleTrades(Instant asOfLimit) {
        recentTrades.removeIf(t -> !t.acceptedAt().isAfter(asOfLimit));
    }

    public long sumTradesAfter(Instant asOfLimit) {
        return recentTrades.stream()
            .filter(t -> t.acceptedAt().isAfter(asOfLimit))
            .mapToLong(AcceptedTrade::notionalUsd)
            .sum();
    }
}

public record AcceptedTrade(
    String orderId,
    long notionalUsd,
    Instant acceptedAt
) {}
```

### B. Reconciliation Command Handler
The handler deterministically executes the floor logic for both active and passive instances:

```java
public void onAccountSnapshot(AccountSnapshotCommandDecoder decoder) {
    var accountId = decoder.getAccountId();
    var seqNo = decoder.getSequenceNo();
    var asOf = Instant.ofEpochMilli(decoder.getAsOfEpochMs());
    
    AccountState current = state.getOrCreate(accountId);

    // Discard duplicate or stale out-of-order snapshots
    if (seqNo <= current.getLastSnapshotSeqNo()) {
        return; 
    }

    long authorityConsumed = decoder.getAuthorityConsumedUsd();
    long authorityLimit = decoder.getAuthorityLimitUsd();

    // Sum in-flight trades accepted after the snapshot's asOf timestamp
    long inFlightAfterAsOf = current.sumTradesAfter(asOf);
    long reconciledConsumed = authorityConsumed + inFlightAfterAsOf;

    // Apply reconciled parameters
    current.setLimitUsd(authorityLimit);
    current.setConsumedUsd(reconciledConsumed);
    current.setLastSnapshotSeqNo(seqNo);
    current.setLastSnapshotAsOf(asOf);
    
    // Clean up rolling trade cache
    current.pruneStaleTrades(asOf);
}
```

---

## 4. Recovery & Failover Protocol

### A. In-band Snapshot and Rolling Journal
Rather than querying a database, the **Aeron Archive** recording of the command channel is the absolute source of truth. The engine periodically writes memory state snapshots to disk, noting the associated journal position.

```
Aeron Journal Segment Ring:
┌─────────────────────┬─────────────────────┬─────────────────────┐
│ Segment 1 (Purged)  │ Segment 2 (RETAIN)  │ Segment 3 (ACTIVE)  │
│ [pos 0 - 128MB]     │ [pos 128MB - 256MB] │ [pos 256MB - 384MB] │
└─────────────────────┴──────────┬──────────┴──────────┬──────────┘
                                 │                     │
                        [Latest Snapshot]      [Crash Position]
                        Replay starts here ───► Replayed to here
```

### B. Recovery Sequence
On a cold start or crash recovery:
1. **Load Latest Snapshot**: Initialize `CreditState` from the most recent snapshot stored in the directory.
2. **Retrieve Journal Position**: Find the exact journal position `replayFrom` from the snapshot metadata.
3. **Replay Commands**: Command Aeron Archive to replay the stream starting at `replayFrom` to the current stop position.
4. **Active Promotion**: Once catch-up is complete, promote the recovery instance to Active.

### C. Active-Passive Promotion (Hot Standby)
Under Aeron Cluster or MoldBus, both active and passive instances run the same command logic.
* **Passive shadowing**: The passive instance parses all commands and runs the same reconciliation checks in memory.
* **Instant promotion**: When the heartbeat monitor detects a primary failure, the passive is promoted to active. Because it has kept up with the command journal in real-time, **zero catch-up is required** before taking over traffic.
