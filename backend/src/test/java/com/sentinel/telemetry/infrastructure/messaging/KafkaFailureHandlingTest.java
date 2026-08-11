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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * Failure handling proven against a real broker: retry policy, dead-lettering, and — the
 * assertion that matters most — the consumer surviving a poison pill.
 *
 * <p>Kafka cannot skip a record and return to it later: an offset is a position in a log, not an
 * item in a work queue. A record that always fails and is always retried therefore blocks its
 * partition permanently, while the consumer still looks healthy. Moving it aside and committing
 * past it is the only way to make progress.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = KafkaTopics.TELEMETRY_RAW_PARTITIONS,
        topics = {KafkaTopics.TELEMETRY_RAW, KafkaTopics.DEAD_LETTER},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaFailureHandlingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Autowired
    private TelemetryPublisher publisher;

    @Autowired
    private MachineStateStore stateStore;

    @Autowired
    private MachineRegistry machineRegistry;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private UUID machineId;

    @BeforeEach
    void registerMachine() {
        machineId = UUID.randomUUID();
        machineRegistry.register(
                Machine.register(machineId, "PUMP-FAIL", MachineType.PUMP, Instant.now()));
    }

    @Test
    void shouldDeadLetterAnUnparseablePayload() {
        String marker = "not-json-" + UUID.randomUUID();
        publishRaw(machineId.toString(), marker);

        assertThat(awaitDeadLetterRecordContaining(marker).value()).contains(marker);
    }

    @Test
    void shouldDeadLetterAStructurallyValidButDomainInvalidPayload() {
        UUID eventId = UUID.randomUUID();
        publishRaw(machineId.toString(), telemetryJson(eventId, 1, -5.0));

        assertThat(awaitDeadLetterRecordContaining(eventId.toString())).isNotNull();
    }

    @Test
    void shouldDeadLetterAnUnknownSchemaVersion() {
        UUID eventId = UUID.randomUUID();
        publishRaw(machineId.toString(), telemetryJson(eventId, 99, 1400.0));

        assertThat(awaitDeadLetterRecordContaining(eventId.toString())).isNotNull();
    }

    /** Without the original coordinates, a dead-lettered record cannot be traced or replayed. */
    @Test
    void shouldPreserveDiagnosticContextOnTheDeadLetteredRecord() {
        String marker = "broken-" + UUID.randomUUID();
        publishRaw(machineId.toString(), "{\"schemaVersion\": " + marker);

        ConsumerRecord<String, String> record = awaitDeadLetterRecordContaining(marker);
        List<String> headers = new ArrayList<>();
        record.headers().forEach(header -> headers.add(header.key()));

        assertThat(headers).contains(
                "kafka_dlt-original-topic",
                "kafka_dlt-original-partition",
                "kafka_dlt-original-offset",
                "kafka_dlt-exception-fqcn");
        assertThat(header(record, "kafka_dlt-original-topic")).isEqualTo(KafkaTopics.TELEMETRY_RAW);
    }

    /**
     * The point of the dead-letter topic. The bad record and the good one share a key, so they
     * share a partition: if the poison pill blocked, the valid event behind it would never arrive.
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

    @Test
    void shouldSurviveSeveralPoisonPillsInterleavedWithValidEvents() {
        Instant start = Instant.parse("2026-01-15T18:00:00Z");
        String run = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            publishRaw(machineId.toString(), "poison-" + run + "-" + i);
            publisher.publish(new TelemetryEvent(UUID.randomUUID(), machineId, start.plusSeconds(i),
                    new TelemetryReadings(60.0 + i, 2.0, 5.0, 30.0, 1400.0)));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state ->
                        assertThat(state.lastTelemetryAt()).isEqualTo(start.plusSeconds(4))));

        List<ConsumerRecord<String, String>> deadLettered = awaitDeadLetterRecords(records ->
                records.stream().filter(record -> record.value().contains(run)).count() >= 5);

        assertThat(deadLettered).filteredOn(record -> record.value().contains(run)).hasSize(5);
    }

    private String telemetryJson(UUID eventId, int schemaVersion, double rpm) {
        return """
                {"schemaVersion":%d,"eventId":"%s","machineId":"%s",
                 "occurredAt":"2026-01-15T10:00:00Z",
                 "readings":{"temperatureCelsius":62.0,"vibrationMillimetresPerSecond":2.0,
                             "pressureBar":5.0,"powerConsumptionKilowatts":30.0,
                             "rotationSpeedRpm":%s}}
                """.formatted(schemaVersion, eventId, machineId, rpm);
    }

    private void publishRaw(String key, String payload) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(KafkaTopics.TELEMETRY_RAW, key, payload));
            producer.flush();
        }
    }

    /**
     * All tests in this class share one broker and one dead-letter topic, and JUnit does not
     * promise an execution order — so a test must identify <em>its own</em> record by a marker
     * rather than assuming it is the first one on the topic.
     */
    private ConsumerRecord<String, String> awaitDeadLetterRecordContaining(String marker) {
        List<ConsumerRecord<String, String>> matching = awaitDeadLetterRecords(
                records -> records.stream().anyMatch(record -> record.value().contains(marker)));

        return matching.stream()
                .filter(record -> record.value().contains(marker))
                .findFirst()
                .orElseThrow();
    }

    private List<ConsumerRecord<String, String>> awaitDeadLetterRecords(
            java.util.function.Predicate<List<ConsumerRecord<String, String>>> done) {

        List<ConsumerRecord<String, String>> collected = new ArrayList<>();

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-inspector-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaTopics.DEAD_LETTER));
            await().atMost(TIMEOUT).until(() -> {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
                polled.forEach(collected::add);
                return done.test(collected);
            });
        }
        return collected;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
