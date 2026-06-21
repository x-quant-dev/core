package com.core.credit.applications.sequencer;

import com.core.credit.domain.CreditState;
import com.core.credit.domain.DecisionCode;
import com.core.credit.schema.AccountSnapshotAckEncoder;
import com.core.credit.schema.AccountSnapshotDecoder;
import com.core.credit.schema.CreditAcceptedEncoder;
import com.core.credit.schema.CreditCheckRequestDecoder;
import com.core.credit.schema.CreditDispatcher;
import com.core.credit.schema.CreditProvider;
import com.core.credit.schema.CreditRejectedEncoder;
import com.core.credit.schema.HardStopDecoder;
import com.core.credit.schema.HardStopEncoder;
import com.core.credit.schema.HardStopReleasedDecoder;
import com.core.credit.schema.HardStopReleasedEncoder;
import com.core.credit.schema.LoadSodConsumedAckEncoder;
import com.core.credit.schema.LoadSodConsumedDecoder;
import com.core.credit.schema.SetCreditLimitAckEncoder;
import com.core.credit.schema.SetCreditLimitDecoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.platform.bus.BusServer;
import org.agrona.DirectBuffer;

import java.util.Objects;

/**
 * Sequencer-side command handlers for the FX Credit Engine.
 *
 * <p>This class runs inside the sequencer and is the sole place where credit state is
 * mutated. It receives inbound commands ({@code CreditCheckRequest},
 * {@code SetCreditLimit}, {@code HardStop}, {@code HardStopReleased},
 * {@code LoadSodConsumed}), validates them, updates the in-memory {@link CreditState},
 * and publishes result events back onto the event channel.
 *
 * <h2>Design 3 — Atomic Broadcast / Total Order</h2>
 * <p>Because all events flow through a single sequencer, every node that replays the
 * ordered event log will arrive at identical state. There is no possibility of
 * cross-client overshoot by construction — the sequencer sees each order one at a time,
 * and the state is updated before the next order is processed.
 *
 * <h2>Determinism constraint</h2>
 * <p>This class MUST be deterministic. It contains no I/O, no wall-clock reads, and no
 * non-deterministic data structures. The same sequence of commands will always produce
 * the same sequence of events.
 */
public class CreditCommandHandlers implements Encodable {

    private final BusServer<CreditDispatcher, CreditProvider> busServer;
    private final CreditState state;

    // Reusable encoders — allocated once, never on the hot path
    private final CreditAcceptedEncoder acceptedEncoder;
    private final CreditRejectedEncoder rejectedEncoder;
    private final SetCreditLimitAckEncoder limitAckEncoder;
    private final HardStopEncoder hardStopEncoder;
    private final HardStopReleasedEncoder hardStopReleasedEncoder;
    private final LoadSodConsumedAckEncoder sodAckEncoder;
    private final AccountSnapshotAckEncoder snapshotAckEncoder;

    /**
     * Creates a {@code CreditCommandHandlers} and subscribes to all credit commands
     * via the bus-server dispatcher.
     *
     * @param busServer the sequencer bus server
     */
    public CreditCommandHandlers(BusServer<CreditDispatcher, CreditProvider> busServer) {
        this.busServer = Objects.requireNonNull(busServer, "busServer is null");
        this.state = new CreditState();

        acceptedEncoder        = new CreditAcceptedEncoder();
        rejectedEncoder        = new CreditRejectedEncoder();
        limitAckEncoder        = new SetCreditLimitAckEncoder();
        hardStopEncoder        = new HardStopEncoder();
        hardStopReleasedEncoder = new HardStopReleasedEncoder();
        sodAckEncoder          = new LoadSodConsumedAckEncoder();
        snapshotAckEncoder     = new AccountSnapshotAckEncoder();

        var dispatcher = busServer.getDispatcher();
        dispatcher.addCreditCheckRequestListener(this::onCreditCheckRequest);
        dispatcher.addSetCreditLimitListener(this::onSetCreditLimit);
        dispatcher.addHardStopListener(this::onHardStop);
        dispatcher.addHardStopReleasedListener(this::onHardStopReleased);
        dispatcher.addLoadSodConsumedListener(this::onLoadSodConsumed);
        dispatcher.addAccountSnapshotListener(this::onAccountSnapshot);
    }

    /**
     * Hot path — credit check on every incoming order.
     * State is mutated synchronously, result published, no I/O.
     */
    private void onCreditCheckRequest(CreditCheckRequestDecoder decoder) {
        var orderId = decoder.getOrderId();
        if (orderId == null || orderId.capacity() == 0) {
            return; // silently drop malformed commands
        }

        var accountId  = decoder.getAccountId();
        var clientId   = decoder.getClientId();
        var notional   = decoder.getNotionalUsd();

        if (accountId == null || accountId.capacity() == 0 || notional <= 0) {
            publishRejected(decoder, accountId, clientId, notional,
                    DecisionCode.REJECT_UNKNOWN_ACCOUNT, "missing accountId or invalid notional");
            return;
        }

        long timestampMs = decoder.getTimestamp();
        if (timestampMs > 0) {
            timestampMs /= 1_000_000L; // convert nano to milli
        }

        var decision = state.applyOrder(accountId, notional, orderId, timestampMs);

        if (decision == DecisionCode.ACCEPT) {
            var consumed   = state.getConsumed(accountId);
            var remaining  = state.getLimit(accountId) - consumed;
            publishAccepted(decoder, accountId, clientId, notional, consumed, remaining);
        } else {
            publishRejected(decoder, accountId, clientId, notional, decision, decision.name());
        }
    }

