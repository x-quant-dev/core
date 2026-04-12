# Command Files: Format, Purpose, and Authoring Guide

Command files (`.cmd`) are the primary mechanism for configuring, composing, and launching the platform. They replace traditional configuration files (YAML, properties, XML) with an executable scripting model that creates objects, wires dependencies, sets variables, and starts services — all through the command shell.

---

## Purpose

Command files serve three roles:

1. **Topology definition.** Which components run in this process — sequencer, bus client, applications, monitoring.
2. **Configuration.** Network addresses, session names, file paths, feature toggles.
3. **Lifecycle orchestration.** Start order, session creation, activation sequencing.

Every runtime deployment is defined entirely by which command files are loaded. The same Java binary can run as a primary sequencer, a standby, a client-only node, or a monitoring tool — the command file determines the topology.

---

## How Command Files Are Loaded

### Entry Point

`Main.java` processes command-line arguments to load command files:

```bash
java -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources \
  -jar core.jar com.core.platform.Main \
  -s clob-aeron.cmd /tmp/aeron
```

| Flag | Behaviour |
|------|-----------|
| `-s <file> [args...]` | **Subshell.** Load and execute file. Variables set inside are **discarded** after execution (isolated scope). |
| `-f <file> [args...]` | **Full shell.** Load and execute file. Variables set inside are **merged back** into the parent scope (persist for subsequent files). |

Multiple `-s` and `-f` flags can be chained. They execute in order.

### File Resolution: `SHELL_PATH`

The `SHELL_PATH` system property (colon-separated list of directories) controls where the shell looks for `.cmd` files. When a `source` command or `-s`/`-f` flag references a filename, the shell searches each directory in `SHELL_PATH` order and uses the **first match**.

```
SHELL_PATH=platform/src/main/resources:clob/src/main/resources

source network-local.cmd
  → tries platform/src/main/resources/network-local.cmd  ✓ found
  
source clob-aeron.cmd
  → tries platform/src/main/resources/clob-aeron.cmd     ✗ not found
  → tries clob/src/main/resources/clob-aeron.cmd          ✓ found
```

---

## Syntax Reference

### Comments

```
# This is a comment. Everything after # is ignored.
```

### Variables

```
set event_channel inet:239.100.100.100:10100:lo0    # always set
default append_log_file true                         # set only if not already defined
```

Variables are referenced with `$`:

```
create /bus ... $event_channel $command_channel
```

### Positional Arguments

Command files receive arguments via `$1`, `$2`, etc. `$0` is the filename.

```
# aeron-network.cmd — called with: source aeron-network.cmd /tmp/aeron
set aeron_dir $1    # $1 = /tmp/aeron
```

### Object Creation

```
create <path> <fully.qualified.ClassName> [args...]
```

Creates a Java object via reflection and registers it at `<path>` in the shell directory tree. Constructor parameters are resolved in this order:

1. **Implied parameters** — `Time`, `Scheduler`, `Selector`, `LogFactory`, `MetricFactory`, `ActivatorFactory`, `Shell`, `EventLoop` are auto-injected by `Main.java`. Never specified in command files.
2. **Object references** — `@path` resolves to the object registered at that shell path.
3. **Variables** — `$variable_name` expands to the variable's value.
4. **Literal strings** — passed directly.

```
# Implied params (Time, Scheduler, etc.) are auto-injected — not listed
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel
#   ^^^^^^ ^^^^^^^^^^^^ ^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^
#   arg1   arg2(ref)    arg3(var)      arg4(var)        arg5(var)
```

### Object References (`@`)

```
@/bus/schema    → resolves to the object at path /bus/schema
@busServer      → resolves to the object at path busServer (relative)
```

### Command Invocation

```
<path>/<method> [args...]
```

Calls a `@Command`-annotated method on the object at `<path>`:

```
/busServer/createSession AA        # calls createSession("AA") on busServer
seq01a/start                       # calls start() on seq01a
ref01a/setPath clob/src/main/resources
/vm/log/debugForAll true
```

### Property Setting

```
set /snapshotCoordinator/intervalMs 300000
```

Sets a `@Property`-annotated field on the object.

### Source (Include Another File)

```
source <file> [args...]         # execute file, variables merge back
source -s <file> [args...]      # execute in subshell, variables isolated
```

