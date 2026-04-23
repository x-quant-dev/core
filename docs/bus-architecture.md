# Bus Architecture: Command, Event, and Admin Channels

## Two-Channel Architecture (No Admin Bus)

The platform uses exactly **two logical channels** — there is no separate admin bus.

| Channel | Direction | Purpose |
|---------|-----------|---------|
| **Command Channel** | Clients → Sequencer | Applications send commands (AddOrder, CancelOrder, etc.) |
| **Event Channel** | Sequencer → All | Sequencer publishes sequenced events (fills, rejects, definitions, heartbeats) |

All message types — orders, fills, reference data, heartbeats, application definitions — flow over the **same pair of channels**. There is no separation of pricing vs non-pricing data in the current design.

### MoldUDP64 Transport

Three multicast UDP addresses:

- **Event channel** (`239.100.100.100:10100`): sequenced MoldUDP64 packets with session header (10 bytes) + sequence number (8 bytes) + message count (2 bytes).
- **Command channel** (`239.100.100.101:10101`): unsequenced MoldUDP64 packets carrying commands.
- **Discovery channel** (`239.100.100.102:10102`): lightweight protocol for gap recovery — clients send `D` byte, rewinders respond with their unicast address.

### Aeron Transport

Two Aeron streams (no discovery channel — Aeron's Media Driver handles connection management):

- **Event stream** (`aeron:udp?endpoint=224.0.1.1:40456`, streamId=1001): `ExclusivePublication` from server, `Subscription` on clients.
- **Command stream** (`aeron:udp?endpoint=224.0.1.2:40457`, streamId=1002): `Publication` from each client, `Subscription` on server.

### No Admin Bus Exists

There is no dedicated admin channel. All administrative operations (start/stop, configuration, status inspection) are performed through the **command shell** accessed via Telnet, HTTP, WebSocket, or CLI. The shell exposes `@Command`-annotated methods on runtime objects, providing full operational control without a separate message bus.

The command shell replaces JMX entirely — `@Property` maps to MBean attributes, `@Command` to MBean operations, and the shell's directory tree provides MBean-like discovery. Unlike JMX, the shell runs on the event loop thread (no concurrency issues) and is allocation-free.

---

## Heartbeating

### Application-Level Heartbeats (Sequencer)

The `Sequencer` publishes a `Heartbeat` message at a fixed interval (default 100ms) via the scheduler:

```
Sequencer.activate()
  → scheduler.scheduleEvery(100ms, this::sendHeartbeat)
  → heartbeat encoder writes to bus → commit → send
```

The heartbeat serves multiple purposes:
- **Liveness detection**: clients know the sequencer is alive.
- **Sequence advancement**: heartbeats carry event sequence numbers, allowing clients to detect gaps even during idle periods.
- **Application definition trigger**: the first heartbeat also emits an `ApplicationDefinition` message that binds the sequencer's application ID.

### Transport-Level Heartbeats

| Transport | Mechanism |
|-----------|-----------|
| **MoldUDP64** | No dedicated transport heartbeat. Command publishers use a **100ms retransmission timeout** — if a sent command is not echoed on the event stream within 100ms, the packet is resent. Gap detection uses **1s discovery timeout** and **1s rewind timeout**. |
| **Aeron** | Aeron's Media Driver provides built-in keepalive and liveness detection between publishers and subscribers. No application-level transport heartbeat is needed. The `unavailableImageHandler` fires when a publisher disappears. |
| **TCP** | `TcpMessagePublisher` and `TcpMessageReceiver` use heartbeat frames containing session name + next sequence number for connection health monitoring. |

---

## Monitoring

### Status Introspection via Command Shell

Most runtime objects implement `Encodable` or expose `@Command(path = "state")` / `@Command(path = "status")` methods. Operators query these through any shell interface:

```
/bus/status              → bus client state, session, sequence numbers
/busServer/status        → server state, event publisher stats
/busServer/eventReceiver/status → gap state, rewind stats
/vm/activation/state     → full activator graph
```

### Metrics

`MetricFactory` registers gauges, switches, and state metrics. Two publishers:

- `UdpMetricPublisher`: encodes metrics to binary and multicasts at configurable intervals (default 10s).
- `LogMetricPublisher`: writes metrics as text log entries.

Key metrics registered automatically:
- `Activator_Active`, `Activator_Ready`, `Activator_Started` per activator node.
- Event sequence number gauges on `MoldEventReceiver`.
- Custom metrics registered by applications during initialization.

### Logging

`LogFactory` with `ChannelLogSink` provides allocation-free logging. Verbosity is controllable at runtime:
- `/vm/log/debugForAll true` — enable all debug logging.
- `/vm/log/debug "Mold" true` — enable debug for specific components.

---

## Service Discovery

### MoldUDP64: Discovery Channel

Service discovery is limited to **gap recovery rewinder discovery** using a dedicated multicast channel:

1. Client detects a gap → sends `D` byte on discovery channel (`239.100.100.102:10102`).
2. `MoldRewinder` instances respond with their unicast rewind socket address.
3. Client sends a rewind request (session + seqNum + count) to rewinder's unicast address.
4. Rewinder reads from `MessageStore` (file-backed or in-memory) and replies with MoldUDP64 packets.

There is no general-purpose service locator or registry. The tiered topology (`clob-tier0.cmd`, `clob-tier1.cmd`, `clob-tier2.cmd`) uses `MoldRepeater` which re-publishes events and serves rewinds for downstream tiers — each tier has its own discovery channel.

### Aeron: No Application-Level Discovery

Aeron handles connection establishment through the Media Driver. Publishers and subscribers find each other by matching on channel + streamId. No application-level discovery protocol is needed.

---

## Client Entry Point — No Single SequencerClient Class

There is **no single `SequencerClient` class**. Instead, the system is composed via command files using the shell:

### What a Client Node Creates

```
# From clob.cmd — a node joins by creating bus components via shell commands:
create /bus com.core.platform.bus.mold.MoldBusClient $event_channel $command_channel $discovery_channel
create /bus/provider01 /bus/createProvider OrderBooks
```

The minimum dependency chain to join the sequencer:

```
BusClient (MoldBusClient or AeronBusClient)
  ├── Subscription to event channel → receives sequenced events
  ├── Provider (per application) → sends commands via command channel
  ├── Dispatcher → routes inbound events to typed listeners
  └── Schema → message encoding/decoding
```

### Main Dependency: `BusClient<D,P>`

The `BusClient` interface is the **single main dependency** for any application joining the sequencer:

```java
public interface BusClient<DispatcherT, ProviderT> {
    Schema<DispatcherT, ProviderT> getSchema();
    DispatcherT getDispatcher();
    ProviderT getProvider(String applicationName, Object associatedObject);
    String getSession();
    void addOpenSessionListener(Runnable listener);
    void addCloseSessionListener(Runnable listener);
}
```

Applications depend on `BusClient` to:
1. Get a `Dispatcher` to subscribe to events (`dispatcher.addListener("FillOrder", handler)`).
2. Get a `Provider` to send commands (`provider.getEncoder("AddOrder")` → encode → `provider.send()`).
3. Get `Schema` for message metadata (header offsets, message types).

The `Sequencer` depends on `BusServer` — which extends the same pattern with `acquire()` / `commit()` / `send()` for publishing events and `setCommandListener()` for receiving commands.

---

## Gap Detection and Replay

### MoldUDP64 Gap Detection

`MoldEventReceiver` maintains `nextSeqNum` — the next expected event sequence number. On each received packet:

```
if msgSeqNum == nextSeqNum → deliver, advance nextSeqNum
if msgSeqNum < nextSeqNum  → drop (already processed)
if msgSeqNum > nextSeqNum  → GAP detected
```

**Gap recovery flow:**
1. Gap detected → send `D` on discovery channel.
2. Wait up to 1s for rewinder addresses.
3. Send rewind request (session + seqNum + count) to rewinder via unicast UDP.
4. Rewinder reads from `MessageStore` (file-backed or in-memory) and replies with MoldUDP64 packets.
5. Client processes rewind packets through the same sequence validation.
6. If rewind times out (1s), try next rewinder in list.
7. Activation: event receiver only becomes `ready` after catching up (first rewind completes or no gap exists).

### Aeron Gap Detection

Aeron handles gap detection at the **driver level**, not the application level:

1. `LossDetector` scans term buffer between `rebuildPosition` and `highWaterMark`.
2. Missing frames (frameLength == 0) trigger a NAK after a feedback delay.
3. Sender retransmits from its term buffer via `RetransmitHandler`.
4. If data has been overwritten (subscriber too far behind), the `Image` becomes unavailable → application must use **Archive replay** via `ReplayMerge`.

### Command Acknowledgment (Both Transports)

Command publishers (`MoldCommandPublisher`, `AeronCommandPublisher`) track:
- `outSeqNum`: next outbound application sequence number.
- `inSeqNum`: last confirmed application sequence number (echoed on event stream).

When the publisher sees its own messages on the event stream via `onBeforeMessage`, it advances `inSeqNum`. In MoldUDP64, unconfirmed commands are retransmitted every 100ms. In Aeron, there is no application-level retransmission — Aeron's reliable transport handles delivery.

---

## Bus Splitting for Pricing vs Non-Pricing Data

### Current Design: Single Unified Bus

The current architecture runs **all message types on a single event stream**: heartbeats, application definitions, reference data, orders, fills, rejects. The schema (`clob-schema.xml`) defines 8 message types all on one bus:

```
Heartbeat, ApplicationDefinition, EquityDefinition,
AddOrder, CancelOrder, FillOrder, RejectOrder, RejectCancel
```

This provides **total ordering** — every event has a single global sequence number. All nodes see the exact same stream in the exact same order, which gives deterministic state and replay.

### The High-Volume Pricing Problem

When pricing data (quotes, ticks) is added, it will dominate the event stream volume. This creates a tension:

| Concern | Single Bus | Split Buses |
|---------|-----------|-------------|
| **Deterministic ordering** | ✅ Total order across all events | ⚠️ Cross-stream ordering requires logical clocks or merge |
| **Replay consistency** | ✅ Replay one stream = full state | ⚠️ Must replay both streams and merge |
| **Throughput** | ⚠️ Pricing volume delays order events | ✅ Order events unaffected by pricing volume |
| **Snapshot** | ✅ Single sequence number = checkpoint | ⚠️ Two sequence numbers needed |

### Recommended Design: Dual Command Channels With Sequencer Merge

To handle high-volume pricing while preserving deterministic state:

```
Pricing Apps ──► Pricing Command Channel ──► Sequencer ──┐
                                                          ├──► Unified Event Stream
Order Apps ────► Order Command Channel ────► Sequencer ──┘
```

The **sequencer remains the single authority**. It receives commands from both channels and publishes all events on a **single event stream** with one global sequence number. This preserves:
- Total ordering: one stream, one sequence.
- Deterministic replay: replay the event stream = exact state recovery.
- Throughput isolation: command channels can be separate to avoid head-of-line blocking on ingress.

If pricing volume is too high for a single event stream, a **two-stream** approach is possible but requires:
- Each stream has its own sequencer or the same sequencer writes to both.
- Clients maintain two `nextSeqNum` counters.
- Snapshot must record both sequence positions.
- Replay must merge both streams using timestamps or logical vector clocks.
