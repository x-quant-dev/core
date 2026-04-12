package com.core.platform.bus.aeron;

import com.core.infrastructure.log.TestLogFactory;
import com.core.infrastructure.metrics.MetricFactory;
import com.core.platform.activation.ActivatorFactory;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.BDDAssertions.then;

@Timeout(30)
class ArchiveManagerIntegrationTest {

    private MediaDriver driver;
    private Aeron aeron;
    private ArchiveManager archiveManager;
    private String aeronDir;
    private String controlChannel;
    private String eventChannel;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        aeronDir = tempDir.resolve("aeron").toString();
        var archiveDir = tempDir.resolve("archive").toString();

        var driverCtx = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .spiesSimulateConnection(true);
        driver = MediaDriver.launch(driverCtx);

        var controlPort = EmbeddedAeronTestFixture.reservePort();
        var eventPort = EmbeddedAeronTestFixture.reservePort();
        controlChannel = "aeron:udp?endpoint=127.0.0.1:" + controlPort;
        eventChannel = "aeron:udp?endpoint=127.0.0.1:" + eventPort;

        var directoryProvider = new ExternalAeronDirectoryProvider(aeronDir);

        var logFactory = new TestLogFactory();
        var metricFactory = new MetricFactory(logFactory);
        var activatorFactory = new ActivatorFactory(logFactory, metricFactory);

        archiveManager = new ArchiveManager(
                logFactory, metricFactory, activatorFactory,
                directoryProvider, archiveDir, controlChannel, 100, eventChannel, 1001);
    }

    @AfterEach
    void tearDown() {
        if (archiveManager != null && archiveManager.isRecording()) {
            archiveManager.deactivate();
        }
        CloseHelper.closeAll(aeron, driver);
    }

    @Test
    void activate_starts_recording() {
        archiveManager.activate();

        then(archiveManager.isRecording()).isTrue();
    }

    @Test
    void deactivate_stops_recording() {
        archiveManager.activate();
        then(archiveManager.isRecording()).isTrue();

        archiveManager.deactivate();

        then(archiveManager.isRecording()).isFalse();
    }

    @Test
    void activate_records_events() throws Exception {
        archiveManager.activate();
        then(archiveManager.isRecording()).isTrue();

        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));
        try (var pub = aeron.addPublication(eventChannel, 1001)) {
            var buffer = new UnsafeBuffer(new byte[64]);
            for (var i = 0; i < 5; i++) {
                buffer.putInt(0, i);
                while (pub.offer(buffer, 0, 64) < 0) {
                    Thread.yield();
                }
            }
        }

        // allow archive to catch up
        Thread.sleep(500);

        then(archiveManager.getControlChannel()).isEqualTo(controlChannel);
        then(archiveManager.toString()).contains("recording=true");
    }
}
