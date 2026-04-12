package com.core.platform.bus.aeron;

import io.aeron.driver.MediaDriver;

import java.util.Objects;

final class EmbeddedAeronDirectoryProvider implements AeronDirectoryProvider {

    private final MediaDriver mediaDriver;

    EmbeddedAeronDirectoryProvider(MediaDriver mediaDriver) {
        this.mediaDriver = Objects.requireNonNull(mediaDriver, "mediaDriver is null");
    }

    @Override
    public String getAeronDirectory() {
        return mediaDriver.aeronDirectoryName();
    }
}
