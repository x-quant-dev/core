package com.core.platform.schema.sbe;

import com.core.infrastructure.messages.MessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.mockito.Mockito.mock;

public class SbeSchemaTest {

    private SbeSchema schema;

    @BeforeEach
    void before_each() {
        schema = new SbeSchema();
    }

    @Test
    void headerLength_is_22() {
        then(schema.getMessageHeaderLength()).isEqualTo(22);
    }

    @Test
    void applicationId_offset_is_0() {
        then(schema.getApplicationIdOffset()).isEqualTo(0);
    }

    @Test
    void applicationSequenceNumber_offset_is_2() {
        then(schema.getApplicationSequenceNumberOffset()).isEqualTo(2);
    }

    @Test
    void timestamp_offset_is_6() {
        then(schema.getTimestampOffset()).isEqualTo(6);
    }

    @Test
    void messageType_offset_is_21() {
        then(schema.getMessageTypeOffset()).isEqualTo(21);
    }

    @Test
    void version_is_2() {
        then(schema.getVersion()).isEqualTo(2);
    }

    @Test
    void getMessageNames_returns_13_names() {
        then(schema.getMessageNames()).hasSize(13);
    }

    @Test
    void getMessageType_heartbeat_is_1() {
        then(schema.getMessageType("heartbeat")).isEqualTo(1);
    }

    @Test
    void getMessageType_addOrder_is_4() {
        then(schema.getMessageType("addOrder")).isEqualTo(4);
    }

    @Test
    void getMessageName_type_1_is_heartbeat() {
        then(schema.getMessageName((byte) 1)).isEqualTo("heartbeat");
    }

    @Test
    void getMessageType_invalid_name_throws() {
        thenThrownBy(() -> schema.getMessageType("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getMessageName_invalid_type_throws() {
        thenThrownBy(() -> schema.getMessageName((byte) 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getProperty_heartbeatMessageName() {
        then(schema.getProperty("heartbeatMessageName")).isEqualTo("heartbeat");
    }

    @Test
    void getProperty_unknown_throws() {
        thenThrownBy(() -> schema.getProperty("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createEncoder_returns_non_null() {
        SbeEncoder encoder = schema.createEncoder("addOrder");
        then(encoder).isNotNull();
    }

    @Test
    void createDecoder_returns_non_null() {
        SbeDecoder decoder = schema.createDecoder("addOrder");
        then(decoder).isNotNull();
    }

    @Test
    void createDispatcher_returns_non_null() {
        then(schema.createDispatcher()).isNotNull();
    }

    @Test
    void createProvider_with_mock_publisher() {
        var publisher = mock(MessagePublisher.class);

        then(schema.createProvider(publisher)).isNotNull();
    }
}
