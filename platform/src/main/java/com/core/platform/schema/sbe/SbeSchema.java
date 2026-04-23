package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.Decoder;
import com.core.infrastructure.messages.Encoder;
import com.core.infrastructure.messages.MessagePublisher;
import com.core.infrastructure.messages.Schema;
import com.core.platform.schema.sbe.SbeFieldLayout.SbeFieldType;

import java.util.HashMap;
import java.util.Map;

/**
 * An SBE-based implementation of the platform's {@code Schema} interface.
 *
 * <p>Uses the same 22-byte header layout as the Velocity-generated schema so that the binary format
 * on the wire is identical. This allows SBE and Velocity schemas to be used interchangeably.
 *
 * <p>Header layout:
 * <ul>
 *     <li>offset 0: short applicationId</li>
 *     <li>offset 2: int applicationSequenceNumber</li>
 *     <li>offset 6: long timestamp</li>
 *     <li>offset 14: short optionalFieldsIndex</li>
 *     <li>offset 16: int leaderEpoch</li>
 *     <li>offset 20: byte schemaVersion</li>
 *     <li>offset 21: byte messageType</li>
 * </ul>
 *
 * <p>This schema can be used as a drop-in replacement for {@code ClobSchema} via command files:
 * <pre>
 * create /bus/schema com.core.platform.schema.sbe.SbeSchema
 * </pre>
 */
public class SbeSchema implements Schema<SbeDispatcher, SbeProvider> {

    private static final int HEADER_LENGTH = 22;
    private static final byte VERSION = 2;
    private static final byte MIN_COMPAT_VERSION = 2;

    private static final SbeFieldLayout[] HEADER_FIELDS = {
            new SbeFieldLayout("applicationId", 0, SbeFieldType.SHORT, true),
            new SbeFieldLayout("applicationSequenceNumber", 2, SbeFieldType.INT, true),
            new SbeFieldLayout("timestamp", 6, SbeFieldType.LONG, true),
            new SbeFieldLayout("optionalFieldsIndex", 14, SbeFieldType.SHORT, true),
            new SbeFieldLayout("leaderEpoch", 16, SbeFieldType.INT, true),
            new SbeFieldLayout("schemaVersion", 20, SbeFieldType.BYTE, true),
            new SbeFieldLayout("messageType", 21, SbeFieldType.BYTE, true),
    };

    private static final String[] MESSAGE_NAMES = {
            "heartbeat", "applicationDefinition", "equityDefinition",
            "addOrder", "cancelOrder", "fillOrder", "rejectOrder", "rejectCancel",
            "archiveAnnouncement", "snapshotRequest", "snapshotBegin",
            "snapshotChunk", "snapshotComplete"
    };

    private static final String[] PROPERTIES = {
            "heartbeatMessageName", "applicationIdField",
            "applicationDefinitionMessageName", "applicationDefinitionNameField"
    };

    private final Map<String, MessageDef> messageDefs;
    private final byte minCompatibleVersion;

    /**
     * Creates an {@code SbeSchema}.
     */
    public SbeSchema() {
        this(MIN_COMPAT_VERSION);
    }

    /**
     * Creates an {@code SbeSchema} with the minimum supported header version.
     * Messages encoded with a lower schema version are rejected during dispatch.
     *
     * @param minCompatibleVersion the minimum header version to accept
     */
    public SbeSchema(byte minCompatibleVersion) {
        this.minCompatibleVersion = minCompatibleVersion;
        messageDefs = new HashMap<>();
        defineMessages();
    }

