package com.core.platform.applications.sequencer;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.concurrent.ThreadIdentityGuard;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.messages.Dispatcher;
import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.messages.Schema;
import com.core.infrastructure.time.Scheduler;
import com.core.infrastructure.time.Time;
import com.core.platform.activation.Activatable;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.BusServer;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The sequencer is the central nervous system of a core trading system.
 * When active, it is the only application to listen to the command channel and publish on the event channel (all other
 * applications, including the sequencer when inactive, do the opposite).
 *
 * <p>Command validation:
 * <ul>
 *     <li>Application definitions are only accepted when {@code appId=0} and {@code appSeqNum=1}.</li>
 *     <li>All other commands must provide a valid application id and match the expected
 *         per-application sequence number maintained by the bus server.</li>
 *     <li>Invalid or out-of-order commands are dropped and do not mutate sequencer state.</li>
 * </ul>
 *
 * <p>Failure handling:
 * <ul>
 *     <li>Dispatch errors are not caught on the hot path — runtime exceptions propagate to the
 *         event loop boundary and crash the process (fail-stop). This avoids try/catch overhead
 *         and non-deterministic exception allocation on the critical path.</li>
 *     <li>When a consensus lease is configured, the sequencer renews the lease on each command and
 *         self-deactivates if the lease is lost.</li>
 * </ul>
 *
 * <h2>Activation</h2>
 *
 * <p>The application has the following activation dependencies:
 * <ul>
 *     <li>the bus server is active
 * </ul>
 *
 * <p>On activation, the application will:
 * <ul>
 *     <li>if this is the first message of the session, publish an application definition message for the this
 *         application
 *     <li>publish a heartbeat message
 * </ul>
 *
 * <p>On deactivation, the application will:
 * <ul>
 *     <li>publish a {@code ApplicationStatus} message with a status of {@code DOWN}
 *     <li>set itself as not ready
 * </ul>
 */
public class Sequencer implements Activatable, Encodable {

    private static final int DEFAULT_HEARTBEAT_TIMEOUT_MS = 100;

    private final Schema<?, ?> schema;
    private final BusServer<?, ?> busServer;
    @Directory(path = ".")
    private final Activator activator;
    private final Log log;
    private final Time time;
    private final Dispatcher dispatcher;
    private final Scheduler scheduler;

    private final Encoder heartbeatEncoder;
    private final Encoder appDefinitionEncoder;

    private final ThreadIdentityGuard threadGuard = new ThreadIdentityGuard();
    private final Runnable cachedSendHeartbeat;

    @Property(write = true)
    private long heartbeatTimeout;
    @Property
    private long heartbeatTaskId;
    @Property
    private int leaderEpoch;
    @Property
    private int lastSeenEpoch;
    @Property
    private long commandCount;
    @Property
    private long commandDropCount;
    @Property
    private long commandProcessedCount;
    @Property
    private long lastCommandLatencyNanos;
    @Property
    private long maxCommandLatencyNanos;
    @Property
    private short lastCorrelationId;
    @Property(write = true)
    private Consensus consensusModule;

    private final String applicationName;

    /**
     * Creates a {@code Sequencer} from the specified parameters.
     * The sequencer subscribes to the command and event channels through the {@code busServer} to process commands
     * when active and listen to events when inactive.
     * The sequencer will also schedule a recurring heartbeat message, with the name of the heartbeat message and the
     * duration between heartbeats specified by the constructor parameters.
     *
     * @param time the time source for event timestamps
     * @param scheduler a scheduler to send heartbeats
     * @param activatorFactory a factory to create activators
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param busServer the bus server
     * @param applicationName the name of the application
     */
    public Sequencer(
            Time time,
            Scheduler scheduler,
            ActivatorFactory activatorFactory,
            LogFactory logFactory,
            MetricFactory metricFactory,
            BusServer<?, ?> busServer,
            String applicationName) {
        this.time = Objects.requireNonNull(time, "time is null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is null");
        Objects.requireNonNull(activatorFactory, "activationManager is null");
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.busServer = Objects.requireNonNull(busServer, "busServer is null");
        Objects.requireNonNull(applicationName, "applicationName is null");
        this.applicationName = applicationName;

        cachedSendHeartbeat = this::sendHeartbeat;

        schema = busServer.getSchema();

        heartbeatTimeout = TimeUnit.MILLISECONDS.toNanos(DEFAULT_HEARTBEAT_TIMEOUT_MS);
        heartbeatEncoder = schema.createEncoder(schema.getHeartbeatMessageName())
                .wrap(BufferUtils.allocate(256));
        var appDefinitionMessageName = schema.getApplicationDefinitionMessageName();
        var appDefinitionNameField = schema.getApplicationDefinitionNameField();
        appDefinitionEncoder = schema.createEncoder(appDefinitionMessageName)
                .wrap(BufferUtils.allocate(256))
                .setApplicationSequenceNumber(1)
                .set(appDefinitionNameField, BufferUtils.fromAsciiString(applicationName));

        dispatcher = busServer.getDispatcher();
        log = logFactory.create(getClass());

        activator = activatorFactory.createActivator(applicationName, this, busServer);

        busServer.addEventListener(buffer -> onEvent(buffer, 0, buffer.capacity()));
        busServer.setCommandListener(buffer -> onCommand(buffer, 0, buffer.capacity()));

        metricFactory.registerGaugeMetric("Sequencer_LeaderEpoch", () -> leaderEpoch);
        metricFactory.registerGaugeMetric("Sequencer_CommandCount", () -> commandCount);
        metricFactory.registerGaugeMetric("Sequencer_CommandDropCount", () -> commandDropCount);
        metricFactory.registerGaugeMetric("Sequencer_LastCommandLatencyNanos", () -> lastCommandLatencyNanos);
        metricFactory.registerGaugeMetric("Sequencer_MaxCommandLatencyNanos", () -> maxCommandLatencyNanos);
        metricFactory.registerGaugeMetric("Sequencer_LastCorrelationId", () -> (long) lastCorrelationId);
        metricFactory.registerGaugeMetric("Sequencer_CommandProcessedCount", () -> commandProcessedCount);
        if (dispatcher instanceof com.core.platform.schema.sbe.SbeDispatcher sbeDispatcher) {
            metricFactory.registerGaugeMetric(
                    "Sequencer_SbeRejectedVersionCount", sbeDispatcher::getRejectedVersionCount);
        }
        threadGuard.bind();
    }

