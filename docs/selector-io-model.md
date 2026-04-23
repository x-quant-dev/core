# Selector and I/O Model

The infrastructure layer provides a selector abstraction around Java NIO, enabling uniform, low-allocation handling of UDP and TCP channels. This is central to the MoldUDP64 bus, command channels, and metrics publishing.

## Selector Abstraction

`com.core.infrastructure.io.Selector` defines:

- creation of datagram, socket, server socket, and pipe channels
- blocking and non-blocking select operations
- lifecycle management

`com.core.infrastructure.io.NioSelector` implements this using the JDK selector. It wires `SelectionKey` readiness to channel callbacks (`onRead`, `onWrite`, `onConnect`, `onAccept`).

## Channel Implementations

Channels wrap JDK NIO channels and expose a consistent API with callbacks:

- `DatagramChannelImpl`
- `SocketChannelImpl`
- `ServerSocketChannelImpl`
- `PipeImpl`

Each maintains read/write/connect/accept listeners and registers interest ops when configured for non-blocking operation.

## Event Loop Integration

`com.core.infrastructure.EventLoop` orchestrates:

- scheduler firing (`Scheduler.fire()`)
- selector polling (`Selector.selectNow()` or `select(timeout)`)

The event loop supports busy polling and event-only modes. This is a key piece when planning Aeron integration, since Aeron uses its own poller and idle strategies.

## Integrating Non-NIO Sources (Aeron, Custom Pollers)

The `Selector` interface exposes `addPoller(Runnable)`, which lets any non-NIO source participate in the same event loop iteration alongside NIO channels:

```java
// Register an Aeron subscription as a poller — no separate class needed
selector.addPoller(() -> eventSubscription.poll(fragmentHandler, FRAGMENT_LIMIT));
```

`NioSelector` calls every registered poller immediately after each NIO select, in registration order. This means a developer can freely combine:

- `Pipe`, `DatagramChannel`, `SocketChannel`, `ServerSocketChannel` (created via the `Selector` factory methods)
- MoldUDP64 channels (which use the datagram channel factory method)
- Aeron `Subscription`s (registered via `addPoller`)
- Any other custom polling source

All sources share one single-threaded event loop — no wrapper class, no separate polling thread, no idle strategy at the selector level. The `EventLoop` drives the whole cycle:

```
EventLoop iteration:
  1. time.updateTime()
  2. scheduler.fire()
  3. selector.selectNow()  (or select(timeout))
       ├─ NIO readiness callbacks  (DatagramChannel, SocketChannel, Pipe, ...)
       └─ addPoller() callbacks    (Aeron subscriptions, custom pollers, ...)
```

See `design-history/platform-architecture-uplift.md` for the full design history.

