package com.core.platform.applications.sequencer;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.BDDAssertions.then;

public class ConsensusModuleTest {

    private ConsensusModule consensusModule;
    private ManualTime time;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        consensusModule = new ConsensusModule(logFactory, metricFactory, time);
    }

    @Test
    void acquire_denied_increments_counter() {
        consensusModule.tryAcquire("nodeA");

        then(consensusModule.tryAcquire("nodeB")).isFalse();
        then(consensusModule.getAcquireDeniedCount()).isEqualTo(1);
    }

    @Test
    void renew_failure_increments_counter() {
        then(consensusModule.tryRenew("nodeA")).isFalse();
        then(consensusModule.getRenewFailureCount()).isEqualTo(1);
    }

    @Test
    void lease_remaining_ms_decreases_over_time() {
        consensusModule.tryAcquire("nodeA");
        var initial = consensusModule.toString();

        time.advanceTime(Duration.ofMillis(100));
        var updated = consensusModule.toString();

        then(initial).contains("remainingMs");
        then(updated).contains("remainingMs");
    }

    @Test
    void epoch_increments_on_new_acquire_after_expiry() {
        consensusModule.tryAcquire("nodeA");
        then(consensusModule.getEpoch()).isEqualTo(1);

        time.advanceTime(Duration.ofMillis(5001));
        consensusModule.tryAcquire("nodeB");

        then(consensusModule.getEpoch()).isEqualTo(2);
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
    }

    @Test
    void acquire_by_same_holder_acts_as_renew_epoch_unchanged() {
        consensusModule.tryAcquire("nodeA");
        var epoch = consensusModule.getEpoch();

        then(consensusModule.tryAcquire("nodeA")).isTrue();
        then(consensusModule.getEpoch()).isEqualTo(epoch);
    }

    @Test
    void renew_fails_after_expiry_and_clears_holder() {
        consensusModule.tryAcquire("nodeA");

        time.advanceTime(Duration.ofMillis(5001));

        then(consensusModule.tryRenew("nodeA")).isFalse();
        then(consensusModule.getLeaseHolder()).isNull();
    }

    @Test
    void release_allows_immediate_new_acquire() {
        consensusModule.tryAcquire("nodeA");
        var epochAfterA = consensusModule.getEpoch();

        consensusModule.tryRelease("nodeA");

        then(consensusModule.tryAcquire("nodeB")).isTrue();
        then(consensusModule.getEpoch()).isEqualTo(epochAfterA + 1);
        then(consensusModule.getLeaseHolder()).isEqualTo("nodeB");
    }

    @Test
    void lease_not_expired_at_exact_timeout_boundary() {
        consensusModule.tryAcquire("nodeA");

        time.advanceTime(Duration.ofMillis(5000));

        then(consensusModule.isLeaseExpired()).isFalse();
        then(consensusModule.tryRenew("nodeA")).isTrue();
    }

    @Test
    void lease_expired_just_after_timeout() {
        consensusModule.tryAcquire("nodeA");

        time.advanceTime(Duration.ofMillis(5001));

        then(consensusModule.isLeaseExpired()).isTrue();
    }

    @Test
    void release_by_non_holder_is_no_op() {
        consensusModule.tryAcquire("nodeA");

        consensusModule.tryRelease("nodeB");

        then(consensusModule.getLeaseHolder()).isEqualTo("nodeA");
    }
}
