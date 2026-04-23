package com.core.platform.applications.sequencer;

import com.core.infrastructure.EventLoop;
import com.core.infrastructure.collections.CoreMap;
import com.core.infrastructure.io.Selector;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusServer;
import com.core.platform.bus.TestMessagePublisher;
import com.core.platform.schema.ApplicationDefinitionDecoder;
import com.core.platform.schema.ApplicationDefinitionEncoder;
import com.core.platform.schema.HeartbeatDecoder;
import com.core.platform.schema.TestDispatcher;
import com.core.platform.schema.TestProvider;
import com.core.platform.schema.TestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.Mockito.mock;

public class SequencerConsensusTest {

    private TestBusServer<TestDispatcher, TestProvider> busServer;
    private ManualTime time;
    private EventLoop eventLoop;
    private Sequencer sequencer;
    private TestSchema schema;
    private TestMessagePublisher eventPublisher;
    private Activator activator;
    private Map<String, Short> nameIdMap;
    private ConsensusModule consensusModule;

    @BeforeEach
    void before_each() throws Exception {
        final var applicationName = "SEQ01";

        nameIdMap = new CoreMap<>();
        time = new ManualTime(LocalTime.of(9, 30));
        var scheduler = new Scheduler(time);
        eventLoop = new EventLoop(time, scheduler, mock(Selector.class));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);
        schema = new TestSchema();
        busServer = new TestBusServer<>(time, schema, activatorFactory);
        eventPublisher = busServer.getEventPublisher();
        consensusModule = new ConsensusModule(logFactory, metricFactory, time);
        sequencer = new Sequencer(
                time,
                scheduler,
                activatorFactory,
                logFactory,
                metricFactory,
                busServer,
                applicationName);
        setConsensusModule(sequencer, consensusModule);
        activator = activatorFactory.getActivator(sequencer);
        busServer.getDispatcher().addHeartbeatListener(x -> {
            var buffer = busServer.acquire();
            buffer.putBytes(0, x.buffer(), x.offset(), x.length());
            busServer.commit(x.length());
        });
        busServer.getDispatcher().addApplicationDefinitionListener(x -> {
            var id = nameIdMap.get(x.nameAsString());
            if (id == null) {
                id = (short) (nameIdMap.size() + 1);
                nameIdMap.put(x.nameAsString(), id);
                busServer.setApplicationSequenceNumber(id, 1);
            }

            var buffer = busServer.acquire();
            buffer.putBytes(0, x.buffer(), x.offset(), x.length());
            buffer.putShort(schema.getApplicationIdOffset(), id);
            busServer.commit(x.length());
        });
    }

    private void setConsensusModule(Sequencer sequencer, Consensus ws) throws Exception {
        var field = Sequencer.class.getDeclaredField("consensusModule");
        field.setAccessible(true);
        field.set(sequencer, ws);
    }

    @Test
    void consensus_lease_denied_blocks_activation() {
        var otherConsensus = consensusModule;
        otherConsensus.tryAcquire("OTHER_NODE");

        activator.start();

        then(activator.isActive()).isFalse();
    }

    @Test
    void command_dropped_when_consensus_lease_expired() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01");

        time.advanceTime(Duration.ofMillis(5001));

        publishCommand(2, 2, -1, "1");

        then(eventPublisher.size()).isEqualTo(0);
    }

    @Test
    void command_processed_when_consensus_lease_valid() {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01");

        publishCommand(2, 2, -1, "1");

        then(eventPublisher.size()).isEqualTo(1);
    }

    @Test
    void heartbeat_renews_lease() throws IOException {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();
        publishAppDefinitionCommand("APP01");

        time.advanceTime(Duration.ofMillis(100));
        eventLoop.runOnce();
        eventPublisher.remove();

        publishCommand(2, 2, -1, "1");

        then(eventPublisher.size()).isEqualTo(1);
    }

    @Test
    void leader_epoch_matches_consensus_epoch() throws Exception {
        activator.start();

        var field = Sequencer.class.getDeclaredField("leaderEpoch");
        field.setAccessible(true);
        var leaderEpoch = (int) field.get(sequencer);

        then(leaderEpoch).isEqualTo(consensusModule.getEpoch());
    }

    @Test
    void heartbeat_contains_leader_epoch() {
        activator.start();
        eventPublisher.remove();
        HeartbeatDecoder decoder = eventPublisher.remove();

        then(decoder.getLeaderEpoch()).isEqualTo(consensusModule.getEpoch());
    }

    @Test
    void sequencer_self_deactivates_when_lease_lost_during_heartbeat() throws IOException {
        activator.start();
        eventPublisher.remove();
        eventPublisher.remove();

        time.advanceTime(Duration.ofMillis(5001));
        eventLoop.runOnce();

        then(activator.isActive()).isFalse();
    }

    private void publishCommand(int appId, int appSeqNum, int msgType, String message) {
        busServer.publishCommand(buildMessage(appId, appSeqNum, (byte) msgType, message));
    }

    private void publishAppDefinitionCommand(String appName) {
        busServer.publishCommand(new ApplicationDefinitionEncoder()
                .setApplicationId((short) 0)
                .setApplicationSequenceNumber(1)
                .setName(appName));
        busServer.getEventPublisher().remove();
    }

    private org.agrona.MutableDirectBuffer buildMessage(
            int appId, int appSeqNum, byte msgType, String message) {
        var event = com.core.infrastructure.buffer.BufferUtils.allocate(
                schema.getMessageHeaderLength() + message.length());
        event.putShort(schema.getApplicationIdOffset(), (short) appId);
        event.putInt(schema.getApplicationSequenceNumberOffset(), appSeqNum);
        event.putByte(schema.getMessageTypeOffset(), msgType);
        event.putStringWithoutLengthAscii(schema.getMessageHeaderLength(), message);
        return event;
    }
}
