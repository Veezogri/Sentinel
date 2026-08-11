package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sentinel.machine.domain.Machine;
import com.sentinel.simulation.anomaly.AnomalyPhase;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.testsupport.SequentialUuids;

/**
 * Spontaneous faults, as opposed to the forced ones every other test uses.
 *
 * <p>{@code anomalyProbability} means: the chance that <em>one healthy machine</em> develops a
 * fault on <em>one tick</em>. A machine already faulty is not rolled for, so the figure is a
 * hazard rate over healthy operation rather than a per-tick share of the fleet.
 *
 * <p>These tests stay deterministic despite testing randomness, because the seed fixes the
 * outcome: they assert on a run that is reproducible, not on a statistical expectation.
 */
class ProbabilisticAnomalyTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");

    private static SimulationEngine engine(double probability, long seed) {
        SimulationConfig config = SimulationConfig.of(20, seed, START)
                .withAnomalyProbability(probability);
        return new SimulationEngine(config, new SequentialUuids());
    }

    @Test
    void shouldNeverInjectAFaultWhenProbabilityIsZero() {
        SimulationEngine engine = engine(0.0, 1L);

        engine.run(2_000);

        assertThat(engine.machines()).allSatisfy(machine ->
                assertThat(engine.machineOrFail(machine.id()).isHealthy()).isTrue());
    }

    @Test
    void shouldEventuallyInjectFaultsWhenProbabilityIsNonZero() {
        SimulationEngine engine = engine(0.01, 1L);

        List<TelemetryEvent> events = engine.run(500);

        long faulty = engine.machines().stream()
                .map(Machine::id)
                .filter(id -> !engine.machineOrFail(id).isHealthy())
                .count();

        assertThat(faulty).as("some machines should be faulty after 500 ticks at p=0.01").isPositive();
        assertThat(events).isNotEmpty();
    }

    /**
     * A spontaneous communication loss silences its machine, so the fleet emits fewer events than
     * machines on some ticks. Anything consuming the simulator must tolerate that.
     */
    @Test
    void shouldSometimesEmitFewerEventsThanMachines() {
        SimulationEngine engine = engine(0.02, 4L);

        boolean sawIncompleteTick = false;
        for (int i = 0; i < 500 && !sawIncompleteTick; i++) {
            sawIncompleteTick = engine.tick().size() < 20;
        }

        assertThat(sawIncompleteTick).isTrue();
    }

    @Test
    void shouldRemainReproducibleDespiteProbabilisticFaults() {
        assertThat(engine(0.02, 9L).run(400)).isEqualTo(engine(0.02, 9L).run(400));
    }

    /**
     * A fault already under way must not be re-rolled, or it would restart every tick and stay
     * frozen at the beginning of its envelope forever — never reaching full strength, never
     * recovering. Probability 1 makes that failure certain if the guard is missing.
     */
    @Test
    void shouldLetARunningFaultAgeInsteadOfRestartingIt() {
        SimulationConfig config = new SimulationConfig(
                1, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(100), 2L, START);
        SimulationEngine engine = new SimulationEngine(config, new SequentialUuids());
        VirtualMachine machine = engine.machineOrFail(engine.machines().get(0).id());

        engine.run(1);
        assertThat(machine.anomalyPhaseAt(engine.currentTime())).isEqualTo(AnomalyPhase.DEVELOPING);

        engine.run(50);

        assertThat(machine.anomalyPhaseAt(engine.currentTime()))
                .as("a fault restarted every tick would never leave DEVELOPING")
                .isEqualTo(AnomalyPhase.ACTIVE);
    }
}
