package com.core.platform.applications.cluster;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.time.Time;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maintains an in-memory registry of nodes in the cluster and provides shell commands for operators to query cluster
 * status.
 *
 * <p>Nodes are registered via the {@code register} command, typically from a command file at startup.
 * Liveness is tracked through the {@code heartbeat} command, and nodes whose last heartbeat exceeds the configurable
 * stale threshold are flagged as stale.
 */
public class ClusterRegistry implements Encodable {

    private final Log log;
    private final Time time;
    private final Map<String, NodeInfo> nodes;

    @Property(write = true)
    private long staleThresholdMs = 5000;

    /**
     * Creates a {@code ClusterRegistry} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param time the source of time
     */
    public ClusterRegistry(LogFactory logFactory, Time time) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        this.time = Objects.requireNonNull(time, "time is null");

        log = logFactory.create(getClass());
        nodes = new HashMap<>();
    }

    /**
     * Registers a node in the cluster registry.
     *
     * @param nodeId the unique identifier of the node
     * @param role the role of the node (SEQUENCER, FOLLOWER, SUBSCRIBER, STANDBY)
     * @param endpoint the network endpoint of the node
     */
    @Command(path = "register")
    public void register(String nodeId, String role, String endpoint) {
        var now = time.nanos();
        var existing = nodes.get(nodeId);
        if (existing != null) {
            existing.role = role;
            existing.endpoint = endpoint;
            existing.lastHeartbeatNanos = now;
            log.info().append("re-registered node: nodeId=").append(nodeId)
                    .append(", role=").append(role)
                    .append(", endpoint=").append(endpoint)
                    .commit();
        } else {
            var info = new NodeInfo();
            info.nodeId = nodeId;
            info.role = role;
            info.endpoint = endpoint;
            info.registrationTimeNanos = now;
            info.lastHeartbeatNanos = now;
            nodes.put(nodeId, info);
            log.info().append("registered node: nodeId=").append(nodeId)
                    .append(", role=").append(role)
                    .append(", endpoint=").append(endpoint)
                    .commit();
        }
    }

    /**
     * Records a heartbeat for the specified node.
     *
     * @param nodeId the unique identifier of the node
     */
    @Command(path = "heartbeat")
    public void heartbeat(String nodeId) {
        var info = nodes.get(nodeId);
        if (info == null) {
            log.warn().append("heartbeat for unknown node: nodeId=").append(nodeId).commit();
            return;
        }
        info.lastHeartbeatNanos = time.nanos();
    }

    /**
     * Returns a list of all registered nodes with their status.
     *
     * @param encoder the encoder
     */
    @Command(path = "nodes", readOnly = true)
    public void nodes(ObjectEncoder encoder) {
        encoder.openList();
        for (var info : nodes.values()) {
            encodeNode(encoder, info);
        }
        encoder.closeList();
    }

    /**
     * Returns the nodeId of the node registered with role SEQUENCER that has the most recent heartbeat.
     *
     * @param encoder the encoder
     */
    @Command(path = "leader", readOnly = true)
    public void leader(ObjectEncoder encoder) {
        NodeInfo leader = null;
        for (var info : nodes.values()) {
            if ("SEQUENCER".equals(info.role)
                    && (leader == null || info.lastHeartbeatNanos > leader.lastHeartbeatNanos)) {
                leader = info;
            }
        }
        if (leader != null) {
            encoder.openMap()
                    .string("nodeId").string(leader.nodeId)
                    .string("endpoint").string(leader.endpoint)
                    .string("stale").bool(isStale(leader))
                    .closeMap();
        } else {
            encoder.string("none");
        }
    }

    /**
     * Returns nodes whose last heartbeat is older than the stale threshold.
     *
     * @param encoder the encoder
     */
    @Command(path = "staleNodes", readOnly = true)
    public void staleNodes(ObjectEncoder encoder) {
        encoder.openList();
        for (var info : nodes.values()) {
            if (isStale(info)) {
                encodeNode(encoder, info);
            }
        }
        encoder.closeList();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        var staleCount = 0;
        for (var info : nodes.values()) {
            if (isStale(info)) {
                staleCount++;
            }
        }

        encoder.openMap()
                .string("totalNodes").number(nodes.size())
                .string("staleNodes").number(staleCount)
                .string("staleThresholdMs").number(staleThresholdMs)
                .string("nodes").openList();
        for (var info : nodes.values()) {
            encodeNode(encoder, info);
        }
        encoder.closeList()
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    private void encodeNode(ObjectEncoder encoder, NodeInfo info) {
        encoder.openMap()
                .string("nodeId").string(info.nodeId)
                .string("role").string(info.role)
                .string("endpoint").string(info.endpoint)
                .string("lastHeartbeatNanos").number(info.lastHeartbeatNanos)
                .string("stale").bool(isStale(info))
                .closeMap();
    }

    private boolean isStale(NodeInfo info) {
        return (time.nanos() - info.lastHeartbeatNanos) > staleThresholdMs * 1_000_000L;
    }

    private static class NodeInfo {

        String nodeId;
        String role;
        String endpoint;
        long lastHeartbeatNanos;
        long registrationTimeNanos;
    }
}
