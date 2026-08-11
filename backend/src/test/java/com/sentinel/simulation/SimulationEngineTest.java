package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sentinel.machine.domain.Machine;
import com.sentinel.machine.domain.MachineType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.testsupport.SequentialUuids;

class SimulationEngineTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final long SEED = 42L;

    private static SimulationEngine engine(int machineCount, long seed) {
        return new SimulationEngine(SimulationConfig.of(machineCount, seed, START), new SequentialUuids());
    }

    @Nested
    class Determinism {

        @Test
        void shouldProduceIdenticalSequencesForTheSameSeed() {
            List<TelemetryEvent> first = engine(5, SEED).run(200);
            List<TelemetryEvent> second = engine(5, SEED).run(200);

            assertThat(second).isEqualTo(first);
        }

        @Test
        void shouldProduceDifferentTrajectoriesForDifferentSeeds() {
            List<TelemetryEvent> first = engine(3, SEED).run(100);
            List<TelemetryEvent> second = engine(3, SEED + 1).run(100);

            assertThat(second).isNotEqualTo(first);
        }

        @Test
        void shouldAddressTheSameMachineIdentifiersOnEveryRunOfTheSameSeed() {
            assertThat(engine(5, SEED).machines()).isEqualTo(engine(5, SEED).machines());
        }

        /**
         * Each machine draws from its own generator, so growing the fleet must not perturb the
         * machines that were already in it. Without this, retuning the fleet size would silently
         * invalidate every recorded trajectory.
         */
        @Test
        void shouldNotChangeAnExistingMachineTrajectoryWhenTheFleetGrows() {
            List<TelemetryEvent> smallFleet = engine(2, SEED).run(50);
            List<TelemetryEvent> largeFleet = engine(6, SEED).run(50);

            UUID firstMachine = engine(2, SEED).machines().get(0).id();
            assertThat(readingsOf(largeFleet, firstMachine))
                    .isEqualTo(readingsOf(smallFleet, firstMachine));
        }

        private static List<?> readingsOf(List<TelemetryEvent> events, UUID machineId) {
            return events.stream()
                    .filter(event -> event.machineId().equals(machineId))
                    .map(TelemetryEvent::readings)
                    .toList();
        }
    }

    @Nested
    class SimulatedTime {

        @Test
        void shouldStampEachTickWithTheSimulatedInstant() {
            SimulationEngine engine = engine(1, SEED);

            List<TelemetryEvent> events = engine.run(5);

            assertThat(events).extracting(TelemetryEvent::occurredAt).containsExactly(
                    START,
                    START.plusSeconds(1),
                    START.plusSeconds(2),
                    START.plusSeconds(3),
                    START.plusSeconds(4));
        }

        @Test
        void shouldAdvanceSimulatedClockByTheConfiguredInterval() {
            SimulationConfig config = new SimulationConfig(
                    1, Duration.ofMillis(250), 0.0, Duration.ofMinutes(1), SEED, START);
            SimulationEngine engine = new SimulationEngine(config, new SequentialUuids());

            engine.run(4);

            assertThat(engine.currentTime()).isEqualTo(START.plusSeconds(1));
            assertThat(engine.tickCount()).isEqualTo(4);
        }

        /** Ten thousand ticks of simulated time must not take ten thousand seconds. */
        @Test
        void shouldGenerateLongRunsWithoutSleeping() {
            SimulationEngine engine = engine(1, SEED);

            List<TelemetryEvent> events = engine.run(10_000);

            assertThat(events).hasSize(10_000);
            assertThat(engine.currentTime()).isEqualTo(START.plusSeconds(10_000));
        }
    }

    @Nested
    class Fleet {

        @Test
        void shouldCreateRequestedNumberOfMachines() {
            assertThat(engine(100, SEED).machines()).hasSize(100);
            assertThat(engine(100, SEED).tick()).hasSize(100);
        }

        @Test
        void shouldSpreadMachinesAcrossEveryType() {
            List<Machine> machines = engine(10, SEED).machines();

            assertThat(machines).extracting(Machine::type)
                    .containsAll(List.of(MachineType.values()));
        }

        @Test
        void shouldNameMachinesByTypeAndSequence() {
            List<Machine> machines = engine(7, SEED).machines();

            assertThat(machines).extracting(Machine::name).containsExactly(
                    "PUMP-001", "COMPRESSOR-001", "TURBINE-001", "MOTOR-001", "GENERATOR-001",
                    "PUMP-002", "COMPRESSOR-002");
        }

        @Test
        void shouldKeepMachineIdentifiersStableAcrossTicks() {
            SimulationEngine engine = engine(3, SEED);

            List<UUID> firstTick = engine.tick().stream().map(TelemetryEvent::machineId).toList();

            List<TelemetryEvent> later = engine.run(20);
            List<UUID> lastTick = later.subList(later.size() - firstTick.size(), later.size())
                    .stream().map(TelemetryEvent::machineId).toList();

            assertThat(firstTick).hasSize(3);
            assertThat(lastTick).isEqualTo(firstTick);
        }

        @Test
        void shouldRejectAnomalyRequestForAnUnknownMachine() {
            assertThatThrownBy(() -> engine(1, SEED).triggerAnomaly(
                    UUID.randomUUID(),
                    com.sentinel.simulation.anomaly.AnomalyType.OVERHEATING,
                    Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no simulated machine");
        }
    }

    @Test
    void shouldGiveEveryEventItsOwnIdentifier() {
        List<TelemetryEvent> events = new SimulationEngine(SimulationConfig.of(3, SEED, START)).run(50);

        assertThat(events).extracting(TelemetryEvent::eventId).doesNotHaveDuplicates();
    }
}
