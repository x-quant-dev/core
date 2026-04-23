# Deterministic Snapshot Rules

This document defines the rules for implementing the `Snapshottable` interface to produce **deterministic snapshots** — snapshots that, when restored and followed by event replay, produce **exactly the same state** as processing the full event stream from the beginning.

Determinism is not optional. The entire recovery model depends on it: `snapshot(S_n) + replay(events[n+1..m]) == state(S_m)`. If a snapshot captures state in a non-deterministic way, the restored node will diverge from other nodes, producing incorrect results silently.

For the snapshot architecture, protocol, and recovery lifecycle, see [snapshot-service.md](snapshot-service.md).

---

## The Golden Rule

> **A snapshot must capture the complete logical state, and that state must be recorded in a canonical (deterministic) order. Two nodes that have processed the same events up to the same sequence number must produce byte-identical snapshots.**

---

## Rule 1: Deterministic Iteration Order

### Problem

Java `HashMap`, `HashSet`, and other hash-based collections do not guarantee iteration order. The order depends on hash codes, table capacity, and insertion order — all of which can vary between JVM instances, runs, or even identical code paths due to hash seed randomisation (JDK 9+).

If you iterate over a `HashMap` to serialise keys, the snapshot will contain keys in an unpredictable order. Two nodes with identical logical state will produce different byte sequences.

### Rule

**All collections MUST be iterated in a deterministic order when serialising to a snapshot.**

| Collection Type | Approach |
|----------------|----------|
| `HashMap` / `HashSet` | ❌ **Do not use** on hot path (GC-producing). If used off-path, sort keys before iterating. |
| Eclipse Collections `IntObjectHashMap`, `LongObjectHashMap` | Sort keys into a pre-allocated primitive array before iterating. |
| Eclipse Collections `Int2IntHashMap` (Agrona) | Use `forEachKeyValue` after collecting keys into a sorted array. |
| Primitive arrays | Already deterministic if populated deterministically. |
| `ObjectIntHashMap`, `ObjectLongHashMap` | Sort object keys by a deterministic comparator (e.g., natural order for strings, or by a numeric identifier). |

### Example: Correct

```java
@Override
public void onSnapshotRequest(long snapshotId, long checkpointSeqNum, Provider provider) {
    // Collect keys and sort them for deterministic order
    var keys = orderBook.keySet().toSortedArray();  // Eclipse Collections
    
    for (var i = 0; i < keys.length; i++) {
        var orderId = keys[i];
        var order = orderBook.get(orderId);
        
        encoder.wrap(buffer, 0);
        encoder.orderId(orderId);
        encoder.price(order.price());
        encoder.quantity(order.quantity());
        encoder.side(order.side());
        // ... serialise remaining fields
        var length = encoder.commit();
        
        provider.sendSnapshotChunk(snapshotId, nodeId(), chunkIndex, totalChunks, buffer, 0, length);
    }
}
```

### Example: WRONG

```java
// ❌ WRONG: HashMap iteration order is non-deterministic
for (var entry : orderMap.entrySet()) {
    encoder.wrap(buffer, 0);
    encoder.orderId(entry.getKey());
    // ...
}

// ❌ WRONG: Eclipse Collections hash map iteration is non-deterministic
orderBook.forEachKeyValue((orderId, order) -> {
    encoder.wrap(buffer, 0);
    encoder.orderId(orderId);
    // ...
});
```

---

## Rule 2: No Random Numbers or UUIDs

### Problem

Random numbers and UUIDs are by definition non-deterministic. If any state field was derived from `Random`, `ThreadLocalRandom`, `SecureRandom`, `UUID.randomUUID()`, or `Math.random()`, the snapshot captures non-reproducible state.

### Rule

**No state that participates in snapshots may be derived from random sources.**

- **Identifiers** must come from deterministic sources: sequence numbers, counters, or values assigned by the sequencer.
- **Hash seeds** must be fixed (not randomised). If using a hash function for internal state, use a constant seed.
- If randomness is needed for external interactions (e.g., session tokens for client connections), it must be derived from the event log (e.g., a seed in the event) or excluded from snapshot state.

---

## Rule 3: No Wall-Clock Time in State

