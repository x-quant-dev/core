package com.core.platform.applications.health;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.LogFactory;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.BusServer;
import com.core.platform.schema.sbe.SbeDispatcher;

import java.util.Objects;

/**
 * Exposes liveness, readiness, and leader health checks via the command shell.
 *
 * <p>When used with the {@code HttpShell}, the following endpoints are available:
 * <ul>
 *     <li>GET /health/live - returns OK if the event loop is running
 *     <li>GET /health/ready - returns OK if all activators are active
 *     <li>GET /health/leader - returns OK if the bus server is active (leader)
 *     <li>GET /health/schema - returns schema compatibility status (SBE only)
 *     <li>GET /health/status - returns a combined status map
 * </ul>
 */
public class HealthEndpoint implements Encodable {

    private final ActivatorFactory activatorFactory;

    @Property
    private BusServer<?, ?> busServer;

    /**
     * Creates a {@code HealthEndpoint} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param activatorFactory the activator factory to inspect activation state
     */
    public HealthEndpoint(LogFactory logFactory, ActivatorFactory activatorFactory) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        this.activatorFactory = Objects.requireNonNull(activatorFactory, "activatorFactory is null");
    }

    /**
     * Creates a {@code HealthEndpoint} with the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param activatorFactory the activator factory to inspect activation state
     * @param busServer the bus server to check leader status
     */
    public HealthEndpoint(LogFactory logFactory, ActivatorFactory activatorFactory, BusServer<?, ?> busServer) {
        this(logFactory, activatorFactory);
        this.busServer = Objects.requireNonNull(busServer, "busServer is null");
    }

    /**
     * Returns liveness status.
     *
     * @param encoder the output encoder
     */
    @Command(path = "live", readOnly = true)
    public void live(ObjectEncoder encoder) {
        encoder.openMap()
                .string("status").string("OK")
                .closeMap();
    }

    /**
     * Returns readiness status.
     *
     * @param encoder the output encoder
     */
    @Command(path = "ready", readOnly = true)
    public void ready(ObjectEncoder encoder) {
        var allReady = true;
        for (var activator : activatorFactory.getActivators()) {
            if (!activator.isActive()) {
                allReady = false;
                break;
            }
        }

        encoder.openMap()
                .string("status").string(allReady ? "OK" : "NOT_READY");
        if (!allReady) {
            encoder.string("notReady").openList();
            for (var activator : activatorFactory.getActivators()) {
                if (!activator.isActive()) {
                    encoder.openMap()
                            .string("name").string(activator.getName())
                            .string("ready").bool(activator.isReady())
                            .string("started").bool(activator.isStarted())
                            .string("reason").string(
                                    activator.getNotReadyReason() != null
                                            ? activator.getNotReadyReason() : "")
                            .closeMap();
                }
            }
            encoder.closeList();
        }
        encoder.closeMap();
    }

    /**
     * Returns leader status.
     *
     * @param encoder the output encoder
     */
    @Command(path = "leader", readOnly = true)
    public void leader(ObjectEncoder encoder) {
        String status;
        if (busServer == null) {
            status = "UNKNOWN";
        } else if (busServer.isActive()) {
            status = "OK";
        } else {
            status = "STANDBY";
        }

        encoder.openMap()
                .string("status").string(status)
                .closeMap();
    }

    /**
     * Returns schema compatibility status.
     *
     * @param encoder the output encoder
     */
    @Command(path = "schema", readOnly = true)
    public void schema(ObjectEncoder encoder) {
        encoder.openMap();
        if (busServer == null) {
            encoder.string("status").string("UNKNOWN");
        } else if (busServer.getDispatcher() instanceof SbeDispatcher sbeDispatcher) {
            encoder.string("status").string("OK")
                    .string("rejectedVersionCount").number(sbeDispatcher.getRejectedVersionCount());
        } else {
            encoder.string("status").string("UNSUPPORTED");
        }
        encoder.closeMap();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        var allReady = true;
        for (var activator : activatorFactory.getActivators()) {
            if (!activator.isActive()) {
                allReady = false;
                break;
            }
        }

        String leaderStatus;
        if (busServer == null) {
            leaderStatus = "UNKNOWN";
        } else if (busServer.isActive()) {
            leaderStatus = "OK";
        } else {
            leaderStatus = "STANDBY";
        }

        Long sbeRejectedVersionCount = null;
        if (busServer != null && busServer.getDispatcher() instanceof SbeDispatcher sbeDispatcher) {
            sbeRejectedVersionCount = sbeDispatcher.getRejectedVersionCount();
        }

        encoder.openMap()
                .string("live").string("OK")
                .string("ready").string(allReady ? "OK" : "NOT_READY")
                .string("leader").string(leaderStatus);
        if (sbeRejectedVersionCount != null) {
            encoder.string("sbeRejectedVersionCount").number(sbeRejectedVersionCount);
        }
        encoder.closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
