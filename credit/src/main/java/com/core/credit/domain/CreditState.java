package com.core.credit.domain;

import org.agrona.DirectBuffer;

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
        return DecisionCode.ACCEPT;
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
     * Returns the number of accounts loaded into the state machine.
     *
     * @return account count
     */
    public int getAccountCount() {
        return masterLimit.size();
    }
}
