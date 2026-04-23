package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.messages.MessagePublisher;
import com.core.infrastructure.messages.Provider;

import java.util.Map;

/**
 * A provider for SBE-encoded messages.
 *
 * <p>Wraps a {@code MessagePublisher} and provides {@code SbeEncoder} instances for each message type.
 */
public class SbeProvider implements Provider {

    private final MessagePublisher messagePublisher;
    private final Map<String, SbeEncoder> encoders;

    /**
     * Creates an {@code SbeProvider}.
     *
     * @param messagePublisher the message publisher
     * @param encoders map of message name to encoder
     */
    public SbeProvider(MessagePublisher messagePublisher, Map<String, SbeEncoder> encoders) {
        this.messagePublisher = messagePublisher;
        this.encoders = encoders;
    }

    @Override
    public Encoder getEncoder(String messageName) {
        var encoder = encoders.get(messageName);
        if (encoder == null) {
            throw new IllegalArgumentException("invalid message name: " + messageName);
        }
        return encoder.wrap(messagePublisher.acquire());
    }

    @Override
    public void send() {
        messagePublisher.send();
    }

    @Override
    public MessagePublisher getMessagePublisher() {
        return messagePublisher;
    }
}
