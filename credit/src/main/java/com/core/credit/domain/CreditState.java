package com.core.credit.domain;

import org.agrona.DirectBuffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure in-memory credit state machine.
 *
 * <p>This class is the sequencer's authoritative ledger of credit consumed per account.
 * It is designed to be fully deterministic — no I/O, no wall-clock reads, no randomness.
 * The same sequence of calls on the primary and any backup node will always produce
 * identical state.
 *
 * <h2>Key invariants</h2>
 * <ul>
 *   <li>All amounts are in USD cents (long) to avoid floating-point rounding.</li>
 *   <li>An account must be loaded via {@link #loadAccount} before orders can be checked.</li>
 *   <li>A hard-stopped account rejects all orders until {@link #releaseHardStop} is called.</li>
 * </ul>
 */
public class CreditState {

    private final Map<DirectBuffer, Long> masterLimit = new HashMap<>();
    private final Map<DirectBuffer, Long> consumed    = new HashMap<>();
    private final Map<DirectBuffer, Boolean> hardStop = new HashMap<>();

    // 24x7x365 additions
    private final Map<DirectBuffer, Long> lastSnapshotSeqNo = new HashMap<>();
    private final Map<DirectBuffer, Long> lastSnapshotAsOfMs = new HashMap<>();
    private final Map<DirectBuffer, Deque<AcceptedTrade>> recentTrades = new HashMap<>();

    /**
     * Load or refresh an account's credit state (called at SOD or on limit update).
     *
     * @param accountId     the account identifier buffer (must be a stable, copied key)
     * @param limitUsd      the master credit limit in USD cents
     * @param sodConsumedUsd the consumed amount at start-of-day in USD cents
     */
    public void loadAccount(DirectBuffer accountId, long limitUsd, long sodConsumedUsd) {
        masterLimit.put(accountId, limitUsd);
        consumed.put(accountId, sodConsumedUsd);
        // do not clear a hard stop — it remains until explicitly released
    }

    /**
     * Update the master credit limit for an account.
     *
     * @param accountId the account identifier buffer (stable, copied key)
     * @param limitUsd  the new limit in USD cents
     */
    public void applyMarginUpdate(DirectBuffer accountId, long limitUsd) {
        masterLimit.put(accountId, limitUsd);
    }

    /**
     * Apply an order credit check — the core hot path.
     *
     * <p>If accepted, the consumed amount is updated atomically (within this call).
     * Rejected orders leave state unchanged.
     *
     * @param accountId  the account identifier buffer
     * @param notionalUsd the order notional in USD cents
     * @return the {@link DecisionCode} describing the outcome
     */
    public DecisionCode applyOrder(DirectBuffer accountId, long notionalUsd) {
        return applyOrder(accountId, notionalUsd, null, 0L);
    }

    /**
     * Apply an order credit check with tracking for rolling window reconciliation.
     *
     * @param accountId    the account identifier buffer
     * @param notionalUsd  the order notional in USD cents
     * @param orderId      the unique order identifier
     * @param acceptedAtMs the timestamp of the check in epoch milliseconds
     * @return the {@link DecisionCode} describing the outcome
     */
    public DecisionCode applyOrder(DirectBuffer accountId, long notionalUsd, DirectBuffer orderId, long acceptedAtMs) {
        if (Boolean.TRUE.equals(hardStop.get(accountId))) {
            return DecisionCode.REJECT_HARD_STOP;
        }

        var limit = masterLimit.get(accountId);
        if (limit == null) {
            return DecisionCode.REJECT_UNKNOWN_ACCOUNT;
        }

        var cons = consumed.getOrDefault(accountId, 0L);
        if (cons + notionalUsd > limit) {
            return DecisionCode.REJECT_LIMIT_EXCEEDED;
        }

        // Mutate state — identical on all nodes because inputs are identical.
        consumed.put(accountId, cons + notionalUsd);

        // Record recent trade if tracking is active
        if (orderId != null && acceptedAtMs > 0) {
            var trades = recentTrades.computeIfAbsent(accountId, k -> new ArrayDeque<>());
            trades.addLast(new AcceptedTrade(
                    com.core.infrastructure.buffer.BufferUtils.copy(orderId),
                    notionalUsd,
                    acceptedAtMs
            ));
        }

        return DecisionCode.ACCEPT;
    }

    /**
     * Apply an authoritative account balance snapshot and reconcile in-flight trades.
     *
     * @param accountId            the account identifier
     * @param authorityLimitUsd    the new credit limit
     * @param authorityConsumedUsd the new consumed baseline from risk/back-office
     * @param asOfEpochMs          point-in-time timestamp of the authority baseline
     * @param sequenceNo           monotonic sequence number of the snapshot
     * @return the reconciled consumed amount in USD cents
     */
    public long applyAccountSnapshot(DirectBuffer accountId, long authorityLimitUsd, long authorityConsumedUsd, long asOfEpochMs, long sequenceNo) {
        var lastSeq = lastSnapshotSeqNo.getOrDefault(accountId, -1L);
        if (sequenceNo <= lastSeq) {
            return consumed.getOrDefault(accountId, 0L);
        }

        // Sum trades accepted strictly after the snapshot's asOf timestamp
        long inFlight = 0;
        var trades = recentTrades.get(accountId);
        if (trades != null) {
            for (var t : trades) {
                if (t.acceptedAtMs() > asOfEpochMs) {
                    inFlight += t.notionalUsd();
                }
            }
            // Prune trades older than or equal to asOfEpochMs
            trades.removeIf(t -> t.acceptedAtMs() <= asOfEpochMs);
        }

        long reconciledConsumed = authorityConsumedUsd + inFlight;

        // Ensure key is stable and copied if it's the first time we load the account
        DirectBuffer storedKey = null;
        for (var k : masterLimit.keySet()) {
            if (k.equals(accountId)) {
                storedKey = k;
                break;
            }
        }
        if (storedKey == null) {
            storedKey = com.core.infrastructure.buffer.BufferUtils.copy(accountId);
        }

        masterLimit.put(storedKey, authorityLimitUsd);
        consumed.put(storedKey, reconciledConsumed);
        lastSnapshotSeqNo.put(storedKey, sequenceNo);
        lastSnapshotAsOfMs.put(storedKey, asOfEpochMs);

        return reconciledConsumed;
    }

    /**
     * Place a hard stop on an account, blocking all further orders.
     *
     * @param accountId the account identifier buffer (stable, copied key)
     */
    public void applyHardStop(DirectBuffer accountId) {
        hardStop.put(accountId, Boolean.TRUE);
    }

    /**
     * Release a hard stop on an account, allowing orders to be accepted again.
     *
     * @param accountId the account identifier buffer
     */
    public void releaseHardStop(DirectBuffer accountId) {
        hardStop.remove(accountId);
    }

    /**
     * Returns the amount consumed in USD cents for the given account, or -1 if unknown.
     *
     * @param accountId the account identifier buffer
     * @return consumed USD cents or -1 if account not loaded
     */
    public long getConsumed(DirectBuffer accountId) {
        var cons = consumed.get(accountId);
        return cons == null ? -1L : cons;
    }

    /**
     * Returns the master limit in USD cents for the given account, or -1 if unknown.
     *
     * @param accountId the account identifier buffer
     * @return limit USD cents or -1 if account not loaded
     */
    public long getLimit(DirectBuffer accountId) {
        var limit = masterLimit.get(accountId);
        return limit == null ? -1L : limit;
    }

    /**
     * Returns whether the given account is under a hard stop.
     *
     * @param accountId the account identifier buffer
     * @return true if the account is hard-stopped
     */
    public boolean isHardStopped(DirectBuffer accountId) {
        return Boolean.TRUE.equals(hardStop.get(accountId));
    }

    /**
     * Returns the last applied snapshot sequence number, or -1 if none applied yet.
     *
     * @param accountId the account identifier buffer
     * @return sequence number or -1
     */
    public long getLastSnapshotSeqNo(DirectBuffer accountId) {
        return lastSnapshotSeqNo.getOrDefault(accountId, -1L);
    }

    /**
     * Returns the last applied snapshot as-of timestamp in epoch milliseconds, or 0 if none.
     *
     * @param accountId the account identifier buffer
     * @return epoch milliseconds or 0
     */
    public long getLastSnapshotAsOfMs(DirectBuffer accountId) {
        return lastSnapshotAsOfMs.getOrDefault(accountId, 0L);
    }

    /**
     * Returns the number of recent trades currently held for reconciliation.
     *
     * @param accountId the account identifier buffer
     * @return trade count
     */
    public int getRecentTradeCount(DirectBuffer accountId) {
        var trades = recentTrades.get(accountId);
        return trades == null ? 0 : trades.size();
    }

    /**
     * Returns the number of accounts loaded into the state machine.
     *
     * @return account count
     */
    public int getAccountCount() {
        return masterLimit.size();
    }
}
