# Tutorial: Distributed HA Key/Value Store

Build a distributed key/value store with automatic HA failover — in under 100 lines of code.

This tutorial walks through the core features of the platform by building a trivial but fully functional application.
By the end you will have touched every layer of the system:

| Concept | What you'll do |
|---|---|
| Schema definition | Define `PutEntry`, `DeleteEntry`, and `RejectEntry` messages in XML |
| Command handling | Validate commands and produce events on the sequencer |
| Event-driven state | Rebuild state from the ordered event log on the client |
| Shell integration | Expose `get`, `status`, and `send` commands via telnet |
| Deterministic replay | Recover identical state from the event log after restart |
| HA failover | Add three lines to get primary/backup with automatic promotion |

---

## 1. Define the Schema

Messages are defined in XML and code-generated into typed encoders and decoders.
Each message carries an 18-byte header with `applicationId`, `applicationSequenceNumber`, `timestamp`, and other fields.

Add the following messages to `clob-schema.xml` (or create a separate `kv-schema.xml`).
Use message IDs starting at 20 to avoid conflicts with the existing CLOB messages.

```xml
<!-- Key/Value store messages -->
<message id="20" name="PutEntry">
    <optional name="Key" type="DirectBuffer"/>
    <optional name="Value" type="DirectBuffer"/>
</message>

<message id="21" name="DeleteEntry">
    <optional name="Key" type="DirectBuffer"/>
</message>

<message id="22" name="RejectEntry">
    <optional name="Key" type="DirectBuffer"/>
    <optional name="Reason" type="DirectBuffer"/>
</message>
```

After running the build (`./gradlew build`), the code generator produces:

- `PutEntryEncoder` / `PutEntryDecoder`
- `DeleteEntryEncoder` / `DeleteEntryDecoder`
- `RejectEntryEncoder` / `RejectEntryDecoder`

These are flyweight accessors over `DirectBuffer` — zero allocation, zero copying on the hot path.

The generated `ClobDispatcher` also gets new listener-registration methods:

```java
dispatcher.addPutEntryListener(this::onPutEntry);
dispatcher.addDeleteEntryListener(this::onDeleteEntry);
dispatcher.addRejectEntryListener(this::onRejectEntry);
```

---

## 2. Implement the Command Handler (Sequencer-Side)

The command handler runs inside the sequencer.
It receives inbound commands, validates them, and produces events via `BusServer.commit()`.

This follows the same pattern as `ClobCommandHandlers`.

```java
package com.core.clob.applications.sequencer;

import com.core.clob.schema.ClobDispatcher;
import com.core.clob.schema.ClobProvider;
import com.core.clob.schema.DeleteEntryDecoder;
import com.core.clob.schema.DeleteEntryEncoder;
import com.core.clob.schema.PutEntryDecoder;
import com.core.clob.schema.PutEntryEncoder;
import com.core.clob.schema.RejectEntryEncoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.platform.bus.BusServer;
import org.agrona.DirectBuffer;
import org.eclipse.collections.impl.set.mutable.UnifiedSet;

import java.util.Objects;

/**
 * Command handlers for the key/value store messages.
 *
 * <p>Validates {@code PutEntry} and {@code DeleteEntry} commands.
 * Valid commands are copied as events.
 * Invalid commands produce a {@code RejectEntry} event.
 */
public class KvCommandHandlers implements Encodable {

    private final BusServer<ClobDispatcher, ClobProvider> busServer;
    private final PutEntryEncoder putEntryEncoder;
    private final DeleteEntryEncoder deleteEntryEncoder;
    private final RejectEntryEncoder rejectEntryEncoder;
    private final UnifiedSet<DirectBuffer> keys;

    /**
     * Creates a {@code KvCommandHandlers} and subscribes to
     * {@code PutEntry} and {@code DeleteEntry} commands.
     *
     * @param busServer the sequencer bus
     */
    public KvCommandHandlers(
            BusServer<ClobDispatcher, ClobProvider> busServer) {
        this.busServer = Objects.requireNonNull(busServer, "busServer is null");

        putEntryEncoder = new PutEntryEncoder();
        deleteEntryEncoder = new DeleteEntryEncoder();
        rejectEntryEncoder = new RejectEntryEncoder();
        keys = new UnifiedSet<>();

        var dispatcher = busServer.getDispatcher();
        dispatcher.addPutEntryListener(this::onPutEntry);
        dispatcher.addDeleteEntryListener(this::onDeleteEntry);
    }

    private void onPutEntry(PutEntryDecoder decoder) {
        var key = decoder.getKey();
        if (key.capacity() == 0) {
            sendReject(decoder.getApplicationId(),
                    decoder.getApplicationSequenceNumber(), key, "empty key");
            return;
        }

        // valid: copy command as event
        BusServer.copy(busServer, decoder);

        // track the key for delete validation
        if (!keys.contains(key)) {
            keys.add(BufferUtils.copy(key));
        }
    }

    private void onDeleteEntry(DeleteEntryDecoder decoder) {
        var key = decoder.getKey();
        if (key.capacity() == 0) {
            sendReject(decoder.getApplicationId(),
                    decoder.getApplicationSequenceNumber(), key, "empty key");
            return;
        }

        if (!keys.contains(key)) {
            sendReject(decoder.getApplicationId(),
                    decoder.getApplicationSequenceNumber(), key, "key not found");
            return;
        }

        // valid: copy command as event
        BusServer.copy(busServer, decoder);
        keys.remove(key);
    }

    private void sendReject(
            short applicationId, int applicationSequenceNumber,
            DirectBuffer key, String reason) {
        BusServer.commit(busServer, rejectEntryEncoder.wrap(busServer.acquire())
                .setApplicationId(applicationId)
                .setApplicationSequenceNumber(applicationSequenceNumber)
                .setKey(key)
                .setReason(reason));
    }

    /**
     * Prints the number of keys tracked by the sequencer.
     *
     * @param encoder the object encoder
     */
    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("keys").number(keys.size())
                .closeMap();
    }
}
```

