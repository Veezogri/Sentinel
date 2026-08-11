package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

@SuppressWarnings("unchecked")
class TelemetryPublisherTest {

    private static final UUID MACHINE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");

    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final ArgumentCaptor<ProducerRecord<String, Object>> sent =
            ArgumentCaptor.forClass(ProducerRecord.class);

    private TelemetryPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TelemetryPublisher(kafkaTemplate, new TelemetryMessageMapper());
    }

    private void givenBrokerAccepts() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private void givenBrokerFails() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    private List<ProducerRecord<String, Object>> recordsSent(int expected) {
        verify(kafkaTemplate, times(expected)).send(sent.capture());
        return sent.getAllValues();
    }

    private static TelemetryEvent event(UUID machineId) {
        return new TelemetryEvent(UUID.randomUUID(), machineId, T0,
                new TelemetryReadings(62.0, 2.0, 5.0, 30.0, 1400.0));
    }

    /**
     * The single most important assertion about the producer: the key is the machine identifier.
     * Everything the platform assumes about per-machine ordering follows from it.
     */
    @Test
    void shouldKeyEachRecordByMachineId() {
        givenBrokerAccepts();

        publisher.publish(event(MACHINE_ID));

        ProducerRecord<String, Object> record = recordsSent(1).get(0);
        assertThat(record.key()).isEqualTo(MACHINE_ID.toString());
        assertThat(record.topic()).isEqualTo(KafkaTopics.TELEMETRY_RAW);
    }

    @Test
    void shouldGiveEveryEventOfAMachineTheSameKey() {
        givenBrokerAccepts();

        publisher.publish(event(MACHINE_ID));
        publisher.publish(event(MACHINE_ID));

        assertThat(recordsSent(2)).extracting(ProducerRecord::key)
                .containsExactly(MACHINE_ID.toString(), MACHINE_ID.toString());
    }

    @Test
    void shouldGiveDifferentMachinesDifferentKeys() {
        givenBrokerAccepts();
        UUID other = UUID.randomUUID();

        publisher.publish(event(MACHINE_ID));
        publisher.publish(event(other));

        assertThat(recordsSent(2)).extracting(ProducerRecord::key)
                .containsExactly(MACHINE_ID.toString(), other.toString());
    }

    /** Publishing the domain record instead would make every internal rename a wire break. */
    @Test
    void shouldPublishTheWireContractNotTheDomainObject() {
        givenBrokerAccepts();

        publisher.publish(event(MACHINE_ID));

        Object payload = recordsSent(1).get(0).value();
        assertThat(payload).isInstanceOf(TelemetryMessage.class);
        assertThat(((TelemetryMessage) payload).schemaVersion())
                .isEqualTo(TelemetryMessage.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void shouldStampSchemaVersionAndEventIdHeaders() {
        givenBrokerAccepts();
        TelemetryEvent event = event(MACHINE_ID);

        publisher.publish(event);

        ProducerRecord<String, Object> record = recordsSent(1).get(0);
        assertThat(header(record, "sentinel-schema-version")).isEqualTo("1");
        assertThat(header(record, "sentinel-event-id")).isEqualTo(event.eventId().toString());
    }

    @Test
    void shouldCountAcknowledgedSends() {
        givenBrokerAccepts();

        publisher.publish(event(MACHINE_ID));

        assertThat(publisher.publishedCount()).isEqualTo(1);
        assertThat(publisher.failedCount()).isZero();
    }

    /** An asynchronous failure must not vanish: this is the whole point of observing the future. */
    @Test
    void shouldCountAndNotSwallowAFailedSend() {
        givenBrokerFails();

        publisher.publish(event(MACHINE_ID));

        assertThat(publisher.failedCount()).isEqualTo(1);
        assertThat(publisher.publishedCount()).isZero();
    }

    private static String header(ProducerRecord<String, Object> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
