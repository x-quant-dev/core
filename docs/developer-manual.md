# Core Sequencer Platform — Developer Manual

This guide covers writing applications, defining schemas, SBE integration, testing, and extending the Core Sequencer Platform.

---

## 1. Architecture Overview

The Core Sequencer Platform implements a **single-threaded, sequencer-centric architecture** based on the command/event pattern. All state changes flow through a central sequencer that serializes commands into a deterministic event stream.

- **Single event loop** — no locks, no concurrent access.
- **Command/event separation** — applications send commands; the sequencer validates, sequences, and publishes events.
- **Activation graph** — explicit dependency management ensures components start in the correct order with no race conditions.
- **Deterministic replay** — every event is sequenced and timestamped, enabling exact behavioral reproduction.

### Data Flow

```
Application → Provider → Command Channel → Sequencer → BusServer → Event Channel → Dispatcher → Application
```

1. An application encodes a command using a `Provider` and sends it to the command channel.
2. The **Sequencer** receives the command, validates the application ID and sequence number, and dispatches it to registered command handlers.
3. Command handlers validate business logic, then write events to the event channel via `BusServer.acquire()` / `BusServer.commit()`.
4. All applications (including the sender) receive the sequenced event through their `Dispatcher`.

---

## 2. Module Guide

### 2.1 `infrastructure`

No platform dependencies. Pure utilities used across the entire system.

| Package | Purpose |
|---------|---------|
| `buffer` | Buffer utilities and allocation-free wrappers |
| `io` | Asynchronous I/O, selectors, network primitives |
| `messages` | `Schema`, `Encoder`, `Decoder`, `Dispatcher`, `Provider` interfaces |
| `time` | `Time` interface, `ManualTime` for deterministic control |
| `log` | Structured logging (`Log`, `LogFactory`) |
| `metrics` | Metrics collection (`MetricFactory`) |
| `collections` | Garbage-free collections (pools, intrusive lists) |
| `encoding` | `Encodable`, `ObjectEncoder` for status/serialization |

### 2.2 `platform`

Depends on `infrastructure`. Provides the Sequencer architecture, bus implementations, and transport layers.

| Package | Purpose |
|---------|---------|
| `applications.sequencer` | Sequencer application and command validation |
| `bus.mold` | MoldUDP64 bus implementation |
| `bus.aeron` | Aeron-based bus implementation with archive support |
| `bus.playback` | Corefile playback for replay and debugging |
| `activation` | `Activatable` interface, `ActivatorFactory`, activation graph |
| `shell` | Command shell, `@Command`, `@Property`, `@Directory` annotations |
| `schema.sbe` | SBE-based generic schema (`SbeSchema`, `SbeDispatcher`, `SbeProvider`) |
| `applications.sequencer` | Sequencer, PromotionGuard, AutoFailover, ConsensusModule, TcpConsensusServer/Client |
| `applications.snapshot` | SnapshotCoordinator, SnapshotIndex, SnapshotRecovery, LateJoinerService, Snapshottable |

### 2.3 `clob`

Depends on `platform`. A sample domain implementation demonstrating how to build a trading core.

| Class | Purpose |
|-------|---------|
| `ClobSchema` | Generated typed schema from `clob-schema.xml` |
| `ClobCommandHandlers` | Sequencer-side command validation and order book logic |
| `ClobInjector` | Injects reference data and test orders |
| `ClobPrinter` | Prints all sequenced events for debugging |
| `ReferenceDataPublisher` | Publishes equity definitions |

### 2.4 `buildSrc`

Gradle tasks for code generation.

| Task | Purpose |
|------|---------|
| `GenerateSchemaTask` | Generates typed encoders, decoders, dispatcher, provider, and schema from XML |
| `GenerateSbeTask` | Generates SBE codecs from SBE XML |
| `GenerateFixTask` | Generates FIX message parsers |

---

## 3. Writing a New Application

### 3.1 Application Structure

Applications follow a consistent pattern:
- Accept `BusClient<?,?>` as a constructor parameter to interact with the message bus.
- Register event listeners on the dispatcher to receive sequenced events.
- Obtain a provider to send commands.
- Use `@Command` annotations for shell-accessible methods.
- Optionally implement `Encodable` for status reporting.

**Example — a simple price tracker:**

