package com.sentinel.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.sentinel.simulation.anomaly.AnomalyPhase;
import com.sentinel.simulation.anomaly.AnomalyType;
import com.sentinel.telemetry.domain.TelemetryEvent;
import com.sentinel.telemetry.domain.TelemetryReadings;
import com.sentinel.testsupport.SequentialUuids;

/**
 * End-to-end behaviour of a fault: healthy, developing, crossing a threshold, persisting, then
 * recovering. Faults are forced rather than rolled for, so no test here depends on a probability.
 */
class AnomalyLifecycleTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Duration FAULT_DURATION = Duration.ofMinutes(2);
    private static final int FAULT_TICKS = 120;

    private record Scenario(List<Double> healthy, List<Double> duringFault, List<Double> afterRecovery) {

        double peakDuringFault() {
            return duringFault.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        }
    }

    /**
     * Runs one machine healthy, then under a forced fault, then long enough to recover, and
     * returns the values of the affected signal in each stage.
     */
    private static Scenario runFault(AnomalyType type, ToDoubleFunction<TelemetryReadings> signal) {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 11L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        List<Double> healthy = values(engine.run(30), signal);
        engine.triggerAnomaly(machineId, type, FAULT_DURATION);
        List<Double> duringFault = values(engine.run(FAULT_TICKS), signal);
        List<Double> afterRecovery = values(engine.run(120), signal);

        return new Scenario(healthy, duringFault, afterRecovery);
    }

    private static List<Double> values(List<TelemetryEvent> events, ToDoubleFunction<TelemetryReadings> signal) {
        return events.stream().map(e -> signal.applyAsDouble(e.readings())).toList();
    }

    private static double nominalTemperature() {
        return MachineProfiles.forType(com.sentinel.machine.domain.MachineType.PUMP)
                .of(Signal.TEMPERATURE).nominal();
    }

    @Test
    void shouldDriveTemperaturePastTheCriticalThresholdAndBackToNominal() {
        Scenario scenario = runFault(AnomalyType.OVERHEATING, TelemetryReadings::temperatureCelsius);
        double nominal = nominalTemperature();

        assertThat(scenario.healthy()).allSatisfy(value -> assertThat(value).isLessThan(80.0));
        assertThat(scenario.peakDuringFault()).isGreaterThan(95.0);

        double settled = scenario.afterRecovery().get(scenario.afterRecovery().size() - 1);
        assertThat(settled).isCloseTo(nominal, org.assertj.core.data.Offset.offset(5.0));
    }

    /**
     * The property that makes deduplication and cooldown worth building: a single fault keeps the
     * signal abnormal across many consecutive events, rather than spiking for one.
     */
    @Test
    void shouldKeepTheSignalAbnormalAcrossManyConsecutiveEvents() {
        Scenario scenario = runFault(AnomalyType.OVERHEATING, TelemetryReadings::temperatureCelsius);

        long abnormalTicks = scenario.duringFault().stream().filter(value -> value >= 80.0).count();

        assertThat(abnormalTicks).isGreaterThan(60);
    }

    @Test
    void shouldRaiseTemperatureGraduallyRatherThanInAJump() {
        Scenario scenario = runFault(AnomalyType.OVERHEATING, TelemetryReadings::temperatureCelsius);
        List<Double> values = scenario.duringFault();

        for (int i = 1; i < values.size(); i++) {
            assertThat(Math.abs(values.get(i) - values.get(i - 1)))
                    .as("temperature step at fault tick %d", i)
                    .isLessThan(8.0);
        }
    }

    @Test
    void shouldRecoverProgressivelyRatherThanResettingInstantly() {
        Scenario scenario = runFault(AnomalyType.OVERHEATING, TelemetryReadings::temperatureCelsius);
        List<Double> recovery = scenario.afterRecovery();

        for (int i = 1; i < recovery.size(); i++) {
            assertThat(Math.abs(recovery.get(i) - recovery.get(i - 1)))
                    .as("temperature step at recovery tick %d", i)
                    .isLessThan(8.0);
        }
        assertThat(recovery.get(0)).isGreaterThan(recovery.get(recovery.size() - 1));
    }

    @Test
    void shouldDriveVibrationIntoTheCriticalBand() {
        Scenario scenario = runFault(
                AnomalyType.EXCESSIVE_VIBRATION, TelemetryReadings::vibrationMillimetresPerSecond);

        assertThat(scenario.healthy()).allSatisfy(value -> assertThat(value).isLessThan(8.0));
        assertThat(scenario.peakDuringFault()).isGreaterThan(14.0);
    }

    @Test
    void shouldDrivePowerConsumptionWellAboveNominal() {
        Scenario scenario = runFault(
                AnomalyType.POWER_OVERLOAD, TelemetryReadings::powerConsumptionKilowatts);
        double nominalPower = MachineProfiles.forType(com.sentinel.machine.domain.MachineType.PUMP)
                .of(Signal.POWER).nominal();

        assertThat(scenario.peakDuringFault()).isGreaterThan(nominalPower * 2);
    }

    @Test
    void shouldDrivePressureAboveItsAcceptableMaximum() {
        Scenario scenario = runFault(AnomalyType.PRESSURE_FAULT, TelemetryReadings::pressureBar);

        assertThat(scenario.healthy()).allSatisfy(value -> assertThat(value).isBetween(1.0, 10.0));
        assertThat(scenario.peakDuringFault()).isGreaterThan(10.0);
    }

    /** A leak is the same fault with a negative intensity, and must drive pressure down. */
    @Test
    void shouldDrivePressureBelowItsAcceptableMinimumWhenIntensityIsNegative() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 11L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        engine.triggerAnomaly(machineId, AnomalyType.PRESSURE_FAULT, FAULT_DURATION, -8.0);
        List<Double> pressures = values(engine.run(FAULT_TICKS), TelemetryReadings::pressureBar);

        assertThat(pressures.stream().mapToDouble(Double::doubleValue).min().orElseThrow())
                .isLessThan(1.0);
    }

    @Test
    void shouldLeaveUnaffectedSignalsAlone() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 11L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        engine.triggerAnomaly(machineId, AnomalyType.OVERHEATING, FAULT_DURATION);
        List<TelemetryEvent> events = engine.run(FAULT_TICKS);

        assertThat(events).allSatisfy(event -> {
            assertThat(event.readings().vibrationMillimetresPerSecond()).isLessThan(8.0);
            assertThat(event.readings().pressureBar()).isBetween(1.0, 10.0);
        });
    }

    /** The machine walks the whole progression, not just its endpoints. */
    @Test
    void shouldPassThroughEveryAnomalyPhaseInOrder() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 11L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();
        VirtualMachine machine = engine.machineOrFail(machineId);

        engine.triggerAnomaly(machineId, AnomalyType.OVERHEATING, FAULT_DURATION);

        List<AnomalyPhase> observed = new ArrayList<>();
        for (int tick = 0; tick < FAULT_TICKS + 5; tick++) {
            AnomalyPhase phase = machine.anomalyPhaseAt(engine.currentTime());
            if (observed.isEmpty() || observed.get(observed.size() - 1) != phase) {
                observed.add(phase);
            }
            engine.tick();
        }

        assertThat(observed).containsExactly(
                AnomalyPhase.DEVELOPING,
                AnomalyPhase.ACTIVE,
                AnomalyPhase.RECOVERING,
                AnomalyPhase.FINISHED);
    }

    @Test
    void shouldReportTheMachineAsHealthyOnceTheFaultHasRunItsCourse() {
        SimulationEngine engine =
                new SimulationEngine(SimulationConfig.of(1, 11L, START), new SequentialUuids());
        UUID machineId = engine.machines().get(0).id();

        engine.triggerAnomaly(machineId, AnomalyType.OVERHEATING, FAULT_DURATION);
        assertThat(engine.machineOrFail(machineId).isHealthy()).isFalse();

        engine.run(FAULT_TICKS + 1);

        assertThat(engine.machineOrFail(machineId).isHealthy()).isTrue();
    }
}
