package com.core.platform.applications.sequencer;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

/**
 * A TCP-based {@link Consensus} client that communicates with a remote {@link TcpConsensusServer}
 * for cross-JVM split-brain fencing.
 *
 * <p>This client uses blocking I/O for request/response. The consensus operations
 * (acquire, renew, release) are called from the event-loop thread during activation
 * and heartbeat, not on the critical command processing path.
 *
 * <p>If the connection fails, the client returns conservative results:
 * acquire and renew return false, and isLeaseExpired returns true.
 * This fail-safe behavior prevents a sequencer from going active without
 * confirmed fencing.
 */
public class TcpConsensusClient implements Consensus, Encodable {

    private static final int NODE_ID_LENGTH = 32;
    private static final int PRIORITY_LENGTH = 4;
    private static final int REQUEST_LENGTH = 1 + NODE_ID_LENGTH;
    private static final int REGISTER_REQUEST_LENGTH = 1 + NODE_ID_LENGTH + PRIORITY_LENGTH;
    private static final int RESPONSE_LENGTH = 1 + 4 + NODE_ID_LENGTH;
    private static final int DEFAULT_TIMEOUT_MS = 2000;

    private static final byte OP_ACQUIRE = 1;
    private static final byte OP_RENEW = 2;
    private static final byte OP_RELEASE = 3;
    private static final byte OP_STATUS = 4;
    private static final byte OP_REGISTER = 5;
    private static final byte OP_DEREGISTER = 6;

    private static final byte RESULT_OK = 0;

    private final Log log;
    private final byte[] requestBytes;
    private final byte[] registerRequestBytes;
    private final byte[] responseBytes;
    @SuppressWarnings("PMD.UnusedPrivateField")
    private ElectionListener electionListener;

    @Property
    private String serverAddress;
    @Property(write = true)
    private int timeoutMs;
    @Property
    private int lastEpoch;
    @Property
    private String lastLeaseHolder;
    @Property
    private long requestsSent;
    @Property
    private long requestsFailed;
    @Property
    private boolean connected;

    private Socket socket;
    private OutputStream out;
    private InputStream in;

    /**
     * Creates a {@code TcpConsensusClient} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     */
    public TcpConsensusClient(LogFactory logFactory, MetricFactory metricFactory) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");

        log = logFactory.create(getClass());
        timeoutMs = DEFAULT_TIMEOUT_MS;
        requestBytes = new byte[REQUEST_LENGTH];
        registerRequestBytes = new byte[REGISTER_REQUEST_LENGTH];
        responseBytes = new byte[RESPONSE_LENGTH];