### Line Continuation

```
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel
```

Backslash at end of line continues the statement on the next line.

### String Escaping

```
"string with spaces"     # double quotes for spaces in values
\$                       # literal dollar sign
\\                       # literal backslash
\n                       # newline
\t                       # tab
```

---

## File Structure Conventions

### Standard Sections

A well-structured command file follows this order:

```
# 1. Header comment (usage, description, parameters)
# 2. Source infrastructure files (network, logging, shell access)
# 3. Create schema
# 4. Create bus transport (client and/or server)
# 5. Create sequencer (if this node runs one)
# 6. Create applications
# 7. Create session and start services (lifecycle)
```

### Example: Annotated `clob-aeron.cmd`

```bash
#
# usage: clob-aeron.cmd <aeron_dir>
# description: demonstration of a clob inside the sequencer using Aeron transport
#

# ── 1. Infrastructure ────────────────────────────────────────
source aeron-network.cmd $1          # sets $event_channel, $command_channel, etc.
source -s sysout-log.cmd             # logging to stdout (isolated scope)
source -s telnet.cmd inet:0.0.0.0:7001  # telnet shell access

# ── 2. Schema ────────────────────────────────────────────────
create /bus/schema com.core.clob.schema.ClobSchema

# ── 3. Transport ─────────────────────────────────────────────
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema $aeron_dir \
    $event_channel $event_stream $command_channel $command_stream $session_name

create /busServer com.core.platform.bus.aeron.AeronBusServer \
    @/bus/schema $aeron_dir \
    $event_channel $event_stream $command_channel $command_stream $session_name

# ── 4. Sequencer ─────────────────────────────────────────────
create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/handlers com.core.clob.applications.sequencer.ClobCommandHandlers @/busServer

# ── 5. Applications ──────────────────────────────────────────
create ref01a com.core.platform.applications.refdata.ReferenceDataPublisher \
    @/bus REF01 equityDefinition
ref01a/setPath clob/src/main/resources
ref01a/start
ref01a/loadFiles

create print01a com.core.clob.applications.printer.ClobPrinter @/bus .

create inject01a com.core.clob.applications.utilities.ClobInjector @/bus INJ01
inject01a/start

# ── 6. Lifecycle ─────────────────────────────────────────────
seq01a/start
```

---

## File Categorization

### Infrastructure Files (Platform Module)

Reusable building blocks sourced by application-level command files:

| File | Purpose | Parameters |
|------|---------|------------|
| `network-local.cmd` | Sets MoldUDP64 multicast addresses for local development | None |
| `aeron-network.cmd` | Sets Aeron channel/stream variables | `$1` = aeron directory |
| `aeron-embedded.cmd` | Creates embedded Aeron Media Driver | `$1` = aeron directory |
| `aeron-external.cmd` | Sets aeron_dir for external driver | `$1` = aeron directory |
| `sysout-log.cmd` | Logging to stdout | None |
| `file-log.cmd` | Logging to file | `$1` = log filename |
| `telnet.cmd` | Telnet shell access | `$1` = bind address |
| `http.cmd` | HTTP shell access | `$1` = bind address |
| `ws.cmd` | WebSocket shell access | `$1` = bind address |
| `cli-shell.cmd` | CLI stdin shell | None |
| `metrics.cmd` | Metrics publisher | `$1` = metrics log file |

### Application Files (Clob Module)

Complete topology definitions:

| File | Topology | Transport |
|------|----------|-----------|
| `clob.cmd` | All-in-one (sequencer + apps) | MoldUDP64 |
| `clob-primary.cmd` | Primary sequencer node | MoldUDP64 |
| `clob-backup.cmd` | Standby sequencer node | MoldUDP64 |
| `clob-2seq.cmd` | Dual sequencer (primary + backup in same JVM) | MoldUDP64 |
| `clob-aeron.cmd` | All-in-one with Aeron (external driver) | Aeron UDP |
| `clob-aeron-embedded.cmd` | All-in-one with Aeron (embedded driver) | Aeron UDP |
| `clob-tier0.cmd` | Tier 0: sequencer only | MoldUDP64 |
| `clob-tier1.cmd` | Tier 1: repeater/rewinder | MoldUDP64 |
| `clob-tier2.cmd` | Tier 2: client applications | MoldUDP64 |

