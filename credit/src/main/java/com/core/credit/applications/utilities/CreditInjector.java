package com.core.credit.applications.utilities;

import com.core.infrastructure.Allocation;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.log.LogFactory;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.applications.utilities.Injector;
import com.core.platform.bus.BusClient;

/**
 * Operator/test injection utility for the credit engine.
 *
 * <p>{@code CreditInjector} extends the platform's {@link Injector} to expose
 * typed command methods for injecting credit commands into the sequencer.
 * It is used in:
 * <ul>
 *   <li>integration tests (via {@code TestBusServer.publishCommand});</li>
 *   <li>interactive shell sessions during SOD loading and manual overrides;</li>
 *   <li>automated SOD scripts that seed consumed amounts from the trade log.</li>
 * </ul>
 *
 * <p>All amounts are in <strong>USD cents</strong> (long) to avoid floating-point
 * imprecision. For example, $1,000,000 is represented as {@code 100_000_000L}.
 */
public class CreditInjector extends Injector {

    /**
     * Creates a {@code CreditInjector}.
     *
     * @param logFactory       a factory to create logs
     * @param activatorFactory a factory of activators
     * @param busClient        the bus client used to send commands to the sequencer
     * @param applicationName  the name of this application (for routing)
     */
    public CreditInjector(LogFactory logFactory,
                          ActivatorFactory activatorFactory,
                          BusClient<?, ?> busClient,
                          String applicationName) {
        super(logFactory, activatorFactory, busClient, applicationName);
    }

    /**
     * Sends a {@code CreditCheckRequest} command for a single order.
     *
     * <p>The sequencer will evaluate the request against the account's current
     * credit state and publish either a {@code CreditAccepted} or
     * {@code CreditRejected} event.
     *
     * @param orderId      unique order identifier (ASCII)
     * @param accountId    the credit account to charge against
     * @param clientId     the originating client identifier
     * @param currencyPair the traded currency pair (e.g., {@code "EURUSD"})
     * @param productType  the product type (e.g., {@code "SPOT"}, {@code "FWD"})
     * @param notionalUsd  the order notional in USD cents
     * @param side         order side — {@code 0} = Buy, {@code 1} = Sell
     */
    @Allocation
    @Command
    public void creditCheckRequest(
            String orderId,
            String accountId,
            String clientId,
            String currencyPair,
            String productType,
            long notionalUsd,
            byte side) {
        send("creditCheckRequest",
                "orderId=" + orderId,
                "accountId=" + accountId,
                "clientId=" + clientId,
                "currencyPair=" + currencyPair,
                "productType=" + productType,
                "notionalUsd=" + notionalUsd,
                "side=" + side);
    }

    /**
     * Sends a {@code SetCreditLimit} command to update a master credit limit.
     *
     * <p>The new limit takes effect immediately on the sequencer once the command
     * is processed. Existing consumed amounts are preserved.
     *
     * @param accountId the account identifier
     * @param limitUsd  the new limit in USD cents
     */
    @Allocation
    @Command
    public void setCreditLimit(String accountId, long limitUsd) {
        send("setCreditLimit",
                "accountId=" + accountId,
                "limitUsd=" + limitUsd);
    }

    /**
     * Sends a {@code LoadSodConsumed} command to seed an account's SOD state.
     *
     * <p>Typically called at start-of-day by an automated script that reads the
     * trade log and computes the overnight carried consumed amounts.
     *
     * @param accountId   the account identifier
     * @param consumedUsd the carried-forward consumed amount in USD cents
     * @param limitUsd    the master limit to (re-)apply at SOD
     */
    @Allocation
    @Command
    public void loadSodConsumed(String accountId, long consumedUsd, long limitUsd) {
        send("loadSodConsumed",
                "accountId=" + accountId,
                "consumedUsd=" + consumedUsd,
                "limitUsd=" + limitUsd);
    }

    /**
     * Sends a {@code HardStop} command to immediately freeze an account.
     *
     * <p>All subsequent orders for this account will be rejected with
     * {@code REJECT_HARD_STOP} until {@link #releaseHardStop(String)} is called.
     *
     * @param accountId the account identifier
     * @param reason    a human-readable reason (for the event log)
     */
    @Allocation
    @Command
    public void hardStop(String accountId, String reason) {
        send("hardStop",
                "accountId=" + accountId,
                "reason=" + reason);
    }

    /**
     * Sends a {@code HardStopReleased} command to lift a hard stop.
     *
     * @param accountId the account identifier
     */
    @Allocation
    @Command
    public void releaseHardStop(String accountId) {
        send("hardStopReleased",
                "accountId=" + accountId);
    }

    /**
     * Sends an {@code AccountSnapshot} command to push authoritative balance updates.
     *
     * @param accountId            the account identifier
     * @param authorityLimitUsd    the new credit limit in USD cents
     * @param authorityConsumedUsd the baseline consumed amount in USD cents
     * @param asOfEpochMs          the snapshot point-in-time timestamp
     * @param sequenceNo           the snapshot sequence number
     */
    @Allocation
    @Command
    public void accountSnapshot(
            String accountId,
            long authorityLimitUsd,
            long authorityConsumedUsd,
            long asOfEpochMs,
            long sequenceNo) {
        send("accountSnapshot",
                "accountId=" + accountId,
                "authorityLimitUsd=" + authorityLimitUsd,
                "authorityConsumedUsd=" + authorityConsumedUsd,
                "asOfEpochMs=" + asOfEpochMs,
                "sequenceNo=" + sequenceNo);
    }
}