```java
package com.core.clob.applications;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.messages.Decoder;
import com.core.platform.bus.BusClient;

public class PriceTracker implements Encodable {
    private final Log log;
    private long lastPrice;
    private int fillCount;

    public PriceTracker(LogFactory logFactory, BusClient<?, ?> busClient) {
        log = logFactory.create(getClass());
        busClient.getDispatcher().addListener("fillOrder", this::onFillOrder);
    }

    private void onFillOrder(Decoder decoder) {
        lastPrice = decoder.integerValue("price");
        fillCount++;
        log.info().append("fill: price=").append(lastPrice).commit();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("lastPrice").number(lastPrice)
                .string("fillCount").number(fillCount)
                .closeMap();
    }
}
```

### 3.2 Registering via Command File

Applications are instantiated and registered through command files executed by the shell:

```
create /tracker com.core.clob.applications.PriceTracker @/bus
```

- `/tracker` — the shell directory where the object is registered.
- The class's fully qualified name follows.
- `@/bus` — a reference to the bus client already registered at `/bus`.

### 3.3 Constructor Parameter Resolution

The shell resolves constructor parameters using the following rules:

| Parameter Type | Resolution |
|----------------|------------|
| Implicit (e.g., `LogFactory`, `MetricFactory`, `Time`, `Scheduler`, `Shell`) | Auto-injected by the platform; not specified in the command file |
| `@/path` references | Resolved from the shell registry by directory path |
| String literals | Passed directly as `String` |
| Numeric literals | Parsed and passed as `int`, `long`, etc. |

The constructor with the matching parameter count (excluding implicit parameters) is selected.

### 3.4 Sending Commands

To send commands, obtain a `Provider` from the bus client and use its encoders:

```java
var provider = busClient.getProvider("MY_APP", this);
var encoder = provider.getEncoder("addOrder");
encoder.set("orderId", 1);
encoder.set("side", (byte) 1);
encoder.set("qty", 100L);
encoder.set("instrumentId", 1);
encoder.set("price", 150L);
encoder.commit().send();
```

- `"MY_APP"` — the application name, used to assign an application ID.
- `this` — the activatable owner; the provider becomes active when the owner is active.
- The encoder writes fields into a pre-allocated buffer. `commit()` finalizes the message; `send()` publishes it to the command channel.

### 3.5 Activation Lifecycle

Applications that require lifecycle management implement the `Activatable` interface.

```java
public class MyApp implements Activatable {
    private final Activator activator;

    public MyApp(ActivatorFactory activatorFactory, BusClient<?, ?> busClient) {
        activator = activatorFactory.createActivator("myApp", this, busClient);
    }

    @Override
    public void activate() {
        // called when: started + ready + all dependencies active
    }

    @Override
    public void deactivate() {
        // called when any dependency deactivates or notReady() is called
    }
}
```

**Activation rules:**
- Create an activator via `activatorFactory.createActivator("name", this, dependencies...)`.
- Call `activator.ready()` when the component is ready to activate.
- Call `activator.notReady()` to deactivate.
- Activation cascades: a component activates only when it is started, ready, and all of its dependencies are active.

---

## 4. Schema Definition

### 4.1 Velocity-Generated Schema (Typed)

The primary schema is defined in XML and processed by the `GenerateSchemaTask` to produce typed Java classes.

**Schema file:** `clob/src/main/resources/clob-schema.xml`

#### Header (18 bytes)

Every message begins with an 18-byte header:

| Offset | Type | Field |
|--------|------|-------|
| 0 | `short` | `applicationId` |
| 2 | `int` | `applicationSequenceNumber` |
| 6 | `long` | `timestamp` |
| 14 | `short` | `optionalFieldsIndex` |
| 16 | `byte` | `schemaVersion` |
| 17 | `byte` | `messageType` |

#### XML Format

