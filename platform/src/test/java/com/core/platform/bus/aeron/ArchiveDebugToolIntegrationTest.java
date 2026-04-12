package com.core.platform.bus.aeron;

import com.core.infrastructure.log.TestLogFactory;
import com.core.platform.schema.sbe.SbeSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.BDDAssertions.then;

@Timeout(30)
class ArchiveDebugToolIntegrationTest {

    private EmbeddedAeronTestFixture fixture;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        fixture = new EmbeddedAeronTestFixture(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void replay_completes_without_error() {
        var recordingId = fixture.recordMessages(10, 64);

        var logFactory = new TestLogFactory();
        var schema = new SbeSchema();
        var debugTool = new ArchiveDebugTool(logFactory, fixture.directoryProvider, schema);

        debugTool.replay(fixture.controlChannel, fixture.controlStreamId,
                recordingId, 0, Long.MAX_VALUE);

        // after replay completes, tool should not be in replaying state
        then(debugTool.toString()).contains("replaying=false");
    }

    @Test
    void replay_reports_messages_replayed() {
        var recordingId = fixture.recordMessages(5, 64);

        var logFactory = new TestLogFactory();
        var schema = new SbeSchema();
        var debugTool = new ArchiveDebugTool(logFactory, fixture.directoryProvider, schema);

        debugTool.replay(fixture.controlChannel, fixture.controlStreamId,
                recordingId, 0, Long.MAX_VALUE);

        // messagesReplayed should be 5 — verify via encoded status
        then(debugTool.toString()).contains("messagesReplayed=5");
    }
}
