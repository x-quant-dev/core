package com.core.kv.applications.utilities;

import com.core.infrastructure.Allocation;
import com.core.infrastructure.command.Command;
import com.core.infrastructure.log.LogFactory;
import com.core.platform.activation.ActivatorFactory;
import com.core.platform.applications.utilities.Injector;
import com.core.platform.bus.BusClient;

/**
 * The {@code KvInjector} extends {@code Injector} to provide methods to add and remove entries from the kvstore
 * book.
 *
 * @see Injector for activation information
 */
public class KvInjector extends Injector {

    /**
     * Creates an {@code ClobInjector} with the specified parameters.
     *
     * @param logFactory       a factory to create logs
     * @param activatorFactory a factory of activators
     * @param busClient        the bus client
     * @param applicationName  the name of this application
     */
    public KvInjector(LogFactory logFactory,
                      ActivatorFactory activatorFactory,
                      BusClient<?, ?> busClient,
                      String applicationName) {
        super(logFactory, activatorFactory, busClient, applicationName);
    }

    /**
     * Put new entry.
     */
    @Allocation
    @Command
    public void putEntry(int id, String value) {

    }

    /**
     * Remove entry
     *
     * @param id the identifier of the record to cancel.
     */
    @Allocation
    @Command
    public void removeEntry(int id) {

    }
}
