package com.core.platform.applications.sequencer;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.io.Selector;
import com.core.infrastructure.io.ServerSocketChannel;
import com.core.infrastructure.io.SocketChannel;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.io.IOException;
import java.util.Objects;

/**
 * Exposes a {@link ConsensusModule} over TCP for cross-JVM fencing.
 *
 * <p>This server runs on an independent failure domain and provides lease-based
 * fencing to prevent split-brain across separate sequencer JVM instances.
 *
 * <p>Protocol: fixed-size binary messages, no allocation on the hot path.
 * <ul>
 *     <li>Request: 1 byte opcode + 32 bytes nodeId (null-padded ASCII)
 *     <li>Response: 1 byte result + 4 bytes epoch + 32 bytes leaseHolder (null-padded ASCII)
 * </ul>
 */
public class TcpConsensusServer implements Encodable {

    static final int NODE_ID_LENGTH = 32;
    static final int PRIORITY_LENGTH = 4;
    static final int REQUEST_LENGTH = 1 + NODE_ID_LENGTH;  // 33 bytes
    static final int REGISTER_REQUEST_LENGTH = 1 + NODE_ID_LENGTH + PRIORITY_LENGTH;  // 37 bytes
    static final int RESPONSE_LENGTH = 1 + 4 + NODE_ID_LENGTH;  // 37 bytes

    static final byte OP_ACQUIRE = 1;
    static final byte OP_RENEW = 2;
    static final byte OP_RELEASE = 3;
    static final byte OP_STATUS = 4;
    static final byte OP_REGISTER = 5;
    static final byte OP_DEREGISTER = 6;

    static final byte RESULT_OK = 0;
    static final byte RESULT_DENIED = 1;
    static final byte RESULT_EXPIRED = 2;

    private final Log log;
    private final ConsensusModule consensusModule;
    private final Selector selector;
    private final MutableDirectBuffer readBuffer;
    private final MutableDirectBuffer writeBuffer;

    private ServerSocketChannel serverChannel;

    @Property
    private long requestsReceived;
    @Property
    private long clientsAccepted;
    @Property
    private String bindAddress;

    /**
     * Creates a {@code TcpConsensusServer} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param selector the I/O selector
     * @param consensusModule the consensus module
     */
    public TcpConsensusServer(
            LogFactory logFactory,
            MetricFactory metricFactory,
            Selector selector,
            ConsensusModule consensusModule) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.selector = Objects.requireNonNull(selector, "selector is null");
        this.consensusModule = Objects.requireNonNull(consensusModule, "consensusModule is null");

        log = logFactory.create(getClass());
        readBuffer = BufferUtils.allocate(REGISTER_REQUEST_LENGTH);
        writeBuffer = BufferUtils.allocate(RESPONSE_LENGTH);