---

## How To Create a Command File for a New Service

### Step 1: Define the Service Role

Determine what this node does:
- **Sequencer node?** → needs `BusServer`, `Sequencer`, `MessageStore`
- **Application node?** → needs `BusClient`, one or more application objects
- **Monitoring node?** → needs `BusClient`, `Printer`, maybe `MetricPublisher`
- **Hybrid?** → any combination

### Step 2: Choose the Template

Start from the closest existing file and modify:

| Role | Start From |
|------|-----------|
| Sequencer + apps (dev) | `clob.cmd` or `clob-aeron.cmd` |
| Primary sequencer (prod) | `clob-primary.cmd` |
| Standby sequencer (prod) | `clob-backup.cmd` |
| Client-only application | `clob-tier2.cmd` |
| Repeater/rewinder | `clob-tier1.cmd` |

### Step 3: Write the File

Follow this structure:

```bash
#
# usage: my-service.cmd [args]
# description: <what this node does>
#
# params:
#   $1: <description>
#

# ── Infrastructure ───────────────────────────────────────────
source aeron-network.cmd $1          # or network-local.cmd for MoldUDP64
source -s sysout-log.cmd             # or file-log.cmd for production
source -s telnet.cmd inet:0.0.0.0:7001

# ── Schema ───────────────────────────────────────────────────
create /bus/schema com.core.clob.schema.ClobSchema

# ── Transport ────────────────────────────────────────────────
# For a client-only node:
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema $aeron_dir \
    $event_channel $event_stream $command_channel $command_stream $session_name

# For a sequencer node, also add:
# create /busServer com.core.platform.bus.aeron.AeronBusServer \
#     @/bus/schema $aeron_dir \
#     $event_channel $event_stream $command_channel $command_stream $session_name
# create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
# create seq01a/handlers com.core.clob.applications.sequencer.ClobCommandHandlers @/busServer

# ── Applications ─────────────────────────────────────────────
create /myApp com.mycompany.MyApplication @/bus MY_APP_01
/myApp/configure <params>

# ── Lifecycle ────────────────────────────────────────────────
# seq01a/start            # if sequencer node
/bus/start                 # if client-only node (waits for session from sequencer)
```

### Step 4: Test

```bash
./gradlew uberjar
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -DSHELL_PATH=platform/src/main/resources:clob/src/main/resources:my-module/src/main/resources \
  -jar clob/build/libs/core-1.0-SNAPSHOT.jar com.core.platform.Main \
  -s my-service.cmd /tmp/aeron
```

### Step 5: Verify via Shell

Connect via telnet and inspect:

```
telnet localhost 7001
> ls /
> /bus/status
> /vm/activation/state
> /myApp/status
```

---

## Common Patterns

### Pattern 1: Variable Defaults With Override

```bash
# In network config file:
default event_channel inet:239.100.100.100:10100:lo0

# In top-level file (overrides if set before sourcing):
set event_channel inet:239.200.200.200:20200:eth0
source network-local.cmd    # default won't overwrite
```

### Pattern 2: Conditional Transport Selection

```bash
# clob-dev.cmd — choose transport based on variable
source network-local.cmd
source -s sysout-log.cmd

create /bus/schema com.core.clob.schema.ClobSchema

# MoldUDP64 transport
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel
```

```bash
# clob-aeron-dev.cmd — same apps, different transport
source aeron-network.cmd $1
source -s sysout-log.cmd

create /bus/schema com.core.clob.schema.ClobSchema

# Aeron transport
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema $aeron_dir \
    $event_channel $event_stream $command_channel $command_stream $session_name
```

Applications below the transport layer are identical — only the bus creation differs.

### Pattern 3: Tiered Topology

```
clob-tier0.cmd → Sequencer only (publishes events)
clob-tier1.cmd → Repeater (receives tier0, re-publishes + serves rewinds)
clob-tier2.cmd → Client apps (receives tier1)
```

Each tier uses a different discovery channel so clients find the right rewinder.

### Pattern 4: Primary/Backup With Shared Config

