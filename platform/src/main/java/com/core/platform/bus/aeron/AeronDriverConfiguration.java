package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Property;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import io.aeron.driver.MediaDriver;

/**
 * Minimal configuration wrapper for creating embedded Media Driver contexts.
 */
public class AeronDriverConfiguration implements Encodable {

    @Property(write = true)
    private String aeronDirectory;

    public AeronDriverConfiguration() {
    }

    /**
     * Creates a Media Driver context from this configuration.
     *
     * @return the configured Media Driver context
     */
    @Command(readOnly = true)
    public MediaDriver.Context toContext() {
        var context = new MediaDriver.Context();
        if (aeronDirectory != null) {
            context.aeronDirectoryName(aeronDirectory);
        }
        return context;
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("aeronDirectory").string(aeronDirectory)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
