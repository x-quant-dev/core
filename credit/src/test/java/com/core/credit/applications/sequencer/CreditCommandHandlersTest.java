package com.core.credit.applications.sequencer;

import com.core.credit.schema.AccountSnapshotAckDecoder;
import com.core.credit.schema.AccountSnapshotEncoder;
import com.core.credit.schema.CreditAcceptedDecoder;
import com.core.credit.schema.CreditCheckRequestEncoder;
import com.core.credit.schema.CreditDispatcher;
import com.core.credit.schema.CreditProvider;
import com.core.credit.schema.CreditRejectedDecoder;
import com.core.credit.schema.CreditSchema;
import com.core.credit.schema.HardStopDecoder;
import com.core.credit.schema.HardStopEncoder;
import com.core.credit.schema.HardStopReleasedDecoder;
import com.core.credit.schema.HardStopReleasedEncoder;
import com.core.credit.schema.LoadSodConsumedAckDecoder;
import com.core.credit.schema.LoadSodConsumedEncoder;
import com.core.credit.schema.SetCreditLimitAckDecoder;
import com.core.credit.schema.SetCreditLimitEncoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.ManualTime;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.TestBusServer;
import com.core.platform.bus.TestMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * Integration-level unit tests for {@link CreditCommandHandlers}.
 *
 * <p>Commands are dispatched directly through the bus-server dispatcher (bypassing the
 * network). The resulting events are inspected via {@link TestMessagePublisher}.
 *
 * <p>Every scenario follows the TDD pattern:
 * <ol>
 *   <li>Arrange — seed account state via a {@code LoadSodConsumed} command.</li>
 *   <li>Act — dispatch a command through the bus dispatcher.</li>
 *   <li>Assert — verify the published event via the event publisher.</li>
 * </ol>
 */
class CreditCommandHandlersTest {

    private static final short APP_ID = 2;

    private TestBusServer<CreditDispatcher, CreditProvider> busServer;
    private TestMessagePublisher eventPublisher;
    private CreditCommandHandlers handler;
    private ManualTime time;
    private int appSeqNum;

    @BeforeEach
    void before_each() {
        time = new ManualTime(LocalTime.of(9, 30));
        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);

        busServer = new TestBusServer<>(time, new CreditSchema(), activatorFactory);
        eventPublisher = busServer.getEventPublisher();
        handler = new CreditCommandHandlers(busServer);
        appSeqNum = 0;

