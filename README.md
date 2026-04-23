# Core Sequencer Platform

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

**Dependencies:** [Agrona](https://github.com/real-logic/agrona) (buffer abstraction) ·
[Eclipse Collections](https://www.eclipse.org/collections/) (GC-free collections) ·
[Aeron](https://github.com/real-logic/aeron) (messaging transport and archive) ·
[SBE](https://github.com/real-logic/simple-binary-encoding) (binary message encoding)

## Quick Start

```bash
./gradlew uberjar

java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main -s clob.cmd

nc 0.0.0.0 7001          # then type: inject01a/submit LEHM01 Buy 100 AAPL 150
```

## Project Structure

| Module | Description |
|--------|-------------|
| `buildSrc` | Code generators for binary encoders/decoders and FIX messages |
| `infrastructure` | Allocation-free utilities: async I/O, buffers, encoding, logging, metrics, time |
| `platform` | Sequencer architecture, bus transports, activation lifecycle, shell, HA |
| `clob` | Demo CLOB (Central Limit Order Book) trading core built on the platform |

## Documentation

→ **[docs/README.md](docs/README.md)** — Full documentation index

| I want to... | Go to |
|--------------|-------|
| Build and run the demo | [Getting Started](docs/getting-started.md) |
| Build a sample application | [Tutorial: KV Store](docs/tutorial-kv-store.md) |
| Write a new application | [Developer Manual](docs/developer-manual.md) |
| Configure HA failover | [User Manual](docs/user-manual.md) |
| Troubleshoot a production issue | [Operations Runbook](docs/operations-runbook.md) |
| Understand the architecture | [Overview](docs/overview.md) |

## Publishing to Maven Local

To publish the artifacts to your local Maven repository for use in other local projects:

```bash
./gradlew publishToMavenLocal
```

This will publish all modules (`clob`, `infrastructure`, `platform`, and `buildSrc`) to your local Maven repository (~/.m2/repository).
You can then reference them in other Maven or Gradle projects using their group ID and version.

## Attribution

Originally authored by Jim Greco as the infrastructure and platform behind the F9 proprietary trading system (2024).
The module layout (`buildSrc` / `infrastructure` / `platform` / `clob`), the command shell, and the Telnet-driven
bootstrap model described in the Quick Start are derived from that original work. See git history prior to
commit `12bb6be` for the original `QuickStart Guide` README and its full shell walkthrough; equivalent material
now lives — rewritten and expanded — across the `docs/` tree (notably `command-files.md`, `getting-started.md`,
and `user-manual.md`).
