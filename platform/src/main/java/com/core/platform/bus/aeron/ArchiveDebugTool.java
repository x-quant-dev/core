package com.core.platform.bus.aeron;

import com.core.infrastructure.command.Command;
import com.core.infrastructure.encoding.Encodable;
import com.core.infrastructure.encoding.ObjectEncoder;
import com.core.infrastructure.log.Log;
import com.core.infrastructure.log.LogFactory;
import com.core.infrastructure.messages.Schema;
import io.aeron.archive.client.AeronArchive;

import java.util.Objects;

/**
 * Archive-based debugging tool for replaying specific time ranges and filtering by message type.
 *
 * <p>Connects to an Archive, replays a recording within a position range, decodes messages using the schema,
 * and prints decoded message details. Supports optional message type filtering.
 *
 * <p>Usage via shell:
 * <pre>
 * create /debugTool com.core.platform.bus.aeron.ArchiveDebugTool @/vm/aeron/driver @/bus/schema
 * /debugTool/replay aeron:udp?endpoint=0.0.0.0:8010 100 42 0 1000000
 * /debugTool/replayByType aeron:udp?endpoint=0.0.0.0:8010 100 42 0 1000000 addOrder
 * </pre>
 */
public class ArchiveDebugTool implements Encodable {

    private final Log log;
    private final AeronDirectoryProvider directoryProvider;
    private final Schema<?, ?> schema;
    private long messagesReplayed;
    private long messagesMatched;
    private boolean replaying;

    /**
     * Creates an {@code ArchiveDebugTool}.
     *
     * @param logFactory a factory to create logs
     * @param directoryProvider provides the Aeron directory
     * @param schema the message schema for decoding
     */
    public ArchiveDebugTool(LogFactory logFactory, AeronDirectoryProvider directoryProvider, Schema<?, ?> schema) {
        Objects.requireNonNull(logFactory, "logFactory is null");
        this.directoryProvider = Objects.requireNonNull(directoryProvider, "directoryProvider is null");
        this.schema = Objects.requireNonNull(schema, "schema is null");
        log = logFactory.create(getClass());
    }

    /**
     * Replays all messages from a recording within the specified position range.
     *
     * @param controlChannel the Archive control channel
     * @param controlStreamId the Archive control stream ID
     * @param recordingId the recording ID
     * @param startPosition the start position
     * @param length the number of bytes to replay
     */
    @Command
    public void replay(
            String controlChannel,
            int controlStreamId,
            long recordingId,
            long startPosition,
            long length) {
        replayInternal(controlChannel, controlStreamId, recordingId, startPosition, length, null);
    }

    /**
     * Replays messages of a specific type from a recording within the specified position range.
     *
     * @param controlChannel the Archive control channel
     * @param controlStreamId the Archive control stream ID
     * @param recordingId the recording ID
     * @param startPosition the start position
     * @param length the number of bytes to replay
     * @param messageTypeName the message type name to filter (e.g., "addOrder")
     */
    @Command
    public void replayByType(
            String controlChannel,
            int controlStreamId,
            long recordingId,
            long startPosition,
            long length,
            String messageTypeName) {
        replayInternal(controlChannel, controlStreamId, recordingId, startPosition, length, messageTypeName);
    }

    private void replayInternal(
            String controlChannel,
            int controlStreamId,
            long recordingId,
            long startPosition,
            long length,
            String messageTypeFilter) {
        if (replaying) {
            log.warn().append("replay already in progress").commit();
            return;
        }
        replaying = true;
        messagesReplayed = 0;
        messagesMatched = 0;

        var messageTypeOffset = schema.getMessageTypeOffset();
        var headerLength = schema.getMessageHeaderLength();
        var filterType = messageTypeFilter != null ? (byte) schema.getMessageType(messageTypeFilter) : -1;

        log.info().append("starting replay: recordingId=").append(recordingId)
                .append(", startPosition=").append(startPosition)
                .append(", length=").append(length)
                .append(", filter=").append(messageTypeFilter != null ? messageTypeFilter : "none")
                .commit();

        var archiveContext = new AeronArchive.Context()
                .controlRequestChannel(controlChannel)
                .controlRequestStreamId(controlStreamId)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                .aeronDirectoryName(directoryProvider.getAeronDirectory());

        try (var archive = AeronArchive.connect(archiveContext)) {

            var replayChannel = "aeron:ipc";
            var replayStreamId = 101;

            archive.startReplay(recordingId, startPosition, length, replayChannel, replayStreamId);

            var aeron = archive.context().aeron();
            var subscription = aeron.addSubscription(replayChannel, replayStreamId);

            while (subscription.imageCount() == 0) {
                Thread.yield();
            }

            var image = subscription.imageAtIndex(0);

            while (!image.isEndOfStream()) {
                image.poll((buffer, offset, msgLength, header) -> {
                    messagesReplayed++;

                    if (msgLength < headerLength) {
                        return;
                    }

                    var msgType = buffer.getByte(offset + messageTypeOffset);
                    if (filterType >= 0 && msgType != filterType) {
                        return;
                    }

                    messagesMatched++;
                    var msgName = schema.getMessageName(msgType);

                    log.info().append("msg=").append(msgName)
                            .append(", pos=").append(header.position())
                            .commit();
                }, 256);
            }
            image.poll((buffer, offset, msgLength, header) -> {
                messagesReplayed++;
                if (msgLength >= headerLength) {
                    var msgType = buffer.getByte(offset + messageTypeOffset);
                    if (filterType < 0 || msgType == filterType) {
                        messagesMatched++;
                    }
                }
            }, 256);

            subscription.close();

            log.info().append("replay complete: messagesReplayed=").append(messagesReplayed)
                    .append(", messagesMatched=").append(messagesMatched)
                    .commit();
        } catch (Exception e) {
            log.warn().append("replay failed: ").append(e).commit();
        } finally {
            replaying = false;
        }
    }

    @Command(path = "status", readOnly = true)
    @Override
    public void encode(ObjectEncoder encoder) {
        encoder.openMap()
                .string("replaying").bool(replaying)
                .string("messagesReplayed").number(messagesReplayed)
                .string("messagesMatched").number(messagesMatched)
                .closeMap();
    }

    @Override
    public String toString() {
        return toEncodedString();
    }
}
