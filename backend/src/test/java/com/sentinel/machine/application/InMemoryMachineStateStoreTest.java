package com.sentinel.machine.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.MachineState;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;

class InMemoryMachineStateStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-15T10:00:00Z");
    private static final UUID MACHINE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final InMemoryMachineStateStore store = new InMemoryMachineStateStore();

    private static TelemetryEvent eventAt(UUID machineId, Instant occurredAt, double temperature) {
        return new TelemetryEvent(UUID.randomUUID(), machineId, occurredAt,
                new TelemetryReadings(temperature, 2.0, 5.0, 30.0, 1400.0));
    }

    @Test
    void shouldStoreTheFirstEventForAMachine() {
        MachineState state = store.apply(eventAt(MACHINE, T0, 60.0), HealthStatus.NORMAL, T0).state();

        assertThat(state.machineId()).isEqualTo(MACHINE);
        assertThat(state.latestReadings().temperatureCelsius()).isEqualTo(60.0);
        assertThat(store.find(MACHINE)).contains(state);
    }

    @Test
    void shouldReplaceStateWithANewerEvent() {
        store.apply(eventAt(MACHINE, T0, 60.0), HealthStatus.NORMAL, T0);

        MachineState updated = store.apply(
                eventAt(MACHINE, T0.plusSeconds(1), 88.0), HealthStatus.WARNING, T0.plusSeconds(1)).state();

        assertThat(updated.latestReadings().temperatureCelsius()).isEqualTo(88.0);
        assertThat(updated.healthStatus()).isEqualTo(HealthStatus.WARNING);
    }

    @Test
    void shouldIgnoreAnOlderEventWithoutLosingCurrentState() {
        store.apply(eventAt(MACHINE, T0.plusSeconds(10), 88.0), HealthStatus.WARNING, T0);

        MachineState afterLate = store.apply(eventAt(MACHINE, T0, 60.0), HealthStatus.NORMAL, T0).state();

        assertThat(afterLate.latestReadings().temperatureCelsius()).isEqualTo(88.0);
        assertThat(afterLate.lastTelemetryAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void shouldKeepMachinesIndependent() {
        UUID other = UUID.randomUUID();

        store.apply(eventAt(MACHINE, T0, 60.0), HealthStatus.NORMAL, T0);
        store.apply(eventAt(other, T0, 90.0), HealthStatus.CRITICAL, T0);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find(MACHINE).orElseThrow().healthStatus()).isEqualTo(HealthStatus.NORMAL);
        assertThat(store.find(other).orElseThrow().healthStatus()).isEqualTo(HealthStatus.CRITICAL);
    }

    @Test
    void shouldReturnEmptyForAMachineThatNeverReported() {
        assertThat(store.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void shouldReportWhetherTheStateActuallyMovedForward() {
        assertThat(store.apply(eventAt(MACHINE, T0, 60.0), HealthStatus.NORMAL, T0).advanced())
                .as("first event for a machine")
                .isTrue();
        assertThat(store.apply(eventAt(MACHINE, T0.plusSeconds(1), 61.0), HealthStatus.NORMAL, T0).advanced())
                .as("newer event")
                .isTrue();
        assertThat(store.apply(eventAt(MACHINE, T0, 99.0), HealthStatus.CRITICAL, T0).advanced())
                .as("older event is declined")
                .isFalse();
    }

    /**
     * A redelivery carries the same timestamp as the state it produced. Inferring "advanced" by
     * comparing that timestamp to the stored one reports true here, which is wrong — which is why
     * the store answers the question itself instead of leaving it to the caller.
     */
    @Test
    void shouldNotReportAdvanceForAnEventWithTheSameTimestampAsCurrentState() {
        TelemetryEvent event = eventAt(MACHINE, T0, 60.0);

        assertThat(store.apply(event, HealthStatus.NORMAL, T0).advanced()).isTrue();
        assertThat(store.apply(event, HealthStatus.NORMAL, T0).advanced()).isFalse();
    }

    /**
     * The reason {@code compute} is used rather than {@code get} followed by {@code put}.
     *
     * <p>Many threads push strictly increasing timestamps for the same machine at once. With a
     * non-atomic read-modify-write, two threads read the same state and the later write discards
     * the earlier one, so the final state lands below the highest timestamp sent. With an atomic
     * update, the final state must be exactly the newest event regardless of interleaving.
     */
    @Test
    void shouldNotLoseUpdatesUnderConcurrentWritesToTheSameMachine() throws Exception {
        int threads = 8;
        int eventsPerThread = 400;
        int total = threads * eventsPerThread;

        CountDownLatch startLine = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Runnable> tasks = IntStream.range(0, threads).<Runnable>mapToObj(threadIndex -> () -> {
                try {
                    startLine.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < eventsPerThread; i++) {
                    int sequence = threadIndex * eventsPerThread + i;
                    Instant occurredAt = T0.plusMillis(sequence);
                    store.apply(eventAt(MACHINE, occurredAt, 60.0 + sequence), HealthStatus.NORMAL, occurredAt);
                }
            }).toList();

            tasks.forEach(pool::submit);
            startLine.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        MachineState finalState = store.find(MACHINE).orElseThrow();
        assertThat(finalState.lastTelemetryAt())
                .as("the newest event must win regardless of thread interleaving")
                .isEqualTo(T0.plusMillis(total - 1L));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void shouldHandleConcurrentWritesAcrossManyMachines() throws Exception {
        int machines = 50;
        List<UUID> ids = IntStream.range(0, machines).mapToObj(i -> UUID.randomUUID()).toList();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 20; round++) {
                Instant at = T0.plusSeconds(round);
                for (UUID id : ids) {
                    pool.submit(() -> store.apply(eventAt(id, at, 60.0), HealthStatus.NORMAL, at));
                }
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(store.size()).isEqualTo(machines);
        assertThat(ids).allSatisfy(id -> assertThat(store.find(id)).isPresent());
    }
}