```xml
<core package="com.core.clob.schema" version="1" prefix="Clob">
    <properties>
        <property name="heartbeatMessageName" value="heartbeat"/>
        <property name="applicationIdField" value="applicationId"/>
        <property name="applicationDefinitionMessageName" value="applicationDefinition"/>
        <property name="applicationDefinitionNameField" value="name"/>
    </properties>

    <enums>
        <enum name="Side" description="the side of the order">
            <value name="Buy" value="1" description="buy the instrument" />
            <value name="Sell" value="2" description="sell the instrument" />
        </enum>
    </enums>

    <header>
        <field name="ApplicationId" type="short" primary-key="true"/>
        <field name="ApplicationSequenceNumber" type="int"/>
        <field name="Timestamp" type="long" metadata="timestamp"/>
        <field name="OptionalFieldsIndex" type="short"/>
        <field name="SchemaVersion" type="byte"/>
        <field name="MessageType" type="byte"/>
    </header>

    <messages>
        <message id="1" name="Heartbeat"/>

        <message id="4" name="AddOrder">
            <field name="OrderId" type="int"/>
            <field name="Side" type="Side"/>
            <field name="Qty" type="long"/>
            <field name="InstrumentId" type="int" foreign-key="equity"/>
            <field name="Price" type="long"/>
        </message>

        <message id="7" name="RejectOrder">
            <field name="Side" type="byte"/>
            <field name="Qty" type="long"/>
            <field name="InstrumentId" type="int" foreign-key="equity"/>
            <field name="Price" type="long"/>
            <optional name="Reason" type="DirectBuffer"/>
        </message>
    </messages>
</core>
```

**Field types:** `byte`, `short`, `int`, `long`, `double`, `DirectBuffer` (variable-length).

**Field attributes:**
- `primary-key` — uniquely identifies an entity instance.
- `foreign-key` — references another entity by name.
- `key` — logical key for lookups.
- `metadata` — field metadata (e.g., `timestamp`).

**Optional fields** (`<optional>`) are variable-length and stored after the `optionalFieldsIndex` offset.

**Generated classes:** `ClobSchema`, `ClobDispatcher`, `ClobProvider`, per-message `Encoder` (e.g., `AddOrderEncoder`), per-message `Decoder` (e.g., `AddOrderDecoder`), enums (e.g., `Side`).

### 4.2 Adding a New Message

1. **Define the message** in `clob/src/main/resources/clob-schema.xml` with a unique `id`:

    ```xml
    <message id="14" name="ModifyOrder">
        <field name="OrderId" type="int"/>
        <field name="NewQty" type="long"/>
        <field name="NewPrice" type="long"/>
    </message>
    ```

2. **Run code generation:**

    ```bash
    ./gradlew :clob:generateSchema
    ```

3. **Add a command handler** (if sequencer-side) in `ClobCommandHandlers`:

    ```java
    dispatcher.addModifyOrderListener(this::onModifyOrder);
    ```

4. **Add an event listener** (if client-side) in your application:

    ```java
    busClient.getDispatcher().addModifyOrderListener(this::onModifyOrder);
    ```

### 4.3 SBE Schema (Generic)

The platform provides an alternative schema implementation using Simple Binary Encoding (SBE).

**Key characteristics:**
- Implements `Schema<SbeDispatcher, SbeProvider>` — same interface as the typed schema.
- Uses the **same 18-byte header layout**, making it wire-compatible with the Velocity-generated schema.
- Uses generic field access: `decoder.get("fieldName")` / `decoder.integerValue("fieldName")` instead of typed getters like `decoder.getPrice()`.
- Defined in `SbeSchema.java` with explicit field offsets rather than generated code.

**SBE XML definition:** `clob/src/main/resources/clob-sbe-schema.xml` (standard SBE XML format).

**Drop-in replacement** — switch from the typed schema to SBE in a command file:

```
create /bus/schema com.core.platform.schema.sbe.SbeSchema
```

**Adding a new SBE message:**

1. Add the message to `clob/src/main/resources/clob-sbe-schema.xml`.
2. Update `SbeSchema.defineMessages()` with the field layout:

    ```java
    define("modifyOrder", (byte) 14, concat(HEADER_FIELDS, new SbeFieldLayout[]{
            new SbeFieldLayout("orderId", HEADER_LENGTH, SbeFieldType.INT, false),
            new SbeFieldLayout("newQty", HEADER_LENGTH + 4, SbeFieldType.LONG, false),
            new SbeFieldLayout("newPrice", HEADER_LENGTH + 12, SbeFieldType.LONG, false),
    }));
    ```

3. Add `"modifyOrder"` to the `MESSAGE_NAMES` array in `SbeSchema`.

### 4.4 Where to Add New Domain Objects