```bash
# clob-common.cmd — shared by primary and backup
source network-local.cmd
create /bus/schema com.core.clob.schema.ClobSchema
create /bus com.core.platform.bus.mold.MoldBusClient \
    client @/bus/schema $event_channel $command_channel $discovery_channel
create ref01a com.core.platform.applications.refdata.ReferenceDataPublisher @/bus REF01 equityDefinition
ref01a/setPath clob/src/main/resources
ref01a/loadFiles
```

```bash
# clob-primary.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001
source clob-common.cmd
# Primary-specific: create sequencer, create session, start
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer ...
create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
/busServer/createSession AA
seq01a/start
```

```bash
# clob-backup.cmd
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7002
source clob-common.cmd
# Backup-specific: passive sequencer, no session creation, no start
create /busServer/store com.core.platform.bus.mold.BufferChannelMessageStore
create /busServer com.core.platform.bus.mold.MoldBusServer ...
create seq01b com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
/bus/start
```

---

## Suggested Improvements

### 1. Standardized Header Format

**Problem:** Headers are inconsistent — some files have usage/description/params, others have nothing.

**Fix:** Enforce a standard header:

```bash
#
# file: my-service.cmd
# usage: my-service.cmd <aeron_dir>
# description: Primary sequencer with Aeron transport and Archive recording
#
# params:
#   $1: Aeron directory path (e.g., /tmp/aeron)
#
# depends: aeron-network.cmd, sysout-log.cmd
# transport: aeron
# role: sequencer-primary
#
```

The `depends`, `transport`, and `role` metadata tags enable tooling to validate and visualize topology.

### 2. Variable Validation

**Problem:** Missing variables cause cryptic `unset variable` errors deep in execution. For example, forgetting to source `network-local.cmd` before using `$event_channel`.

**Fix:** Add a `require` command (or convention) at the top of files that use external variables:

```bash
# Validate required variables are set before proceeding
require event_channel "event_channel must be set — source network-local.cmd or aeron-network.cmd first"
require command_channel "command_channel must be set"
```

Until `require` is implemented, use a convention with `default` + sentinel:

```bash
default event_channel UNSET
# Later, the constructor will fail with a clear error if "UNSET" is passed as an address
```

### 3. Eliminate Duplication With Composable Fragments

**Problem:** `clob.cmd`, `clob-primary.cmd`, `clob-backup.cmd`, `clob-aeron.cmd` all repeat the same application creation blocks (ref data, printer, injector).

**Fix:** Extract shared application wiring into reusable fragments:

```bash
# clob-apps.cmd — shared applications (sourced by all clob command files)
create ref01a com.core.platform.applications.refdata.ReferenceDataPublisher @/bus REF01 equityDefinition
ref01a/setPath clob/src/main/resources
ref01a/start
ref01a/loadFiles

create print01a com.core.clob.applications.printer.ClobPrinter @/bus .

create inject01a com.core.clob.applications.utilities.ClobInjector @/bus INJ01
inject01a/start
```

```bash
# clob-sequencer.cmd — sequencer creation (sourced by primary/standalone)
create /busServer com.core.platform.bus.aeron.AeronBusServer ...
create seq01a com.core.platform.applications.sequencer.Sequencer @/busServer SEQ01
create seq01a/handlers com.core.clob.applications.sequencer.ClobCommandHandlers @/busServer
```

Then top-level files become thin:

```bash
# clob-aeron.cmd (simplified)
source aeron-network.cmd $1
source -s sysout-log.cmd
source -s telnet.cmd inet:0.0.0.0:7001
create /bus/schema com.core.clob.schema.ClobSchema
source clob-aeron-transport.cmd       # bus client + server for Aeron
source clob-sequencer.cmd             # sequencer creation
source clob-apps.cmd                  # shared applications
seq01a/start
```

### 4. Consistent Path Conventions

**Problem:** Some objects use absolute paths (`/bus`, `/busServer`), others use relative (`seq01a`, `ref01a`). Naming conventions are inconsistent (`seq01a` vs `SEQ01`).

**Fix:** Standardize:

| Convention | Rule |
|------------|------|
| Shell path | Always absolute: `/bus`, `/busServer`, `/seq01a`, `/ref01a` |
| Application name | Uppercase: `SEQ01`, `REF01`, `INJ01` (used in `ApplicationDefinition` messages) |
| Instance suffix | Lowercase letter for primary/backup: `a` = primary, `b` = backup |
| Telnet port | Primary: 7001, Backup: 7002, Tier2: 7003+ |

