package com.core.platform.bus.aeron;

import io.aeron.Aeron;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.UnsafeBuffer;

import java.net.DatagramSocket;
import java.nio.file.Path;

/**
 * Reusable test fixture for embedded Aeron MediaDriver + Archive.
 *
 * <p>Uses {@link ArchivingMediaDriver} to launch both the MediaDriver and Archive as a single
 * composite component. This ensures proper coordination: shared agent invoker, shared error
 * handling, consistent directory configuration, and correct close ordering.
 */
class EmbeddedAeronTestFixture implements AutoCloseable {

    final String aeronDir;
    final String archiveDir;
    final String controlChannel;
    final int controlStreamId = 100;
    final String eventChannel;
    final int eventStreamId = 1001;

    ArchivingMediaDriver archivingMediaDriver;
    Aeron aeron;
    AeronArchive aeronArchive;
    AeronDirectoryProvider directoryProvider;

    EmbeddedAeronTestFixture(Path tempDir) {
        this(tempDir, reservePort(), reservePort());
    }

    EmbeddedAeronTestFixture(Path tempDir, int controlPort, int eventPort) {
        aeronDir = tempDir.resolve("aeron").toString();
        archiveDir = tempDir.resolve("archive").toString();
        controlChannel = "aeron:udp?endpoint=127.0.0.1:" + controlPort;
        eventChannel = "aeron:udp?endpoint=127.0.0.1:" + eventPort;

        var driverCtx = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .spiesSimulateConnection(true);

        var archiveCtx = new Archive.Context()
                .archiveDirectoryName(archiveDir)
                .controlChannel(controlChannel)
                .controlStreamId(controlStreamId)
                .replicationChannel("aeron:udp?endpoint=127.0.0.1:0")
                .deleteArchiveOnStart(true)
                .fileSyncLevel(0)
                .threadingMode(ArchiveThreadingMode.SHARED);

        archivingMediaDriver = ArchivingMediaDriver.launch(driverCtx, archiveCtx);

        aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDir));

        aeronArchive = AeronArchive.connect(new AeronArchive.Context()
                .controlRequestChannel(controlChannel)
                .controlRequestStreamId(controlStreamId)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                .aeronDirectoryName(aeronDir));

        directoryProvider = new ExternalAeronDirectoryProvider(aeronDir);
    }

    /**
     * Records {@code count} messages on the event channel and returns the recording ID.
     */
    long recordMessages(int count, int messageSize) {
        aeronArchive.startRecording(eventChannel, eventStreamId, SourceLocation.LOCAL);

        try (var pub = aeron.addPublication(eventChannel, eventStreamId)) {
            while (!pub.isConnected()) {
                Thread.yield();
            }

            var buffer = new UnsafeBuffer(new byte[messageSize]);
            for (var i = 0; i < count; i++) {
                buffer.putInt(0, i);
                if (messageSize > 21) {
                    buffer.putByte(21, (byte) 1);
                }
                while (pub.offer(buffer, 0, messageSize) < 0) {
                    Thread.yield();
                }
            }

            var pubPosition = pub.position();
            var deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                var recId = aeronArchive.findLastMatchingRecording(
                        0, eventChannel, eventStreamId, Aeron.NULL_VALUE);
                if (recId >= 0) {
                    var recPos = aeronArchive.getMaxRecordedPosition(recId);
                    if (recPos >= pubPosition) {
                        break;
                    }
                }
                Thread.yield();
            }
        }

        aeronArchive.stopRecording(eventChannel, eventStreamId);

        var recordingId = new long[]{Aeron.NULL_VALUE};
        aeronArchive.listRecordingsForUri(0, 10, eventChannel, eventStreamId,
                (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength,
                 termBufferLength, mtuLength, sessionId, streamId,
                 strippedChannel, originalChannel, sourceIdentity) ->
                        recordingId[0] = recId);
        if (recordingId[0] == Aeron.NULL_VALUE) {
            aeronArchive.listRecordingsForUri(0, 10, "", eventStreamId,
                    (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp,
                     startPosition, stopPosition, initialTermId, segmentFileLength,
                     termBufferLength, mtuLength, sessionId, streamId,
                     strippedChannel, originalChannel, sourceIdentity) ->
                            recordingId[0] = recId);
        }
        return recordingId[0];
    }

    @Override
    public void close() {
        CloseHelper.closeAll(aeronArchive, aeron, archivingMediaDriver);
    }

    static int reservePort() {
        try (var socket = new DatagramSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException("failed to reserve port", e);
        }
    }
}