### Problem

`System.currentTimeMillis()`, `System.nanoTime()`, `Instant.now()`, `LocalDateTime.now()` — all of these produce different values on different nodes at the same logical point in the event stream.

### Rule

**All timestamps in business state must come from the event log (event timestamps), never from the wall clock.**

The `Time` interface in the platform provides event-time semantics — it returns the timestamp from the currently-being-processed event. Snapshot state must only contain these event-derived timestamps.

```java
// ✅ CORRECT: time from event
var eventTime = time.nanos();  // returns event timestamp during replay

// ❌ WRONG: wall-clock time
var now = System.currentTimeMillis();
```

---

## Rule 4: No Floating-Point Accumulation

### Problem

IEEE 754 floating-point arithmetic is not associative: `(a + b) + c ≠ a + (b + c)` in many cases due to rounding. If state involves accumulated floating-point values (e.g., running totals, VWAP calculations), the order of operations affects the result. Even with deterministic event ordering, different code paths during replay vs. live processing can produce different accumulated values.

### Rule

**Use fixed-point arithmetic (scaled longs) for all monetary and quantity values.** The platform already enforces this: `BigDecimal` and `float`/`double` are prohibited on the hot path.

```java
// ✅ CORRECT: fixed-point (price in 1/10000ths)
private long priceScaled;  // e.g., 123.4567 stored as 1234567L

// ❌ WRONG: floating-point accumulation
private double totalNotional;  // accumulated sum will drift
```

If you must use floating-point (e.g., for analytics computed off the hot path), these values must be **excluded from the snapshot** or recomputed deterministically from the fixed-point source data during restore.

---

## Rule 5: Complete State Capture

### Problem

If a snapshot omits any state field that affects business logic, the restored node will behave differently from a node that processed all events from the beginning.

### Rule

**Every field that affects event processing or command handling must be included in the snapshot.**

A useful test: after restoring from a snapshot and replaying delta events, the node must produce **exactly the same output events** as a node that processed the full event stream. If any field is missing, the outputs will diverge.

### Checklist

- [ ] All maps, sets, and collections
- [ ] All counters and sequence numbers
- [ ] All flags and state machine states (e.g., `isHalted`, `isAuctionPhase`)
- [ ] All cached/derived values that are not recomputed on every event
- [ ] All timer state (next scheduled time, interval) — expressed as event-time offsets, not wall-clock times
- [ ] All per-instrument / per-account state

### What to EXCLUDE

- Transient connection state (socket handles, channel references)
- Metrics and monitoring counters (they can be reset on recovery)
- Logging state
- References to framework objects (Selector, Scheduler, LogFactory)

---

## Rule 6: Versioned Binary Format

### Problem

Schema evolution. The snapshot format will change as fields are added, removed, or reordered. A snapshot written by version N of the code must be readable by version N+1 (and ideally vice versa).

### Rule

**Every snapshot payload must start with a version header.**

```
┌──────────┬──────────────────────────────────────┐
│ version  │           state data                 │
│ (4 bytes)│  (SBE-encoded messages)              │
└──────────┴──────────────────────────────────────┘
```

- The version is a simple integer, incremented when the format changes.
- The restore code (`onSnapshotRestore`) must check the version and handle migrations.
- Unknown versions must be rejected (fail recovery, fall back to full replay).

---

## Rule 7: Idempotent Restore

### Problem

During recovery, `onSnapshotRestore` may be called, followed by delta event replay. The restore must leave the node in a state where subsequent event processing works correctly — no duplicate entries, no stale references, no half-initialised structures.

### Rule

**`onSnapshotRestore` must produce a clean, complete state. All pre-existing state must be cleared before restoring.**

```java
@Override
public void onSnapshotRestore(long snapshotId, int chunkIndex, int totalChunks, DirectBuffer payload) {
    if (chunkIndex == 0) {
        // Clear ALL state before restoring
        orderBook.clear();
        instrumentState.clear();
        nextOrderId = 0;
    }
    
    // Deserialise this chunk's data into state
    var offset = 0;
    var version = payload.getInt(offset);
    offset += Integer.BYTES;
    
    // ... restore state from payload
}
```

