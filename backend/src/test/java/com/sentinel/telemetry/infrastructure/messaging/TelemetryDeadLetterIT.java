package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.KafkaIntegrationTestBase;

/**
 * The poison-pill scenario.
 *
 * <p>Kafka has no way to skip a record and come back to it: the offset is a position in a log, not
 * an item in a work queue. So a record that always fails will, if simply retried, block its
 * partition forever — and the consumer will look perfectly healthy while making no progress. The
 * only way forward is to move the record aside and commit past it.
 *
 * <p>The assertion that matters most here is the last one: <em>the consumer keeps working</em>.
 */
class TelemetryDeadLetterIT extends KafkaIntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Autowired
    private TelemetryPublisher publisher;

    @Autowired
    private MachineStateStore stateStore;

    @Autowired
    private MachineRegistry machineRegistry;

    private UUID machineId;

    @BeforeEach
    void registerMachine() {
        machineId = UUID.randomUUID();
        machineRegistry.register(
                Machine.register(machineId, "PUMP-DLQ", MachineType.PUMP, Instant.now()));
    }

    @Test
    void shouldSendAnUnparseablePayloadToTheDeadLetterTopic() {
        publishRaw(machineId.toString(), "this is not json at all");

        List<ConsumerRecord<String, String>> deadLettered = awaitDeadLetterRecords(1);

        assertThat(deadLettered).isNotEmpty();
        assertThat(deadLettered.get(0).value()).contains("not json");
    }

    /** Without the original coordinates a dead-lettered record cannot be traced back or replayed. */
    @Test
    void shouldPreserveDiagnosticContextOnTheDeadLetteredRecord() {
        publishRaw(machineId.toString(), "{\"schemaVersion\": broken");

        ConsumerRecord<String, String> record = awaitDeadLetterRecords(1).get(0);

        assertThat(headerNames(record))
                .contains("kafka_dlt-original-topic", "kafka_dlt-original-partition",
                        "kafka_dlt-original-offset", "kafka_dlt-exception-fqcn");
        assertThat(header(record, "kafka_dlt-original-topic")).isEqualTo(KafkaTopics.TELEMETRY_RAW);
    }

    /** Well-formed JSON that cannot become a valid domain object is equally non-retryable. */
    @Test
    void shouldDeadLetterAStructurallyValidButDomainInvalidPayload() {
        String negativeRpm = """
                {"schemaVersion":1,
                 "eventId":"%s",
                 "machineId":"%s",
                 "occurredAt":"2026-01-15T10:00:00Z",
                 "readings":{"temperatureCelsius":62.0,"vibrationMillimetresPerSecond":2.0,
                             "pressureBar":5.0,"powerConsumptionKilowatts":30.0,
                             "rotationSpeedRpm":-5.0}}
                """.formatted(UUID.randomUUID(), machineId);

        publishRaw(machineId.toString(), negativeRpm);

        assertThat(awaitDeadLetterRecords(1)).isNotEmpty();
    }

    @Test
    void shouldRejectAMessageAnnouncingAnUnknownSchemaVersion() {
        String futureVersion = """
                {"schemaVersion":99,
                 "eventId":"%s",
                 "machineId":"%s",
                 "occurredAt":"2026-01-15T10:00:00Z",
                 "readings":{"temperatureCelsius":62.0,"vibrationMillimetresPerSecond":2.0,
                             "pressureBar":5.0,"powerConsumptionKilowatts":30.0,
                             "rotationSpeedRpm":1400.0}}
                """.formatted(UUID.randomUUID(), machineId);

        publishRaw(machineId.toString(), futureVersion);

        assertThat(awaitDeadLetterRecords(1)).isNotEmpty();
    }

    /**
     * The whole reason the dead-letter topic exists. A bad record on a partition must not stop the
     * good records queued behind it — and both are on the same partition here, because they share
     * a key.
     */
    @Test
    void shouldKeepConsumingAfterAPoisonPill() {
        publishRaw(machineId.toString(), "garbage that will never parse");

        Instant occurredAt = Instant.parse("2026-01-15T17:00:00Z");
        publisher.publish(new TelemetryEvent(UUID.randomUUID(), machineId, occurredAt,
                new TelemetryReadings(64.0, 2.0, 5.0, 30.0, 1400.0)));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.lastTelemetryAt()).isEqualTo(occurredAt);
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(64.0);
                }));
    }

    private void publishRaw(String key, String payload) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(KafkaTopics.TELEMETRY_RAW, key, payload));
            producer.flush();
        }
    }

    private List<ConsumerRecord<String, String>> awaitDeadLetterRecords(int atLeast) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-inspector-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopics.DEAD_LETTER));
            await().atMost(TIMEOUT).until(() -> {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
                polled.forEach(collected::add);
                return collected.size() >= atLeast;
            });
        }
        return collected;
    }

    private static List<String> headerNames(ConsumerRecord<String, String> record) {
        List<String> names = new ArrayList<>();
        record.headers().forEach(header -> names.add(header.key()));
        return names;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