### 5. Transport Abstraction Layer

**Problem:** Switching between MoldUDP64 and Aeron requires creating different command files with largely duplicated content. The only difference is the bus creation lines.

**Fix:** Create a single transport-selection file:

```bash
# transport.cmd — select transport based on $transport variable
# Usage: set transport aeron|mold, then source transport.cmd

# ── Aeron transport ──
# if $transport == aeron (pseudo — use separate files for now):
create /bus com.core.platform.bus.aeron.AeronBusClient \
    client @/bus/schema $aeron_dir \
    $event_channel $event_stream $command_channel $command_stream $session_name
create /busServer com.core.platform.bus.aeron.AeronBusServer \
    @/bus/schema $aeron_dir \
    $event_channel $event_stream $command_channel $command_stream $session_name

# ── MoldUDP64 transport ──
# create /bus com.core.platform.bus.mold.MoldBusClient ...
# create /busServer com.core.platform.bus.mold.MoldBusServer ...
```

Since the shell has no `if` statement, use separate files (`transport-aeron.cmd`, `transport-mold.cmd`) sourced by the top-level file:

```bash
# clob-dev.cmd
source transport-mold.cmd       # or transport-aeron.cmd
source clob-sequencer.cmd
source clob-apps.cmd
```

### 6. Startup Order Documentation

**Problem:** The order of `create` and `start` commands matters, but it is not documented. Calling `seq01a/start` before `busServer/createSession` will fail silently because the activator dependency is not met.

**Fix:** Add comments documenting the required ordering, or better, define a `startup.cmd` fragment:

```bash
# startup-sequencer.cmd — correct startup sequence for a sequencer node
# Must be sourced AFTER transport and sequencer creation
/busServer/createSession $session_suffix    # creates MoldSession, enables event publisher
seq01a/start                                 # activates sequencer (depends on busServer being ready)
```

### 7. Environment-Specific Override Files

**Problem:** Addresses, ports, and paths are hardcoded in network files. Switching between dev, staging, and production requires editing files.

**Fix:** Use a layered configuration pattern:

```bash
# env-dev.cmd
set event_channel inet:239.100.100.100:10100:lo0
set telnet_port 7001
set log_mode sysout

# env-staging.cmd
set event_channel inet:239.100.100.100:10100:eth0
set telnet_port 7001
set log_mode file
set log_file /var/log/core/vm.log

# env-prod.cmd
set event_channel inet:10.1.2.3:10100:bond0
set telnet_port 7001
set log_mode file
set log_file /var/log/core/vm.log
```

Top-level file:

```bash
# clob-service.cmd
source env-$environment.cmd            # requires: -Denvironment=dev|staging|prod
source -s ${log_mode}-log.cmd $log_file
source -s telnet.cmd inet:0.0.0.0:$telnet_port
# ... rest of service
```

---

## Quick Reference Card

```
┌────────────────────────────────────────────────────────────┐
│ COMMAND FILE SYNTAX                                        │
├────────────────────────────────────────────────────────────┤
│ # comment                                                  │
│ set <var> <value>          Set variable                    │
│ default <var> <value>      Set if not already defined      │
│ source <file> [args]       Execute file (vars merge)       │
│ source -s <file> [args]    Execute in subshell (isolated)  │
│ create <path> <class> [args]  Create object at path        │
│ <path>/<command> [args]    Call @Command method            │
│ $var                       Variable reference              │
│ $1, $2, ...                Positional arguments            │
│ @path                      Object reference                │
│ \                          Line continuation               │
│ "quoted string"            String with spaces              │
├────────────────────────────────────────────────────────────┤
│ LAUNCH                                                     │
│ java -DSHELL_PATH=dir1:dir2 -jar core.jar Main -s file.cmd│
├────────────────────────────────────────────────────────────┤
│ IMPLIED PARAMETERS (auto-injected, never in .cmd files)    │
│ Time, Scheduler, Selector, LogFactory, MetricFactory,      │
│ ActivatorFactory, Shell, EventLoop                         │
└────────────────────────────────────────────────────────────┘
```
