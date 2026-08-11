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
import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.KafkaIntegrationTestBase;

/**
 * How the pipeline behaves with out-of-order and repeated delivery.
 *
 * <p>The two are kept apart on purpose, because they are different problems with different fixes:
 *
 * <ul>
 *   <li>A <strong>late event</strong> is a distinct event whose timestamp precedes the newest one
 *       already seen. It is real data and must be accepted; it simply must not drag current state
 *       backwards. Historical persistence will still want it (M4).</li>
 *   <li>A <strong>duplicate</strong> is the same {@code eventId} delivered twice. Nothing here
 *       suppresses it yet, and this test documents that rather than hiding it.</li>
 * </ul>
 */
class TelemetryOrderingIT extends KafkaIntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Instant T1 = Instant.parse("2026-01-15T15:00:01Z");
    private static final Instant T2 = Instant.parse("2026-01-15T15:00:02Z");
    private static final Instant T3 = Instant.parse("2026-01-15T15:00:03Z");

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
                Machine.register(machineId, "PUMP-ORD", MachineType.PUMP, Instant.now()));
    }

    private TelemetryEvent event(Instant occurredAt, double temperature) {
        return new TelemetryEvent(UUID.randomUUID(), machineId, occurredAt,
                new TelemetryReadings(temperature, 2.0, 5.0, 30.0, 1400.0));
    }

    /**
     * Publish t1, t3, then t2. Because all three share a key they share a partition, so they are
     * consumed in the order published — and t2, arriving after t3, must not overwrite it.
     */
    @Test
    void shouldNotLetALateEventOverwriteNewerState() {
        publisher.publish(event(T1, 61.0));
        publisher.publish(event(T3, 63.0));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId))
                        .hasValueSatisfying(state -> assertThat(state.lastTelemetryAt()).isEqualTo(T3)));

        publisher.publish(event(T2, 62.0));

        // Give the late event time to be consumed, then assert it changed nothing.
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.lastTelemetryAt()).isEqualTo(T3);
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(63.0);
                }));
    }

    @Test
    void shouldEndAtTheNewestEventWhateverTheOrderOfPublication() {
        publisher.publish(event(T2, 62.0));
        publisher.publish(event(T1, 61.0));
        publisher.publish(event(T3, 63.0));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.lastTelemetryAt()).isEqualTo(T3);
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(63.0);
                }));
    }

    /**
     * The same event, published twice, is consumed twice: there is no durable deduplication in
     * this milestone. State happens to be unaffected because a redelivery carries the same
     * timestamp and is therefore declined — but that is state protection, not idempotency, and it
     * would not stop a duplicate alert once alerts exist.
     */
    @Test
    void shouldCurrentlyProcessADuplicateEventTwiceWithoutCorruptingState() {
        TelemetryEvent duplicated = event(T2, 77.0);

        publisher.publish(duplicated);
        publisher.publish(duplicated);

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId)).hasValueSatisfying(state -> {
                    assertThat(state.lastTelemetryAt()).isEqualTo(T2);
                    assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(77.0);
                }));
    }

    @Test
    void shouldPreserveOrderAcrossALongerBurstForOneMachine() {
        Instant start = Instant.parse("2026-01-15T16:00:00Z");
        int count = 100;
        for (int i = 0; i < count; i++) {
            publisher.publish(event(start.plusSeconds(i), 60.0 + (i % 10)));
        }

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(stateStore.find(machineId))
                        .hasValueSatisfying(state -> assertThat(state.lastTelemetryAt())
                                .isEqualTo(start.plusSeconds(count - 1L))));
    }
}
