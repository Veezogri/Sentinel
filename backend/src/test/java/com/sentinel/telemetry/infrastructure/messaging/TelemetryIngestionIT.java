package com.sentinel.telemetry.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.sentinel.machine.application.MachineRegistry;
import com.sentinel.machine.application.MachineStateStore;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.KafkaIntegrationTestBase;

/**
 * The whole pipeline against a real broker:
 * publisher → Kafka → listener → mapper → processor → state store and rule engine.
 *
 * <p>Assertions are on business outcomes rather than on plumbing. That the machine's stored health
 * became {@code CRITICAL} proves the record was serialised, partitioned, consumed, deserialised,
 * mapped into the domain, evaluated by the rules and written to state — every step, without
 * asserting any of them individually.
 */
class TelemetryIngestionIT extends KafkaIntegrationTestBase {

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
                Machine.register(machineId, "PUMP-IT", MachineType.PUMP, Instant.now()));
    }

    private TelemetryEvent event(Instant occurredAt, double temperature) {
        return new TelemetryEvent(UUID.randomUUID(), machineId, occurredAt,
                new TelemetryReadings(temperature, 2.0, 5.0, 30.0, 1400.0));
    }

    @Test
    void shouldCarryNominalTelemetryIntoMachineState() {
        Instant occurredAt = Instant.parse("2026-01-15T10:00:00Z");

        publisher.publish(event(occurredAt, 62.0));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(62.0);
                    assertThat(state.lastTelemetryAt()).isEqualTo(occurredAt);
                    assertThat(state.healthStatus()).isEqualTo(HealthStatus.NORMAL);
                }));
    }

    @Test
    void shouldDriveMachineHealthToCriticalWhenTelemetryBreachesTheThreshold() {
        publisher.publish(event(Instant.parse("2026-01-15T10:00:00Z"), 99.0));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId))
                        .hasValueSatisfying(state ->
                                assertThat(state.healthStatus()).isEqualTo(HealthStatus.CRITICAL)));
    }

    @Test
    void shouldProcessASequenceOfEventsAndKeepTheLatest() {
        Instant start = Instant.parse("2026-01-15T11:00:00Z");
        for (int i = 0; i < 20; i++) {
            publisher.publish(event(start.plusSeconds(i), 60.0 + i));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.lastTelemetryAt()).isEqualTo(start.plusSeconds(19));
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(79.0);
                }));
    }

    @Test
    void shouldKeepMachinesIndependentAcrossTheTopic() {
        UUID otherMachine = UUID.randomUUID();
        machineRegistry.register(
                Machine.register(otherMachine, "TURBINE-IT", MachineType.TURBINE, Instant.now()));
        Instant occurredAt = Instant.parse("2026-01-15T12:00:00Z");

        publisher.publish(event(occurredAt, 62.0));
        publisher.publish(new TelemetryEvent(UUID.randomUUID(), otherMachine, occurredAt,
                new TelemetryReadings(99.0, 2.0, 5.0, 30.0, 1400.0)));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(stateStore.find(machineId)).hasValueSatisfying(state ->
                    assertThat(state.healthStatus()).isEqualTo(HealthStatus.NORMAL));
            assertThat(stateStore.find(otherMachine)).hasValueSatisfying(state ->
                    assertThat(state.healthStatus()).isEqualTo(HealthStatus.CRITICAL));
        });
    }
}