        metricFactory.registerGaugeMetric("TcpConsensus_RequestsReceived", () -> requestsReceived);
        metricFactory.registerGaugeMetric("TcpConsensus_ClientsAccepted", () -> clientsAccepted);
    }

    /**
     * Binds the server to the specified address.
     *
     * @param address the address to bind to
     * @throws IOException if binding fails
     */
    @Command
    public void bind(String address) throws IOException {
        if (serverChannel != null) {
            log.warn().append("already bound to: ").append(bindAddress).commit();
            return;
        }
        bindAddress = address;
        serverChannel = selector.createServerSocketChannel();
        serverChannel.configureBlocking(false);
        serverChannel.bind(address);
        serverChannel.setAcceptListener(this::onAccept);
        log.info().append("consensus server bound to: ").append(address).commit();
    }

    private void onAccept() {
        try {
            var clientChannel = serverChannel.accept();
            if (clientChannel != null) {
                clientsAccepted++;
                clientChannel.configureBlocking(false);
                clientChannel.setReadListener(() -> onClientRead(clientChannel));
                log.info().append("consensus client accepted: ").append(clientChannel.getRemoteAddress()).commit();
            }
        } catch (IOException e) {
            log.warn().append("error accepting consensus client: ").append(e).commit();
        }
    }

    private void onClientRead(SocketChannel clientChannel) {
        try {
            // peek at the opcode first, then read the full request
            var bytesRead = clientChannel.read(readBuffer, 0, 1);
            if (bytesRead < 0) {
                clientChannel.close();
                return;
            }
            if (bytesRead < 1) {
                return;
            }

            var opcode = readBuffer.getByte(0);
            var expectedLen = opcode == OP_REGISTER
                    ? REGISTER_REQUEST_LENGTH : REQUEST_LENGTH;

            // read remaining bytes
            var remaining = expectedLen - 1;
            var read = clientChannel.read(readBuffer, 1, remaining);
            if (read < 0) {
                clientChannel.close();
                return;
            }
            if (read < remaining) {
                return;  // partial read, wait for more
            }

            requestsReceived++;
            var nodeId = readNodeId(readBuffer, 1);

            processRequest(opcode, nodeId, clientChannel);
        } catch (IOException e) {
            log.warn().append("error reading from consensus client: ").append(e).commit();
            try {
                clientChannel.close();
            } catch (IOException ignored) {
                // intentionally empty
            }
        }
    }

    private void processRequest(byte opcode, String nodeId, SocketChannel clientChannel) throws IOException {
        byte result;
        switch (opcode) {
            case OP_ACQUIRE -> {
                result = consensusModule.tryAcquire(nodeId) ? RESULT_OK : RESULT_DENIED;
            }
            case OP_RENEW -> {
                var renewed = consensusModule.tryRenew(nodeId);
                var expired = consensusModule.isLeaseExpired();
                result = renewed ? RESULT_OK : expired ? RESULT_EXPIRED : RESULT_DENIED;
            }
            case OP_RELEASE -> {
                consensusModule.tryRelease(nodeId);
                result = RESULT_OK;
            }
            case OP_STATUS -> {
                result = RESULT_OK;
            }
            case OP_REGISTER -> {
                var priority = readBuffer.getInt(1 + NODE_ID_LENGTH);
                result = consensusModule.registerCandidate(nodeId, priority)
                        ? RESULT_OK : RESULT_DENIED;
            }
            case OP_DEREGISTER -> {
                consensusModule.deregisterCandidate(nodeId);
                result = RESULT_OK;
            }
            default -> {
                log.warn().append("unknown opcode: ").append(opcode).commit();
                return;
            }
        }

        writeResponse(result, clientChannel);
    }

    private void writeResponse(byte result, SocketChannel clientChannel) throws IOException {
        writeBuffer.putByte(0, result);
        writeBuffer.putInt(1, consensusModule.getEpoch());

        // write lease holder
        for (var i = 0; i < NODE_ID_LENGTH; i++) {
            writeBuffer.putByte(5 + i, (byte) 0);
        }
        var holder = consensusModule.getLeaseHolder();
        if (holder != null) {
            var holderLen = Math.min(holder.length(), NODE_ID_LENGTH);
            for (var i = 0; i < holderLen; i++) {
                writeBuffer.putByte(5 + i, (byte) holder.charAt(i));
            }
        }

        clientChannel.write(writeBuffer, 0, RESPONSE_LENGTH);
    }

    private String readNodeId(DirectBuffer buffer, int offset) {
        var sb = new StringBuilder(NODE_ID_LENGTH);
        for (var i = 0; i < NODE_ID_LENGTH; i++) {
            var b = buffer.getByte(offset + i);
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        return sb.toString();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("bindAddress").string(bindAddress != null ? bindAddress : "unbound")
                .string("requestsReceived").number(requestsReceived)
                .string("clientsAccepted").number(clientsAccepted)
                .string("consensusEpoch").number(consensusModule.getEpoch())
                .string("consensusLeaseHolder").string(
                        consensusModule.getLeaseHolder() != null ? consensusModule.getLeaseHolder() : "none")
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
