package com.core.credit.domain;

import com.core.infrastructure.buffer.BufferUtils;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * Unit tests for {@link CreditState}.
 *
 * <p>These tests exercise the pure domain state machine in isolation — no bus,
 * no schema, no I/O. They verify all credit decision paths and state transitions.
 */
class CreditStateTest {

    private static final DirectBuffer ACCOUNT_A = BufferUtils.fromAsciiString("ACCOUNT_A");
    private static final DirectBuffer ACCOUNT_B = BufferUtils.fromAsciiString("ACCOUNT_B");
    private static final long LIMIT_1M = 100_000_000L;   // $1,000,000 in cents

    private CreditState state;

    @BeforeEach
    void before_each() {
        state = new CreditState();
    }

    @Nested
    class LoadAccountTests {

        @Test
        void unknown_account_is_rejected() {
            var decision = state.applyOrder(ACCOUNT_A, 1_000_000L);
            then(decision).isEqualTo(DecisionCode.REJECT_UNKNOWN_ACCOUNT);
        }

        @Test
        void loaded_account_starts_with_sod_consumed() {
            state.loadAccount(ACCOUNT_A, LIMIT_1M, 20_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(20_000_000L);
            then(state.getLimit(ACCOUNT_A)).isEqualTo(LIMIT_1M);
        }

        @Test
        void loading_same_account_twice_overwrites_limit() {
            state.loadAccount(ACCOUNT_A, LIMIT_1M, 0L);
            state.loadAccount(ACCOUNT_A, 200_000_000L, 50_000_000L);
            then(state.getLimit(ACCOUNT_A)).isEqualTo(200_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(50_000_000L);
        }

        @Test
        void multiple_accounts_are_independent() {
            state.loadAccount(ACCOUNT_A, LIMIT_1M, 0L);
            state.loadAccount(ACCOUNT_B, 50_000_000L, 10_000_000L);
            then(state.getLimit(ACCOUNT_A)).isEqualTo(LIMIT_1M);
            then(state.getLimit(ACCOUNT_B)).isEqualTo(50_000_000L);
        }
    }

    @Nested
    class CreditCheckTests {

        @BeforeEach
        void setup() {
            state.loadAccount(ACCOUNT_A, LIMIT_1M, 0L);
        }

        @Test
        void order_within_limit_is_accepted() {
            var decision = state.applyOrder(ACCOUNT_A, 50_000_000L);
            then(decision).isEqualTo(DecisionCode.ACCEPT);
        }

        @Test
        void accepted_order_updates_consumed() {
            state.applyOrder(ACCOUNT_A, 50_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(50_000_000L);
        }

        @Test
        void order_exactly_at_limit_is_accepted() {
            var decision = state.applyOrder(ACCOUNT_A, LIMIT_1M);
            then(decision).isEqualTo(DecisionCode.ACCEPT);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(LIMIT_1M);
        }

        @Test
        void order_exceeding_limit_is_rejected() {
            var decision = state.applyOrder(ACCOUNT_A, LIMIT_1M + 1L);
            then(decision).isEqualTo(DecisionCode.REJECT_LIMIT_EXCEEDED);
        }

        @Test
        void rejected_order_does_not_update_consumed() {
            state.applyOrder(ACCOUNT_A, LIMIT_1M + 1L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(0L);
        }

        @Test
        void sequential_orders_accumulate_consumed() {
            state.applyOrder(ACCOUNT_A, 30_000_000L);
            state.applyOrder(ACCOUNT_A, 30_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(60_000_000L);
        }

        @Test
        void cumulative_orders_that_exceed_limit_are_rejected() {
            state.applyOrder(ACCOUNT_A, 80_000_000L); // consumed = 80M
            var decision = state.applyOrder(ACCOUNT_A, 30_000_000L); // would be 110M > 100M
            then(decision).isEqualTo(DecisionCode.REJECT_LIMIT_EXCEEDED);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(80_000_000L);
        }

        @Test
        void increasing_limit_allows_previously_rejected_size() {
            state.applyOrder(ACCOUNT_A, 80_000_000L);
            state.applyMarginUpdate(ACCOUNT_A, 200_000_000L);
            var decision = state.applyOrder(ACCOUNT_A, 60_000_000L); // 80 + 60 = 140 < 200
            then(decision).isEqualTo(DecisionCode.ACCEPT);
        }

        @Test
        void unknown_account_returns_minus_one_for_consumed() {
            then(state.getConsumed(ACCOUNT_B)).isEqualTo(-1L);
        }

        @Test
        void account_count_grows_with_each_new_load() {
            then(state.getAccountCount()).isEqualTo(1);
            state.loadAccount(ACCOUNT_B, 50_000_000L, 0L);
            then(state.getAccountCount()).isEqualTo(2);
        }
    }

    @Nested
    class HardStopTests {

        @BeforeEach
        void setup() {
            state.loadAccount(ACCOUNT_A, LIMIT_1M, 0L);
        }

        @Test
        void hard_stopped_account_is_rejected() {
            state.applyHardStop(ACCOUNT_A);
            var decision = state.applyOrder(ACCOUNT_A, 1_000L);
            then(decision).isEqualTo(DecisionCode.REJECT_HARD_STOP);
        }

        @Test
        void hard_stop_does_not_change_consumed() {
            state.applyOrder(ACCOUNT_A, 10_000_000L);
            state.applyHardStop(ACCOUNT_A);
            state.applyOrder(ACCOUNT_A, 5_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(10_000_000L);
        }

        @Test
        void releasing_hard_stop_allows_orders_again() {
            state.applyHardStop(ACCOUNT_A);
            state.releaseHardStop(ACCOUNT_A);
            var decision = state.applyOrder(ACCOUNT_A, 1_000_000L);
            then(decision).isEqualTo(DecisionCode.ACCEPT);
        }

        @Test
        void isHardStopped_is_false_initially() {
            then(state.isHardStopped(ACCOUNT_A)).isFalse();
        }

        @Test
        void isHardStopped_is_true_after_stop() {
            state.applyHardStop(ACCOUNT_A);
            then(state.isHardStopped(ACCOUNT_A)).isTrue();
        }

        @Test
        void isHardStopped_is_false_after_release() {
            state.applyHardStop(ACCOUNT_A);
            state.releaseHardStop(ACCOUNT_A);
            then(state.isHardStopped(ACCOUNT_A)).isFalse();
        }

        @Test
        void hard_stop_on_one_account_does_not_affect_other() {
            state.loadAccount(ACCOUNT_B, LIMIT_1M, 0L);
            state.applyHardStop(ACCOUNT_A);
            var decision = state.applyOrder(ACCOUNT_B, 1_000_000L);
            then(decision).isEqualTo(DecisionCode.ACCEPT);
        }
    }

    @Nested
    class SnapshotReconciliationTests {

        private final DirectBuffer ORDER_1 = BufferUtils.fromAsciiString("ORD_1");
        private final DirectBuffer ORDER_2 = BufferUtils.fromAsciiString("ORD_2");
        private final DirectBuffer ORDER_3 = BufferUtils.fromAsciiString("ORD_3");

        @BeforeEach
        void setup() {
            state.loadAccount(ACCOUNT_A, LIMIT_1M, 0L);
        }

        @Test
        void initial_snapshot_applies_limit_and_consumed() {
            state.applyAccountSnapshot(ACCOUNT_A, LIMIT_1M, 15_000_000L, 1000L, 1L);

            then(state.getLimit(ACCOUNT_A)).isEqualTo(LIMIT_1M);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(15_000_000L);
            then(state.getLastSnapshotSeqNo(ACCOUNT_A)).isEqualTo(1L);
            then(state.getLastSnapshotAsOfMs(ACCOUNT_A)).isEqualTo(1000L);
        }

        @Test
        void applyOrder_with_tracking_records_recent_trades() {
            state.applyOrder(ACCOUNT_A, 5_000_000L, ORDER_1, 1005L);
            state.applyOrder(ACCOUNT_A, 3_000_000L, ORDER_2, 1010L);

            then(state.getRecentTradeCount(ACCOUNT_A)).isEqualTo(2);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(8_000_000L);
        }

        @Test
        void snapshot_reconciles_adding_only_trades_after_asOf() {
            // 1. Accept trades at different timestamps
            state.applyOrder(ACCOUNT_A, 1_000_000L, ORDER_1, 990L);  // Before/At asOf limit
            state.applyOrder(ACCOUNT_A, 2_000_000L, ORDER_2, 1000L); // At asOf limit (inclusive)
            state.applyOrder(ACCOUNT_A, 3_000_000L, ORDER_3, 1005L); // Strictly after asOf limit

            then(state.getConsumed(ACCOUNT_A)).isEqualTo(6_000_000L);

            // 2. Apply snapshot as of 1000L. Confirmed baseline consumed = 5,000,000.
            // Reconciled should be: baseline (5M) + in-flight after 1000L (ORDER_3 = 3M) = 8,000,000.
            var reconciled = state.applyAccountSnapshot(ACCOUNT_A, LIMIT_1M, 5_000_000L, 1000L, 1L);

            then(reconciled).isEqualTo(8_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(8_000_000L);

            // 3. Stale trades (ORDER_1, ORDER_2) should be pruned
            then(state.getRecentTradeCount(ACCOUNT_A)).isEqualTo(1);
        }

        @Test
        void stale_or_duplicate_snapshots_are_ignored() {
            state.applyAccountSnapshot(ACCOUNT_A, LIMIT_1M, 10_000_000L, 1000L, 5L);

            // Stale sequence number (4 <= 5) should be ignored, leaving consumed unchanged
            var reconciledStale = state.applyAccountSnapshot(ACCOUNT_A, LIMIT_1M, 20_000_000L, 1050L, 4L);
            then(reconciledStale).isEqualTo(10_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(10_000_000L);
            then(state.getLastSnapshotSeqNo(ACCOUNT_A)).isEqualTo(5L);

            // Duplicate sequence number (5 <= 5) should also be ignored
            var reconciledDup = state.applyAccountSnapshot(ACCOUNT_A, LIMIT_1M, 20_000_000L, 1050L, 5L);
            then(reconciledDup).isEqualTo(10_000_000L);
            then(state.getConsumed(ACCOUNT_A)).isEqualTo(10_000_000L);
        }
    }
}
