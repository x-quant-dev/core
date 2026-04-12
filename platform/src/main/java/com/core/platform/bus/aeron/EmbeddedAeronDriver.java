package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.command.Directory;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import io.aeron.driver.MediaDriver;

import java.util.Objects;

/**
 * Wrapper for an embedded Aeron Media Driver.
 */
public class EmbeddedAeronDriver implements Encodable {

    @Directory(path = "driver")
    private final MediaDriver mediaDriver;

    public EmbeddedAeronDriver(MediaDriver.Context context) {
        Objects.requireNonNull(context, "context is null");
        mediaDriver = MediaDriver.launch(context);
    }

    public EmbeddedAeronDriver(String aeronDirectory) {
        Objects.requireNonNull(aeronDirectory, "aeronDirectory is null");
        mediaDriver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(aeronDirectory));
    }

    public AeronDirectoryProvider asDirectoryProvider() {
        return new EmbeddedAeronDirectoryProvider(mediaDriver);
    }

    @Command
    public void close() {
        mediaDriver.close();
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("aeronDirectory").string(mediaDriver.aeronDirectoryName())
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
