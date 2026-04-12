package com.core.platform.applications.sequencer;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.concurrent.ThreadIdentityGuard;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.Scheduler;
import com.core.infrastructure.time.Time;
import com.core.platform.activation.Activator;
import com.core.platform.bus.BusServer;
import org.agrona.DirectBuffer;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Validates backup synchronization status before allowing sequencer promotion (go-active).
 *
 * <p>This application wraps sequencer promotion with safety checks to prevent promoting a backup
 * that is not synchronized with the primary. The operator can check {@code syncStatus} to see
 * current sync state, use {@code promote} to validate before promotion, or use {@code forcePromote}
 * to bypass checks in emergencies.
 *
 * <p>This class does not call start/stop on the sequencer directly. It only performs validation
 * checks and reports whether promotion is safe.
 */
public class PromotionGuard implements Encodable {

    private static final long DEFAULT_EVENT_SILENCE_THRESHOLD_MS = 3000;
    private static final long DEFAULT_AUTO_PROMOTE_CHECK_INTERVAL_NANOS = 200_000_000L;

    private final Log log;
    private final Time time;
    private final BusServer<?, ?> busServer;
    private final ThreadIdentityGuard threadGuard = new ThreadIdentityGuard();
    private final Runnable cachedAutoPromoteCheck;

    @Property(write = true)
    private long eventSilenceThresholdMs;
    @Property(write = true)
    private Consensus consensusModule;

    @Property
    private boolean sequenceGapDetected;
    @Property
    private short gapAppId;
    @Property
    private int gapExpectedSeq;
    @Property
    private int gapActualSeq;

    private long lastEventTimestampNanos;
    private long eventsReceived;
    @Property
    private long promotionChecksPassed;
    @Property
    private long promotionChecksFailed;
    private int[] lastSeqByAppId;

    @Property
    private long rollingChecksum;
    @Property
    private long checksumMessageCount;

    @Property
    private int lastSeenEpoch;
    @Property
    private boolean staleEpochDetected;

    private Scheduler scheduler;
    private Activator targetActivator;
    @Property
    private boolean autoPromoteEnabled;
    @Property
    private long autoPromoteCheckIntervalNanos;
    @Property
    private boolean autoPromoteAttempted;
    @Property
    private long autoPromoteAttemptCount;
    private long autoPromoteTaskId;

    /**
     * Creates a {@code PromotionGuard} from the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param time the time source
     * @param busServer the bus server
     */
    public PromotionGuard(LogFactory logFactory, Time time, BusServer<?, ?> busServer) {
        this(logFactory, new MetricFactory(logFactory), time, busServer,
                busServer.supportsEventListening() ? busServer::addEventListener : listener -> {});
    }

    /**
     * Creates a {@code PromotionGuard} with an external passive event source.
     *
     * @param logFactory a factory to create logs
     * @param time the time source
     * @param busServer the bus server
     * @param passiveEventSource the source of passive events for backup sync validation
     */
    public PromotionGuard(LogFactory logFactory, Time time, BusServer<?, ?> busServer,
                           PassiveEventSource passiveEventSource) {
        this(logFactory, new MetricFactory(logFactory), time, busServer, passiveEventSource);
    }

    /**
     * Creates a {@code PromotionGuard} with metrics and an external passive event source.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param time the time source
     * @param busServer the bus server
     * @param passiveEventSource the source of passive events for backup sync validation
     */
    public PromotionGuard(LogFactory logFactory, MetricFactory metricFactory, Time time,
                           BusServer<?, ?> busServer, PassiveEventSource passiveEventSource) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.time = Objects.requireNonNull(time, "time is null");
        this.busServer = Objects.requireNonNull(busServer, "busServer is null");
        Objects.requireNonNull(passiveEventSource, "passiveEventSource is null");

        log = logFactory.create(getClass());
        eventSilenceThresholdMs = DEFAULT_EVENT_SILENCE_THRESHOLD_MS;
        autoPromoteCheckIntervalNanos = DEFAULT_AUTO_PROMOTE_CHECK_INTERVAL_NANOS;
        lastSeqByAppId = new int[100];
        cachedAutoPromoteCheck = this::autoPromoteCheck;

        passiveEventSource.addEventListener(this::onEvent);

        metricFactory.registerGaugeMetric("PromotionGuard_EventsReceived", () -> eventsReceived);
        metricFactory.registerGaugeMetric("PromotionGuard_GapDetected", () -> sequenceGapDetected ? 1L : 0L);
        metricFactory.registerGaugeMetric("PromotionGuard_PromotionChecksPassed", () -> promotionChecksPassed);
        metricFactory.registerGaugeMetric("PromotionGuard_PromotionChecksFailed", () -> promotionChecksFailed);
        metricFactory.registerGaugeMetric("PromotionGuard_AutoPromoteAttemptCount", () -> autoPromoteAttemptCount);
        metricFactory.registerSwitchMetric("PromotionGuard_AutoPromoteEnabled", () -> autoPromoteEnabled);
        metricFactory.registerGaugeMetric("PromotionGuard_StaleEpochDetected", () -> staleEpochDetected ? 1L : 0L);
        metricFactory.registerGaugeMetric("PromotionGuard_RollingChecksum", () -> rollingChecksum);
        metricFactory.registerGaugeMetric("PromotionGuard_ChecksumMessageCount", () -> checksumMessageCount);