    /**
     * Update or set a credit limit for an account.
     */
    private void onSetCreditLimit(SetCreditLimitDecoder decoder) {
        var accountId = decoder.getAccountId();
        var limitUsd  = decoder.getLimitUsd();

        if (accountId == null || accountId.capacity() == 0 || limitUsd < 0) {
            return;
        }

        // Preserve existing consumed; only update the limit
        var existing = state.getConsumed(accountId);
        state.loadAccount(BufferUtils.copy(accountId), limitUsd,
                existing >= 0 ? existing : 0L);

        // Publish ack event
        BusServer.commit(busServer,
                limitAckEncoder.wrap(busServer.acquire())
                        .setApplicationId(decoder.getApplicationId())
                        .setApplicationSequenceNumber(decoder.getApplicationSequenceNumber())
                        .setAccountId(accountId)
                        .setLimitUsd(limitUsd));
    }

    /**
     * Apply a hard stop to freeze an account.
     */
    private void onHardStop(HardStopDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }

        state.applyHardStop(BufferUtils.copy(accountId));

        BusServer.commit(busServer,
                hardStopEncoder.wrap(busServer.acquire())
                        .setApplicationId(decoder.getApplicationId())
                        .setApplicationSequenceNumber(decoder.getApplicationSequenceNumber())
                        .setAccountId(accountId)
                        .setReason(decoder.getReason() != null ? decoder.getReason()
                                : BufferUtils.fromAsciiString("operator")));
    }

    /**
     * Release a hard stop on an account.
     */
    private void onHardStopReleased(HardStopReleasedDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }

        state.releaseHardStop(accountId);

        BusServer.commit(busServer,
                hardStopReleasedEncoder.wrap(busServer.acquire())
                        .setApplicationId(decoder.getApplicationId())
                        .setApplicationSequenceNumber(decoder.getApplicationSequenceNumber())
                        .setAccountId(accountId));
    }

    /**
     * Seed or replay SOD consumed state for an account.
     */
    private void onLoadSodConsumed(LoadSodConsumedDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }

        var consumed  = decoder.getConsumedUsd();
        var limitUsd  = decoder.getLimitUsd();

        state.loadAccount(BufferUtils.copy(accountId), limitUsd, consumed);

        BusServer.commit(busServer,
                sodAckEncoder.wrap(busServer.acquire())
                        .setApplicationId(decoder.getApplicationId())
                        .setApplicationSequenceNumber(decoder.getApplicationSequenceNumber())
                        .setAccountId(accountId)
                        .setConsumedUsd(consumed)
                        .setLimitUsd(limitUsd));
    }

    /**
     * Reconciles authoritative balance snapshot and publishes ack event.
     */
    private void onAccountSnapshot(AccountSnapshotDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }

        var authorityLimit = decoder.getAuthorityLimitUsd();
        var authorityConsumed = decoder.getAuthorityConsumedUsd();
        var asOf = decoder.getAsOfEpochMs();
        var seqNo = decoder.getSequenceNo();

        if (seqNo <= state.getLastSnapshotSeqNo(accountId)) {
            return; // ignore stale snapshot commands
        }

        long reconciledConsumed = state.applyAccountSnapshot(
                accountId, authorityLimit, authorityConsumed, asOf, seqNo);

        BusServer.commit(busServer,
                snapshotAckEncoder.wrap(busServer.acquire())
                        .setApplicationId(decoder.getApplicationId())
                        .setApplicationSequenceNumber(decoder.getApplicationSequenceNumber())
                        .setAccountId(accountId)
                        .setReconciledConsumedUsd(reconciledConsumed)
                        .setReconciledLimitUsd(authorityLimit)
                        .setSequenceNo(seqNo));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private void publishAccepted(CreditCheckRequestDecoder req,
                                  DirectBuffer accountId, DirectBuffer clientId,
                                  long notional, long consumedAfter, long remaining) {
        BusServer.commit(busServer,
                acceptedEncoder.wrap(busServer.acquire())
                        .setApplicationId(req.getApplicationId())
                        .setApplicationSequenceNumber(req.getApplicationSequenceNumber())
                        .setOrderId(req.getOrderId())
                        .setAccountId(accountId)
                        .setClientId(clientId)
                        .setNotionalUsd(notional)
                        .setConsumedAfterUsd(consumedAfter)
                        .setRemainingUsd(remaining));
    }

    private void publishRejected(CreditCheckRequestDecoder req,
                                  DirectBuffer accountId, DirectBuffer clientId,
                                  long notional, DecisionCode code, String reason) {
        var enc = rejectedEncoder.wrap(busServer.acquire())
                .setApplicationId(req.getApplicationId())
                .setApplicationSequenceNumber(req.getApplicationSequenceNumber())
                .setDecisionCode((byte) code.ordinal());

        if (req.getOrderId() != null && req.getOrderId().capacity() > 0) {
            enc.setOrderId(req.getOrderId());
        }
        if (accountId != null && accountId.capacity() > 0) {
            enc.setAccountId(accountId);
        }
        if (clientId != null && clientId.capacity() > 0) {
            enc.setClientId(clientId);
        }
        enc.setNotionalUsd(notional)
           .setReason(reason);

        BusServer.commit(busServer, enc);
    }

    // ─── Shell / monitoring ───────────────────────────────────────────────────

    /**
     * Returns the number of accounts loaded into the credit state.
     *
     * @return account count
     */
    int getAccountCount() {
        return state.getAccountCount();
    }

    /**
     * Returns the credit state for testing and shell introspection.
     *
     * @return the credit state
     */
    CreditState getState() {
        return state;
    }

    /**
     * Encodes the sequencer credit engine status.
     *
     * @param encoder the object encoder
     */
    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("accounts").number(state.getAccountCount())
                .closeMap();
    }
}