Key points:

- `BusServer.copy(busServer, decoder)` copies the inbound command verbatim as an event.
- `BusServer.commit(busServer, encoder)` publishes a newly constructed event (used for rejects).
- `UnifiedSet` is from Eclipse Collections — no `java.util.HashSet` on the hot path.
- The handler tracks which keys exist so it can validate deletes.

---

## 3. Implement the Client Application

The client listens for events on the bus and maintains the materialized state.
Any number of clients can run — each independently replays the same event log and arrives at the same state.

```java
package com.core.clob.applications;

import com.core.clob.schema.DeleteEntryDecoder;
import com.core.clob.schema.PutEntryDecoder;
import com.core.clob.schema.RejectEntryDecoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.platform.bus.BusClient;
import org.agrona.DirectBuffer;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

import java.util.Objects;

/**
 * Client-side key/value store that materializes state from events.
 *
 * <p>Listens for {@code PutEntry}, {@code DeleteEntry}, and {@code RejectEntry} events
 * and maintains an in-memory key-to-value map.
 */
public class KvStoreClient implements Encodable {

    private final UnifiedMap<DirectBuffer, DirectBuffer> store;
    private int rejectCount;

    /**
     * Creates a {@code KvStoreClient} and subscribes to key/value events.
     *
     * @param busClient the bus client
     */
    public KvStoreClient(BusClient<?, ?> busClient) {
        Objects.requireNonNull(busClient, "busClient is null");

        store = new UnifiedMap<>();

        var dispatcher = busClient.getDispatcher();
        dispatcher.addListener("putEntry", this::onPutEntry);
        dispatcher.addListener("deleteEntry", this::onDeleteEntry);
        dispatcher.addListener("rejectEntry", this::onRejectEntry);
    }

    private void onPutEntry(PutEntryDecoder decoder) {
        var key = decoder.getKey();
        var value = decoder.getValue();
        store.put(BufferUtils.copy(key), BufferUtils.copy(value));
    }

    private void onDeleteEntry(DeleteEntryDecoder decoder) {
        var key = decoder.getKey();
        store.remove(key);
    }

    private void onRejectEntry(RejectEntryDecoder decoder) {
        rejectCount++;
    }

    /**
     * Returns the value associated with the specified key, or "NOT_FOUND".
     *
     * @param key the key to look up
     * @return the value as a string
     */
    @Command(readOnly = true)
    public String get(DirectBuffer key) {
        var value = store.get(key);
        return value == null ? "NOT_FOUND" : BufferUtils.toAsciiString(value);
    }

    /**
     * Encodes the store status as a JSON-like object.
     *
     * @param encoder the object encoder
     */
    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("entries").number(store.size())
                .string("rejects").number(rejectCount)
                .closeMap();
    }
}
```

Key points:

- The client receives typed events via the `BusClient` dispatcher — the same events that the sequencer published.
- State is rebuilt purely from the event log. On restart, replay produces the identical map contents.
- `@Command(readOnly = true)` exposes the `get` method in the shell without side effects.
- `@Command(path = "status")` wires the `encode` method to the shell's `status` path.
- `UnifiedMap` from Eclipse Collections replaces `java.util.HashMap`.

---

## 4. Wire It Together (Command File)

Create `kv-store.cmd` to wire everything into a running process:

```bash
#
# usage: kv-store.cmd
# description: standalone key/value store with sequencer and client
#

# ── Infrastructure ────────────────────────────────────────────
source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001

# ── Schema ────────────────────────────────────────────────────
create /bus/schema com.core.clob.schema.ClobSchema

# ── Bus Client ────────────────────────────────────────────────
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# ── Sequencer + Command Handlers ──────────────────────────────
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/kvHandlers com.core.clob.applications.sequencer.KvCommandHandlers @/busServer

# ── KV Store Client ──────────────────────────────────────────
create kvStore com.core.clob.applications.KvStoreClient @/bus

# ── Injector (for shell-driven sends) ────────────────────────
create inject01a com.core.platform.applications.utilities.Injector @/bus INJ01
inject01a/start

# ── Lifecycle ─────────────────────────────────────────────────
/busServer/createSession AA
seq01a/start
```