    private void onCommand(DirectBuffer buffer, int offset, int length) {
        threadGuard.check();
        var commandReceiveTime = time.nanos();

        if (length < schema.getMessageHeaderLength()) {
            log.warn().append("command received with less bytes than header length: expected=")
                    .append(schema.getMessageHeaderLength())
                    .append(", received=").append(length)
                    .commit();
            return;
        }

        if (consensusModule != null) {
            var holder = consensusModule.getLeaseHolder();
            if (holder == null || !holder.equals(applicationName) || consensusModule.isLeaseExpired()) {
                commandDropCount++;
                log.error().append("dropping command: consensus lease invalid, self-deactivating: node=")
                        .append(applicationName).commit();
                activator.stop();
                return;
            }
        }

        var appId = buffer.getShort(schema.getApplicationIdOffset());
        var appSeqNum = buffer.getInt(schema.getApplicationSequenceNumberOffset());
        lastCorrelationId = buffer.getShort(schema.getOptionalFieldsOffset());

        boolean isAppDefinition = appId == 0
                && appSeqNum == 1
                && buffer.getByte(schema.getMessageTypeOffset()) == appDefinitionEncoder.messageType();
        if (isAppDefinition) {
            dispatchAndRecord(buffer, offset, length, commandReceiveTime);
            return;
        }

        if (appId <= 0) {
            commandDropCount++;
            log.warn().append("command received with invalid appId, dropping: appId=").append(appId)
                    .commit();
            return;
        }

        // handle other messages
        var expectedAppSeqNum = busServer.incrementAndGetApplicationSequenceNumber(appId);
        if (expectedAppSeqNum < 0) {
            commandDropCount++;
            log.warn().append("command received with unknown appId, dropping: appId=").append(appId)
                    .commit();
            return;
        }
        if (appSeqNum == expectedAppSeqNum) {
            dispatchAndRecord(buffer, offset, length, commandReceiveTime);
        } else {
            // back out the changes
            busServer.setApplicationSequenceNumber(appId, expectedAppSeqNum - 1);
            commandDropCount++;
            log.warn().append("command received with incorrect appSeqNum, dropping: appId=").append(appId)
                    .append(", actualAppSeqNum=").append(appSeqNum)
                    .append(", expectedAppSeqNum=").append(expectedAppSeqNum)
                    .commit();
        }
    }

    private void dispatchAndRecord(
            DirectBuffer buffer,
            int offset,
            int length,
            long commandReceiveTime) {
        dispatcher.dispatch(buffer, offset, length);
        busServer.send();
        renewLeaseIfPresent();
        commandProcessedCount++;
        commandCount++;
        lastCommandLatencyNanos = time.nanos() - commandReceiveTime;
        if (lastCommandLatencyNanos > maxCommandLatencyNanos) {
            maxCommandLatencyNanos = lastCommandLatencyNanos;
        }
    }

