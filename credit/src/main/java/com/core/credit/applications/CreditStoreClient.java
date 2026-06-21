package com.core.credit.applications;

import com.core.credit.domain.DecisionCode;
import com.core.credit.schema.CreditAcceptedDecoder;
import com.core.credit.schema.CreditDispatcher;
import com.core.credit.schema.CreditProvider;
import com.core.credit.schema.CreditRejectedDecoder;
import com.core.credit.schema.HardStopDecoder;
import com.core.credit.schema.HardStopReleasedDecoder;
import com.core.credit.schema.LoadSodConsumedAckDecoder;
import com.core.credit.schema.SetCreditLimitAckDecoder;
import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.platform.bus.BusClient;
import org.agrona.DirectBuffer;
import org.eclipse.collections.impl.map.mutable.UnifiedMap;

import java.util.Objects;

/**
 * Client-side view of the credit engine state, materialised from the ordered event stream.
 *
 * <p>This is the <em>passive replica</em> component (Design 3). It listens for events
 * that the sequencer has published ({@code CreditAccepted}, {@code CreditRejected},
 * {@code SetCreditLimitAck}, {@code HardStop}, etc.) and mirrors them into a local
 * read-only in-memory store. Any downstream application — risk monitoring, reporting,
 * trading OMS — can construct a {@code CreditStoreClient} and query current account
 * state without sending commands.
 *
 * <h2>Key properties</h2>
 * <ul>
 *   <li>Read-only: this class never publishes commands.</li>
 *   <li>Eventually consistent: state reflects the last event processed from the
 *       sequencer event stream.</li>
 *   <li>Deterministic replay: replaying the event log from the start will produce
 *       identical final state.</li>
 * </ul>
 */
public class CreditStoreClient implements Encodable {

    /** Map from accountId (DirectBuffer) → consumed USD cents. */
    private final UnifiedMap<DirectBuffer, Long> consumed = new UnifiedMap<>();
    /** Map from accountId (DirectBuffer) → limit USD cents. */
    private final UnifiedMap<DirectBuffer, Long> limits = new UnifiedMap<>();
    /** Set of accounts currently under a hard stop. */
    private final UnifiedMap<DirectBuffer, Boolean> hardStopped = new UnifiedMap<>();

    private int acceptedCount;
    private int rejectedCount;

    /**
     * Creates a {@code CreditStoreClient} and subscribes to credit events from the bus.
     *
     * @param busClient the bus client to subscribe on
     */
    public CreditStoreClient(BusClient<?, ?> busClient) {
        Objects.requireNonNull(busClient, "busClient is null");

        var dispatcher = (CreditDispatcher) busClient.getDispatcher();
        dispatcher.addCreditAcceptedListener(this::onCreditAccepted);
        dispatcher.addCreditRejectedListener(this::onCreditRejected);
        dispatcher.addSetCreditLimitAckListener(this::onSetCreditLimitAck);
        dispatcher.addHardStopListener(this::onHardStop);
        dispatcher.addHardStopReleasedListener(this::onHardStopReleased);
        dispatcher.addLoadSodConsumedAckListener(this::onLoadSodConsumedAck);
    }

    // ─── Event listeners ──────────────────────────────────────────────────────

    private void onCreditAccepted(CreditAcceptedDecoder decoder) {
        acceptedCount++;
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }
        consumed.put(BufferUtils.copy(accountId), decoder.getConsumedAfterUsd());
    }

    private void onCreditRejected(CreditRejectedDecoder decoder) {
        rejectedCount++;
        // Rejected orders do not change consumed amounts — state is unchanged.
    }

    private void onSetCreditLimitAck(SetCreditLimitAckDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }
        limits.put(BufferUtils.copy(accountId), decoder.getLimitUsd());
    }

    private void onHardStop(HardStopDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }
        hardStopped.put(BufferUtils.copy(accountId), Boolean.TRUE);
    }

    private void onHardStopReleased(HardStopReleasedDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId != null) {
            hardStopped.remove(accountId);
        }
    }

    private void onLoadSodConsumedAck(LoadSodConsumedAckDecoder decoder) {
        var accountId = decoder.getAccountId();
        if (accountId == null || accountId.capacity() == 0) {
            return;
        }
        var key = BufferUtils.copy(accountId);
        limits.put(key, decoder.getLimitUsd());
        consumed.put(key, decoder.getConsumedUsd());
    }

    // ─── Queries ──────────────────────────────────────────────────────────────

    /**
     * Returns the consumed amount in USD cents for the given account, or -1 if unknown.
     *
     * @param accountId the account identifier (ASCII string buffer)
     * @return consumed USD cents or -1
     */
    @Command(readOnly = true)
    public long getConsumed(DirectBuffer accountId) {
        var val = consumed.get(accountId);
        return val == null ? -1L : val;
    }

    /**
     * Returns the master credit limit in USD cents for the given account, or -1 if unknown.
     *
     * @param accountId the account identifier
     * @return limit USD cents or -1
     */
    @Command(readOnly = true)
    public long getLimit(DirectBuffer accountId) {
        var val = limits.get(accountId);
        return val == null ? -1L : val;
    }

    /**
     * Returns whether the given account is under a hard stop.
     *
     * @param accountId the account identifier
     * @return true if hard-stopped
     */
    @Command(readOnly = true)
    public boolean isHardStopped(DirectBuffer accountId) {
        return Boolean.TRUE.equals(hardStopped.get(accountId));
    }

    /**
     * Returns the remaining credit (limit − consumed) for an account, or -1 if unknown.
     *
     * @param accountId the account identifier
     * @return remaining USD cents or -1
     */
    @Command(readOnly = true)
    public long getRemaining(DirectBuffer accountId) {
        var lim = getLimit(accountId);
        var con = getConsumed(accountId);
        return (lim < 0 || con < 0) ? -1L : lim - con;
    }

    /**
     * Encodes a summary of this client's materialised state.
     *
     * @param encoder the encoder
     */
    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("accounts").number(limits.size())
                .string("hardStopped").number(hardStopped.size())
                .string("accepted").number(acceptedCount)
                .string("rejected").number(rejectedCount)
                .closeMap();
    }
}