        threadGuard.bind();
    }

    private void onEvent(DirectBuffer buffer) {
        threadGuard.check();
        if (!busServer.isActive()) {
            lastEventTimestampNanos = time.nanos();
            eventsReceived++;
            updateRollingChecksum(buffer);

            var schema = busServer.getSchema();
            if (buffer.capacity() >= schema.getMessageHeaderLength()) {
                var eventEpoch = buffer.getInt(schema.getLeaderEpochOffset());
                if (eventEpoch > 0) {
                    if (eventEpoch < lastSeenEpoch) {
                        staleEpochDetected = true;
                    } else if (eventEpoch > lastSeenEpoch) {
                        lastSeenEpoch = eventEpoch;
                    }
                }

                var appId = buffer.getShort(schema.getApplicationIdOffset());
                var appSeq = buffer.getInt(schema.getApplicationSequenceNumberOffset());

                if (appId > 0 && appSeq > 0) {
                    if (appId > lastSeqByAppId.length) {
                        lastSeqByAppId = Arrays.copyOf(lastSeqByAppId, appId * 2);
                    }
                    var idx = appId - 1;
                    var last = lastSeqByAppId[idx];
                    if (last != 0 && appSeq != last + 1) {
                        sequenceGapDetected = true;
                        gapAppId = (short) appId;
                        gapExpectedSeq = last + 1;
                        gapActualSeq = appSeq;
                    }
                    if (appSeq > last) {
                        lastSeqByAppId[idx] = appSeq;
                    }
                }
            }
        }
    }

    /**
     * Performs safety checks to determine if promotion is safe.
     * Does not actually promote the sequencer — the operator must still call start on the
     * sequencer's activator.
     *
     * @return a message indicating whether promotion checks passed or failed
     */
    @Command(path = "promote")
    public String promote() {
        threadGuard.check();
        var reasons = checkPromotion();
        if (reasons == null) {
            promotionChecksPassed++;
            log.info().append("promotion checks passed").commit();
            return "PASS: promotion checks passed, safe to promote";
        } else {
            promotionChecksFailed++;
            log.warn().append("promotion checks failed: ").append(reasons).commit();
            return "FAIL: " + reasons;
        }
    }

    /**
     * Bypasses all promotion checks. For emergency use only.
     *
     * @return a message indicating force promotion was acknowledged
     */
    @Command(path = "forcePromote")
    public String forcePromote() {
        threadGuard.check();
        log.warn().append("force promote invoked, bypassing all safety checks").commit();
        return "PASS: force promote acknowledged, bypassing all safety checks";
    }

    /**
     * Resets the gap detection state.
     * Use after investigating and resolving the cause of a detected sequence gap.
     *
     * @return confirmation message
     */
    @Command(path = "resetGapDetection")
    public String resetGapDetection() {
        threadGuard.check();
        if (sequenceGapDetected) {
            log.info().append("gap detection reset: was appId=").append(gapAppId)
                    .append(", expectedSeq=").append(gapExpectedSeq)
                    .append(", actualSeq=").append(gapActualSeq)
                    .commit();
            sequenceGapDetected = false;
            gapAppId = 0;
            gapExpectedSeq = 0;
            gapActualSeq = 0;
            return "OK: gap detection state reset";
        }
        return "OK: no gap was detected";
    }

    /**
     * Configures automatic promotion with the specified scheduler and target activator.
     * When enabled, the guard will periodically check promotion safety and automatically
     * start the target activator when all checks pass.
     *
     * @param scheduler the scheduler for periodic checks
     * @param targetActivator the sequencer's activator to start on auto-promotion
     */
    public void configureAutoPromote(Scheduler scheduler, Activator targetActivator) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is null");
        this.targetActivator = Objects.requireNonNull(targetActivator, "targetActivator is null");
    }

    /**
     * Enables automatic promotion checks. Requires prior call to
     * {@link #configureAutoPromote(Scheduler, Activator)}.
     *
     * @return status message
     */
    @Command(path = "enableAutoPromote")
    public String enableAutoPromote() {
        threadGuard.check();
        if (scheduler == null || targetActivator == null) {
            return "FAIL: auto-promote not configured, call configureAutoPromote first";
        }
        if (autoPromoteEnabled) {
            return "OK: auto-promote already enabled";
        }
        autoPromoteEnabled = true;
        autoPromoteAttempted = false;
        autoPromoteTaskId = scheduler.scheduleEvery(
                autoPromoteTaskId, autoPromoteCheckIntervalNanos,
                cachedAutoPromoteCheck, "PromotionGuard:autoPromote", 0);
        log.info().append("auto-promote enabled: checkIntervalNanos=")
                .append(autoPromoteCheckIntervalNanos).commit();
        return "OK: auto-promote enabled";
    }

    /**
     * Disables automatic promotion checks.
     *
     * @return status message
     */
    @Command(path = "disableAutoPromote")
    public String disableAutoPromote() {
        threadGuard.check();
        if (!autoPromoteEnabled) {
            return "OK: auto-promote already disabled";
        }
        autoPromoteEnabled = false;
        if (scheduler != null) {
            autoPromoteTaskId = scheduler.cancel(autoPromoteTaskId);
        }
        log.info().append("auto-promote disabled").commit();
        return "OK: auto-promote disabled";
    }

    private void autoPromoteCheck() {
        if (!autoPromoteEnabled || autoPromoteAttempted || busServer.isActive()) {
            return;
        }
        var reasons = checkPromotion();
        if (reasons == null) {
            autoPromoteAttempted = true;
            autoPromoteAttemptCount++;
            log.info().append("auto-promote: all checks passed, starting target activator").commit();
            targetActivator.start();
        }
    }

    /**
     * Returns the current synchronization status.
     *
     * @param encoder the encoder
     */
    @Command(path = "syncStatus", readOnly = true)
    public void encodeSyncStatus(ObjectEncoder encoder) {
        var nowNanos = time.nanos();
        var silenceMs = lastEventTimestampNanos > 0
                ? TimeUnit.NANOSECONDS.toMillis(nowNanos - lastEventTimestampNanos)
                : -1;
        var safe = checkPromotion() == null;

        encoder.openMap()
                .string("lastEventTimestampNanos").number(lastEventTimestampNanos)
                .string("eventSilenceMs").number(silenceMs)
                .string("isActive").bool(busServer.isActive())
                .string("eventsReceived").number(eventsReceived)
                .string("promotionSafe").bool(safe)
                .string("eventSilenceThresholdMs").number(eventSilenceThresholdMs)
                .string("promotionChecksPassed").number(promotionChecksPassed)
                .string("promotionChecksFailed").number(promotionChecksFailed);
        encoder.string("sequenceGapDetected").bool(sequenceGapDetected)
                .string("staleEpochDetected").bool(staleEpochDetected)
                .string("lastSeenEpoch").number(lastSeenEpoch);
        if (sequenceGapDetected) {
            encoder.string("gapAppId").number(gapAppId)
                    .string("gapExpectedSeq").number(gapExpectedSeq)
                    .string("gapActualSeq").number(gapActualSeq);
        }
        if (consensusModule != null) {
            var leaseHolder = consensusModule.getLeaseHolder();
            encoder.string("consensusLeaseHolder").string(leaseHolder != null ? leaseHolder : "none")
                    .string("consensusEpoch").number(consensusModule.getEpoch())
                    .string("consensusLeaseExpired").bool(consensusModule.isLeaseExpired());
        }
        encoder.string("autoPromoteEnabled").bool(autoPromoteEnabled)
                .string("autoPromoteAttempted").bool(autoPromoteAttempted)
                .string("autoPromoteAttemptCount").number(autoPromoteAttemptCount)
                .string("rollingChecksum").number(rollingChecksum)
                .string("checksumMessageCount").number(checksumMessageCount);
        encoder.closeMap();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encodeSyncStatus(encoder);
    }

    @Override
    public String toString() {
        return toEncodedString();
    }

    private void updateRollingChecksum(DirectBuffer buffer) {
        var hash = rollingChecksum;
        var capacity = buffer.capacity();
        var i = 0;
        for (; i + 7 < capacity; i += 8) {
            hash ^= buffer.getLong(i);
            hash = Long.rotateLeft(hash, 17);
        }
        for (; i < capacity; i++) {
            hash ^= (long) buffer.getByte(i) << ((i & 7) * 8);
        }
        hash = Long.rotateLeft(hash, 13);
        rollingChecksum = hash;
        checksumMessageCount++;
    }

    private String checkPromotion() {
        if (busServer.isActive()) {
            return "bus server is already active";
        }

        if (eventsReceived == 0) {
            return "no events received while passive, backup may not be synchronized";
        }

        if (sequenceGapDetected) {
            return "event sequence gap detected while passive: appId=" + gapAppId
                    + ", expectedSeq=" + gapExpectedSeq + ", actualSeq=" + gapActualSeq;
        }

        if (staleEpochDetected) {
            return "stale leader epoch detected while passive, event stream may be corrupted";
        }

        var nowNanos = time.nanos();
        var silenceMs = TimeUnit.NANOSECONDS.toMillis(nowNanos - lastEventTimestampNanos);
        if (silenceMs < eventSilenceThresholdMs) {
            return "primary may still be active, last event was " + silenceMs
                    + "ms ago (threshold: " + eventSilenceThresholdMs + "ms)";
        }

        if (consensusModule != null) {
            // check that no other node holds an active lease
            var leaseHolder = consensusModule.getLeaseHolder();
            if (leaseHolder != null && !consensusModule.isLeaseExpired()) {
                return "another node holds the consensus lease: " + leaseHolder;
            }
        }

        return null;
    }
}