Run it:

```bash
./gradlew uberjar

java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s kv-store.cmd
```

---

## 5. Try It Out

Connect to the shell via telnet and interact with the store:

```bash
nc 0.0.0.0 7001
```

### Put a value

```
/ % inject01a/send putEntry key=hello value=world
```

### Read it back

```
/ % kvStore/get hello
world
```

### Check status

```
/ % kvStore/status
{entries: 1, rejects: 0}
```

### Delete a key

```
/ % inject01a/send deleteEntry key=hello
/ % kvStore/get hello
NOT_FOUND
```

### Try an invalid delete

```
/ % inject01a/send deleteEntry key=doesNotExist
/ % kvStore/status
{entries: 0, rejects: 1}
```

### Inspect sequencer state

```
/ % seq01a/kvHandlers/status
{keys: 0}
```

---

## 6. Add HA Failover

The platform supports primary/backup HA with lease-based fencing.
Adding it to the KV store takes three lines.

### Primary (`kv-store-primary.cmd`)

```bash
#
# usage: kv-store-primary.cmd
# description: primary KV store with HA consensus
#

source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001
create /bus/schema com.core.clob.schema.ClobSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# Sequencer
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/kvHandlers com.core.clob.applications.sequencer.KvCommandHandlers @/busServer

# KV Client + Injector
create kvStore com.core.clob.applications.KvStoreClient @/bus
create inject01a com.core.platform.applications.utilities.Injector @/bus INJ01
inject01a/start

# ── HA: these three lines add consensus fencing ──────────────
source -s promotion-guard.cmd
source -s consensus.cmd
source -s consensus-wire.cmd seq01a

# Lifecycle
/busServer/createSession AA
seq01a/start
```

### Backup (`kv-store-backup.cmd`)

```bash
#
# usage: kv-store-backup.cmd
# description: backup KV store — replays events, ready for promotion
#

source network-local.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7002
create /bus/schema com.core.clob.schema.ClobSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel

# Backup Sequencer
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer \
    server @/bus @/bus/schema @/busServer/store $event_channel $command_channel $discovery_channel

create seq01b com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01b/kvHandlers com.core.clob.applications.sequencer.KvCommandHandlers @/busServer

# KV Client (receives same events, builds same state)
create kvStore com.core.clob.applications.KvStoreClient @/bus

# HA
source -s promotion-guard.cmd
source -s consensus.cmd
source -s consensus-wire.cmd seq01b

# Backup does NOT call createSession or seq/start
# It just listens on the bus
/bus/start
```

The backup replays the same ordered event log and builds identical state.
If the primary fails, the consensus module promotes the backup:
it acquires the lease, validates it is caught up, and starts sequencing from `lastSeq + 1`.

---

## 7. What You've Built

```
                         ┌─────────────────────────────┐
                         │       Sequencer (Primary)    │
                         │                             │
  Client ──put/delete──▶ │  Command     KvCommand-     │
           (command      │  Channel ──▶ Handlers ──▶  │
            channel)     │              │  validate    │
                         │              │  produce     │
                         │              ▼  event       │
                         │         Event Channel ──────┼──▶ KvStoreClient (primary)
                         │              │              │     state: {hello: world}
                         │              │              │
                         └──────────────┼──────────────┘
                                        │
                                        └─────────────────▶ KvStoreClient (backup)
                                                            state: {hello: world}
                                                            (identical, from same log)
```

### What you get "for free"

| Feature | How it works |
|---|---|
| **Deterministic replay** | State is derived solely from the ordered event log. Restart and replay produces identical state. |
| **Snapshot recovery** | Add `source snapshot.cmd` to periodically snapshot state. Recovery = load snapshot + replay tail. |
| **HA failover** | Consensus module + promotion guard. Three lines of config. No code changes. |
| **Shell inspection** | Every `@Command` method is accessible via telnet. `status`, `get`, `printBook` — all live. |
| **Zero-allocation hot path** | Flyweight encoders/decoders over `DirectBuffer`. No object creation on the critical path. |
| **Gap detection & replay** | MoldUDP64 bus detects sequence gaps and requests replay automatically. |

### Total code

| Component | Lines |
|---|---|
| Schema XML | ~12 |
| `KvCommandHandlers.java` | ~75 |
| `KvStoreClient.java` | ~55 |
| `kv-store.cmd` | ~20 |
| **Total** | **~162** |

---

## What's Next

- **[getting-started.md](getting-started.md)** — Build and run the platform
- **[developer-manual.md](developer-manual.md)** — Writing your own applications and schemas
- **[command-files.md](command-files.md)** — Command file format and all `.cmd` files explained
- **[ha-architecture.md](ha-architecture.md)** — Deep dive into primary/backup failover
- **[deterministic-snapshots.md](deterministic-snapshots.md)** — Snapshot format and recovery
