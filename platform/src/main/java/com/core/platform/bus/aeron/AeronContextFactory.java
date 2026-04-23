package com.core.platform.bus.aeron;

import io.aeron.Aeron;

final class AeronContextFactory {

    private AeronContextFactory() {
    }

    static Aeron.Context createContext(AeronDirectoryProvider directoryProvider) {
        return new Aeron.Context().aeronDirectoryName(directoryProvider.getAeronDirectory());
    }
}
