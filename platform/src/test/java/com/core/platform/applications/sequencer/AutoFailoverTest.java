package com.core.platform.applications.sequencer;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusServer;
import com.core.platform.schema.TestDispatcher;
import com.core.platform.schema.TestProvider;
import com.core.platform.schema.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.BDDAssertions.then;

public class AutoFailoverTest {

    private ManualTime time;
    private Scheduler scheduler;
    private TestBusServer<TestDispatcher, TestProvider> busServer;
    private PromotionGuard promotionGuard;
    private Activator sequencerActivator;
    private AutoFailover autoFailover;
    private TestSchema schema;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        schema = new TestSchema();
        busServer = new TestBusServer<>(time, schema, activatorFactory);
        promotionGuard = new PromotionGuard(logFactory, time, busServer);
        scheduler = new Scheduler(time);
        sequencerActivator = activatorFactory.createActivator("TestSequencer", new Object());
        autoFailover = new AutoFailover(logFactory, metricFactory, scheduler, promotionGuard, sequencerActivator);
    }

    @Test
    void auto_failover_disabled_by_default() {
        var status = autoFailover.toString();
        then(status).contains("enabled=false");
    }

    @Test
    void enable_starts_periodic_checks() {
        autoFailover.enable();

        var status = autoFailover.toString();
        then(status).contains("enabled=true");
    }

    @Test
    void disable_stops_checks() {
        autoFailover.enable();
        autoFailover.disable();

        var status = autoFailover.toString();
        then(status).contains("enabled=false");
    }

    @Test
    void no_promotion_when_promotion_guard_fails() {
        autoFailover.enable();

        // no events published, promotion guard should fail
        time.advanceTime(Duration.ofMillis(1100));
        scheduler.fire();

        then(sequencerActivator.isStarted()).isFalse();
        then(autoFailover.toString()).contains("checksPerformed=1");
    }

    @Test
    void promotion_after_consecutive_passes() {
        autoFailover.enable();

        // publish event and wait past silence threshold so promotion guard passes
        publishPassiveEvent(1, 1);
        time.advanceTime(Duration.ofMillis(3500));

        // fire 3 times (the default requiredConsecutivePasses)
        scheduler.fire(); // pass 1
        time.advanceTime(Duration.ofMillis(1000));
        scheduler.fire(); // pass 2
        time.advanceTime(Duration.ofMillis(1000));
        scheduler.fire(); // pass 3

        then(sequencerActivator.isStarted()).isTrue();
        then(autoFailover.toString()).contains("autoPromotionsTriggered=1");
    }

    @Test
    void consecutive_passes_reset_on_failure() {
        autoFailover.enable();

        // publish event and wait past silence threshold
        publishPassiveEvent(1, 1);
        time.advanceTime(Duration.ofMillis(3500));

        // pass 1
        scheduler.fire();
        then(autoFailover.toString()).contains("consecutivePasses=1");

        // pass 2
        time.advanceTime(Duration.ofMillis(1000));
        scheduler.fire();
        then(autoFailover.toString()).contains("consecutivePasses=2");

        // now introduce a gap to make promotion guard fail
        publishPassiveEvent(1, 5); // gap: expected 2, got 5
        time.advanceTime(Duration.ofMillis(1000));
        scheduler.fire();

        then(autoFailover.toString()).contains("consecutivePasses=0");
        then(sequencerActivator.isStarted()).isFalse();
    }

    @Test
    void no_promotion_when_already_active() {
        // start the sequencer activator so isActive returns true
        sequencerActivator.ready();
        sequencerActivator.start();

        autoFailover.enable();

        publishPassiveEvent(1, 1);
        time.advanceTime(Duration.ofMillis(3500));

        // fire multiple times
        scheduler.fire();
        time.advanceTime(Duration.ofMillis(1000));
        scheduler.fire();
        time.advanceTime(Duration.ofMillis(1000));
        scheduler.fire();

        // should not have triggered promotion (checksPerformed stays 0 because isActive short-circuits)
        then(autoFailover.toString()).contains("autoPromotionsTriggered=0");
        then(autoFailover.toString()).contains("consecutivePasses=0");
    }

    @Test
    void status_contains_all_fields() {
        var status = autoFailover.toString();
        then(status).contains("enabled");
        then(status).contains("checkIntervalMs");
        then(status).contains("requiredConsecutivePasses");
        then(status).contains("consecutivePasses");
        then(status).contains("checksPerformed");
        then(status).contains("autoPromotionsTriggered");
    }

    private void publishPassiveEvent(int appId, int appSeqNum) {
        var buffer = BufferUtils.allocate(schema.getMessageHeaderLength() + 10);
        buffer.putShort(schema.getApplicationIdOffset(), (short) appId);
        buffer.putInt(schema.getApplicationSequenceNumberOffset(), appSeqNum);
        buffer.putByte(schema.getMessageTypeOffset(), (byte) -1);
        busServer.publishEvent(buffer);
    }
}