| Artifact | Location |
|----------|----------|
| Message definitions | `clob/src/main/resources/clob-schema.xml` |
| SBE definitions | `clob/src/main/resources/clob-sbe-schema.xml` |
| Enums | `<enums>` block in the schema XML |
| Sequencer command handlers | `clob/src/main/java/.../sequencer/ClobCommandHandlers.java` |
| SBE message layouts | `SbeSchema.defineMessages()` in `platform/src/main/java/.../schema/sbe/SbeSchema.java` |

---

## 5. Sequencer-Side Development

### 5.1 Command Handlers

Command handlers run inside the sequencer and are registered on `BusServer.getDispatcher()`. They receive commands, validate them, and produce sequenced events.

```java
public ClobCommandHandlers(LogFactory logFactory, BusServer<ClobDispatcher, ClobProvider> busServer) {
    this.busServer = busServer;
    log = logFactory.create(getClass());

    var dispatcher = busServer.getDispatcher();
    dispatcher.addHeartbeatListener(this::onHeartbeat);
    dispatcher.addAddOrderListener(this::onAddOrder);
    dispatcher.addCancelOrderListener(this::onCancelOrder);
    // ... additional handlers
}
```

**Writing events** — handlers acquire a buffer, encode the event, and commit:

```java
private void onHeartbeat(HeartbeatDecoder decoder) {
    // Simple passthrough: copy the command as an event
    BusServer.copy(busServer, decoder);
}

private void onAddOrder(AddOrderDecoder decoder) {
    // Validate, then write the event
    var buf = busServer.acquire();
    var length = addOrderEncoder.copy(decoder, buf)
            .setOrderId(nextOrderId++)
            .length();
    BusServer.commit(busServer, addOrderEncoder);
}
```

- `busServer.acquire()` — returns a pre-allocated buffer for writing.
- `BusServer.commit(busServer, encoder)` — publishes the encoded event to the event channel.
- `BusServer.copy(busServer, decoder)` — copies a command directly as an event (for passthrough messages like heartbeats).

### 5.2 Validation Pattern

The sequencer enforces two levels of validation:

1. **Transport-level:** The sequencer validates `applicationId` and `applicationSequenceNumber` on every incoming command to detect duplicates and ensure ordering.
2. **Business-level:** Command handlers validate domain logic (e.g., valid enum values, non-empty fields, referential integrity) and can reject commands by publishing reject events.

```java
private void onEquityDef(EquityDefinitionDecoder decoder) {
    var ticker = decoder.getTicker();
    if (ticker.capacity() == 0) {
        log.warn().append("empty equity ticker").commit();
        return; // silently drop invalid command
    }
    // ... assign instrumentId and publish event
}
```

### 5.3 Thread Safety and Fail-Stop

The sequencer enforces single-threaded access via `ThreadIdentityGuard`:

```java
private final ThreadIdentityGuard threadGuard = new ThreadIdentityGuard();

private void onCommand(DirectBuffer buffer, int offset, int length) {
    threadGuard.check();  // throws if called from wrong thread
    // ...
}
```

Both `onCommand` (active path) and `onEvent` (passive path) are guarded. Enable thread checks with `-Dcore.threadChecks=true`.

On dispatch errors, both paths implement fail-stop behavior:
- Increment `dispatchErrorCount`.
- Log a fatal error.
- Call `activator.stop()` to deactivate.

This prevents state divergence between primary and backup nodes.

---

## 6. Testing

### 6.1 Unit Testing with Test Fixtures

The platform provides in-memory test doubles in `platform/src/testFixtures`:

| Fixture | Purpose |
|---------|---------|
| `TestBusServer<D, P>` | In-memory bus server for sequencer-side testing |
| `TestBusClient<D, P>` | In-memory bus client for application-side testing |
| `TestMessagePublisher` | Captures sent messages for assertion |
| `ManualTime` | Deterministic time control (advance time manually) |
| `TestLogFactory` | Test-compatible logging |

**Typical test pattern:**

```java
@Test
void shouldTrackFillPrice() {
    // arrange
    var time = new ManualTime();
    var schema = new ClobSchema();
    var busServer = new TestBusServer<>(schema);
    var busClient = new TestBusClient<>(schema);
    var logFactory = new TestLogFactory();

    var tracker = new PriceTracker(logFactory, busClient);

    // act: simulate a fill event arriving via the dispatcher
    var encoder = schema.createEncoder("fillOrder");
    // ... encode fields, dispatch to bus client

    // assert
    BDDAssertions.then(tracker.getLastPrice()).isEqualTo(150L);
}
```