        // Default: seed account ACCT1 with $1M limit, $0 consumed
        loadSod("ACCT1", 0L, 100_000_000L);
        eventPublisher.removeAll(); // clear the SOD ack from the queue
    }

    // ─── SOD Loading ──────────────────────────────────────────────────────────

    @Nested
    class LoadSodConsumedTests {

        @Test
        void loadSod_emits_ack_event() {
            loadSod("ACCT2", 5_000_000L, 50_000_000L);

            LoadSodConsumedAckDecoder ack = eventPublisher.remove();
            then(BufferUtils.toAsciiString(ack.getAccountId())).isEqualTo("ACCT2");
            then(ack.getConsumedUsd()).isEqualTo(5_000_000L);
            then(ack.getLimitUsd()).isEqualTo(50_000_000L);
        }

        @Test
        void loadSod_seeds_sequencer_state() {
            loadSod("ACCT2", 20_000_000L, 50_000_000L);
            eventPublisher.removeAll();

            // Order of 25M should be accepted (20 + 25 = 45 <= 50)
            creditCheck("ORD1", "ACCT2", "CLI1", 25_000_000L);
            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(BufferUtils.toAsciiString(accepted.getAccountId())).isEqualTo("ACCT2");
        }
    }

    // ─── Credit Check ─────────────────────────────────────────────────────────

    @Nested
    class CreditCheckTests {

        @Test
        void credit_check_within_limit_emits_accepted() {
            creditCheck("ORD1", "ACCT1", "CLI1", 50_000_000L);

            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(BufferUtils.toAsciiString(accepted.getOrderId())).isEqualTo("ORD1");
            then(BufferUtils.toAsciiString(accepted.getAccountId())).isEqualTo("ACCT1");
            then(accepted.getNotionalUsd()).isEqualTo(50_000_000L);
            then(accepted.getConsumedAfterUsd()).isEqualTo(50_000_000L);
            then(accepted.getRemainingUsd()).isEqualTo(50_000_000L);
        }

        @Test
        void credit_check_exactly_at_limit_is_accepted() {
            creditCheck("ORD1", "ACCT1", "CLI1", 100_000_000L);

            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(accepted.getRemainingUsd()).isEqualTo(0L);
        }

        @Test
        void credit_check_over_limit_emits_rejected() {
            creditCheck("ORD1", "ACCT1", "CLI1", 100_000_001L);

            CreditRejectedDecoder rejected = eventPublisher.remove();
            then(BufferUtils.toAsciiString(rejected.getOrderId())).isEqualTo("ORD1");
        }

        @Test
        void sequential_orders_accumulate_consumed() {
            creditCheck("ORD1", "ACCT1", "CLI1", 60_000_000L);
            eventPublisher.removeAll();

            creditCheck("ORD2", "ACCT1", "CLI1", 60_000_000L); // 60 + 60 > 100

            CreditRejectedDecoder rejected = eventPublisher.remove();
            then(BufferUtils.toAsciiString(rejected.getOrderId())).isEqualTo("ORD2");
        }

        @Test
        void accepted_order_reflects_updated_consumed_in_response() {
            creditCheck("ORD1", "ACCT1", "CLI1", 30_000_000L);
            eventPublisher.removeAll();

            creditCheck("ORD2", "ACCT1", "CLI1", 30_000_000L);

            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(accepted.getConsumedAfterUsd()).isEqualTo(60_000_000L);
            then(accepted.getRemainingUsd()).isEqualTo(40_000_000L);
        }

        @Test
        void unknown_account_emits_rejected() {
            creditCheck("ORD1", "UNKNOWN", "CLI1", 1_000L);

            CreditRejectedDecoder rejected = eventPublisher.remove();
            then(BufferUtils.toAsciiString(rejected.getOrderId())).isEqualTo("ORD1");
        }

        @Test
        void independent_accounts_do_not_share_consumed() {
            loadSod("ACCT2", 0L, 50_000_000L);
            eventPublisher.removeAll();

            creditCheck("ORD1", "ACCT1", "CLI1", 90_000_000L);
            eventPublisher.removeAll();

            creditCheck("ORD2", "ACCT2", "CLI2", 40_000_000L);

            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(BufferUtils.toAsciiString(accepted.getAccountId())).isEqualTo("ACCT2");
        }
    }

    // ─── Set Credit Limit ─────────────────────────────────────────────────────

    @Nested
    class SetCreditLimitTests {

        @Test
        void set_credit_limit_emits_ack() {
            setLimit("ACCT1", 200_000_000L);

            SetCreditLimitAckDecoder ack = eventPublisher.remove();
            then(BufferUtils.toAsciiString(ack.getAccountId())).isEqualTo("ACCT1");
            then(ack.getLimitUsd()).isEqualTo(200_000_000L);
        }

        @Test
        void increased_limit_allows_larger_orders() {
            // Fill 90M of the 100M limit
            creditCheck("ORD1", "ACCT1", "CLI1", 90_000_000L);
            eventPublisher.removeAll();

            // Increase to 200M
            setLimit("ACCT1", 200_000_000L);
            eventPublisher.removeAll();

            // Now 30M should be accepted (90 + 30 = 120 <= 200)
            creditCheck("ORD2", "ACCT1", "CLI1", 30_000_000L);
            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(accepted.getConsumedAfterUsd()).isEqualTo(120_000_000L);
        }
    }

    // ─── Hard Stop ────────────────────────────────────────────────────────────

    @Nested
    class HardStopTests {

        @Test
        void hard_stop_emits_event() {
            hardStop("ACCT1", "breach detected");

            HardStopDecoder hs = eventPublisher.remove();
            then(BufferUtils.toAsciiString(hs.getAccountId())).isEqualTo("ACCT1");
        }

        @Test
        void hard_stopped_account_orders_are_rejected() {
            hardStop("ACCT1", "test");
            eventPublisher.removeAll();

            creditCheck("ORD1", "ACCT1", "CLI1", 1_000L);

            CreditRejectedDecoder rejected = eventPublisher.remove();
            then(BufferUtils.toAsciiString(rejected.getOrderId())).isEqualTo("ORD1");
        }

        @Test
        void release_hard_stop_emits_event() {
            hardStop("ACCT1", "test");
            eventPublisher.removeAll();

            releaseHardStop("ACCT1");

            HardStopReleasedDecoder rel = eventPublisher.remove();
            then(BufferUtils.toAsciiString(rel.getAccountId())).isEqualTo("ACCT1");
        }

        @Test
        void released_hard_stop_allows_orders() {
            hardStop("ACCT1", "test");
            releaseHardStop("ACCT1");
            eventPublisher.removeAll();

            creditCheck("ORD1", "ACCT1", "CLI1", 10_000_000L);

            CreditAcceptedDecoder accepted = eventPublisher.remove();
            then(BufferUtils.toAsciiString(accepted.getOrderId())).isEqualTo("ORD1");
        }
    }

    @Nested
    class AccountSnapshotReconciliationTests {

        @Test
        void snapshot_ack_emitted_and_reconciled() {
            // 1. Send credit check requests at specific timestamps
            long baselineMs = time.nanos() / 1_000_000L;

            // Trade 1: at baselineMs + 5
            time.advanceTime(Duration.ofMillis(5));
            creditCheck("ORD1", "ACCT1", "CLI1", 2_000_000L);
            eventPublisher.removeAll();

            // Trade 2: at baselineMs + 10
            time.advanceTime(Duration.ofMillis(5));
            creditCheck("ORD2", "ACCT1", "CLI1", 3_000_000L);
            eventPublisher.removeAll();

            // 2. Dispatch snapshot command as of baselineMs + 5.
            // Baseline consumed = 10,000,000.
            // Reconciled should be: baseline (10M) + Trade 2 (3M) = 13,000,000.
            // Trade 1 (2M) was accepted at baselineMs + 5 (not after), so it is pruned.
            accountSnapshot("ACCT1", 100_000_000L, 10_000_000L, baselineMs + 5, 1L);

            AccountSnapshotAckDecoder ack = eventPublisher.remove();
            then(BufferUtils.toAsciiString(ack.getAccountId())).isEqualTo("ACCT1");
            then(ack.getReconciledConsumedUsd()).isEqualTo(13_000_000L);
            then(ack.getReconciledLimitUsd()).isEqualTo(100_000_000L);
            then(ack.getSequenceNo()).isEqualTo(1L);
        }

        @Test
        void stale_snapshots_are_ignored() {
            // Sequence 5
            accountSnapshot("ACCT1", 100_000_000L, 10_000_000L, 1000L, 5L);
            AccountSnapshotAckDecoder ack5 = eventPublisher.remove();
            then(ack5.getReconciledConsumedUsd()).isEqualTo(10_000_000L);

            // Sequence 4 (stale) - should be ignored and reconciled consumed remains same as seq 5 (10M)
            accountSnapshot("ACCT1", 100_000_000L, 20_000_000L, 1050L, 4L);
            then(eventPublisher.isEmpty()).isTrue();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void creditCheck(String orderId, String accountId, String clientId, long notional) {
        var encoder = new CreditCheckRequestEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum)
                .setTimestamp(time.nanos())
                .setOrderId(orderId)
                .setAccountId(accountId)
                .setClientId(clientId)
                .setNotionalUsd(notional)
                .setSide((byte) 0);
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }

    private void setLimit(String accountId, long limitUsd) {
        var encoder = new SetCreditLimitEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum)
                .setTimestamp(time.nanos())
                .setAccountId(accountId)
                .setLimitUsd(limitUsd);
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }

    private void loadSod(String accountId, long consumedUsd, long limitUsd) {
        var encoder = new LoadSodConsumedEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum)
                .setTimestamp(time.nanos())
                .setAccountId(accountId)
                .setConsumedUsd(consumedUsd)
                .setLimitUsd(limitUsd);
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }

    private void hardStop(String accountId, String reason) {
        var encoder = new HardStopEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum)
                .setTimestamp(time.nanos())
                .setAccountId(accountId)
                .setReason(reason);
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }

    private void releaseHardStop(String accountId) {
        var encoder = new HardStopReleasedEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum)
                .setTimestamp(time.nanos())
                .setAccountId(accountId);
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }

    private void accountSnapshot(String accountId, long limitUsd, long consumedUsd, long asOfEpochMs, long seqNo) {
        var encoder = new AccountSnapshotEncoder()
                .setApplicationId(APP_ID)
                .setApplicationSequenceNumber(++appSeqNum)
                .setTimestamp(time.nanos())
                .setAccountId(accountId)
                .setAuthorityLimitUsd(limitUsd)
                .setAuthorityConsumedUsd(consumedUsd)
                .setAsOfEpochMs(asOfEpochMs)
                .setSequenceNo(seqNo);
        busServer.getDispatcher().dispatch(encoder.toDecoder());
        busServer.send();
    }
}
