package com.core.platform.bus;

import com.core.infrastructure.messages.Dispatcher;
import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.messages.Provider;
import com.core.infrastructure.messages.Schema;
import com.core.infrastructure.time.Time;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

import java.util.function.Consumer;

public class TestBusServer<DispatcherT extends Dispatcher, ProviderT extends Provider>
        extends AbstractBusServer<DispatcherT, ProviderT>
        implements BusServer<DispatcherT, ProviderT> {

    private final TestMessagePublisher eventPublisher;
    private final Activator activator;
    private final Time time;
    @SuppressWarnings("unchecked")
    private Consumer<DirectBuffer>[] eventListeners = new Consumer[0];
    private Consumer<DirectBuffer> commandListener;
    private MutableDirectBuffer messageBuffer;

    public TestBusServer(
            Time time, Schema<DispatcherT, ProviderT> schema, ActivatorFactory activatorFactory) {
        super(schema);
        this.time = time;

        eventPublisher = new TestMessagePublisher(activatorFactory, schema, 1, false);

        activator = activatorFactory.createActivator("TestBusServer", this, eventPublisher);
        activator.ready();
    }

    @Override
    public MutableDirectBuffer acquire() {
        messageBuffer = eventPublisher.acquire();
        return messageBuffer;
    }

    @Override
    public void commit(int msgLength) {
        if (activator.isActive()) {
            messageBuffer.putLong(getSchema().getTimestampOffset(), time.nanos());
            messageBuffer.putInt(getSchema().getLeaderEpochOffset(), getLeaderEpoch());
        }
        eventPublisher.commit(msgLength);
    }

    @Override
    public void commit(int msgLength, long timestamp) {
        if (activator.isActive()) {
            messageBuffer.putLong(getSchema().getTimestampOffset(), timestamp);
            messageBuffer.putInt(getSchema().getLeaderEpochOffset(), getLeaderEpoch());
        }
        eventPublisher.commit(msgLength);
    }

    @Override
    public boolean isActive() {
        return activator.isActive();
    }

    @Override
    public void send() {
        eventPublisher.send();
    }

    @Override
    public void addEventListener(Consumer<DirectBuffer> eventListener) {
        this.eventListeners = java.util.Arrays.copyOf(eventListeners, eventListeners.length + 1);
        eventListeners[eventListeners.length - 1] = eventListener;
    }

    @Override
    public void setCommandListener(Consumer<DirectBuffer> commandListener) {
        this.commandListener = commandListener;
    }

    public TestMessagePublisher getEventPublisher() {
        return eventPublisher;
    }

    public void publishEvent(DirectBuffer event) {
        if (!activator.isActive()) {
            for (var listener : eventListeners) {
                listener.accept(event);
            }
        }
    }

    public void publishEvent(Encoder decoder) {
        publishEvent(decoder.toDecoder().buffer());
    }

    public void publishCommand(Encoder encoder) {
        publishCommand(encoder.toDecoder().buffer());
    }

    public void publishCommand(DirectBuffer command) {
        if (activator.isActive()) {
            commandListener.accept(command);
        }
    }
}
