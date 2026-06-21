package com.core.credit.applications;

import com.core.credit.schema.CreditAcceptedEncoder;
import com.core.credit.schema.CreditDispatcher;
import com.core.credit.schema.CreditProvider;
import com.core.credit.schema.CreditSchema;
import com.core.credit.schema.HardStopEncoder;
import com.core.credit.schema.HardStopReleasedEncoder;
import com.core.credit.schema.LoadSodConsumedAckEncoder;
import com.core.credit.schema.SetCreditLimitAckEncoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * Unit tests for {@link CreditStoreClient}.
 *
 * <p>Events are injected directly via {@link TestBusClient#dispatch(Encoder)},
 * which calls the schema dispatcher synchronously — exactly what the sequencer
 * would do after writing the event to the event log.
 */
class CreditStoreClientTest {

    private TestBusClient<CreditDispatcher, CreditProvider> busClient;
    private CreditStoreClient client;
    private short seqNum;

    @BeforeEach
    void before_each() {
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);

        busClient = new TestBusClient<>(new CreditSchema(), activatorFactory);
        client = new CreditStoreClient(busClient);
        seqNum = 0;
    }

    @Nested
    class SodLoadTests {

        @Test
        void sod_ack_seeds_consumed_and_limit() {
            publishSodAck("ACCT1", 20_000_000L, 100_000_000L);

            then(client.getConsumed(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(20_000_000L);
            then(client.getLimit(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(100_000_000L);
        }

        @Test
        void remaining_reflects_limit_minus_consumed() {
            publishSodAck("ACCT1", 30_000_000L, 100_000_000L);

            then(client.getRemaining(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(70_000_000L);
        }

        @Test
        void unknown_account_returns_minus_one() {
            then(client.getConsumed(BufferUtils.fromAsciiString("UNKNOWN"))).isEqualTo(-1L);
            then(client.getLimit(BufferUtils.fromAsciiString("UNKNOWN"))).isEqualTo(-1L);
            then(client.getRemaining(BufferUtils.fromAsciiString("UNKNOWN"))).isEqualTo(-1L);
        }
    }

    @Nested
    class CreditAcceptedTests {

        @BeforeEach
        void setup() {
            publishSodAck("ACCT1", 0L, 100_000_000L);
        }

        @Test
        void accepted_event_updates_consumed() {
            publishAccepted("ORD1", "ACCT1", "CLI1", 40_000_000L, 40_000_000L);

            then(client.getConsumed(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(40_000_000L);
        }

        @Test
        void multiple_accepted_events_accumulate() {
            publishAccepted("ORD1", "ACCT1", "CLI1", 30_000_000L, 30_000_000L);
            publishAccepted("ORD2", "ACCT1", "CLI1", 20_000_000L, 50_000_000L);

            then(client.getConsumed(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(50_000_000L);
        }

        @Test
        void consumed_is_unchanged_when_no_accepted_event() {
            // Initial SOD loaded 0 consumed; without further accepted events it stays at 0
            then(client.getConsumed(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(0L);
        }
    }

    @Nested
    class LimitUpdateTests {

        @Test
        void set_credit_limit_ack_updates_limit() {
            publishLimitAck("ACCT1", 200_000_000L);

            then(client.getLimit(BufferUtils.fromAsciiString("ACCT1")))
                    .isEqualTo(200_000_000L);
        }
    }

    @Nested
    class HardStopTests {

        @Test
        void hard_stop_event_marks_account() {
            publishHardStop("ACCT1");

            then(client.isHardStopped(BufferUtils.fromAsciiString("ACCT1"))).isTrue();
        }

        @Test
        void hard_stop_released_clears_flag() {
            publishHardStop("ACCT1");
            publishHardStopReleased("ACCT1");

            then(client.isHardStopped(BufferUtils.fromAsciiString("ACCT1"))).isFalse();
        }

        @Test
        void account_not_hard_stopped_by_default() {
            then(client.isHardStopped(BufferUtils.fromAsciiString("ACCT1"))).isFalse();
        }
    }

    // ─── Event helpers ────────────────────────────────────────────────────────

    private void publishSodAck(String accountId, long consumed, long limit) {
        busClient.dispatch(new LoadSodConsumedAckEncoder()
                .setApplicationId((short) 1)
                .setApplicationSequenceNumber(++seqNum)
                .setAccountId(accountId)
                .setConsumedUsd(consumed)
                .setLimitUsd(limit));
    }

    private void publishAccepted(String orderId, String accountId, String clientId,
                                  long notional, long consumedAfter) {
        busClient.dispatch(new CreditAcceptedEncoder()
                .setApplicationId((short) 1)
                .setApplicationSequenceNumber(++seqNum)
                .setOrderId(orderId)
                .setAccountId(accountId)
                .setClientId(clientId)
                .setNotionalUsd(notional)
                .setConsumedAfterUsd(consumedAfter)
                .setRemainingUsd(100_000_000L - consumedAfter));
    }

    private void publishLimitAck(String accountId, long limit) {
        busClient.dispatch(new SetCreditLimitAckEncoder()
                .setApplicationId((short) 1)
                .setApplicationSequenceNumber(++seqNum)
                .setAccountId(accountId)
                .setLimitUsd(limit));
    }

    private void publishHardStop(String accountId) {
        busClient.dispatch(new HardStopEncoder()
                .setApplicationId((short) 1)
                .setApplicationSequenceNumber(++seqNum)
                .setAccountId(accountId)
                .setReason("test"));
    }

    private void publishHardStopReleased(String accountId) {
        busClient.dispatch(new HardStopReleasedEncoder()
                .setApplicationId((short) 1)
                .setApplicationSequenceNumber(++seqNum)
                .setAccountId(accountId));
    }
}
