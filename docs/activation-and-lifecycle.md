# Activation and Lifecycle

The system uses an explicit activation graph to coordinate readiness, dependencies, and active/passive transitions. This is central to go-active/go-passive behavior and to consistent startup/teardown ordering.

Key classes:

- `com.core.platform.activation.Activator`
- `com.core.platform.activation.ActivatorFactory`
- `com.core.platform.activation.Activatable`

## Activator Graph

An `Activator` wraps an object and optional dependency activators (children). The object becomes active when:

- it is marked `ready`,
- it is `started`,
- and all dependency activators are active.

Activators are created through `ActivatorFactory.createActivator(name, object, dependencies...)` which links parent/child relationships and registers metrics for active/started/ready state.

## State Machine

Each activator tracks:

- `started` vs `stopped` (explicit lifecycle control)
- `ready` vs `notReady` (readiness gate)
- `active` (derived state when started + ready + children active)

Transitions:

- `start()` and `stop()` cascade to children (unless `preventParentStop` is enabled).
- `ready()` and `notReady()` set readiness state and propagate updates to parents.
- If active -> inactive, `deactivate()` is invoked on the associated object (if it implements `Activatable`).
- If inactive -> active, `activate()` is invoked, and the object typically calls `ready()` when it is fully initialized.

## Preventing Stop Propagation

Some components (notably event receivers) call `activator.preventParentStop()` to stay running even if parents stop. This is used where a component should continue to gather state even if upstream dependencies are inactive.

## Go-Active / Go-Passive

- **Sequencer active**: command listener is used, event stream is published, heartbeat is emitted.
- **Sequencer passive**: listens to event stream, updates per-app sequence numbers, does not accept commands.

Bus server activation controls whether timestamps are written and whether send operations are active.

Examples:

- `MoldBusServer` is active when its session, command receiver, rewinder, and event publisher are active.
- `MoldEventReceiver` can become `ready` only after it is caught up (or after first rewind completes).

## Activation in Command Files

Command files typically start the graph by calling `start` on the top-level sequencer or bus component. For example:

- `seq01a/start` in `clob.cmd` triggers sequencer startup and dependencies.
- `busServer/createSession AA` creates session name, which allows event publisher/rewinder to become ready.

Because the shell registers each object with `@Directory`, it is possible to call `status` or `state` commands on any node to inspect its activator status.

