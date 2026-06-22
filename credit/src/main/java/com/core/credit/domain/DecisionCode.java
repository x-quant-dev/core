package com.core.credit.domain;

/**
 * Outcome codes for a credit decision.
 *
 * <p>Encoded as a {@code byte} on the wire (ordinal value).
 */
public enum DecisionCode {

    /** Order was accepted; credit has been reserved. */
    ACCEPT,

    /** Order was rejected because consumed + notional would exceed the master limit. */
    REJECT_LIMIT_EXCEEDED,

    /** Order was rejected because the account is under a hard stop (circuit breaker). */
    REJECT_HARD_STOP,

    /** Order was rejected because the account is unknown (no limit loaded). */
    REJECT_UNKNOWN_ACCOUNT
}
