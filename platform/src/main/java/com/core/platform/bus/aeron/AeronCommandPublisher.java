package com.core.platform.bus.aeron;

import com.core.infrastructure.buffer.BufferUtils;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.messages.MessagePublisher;
import com.core.infrastructure.messages.Schema;
import com.core.infrastructure.time.Scheduler;
import com.core.platform.activation.Activatable;
import com.core.platform.activation.Activator;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.bus.BusClient;
import io.aeron.Publication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.Objects;

class AeronCommandPublisher implements Encodable, MessagePublisher, Activatable {

    private final Log log;
    private final AeronSession session;
    @Property
    private final String applicationName;
    private final DirectBuffer applicationNameBuf;
    private final MutableDirectBuffer messageWrapper;
    private final Activator activator;
    private final Schema<?, ?> schema;
    private final Encoder appDefinitionEncoder;
    private final String applicationDefinitionNameField;
    private final BufferClaim bufferClaim;
    private final Publication publication;
    private final MutableDirectBuffer publishBuffer;

    @Property
    private int outSeqNum;
    @Property
    private int inSeqNum;
    @Property
    private short appId;

    AeronCommandPublisher(
            Scheduler scheduler,
            LogFactory logFactory,
            ActivatorFactory activatorFactory,
            BusClient<?, ?> busClient,
            AeronSession session,
            Publication publication,
            String applicationName) {
        Objects.requireNonNull(scheduler, "scheduler is null");
        Objects.requireNonNull(logFactory, "logFactory is null");
        Objects.requireNonNull(activatorFactory, "activationManager is null");
        Objects.requireNonNull(busClient, "busClient is null");
        this.session = Objects.requireNonNull(session, "session is null");
        this.publication = Objects.requireNonNull(publication, "publication is null");
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName is null");

        schema = busClient.getSchema();
        outSeqNum = 1;
        inSeqNum = 1;

        messageWrapper = BufferUtils.mutableEmptyBuffer();
        log = logFactory.create(getClass());
        applicationNameBuf = BufferUtils.fromAsciiString(applicationName);

        bufferClaim = new BufferClaim();
        publishBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(publication.maxPayloadLength()));

        var dispatcher = busClient.getDispatcher();
        dispatcher.addListenerBeforeDispatch(this::onBeforeMessage);
        dispatcher.addListenerAfterDispatch(this::onAfterMessage);

        appDefinitionEncoder = schema.createEncoder(schema.getProperty("applicationDefinitionMessageName"));
        applicationDefinitionNameField = schema.getProperty("applicationDefinitionNameField");
        commit(appDefinitionEncoder.wrap(acquire())
                .set(applicationDefinitionNameField, applicationNameBuf)
                .length());
        send();

        activator = activatorFactory.createActivator("AeronCommandPublisher:" + applicationName, this, session);
    }

    @Override
    public void activate() {
        if (appId != 0) {
            activator.ready();
        }
    }

    @Override
    public void deactivate() {
        activator.notReady("closed");
    }

    @Override
    public MutableDirectBuffer acquire() {
        messageWrapper.wrap(publishBuffer, 0, publishBuffer.capacity());
        return messageWrapper;
    }

    @Override
    public void commit(int msgLength) {
        if (msgLength > publication.maxPayloadLength()) {
            throw new IllegalArgumentException("message length larger than maxPayloadLength: " + msgLength);
        }
        messageWrapper.putShort(schema.getApplicationIdOffset(), appId);
        messageWrapper.putInt(schema.getApplicationSequenceNumberOffset(), outSeqNum++);
        messageWrapper.wrap(0, 0);

        var result = publication.tryClaim(msgLength, bufferClaim);
        if (result <= 0) {
            log.warn().append("command publication back pressure: result=").append(result).commit();
            return;
        }
        bufferClaim.buffer().putBytes(bufferClaim.offset(), publishBuffer, 0, msgLength);
        bufferClaim.commit();
    }

    @Override
    public void send() {
        // no-op, send occurs on commit via tryClaim
    }

    @Override
    public String getApplicationName() {
        return applicationName;
    }

    @Override
    public short getApplicationId() {
        return appId;
    }

    @Override
    public boolean isCurrent() {
        return inSeqNum == outSeqNum;
    }

    private void onBeforeMessage(Decoder decoder) {
        if (decoder.getApplicationId() == appId) {
            inSeqNum = decoder.getApplicationSequenceNumber() + 1;
            if (inSeqNum >= outSeqNum) {
                outSeqNum = inSeqNum;
            }
        } else if (appId == 0
                && decoder.messageName().equals(appDefinitionEncoder.messageName())
                && applicationNameBuf.equals(decoder.get(applicationDefinitionNameField))) {
            appId = decoder.getApplicationId();
            inSeqNum = decoder.getApplicationSequenceNumber() + 1;
            outSeqNum = inSeqNum;
            activator.ready();
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void onAfterMessage(Decoder decoder) {
        // placeholder for future all-commands-cleared callbacks
    }

    @Command(path = "status")
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("session").object(session)
                .string("application").string(applicationName)
                .string("publicationChannel").string(publication.channel())
                .string("activator").object(activator)
                .string("nextConfirmedAppSeqNum").number(inSeqNum)
                .string("nextAppSeqNum").number(outSeqNum)
                .string("current").bool(isCurrent())
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
