package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sentinel.machine.domain.ConnectivityStatus;
import com.sentinel.machine.domain.HealthStatus;
import com.sentinel.machine.domain.MachineState;
import com.sentinel.simulation.anomaly.AnomalyType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.testsupport.SequentialUuids;

/**
 * Communication loss is modelled as silence, not as a distorted reading.
 *
 * <p>Emitting an event flagged "offline" would be self-defeating: the platform detects offline
 * machines precisely by the absence of telemetry, so a machine that keeps reporting is, by
 * definition, online.
 */
class CommunicationLossTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Duration OUTAGE = Duration.ofSeconds(60);
    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(30);

    private SimulationEngine engine;
    private UUID machineId;

    private SimulationEngine newEngine() {
        engine = new SimulationEngine(SimulationConfig.of(1, 3L, START), new SequentialUuids());
        machineId = engine.machines().get(0).id();
        return engine;
    }

    @Test
    void shouldStopEmittingWhileTheLinkIsDownAndResumeAfterwards() {
        newEngine();

        List<TelemetryEvent> before = engine.run(10);
        engine.triggerAnomaly(machineId, AnomalyType.COMMUNICATION_LOSS, OUTAGE);
        List<TelemetryEvent> during = engine.run(50);
        List<TelemetryEvent> after = engine.run(30);

        assertThat(before).hasSize(10);
        assertThat(during).as("a machine with no link reports nothing").isEmpty();
        assertThat(after).as("reporting resumes once the outage ends").isNotEmpty();
    }

    @Test
    void shouldReportTheMachineAsSilentOnlyDuringTheOutage() {
        newEngine();
        engine.run(5);

        engine.triggerAnomaly(machineId, AnomalyType.COMMUNICATION_LOSS, OUTAGE);
        assertThat(engine.machineOrFail(machineId).isSilent()).isTrue();

        engine.run(70);

        assertThat(engine.machineOrFail(machineId).isSilent()).isFalse();
    }

    /**
     * The point of the whole design: the gap the simulator produces is exactly what
     * {@link MachineState#connectivityAt} consumes.
     */
    @Test
    void shouldProduceAGapThatTheDomainReadsAsOffline() {
        newEngine();
        List<TelemetryEvent> before = engine.run(10);
        TelemetryEvent lastHeard = before.get(before.size() - 1);

        MachineState state = MachineState.fromFirstEvent(lastHeard, HealthStatus.NORMAL, lastHeard.occurredAt());

        engine.triggerAnomaly(machineId, AnomalyType.COMMUNICATION_LOSS, OUTAGE);
        engine.run(50);

        assertThat(state.connectivityAt(lastHeard.occurredAt().plusSeconds(10), OFFLINE_AFTER))
                .as("still within the tolerated silence")
                .isEqualTo(ConnectivityStatus.ONLINE);
        assertThat(state.connectivityAt(engine.currentTime(), OFFLINE_AFTER))
                .as("silence has outlasted the threshold")
                .isEqualTo(ConnectivityStatus.OFFLINE);
    }

    /**
     * The equipment keeps running while the link is down. Readings must reflect the elapsed time,
     * not resume from where the outage began, otherwise a long outage would hide a fault that
     * developed during it.
     */
    @Test
    void shouldKeepEvolvingInternalStateWhileSilent() {
        newEngine();
        engine.run(5);

        engine.triggerAnomaly(machineId, AnomalyType.COMMUNICATION_LOSS, OUTAGE);
        double atOutageStart = engine.machineOrFail(machineId).valueOf(Signal.TEMPERATURE);
        engine.run(50);
        double duringOutage = engine.machineOrFail(machineId).valueOf(Signal.TEMPERATURE);

        assertThat(duringOutage).isNotEqualTo(atOutageStart);
    }

    @Test
    void shouldNotSilenceOtherMachinesInTheFleet() {
        SimulationEngine fleet =
                new SimulationEngine(SimulationConfig.of(4, 3L, START), new SequentialUuids());
        UUID silenced = fleet.machines().get(0).id();

        fleet.triggerAnomaly(silenced, AnomalyType.COMMUNICATION_LOSS, OUTAGE);
        List<TelemetryEvent> events = fleet.run(20);

        assertThat(events).extracting(TelemetryEvent::machineId).doesNotContain(silenced);
        assertThat(events).hasSize(3 * 20);
    }
}
