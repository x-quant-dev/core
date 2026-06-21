package com.core.credit.domain;

import org.agrona.DirectBuffer;

/**
 * Represents an accepted trade for rolling window reconciliation.
 */
public class AcceptedTrade {
    private final DirectBuffer orderId;
    private final long notionalUsd;
    private final long acceptedAtMs;

    /**
     * Creates an {@code AcceptedTrade}.
     *
     * @param orderId      the unique order identifier buffer
     * @param notionalUsd  the trade notional in USD cents
     * @param acceptedAtMs the trade execution timestamp in epoch milliseconds
     */
    public AcceptedTrade(DirectBuffer orderId, long notionalUsd, long acceptedAtMs) {
        this.orderId = orderId;
        this.notionalUsd = notionalUsd;
        this.acceptedAtMs = acceptedAtMs;
    }

    public DirectBuffer orderId() {
        return orderId;
    }

    public long notionalUsd() {
        return notionalUsd;
    }

    public long acceptedAtMs() {
        return acceptedAtMs;
    }
}
