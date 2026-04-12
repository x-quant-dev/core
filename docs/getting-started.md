# Getting Started

Get from zero to a running Core Sequencer Platform instance in under 10 minutes.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| **Java 17+** | JDK required (not JRE). Tested with OpenJDK and Eclipse Temurin. |
| **Gradle** | Wrapper included — no separate install needed. |
| **JVM flag** | `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` (required for high-precision time) |
| **Aeron Media Driver** | Only if using external Aeron transport mode (see Option 3 below) |

## Project Structure

```
core/
├── buildSrc/            # Code generators (schema, FIX, SBE)
├── infrastructure/      # Allocation-free utilities, I/O, time, logging, metrics
├── platform/            # Sequencer, bus, activation, shell, Aeron/MoldUDP64
├── clob/                # Sample CLOB (Central Limit Order Book) domain
├── config/              # Checkstyle, PMD rules
└── reverse-engineering/ # Architecture documentation
```

## Build

```bash
# Full build with tests
./gradlew build

# Fat JAR only (faster, skips tests)
./gradlew uberjar
```

The fat JAR is created at `clob/build/libs/core-1.0-SNAPSHOT.jar`.

## Run Your First Application

All run commands use the same base invocation. The only difference is the command file (`-s`) which selects the transport layer.

### Option 1: MoldUDP64 Transport (Simplest)

No external dependencies — start here.

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob.cmd
```

This starts a standalone CLOB with a sequencer, order book, reference data loader, event printer, and test injector — all in a single process using multicast UDP.

### Option 2: Aeron Transport (Embedded Driver)

Uses Aeron IPC with a media driver embedded in the same JVM.

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron-embedded.cmd /tmp/aeron
```

### Option 3: Aeron Transport (External Driver)

For production-like deployments. Start the Aeron Media Driver process first, then launch the application pointing at the same directory.

```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s clob-aeron.cmd /tmp/aeron
```

## Connect to the Shell

Once the application is running, connect via netcat or telnet on port 7001:

```bash
nc 0.0.0.0 7001
```

### Basic Shell Commands

The shell provides a filesystem-like interface to all registered objects.

```
/ % ls                           # list all registered objects
/ % cd seq01a                    # navigate to the sequencer
/seq01a % status                 # view sequencer status
/seq01a % cd ..
/ % cd bus
/bus % status                    # view bus status
```

### Send a Test Order

Use the injector to submit an order through the sequencer:

```
/ % inject01a/submit LEHM01 Buy 100 AAPL 150
```

## Explore the CLOB Demo

The `clob.cmd` command file wires together the following components:

| Object | Class | Role |
|---|---|---|
| `seq01a` | `Sequencer` | Validates and sequences inbound commands |
| `seq01a/handlers` | `ClobCommandHandlers` | CLOB matching engine (processes sequenced commands) |
| `ref01a` | `ReferenceDataPublisher` | Loads equity definitions from CSV files |
| `print01a` | `ClobPrinter` | Prints all events to the log |
| `inject01a` | `ClobInjector` | Submits test orders for exercising the system |

## Run Tests

```bash
./gradlew test
```

## What's Next

- **[user-manual.md](user-manual.md)** — Configuration, deployment, and monitoring
- **[developer-manual.md](developer-manual.md)** — Writing your own applications and schemas
- **[overview.md](overview.md)** — Architecture deep dive
- **[command-files.md](command-files.md)** — Command file format and all `.cmd` files explained
