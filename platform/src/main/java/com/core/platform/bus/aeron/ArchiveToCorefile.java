package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Exports an Aeron Archive recording to legacy corefile format.
 *
 * <p>The corefile format is a sequential binary file where each message is prefixed
 * by a 2-byte length (big-endian short). This format is compatible with
 * {@link com.core.platform.bus.playback.FilePlayback}.
 *
 * <p>Usage via shell:
 * <pre>
 * create /archiveExport com.core.platform.bus.aeron.ArchiveToCorefile @/vm/aeron/driver
 * /archiveExport/export /tmp/archive aeron:udp?endpoint=0.0.0.0:8010 100 42 0 output.events.dat
 * </pre>
 */
public class ArchiveToCorefile implements Encodable {

    private final Log log;
    private final AeronDirectoryProvider directoryProvider;
    private long messagesExported;
    private long bytesExported;
    private boolean exporting;

    /**
     * Creates an {@code ArchiveToCorefile}.
     *
     * @param logFactory a factory to create logs
     * @param directoryProvider provides the Aeron directory
     */
    public ArchiveToCorefile(LogFactory logFactory, AeronDirectoryProvider directoryProvider) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        this.directoryProvider = Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        log = logFactory.create(getClass());
    }

    /**
     * Exports an Archive recording to a corefile.
     *
     * @param controlChannel the Archive control channel
     * @param controlStreamId the Archive control stream ID
     * @param recordingId the recording ID to export
     * @param startPosition the position to start exporting from
     * @param outputPath the output file path
     */
    @Command
    public void export(
            String controlChannel,
            int controlStreamId,
            long recordingId,
            long startPosition,
            String outputPath) {
        if (exporting) {
            log.warn().append("export already in progress").commit();
            return;
        }
        exporting = true;
        messagesExported = 0;
        bytesExported = 0;

        log.info().append("starting export: recordingId=").append(recordingId)
                .append(", startPosition=").append(startPosition)
                .append(", outputPath=").append(outputPath)
                .commit();

        var archiveContext = new AeronArchive.Context()
                .controlRequestChannel(controlChannel)
                .controlRequestStreamId(controlStreamId)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                .aeronDirectoryName(directoryProvider.getAeronDirectory());

        try (
                var archive = AeronArchive.connect(archiveContext);
                var fos = new FileOutputStream(outputPath)) {

            var replayChannel = "aeron:ipc";
            var replayStreamId = 100;
            var lengthBuffer = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);

            archive.startReplay(
                    recordingId, startPosition, Long.MAX_VALUE,
                    replayChannel, replayStreamId);

            var aeron = archive.context().aeron();
            var replayImage = aeron.addSubscription(replayChannel, replayStreamId);

            // wait for image to connect
            while (replayImage.imageCount() == 0) {
                Thread.yield();
            }

            var image = replayImage.imageAtIndex(0);
            FragmentHandler handler = (buffer, offset, length, header) -> {
                try {
                    lengthBuffer.clear();
                    lengthBuffer.putShort((short) length);
                    lengthBuffer.flip();
                    fos.write(lengthBuffer.array(), 0, 2);

                    var bytes = new byte[length];
                    buffer.getBytes(offset, bytes);
                    fos.write(bytes);

                    messagesExported++;
                    bytesExported += 2 + length;
                } catch (IOException e) {
                    throw new RuntimeException("error writing to corefile", e);
                }
            };

            while (!image.isEndOfStream()) {
                image.poll(handler, 256);
            }
            // drain remaining fragments
            image.poll(handler, 256);

            replayImage.close();

            log.info().append("export complete: messagesExported=").append(messagesExported)
                    .append(", bytesExported=").append(bytesExported)
                    .commit();
        } catch (Exception e) {
            log.warn().append("export failed: ").append(e).commit();
        } finally {
            exporting = false;
        }
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("exporting").bool(exporting)
                .string("messagesExported").number(messagesExported)
                .string("bytesExported").number(bytesExported)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
