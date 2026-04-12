package com.core.platform.bus.aeron;

import java.util.Objects;

final class ExternalAeronDirectoryProvider implements AeronDirectoryProvider {

    private final String aeronDirectory;

    ExternalAeronDirectoryProvider(String aeronDirectory) {
        this.aeronDirectory = Objects.requireNonNull(aeronDirectory, "aeronDirectory is null");
    }

    @Override
    public String getAeronDirectory() {
        return aeronDirectory;
    }
}
