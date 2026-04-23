package com.core.platform.applications.sequencer;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;

public class TcpConsensusTest {

    private TcpConsensusClient client;

    @BeforeEach
    void before_each() {
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        client = new TcpConsensusClient(logFactory, metricFactory);
    }

    @Test
    void client_not_connected_by_default() {
        then(client.getLeaseHolder()).isNull();
        then(client.getEpoch()).isEqualTo(0);
    }

    @Test
    void acquire_returns_false_when_not_connected() {
        then(client.tryAcquire("node1")).isFalse();
    }

    @Test
    void renew_returns_false_when_not_connected() {
        then(client.tryRenew("node1")).isFalse();
    }

    @Test
    void release_does_not_throw_when_not_connected() {
        client.tryRelease("node1");
    }

    @Test
    void lease_expired_returns_true_when_not_connected() {
        then(client.isLeaseExpired()).isTrue();
    }

    @Test
    void connect_to_invalid_address_does_not_throw() {
        client.connect("localhost:1");
        then(client.tryAcquire("node1")).isFalse();
    }

    @Test
    void status_contains_all_fields() {
        var status = client.toString();
        then(status).contains("serverAddress");
        then(status).contains("connected");
        then(status).contains("lastEpoch");
        then(status).contains("requestsSent");
        then(status).contains("requestsFailed");
    }

    @Test
    void client_implements_consensus_interface() {
        Consensus consensus = client;
        then(consensus.tryAcquire("test")).isFalse();
        then(consensus.getEpoch()).isEqualTo(0);
    }
}
