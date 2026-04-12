package com.core.platform.bus.aeron;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.collections.CoreMap;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.io.Selector;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.messages.Dispatcher;
import com.core.infrastructure.messages.Provider;
import com.core.infrastructure.messages.Schema;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.infrastructure.time.Scheduler;
import com.core.infrastructure.time.SequencerDrivenTime;
import com.core.infrastructure.time.Time;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.BusClient;
import com.core.platform.shell.CommandException;
import com.core.platform.shell.Shell;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;

import java.util.Map;
import java.util.Objects;

/**
 * Aeron-based bus client implementation.
 */
public class AeronBusClient<DispatcherT extends Dispatcher, ProviderT extends Provider>
        implements BusClient<DispatcherT, ProviderT>, Encodable {

    private static final int FRAGMENT_LIMIT = 10;

    private final Shell shell;
    private final Scheduler scheduler;
    private final LogFactory logFactory;
    private final ActivatorFactory activatorFactory;
    private final AeronSession session;
    @Directory
    private final Activator activator;
    private final Schema<DispatcherT, ProviderT> schema;
    private final DispatcherT dispatcher;
    private final Aeron aeron;
    private final Subscription eventSubscription;
    private final Publication commandPublication;
    private final Map<String, ProviderT> nameToCommandProviders;
    @Property
    private final String eventChannel;
    @Property
    private final String commandChannel;

    /**
     * Creates an {@code AeronBusClient} with an external Aeron directory.
     *
     * @param shell the command shell
     * @param selector the Aeron selector
     * @param time the time source
     * @param scheduler the task scheduler
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param busName the bus name
     * @param schema the message schema
     * @param aeronDirectory the Aeron media driver directory
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     * @param commandChannel the command channel URI
     * @param commandStreamId the command stream identifier
     * @param sessionName the session name
     */
    public AeronBusClient(
            Shell shell,
            Selector selector,
            Time time,
            Scheduler scheduler,
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            String busName,
            Schema<DispatcherT, ProviderT> schema,
            String aeronDirectory,
            String eventChannel,
            int eventStreamId,
            String commandChannel,
            int commandStreamId,
            String sessionName) {
        this(
                shell,
                selector,
                time,
                scheduler,
                logFactory,
                metricFactory,
                activatorFactory,
                busName,
                schema,
                new ExternalAeronDirectoryProvider(aeronDirectory),
                eventChannel,
                eventStreamId,
                commandChannel,
                commandStreamId,
                sessionName);
    }

    /**
     * Creates an {@code AeronBusClient} with an embedded Aeron driver.
     *
     * @param shell the command shell
     * @param selector the Aeron selector
     * @param time the time source
     * @param scheduler the task scheduler
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param busName the bus name
     * @param schema the message schema
     * @param embeddedDriver the embedded Aeron driver
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     * @param commandChannel the command channel URI
     * @param commandStreamId the command stream identifier
     * @param sessionName the session name
     */
    public AeronBusClient(
            Shell shell,
            Selector selector,
            Time time,
            Scheduler scheduler,
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            String busName,
            Schema<DispatcherT, ProviderT> schema,
            EmbeddedAeronDriver embeddedDriver,
            String eventChannel,
            int eventStreamId,
            String commandChannel,
            int commandStreamId,
            String sessionName) {
        this(
                shell,
                selector,
                time,
                scheduler,
                logFactory,
                metricFactory,
                activatorFactory,
                busName,
                schema,
                embeddedDriver.asDirectoryProvider(),
                eventChannel,
                eventStreamId,
                commandChannel,
                commandStreamId,
                sessionName);
    }

    /**
     * Creates an {@code AeronBusClient} with the specified directory provider.
     *
     * @param shell the command shell
     * @param selector the Aeron selector
     * @param time the time source
     * @param scheduler the task scheduler
     * @param logFactory a factory to create logs
     * @param metricFactory a factory to create metrics
     * @param activatorFactory the activator factory
     * @param busName the bus name
     * @param schema the message schema
     * @param directoryProvider the Aeron directory provider
     * @param eventChannel the event channel URI
     * @param eventStreamId the event stream identifier
     * @param commandChannel the command channel URI
     * @param commandStreamId the command stream identifier
     * @param sessionName the session name
     * @throws IllegalArgumentException if stream IDs are not positive
     */
    public AeronBusClient(
            Shell shell,
            Selector selector,
            Time time,
            Scheduler scheduler,
            LogFactory logFactory,
            MetricFactory metricFactory,
            ActivatorFactory activatorFactory,
            String busName,
            Schema<DispatcherT, ProviderT> schema,
            AeronDirectoryProvider directoryProvider,
            String eventChannel,
            int eventStreamId,
            String commandChannel,
            int commandStreamId,
            String sessionName) {
        this.shell = Objects.requireNonNull(shell, "shell is null");
        Objects.requireNonNull(selector, "selector is null");
        Objects.requireNonNull(time, "time is null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is null");
        this.logFactory = Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(metricFactory, "metricFactory is null");
        this.activatorFactory = Objects.requireNonNull(activatorFactory, "activationManager is null");
        Objects.requireNonNull(busName, "busName is null");
        this.schema = Objects.requireNonNull(schema, "schema is null");
        Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        this.eventChannel = Objects.requireNonNull(eventChannel, "eventChannel is null");
        this.commandChannel = Objects.requireNonNull(commandChannel, "commandChannel is null");
        if (eventStreamId <= 0) {
            throw new IllegalArgumentException("eventStreamId must be positive");
        }
        if (commandStreamId <= 0) {
            throw new IllegalArgumentException("commandStreamId must be positive");
        }
        Objects.requireNonNull(sessionName, "sessionName is null");

        nameToCommandProviders = new CoreMap<>();
        session = new AeronSession();
        session.setSessionName(BufferUtils.fromAsciiString(sessionName));
        dispatcher = schema.createDispatcher();

        var context = AeronContextFactory.createContext(directoryProvider);
        aeron = Aeron.connect(context);

        eventSubscription = aeron.addSubscription(eventChannel, eventStreamId);
        commandPublication = aeron.addPublication(commandChannel, commandStreamId);

        var timestampOffset = schema.getTimestampOffset();
        FragmentHandler eventHandler;
        if (time instanceof SequencerDrivenTime sdt) {
            eventHandler = (buffer, offset, length, header) -> {
                sdt.onEventTimestamp(buffer.getLong(offset + timestampOffset));
                dispatcher.dispatch(buffer, offset, length);
            };
        } else {
            eventHandler = (buffer, offset, length, header) ->
                    dispatcher.dispatch(buffer, offset, length);
        }
        selector.addPoller(() -> eventSubscription.poll(eventHandler, FRAGMENT_LIMIT));

        activator = activatorFactory.createActivator(
                "AeronBusClient:" + busName + ":" + eventChannel, this);
        activator.ready();
    }

    @Override
    public Schema<DispatcherT, ProviderT> getSchema() {
        return schema;
    }

    @Override
    public DispatcherT getDispatcher() {
        return dispatcher;
    }

    @Override
    public ProviderT getProvider(String applicationName, Object associatedObject) {
        try {
            var commandProvider = nameToCommandProviders.get(applicationName);

            if (commandProvider == null) {
                var messagePublisher = new AeronCommandPublisher(
                        scheduler,
                        logFactory,
                        activatorFactory,
                        this,
                        session,
                        commandPublication,
                        applicationName);
                shell.addObject(this, BufferUtils.fromAsciiString("publishers/" + applicationName), messagePublisher);
                commandProvider = schema.createProvider(messagePublisher);
                var providerActivator = activatorFactory.createActivator(
                        "Provider:" + applicationName, commandProvider, messagePublisher);
                providerActivator.ready();
                nameToCommandProviders.put(applicationName, commandProvider);
            }

            return commandProvider;
        } catch (CommandException e) {
            throw new IllegalArgumentException("application is already registered: " + applicationName);
        }
    }

    @Override
    public String getSession() {
        return session.getSessionNameAsString();
    }

    @Override
    public void addOpenSessionListener(Runnable listener) {
        listener.run();
    }

    @Override
    public void addCloseSessionListener(Runnable listener) {
        // no-op
    }

    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("eventChannel").string(eventChannel)
                .string("commandChannel").string(commandChannel)
                .string("commandProviders").number(nameToCommandProviders.size())
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
