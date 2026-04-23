package com.core.platform.applications.sequencer;

import org.agrona.DirectBuffer;

import java.util.function.Consumer;

/**
 * Source of passive events for backup sync validation.
 * Abstracts over BusServer event listening and external event subscriptions (e.g., AeronBusClient).
 */
public interface PassiveEventSource {
    void addEventListener(Consumer<DirectBuffer> listener);
}