        metricFactory.registerGaugeMetric("TcpConsensusClient_Epoch", () -> (long) lastEpoch);
        metricFactory.registerGaugeMetric("TcpConsensusClient_RequestsSent", () -> requestsSent);
        metricFactory.registerGaugeMetric("TcpConsensusClient_RequestsFailed", () -> requestsFailed);
        metricFactory.registerSwitchMetric("TcpConsensusClient_Connected", () -> connected);
    }

    /**
     * Connects to the consensus server at the specified address.
     *
     * @param address the server address in {@code host:port} format
     */
    @Command
    public void connect(String address) {
        this.serverAddress = address;
        doConnect();
    }

    private void doConnect() {
        try {
            close();
            socket = new Socket();
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            var parts = serverAddress.split(":");
            var host = parts[0];
            var port = Integer.parseInt(parts[1]);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            out = socket.getOutputStream();
            in = socket.getInputStream();
            connected = true;
            log.info().append("connected to consensus server: ").append(serverAddress).commit();
        } catch (IOException e) {
            connected = false;
            log.warn().append("failed to connect to consensus server: ").append(serverAddress)
                    .append(", error=").append(e).commit();
        }
    }

    /**
     * Closes the connection to the consensus server.
     */
    @Command
    public void close() {
        connected = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // intentionally ignored
            }
            socket = null;
            out = null;
            in = null;
        }
    }

    @Override
    public boolean tryAcquire(String nodeId) {
        var response = sendRequest(OP_ACQUIRE, nodeId);
        return response != null && response[0] == RESULT_OK;
    }

    @Override
    public boolean tryRenew(String nodeId) {
        var response = sendRequest(OP_RENEW, nodeId);
        return response != null && response[0] == RESULT_OK;
    }

    @Override
    public void tryRelease(String nodeId) {
        sendRequest(OP_RELEASE, nodeId);
    }

    @Override
    public String getLeaseHolder() {
        return lastLeaseHolder;
    }

    @Override
    public int getEpoch() {
        return lastEpoch;
    }

    @Override
    public boolean isLeaseExpired() {
        var response = sendRequest(OP_STATUS, "");
        if (response == null) {
            return true;
        }
        return lastLeaseHolder == null;
    }

    @Override
    public boolean registerCandidate(String nodeId, int priority) {
        var response = sendRegisterRequest(nodeId, priority);
        return response != null && response[0] == RESULT_OK;
    }

    @Override
    public void deregisterCandidate(String nodeId) {
        sendRequest(OP_DEREGISTER, nodeId);
    }

    @Override
    public void setElectionListener(ElectionListener listener) {
        this.electionListener = listener;
    }

    @Override
    public int getCandidateCount() {
        // not tracked on client side; return -1 to indicate unknown
        return -1;
    }

    private byte[] sendRegisterRequest(String nodeId, int priority) {
        if (!connected) {
            if (serverAddress != null) {
                doConnect();
            }
            if (!connected) {
                requestsFailed++;
                return null;
            }
        }

        try {
            registerRequestBytes[0] = OP_REGISTER;
            for (var i = 0; i < NODE_ID_LENGTH; i++) {
                registerRequestBytes[1 + i] = i < nodeId.length() ? (byte) nodeId.charAt(i) : 0;
            }
            // encode priority as big-endian int
            registerRequestBytes[1 + NODE_ID_LENGTH] = (byte) (priority >> 24);
            registerRequestBytes[2 + NODE_ID_LENGTH] = (byte) (priority >> 16);
            registerRequestBytes[3 + NODE_ID_LENGTH] = (byte) (priority >> 8);
            registerRequestBytes[4 + NODE_ID_LENGTH] = (byte) priority;

            out.write(registerRequestBytes);
            out.flush();
            requestsSent++;

            // read full response
            var totalRead = 0;
            while (totalRead < RESPONSE_LENGTH) {
                var read = in.read(responseBytes, totalRead, RESPONSE_LENGTH - totalRead);
                if (read < 0) {
                    connected = false;
                    requestsFailed++;
                    return null;
                }
                totalRead += read;
            }

            decodeResponse();
            return responseBytes;
        } catch (IOException e) {
            connected = false;
            requestsFailed++;
            log.warn().append("consensus register request failed: nodeId=").append(nodeId)
                    .append(", error=").append(e).commit();
            return null;
        }
    }

    private byte[] sendRequest(byte opcode, String nodeId) {
        if (!connected) {
            if (serverAddress != null) {
                doConnect();
            }
            if (!connected) {
                requestsFailed++;
                return null;
            }
        }

        try {
            // encode request
            requestBytes[0] = opcode;
            for (var i = 0; i < NODE_ID_LENGTH; i++) {
                requestBytes[1 + i] = i < nodeId.length() ? (byte) nodeId.charAt(i) : 0;
            }

            out.write(requestBytes);
            out.flush();
            requestsSent++;

            // read full response
            var totalRead = 0;
            while (totalRead < RESPONSE_LENGTH) {
                var read = in.read(responseBytes, totalRead, RESPONSE_LENGTH - totalRead);
                if (read < 0) {
                    connected = false;
                    requestsFailed++;
                    return null;
                }
                totalRead += read;
            }

            decodeResponse();
            return responseBytes;
        } catch (IOException e) {
            connected = false;
            requestsFailed++;
            log.warn().append("consensus request failed: opcode=").append(opcode)
                    .append(", error=").append(e).commit();
            return null;
        }
    }

    private void decodeResponse() {
        // decode epoch (big-endian)
        lastEpoch = ((responseBytes[1] & 0xFF) << 24)
                | ((responseBytes[2] & 0xFF) << 16)
                | ((responseBytes[3] & 0xFF) << 8)
                | (responseBytes[4] & 0xFF);

        // decode lease holder
        var sb = new StringBuilder();
        for (var i = 0; i < NODE_ID_LENGTH; i++) {
            var b = responseBytes[5 + i];
            if (b == 0) {
                break;
            }
            sb.append((char) b);
        }
        lastLeaseHolder = sb.length() > 0 ? sb.toString() : null;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("serverAddress").string(serverAddress != null ? serverAddress : "not configured")
                .string("connected").bool(connected)
                .string("lastEpoch").number(lastEpoch)
                .string("lastLeaseHolder").string(lastLeaseHolder != null ? lastLeaseHolder : "none")
                .string("requestsSent").number(requestsSent)
                .string("requestsFailed").number(requestsFailed)
                .string("timeoutMs").number(timeoutMs)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
