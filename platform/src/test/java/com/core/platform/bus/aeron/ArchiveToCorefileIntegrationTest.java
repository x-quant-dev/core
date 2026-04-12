package com.core.platform.bus.aeron;

import com.core.infrastructure.log.TestLogFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static org.assertj.core.api.BDDAssertions.then;

@Timeout(30)
class ArchiveToCorefileIntegrationTest {

    private EmbeddedAeronTestFixture fixture;
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path aeronTempDir) {
        fixture = new EmbeddedAeronTestFixture(aeronTempDir);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void export_writes_corefile_with_correct_format() {
        var recordingId = fixture.recordMessages(10, 64);
        var outputPath = tempDir.resolve("output.dat").toString();

        var logFactory = new TestLogFactory();
        var exporter = new ArchiveToCorefile(logFactory, fixture.directoryProvider);
        exporter.export(fixture.controlChannel, fixture.controlStreamId, recordingId, 0, outputPath);

        var file = new File(outputPath);
        then(file.exists()).isTrue();
        then(file.length()).isGreaterThan(0);

        // corefile format: each message is [2-byte big-endian length][payload]
        try (var fis = new FileInputStream(file)) {
            var count = 0;
            while (fis.available() > 0) {
                var lenBytes = fis.readNBytes(2);
                var len = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).getShort();
                then(len).isEqualTo((short) 64);
                var payload = fis.readNBytes(len);
                then(payload.length).isEqualTo(64);
                count++;
            }
            then(count).isEqualTo(10);
        } catch (Exception e) {
            throw new RuntimeException("failed to read corefile", e);
        }
    }
}
