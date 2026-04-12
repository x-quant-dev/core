package com.core.platform.bus;

import com.core.infrastructure.command.Property;
import com.core.infrastructure.messages.Dispatcher;
import com.core.infrastructure.messages.Provider;
import com.core.infrastructure.messages.Schema;

import java.util.Arrays;
import java.util.Objects;

/**
 * The abstract bus server provides an implementation of common features for bus server implementations including
 * management of application sequence numbers and getters for the dispatcher and schema.
 *
 * @param <DispatcherT> the dispatcher type
 */
public abstract class AbstractBusServer<DispatcherT extends Dispatcher, ProviderT extends Provider>
        implements BusServer<DispatcherT, ProviderT> {

    @Property
    private int[] appSeqNum;
    private final DispatcherT dispatcher;
    private short appId;
    private int leaderEpoch;
    private final Schema<DispatcherT, ProviderT> schema;

    /**
     * Creates a {@code AbstractBusServer} with the specified schema.
     *
     * @param schema the schema
     */
    protected AbstractBusServer(Schema<DispatcherT, ProviderT> schema) {
        this.schema = Objects.requireNonNull(schema, "schema is null");
        appSeqNum = new int[100];
        dispatcher = schema.createDispatcher();
    }

    @Override
    public short getApplicationId() {
        return appId;
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
    public void setApplicationSequenceNumber(int applicationId, int applicationSequenceNumber) {
        if (applicationId <= 0) {
            return;
        }
        if (applicationId > appSeqNum.length) {
            appSeqNum = Arrays.copyOf(appSeqNum, Math.max(applicationId, 2 * appSeqNum.length));
        }
        if (appId == 0) {
            appId = (short) applicationId;
        }
        appSeqNum[applicationId - 1] = applicationSequenceNumber;
    }

    @Override
    public int incrementAndGetApplicationSequenceNumber(int applicationId) {
        if (applicationId <= 0 || applicationId > appSeqNum.length) {
            return -1;
        }
        int idx = applicationId - 1;
        if (appSeqNum[idx] == 0) {
            return -1;
        }
        return ++appSeqNum[idx];
    }

    @Override
    public int getApplicationSequenceNumber(int applicationId) {
        if (applicationId <= 0 || applicationId > appSeqNum.length) {
            return -1;
        } else {
            return appSeqNum[applicationId - 1];
        }
    }

    @Override
    public void setLeaderEpoch(int epoch) {
        this.leaderEpoch = epoch;
    }

    protected int getLeaderEpoch() {
        return leaderEpoch;
    }
}