    private void onEvent(DirectBuffer buffer, int offset, int length) {
        threadGuard.check();
        if (busServer.isActive()) {
            return;
        }

        if (length < schema.getMessageHeaderLength()) {
            log.warn().append("event received with less bytes than header length: expected=")
                    .append(schema.getMessageHeaderLength())
                    .append(", received=").append(length)
                    .commit();
            return;
        }

        warnOnRejectedSchemaVersion();

        var eventEpoch = buffer.getInt(schema.getLeaderEpochOffset());
        if (eventEpoch > 0 && eventEpoch < lastSeenEpoch) {
            log.warn().append("stale epoch event dropped: eventEpoch=").append(eventEpoch)
                    .append(", lastSeenEpoch=").append(lastSeenEpoch).commit();
            return;
        }
        if (eventEpoch > lastSeenEpoch) {
            lastSeenEpoch = eventEpoch;
        }

        // set app sequence number
        var appId = buffer.getShort(schema.getApplicationIdOffset());
        if (appId <= 0) {
            return;
        }
        var appSeqNum = buffer.getInt(schema.getApplicationSequenceNumberOffset());
        var currentSeqNum = busServer.getApplicationSequenceNumber(appId);
        if (appSeqNum > currentSeqNum) {
            busServer.setApplicationSequenceNumber(appId, appSeqNum);
        }
        dispatcher.dispatch(buffer, offset, length);
        busServer.send();
    }

    private void warnOnRejectedSchemaVersion() {
        if (!(dispatcher instanceof com.core.platform.schema.sbe.SbeDispatcher sbeDispatcher)) {
            return;
        }
        if (sbeDispatcher.getRejectedVersionCount() > 0 && !sbeDispatcher.isReportedRejectedVersion()) {
            log.warn().append("schema version rejected by SBE dispatcher: count=")
                    .append(sbeDispatcher.getRejectedVersionCount())
                    .commit();
            sbeDispatcher.markRejectedVersionReported();
        } else if (sbeDispatcher.getRejectedVersionCount() == 0 && sbeDispatcher.isReportedRejectedVersion()) {
            sbeDispatcher.resetRejectedVersionReported();
        }
    }

    private void sendHeartbeat() {
        if (consensusModule != null && !consensusModule.tryRenew(applicationName)) {
            log.error().append("consensus lease lost, self-deactivating: ").append(applicationName).commit();
            activator.stop();
            return;
        }

        if (busServer.getApplicationId() == 0) {
            // define the sequencer
            onCommand(appDefinitionEncoder.buffer(), appDefinitionEncoder.offset(), appDefinitionEncoder.length());
        }

        // send the heartbeat
        var appSeqNum = busServer.getApplicationSequenceNumber(busServer.getApplicationId()) + 1;
        heartbeatEncoder.setApplicationId(busServer.getApplicationId())
                .setApplicationSequenceNumber(appSeqNum);
        onCommand(heartbeatEncoder.buffer(), heartbeatEncoder.offset(), heartbeatEncoder.length());
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        Long sbeRejectedVersionCount = null;
        if (dispatcher instanceof com.core.platform.schema.sbe.SbeDispatcher sbeDispatcher) {
            sbeRejectedVersionCount = sbeDispatcher.getRejectedVersionCount();
        }
        encoder.openMap()
                .string("activator").object(activator)
                .string("busServer").object(busServer)
                .string("time").object(time)
                .string("leaderEpoch").number(leaderEpoch)
                .string("commandCount").number(commandCount)
                .string("commandDropCount").number(commandDropCount)
                .string("commandProcessedCount").number(commandProcessedCount)
                .string("lastCommandLatencyNanos").number(lastCommandLatencyNanos)
                .string("maxCommandLatencyNanos").number(maxCommandLatencyNanos)
                .string("lastCorrelationId").number(lastCorrelationId)
                .string("heartbeatTimeout").number(heartbeatTimeout)
                .string("consensusEnabled").bool(consensusModule != null);
        if (sbeRejectedVersionCount != null) {
            encoder.string("sbeRejectedVersionCount").number(sbeRejectedVersionCount);
        }
        encoder.closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    @Override
    public void activate() {
        if (consensusModule != null && !acquireLease()) {
            log.error().append("sequencer activation blocked: consensus lease denied for ")
                    .append(applicationName).commit();
            activator.notReady("consensus lease denied");
            return;
        }

        if (consensusModule != null) {
            leaderEpoch = consensusModule.getEpoch();
        } else {
            leaderEpoch++;
        }
        log.info().append("sequencer activated: epoch=").append(leaderEpoch).commit();
        busServer.setLeaderEpoch(leaderEpoch);

        heartbeatTaskId = scheduler.scheduleEvery(
                heartbeatTaskId, heartbeatTimeout, cachedSendHeartbeat, "Sequencer:heartbeat", 0);
        activator.ready();

        sendHeartbeat();
    }

    /** Resets latency tracking counters. */
    @Command(path = "resetLatency")
    public void resetLatency() {
        threadGuard.check();
        maxCommandLatencyNanos = 0;
        lastCommandLatencyNanos = 0;
    }

    @Override
    public void deactivate() {
        heartbeatTaskId = scheduler.cancel(heartbeatTaskId);
        if (consensusModule != null) {
            releaseLease();
        }
        activator.notReady();
    }

    private boolean acquireLease() {
        return consensusModule.tryAcquire(applicationName);
    }

    private void releaseLease() {
        consensusModule.tryRelease(applicationName);
    }

    private void renewLeaseIfPresent() {
        if (consensusModule != null) {
            consensusModule.tryRenew(applicationName);
        }
    }
}