---

## Rule 8: No External Dependencies in State

### Problem

State derived from external sources (database queries, HTTP calls, configuration files read at startup) may differ between the original run and the recovery run.

### Rule

**All state must be derivable from the event log alone.**

- Configuration that affects business logic must be published as events (e.g., `InstrumentDefinition`, `TradingSessionConfig`).
- External data that was fetched during the original run must have been injected into the event stream as a command/event before it affected state.
- The snapshot captures the result of processing these configuration events, so restore + replay reproduces the same state.

---

## Rule 9: Chunk Ordering and Reassembly

### Problem

A node's state may be too large for a single `SnapshotChunk` message. It must be split into multiple chunks, and the chunks must be reassembled in the correct order during restore.

### Rule

**Chunks must be self-describing: each chunk carries its `chunkIndex` and `totalChunks`.** The restore code must handle chunks in order (0, 1, 2, ..., totalChunks-1).

```java
// Serialisation: split state into chunks
var totalBytes = serialise(stateBuffer);
var totalChunks = (totalBytes + maxPayload - 1) / maxPayload;

for (var i = 0; i < totalChunks; i++) {
    var offset = i * maxPayload;
    var length = Math.min(maxPayload, totalBytes - offset);
    provider.sendSnapshotChunk(snapshotId, nodeId(), i, totalChunks, stateBuffer, offset, length);
}
```

---

## Rule 10: Snapshot Verification

### Problem

A snapshot may be corrupted (bit flip, truncated write, software bug). Restoring from a corrupted snapshot produces silently wrong state.

### Rule

**Snapshots should include checksums for verification.**

The `PromotionGuard` already maintains a **rolling XOR-rotate checksum** of all events processed. After restoring from a snapshot and replaying deltas, the rolling checksum at position M should match the checksum computed by a node that processed all events 1..M. If they differ, the snapshot or replay produced divergent state.

### Verification Process

```
Node A (full replay):       checksum after event 1000000 = 0xABCD1234
Node B (snapshot + replay): checksum after event 1000000 = 0xABCD1234  ✅ Match

Node C (corrupt snapshot):  checksum after event 1000000 = 0xDEADBEEF  ❌ Mismatch → alert
```

---

## Summary Checklist for Implementors

Before implementing `Snapshottable` for a new node, verify:

| # | Rule | Check |
|---|------|-------|
| 1 | **Deterministic iteration** | All maps/sets sorted by key before serialising |
| 2 | **No randomness** | No `Random`, `UUID.randomUUID()`, `Math.random()` in state |
| 3 | **No wall-clock time** | All timestamps from event log, not `System.currentTimeMillis()` |
| 4 | **No floating-point accumulation** | All monetary/quantity values are scaled longs |
| 5 | **Complete capture** | Every field affecting business logic is serialised |
| 6 | **Versioned format** | 4-byte version header; migration support in restore |
| 7 | **Idempotent restore** | State cleared before restoring; no leftover data |
| 8 | **No external dependencies** | All state derivable from the event log alone |
| 9 | **Chunk ordering** | Chunks are self-describing with index and total count |
| 10 | **Checksum verification** | Rolling checksum compared across nodes after recovery |

---

## Anti-Patterns

| Anti-Pattern | Why It Breaks Determinism | Fix |
|-------------|--------------------------|-----|
| Iterating `HashMap.keySet()` | Hash iteration order varies across JVM runs | Sort keys first, or use sorted/array-backed structures |
| Using `Object.hashCode()` as a key | Default `hashCode()` is identity-based (memory address) | Use value-based keys (primitive IDs, strings) |
| Caching `System.nanoTime()` deltas | Wall-clock deltas differ across nodes | Use event timestamps only |
| Storing thread names or IDs | Thread identity varies across runs | Exclude from state |
| Using `enum.ordinal()` without version guard | Adding an enum value shifts ordinals | Use explicit numeric codes or name-based serialisation |
| Lazy initialisation | "First access" timing may differ between live and replay | Eagerly initialise all state, or initialise from events |
| Mutable default values | `new ArrayList<>()` vs `null` on restore affects behaviour | Always explicitly set defaults in restore |
