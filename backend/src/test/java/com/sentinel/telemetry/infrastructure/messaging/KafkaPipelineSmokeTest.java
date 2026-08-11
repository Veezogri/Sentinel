package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import com.sentinel.infrastructure.kafka.KafkaTopics;
import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

/**
 * The pipeline running against a real, in-process broker.
 *
 * <p>Two tiers of Kafka test exist in this project, on purpose:
 *
 * <ul>
 *   <li>This one, on an <strong>embedded</strong> broker. It runs in {@code mvn test} on any
 *       machine, so the pipeline is exercised on every build rather than only where Docker
 *       happens to be installed. It is genuine Kafka — partitions, keys, consumer groups,
 *       offsets — not a mock.</li>
 *   <li>The {@code *IT} classes, on a <strong>containerised</strong> broker via Testcontainers.
 *       They run the same scenarios against the exact image used in Compose, which is what
 *       catches version-specific behaviour an embedded broker would not.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = KafkaTopics.TELEMETRY_RAW_PARTITIONS,
        topics = {KafkaTopics.TELEMETRY_RAW, KafkaTopics.DEAD_LETTER},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaPipelineSmokeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
                Machine.register(machineId, "PUMP-SMOKE", MachineType.PUMP, Instant.now()));
    }

    private TelemetryEvent event(Instant occurredAt, double temperature) {
        return new TelemetryEvent(UUID.randomUUID(), machineId, occurredAt,
                new TelemetryReadings(temperature, 2.0, 5.0, 30.0, 1400.0));
    }

    /**
     * Publisher → Kafka → listener → mapper → processor → state store, asserted on the business
     * outcome rather than on any single step.
     */
    @Test
    void shouldCarryTelemetryThroughTheWholePipeline() {
        Instant occurredAt = Instant.parse("2026-01-15T10:00:00Z");

        publisher.publish(event(occurredAt, 62.0));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(62.0);
                    assertThat(state.lastTelemetryAt()).isEqualTo(occurredAt);
                    assertThat(state.healthStatus()).isEqualTo(HealthStatus.NORMAL);
                }));
    }

    /** Proves the rule engine really runs in the consumer path, not just in unit tests. */
    @Test
    void shouldDriveHealthToCriticalThroughTheRuleEngine() {
        publisher.publish(event(Instant.parse("2026-01-15T10:05:00Z"), 99.0));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state ->
                        assertThat(state.healthStatus()).isEqualTo(HealthStatus.CRITICAL)));
    }

    @Test
    void shouldKeepTheNewestEventOfABurst() {
        Instant start = Instant.parse("2026-01-15T10:10:00Z");
        for (int i = 0; i < 50; i++) {
            publisher.publish(event(start.plusSeconds(i), 60.0 + (i % 5)));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state ->
                        assertThat(state.lastTelemetryAt()).isEqualTo(start.plusSeconds(49))));
    }

    /** A late event is accepted by the consumer but must not drag current state backwards. */
    @Test
    void shouldNotLetALateEventOverwriteNewerState() {
        Instant t1 = Instant.parse("2026-01-15T10:20:01Z");
        Instant t3 = Instant.parse("2026-01-15T10:20:03Z");
        Instant t2 = Instant.parse("2026-01-15T10:20:02Z");

        publisher.publish(event(t1, 61.0));
        publisher.publish(event(t3, 63.0));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state ->
                        assertThat(state.lastTelemetryAt()).isEqualTo(t3)));

        publisher.publish(event(t2, 62.0));

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.lastTelemetryAt()).isEqualTo(t3);
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(63.0);
                }));
    }
}
