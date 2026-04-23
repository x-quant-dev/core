package com.core.platform.bus.aeron;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.io.Selector;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.messages.Dispatcher;
import com.core.infrastructure.messages.Provider;
import com.core.infrastructure.messages.Schema;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.Time;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.AbstractBusServer;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Aeron-based bus server implementation.
 */
public class AeronBusServer<DispatcherT extends Dispatcher, ProviderT extends Provider>
        extends AbstractBusServer<DispatcherT, ProviderT>
        implements Encodable {

    private static final int FRAGMENT_LIMIT = 10;

    private final Aeron aeron;
    private final Publication eventPublication;
    private final Subscription commandSubscription;
    private final AeronSession session;
    @Directory(path = ".")
    private final Activator activator;
    private final Log log;
    private final Time time;
    private final int timestampOffset;
    private final int leaderEpochOffset;
    private final MutableDirectBuffer publishBuffer;
    private final BufferClaim bufferClaim;
    private final FragmentHandler commandFragmentHandler;

    private Consumer<DirectBuffer> commandListener;
    private MutableDirectBuffer messageBuffer;
    @Property
    private long publicationFailCount;

    /**
     * Creates an {@code AeronBusServer} with an external Aeron directory.
     *
     * @param selector the Aeron selector
     * @param time the time source
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param schema the message schema
     * @param aeronDirectory the Aeron media driver directory
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     * @param commandChannel the command channel URI
     * @param commandStreamId the command stream identifier
     * @param sessionName the session name
     */
    public AeronBusServer(
            Selector selector,
            Time time,
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            Schema<DispatcherT, ProviderT> schema,
            String aeronDirectory,
            String eventChannel,
            int eventStreamId,
            String commandChannel,
            int commandStreamId,
            String sessionName) {
        this(
                selector,
                time,
                logFactory,
                metricFactory,
                activatorFactory,
                schema,
                new ExternalAeronDirectoryProvider(aeronDirectory),
                eventChannel,
                eventStreamId,
                commandChannel,
                commandStreamId,
                sessionName);
    }

    /**
     * Creates an {@code AeronBusServer} with an embedded Aeron driver.
     *
     * @param selector the Aeron selector
     * @param time the time source
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param schema the message schema
     * @param embeddedDriver the embedded Aeron driver
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     * @param commandChannel the command channel URI
     * @param commandStreamId the command stream identifier
     * @param sessionName the session name
     */
    public AeronBusServer(
            Selector selector,
            Time time,
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            Schema<DispatcherT, ProviderT> schema,
            EmbeddedAeronDriver embeddedDriver,
            String eventChannel,
            int eventStreamId,
            String commandChannel,
            int commandStreamId,
            String sessionName) {
        this(
                selector,
                time,
                logFactory,
                metricFactory,
                activatorFactory,
                schema,
                embeddedDriver.asDirectoryProvider(),
                eventChannel,
                eventStreamId,
                commandChannel,
                commandStreamId,
                sessionName);
    }

    /**
     * Creates an {@code AeronBusServer} with the specified directory provider.
     *
     * @param selector the Aeron selector
     * @param time the time source
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param schema the message schema
     * @param directoryProvider the Aeron directory provider
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     * @param commandChannel the command channel URI
     * @param commandStreamId the command stream identifier
     * @param sessionName the session name
     * @throws IllegalArgumentException if stream IDs are not positive
     */
    public AeronBusServer(
            Selector selector,
            Time time,
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            Schema<DispatcherT, ProviderT> schema,
            AeronDirectoryProvider directoryProvider,
            String eventChannel,
            int eventStreamId,
            String commandChannel,
            int commandStreamId,
            String sessionName) {
        super(schema);
        Objects.requireNonNull(selector, "selector is null");
        this.time = Objects.requireNonNull(time, "time is null");
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        Objects.requireNonNull(activatorFactory, "activationManager is null");
        Objects.requireNonNull(schema, "schema is null");
        Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        Objects.requireNonNull(eventChannel, "eventChannel is null");
        Objects.requireNonNull(commandChannel, "commandChannel is null");
        if (eventStreamId <= 0) {
            throw new IllegalArgumentException("eventStreamId must be positive");
        }
        if (commandStreamId <= 0) {
            throw new IllegalArgumentException("commandStreamId must be positive");
        }
        Objects.requireNonNull(sessionName, "sessionName is null");

        log = logFactory.create(getClass());
        timestampOffset = getSchema().getTimestampOffset();
        leaderEpochOffset = getSchema().getLeaderEpochOffset();
        session = new AeronSession();
        session.setSessionName(BufferUtils.fromAsciiString(sessionName));

        var context = AeronContextFactory.createContext(directoryProvider);
        aeron = Aeron.connect(context);

        eventPublication = aeron.addExclusivePublication(eventChannel, eventStreamId);
        commandSubscription = aeron.addSubscription(commandChannel, commandStreamId);

        publishBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(eventPublication.maxPayloadLength()));
        bufferClaim = new BufferClaim();

        commandFragmentHandler = (buffer, offset, length, header) -> {
            if (commandListener == null) {
                return;
            }
            if (length < getSchema().getMessageHeaderLength()) {
                log.warn().append("command too short: length=").append(length).commit();
                return;
            }
            var wrapper = BufferUtils.wrap(buffer, offset, length);
            commandListener.accept(wrapper);
        };

        selector.addPoller(() -> commandSubscription.poll(commandFragmentHandler, FRAGMENT_LIMIT));

        activator = activatorFactory.createActivator(
                "AeronBusServer:" + eventChannel,
                this);
        activator.ready();

        metricFactory.registerGaugeMetric(
                "Aeron_EventStream_Position",
                eventPublication::position,
                "channel", eventChannel);
        metricFactory.registerGaugeMetric(
                "Aeron_PublicationFailCount",
                () -> publicationFailCount,
                "channel", eventChannel);
    }

    @Override
    public MutableDirectBuffer acquire() {
        messageBuffer = publishBuffer;
        return messageBuffer;
    }

    @Override
    public void commit(int msgLength) {
        if (msgLength > eventPublication.maxPayloadLength()) {
            throw new IllegalArgumentException(
                    "message length exceeds maxPayloadLength: length=" + msgLength);
        }
        if (activator.isActive()) {
            messageBuffer.putLong(timestampOffset, time.nanos());
            messageBuffer.putInt(leaderEpochOffset, getLeaderEpoch());
        }
        var result = eventPublication.tryClaim(msgLength, bufferClaim);
        if (result <= 0) {
            publicationFailCount++;
            throw new IllegalStateException(
                    "event publication failed, sequencer must stop to prevent state divergence: result=" + result);
        }
        bufferClaim.buffer().putBytes(bufferClaim.offset(), messageBuffer, 0, msgLength);
        bufferClaim.commit();
    }

    @Override
    public void commit(int msgLength, long timestamp) {
        if (msgLength > eventPublication.maxPayloadLength()) {
            throw new IllegalArgumentException(
                    "message length exceeds maxPayloadLength: length=" + msgLength);
        }
        if (activator.isActive()) {
            messageBuffer.putLong(timestampOffset, timestamp);
            messageBuffer.putInt(leaderEpochOffset, getLeaderEpoch());
        }
        var result = eventPublication.tryClaim(msgLength, bufferClaim);
        if (result <= 0) {
            publicationFailCount++;
            throw new IllegalStateException(
                    "event publication failed, sequencer must stop to prevent state divergence: result=" + result);
        }
        bufferClaim.buffer().putBytes(bufferClaim.offset(), messageBuffer, 0, msgLength);
        bufferClaim.commit();
    }

    @Override
    public boolean isActive() {
        return activator.isActive();
    }

    @Override
    public void send() {
        // no-op, send occurs on commit via tryClaim
    }

    @Override
    public boolean supportsEventListening() {
        return false;
    }

    /**
     * No-op: Aeron bus server publishes events but cannot subscribe to the event channel.
     * Backup sequencer event listening requires a separate {@code AeronBusClient} subscription.
     * Check {@link #supportsEventListening()} before relying on event listeners.
     *
     * @param eventListener ignored
     */
    @Override
    public void addEventListener(Consumer<DirectBuffer> eventListener) {
        // Aeron bus server does not receive events from the event channel.
        // Backup sequencer event listening requires a separate AeronBusClient subscription.
    }

    @Override
    public void setCommandListener(Consumer<DirectBuffer> commandListener) {
        this.commandListener = Objects.requireNonNull(commandListener, "commandListener is null");
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("activator").object(activator)
                .string("session").object(session)
                .string("eventPublication").string(eventPublication.channel())
                .string("commandSubscription").string(commandSubscription.channel())
                .string("publicationFailCount").number(publicationFailCount)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