    private void defineMessages() {
        define("heartbeat", (byte) 1, HEADER_FIELDS);

        define("applicationDefinition", (byte) 2, HEADER_FIELDS);

        define("equityDefinition", (byte) 3, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("instrumentId", HEADER_LENGTH, SbeFieldType.INT, false),
        }));

        define("addOrder", (byte) 4, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("orderId", HEADER_LENGTH, SbeFieldType.INT, false),
                new SbeFieldLayout("side", HEADER_LENGTH + 4, SbeFieldType.BYTE, false),
                new SbeFieldLayout("qty", HEADER_LENGTH + 5, SbeFieldType.LONG, false),
                new SbeFieldLayout("instrumentId", HEADER_LENGTH + 13, SbeFieldType.INT, false),
                new SbeFieldLayout("price", HEADER_LENGTH + 17, SbeFieldType.LONG, false),
        }));

        define("cancelOrder", (byte) 5, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("orderId", HEADER_LENGTH, SbeFieldType.INT, false),
        }));

        define("fillOrder", (byte) 6, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("orderId", HEADER_LENGTH, SbeFieldType.INT, false),
                new SbeFieldLayout("qty", HEADER_LENGTH + 4, SbeFieldType.LONG, false),
                new SbeFieldLayout("price", HEADER_LENGTH + 12, SbeFieldType.LONG, false),
        }));

        define("rejectOrder", (byte) 7, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("side", HEADER_LENGTH, SbeFieldType.BYTE, false),
                new SbeFieldLayout("qty", HEADER_LENGTH + 1, SbeFieldType.LONG, false),
                new SbeFieldLayout("instrumentId", HEADER_LENGTH + 9, SbeFieldType.INT, false),
                new SbeFieldLayout("price", HEADER_LENGTH + 13, SbeFieldType.LONG, false),
        }));

        define("rejectCancel", (byte) 8, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("orderId", HEADER_LENGTH, SbeFieldType.INT, false),
        }));

        define("archiveAnnouncement", (byte) 9, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("archiveId", HEADER_LENGTH, SbeFieldType.SHORT, false),
                new SbeFieldLayout("recordingId", HEADER_LENGTH + 2, SbeFieldType.LONG, false),
                new SbeFieldLayout("role", HEADER_LENGTH + 10, SbeFieldType.BYTE, false),
        }));

        define("snapshotRequest", (byte) 10, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("snapshotId", HEADER_LENGTH, SbeFieldType.LONG, false),
                new SbeFieldLayout("requestTimestamp", HEADER_LENGTH + 8, SbeFieldType.LONG, false),
                new SbeFieldLayout("expectedNodeCount", HEADER_LENGTH + 16, SbeFieldType.SHORT, false),
        }));

        define("snapshotBegin", (byte) 11, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("snapshotId", HEADER_LENGTH, SbeFieldType.LONG, false),
                new SbeFieldLayout("checkpointSeqNum", HEADER_LENGTH + 8, SbeFieldType.LONG, false),
                new SbeFieldLayout("requestTimestamp", HEADER_LENGTH + 16, SbeFieldType.LONG, false),
                new SbeFieldLayout("expectedNodeCount", HEADER_LENGTH + 24, SbeFieldType.SHORT, false),
        }));

        define("snapshotChunk", (byte) 12, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("snapshotId", HEADER_LENGTH, SbeFieldType.LONG, false),
                new SbeFieldLayout("nodeId", HEADER_LENGTH + 8, SbeFieldType.SHORT, false),
                new SbeFieldLayout("chunkIndex", HEADER_LENGTH + 10, SbeFieldType.SHORT, false),
                new SbeFieldLayout("totalChunks", HEADER_LENGTH + 12, SbeFieldType.SHORT, false),
        }));

        define("snapshotComplete", (byte) 13, concat(HEADER_FIELDS, new SbeFieldLayout[]{
                new SbeFieldLayout("snapshotId", HEADER_LENGTH, SbeFieldType.LONG, false),
                new SbeFieldLayout("checkpointSeqNum", HEADER_LENGTH + 8, SbeFieldType.LONG, false),
                new SbeFieldLayout("nodeCount", HEADER_LENGTH + 16, SbeFieldType.SHORT, false),
                new SbeFieldLayout("validity", HEADER_LENGTH + 18, SbeFieldType.BYTE, false),
                new SbeFieldLayout("archiveRecordingId", HEADER_LENGTH + 19, SbeFieldType.LONG, false),
                new SbeFieldLayout("archivePosition", HEADER_LENGTH + 27, SbeFieldType.LONG, false),
        }));
    }

    private void define(String name, byte type, SbeFieldLayout[] fields) {
        messageDefs.put(name, new MessageDef(name, type, fields));
    }

    private static SbeFieldLayout[] concat(SbeFieldLayout[] a, SbeFieldLayout[] b) {
        var result = new SbeFieldLayout[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public String[] getMessageNames() {
        return MESSAGE_NAMES;
    }

    @Override
    public int getApplicationIdOffset() {
        return 0;
    }

    @Override
    public int getApplicationSequenceNumberOffset() {
        return 2;
    }

    @Override
    public int getTimestampOffset() {
        return 6;
    }

    @Override
    public int getOptionalFieldsOffset() {
        return 14;
    }

    @Override
    public int getLeaderEpochOffset() {
        return 16;
    }

    @Override
    public int getSchemaVersionOffset() {
        return 20;
    }

    @Override
    public int getMessageTypeOffset() {
        return 21;
    }

    @Override
    public int getMessageHeaderLength() {
        return HEADER_LENGTH;
    }

    @Override
    public String getProperty(String property) {
        return switch (property) {
            case "heartbeatMessageName" -> "heartbeat";
            case "applicationIdField" -> "applicationId";
            case "applicationDefinitionMessageName" -> "applicationDefinition";
            case "applicationDefinitionNameField" -> "name";
            default -> throw new IllegalArgumentException("unknown property: " + property);
        };
    }

    @Override
    public String[] getProperties() {
        return PROPERTIES;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Encoder> T createEncoder(String messageName) {
        var def = messageDefs.get(messageName);
        if (def == null) {
            throw new IllegalArgumentException("unknown message name: " + messageName);
        }
        return (T) new SbeEncoder(null, def.name, def.type, VERSION, def.fixedLength(), def.fields);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends Decoder> T createDecoder(String messageName) {
        var def = messageDefs.get(messageName);
        if (def == null) {
            throw new IllegalArgumentException("unknown message name: " + messageName);
        }
        return (T) new SbeDecoder(def.name, def.type, def.fields);
    }

    @Override
    public int getMessageType(String messageName) {
        var def = messageDefs.get(messageName);
        if (def == null) {
            throw new IllegalArgumentException("unknown message name: " + messageName);
        }
        return def.type;
    }

    @Override
    public String getMessageName(byte messageType) {
        for (var def : messageDefs.values()) {
            if (def.type == messageType) {
                return def.name;
            }
        }
        throw new IllegalArgumentException("unknown message type: " + messageType);
    }

    @Override
    public SbeDispatcher createDispatcher() {
        var decoders = new HashMap<Byte, SbeDecoder>();
        var nameToType = new HashMap<String, Byte>();
        for (var def : messageDefs.values()) {
            decoders.put(def.type, new SbeDecoder(def.name, def.type, def.fields));
            nameToType.put(def.name, def.type);
        }
        return new SbeDispatcher(decoders, nameToType, minCompatibleVersion);
    }

    @Override
    public SbeProvider createProvider(MessagePublisher messagePublisher) {
        var encoders = new HashMap<String, SbeEncoder>();
        for (var def : messageDefs.values()) {
            encoders.put(def.name, new SbeEncoder(
                    messagePublisher, def.name, def.type, VERSION, def.fixedLength(), def.fields));
        }
        return new SbeProvider(messagePublisher, encoders);
    }

    private record MessageDef(String name, byte type, SbeFieldLayout[] fields) {
        int fixedLength() {
            var maxEnd = HEADER_LENGTH;
            for (var f : fields) {
                if (!f.header()) {
                    var end = f.fieldOffset() + switch (f.fieldType()) {
                        case BYTE -> 1;
                        case SHORT -> 2;
                        case INT -> 4;
                        case LONG -> 8;
                    };
                    if (end > maxEnd) {
                        maxEnd = end;
                    }
                }
            }
            return maxEnd;
        }
    }
}
