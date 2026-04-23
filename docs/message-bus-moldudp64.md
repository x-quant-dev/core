# MoldUDP64 Message Bus

The platform implements a distributed message bus based on the MoldUDP64 protocol, providing a single ordered event stream with gap detection and replay. The bus has two primary roles:

- **Bus Server**: accepts commands and publishes ordered events.
- **Bus Client**: receives ordered events and provides command publishers for applications.

Primary components live in `platform/src/main/java/com/core/platform/bus/mold/`.

## Bus Components Overview

- `MoldBusServer`: main server implementation; combines command receiver, event publisher, session, and rewinder.
- `MoldBusClient`: client implementation; receives events, dispatches messages, creates per-application command publishers.
- `MoldEventPublisher`: constructs MoldUDP64 event packets and publishes to multicast.
- `MoldEventReceiver`: consumes multicast event packets, detects gaps, issues replay requests.
- `MoldCommandReceiver`: receives command packets on command channel.
- `MoldCommandPublisher`: per-application sender for commands to the command channel.
- `MoldRewinder`: services replay requests using the message store.
- `MessageStore` + implementations: persistent or in-memory storage for replay.

## Sessions and Sequence Numbers

`MoldSession` encapsulates session identity and sequencing:

- Session name: 10 bytes `yyyyMMddXX` (UTC date + suffix).
- `nextSessionSeqNum`: next event sequence number (session-wide).
- Session is opened by server or inferred by clients based on first packet.

The session sequence number is global ordering for events. Each event packet carries:

- session id (10 bytes)
- first sequence number (8 bytes)
- number of messages in packet (2 bytes)
- message frames: `[length][payload]...`

## Event Publishing (Server Side)

`MoldEventPublisher` is responsible for:

- Acquiring a buffer from the message store.
- Building packet headers and message frames.
- Committing message lengths into the store.
- Updating session sequence number.
- Sending datagram to the event multicast channel.

Key operations:

1. `acquire()` returns a buffer for the next message.
2. `commit(length)` writes the length and advances packet position.
3. `send()` finalizes header, commits to store, advances session seq number, sends packet.

This allows `BusServer.commit()` to be cheap and `send()` to flush a packet.

## Event Receiving (Client Side)

`MoldEventReceiver` processes event packets and provides in-order delivery:

- Maintains `nextSeqNum` (next expected event sequence).
- On each packet, validates session, parses header, and checks each message.
- Delivers messages only if `msgSeqNum == nextSeqNum`.
- Drops messages already processed (`msgSeqNum < nextSeqNum`).
- Detects gaps (`nextSeqNum < session.nextSeqNum`) and triggers replay.

Gap handling:

- If behind, it first discovers rewinders via the discovery channel.
- It then sends rewind requests to a rewinder on a unicast socket.
- Rewind replies are processed in the same way as event packets.

Activation: event receiver marks itself ready when it has caught up, or after first full rewind.

## Rewind and Discovery

`MoldRewinder` listens on discovery and rewind sockets:

- **Discovery channel**: responds to single-byte `D` requests with its rewind socket address.
- **Rewind socket**: accepts unicast replay requests containing session + seq num + count.
- It reads messages from the `MessageStore` and replies with a standard MoldUDP64 packet.

The `MoldEventReceiver` maintains a list of discovered rewinders and cycles through them on timeouts.

## Command Publishing (Client Side)

`MoldCommandPublisher` is per-application and is tightly coupled to the ordered event stream:

- Maintains outbound app seq num (`outSeqNum`) and confirmed seq num (`inSeqNum`).
- Buffers outbound packets until commands are confirmed via the event stream.
- Uses dispatcher callbacks to observe events:
  - `onBeforeMessage` updates ack state when matching application messages are seen.
  - If app definition arrives and matches publisher, app id is bound and queued packets are updated.

This ensures application command ordering is consistent with the stream.

## Command Receiving (Server Side)

`MoldCommandReceiver` listens on the command channel (multicast or unicast):

- Validates session header.
- Iterates over message frames, forwarding each to the sequencer's command listener.
- On errors, logs and stops the activator.

## Message Storage for Replay

`MessageStore` is the abstraction used by publishers and rewinders. Implementations:

- `ChannelMessageStore`: base implementation that stores messages and index entries in files or buffers.
- `FileChannelMessageStore`: uses file-backed channels for persistence (`.events.dat` + `.index.dat`).
- `BufferChannelMessageStore`: in-memory store for demos/tests.

The index file maps sequence numbers to offsets into the events file, enabling O(1) random access for rewinds.

## TCP Bus Variant

`TcpBusServer` uses MoldUDP64 for command ingress but publishes events to TCP clients:

- `TcpMessagePublisher`: accepts TCP clients, handles heartbeats and rewinds per client.
- `TcpMessageReceiver`: connects to TCP server and reads ordered messages, using a heartbeat frame with session name + next seq num.

This is used for environments where multicast is not possible but ordering/replay semantics are required.

