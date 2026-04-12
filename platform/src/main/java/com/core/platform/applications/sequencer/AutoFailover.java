package com.core.platform.applications.sequencer;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.Activator;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Monitors event stream health and automatically triggers sequencer promotion
 * when the primary is detected as failed.
 *
 * <p>This service runs a periodic health check. When it detects that:
 * <ul>
 *     <li>The bus server is not active (we are a backup)
 *     <li>Events have been received (backup is synchronized)
 *     <li>No events received for longer than the configured silence threshold
 *     <li>No sequence gaps detected
 *     <li>No consensus lease is held by another node (or consensus module not configured)
 *     <li>Auto-failover is enabled
 * </ul>
 * ...it will automatically start the sequencer's activator, triggering promotion.
 *
 * <p>Auto-failover is disabled by default and must be explicitly enabled via the
 * {@code enable} command.
 */
public class AutoFailover implements Encodable {

    private static final long DEFAULT_CHECK_INTERVAL_MS = 1000;
    private static final int DEFAULT_REQUIRED_CONSECUTIVE_PASSES = 3;

    private final Log log;
    private final Scheduler scheduler;
    private final PromotionGuard promotionGuard;
    private final Activator sequencerActivator;

    @Property(write = true)
    private boolean enabled;
    @Property(write = true)
    private long checkIntervalMs;
    @Property(write = true)
    private int requiredConsecutivePasses;

    @Property
    private int consecutivePasses;
    @Property
    private long checksPerformed;
    @Property
    private long autoPromotionsTriggered;

    private long taskId;
    private final Runnable cachedCheck;

    /**
     * Creates an {@code AutoFailover} from the specified parameters.
     *
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param scheduler the scheduler for periodic checks
     * @param promotionGuard the promotion guard for safety validation
     * @param sequencerActivator the sequencer's activator to start on auto-promotion
     */
    public AutoFailover(
            LogFactory logFactory,
            MetricFactory metricFactory,
            Scheduler scheduler,
            PromotionGuard promotionGuard,
            Activator sequencerActivator) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is null");
        this.promotionGuard = Objects.requireNonNull(promotionGuard, "promotionGuard is null");
        this.sequencerActivator = Objects.requireNonNull(sequencerActivator, "sequencerActivator is null");

        log = logFactory.create(getClass());
        checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
        requiredConsecutivePasses = DEFAULT_REQUIRED_CONSECUTIVE_PASSES;
        cachedCheck = this::performCheck;

        metricFactory.registerSwitchMetric("AutoFailover_Enabled", () -> enabled);
        metricFactory.registerGaugeMetric("AutoFailover_ConsecutivePasses", () -> (long) consecutivePasses);
        metricFactory.registerGaugeMetric("AutoFailover_ChecksPerformed", () -> checksPerformed);
        metricFactory.registerGaugeMetric("AutoFailover_AutoPromotions", () -> autoPromotionsTriggered);
    }

    /**
     * Enables auto-failover and starts periodic health checks.
     */
    @Command
    public void enable() {
        if (!enabled) {
            enabled = true;
            taskId = scheduler.scheduleEvery(
                    taskId, TimeUnit.MILLISECONDS.toNanos(checkIntervalMs),
                    cachedCheck, "AutoFailover:check", 0);
            log.info().append("auto-failover enabled: checkIntervalMs=").append(checkIntervalMs)
                    .append(", requiredConsecutivePasses=").append(requiredConsecutivePasses)
                    .commit();
        }
    }

    /**
     * Disables auto-failover and stops periodic health checks.
     */
    @Command
    public void disable() {
        if (enabled) {
            enabled = false;
            taskId = scheduler.cancel(taskId);
            consecutivePasses = 0;
            log.info().append("auto-failover disabled").commit();
        }
    }

    private void performCheck() {
        if (!enabled || sequencerActivator.isActive()) {
            consecutivePasses = 0;
            return;
        }

        checksPerformed++;
        var result = promotionGuard.promote();

        if (result.startsWith("PASS")) {
            consecutivePasses++;
            if (consecutivePasses >= requiredConsecutivePasses) {
                log.info().append("auto-failover triggering promotion: consecutivePasses=")
                        .append(consecutivePasses).commit();
                autoPromotionsTriggered++;
                consecutivePasses = 0;
                sequencerActivator.start();
            }
        } else {
            if (consecutivePasses > 0) {
                log.info().append("auto-failover check failed, resetting: consecutivePasses=")
                        .append(consecutivePasses).append(", reason=").append(result).commit();
            }
            consecutivePasses = 0;
        }
    }

    /**
     * Returns the current status of the auto-failover.
     *
     * @param encoder the encoder
     */
    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("enabled").bool(enabled)
                .string("checkIntervalMs").number(checkIntervalMs)
                .string("requiredConsecutivePasses").number(requiredConsecutivePasses)
                .string("consecutivePasses").number(consecutivePasses)
                .string("checksPerformed").number(checksPerformed)
                .string("autoPromotionsTriggered").number(autoPromotionsTriggered)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