**Testing conventions:**
- JUnit 5 for test lifecycle.
- AssertJ with `BDDAssertions.then()` for fluent assertions.
- Mockito for mocking dependencies where needed.

### 6.2 Integration Testing with Embedded Aeron

For end-to-end tests involving Aeron transport and archiving:

```java
class AeronBusIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    @Timeout(30)
    void shouldRecordAndReplayMessages() throws Exception {
        var controlPort = EmbeddedAeronTestFixture.reservePort();
        var eventPort = EmbeddedAeronTestFixture.reservePort();

        try (var fixture = new EmbeddedAeronTestFixture(tempDir, controlPort, eventPort)) {
            var recordingId = fixture.recordMessages(10, 64);
            // verify archive contains the expected messages
        }
    }
}
```

**Key points:**
- `EmbeddedAeronTestFixture` launches an `ArchivingMediaDriver` per test.
- `@TempDir` ensures clean media driver directories.
- `reservePort()` allocates unique ports to avoid conflicts.
- `@Timeout(30)` on all integration tests to prevent hangs.

### 6.3 Running Tests

```bash
# All tests across all modules
./gradlew test

# Platform module only
./gradlew :platform:test

# Specific package
./gradlew :platform:test --tests "com.core.platform.schema.sbe.*"

# Specific test class
./gradlew :clob:test --tests "com.core.clob.applications.sequencer.ClobCommandHandlersTest"
```

---

## 7. Aeron Configuration for Developers

### 7.1 Channel URI Format

| URI | Transport |
|-----|-----------|
| `aeron:udp?endpoint=host:port` | Unicast UDP |
| `aeron:udp?endpoint=239.x.x.x:port` | Multicast UDP |
| `aeron:ipc` | Inter-process communication (shared memory) |
| `aeron:udp?control-mode=manual` | Manual control for ReplayMerge |

### 7.2 IPC vs UDP Decision Matrix

| Criterion | IPC | UDP |
|-----------|-----|-----|
| Same host | ✅ Preferred | ✅ Works |
| Cross host | ❌ | ✅ Required |
| Latency | Lowest | Low |
| Archive recording | ✅ | ✅ |
| Flow control | Built-in | Configurable |

**Guideline:** Use IPC when all processes are on the same host. Use UDP when processes span multiple hosts or when multicast delivery is required.

### 7.3 Stream IDs

| Stream | Default ID | Purpose |
|--------|-----------|---------|
| Event | 1001 | Sequenced events broadcast to all applications |
| Command | 1002 | Commands from applications to the sequencer |
| Archive control | 100 | Archive recording control channel |

Stream IDs must be unique per logical channel. When running multiple cores on the same host, assign distinct stream IDs or use separate channels.

---

## 8. Design Principles

| Principle | Rationale |
|-----------|-----------|
| **Single-threaded event loop** | No locks, no concurrent access — eliminates an entire class of bugs. |
| **Allocation-free hot path** | Pre-allocated buffers and object pools — no GC pressure during message processing. |
| **Deterministic replay** | `ManualTime` + corefile playback reproduces exact behavior for debugging. |
| **Schema-first** | All messages defined in XML; code is generated — ensures consistency across producers and consumers. |
| **Shell-driven configuration** | All wiring via command files — no hardcoded values, no Spring, no DI frameworks. |
| **Activation graph** | Explicit dependency management — components activate in the correct order with no race conditions. |
| **Wire compatibility** | The Velocity-generated and SBE schemas share the same 18-byte header — they are interchangeable on the wire. |
| **Fail-stop on errors** | Dispatch exceptions in both active and passive paths cause immediate deactivation — prevents state divergence between primary and backup. |
| **Lease-based fencing** | ConsensusModule prevents split-brain with monotonic epoch tokens — only one sequencer can be active at a time. |
| **Consecutive-pass gating** | AutoFailover requires multiple consecutive successful health checks before promotion — prevents flapping during transient failures. |
| **Checkpoint recovery** | LateJoinerService orchestrates snapshot restore → delta replay → live — reduces recovery from hours to seconds. |
